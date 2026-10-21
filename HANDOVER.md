# 交接文档：手机单机 QQ 运行时

> 最后更新：2026-10-21 19:10
> 当前版本：**1.0.18（versionCode 19）· 容器脚本 v7**
> 状态：**真机跑通，可稳定收发消息**

---

## 一、一页速览

### 这个功能解决什么问题

把 QQ 账号接入 AiLink（AI 自动回复），**不需要电脑、不需要 Termux、不需要 root**。

传统做法是在 PC 上跑 NapCat 桥接端，手机通过网络连过去。本项目把整套桥接端（Linux 容器 + QQ Linux 客户端 + NapCat）塞进 APK，在手机内部运行，App 直接连 `127.0.0.1:3001`。

### 技术栈事实（已实测，勿凭印象修改）

| 组件 | 版本 | 说明 |
| --- | --- | --- |
| proot | 5.1.107.98 | Termux 预编译（bionic），非自编译 |
| libtalloc | 2.5.0 | proot 依赖，`DT_NEEDED` 已就地改名 |
| libandroid-shmem | 0.7 | proot 依赖 |
| 根文件系统 | Ubuntu 22.04.5 base (arm64) | 27 MB gz / 解压 74 MB / 2615 个文件 |
| QQ Linux | 3.2.34-53644（198 MB deb） | 解包后 640 MB，Qt 已内置 |
| NapCat | 4.18.38（34.2 MB zip） | 随 APK 内置，不走 GitHub |
| Android 侧 | Kotlin 2.0.21 / Compose / OkHttp 4.12 | 最小 SDK 24 |

### 一次完整链路的耗时（真机实测）

| 阶段 | 耗时 |
| --- | --- |
| 安装容器（解包 rootfs） | 30 秒 |
| 安装 QQ 运行时（依赖 + 下载 + 解包 + 注入） | 约 8 分钟（依赖 6 分 + 下载 108 秒 + 解包 22 秒） |
| 启动 QQ 到出二维码 | 约 3–5 秒（已登录时 `-q` 直接回线） |

---

## 二、系统架构

```
┌──────────────────────────────────────────────────────────┐
│  AiLink App 进程（com.jiuguan.ailink）                     │
│                                                          │
│  ┌────────────────────┐   ┌───────────────────────────┐  │
│  │ BotService         │   │ QqBridgeService           │  │
│  │ 账号引擎、OneBot    │   │ 托管 QQ 进程、二维码、自愈  │  │
│  │ 客户端（OkHttp WS） │   │ 持 WakeLock + WifiLock    │  │
│  └─────────┬──────────┘   └────────────┬──────────────┘  │
│            │ ws://127.0.0.1:3001       │ ProcessBuilder   │
│            │                           ▼                  │
│            │              ┌────────────────────────────┐  │
│            │              │ proot（jniLibs/libproot.so）│  │
│            │              │  rootfs: Ubuntu 22.04       │  │
│            │              │   └ /opt/QQ  + NapCat       │  │
│            │              │      Xvfb 虚拟屏 :99        │  │
│            │              └────────────┬───────────────┘  │
│            └───────────────────────────┘                  │
└──────────────────────────────────────────────────────────┘
```

**关键点**：proot 不做网络隔离，容器内的 `127.0.0.1:3001` 就是设备的回环地址，所以 App 直接连本地端口即可。

---

## 三、代码地图

### Android 侧（6 个 Gradle 模块，依赖单向 `:app → :ui → :assembly → {:data,:engine} → :core`）

包名前缀统一为 `com.jiuguan.ailink.<模块>`，下表路径省略该前缀。

