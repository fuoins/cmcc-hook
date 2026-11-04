# 移动签到助手（第一阶段：抓取版）

LSPosed 模块，用于抓取中国移动 App（`com.greenpoint.android.mc10086.activity`）里
签到、福利中心、任务中心相关的页面与接口，为下一阶段的自动签到 / 一键领奖励提供依据。

v1.3 起内置**去升级弹窗**，适配 11.9.1（versionCode 119101）；抓取相关 Hook 不区分版本，12.x 同样可用。

**当前版本只抓取、不改动 App 行为**。首页是模块状态与抓取开关，日志页支持刷新、清空、导出到 Download。

## 环境要求

- Android 8.1 及以上，已安装并激活 LSPosed（或兼容的 Xposed 框架）
- 中国移动 App 11.9.1 或 12.x（接口可能随版本变化）
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

## 去升级弹窗

v1.3 起自动拦截升级弹窗，不需要额外配置。

11.9.1 的升级对话框有个已知问题：`AppUpgradeDialogManager.showDialog` 最终会走到
`android.media.QuourstGlaernong.<init>`，这个类被改名后构造函数直接抛异常，**弹窗一创建就闪退**。
模块不去修那个类，而是在调用链的四层入口全部设卡：

| 层级 | 类#方法 |
| --- | --- |
| ① | `UpgradeDialogChecker#process` |
| ② | `CheckVersionHelper#showUpgradeDialog` |
| ③ | `AppUpgradeManager#showUpgradeDialog` |
| ④ | `AppUpgradeDialogManager#showDialog` |

任意一层命中就 return，所以首页滑动触发的自动检查、设置页手动点「检查更新」都不会漏，
也不会再走到那个会崩的构造函数。

同时 `CheckVersionHelper#checkVersion` 等检查入口也被挡掉，「发现新版本」的红点不会出现。
如果发现「关于」页版本号显示异常，把 `UpdateHooks` 里的 `CHECK_ENTRIES` 循环注释掉即可，
只保留弹窗拦截。

日志里搜 `update hook` 可以看到命中情况：

```
update hook com.cmccit.appupgrade.utils.AppUpgradeDialogManager x1 showDialog=1
update hooks ready, classes=4
```

## H5 埋点噪音

H5 控制台经常刷这一条：

```
监听到dom改变后初始化曝光失败 TypeError: Cannot read properties of undefined (reading 'getAttribute')
来源：https://h.app.coc.10086.cn/ngpsie/psieappaijssdk/collectData/1.0.0/index.js
```

这是中国移动的 psie 曝光埋点 SDK：它注册 MutationObserver 监听 DOM，节点被移除后仍拿旧引用调
`getAttribute`，自己 catch 住再打一条 WARNING。**属于 SDK 自身缺陷，不影响页面功能**，
但 DOM 每变一次就刷一条，会把有用日志淹掉。

v1.4 起默认过滤：

- 来源含 `psie`、消息含 `初始化曝光失败`、或 `Cannot read … undefined … getAttribute` 的，
  直接丢弃，并让 `onConsoleMessage` 返回 true —— 连 App 自己打到 logcat 的 `chromium` 也一起消掉；
- 被丢弃的条数每 60 秒汇总一条 `H5-NOISE`，不会让你误以为 Hook 失效；
- 其余 H5 console 同一条消息 10 秒内只记一次，重复次数写在详情里。

设置页的「过滤 H5 埋点噪音」可以关掉，关掉后逐条原样记录。

## 界面

v1.5 起按「首页 / 功能 / 日志」三个 tab 组织，M3 卡片布局：

- **首页**：运行状态（心跳、目标版本、已抓条数）、快捷操作、使用说明、关于
- **功能**：抓取范围、过滤、调试、模块四组开关，行内开关下方带说明，改动即时生效
- **日志**：顶部「刷新 / 清空 / 导出」，下方按 tag 筛选（全部 / 原生接口 / H5 / 页面 / Cookie / 错误），
  点条目展开完整请求响应；每 2.5 秒自动刷新，往上翻历史时不会被拉回底部

