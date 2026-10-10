package com.syxl.zashboard;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ComponentCallbacks2;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.webkit.ConsoleMessage;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Zashboard 面板壳：APK 内置面板 + 应用内自动更新。
 *
 * 面板文件有两份：
 *   1) APK 内置：assets/zashboard/**（离线兜底）
 *   2) 已更新副本：<应用私有目录>/files/panel/**（优先使用）
 * 启动时后台问一次 Zashboard 官方最新版本，有新版就下载官方 dist 包覆盖 (2)，页面立即刷新。
 * 所以"换面板版本"不需要重装 APK。
 *
 * 配色：网页里的 prefers-color-scheme 是跟着 App 主题走的（见 res/values/themes.xml），
 * 所以日间主题必须是 .Light 系列；系统切深浅色时本 Activity 会自动重建、重新读取主题。
 */
public class MainActivity extends Activity {

    /** 假域名：内容全部由 shouldInterceptRequest 提供，不会真的联网 */
    private static final String PANEL_HOST = "panel.local";
    private static final String PANEL_URL = "https://" + PANEL_HOST + "/index.html";
    private static final String ACTION_FORCE_UPDATE = "com.syxl.zashboard.FORCE_UPDATE";

    /** Zashboard 官方发布包（约 2.8 MB），走 gh-proxy 加速 */
    private static final String ZIP_URL =
            "https://gh-proxy.com/https://github.com/Zephyruso/zashboard/releases/latest/download/dist-no-fonts.zip";

    /** 取最新版本号，第一个地址失败就换 gh-proxy 重试 */
    private static final String[] TAG_URLS = {
            "https://api.github.com/repos/Zephyruso/zashboard/releases/latest",
            "https://gh-proxy.com/https://api.github.com/repos/Zephyruso/zashboard/releases/latest"
    };

    private static final Pattern TAG_PATTERN =
            Pattern.compile("\"tag_name\"\\s*:\\s*\"([^\"]+)\"");

    /** 文件名里带内容哈希的静态资源（Zashboard 构建产物全是这种），可以长期缓存 */
    private static final Pattern HASHED_FILE =
            Pattern.compile("-[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9]+$");

    private static final String FALLBACK_HTML =
            "<meta name=viewport content='width=device-width,initial-scale=1'>"
                    + "<div style='font:16px/1.8 sans-serif;padding:24px'>"
                    + "<h3>面板加载失败</h3>"
                    + "<p>请重新打开本应用；若一直如此，请反馈机型与 Android 版本。</p></div>";

    private static final String PREF_TAG = "panel_tag";
    private static final String PREF_CHECK_AT = "check_at";
    /** 自动检查间隔：6 小时（省电、省流量，也避开 GitHub API 的匿名限流） */
    private static final long CHECK_INTERVAL_MS = 6L * 60L * 60L * 1000L;

    private static final String ASSET_ROOT = "zashboard";
    private static final String PANEL_DIR = "panel";