| 文件 | 职责 | 改它要小心 |
| --- | --- | --- |
| `engine/container/LocalRuntime.kt` | **全链路核心**：proot 进程构造、rootfs 安装、QQ 安装/启动、二维码读取、日志读写 | 环境变量与 bind 顺序 |
| `engine/container/TarGzExtractor.kt` | 纯 Kotlin tar 解包（文件/目录/符号链接/硬链接） | AAPT 会改写资源名 |
| `engine/container/ZipExtractor.kt` | 纯 Kotlin zip 解包（NapCat） | 容器内 unzip 不可靠 |
| `engine/container/LogExporter.kt` | 日志写入「下载」目录 + 系统分享 | MediaStore 在 Q 以下退化 |
| `engine/service/RuntimeInstallService.kt` | 安装 QQ 运行时的前台服务 | 长任务必须在这里 |
| `engine/service/QqBridgeService.kt` | QQ 进程托管、二维码监听、掉线自愈 | 不要挂回 ViewModel |
| `engine/service/KeepAlive.kt` | WakeLock + WifiLock | 息屏断连的根因 |
| `engine/service/BootReceiver.kt` | 开机自启（仅当用户开启过服务） | 高版本需前台服务启动豁免 |
| `engine/service/BotService.kt` | 账号引擎常驻前台服务 | 通过 `BotHost` 取 BotManager |
| `engine/bot/BotManager.kt` / `AccountRuntime.kt` | 账号运行时编排、收消息与回复 | 单账号串行（Mutex） |
| `engine/di/BotHost.kt` | 暴露 `botManager` 的窄接口 | 防止 `:engine` 反向依赖 `:assembly` |
| `assembly/AppContainer.kt` | 手写依赖注入，唯一装配全部实现的地方 | 唯一允许横跨各层 |
| `core/SessionLog.kt` | AiLink 侧内存日志（导出用） | 排查必备 |
| `core/AppPrefs.kt` | `qqUin` 等偏好 | 快速登录依赖它 |
| `core/AppVersion.kt` | 运行期读版本号（替代 BuildConfig） | 跨模块取版本统一走这里 |
| `data/gateway/onebot/OneBotGateway.kt` | OneBot v11 客户端 | **切忌设置 pingInterval** |
| `ui/runtime/` | 运行时页面（VM + Screen） | 状态全部来自服务镜像 |

### 脚本与资源

| 路径 | 说明 |
| --- | --- |
| `tools/runtime/prepare-proot.py` | 从 Termux deb 装配 proot 到 jniLibs（可复现，升级只改版本号） |
| `tools/runtime/install-qq.sh` | 容器内安装脚本（v7）**唯一真源，必须 LF** |
| `android/app/src/main/assets/rootfs/arm64-v8a.rootfs` | gzip 内容 + 自定义扩展名（见问题 B1） |
| `android/app/src/main/assets/runtime/NapCat.Shell.zip` | 34.2 MB，随包分发 |
| `build/generated/containerAssets/container/install-qq.sh` | **构建产物，不要手改**：`:app` 的 `SyncContainerScript` 任务在 preBuild 阶段从 `tools/runtime/install-qq.sh` 强制转 LF 复制而来。改脚本只需改真源，无需再手工同步副本 |

---

## 四、构建与发布

```bash
cd android && ./gradlew assembleDebug
# 产物：android/app/build/outputs/apk/debug/app-debug.apk
cp android/app/build/outputs/apk/debug/app-debug.apk "release/接入助手-<版本>-debug.apk"
```

**发版纪律（血的教训）**：

1. 每次改动都提升 `versionCode` 和 `versionName`（`app/build.gradle.kts`）；
2. 改了容器脚本就同步提升 `LocalRuntime.SCRIPT_VERSION`（v7 → v8），这是 App 判定「已在容器里装过」的依据；
3. 界面顶部会显示 `App x.y.z(code) · 运行时脚本 vN`，**这是确认用户装的是哪版的唯一可靠依据**；
4. 容器日志的 `[app]` 行只进内存，**不落盘**，不能拿"导出里没有 `[app]`"判断版本。

### 环境依赖

