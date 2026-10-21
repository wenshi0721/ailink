# 接入助手（AiLink）

把闲置的 QQ / 微信 账号接到 AI 服务上的 Android 客户端。负责账号接入配置、消息收发、自动回复、在线状态、消息记录与账号切换。

- **安装包**：`release/接入助手-1.0.17-debug.apk`（71.7 MB，minSdk 24 / targetSdk 34；含手机内置 QQ 运行时）
- **界面预览**：`preview/app-preview.html`（浏览器直接打开，可点击切换页面）
- **真机截图**：`docs/screenshots/`
- **源码工程**：`android/`（Kotlin + Jetpack Compose，6 模块）
- **模块地图**：`docs/architecture/module-map.md`（每个模块的职责与依赖边界）

> 说明：应用本身不破解、不代理任何官方协议。QQ / 微信 的真实接入通过你自建的桥接端完成（OneBot v11 正向 WebSocket），应用是桥接端的管理与 AI 回复客户端。平台合规风险见文末。

---

> **手机单机 QQ 运行时的技术交接文档**：`HANDOVER.md`
> 包含架构、代码地图、构建发版、全部踩坑记录（19 条）、排障关键字表与待办。

## 界面速览

| 概览 | 账号 | 会话 | 聊天 |
| -- | -- | -- | -- |
| 概览 | 账号 | 会话 | 聊天 |

更多界面见 `docs/screenshots/`（账号编辑、AI 服务配置、设置页）。

---

## 一、需求对照

| 需求            | 实现                                                                  |
| ------------- | ------------------------------------------------------------------- |
| 配置 QQ / 微信 账号 | 账号管理页新增/编辑：平台、备注名、账号号码、接入方式、WebSocket 地址与令牌                         |
| 配置对应 AI 服务    | 独立的 AI 服务配置：接口地址、密钥、模型、温度、最大 Token、系统提示词，兼容 OpenAI Chat Completions |
| 消息接收          | 前台服务常驻 WebSocket，收到消息实时落库并刷新会话                                      |
| 自动回复          | 触发范围（仅私聊 / 私聊+群@ / 全部）、回复延迟、忽略关键词、会话级开关、人设提示词                       |
| 在线状态查看        | 概览页显示在线数、连接状态、在线时长、心跳延迟；账号页显示每个账号状态                                 |
| 消息记录管理        | 会话列表（搜索、全部/未读/群聊/私聊筛选）、聊天详情、未读数、清空单聊/全量、手动发消息                       |
| 账号切换          | 顶部账号筛选条 + 账号页「设为当前账号」，全局生效于概览与消息                                    |
| 界面简洁          | Material 3，浅色/深色自适应，统一卡片、圆角与状态色                                     |
| 运行稳定          | 前台服务、指数退避重连、消息去重、AI 失败降级与系统提示、崩溃兜底                                  |
| 适配主流机型        | minSdk 24（Android 7.0）至 Android 14，密度无关布局，无硬编码尺寸                    |

## 二、技术方案

工程按职责切成 6 个 Gradle 模块，依赖方向严格单向，不允许回边与跨层引用：

```
:app               应用壳（Application / Manifest / 图标 / 构建期资源同步）
 │
:ui                Compose 界面（主题 / 通用组件 / 8 个页面与 ViewModel / 导航）
 │
:assembly          装配层（AppContainer 手写依赖注入，唯一知道全部实现的地方）
 │
 ├── :engine        业务引擎（BotManager / AccountRuntime / 前台服务 / 容器运行时）
 └── :data          数据层（Room / 仓库 / OneBot 网关 / AI 客户端）
 │
:core              内核（模型与枚举 / 偏好 / 日志 / 工具 / 配对解析）
```

各层内部：

```
:ui      Home / Accounts / AccountEdit / Conversations / Chat / AiProfiles / Settings / Runtime
              │ StateFlow + ViewModel（通过 AppHost 取容器，不依赖其他模块实现）

:engine  BotManager ── AccountRuntime（每账号一个运行时）
                          ├─ 网关：OneBotGateway（WS）/ DemoGateway（本地脚本）
                          ├─ 策略：触发范围、忽略词、会话开关、延迟
                          └─ 回复：AI 调用 → 失败降级 / 系统提示
         前台服务：BotService / QqBridgeService / RuntimeInstallService / BootReceiver
         容器运行时：proot + rootfs + QQ Linux + NapCat（详见 HANDOVER.md）

:data    Room（accounts / ai_profiles / conversations / messages）
         OkHttp（WebSocket + HTTP）
         GatewayFactory / MessageGateway / AiClient

:core    Platform / BridgeType / TriggerScope / ConnStatus / Pairing
         AppPrefs（当前账号、服务总开关）/ AppVersion / SessionLog
```

**关键实现点**

