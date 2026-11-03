# 移动签到助手（第一阶段：抓取版）

LSPosed 模块，用于抓取中国移动 App（`com.greenpoint.android.mc10086.activity`，测试版本 12.5.6）里
签到、福利中心、任务中心相关的页面与接口，为下一阶段的自动签到 / 一键领奖励提供依据。

**当前版本只抓取、不改动 App 行为**。首页是模块状态与抓取开关，日志页支持刷新、清空、导出到 Download。

## 环境要求

- Android 8.1 及以上，已安装并激活 LSPosed（或兼容的 Xposed 框架）
- 中国移动 App 12.5.6（其他版本大多也能用，但接口可能不同）
- 不需要 root 权限给本 App；root/LSPosed 由框架提供

## 安装与启用

1. 安装 `cmcc-hook-v1.2-capture.apk`（固定签名，可直接安装）。
   - v1.1 起使用仓库内固定 keystore 签名，之后的版本都能直接覆盖安装。
   - 如果系统提示「签名不一致」，说明旧版是临时 CI 调试签名：先卸载旧版再安装新版即可（旧日志已导出，不影响）。
2. 打开 LSPosed 管理器 → 模块 → 启用「移动签到助手」。
3. 点开作用域，**只勾选「中国移动」**（`com.greenpoint.android.mc10086.activity`）。
4. 在系统设置里强制停止中国移动 App，或重启手机，让模块重新注入。
5. 打开一次中国移动 App，再回到本模块首页：
   - 状态显示「运行中」，并且有「最近心跳」，说明注入成功；
   - 若一直「等待中国移动 App 启动」，检查 LSPosed 作用域、模块是否启用、系统是否限制后台。

## 抓取步骤（请按这个顺序操作）

1. 打开中国移动 App，进入 **我的 → 福利中心**（或你平时领奖励的入口）。
2. 把希望自动化的操作都手动做一遍，例如：
   - 每日签到 / 连续签到
   - 福利中心里的签到任务、浏览任务、领积分 / 领金币、领优惠券
   - 任务中心里能完成的任务，以及「领取」「开宝箱」「抽奖」等按钮
   - 会员权益、等级奖励等
3. 每进入一个页面停留 1～2 秒，等 H5 把数据请求发完；点按钮后也稍等再返回。
4. 回到本模块 → **查看日志 → 刷新**，确认能看到 `H5` / `HTTP` / `ACTIVITY` 等条目。
5. 点 **导出到 Download**，文件位于：

   ```
   Download/CmccHook/cmcc-hook-YYYYMMDD-HHMMSS.txt
   ```

6. 把这个 txt 发回来。日志里会包含账号 Cookie / Token / 手机号，**请只发给可信的人，不要上传公开网盘或群聊**。

## 抓不到请求时

- 先确认首页有「最近心跳」，日志里有 `APP-START`。没有心跳就是模块没注入，与抓取逻辑无关。
- 关闭首页的「仅记录 10086 / cmcc 域名」开关再试一次；有些请求会走其他域名。
- 默认只注入主进程；加固壳的 `:tools` 安全进程始终跳过。需要抓推送 / X5 沙盒进程时，可在首页打开「注入其他进程」。
- 如果只看到 `ACTIVITY` / `WEBVIEW` 而没有 `HTTP` / `H5`，可能是加固壳使用了自研网络库或系统
  `HttpURLConnection`。把现有日志发回来，下一版再针对性补 Hook。
- MIUI / ColorOS / OriginOS 等系统请给本模块和中国移动 App 允许自启动、后台运行、无省电限制，
  否则模块进程可能被冻结，广播日志会丢失。

## 日志里有什么