- JDK 17、Android SDK（platform 34）、Gradle 走腾讯云 + 阿里云镜像
- NDK 27 已安装但**最终没有用到**（proot 用 Termux 预编译包）
- 模拟器 `Pixel_9a`（x86_64，API 36.1）——**只能验证容器与安装，无法验证 QQ 启动**（arm64 二进制）

---

## 五、运行时链路详解

### 5.1 proot 启动参数（`LocalRuntime.newProotProcess`）

```
libproot.so --kill-on-exit --link2symlink -0
  --rootfs=<files>/runtime/rootfs
  --bind=/dev --bind=/proc --bind=/sys
  --bind=<files>/runtime/shm:/dev/shm      ← 必须在 /dev 之后
  --cwd=/root
  /bin/bash ...
```

环境变量：`PROOT_LOADER`、`PROOT_TMP_DIR`、`LD_LIBRARY_PATH`（指向 nativeLibraryDir）、`TMPDIR=/tmp`、`HOME=/root`。

### 5.2 安装 QQ 的五个步骤（`install-qq.sh`）

1. **依赖**：apt 安装 Qt/xcb 全套（见脚本 `PKGS`），按「关键能力」判定成功，而非 apt 退出码；
2. **下载**：QQ deb 198 MB，`curl -C -` 断点续传 + 最多 6 轮重试；
3. **解包**：`dpkg-deb -x` 到临时目录，再 `cp -a` 到 `/opt/QQ`；
4. **NapCat**：App 侧已解压到 `/opt/ailink/napcat`，脚本只做校验与复制；
5. **注入**：复制到 `app_launcher/napcat`、写 `loadNapCat.js`、用 jq 改 `package.json` 的 `main`。

最后写入 `onebot11.json`（WS `127.0.0.1:3001`，token `ailink-local`）与完成标记 `.qq-installed`。

### 5.3 启动 QQ

```
xb-run -a /opt/QQ/qq --no-sandbox [-q <QQ号>]
```

`-q` 走本地凭据免扫码。二维码由 NapCat 写到
`/opt/QQ/resources/app/app_launcher/napcat/cache/qrcode.png`，服务每 2 秒比对 mtime+size 推送新图。

---

## 六、问题档案（全部实测踩过）

> 按主题分类。每条包含：现象 → 根因 → 解法。**这些都是真机验证过的结论，不要凭直觉回退。**

### A. 容器引擎（proot）

**A1. 静态 busybox 在 Android 16 上被 seccomp 杀死**
现象：退出码 159 / `Bad system call`。
根因：Alpine 的 musl 静态二进制与 Android 的 seccomp 策略冲突。
解法：放弃 musl 静态包，改用 **Termux 的 bionic 预编译 proot**（`tools/runtime/prepare-proot.py` 自动装配）。省掉了交叉编译。

**A2. `library "libtalloc.so" not found`**
根因：APK 内文件只能叫 `lib*.so`，而 proot 的 `DT_NEEDED` 是 `libtalloc.so.2`。
解法：对 ELF 做**等长字节替换**把 `DT_NEEDED` 改成 `libtalloc.so`（脚本自动完成）。

**A3. 经符号链接启动 proot 会找不到依赖**
根因：链接器以**链接所在目录**为基准找依赖。
解法：必须执行 `nativeLibraryDir` 下的真实路径，并设 `LD_LIBRARY_PATH`。（`exec -a` 那套只适用于 busybox 这类多合一二进制。）

**A4. `nativeLibraryDir` 为空**
解法：`useLegacyPackaging = true`，否则 native 库不解压到磁盘。

**A5. 容器缺 `/dev/shm` 导致 QQ 启动即崩**
现象：`Creating shared memory in /dev/shm/... failed` → `FATAL ... incorrect permissions on /dev/shm` → SIGTRAP。
解法：另建目录并 `--bind=<dir>:/dev/shm`，**必须放在 `--bind=/dev` 之后**（后绑定覆盖前绑定）。

### B. 资源与打包

