# Zashboard 面板壳（Android APK，自带面板 + 自动更新）

官方 Zashboard 只有 Web 版，没有 APK。这个工程是一个最小 WebView 壳，但**面板文件是打进 APK 的**，
并且 App 每次启动会去问一次 Zashboard 官方最新版本，有新版就自己下载替换 ——
**更新面板不需要重装 APK**。

## 面板从哪来

| 优先级 | 位置 | 说明 |
|---|---|---|
| 1 | `<应用私有目录>/files/panel/` | App 自己更新下来的最新版（启动时自动检查 Zashboard 官方 release） |
| 2 | APK 内置 `assets/zashboard/` | 离线兜底（当前内置的官方 dist 快照） |

面板通过假域名 `https://panel.local/` 提供（`shouldInterceptRequest` 拦截），
所以它永远是一个稳定的 https 源：localStorage（后端地址 + 密码）在更新前后都不会丢。

## 一、出 APK（GitHub Actions）

1. 把 `zashboard-apk/` 放到仓库根目录；
2. 把 `zashboard-apk/.github/workflows/build-apk.yml` 复制到**仓库根**的 `.github/workflows/`；
3. push，或到 Actions 手动点 **Build Zashboard APK → Run workflow**；
4. 跑完在该次运行的 **Artifacts** 下载 `zashboard-apk.zip`，解压得到 `app-debug.apk`；
5. 传到手机安装（首次允许"安装未知来源应用"）。

本地出包：用 Android Studio 打开 `zashboard-apk/` → `Build > Build APK(s)`。

## 二、首次打开填什么

- 后端地址：`http://127.0.0.1:9090`
- 密码：配置文件里的 `secret`（当前是 `Gemini`）

填一次就记住（存在 `https://panel.local` 的 localStorage 里，更新面板不会丢）。

## 三、怎么更新面板

- **自动**：每次启动 App，后台对比 Zashboard 官方最新 release tag，发现新版就下载替换并刷新页面（会有 Toast 提示）。网不通时静默保持当前版本。
- **手动**：长按桌面图标 → 「更新面板」（强制重新拉取一次）。

## 四、注意

1. 必须开着 CMFA 的 VPN，否则面板连不上 `127.0.0.1:9090`（面板里会显示连不上）。
2. 面板页是 `https://panel.local`、API 是 `http://127.0.0.1:9090`，属于跨域请求，
   需要 mihomo 侧放行 CORS —— 你的配置里已经是：
   ```yaml
   external-controller-cors:
     allow-origins: ['*']
     allow-private-network: true
   ```
   把这个删掉/收紧，面板就连不上 API。
3. 明文 HTTP 只对 `127.0.0.1` / `localhost` 放行（`network_security_config.xml`）。
4. 想换面板地址：改 `MainActivity.java` 里的 `PANEL_URL` / `PANEL_HOST`。
5. 想换面板来源（比如换成 metacubexd）：改 `ZIP_URL` 指向对应 dist 包即可。
6. 内置快照不会随 App 更新，只是兜底；日常用的是自动更新下来的版本。