1. **接入抽象**：`MessageGateway` 统一 `start/stop/sendText` 与事件流；新增接入方式只需实现接口并在 `GatewayFactory` 注册。
2. **OneBot v11 协议**：解析 `post_type=message` 事件，兼容消息段数组与 CQ 码字符串；通过 `send_private_msg` / `send_group_msg` 发送，`echo` 关联响应，10 秒超时。
3. **连接可靠性**：`1s→30s` 指数退避重连；25 秒 `get_status` JSON 心跳同时测量 RTT。**绝不可给 OkHttp 设 `pingInterval`**——协议层 ping 帧会让 NapCat 直接断 TCP（表现为每 20 秒 `EOFException`），详见 `HANDOVER.md` 问题 E2。
4. **消息幂等**：以 `convId + platformMsgId` 去重，避免重连后重复回复。
5. **防打扰**：启动 10 分钟内不处理历史消息；单账号回复串行（Mutex）；可配置 0–5 秒延迟。
6. **AI 降级**：未配置接口地址或未配置 AI 服务时，使用内置 `LocalResponder` 本地生成回复，链路照常可用；调用失败则在会话内写入系统提示，便于定位。
7. **上下文**：取该会话最近 8 条消息作为多轮上下文，叠加「系统提示词 + 账号人设 + 平台约束」。
8. **前台服务**：`dataSync` 类型前台服务 + 常驻通知，退到后台仍保持在线；开机自启（仅当用户开启过服务）。

## 三、目录结构

```
jiuguan/
├── android/                     Gradle 多模块工程（Kotlin + Compose）
│   ├── app/                     应用壳：Application / Manifest / 图标 / res
│   ├── ui/                      Compose 界面：主题 / 通用组件 / 页面 / 导航
│   ├── assembly/                装配层：AppContainer（DI）、AppHost
│   ├── engine/                  业务引擎 + 前台服务 + 容器运行时
│   ├── data/                    数据层：Room / 仓库 / 网关 / AI
│   ├── core/                    内核：模型 / 枚举 / 偏好 / 日志 / 工具
│   ├── build.gradle.kts         模块公共构建配置
│   └── settings.gradle.kts      模块清单 + 依赖方向约束（文件头有注释）
├── bridge/                      OneBot v11 桥接端（PC 侧）
│   ├── napcat/                  NapCat Shell 配置
│   ├── wechat/                  微信 hook → OneBot v11 适配层
│   ├── scripts/                 启停 / 配对 / 检查脚本
│   ├── runtime/                 运行期状态：配置与日志（gitignore，保留 README）
│   └── vendor/                  第三方分发包 575 MB（gitignore，保留 README）
├── tools/                       开发与运维脚本
│   ├── pairing/                 配对码生成（make-pairing.mjs）
│   ├── runtime/                 proot 装配 + 容器内安装脚本（单一真源）
│   ├── termux/                  手机单机 Termux 部署
│   └── vendor/                  脚本依赖的第三方库
├── docs/
│   ├── architecture/            模块地图、一体化可行性评估
│   ├── ops/                     真机验证清单、测试环境审计
│   └── screenshots/             界面截图
├── preview/                     HTML 界面预览原型
├── release/                     对外交付：APK、配对页（gitignore）
├── archive/                     开发过程产物归档（gitignore，保留 README）
├── HANDOVER.md                  手机单机 QQ 运行时交接文档
└── README.md
```

模块内的包结构（以 `:engine`、`:data` 为例，完整清单见 `docs/architecture/module-map.md`）：

```
android/engine/src/main/java/com/jiuguan/ailink/engine/
├── bot/            BotManager、AccountRuntime（每账号运行时）
├── service/        前台服务、开机广播、保活、通知、运行态镜像
├── container/      proot 运行时：进程构造、解包、日志导出
└── di/             BotHost（暴露 botManager 的窄接口，避免反向依赖装配层）

android/data/src/main/java/com/jiuguan/ailink/data/
├── local/          Room 实体 / DAO / 数据库
├── gateway/        网关抽象、工厂、OneBot、演示网关、连接测试
│   └── onebot/     OneBot v11 客户端
├── ai/             AI 客户端、本地兜底回复器
└── repo/           账号、消息、AI 配置仓库 + 首启种子数据
```

## 四、构建与安装

**方式一：直接安装**

```bash
adb install -r release/接入助手-1.0.17-debug.apk
```

**方式二：Android Studio**

1. 打开 `android/` 目录（AGP 8.7.3 / Gradle 8.9 / JDK 17）；
2. 等待同步完成，运行 `app` 配置即可。

**方式三：命令行**

```bash
cd android
./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

已配置阿里云 Maven 镜像与腾讯云 Gradle 发行包镜像，国内网络可正常构建。

## 五、使用指南

### 1. 先用演示模式跑通

1. 首次启动已内置「演示账号（本地演示）」与「内置模拟回复」；
2. 概览页打开「接入服务」开关；
3. 约 10 秒后开始出现模拟消息，并自动生成回复；
4. 消息页可查看会话，聊天页可手动发消息、关闭该会话自动回复。

### 2. 接入真实 QQ / 微信

> 本仓库已在 `bridge/` 部署好 NapCat Shell 桥接端（QQ NT 9.9.33 + NapCat 4.18.38，正向 WS `:3001`），
> 完整步骤见 **`bridge/README.md`**，启动入口为 `bridge/scripts/start-napcat.bat`。

1. 在桥接端（NapCat / LLOneBot / Lagrange 等）登录要接入的账号，开启「正向 WebSocket 服务端」，记下端口与 token；
2. 应用内新增账号 → 选择平台 → 接入方式选「OneBot v11 桥接」→ 填 `ws://<桥接端IP>:<端口>` 与 token → **测试连接**；
   - 更省事的做法：电脑上执行 `node tools/pairing/make-pairing.mjs`，把输出的 `ailink://` 那一行粘贴到「账号 → 配对码导入」，地址与令牌会自动填好并自动测试连接；