**B1. AAPT 改写了 `.tar.gz` 资源**
现象：打包后变成裸 `.tar` 且丢了 `.gz` 后缀，按原名读取必失败。
解法：资源改名 `<abi>.rootfs`（内容仍是 gzip）；解包器按**魔数 `1f 8b`** 判断是否 gzip；读取时依次尝试 `.rootfs / .tar.gz / .tar`。
另注：裸 `.tar` 在 AAPT 免压缩列表里，会让 APK 从 39 MB 涨到 76 MB。

### C. 容器内安装

**C1. `$'\r': command not found` / `pipefail: invalid option`**
根因：脚本在 Windows 上带 CRLF。
解法：资源转 LF，**且 App 写入容器时再归一化一次**（双重保险）。

**C2. 装到 `/opt/opt/QQ`**
根因：`dpkg-deb -x deb /opt` 会保留包内的 `./opt` 前缀。
解法：先解到临时目录，再 `cp -a` 到 `/opt/QQ`。

**C3. `unzip: command not found`（同一环境时有时无）**
根因：apt 事务中断后包停在「已解包未配置」，文件时有时无；proot 下可见性也会抖。
解法：**NapCat 的解压改到 App 侧用 `ZipExtractor`**，容器不再依赖 unzip。

**C4. TMPDIR 指向 Android 路径**
现象：`mktemp: failed to create file via template '/data/user/0/<pkg>/cache/...'`。
根因：容器继承了 Android 的 `TMPDIR`，该路径在 rootfs 内不存在。
解法：proot 环境显式设 `TMPDIR=/tmp`，脚本启动时再做一次 `mktemp` 自愈探测。

**C5. `ca-certificates` 配置失败导致整个安装中断**
现象：`exec: /usr/share/debconf/frontend: not found`。
根因：`debconf/frontend` 是 perl 脚本，perl 与它同一事务升级，postinst 执行时 `/usr/bin/perl` 正在被替换。
解法：**不要用 apt 退出码判定成败**，改成校验「关键能力」（`xvfb-run / unzip / jq / curl` + `libgtk-3 / libnss3 / libgbm / libasound / libxkbcommon`），缺失时 `dpkg --configure -a` → `apt-get -f` → 定向 `--reinstall` 再复检。ca-certificates 的告警可忽略（只影响证书软链，不影响 curl）。

**C6. 下载 198 MB 中途断流**
现象：`curl: (56) OpenSSL SSL_read: Software caused connection abort`。
解法：`curl -C -` 断点续传 + 最多 6 轮重试（`--retry-all-errors`）+ 连续失败两轮丢弃残包 + 大小与 `dpkg-deb -I` 完整性校验。

**C7. 安装进程被杀（切后台/熄屏）**
解法：安装整体移入 `RuntimeInstallService`（前台服务），界面只做镜像。

### D. 登录

**D1. 单手机无法扫码**
现象：相册选图 → QQ 提示「本次请求不支持图片识别或长按扫描二维码授权」。
根因：腾讯对 PC 端登录的**安全策略**，不是实现缺陷。
结论：**首次登录必须有一块第二屏幕**（电脑/平板/另一台手机）用摄像头扫。UI 已改为「分享二维码」+「复制解码链接」，并明确写清限制。
经验：借另一台手机扫屏幕，比"发到电脑再扫"快得多——后者往返耗时，二维码 2 分钟就失效。

**D2. 快速登录 `-q` 报「未找到该 QQ 历史登录记录」**
根因：QQ 号填错（现场是把 `<正确QQ号>` 打成 `<误填QQ号>`）。
解法：日志会打印实际传入的号码；UI 对「快速登录失败」显示橙色横幅。

**D3. `-q` 报「登录态已失效，请重新登录」**
根因：QQ 主动作废本地凭据（换网络/风控/长时间未用/频繁重启）。
结论：**"登录一次永久免扫"不成立**。UI 显示「登录态已失效，需要重新扫码」。
**副作用**：凭据失效时 QQ 不会登录，NapCat 的 3001 也不监听，App 侧表现为 `Failed to connect to /127.0.0.1:3001` 循环——这只是表象，别去改连接代码。