深色模式自动跟随系统。

### 详细原始返回

默认关闭，单条 detail 截到 **8 KB**；打开后整段输出（最多 160 KB）。

对接新接口、核对字段时才打开。平时关着，单个大响应不会把日志文件撑爆，
列表也不会因为一条几十万字的 JSON 卡住。

## 任务引擎（v2.0 起）

抓包只是手段，真正想要的是「不用管，它自己跑」。v1.6 起模块自带任务引擎，
v2.0 改成**后台跑**：开关打开，模块自己在目标进程里跑完，不用进活动页、
不用切到中国移动，界面完全不动。

### 怎么触发

| 触发方式 | 说明 |
|---|---|
| **后台广播（主路径）** | 模块点按钮 → 发显式广播 → 中国移动进程里常驻的 receiver 收到就直接跑，界面不动、不切 App |
| **定时补跑** | 目标进程每 30 分钟检查一次，开关开着、当天没跑过的活动自动补跑（7:00–23:00） |
| **App 前台补跑** | 中国移动被拉到前台时再补一次（onResume / onNewIntent 都挂） |
| **进活动页** | 打开签到 / 砸蛋 / 鹅蛋页 2~3 秒后触发，兜底 |
| **手动** | 「立即全部跑一次」按钮 |

同一天同一个活动成功跑过之后，自动触发会跳过；手动按钮不受限制。

**关键点：不需要进活动页。** 签到和砸金蛋是纯接口；浏览任务和鹅蛋页用一个
不挂到窗口的离屏 WebView 加载，Cookie、UA、内核都是 App 自己那一套，
服务端看起来和正常浏览一样，但用户界面一点没动。

### 每个活动到底跑哪些任务

抓包看到的活动页任务，模块分三类处理：

| 任务类型 | 例子 | 模块怎么做 |
|---|---|---|
| **签到 / 领奖类** | 每日签到、累计 7 天、连签、组队共签、月签 | 原生 HTTP 直连，服务端幂等 |
| **浏览类** | 「查询我的套餐」「查查我的余量」「浏览话费余额」 | 离屏 WebView 加载任务页停留 7 秒，页面自己上报埋点 |
| **增长类** | 充值 10 元、装宽带、订阅小程序、去头条刷视频 | **不做**，涉及真实交易或跳第三方 App |

砸金蛋的 6 个浏览任务全部走 `startBrowse`，服务端 302 就算登记成功，不用真打开落地页。
鹅蛋页的任务在国密加密的接口里，只能在页面内注入脚本，按文案点「打卡 / 领饲料 / 投喂 / 收蛋 / 领取」。

### 三个活动的实现方式

| 活动 | 活动 ID | 怎么做的 | 自动触发条件 |
|---|---|---|---|
| 签到有礼 | 1021122301 | 原生 HTTP 直连 `qwhdhub/api/mark/*`；浏览任务走离屏 WebView | 广播 / 定时 / 进页面 |
| 天天砸金蛋 | 1026071510 | 原生 HTTP 直连 `qwhdhub/lottery/*` + `task/*` | 广播 / 定时 / 进页面 |
| 收鹅蛋换好礼 | goose2025 | 离屏 WebView 加载活动页 + 注入脚本点按钮 | 广播 / 定时 / 进页面 |

前两个是明文 JSON，服务端只校验会话和 Referer，模块直接发就行。
第三个不是同一个体系：接口在 `lyh.lncmcc.com/huodong-portal`，
请求体是国密加密后的 `sinoEnParams`、响应是 `dataEn`，URL 上还有瑞数（`$_ts`）动态签名，
每次请求的 `LJRkIeoW` 都不一样。要本地重放，得把整套瑞数 VM 一起搬过来 ——
成本远大于收益，而且风控一升级就全废。

所以收鹅蛋换了个思路：不自己发请求，等页面打开后注入脚本，
让页面自己去点。加密和签名全交给页面自己的 JS，模块只负责触发和收结果。