3. 保存后打开总开关，账号状态变为「在线」即接入成功；
4. 手机与桥接端需网络互通；跨网络请使用 `wss://` 或内网穿透。

### 3. 配置 AI

- 新增 AI 服务，填写 `https://api.deepseek.com/v1`、密钥、模型名（如 `deepseek-chat`）即可；
- 兼容 OpenAI、通义、Moonshot、Ollama、LM Studio、One-API 等；
- 接口地址留空 = 使用内置模拟回复，便于先验证链路；
- 账号编辑页可设置人设与回复延迟，AI 服务页可调整温度与最大 Token。

## 六、数据与隐私

- 账号配置、消息记录全部保存在本机 Room 数据库（`ailink.db`），不经过任何中转服务器；
- AI 调用只发送当前会话的最近上下文，直连你配置的接口地址；
- 应用已关闭 `allowBackup`，避免配置被系统备份带走；
- 清空记录：设置 → 数据 → 清空全部聊天记录（不可恢复）。

## 七、兼容性说明

- **minSdk 24 / targetSdk 34 / compileSdk 34**，已在 Pixel 7a（Android 16 模拟器，1080×2400，420dpi）实机验证主要流程；
- 布局全部使用 dp / 自适应容器，无固定分辨率假设；
- Android 13+ 首次启动申请通知权限（拒绝后仍可运行，但看不到常驻通知）；
- Android 12+ 后台启动前台服务受限，开机自启失败会被静默忽略，重新打开应用即可恢复。

## 八、合规与风险（务必阅读）

1. 请**只接入你本人拥有合法使用权的账号**，并遵守腾讯相关用户协议；
2. NapCat / LLOneBot 等属于**非官方协议实现**，可能触发平台风控，导致限制登录甚至封号，建议先用小号或独立设备验证；
3. 不建议用于营销群发、自动加好友、批量养号等行为，这类用途风险与合规问题最大；
4. 若用于生产环境，建议改用官方通道：企业微信应用、微信公众号/服务号、QQ 官方机器人开放平台——本项目的 `MessageGateway` 抽象已为这类接入预留扩展点。

## 九、已知边界

- 群聊标题取「群聊 · 发送者昵称」，未调用 `get_group_info` 拉取真实群名（可在 `OneBotGateway.handleMessage` 扩展）；
- 消息仅支持文本与常见富媒体的占位显示（图片/语音显示为 `[图片]`、`[语音]`），不做媒体下载；
- 尚未实现多账号同时回复的并发上限控制（当前为每账号串行），账号数量较多时可再加信号量；
- 未接入 `WorkManager` 定时任务，长时间后台运行依赖前台服务，厂商省电策略仍可能回收，建议加入白名单；
- 「手机内置运行时（QQ）」已完整实现：proot 5.1.107.98 + Ubuntu 22.04 rootfs + QQ Linux + NapCat 4.18.38 全部打进 APK，真机可稳定收发（详见 `HANDOVER.md`）。**首次登录必须借助第二块屏幕扫码**（腾讯安全策略）；重启后由 `BootReceiver` 自启。

## 十、扫码配对（手机端零配置接入）

桥接端仍在电脑/云主机，手机只装 APK：

1. 双击 `bridge\scripts\show-pairing.bat`（或 `node tools/pairing/make-pairing.mjs`）；
2. 脚本读取桥接端的真实端口与令牌，生成 `release/pairing.html` 并自动打开；
3. 手机扫码，或把 `ailink://` 配对码粘贴到 App「账号 → 配对码导入」；
4. App 自动填好平台/地址/令牌并立即测试连接，点保存即可。

配对码里含令牌，等同于该接入端的凭证，请勿公开发布。

### 可选：手机完全单机跑 QQ（需 Termux）

QQ 可以在手机内部直接跑桥接端，AiLink 连 `ws://127.0.0.1:3001` 即可，客户端代码无需改动。
这条路径需要在手机上安装 Termux，不做默认推荐。

```bash
# 手机装 Termux（F-Droid 版）后执行
bash tools/termux/termux-napcat.sh          # 安装 proot 容器 + QQ Linux arm64 + NapCat
bash tools/termux/termux-napcat.sh --start  # 启动并扫码登录
```

微信无法用同样方式搬进手机（依赖 Windows 版客户端注入），详细论证、四条路线对比与分阶段方案见
[`docs/architecture/安卓端一体化可行性评估.md`](docs/architecture/安卓端一体化可行性评估.md)。
