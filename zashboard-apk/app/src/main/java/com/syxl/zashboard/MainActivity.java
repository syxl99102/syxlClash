package com.syxl.zashboard;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
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
 * Zashboard 面板壳（自带面板 + 应用内自动更新）。
 *
 * 面板文件有两份：
 *   1) APK 内置：assets/zashboard/**（离线兜底）
 *   2) 已更新副本：<应用私有目录>/files/panel/**（优先使用）
 * 启动时后台问一次 Zashboard 官方最新版本，有新版就下载官方 dist 包覆盖 (2)，页面立即刷新。
 * 所以"换面板版本"不需要重装 APK。
 */
public class MainActivity extends Activity {

    /** 假域名：全部由 shouldInterceptRequest 提供内容，不产生真实网络请求 */
    private static final String PANEL_HOST = "panel.local";
    private static final String PANEL_URL = "https://" + PANEL_HOST + "/index.html";
    private static final String ACTION_FORCE_UPDATE = "com.syxl.zashboard.FORCE_UPDATE";

    /** Zashboard 官方发布包（约 2.8 MB），走 gh-proxy 加速 */
    private static final String ZIP_URL =
            "https://gh-proxy.com/https://github.com/Zephyruso/zashboard/releases/latest/download/dist-no-fonts.zip";

    /** 取最新版本号，第一个失败就换 gh-proxy 重试 */
    private static final String[] TAG_URLS = {
            "https://api.github.com/repos/Zephyruso/zashboard/releases/latest",
            "https://gh-proxy.com/https://api.github.com/repos/Zephyruso/zashboard/releases/latest"
    };

    private static final Pattern TAG_PATTERN =
            Pattern.compile("\"tag_name\"\\s*:\\s*\"([^\"]+)\"");
    private static final String PREF_TAG = "panel_tag";
    private static final String ASSET_ROOT = "zashboard";
    private static final String PANEL_DIR = "panel";

    private WebView web;
    private SharedPreferences prefs;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("panel", MODE_PRIVATE);

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);       // 面板是 SPA
        s.setDomStorageEnabled(true);       // 面板把后端地址/密码存在 localStorage
        s.setDatabaseEnabled(true);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        // 面板是 https://panel.local，要允许它请求 http://127.0.0.1:9090
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);

        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (uri == null || !PANEL_HOST.equals(uri.getHost())) {
                    return null;            // 其它域名（内核 API）走正常网络
                }
                return serve(uri.getPath());
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                // 兜底：极少数机型上假域名可能没被拦截成功
                if (request.isForMainFrame() && PANEL_HOST.equals(request.getUrl().getHost())) {
                    view.loadDataWithBaseURL(null,
                            "<meta name=viewport content='width=device-width,initial-scale=1'>"
                                    + "<div style='font:16px/1.8 sans-serif;padding:24px'>"
                                    + "<h3>面板加载失败</h3>"
                                    + "<p>请重新打开本应用；若一直如此，请反馈机型与 Android 版本。</p></div>",
                            "text/html", "utf-8", null);
                }
            }
        });

        setContentView(web);
        web.loadUrl(PANEL_URL);

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

    // ------------------------------------------------------------------ 面板资源

    /** 优先读已更新的本地副本，其次读 APK 内置资源 */
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
        headers.put("Cache-Control", "no-cache");

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

    /** force=false 时只在版本变化时更新；force=true 时无论如何重新拉一次 */
    private void checkUpdate(final boolean force) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String latest = fetchLatestTag();
                    if (latest == null || latest.isEmpty()) {
                        return;
                    }
                    String current = prefs.getString(PREF_TAG, "");
                    if (!force && latest.equals(current)) {
                        return;
                    }
                    File zip = new File(getCacheDir(), "panel.zip");
                    download(ZIP_URL, zip);
                    File staging = new File(getFilesDir(), "panel-new");
                    deleteRecursive(staging);
                    unzipFlatten(zip, staging);
                    File target = new File(getFilesDir(), PANEL_DIR);
                    deleteRecursive(target);
                    if (!staging.renameTo(target)) {
                        throw new IOException("cannot swap panel dir");
                    }
                    zip.delete();
                    prefs.edit().putString(PREF_TAG, latest).apply();
                    toastAsync("面板已更新：" + latest);
                    reloadAsync();
                } catch (Throwable t) {
                    if (force) {
                        toastAsync("更新失败：" + t.getClass().getSimpleName());
                    }
                }
            }
        }, "panel-update").start();
    }

    private String fetchLatestTag() {
        for (String url : TAG_URLS) {
            HttpURLConnection conn = null;
            try {
                conn = open(url);
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

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(60000);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", "zashboard-shell");
        conn.setRequestProperty("Accept", "*/*");
        return conn;
    }

    private static void download(String url, File out) throws IOException {
        HttpURLConnection conn = open(url);
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

    // ------------------------------------------------------------------ 小工具

    private void toastAsync(final String text) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(MainActivity.this, text, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void reloadAsync() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (web != null) {
                    web.reload();
                }
            }
        });
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
        if (web != null) {
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}