### 界面上怎么用

「功能」页最上面的「活动任务」卡片：

- **签到有礼** 开关：打开签到页自动跑
- **天天砸金蛋** 开关：打开砸蛋页自动跑
- **收鹅蛋换好礼** 开关：打开鹅蛋页自动跑
- **打开 App 就跑全部** 开关：不依赖活动页，默认关
- **立即全部跑一次** 按钮：手动串行跑三个

### 签到有礼做什么

```
SSO 换 token（/qwhdsso/login?dlwmh=true&actUrl=…）
  → markstatus 查状态
  → domark 签到（HAVE_MARKED 视为已签）
  → 再查一次，领 taskAwardChance 里的累计/连签奖励
  → 组团共签（status=3 才领）
  → 连续月签（monthStatus=UNDO 且 hasPrize）
  → task/taskList 里 status=2 的任务，逐个 getTaskAward 领奖
  → 剩下 status=0 的浏览任务：jumpUrl 在 WebView 打开停留 7 秒，回到签到页
  → 再拉一次 taskList，把新完成的奖励领掉
```

浏览任务最多做 5 个，每个 8 秒左右；遇到「充值 / 装宽带 / 办理 / 订阅」直接跳过。

### 天天砸金蛋做什么

```
lottery/remain 查剩余次数
  → task/list 拿任务清单，对 taskStage=UNDO 的逐个 GET startBrowse（+1 次）
  → 重新查次数
  → 按次数循环 lottery/lotterySafely，直到次数用完
```

`startBrowse` 是服务端 302 到落地页，模块只要把请求发出去就登记成功了，
不用真的把落地页加载出来。抽奖之间带 1.8 秒随机间隔，最多 15 次。

### 收鹅蛋换好礼做什么

注入脚本先做四类固定动作（**打卡 → 领饲料 → 投喂 → 收蛋**），
再进入补充轮：扫页面上所有可见的「领取 / 领奖励 / 收下 / 收获」按钮，最多再点 8 次。

只碰这些动作，兑换、分享、邀请、充值、购买、「去完成」一律不点 ——
「去完成」会跳到别的页面，脚本就断了。

### 会话过期怎么办

模块不存死 Cookie，每次跑之前都会重新走一遍 SSO 换 token。只有 `cmccssotoken`
本身失效（通常是 App 里退出登录、或者长时间没用）才会失败，这时日志会直接说：

```
任务失败 · 签到会话不可用
```

在 App 里打开一次「我的 → 签到领流量」，等页面加载完，模块会把新 Cookie 收进来，
再点一次就行 —— **不需要重新抓包**。

### 还没移植的

| 活动 | 状态 | 说明 |
|---|---|---|
| 签到有礼 | ✅ v1.6 / v1.9 加浏览任务 | 签到 + 累计/连签/组队/月签 + 任务奖励 + 浏览任务 |
| 天天砸金蛋 | ✅ v1.8 | 浏览任务换次数 + 全部抽掉 |
| 收鹅蛋换好礼 | ✅ v1.8 / v1.9 加任务遍历 | 页面内打卡/领饲料/投喂/收蛋/领任务 |
| `cmcc_extra.py` 游戏中心/追剧 | 下一步 | diy-client，同样的明文 JSON |
| `cmcc_coc3.py` / `cmcc_seed.py` | 待评估 | 要 `phone` 头，可以 hook 请求头自动收，但要先确认 11.9.1 还带 |
| `cmcc_wed.py` 周三充值日 | 待评估 | 5 个任务会被 160005 风控，网页端做不了，模块同样做不了 |
| `cmcc_seckill.py` 秒杀 | 不建议 | 要在前台卡秒，模块跑在 App 进程里，反而更适合「打开 App 时顺手抢」 |
| `cmcc_age.py` 网龄 | 不建议 | 抽奖要短信/滑块，只能查次数 |
| `cmcc_fee.py` 话费 | 不需要 | App 首页本来就有 |