### E. 运行期稳定性

**E1. 息屏后消息中断**
根因：Android Doze 让 CPU 休眠，容器内 QQ/NapCat 线程冻结。**前台服务只保证进程不被杀，不保证 CPU 不休眠**。
解法：两个前台服务各自持有 `PARTIAL_WAKE_LOCK` + `WifiLock`；设置页提供「息屏保持在线（电池白名单）」跳转。
仍需用户手动做：国产 ROM 的省电策略设为「无限制/允许后台活动」，否则锁会被强制释放。
代价：每小时约 3–8% 额外耗电。

**E2. 每 20 秒固定断连（`EOFException`）**
现象：`已连接` → 20 秒后 `EOFException` → 重连，稳定循环；消息落在窗口就 `发送失败：WebSocket 未连接`。
根因：**OkHttp 的 `pingInterval(20s)` 会发 WebSocket 协议层 ping 帧，NapCat 的 WS 服务端只认 JSON 帧，收到就断 TCP**。
解法：移除 `pingInterval`，保活改用 JSON 心跳（`get_status`，25 秒）；`sendText` 在 `socket` 为空时最多等 5 秒重连再发一次。
**这条是"消息发不出去"的最终根因，切勿再给 OneBot 客户端加协议层 ping。**

**E3. 日志看不到 AiLink 侧的动作**
根因：早期导出只有容器日志。
解法：新增 `SessionLog`（内存环形 2000 行），导出时作为「会话日志（AiLink 侧）」独立段落；OneBot 的连接/断开/发送失败、engine 的收消息与回复结果、保活锁的获取释放都会记录。

---

## 七、已知限制（产品层面必须承认）

| 限制 | 说明 | 可能的应对 |
| --- | --- | --- |
| 首次登录需第二块屏幕 | 腾讯安全策略，不可绕 | 引导语必须写清楚；或提供"先在电脑跑一次桥接"的过渡 |
| `-q` 凭据会被作废 | 无法保证永久免扫 | 界面要能优雅回到扫码 |
| 国产 ROM 省电策略 | WakeLock 可能被强制释放 | 引导用户设白名单；建议观察自愈是否过度触发 |
| 重启后不自启 | **已实现**：`engine/service/BootReceiver.kt` 监听 `BOOT_COMPLETED`，仅当用户此前开启过服务才拉起（依赖已声明权限 + 前台服务启动豁免） | 部分 ROM 会拦截，需引导加白名单 |
| 耗电 | 常驻约 3–8%/小时 | 可加"仅充电时常驻"开关 |
| APK 体积 | 71.7 MB（rootfs 27 + NapCat 34） | 可改首启下载 |
| 首次下载 | 约 350 MB（QQ 198 + 依赖 150） | 可预热 CDN 或提供 PC 端导入包 |
| 容器占用 | 约 800 MB | 需提示用户预留 1 GB |
| 模拟器无法验证 QQ | x86_64 跑不了 arm64 二进制 | 只能真机验证 |

---

## 八、排障手册

### 8.1 取日志

设置 → 手机内置运行时 → **导出日志文件（保存到下载目录）**。
文件在 `下载/ailink-log-<时间戳>.log.txt`，同时会唤起分享（可直发微信）。

导出内容是三段拼接：

```
===== 安装日志 =====        （容器内 apt/下载/解包全过程，追加写入，保留历史）
===== 启动日志 =====        （QQ/NapCat 的 stdout）
===== 会话日志（AiLink 侧） ===== （WS 连接、收发结果、保活锁、掉线）
```

### 8.2 关键字对照表