| 标签 | 含义 |
| --- | --- |
| `APP-START` | 模块注入目标进程成功，含设备、版本、进程名 |
| `SYSTEM` | 心跳 / 模块状态 |
| `HTTP` | OkHttp 请求与响应（URL、方法、请求头、请求体、状态码、响应头、响应体） |
| `HTTP-REQ` | 只抓到请求、响应未被拦截时的兜底记录 |
| `HTTP-ERROR` | 网络异常 |
| `WEBVIEW` | WebView / X5 页面加载、跳转、注册的 JS 桥 |
| `H5` | H5 内 XHR / fetch / sendBeacon 的请求与响应 |
| `H5-PAGE` | H5 页面快照：URL、标题、Cookie、localStorage、sessionStorage、可点击控件 |
| `H5-CLICK` | H5 点击的控件文本 / id / class / href |
| `H5-ROUTE` | 单页应用路由变化 |
| `H5-ERROR` | H5 脚本错误 |
| `COOKIE` | WebView / X5 Cookie 读写快照 |
| `ACTIVITY` | 原生页面打开与新 Intent（含 extras） |
| `CRASH` | 未捕获异常：线程、进程、完整堆栈（排查闪退） |
| `EXIT` | `System.exit` / `Process.killProcess` / `Runtime.halt` 的调用堆栈 |
| `EXIT-INFO` | 上一次进程退出的系统记录：Java 崩溃 / native 崩溃 / 被信号杀死，附 tombstone |
| `HOOK` | 晚加载类探测结果（OkHttp / X5 是否补装成功） |

日志保存在 App 私有目录，单文件上限 10 MB，超出后自动轮转为 `hook.log.1`；导出的 txt 是可读全量文本。
「清空」会删除本机两份日志文件。

## 隐私与安全

- 模块不上传任何数据，日志只写入本机；是否导出由你决定。
- 日志含有登录态（Cookie / Token / Authorization / 手机号），泄露等同于账号被他人使用。
- 本工具仅用于抓取你自己账号在你自己的设备上的操作数据；自动化操作可能有账号风控风险，请自行评估。
- 源码和 APK 中不包含任何账号、手机号或 token。

## 从源码构建

### GitHub Actions

仓库自带 `.github/workflows/build.yml`：

- push 到 `main` 或手动触发 `Build APK`；
- 自动使用 JDK 17 + Gradle 8.10 + Android SDK 34 构建；
- 构建产物在 Actions 运行页面的 `cmcc-hook-debug-apk` artifact 中，保留 30 天；
- 使用仓库内 `keystore/cmcc-hook.jks` 固定签名，连续构建可互相覆盖安装。

### 本地构建

- JDK 17+，Android SDK Platform 34 / Build-Tools 34.0.0
- 新建 `local.properties`：`sdk.dir=/path/to/android-sdk`
- 执行：`gradle assembleDebug`
- 产物：`app/build/outputs/apk/debug/app-debug.apk`
- Xposed API 82 的编译依赖已放在 `app/libs/XposedBridgeAPI-82.jar`

## 代码结构

```
app/src/main/java/com/qoder/cmcchook/
  HookEntry.java         模块入口，按进程安装 Hook
  ClassLoaderHooks.java  后台探测晚加载的 OkHttp / X5 类并补装 Hook
  ActivityHooks.java     Application / Activity / Intent
  CrashHooks.java        未捕获异常捕获（CRASH 堆栈）
  WebViewHooks.java      WebView / X5 的加载、页面回调、Cookie
  JsInjector.java        注入 H5 抓取脚本与 JS Bridge
  JsBridge.java          把 H5 数据转成日志
  OkHttpHooks.java       OkHttp 2/3 请求响应（不再 hook 系统 com.android.okhttp）
  HookLog.java           通过显式广播把日志送到模块 App
  LogReceiver.java       接收日志并落盘
  LogStore.java          日志存储 / 轮转 / 读取
  LogActivity.java       日志页：刷新 / 清空 / 导出
  LogExporter.java       导出到 Download/CmccHook
  MainActivity.java      首页：状态、抓取开关、快捷入口
```

## 下一阶段

拿到导出的日志后，将补全功能页：自动签到、福利中心一键领取、任务中心批量完成等，
并用你提供的账号信息验证接口参数与签名逻辑。