写死的活动列表迟早会漏，所以任务引擎按「活动 ID + 接口路径」注册，
新增活动只加一条配置，不动主流程。

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
| `H5-NOISE` | 被过滤的 H5 埋点噪音汇总，每 60 秒一条（v1.4+） |
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
  UpdateHooks.java       升级弹窗 / 版本检查拦截（11.9.1）
  ClassLoaderHooks.java  后台探测晚加载的 OkHttp / X5 类并补装 Hook
  ActivityHooks.java     Application / Activity / Intent
  CrashHooks.java        未捕获异常捕获（CRASH 堆栈）
  WebViewHooks.java      WebView / X5 的加载、页面回调、Cookie、H5 噪音过滤
  JsInjector.java        注入 H5 抓取脚本与 JS Bridge
  JsBridge.java          把 H5 数据转成日志
  OkHttpHooks.java       OkHttp 2/3 请求响应（不再 hook 系统 com.android.okhttp）
  HookLog.java           通过显式广播把日志送到模块 App，按开关截断 detail
  LogReceiver.java       接收日志并落盘
  LogStore.java          日志存储 / 轮转 / 读取
  LogExporter.java       导出到 Download/CmccHook
  MainActivity.java      三 tab：首页 / 功能 / 日志（含筛选、清空、导出）
  LogAdapter.java        日志列表与展开详情
  CookieStore.java       10086 系 Cookie 汇总（OkHttp / WebView 两处截取）
  TaskNet.java           任务接口的极简 HTTP 客户端（HttpURLConnection）
  TaskApi.java           qwhdhub 接口封装、SSO 预热、code 校验、设备码
  SsoLogin.java          用 App 的 JSBridge 取 SSO token，换 QWHD_SESSION_TOKEN
  SignTask.java          签到有礼：签到 + 累计/连签/组队/月签 + 任务奖励
  EggTask.java           天天砸金蛋：浏览任务换次数 + 抽奖
  GooseTask.java         收鹅蛋换好礼：WebView 注入，页面内点按钮
  PageAuto.java          进活动页自动触发：URL 匹配 + 去重 + WebView 浏览任务
  TaskRunner.java        任务调度：单活动 / 一键全部 / 进页面触发 / 启动跑 / 幂等
  TaskReceiver.java      常驻广播接收：模块界面点按钮直接投递给目标进程
  OffscreenWeb.java      离屏 WebView（浏览任务用；鹅蛋用不了，缺登录态）
```

`res/layout/` 里 `page_home.xml`、`page_func.xml`、`page_log.xml` 分别是三个 tab 的页面，
配色走 `values/` + `values-night/` 两套 Material 3 色板，跟随系统深色模式。

## 版本

### v2.1
- 新增 `SsoLogin`：在 App 的 WebView 里执行 JS 调 `newSdkInterface.getUserInfo()`，
  拿手机号和 SSO token，再走 `qwhdsso/appTokenLogin` 换 `QWHD_SESSION_TOKEN`。
  这是签到 / 砸金蛋能后台跑的前提——纯 HTTP 走 SSO 只会 302 回登录页。
- `TaskApi.warmUp` 改为走 `SsoLogin`，失败时把「登录页 / sid / 用户信息 / appTokenLogin」
  每一步写进任务详情，方便定位。
- 完成标记按单个活动记录（`markDone`），`pendingAuto` 不会再因为一次失败整天重跑整组。
- 收鹅蛋改用 App 当前 WebView 打开活动页。离屏 WebView 里页面拿不到 `pc`/`pk` 登录参数，
  渲染出来是空壳，v2.0 的「一个按钮都找不到」就是这么来的。
- 鹅蛋脚本加页面文本快照，找不到按钮时日志里直接能看到页面上真实文案。

### v2.0
- 常驻广播 + 30 分钟定时补跑 + 离屏 WebView + onNewIntent 修复。

## 下一阶段

拿到导出的日志后，将补全功能页：自动签到、福利中心一键领取、任务中心批量完成等，
并用你提供的账号信息验证接口参数与签名逻辑。