| 关键字 | 含义 | 下一步 |
| --- | --- | --- |
| `[app] 启动 QQ，传入号码：xxx` | 实际传给 `-q` 的号码 | 与账号核对 |
| `登录态已失效` | 凭据作废 | 重新扫码 |
| `快速登录失败，未找到该 QQ 历史登录记录` | 号错或从未登录过 | 核对号码 / 扫码 |
| `WebSocket服务: 127.0.0.1:3001, : 已启动` | NapCat WS 已监听 | 正常 |
| `[onebot] 已连接 ws://127.0.0.1:3001（selfId=xxx）` | 客户端连上 | 正常；若随后 20 秒 EOF，是 E2 未修复 |
| `[onebot] 发送超时：10 秒未收到回执` | 发出去了但无响应 | 看 NapCat 日志是否拒绝 |
| `[onebot] 发送被拒：status=… retcode=…` | NapCat 明确拒绝 | 看 retcode 与原始响应 |
| `[engine] 回复 失败` | AiLink 侧发送失败 | 对照上面两条 |
| `[keepalive] 释放保活锁` 出现在息屏期间 | 系统收走了锁 | ROM 省电策略要设白名单 |
| `账号状态变更为离线` | QQ 掉线 | 60 秒后会自动重启容器 |

### 8.3 常见故障速查

| 现象 | 先查什么 |
| --- | --- |
| `Failed to connect to /127.0.0.1:3001` 循环 | QQ 是否真的登录了（看是否有 `登录态已失效` / 二维码在转） |
| 收到消息但不回复 | 会话日志里有没有 `[engine] 处理消息`；有则看 `[onebot] 发送…` |
| 每隔 20 秒断连 | 检查是否又给 OkHttp 加了 `pingInterval` |
| 息屏后断 | 看 `keepalive` 日志与系统电池白名单 |
| 安装卡在步骤 1 | 正常，apt 阶段 6 分钟无输出；有 `[app] 仍在进行…已耗时 Ns` 心跳 |

---

## 九、待办与建议

按优先级：

1. **自愈策略调优**——当前"离线 60 秒重启容器"，若发现一天内多次重新扫码，改为"先重连、失败再重启"，避免频繁重启加速凭据作废；
2. **账号页展示内置 QQ 在线状态**，与运行态联动；
3. **首启下载运行时**——把 rootfs/QQ 从 APK 挪到下载，APK 可回到 15 MB 级；
4. **"仅充电时常驻"开关**——缓解耗电；
5. **release 签名与混淆**——当前是 debug 包；
6. **用户手册**——面向非技术用户的操作指引（尤其"首次登录需要另一台手机"）。

已完成：开机自启（`engine/service/BootReceiver.kt`）。

---

## 十、给接手者的三句话

1. **不要凭直觉改容器参数**：bind 顺序、`TMPDIR`、`LD_LIBRARY_PATH`、真实路径执行，每一条都是踩出来的。
2. **判断问题先看会话日志**：`SessionLog` 能区分"没连上 / 收到了没处理 / 处理了没发出去"，这三类修法完全不同。
3. **单手机首次登录做不到**：这是腾讯的策略，不是 bug。所有绕过它的尝试都失败了（相册识别、长按识别），不要在这一点上浪费时间。

---

## 附：外部依赖与来源

| 资源 | 来源 | 备注 |
| --- | --- | --- |
| proot / libtalloc / libandroid-shmem | Termux 仓库（`packages.termux.dev`） | `tools/runtime/prepare-proot.py` 自动下载装配 |
| Ubuntu 22.04 base rootfs | `cdimage.ubuntu.com/ubuntu-base` | arm64 / amd64 各一份 |
| QQ Linux arm64 3.2.34-53644 | `qqdl.gtimg.cn`（腾讯官方 CDN） | 版本号取自 NapCat 官方 installer 的 `linuxqq_target_version` |
| NapCat Shell 4.18.38 | GitHub Releases（已内置进 APK） | 版本与 QQ 版本强绑定 |

> NapCat 与 QQ 的版本对应关系是硬约束：换 QQ 版本必须确认 NapCat 支持，注入点会变。