    private WebView web;
    private SharedPreferences prefs;
    /** 是否有更新线程在跑，避免重复起线程 */
    private volatile boolean updating;
    /** Activity 是否还活着，更新线程靠它决定要不要回写 UI */
    private volatile boolean alive;
    /** 跟当前主题一致的底色，用来消掉启动/切换时的白闪、黑闪 */
    private int themeBg;
    /** 渲染进程崩溃后原地重建的次数，防止无限重建 */
    private int recovered;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        alive = true;
        prefs = getSharedPreferences("panel", MODE_PRIVATE);
        themeBg = getResources().getColor(R.color.window_bg, getTheme());
        getWindow().getDecorView().setBackgroundColor(themeBg);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // 不让系统/厂商（One UI、HyperOS 的深色模式）二次反色我们的界面
            getWindow().getDecorView().setForceDarkAllowed(false);
        }

        buildWebView();

        checkUpdate(ACTION_FORCE_UPDATE.equals(getIntent().getAction()));
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent != null && ACTION_FORCE_UPDATE.equals(intent.getAction())) {
            checkUpdate(true);
        }
    }

    // ------------------------------------------------------------------ WebView

    /** 建 WebView + 塞进窗口 + 打开面板（渲染进程崩溃后也靠它原地重建） */
    private void buildWebView() {
        web = createWebView();
        setContentView(web);
        web.loadUrl(PANEL_URL);
    }

    @SuppressLint("SetJavaScriptEnabled")
    @SuppressWarnings("deprecation")
    private WebView createWebView() {
        WebView w = new WebView(this);
        w.setBackgroundColor(themeBg);
        w.setOverScrollMode(View.OVER_SCROLL_NEVER);   // 去掉边缘回弹，少一点无谓重绘

        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);          // 面板是单页应用
        s.setDomStorageEnabled(true);          // 后端地址 / 密码存在 localStorage
        s.setDatabaseEnabled(true);            // 面板还用 IndexedDB 缓存数据
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setDefaultTextEncodingName("utf-8");
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);  // https://panel.local 要请求 http://127.0.0.1:9090
        s.setMediaPlaybackRequiresUserGesture(false);

        // 用不到的能力全部关掉：省内存、少被探测、也更快
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setGeolocationEnabled(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setOffscreenPreRaster(false);

        // 关键：关掉 WebView 自己的"强制深色"，颜色完全听网页的（面板自带深色模式）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                && Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            s.setForceDark(WebSettings.FORCE_DARK_OFF);         // Android 10 ~ 12
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            s.setAlgorithmicDarkeningAllowed(false);            // Android 13+
        }

        w.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage cm) {
                return true;        // 丢掉面板的 console 输出，省一点 logcat 开销
            }
        });

        w.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                try {
                    Uri uri = request.getUrl();
                    if (uri == null || !PANEL_HOST.equals(uri.getHost())) {
                        return null;        // 其它域名（内核 API）走正常网络
                    }
                    return serve(uri.getPath());
                } catch (Throwable t) {
                    return null;
                }
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                // 兜底：极少数机型上假域名可能没被拦截成功
                if (request.isForMainFrame() && PANEL_HOST.equals(request.getUrl().getHost())) {
                    view.loadDataWithBaseURL(null, FALLBACK_HTML, "text/html", "utf-8", null);
                }
            }

            @Override
            public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                // 渲染进程挂了（多半是地球卡片这类 WebGL 场景吃内存被系统杀）：
                // 自己重建 WebView，别让整个 App 跟着崩。
                if (view != null) {
                    view.destroy();
                }
                web = null;
                if (++recovered > 3) {
                    toastAsync("页面反复崩溃，请重新打开应用");
                    finish();
                    return true;
                }
                buildWebView();
                toastAsync("页面已自动恢复");
                return true;            // true = 已处理，系统不要杀进程
            }
        });

        return w;
    }

    // ------------------------------------------------------------------ 面板资源

    /** 优先读已更新的本地副本（files/panel），没有再读 APK 内置的 assets/zashboard */
    private WebResourceResponse serve(String rawPath) {
        String path = rawPath == null ? "" : rawPath;
        while (path.startsWith("/")) {
            path = path.substring(1);
        }
        if (path.isEmpty() || path.endsWith("/")) {
            path = path + "index.html";
        }
        if (path.contains("..")) {
            return notFound();
        }

        String mime = mimeOf(path);
        String enc = isText(mime) ? "utf-8" : null;
        Map<String, String> headers = new HashMap<>();
        // 带内容哈希的文件名可以长期缓存；index.html / manifest 每次校验，保证换版本立刻生效
        headers.put("Cache-Control", HASHED_FILE.matcher(path).find()
                ? "public, max-age=31536000, immutable"
                : "no-cache");

        try {
            File updated = new File(new File(getFilesDir(), PANEL_DIR), path);
            if (updated.isFile()) {
                return new WebResourceResponse(mime, enc, 200, "OK", headers,
                        new FileInputStream(updated));
            }
            return new WebResourceResponse(mime, enc, 200, "OK", headers,
                    getAssets().open(ASSET_ROOT + "/" + path));
        } catch (IOException e) {
            return notFound();
        }
    }

    private WebResourceResponse notFound() {
        Map<String, String> headers = new HashMap<>();
        headers.put("Cache-Control", "no-store");
        return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found", headers,
                new ByteArrayInputStream(new byte[0]));
    }

    private static boolean isText(String mime) {
        return mime.startsWith("text/") || mime.contains("javascript")
                || mime.contains("json") || mime.contains("svg");
    }

    private static String mimeOf(String path) {
        String p = path.toLowerCase(Locale.ROOT);
        if (p.endsWith(".html") || p.endsWith(".htm")) return "text/html";
        if (p.endsWith(".js") || p.endsWith(".mjs")) return "application/javascript";
        if (p.endsWith(".css")) return "text/css";
        if (p.endsWith(".json") || p.endsWith(".webmanifest")) return "application/json";
        if (p.endsWith(".svg")) return "image/svg+xml";
        if (p.endsWith(".png")) return "image/png";
        if (p.endsWith(".jpg") || p.endsWith(".jpeg")) return "image/jpeg";
        if (p.endsWith(".webp")) return "image/webp";
        if (p.endsWith(".ico")) return "image/x-icon";
        if (p.endsWith(".woff2")) return "font/woff2";
        if (p.endsWith(".woff")) return "font/woff";
        if (p.endsWith(".ttf")) return "font/ttf";
        if (p.endsWith(".txt") || p.endsWith(".md")) return "text/plain";
        return "application/octet-stream";
    }

    // ------------------------------------------------------------------ 面板更新

    /** force=false：距上次检查不足 CHECK_INTERVAL_MS 就跳过；force=true：长按图标的「更新面板」 */
    private void checkUpdate(final boolean force) {
        if (updating) {
            return;                     // 已经在检查/下载了，别重复起线程
        }
        if (!force
                && System.currentTimeMillis() - prefs.getLong(PREF_CHECK_AT, 0L) < CHECK_INTERVAL_MS) {
            return;                     // 省电：6 小时内不再问 GitHub
        }
        updating = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String latest = fetchLatestTag();
                    if (latest == null || latest.isEmpty()) {
                        return;         // 没网/被墙：不记时间，下次启动再试
                    }
                    prefs.edit().putLong(PREF_CHECK_AT, System.currentTimeMillis()).apply();
                    if (!force && latest.equals(prefs.getString(PREF_TAG, ""))) {
                        return;         // 已经是最新版
                    }
                    File zip = new File(getCacheDir(), "panel.zip");
                    download(ZIP_URL, zip);
                    File staging = new File(getFilesDir(), "panel-new");
                    deleteRecursive(staging);
                    unzipFlatten(zip, staging);
                    if (!alive) {
                        deleteRecursive(staging);
                        return;
                    }
                    File target = new File(getFilesDir(), PANEL_DIR);
                    deleteRecursive(target);
                    if (!staging.renameTo(target)) {
                        throw new IOException("cannot swap panel dir");
                    }
                    zip.delete();
                    prefs.edit().putString(PREF_TAG, latest).apply();
                    toastAsync("面板已更新：" + latest);
                    reloadAsync(true);
                } catch (Throwable t) {
                    if (force) {
                        toastAsync("更新失败：" + t.getClass().getSimpleName());
                    }
                } finally {
                    updating = false;
                }
            }
        }, "panel-update").start();
    }

    private String fetchLatestTag() {
        for (String url : TAG_URLS) {
            HttpURLConnection conn = null;
            try {
                conn = open(url, 15000);
                if (conn.getResponseCode() != 200) {
                    continue;
                }
                Matcher m = TAG_PATTERN.matcher(readAll(conn.getInputStream()));
                if (m.find()) {
                    return m.group(1);
                }
            } catch (Throwable ignored) {
                // 换下一个地址
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        }
        return null;
    }

    private static HttpURLConnection open(String url, int readTimeoutMs) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(readTimeoutMs);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", "zashboard-shell");
        conn.setRequestProperty("Accept", "*/*");
        return conn;
    }

    private static void download(String url, File out) throws IOException {
        HttpURLConnection conn = open(url, 120000);
        try {
            if (conn.getResponseCode() != 200) {
                throw new IOException("HTTP " + conn.getResponseCode());
            }
            InputStream in = conn.getInputStream();
            OutputStream os = new FileOutputStream(out);
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) {
                os.write(buf, 0, n);
            }
            os.close();
            in.close();
        } finally {
            conn.disconnect();
        }
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            bos.write(buf, 0, n);
        }
        in.close();
        return new String(bos.toByteArray(), "UTF-8");
    }

    /** 解压并展平顶层目录（官方包是 dist/xxx），保证 index.html 在根上 */
    private static void unzipFlatten(File zipFile, File destDir) throws IOException {
        ZipFile zf = new ZipFile(zipFile);
        try {
            String prefix = "";
            Enumeration<? extends ZipEntry> it = zf.entries();
            if (it.hasMoreElements()) {
                String first = it.nextElement().getName();
                if (first.contains("/")) {
                    prefix = first.substring(0, first.indexOf('/') + 1);
                }
            }
            it = zf.entries();
            while (it.hasMoreElements()) {
                ZipEntry e = it.nextElement();
                String name = e.getName();
                if (name.startsWith(prefix)) {
                    name = name.substring(prefix.length());
                }
                if (name.isEmpty() || name.contains("..")) {
                    continue;
                }
                File out = new File(destDir, name);
                if (e.isDirectory()) {
                    out.mkdirs();
                    continue;
                }
                File parent = out.getParentFile();
                if (parent != null) {
                    parent.mkdirs();
                }
                InputStream in = zf.getInputStream(e);
                OutputStream os = new FileOutputStream(out);
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) {
                    os.write(buf, 0, n);
                }
                os.close();
                in.close();
            }
        } finally {
            zf.close();
        }
    }

    private static void deleteRecursive(File f) {
        if (f == null || !f.exists()) {
            return;
        }
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File c : children) {
                    deleteRecursive(c);
                }
            }
        }
        f.delete();
    }

    // ------------------------------------------------------------------ 生命周期 / 省电

    @Override
    protected void onStart() {
        super.onStart();
        if (web != null) {
            web.resumeTimers();     // 与 onStop 里的 pauseTimers 配对
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) {
            web.onResume();
        }
    }

    @Override
    protected void onPause() {
        if (web != null) {
            web.onPause();          // 告诉 WebView 自己不可见了：停动画、停渲染
        }
        super.onPause();
    }

    @Override
    protected void onStop() {
        if (web != null) {
            // 进后台就冻结 JS：面板靠 WebSocket 收数据，冻结后不再刷新界面，
            // 省电、省 CPU；回前台会自动恢复（不会丢后端地址/密码）。
            web.pauseTimers();
        }
        super.onStop();
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        WebView w = web;
        if (w == null) {
            return;
        }
        if (level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE) {
            w.clearCache(false);    // 磁盘缓存丢了也能重建
            w.freeMemory();
        } else if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            w.freeMemory();
        }
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        if (web != null) {
            web.freeMemory();
        }
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) {
            web.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        alive = false;
        if (web != null) {
            web.stopLoading();
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }

    // ------------------------------------------------------------------ 小工具

    private void toastAsync(final String text) {
        if (!alive) {
            return;
        }
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (alive) {
                    Toast.makeText(MainActivity.this, text, Toast.LENGTH_LONG).show();
                }
            }
        });
    }

    /** 换了面板文件之后刷新页面；clearMemoryCache=true 时顺手清掉内存缓存，避免用到旧资源 */
    private void reloadAsync(final boolean clearMemoryCache) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (web != null) {
                    if (clearMemoryCache) {
                        web.clearCache(true);
                    }
                    web.reload();
                }
            }
        });
    }
}
