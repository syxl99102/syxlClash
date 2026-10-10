# Zashboard 面板壳（Android APK，自带面板 + 自动更新）

官方 Zashboard 只有 Web 版，没有 APK。这个工程是一个最小 WebView 壳，但**面板文件是打进 APK 的**，
并且 App 每次启动会去问一次 Zashboard 官方最新版本，有新版就自己下载替换 ——
**更新面板不需要重装 APK**。

当前版本：**1.2**（versionCode 3）

## 面板从哪来

| 优先级 | 位置 | 说明 |
|---|---|---|
| 1 | `<应用私有目录>/files/panel/` | App 自己更新下来的最新版（启动时自动检查 Zashboard 官方 release） |
| 2 | APK 内置 `assets/zashboard/` | 离线兜底（当前内置的官方 dist 快照） |

面板通过假域名 `https://panel.local/` 提供（`shouldInterceptRequest` 拦截），
所以它永远是一个稳定的 https 源：localStorage（后端地址 + 密码）在更新前后都不会丢。

## 1.2 改了什么

**修 bug：系统是日间、App 却是深色**

- 原因：网页里的 `prefers-color-scheme` 跟着 App 主题走。原来用的是
  `Theme.DeviceDefault.NoActionBar`（深色主题），所以网页一直被告知“系统是深色”，Zashboard 就一直画成黑的。
- 现在：日间用 `Theme.DeviceDefault.Light.NoActionBar`（`res/values/themes.xml`），
  夜间用深色主题（`res/values-night/themes.xml`），系统切深浅色时 Activity 自动重建、重新读主题。
- 另外还关掉了两处“强制深色”：`View.setForceDarkAllowed(false)`
  （防 One UI / HyperOS 的深色模式二次反色）和 WebView 的
  `setForceDark(FORCE_DARK_OFF)` / `setAlgorithmicDarkeningAllowed(false)`。

**性能 / 省电 / 内存**

| 改动 | 作用 |
|---|---|
| `onStop` → `pauseTimers()`，`onStart` → `resumeTimers()` | 进后台彻底冻结 JS，面板不再靠 WebSocket 刷新界面（最省电的一条） |
| `onPause` → `onPause()`，`onResume` → `onResume()` | 不可见时停渲染、停动画 |
| 自动检查版本改成 **6 小时节流**，成功后记录时间 | 不再每次启动都打 GitHub API，省电、避开匿名限流 |
| 关掉缩放、定位、file:// 访问、content 访问、新窗口、离屏预栅格 | 少占内存、少被探测、页面上不会误触缩放 |
| `onTrimMemory` / `onLowMemory` → `freeMemory()`，必要时 `clearCache(false)` | 系统吃紧时主动还内存，降低被后台杀的几率 |
| `onRenderProcessGone` → 原地重建 WebView | 地球卡片这类 WebGL 场景把渲染进程搞崩时，App 不会跟着闪退 |
| 带内容哈希的资源长缓存，`index.html` 仍 `no-cache` | 换版本立即生效，静态资源不重复读 |
| 窗口底色跟主题一致（`window_bg`） | 启动时不再白闪/黑闪 |

## 一、出 APK（GitHub Actions）

1. 把 `zashboard-apk/` 放到仓库根目录；
2. 把 `zashboard-apk/.github/workflows/build-apk.yml` 复制到**仓库根**的 `.github/workflows/`；
3. push，或到 Actions 手动点 **Build Zashboard APK → Run workflow**；
4. Release 里会直接出 `app-debug.apk`
   （直链：`https://github.com/syxl99102/syxlClash/releases/latest/download/app-debug.apk`），
   该次运行的 Artifacts 里也有一份备用；
5. 传到手机安装（首次允许“安装未知来源应用”）。

> **签名提醒**：CI 用的是**调试签名**，全新 runner 会生成新的调试密钥。
> workflow 里已用 `actions/cache` 缓存 `~/.android/debug.keystore`，命中缓存时签名不变、可以直接覆盖安装；
> 一旦提示“应用未安装 / 签名不一致”，**先卸载旧版再装新版**即可（面板里重新填一次地址和密码）。

本地出包：用 Android Studio 打开 `zashboard-apk/` → `Build > Build APK(s)`。

## 二、首次打开填什么

- 后端地址：`http://127.0.0.1:9090`
- 密码：配置文件里的 `secret`（当前是 `Gemini`）

填一次就记住（存在 `https://panel.local` 的 localStorage 里，更新面板不会丢）。

## 三、怎么更新面板

- **自动**：每次启动 App（且距上次检查超过 6 小时），后台对比 Zashboard 官方最新 release tag，
  发现新版就下载替换并刷新页面（会有 Toast 提示）。网不通时静默保持当前版本，下次再试。
- **手动**：长按桌面图标 → 「更新面板」（强制重新拉取一次，不受 6 小时限制）。

## 四、省电 / 省内存还可以这样调（面板里）

- 面板设置里把**刷新间隔调大**（比如 2~5 秒），流量统计改用 **WebSocket** 而不是轮询。
- 关掉**地球 / 3D 球体卡片**等动态组件：那是 WebGL 场景，最吃 GPU 和内存，也是渲染进程崩溃的头号原因。
- 关掉背景动态效果 / 毛玻璃，面板会更轻。

## 五、注意

1. 必须开着 CMFA 的 VPN，否则面板连不上 `127.0.0.1:9090`（面板里会显示连不上）。
2. 面板页是 `https://panel.local`、API 是 `http://127.0.0.1:9090`，属于跨域请求，
   需要 mihomo 侧放行 CORS —— 你的配置里已经是 `allow-origins: ['*']` + `allow-private-network: true`，
   把这个删掉 / 收紧，面板就连不上 API。
3. 明文 HTTP 只对 `127.0.0.1` / `localhost` 放行（`network_security_config.xml`）。
4. 如果 **HyperOS（小米 / 红米）** 的“深色模式 → 更多深色模式设置”把本应用勾上了，它会自己反色；
   把「Zashboard 面板」那一项关掉即可。One UI（三星）同理：设置 → 显示 → 深色模式。
5. 想换面板地址：改 `MainActivity.java` 里的 `PANEL_URL` / `PANEL_HOST`。
6. 想换面板来源（比如换成 metacubexd）：改 `ZIP_URL` 指向对应 dist 包即可。
7. 内置快照不会随 App 更新，只是兜底；日常用的是自动更新下来的版本。
