# 模块地图

> 适用版本：1.0.17（versionCode 18）· 重构后 6 模块结构
> 校验脚本：`node tools/dev/check-module-deps.mjs`（CI / 本地提交前跑一次）

工程被切成 6 个 Gradle 模块。目的不是"多模块"，而是让**依赖方向能被机械校验**：只要方向对了，改动就不会扩散。

```
                        ┌──────────┐
                        │  :app    │  应用壳
                        └────┬─────┘
                             │ implementation
                    ┌────────┴────────┐
                    ▼                 ▼
              ┌──────────┐      ┌───────────┐
              │  :ui     │      │ :assembly │  装配层
              └────┬─────┘      └─────┬─────┘
                   │ implementation  │ api ×3
                   └────────┬─────────┘
                            ▼
              ┌─────────────┴─────────────┐
              ▼                           ▼
        ┌──────────┐                ┌──────────┐
        │ :engine  │───────────────▶│  :data   │
        └────┬─────┘ implementation └────┬─────┘
             │ api                       │ api
             └─────────────┬─────────────┘
                           ▼
                      ┌──────────┐
                      │  :core   │  内核（不依赖任何模块）
                      └──────────┘
```

**唯一合法方向**：`:app → :ui → :assembly → {:data, :engine} → :core`
`:engine` 与 `:data` 之间只有 `:engine → :data` 一条边；`:core` 是叶子，谁也不依赖。

---

## 一、模块职责

| 模块 | 类型 | namespace | 职责 | 对外暴露 |
| --- | --- | --- | --- | --- |
| `:app` | application | `com.jiuguan.ailink` | Application、Manifest、图标、字符串、容器脚本同步 Task | — |
| `:ui` | library | `com.jiuguan.ailink.ui` | Compose 主题、通用组件、8 个页面与 ViewModel、导航、MainActivity | — |
| `:assembly` | library | `com.jiuguan.ailink.assembly` | 手写依赖注入：`AppContainer` 装配仓库/网关/BotManager；`AppHost` 窄接口 | `core` `data` `engine` |
| `:engine` | library | `com.jiuguan.ailink.engine` | 业务引擎：BotManager / AccountRuntime、前台服务、proot 容器运行时 | `core` |
| `:data` | library | `com.jiuguan.ailink.data` | Room（实体/DAO/DB）、仓库、OneBot 网关、AI 客户端 | `core` |
| `:core` | library | `com.jiuguan.ailink.core` | 模型与枚举、偏好、版本号、内存日志、工具、配对解析 | — |

### 各模块内部的包

```
:app     com.jiuguan.ailink
         └── AiLinkApp.kt            同时实现 AppHost（给 UI）与 BotHost（给 Service）

:ui      com.jiuguan.ailink.ui
         ├── MainActivity.kt / nav/AppNav.kt
         ├── theme/                  Color / Theme / Type
         ├── common/                 Components*.kt（状态、卡片、横幅、表单、标签）、ViewModelExt
         ├── home/ accounts/ messages/ ai/ settings/ runtime/
         └── （每个功能包 = Screen + ViewModel 成对）

:assembly com.jiuguan.ailink.assembly
         ├── AppContainer.kt         唯一知道全部实现的地方
         └── AppHost.kt              interface AppHost { val container: AppContainer }

:engine  com.jiuguan.ailink.engine
         ├── bot/                    BotManager、AccountRuntime
         ├── service/                BotService、QqBridgeService、RuntimeInstallService、
         │                           BootReceiver、KeepAlive、NotificationHelper、RuntimeState
         ├── container/              LocalRuntime（全链路）、TarGzExtractor、ZipExtractor、LogExporter
         └── di/BotHost.kt           interface BotHost { val botManager: BotManager }

:data    com.jiuguan.ailink.data
         ├── local/                  4 个 Entity + 4 个 DAO + AiLinkDatabase
         ├── gateway/                MessageGateway、GatewayFactory、ConvId、
         │   │                       ConnectionTester
         │   ├── onebot/             OneBotGateway（WS 客户端）
         │   └── demo/               DemoGateway（本地演示）
         ├── ai/                     AiClient（OpenAI 兼容）、LocalResponder（兜底）
         └── repo/                   AccountRepository、MessageRepository、AiRepository、SeedData

:core    com.jiuguan.ailink.core / .model / .util
         ├── core/                   AppPrefs、AppVersion、SessionLog、PairingBus
         ├── model/                  Platform、BridgeType、TriggerScope、ConnStatus、
         │                           Direction、MessageKind、Mappers、Pairing
         └── util/                   TimeFmt、TextUtils、Ids
```

---

## 二、三条铁律

### 1. 依赖单向，禁止回边

`check-module-deps.mjs` 会把每个 `.kt` 的 `import` 与对应模块的允许集合比对，违反即失败。典型违规：

- `:engine` 里 `import com.jiuguan.ailink.assembly.AppContainer` —— 引擎不该认识装配层；
- `:engine` 里 `import com.jiuguan.ailink.ui.MainActivity` —— 通知点击跳转应改用
  `packageManager.getLaunchIntentForPackage(packageName)`，见 `NotificationHelper`；
- `:core` 里 import 任何其他模块 —— `:core` 是叶子。

### 2. 跨层通信走窄接口，不引实现

两个接口刻意拆得很小，避免把整个容器暴露给下层：

```kotlin
// :assembly —— 给界面层
interface AppHost { val container: AppContainer }

// :engine/di —— 给服务层（Service 只拿 botManager，看不到仓库与界面）
interface BotHost { val botManager: BotManager }
```

`AiLinkApp` 同时实现两者，服务侧写 `(application as BotHost).botManager`，
界面侧写 `(applicationContext as AppHost).container`。这样 `:engine` 永远不需要 import `:assembly`。

### 3. `api` 只用于「类型出现在公开签名里」的依赖

| 模块 | `api` 了什么 | 原因 |
| --- | --- | --- |
| `:assembly` | `core` `data` `engine` | 它对外只提供 `AppContainer`，而 `AppContainer` 的属性类型横跨三层的类 |
| `:engine` | `core` | 公开方法签名用到 `:core` 的模型与枚举 |
| `:data` | `core` | 实体与仓库签名大量使用 `:core` 模型 |
| `:data` | `androidx.room:room-runtime` | `AiLinkDatabase` 继承 `RoomDatabase`，必须让消费方看得见该父类 |

其余一律 `implementation`。

---

## 三、新增代码该放哪

| 你要加的东西 | 放这里 | 备注 |
| --- | --- | --- |
| 新页面 | `:ui/ui/<功能>/{XxxScreen,XxxViewModel}.kt` + `nav/AppNav.kt` 注册路由 | ViewModel 构造参数只允许 `AppContainer` |
| 新接入方式（如官方 Bot API） | `:data/gateway/<name>/` 实现 `MessageGateway`，在 `GatewayFactory` 注册 | `:ui` 与 `:engine` 不用改 |
| 新 AI 供应商特化 | `:data/ai/` | 保持 OpenAI 兼容为主，特化再抽 |
| 新表 / 新字段 | `:data/local/`（Entity + DAO + DB 版本号） | **`ailink.db` 库名不可改**，改动必须写 Migration |
| 新枚举 / 状态 | `:core/model/`，实体里存字符串，转换写在 `Mappers.kt` | 不要在各模块各定义一份 |
| 新的后台能力（进程、服务、保活） | `:engine/service/` | 长任务必须在服务里，不挂 ViewModel |
| 容器内行为 | `:engine/container/` + `tools/runtime/install-qq.sh` | 改脚本记得抬 `SCRIPT_VERSION` |
| 新的全局偏好 | `:core/core/AppPrefs.kt` | |
| 跨模块工具函数 | `:core/util/` | 放不下说明职责没想清 |

---

## 四、构建期机制（三个容易踩的点）

1. **容器脚本单一真源**
   真源是 `tools/runtime/install-qq.sh`（必须 LF）。`:app/build.gradle.kts` 里的
   `SyncContainerScript` 任务在 `preBuild` 阶段把它强制转 LF 复制到
   `build/generated/containerAssets/container/install-qq.sh`，再通过
   `sourceSets.main.assets.srcDir(...)` 打进 APK。
   → 以前 repo 里有一份手抄副本，两边会飘；现在副本是构建产物，不要手改。

2. **`nonTransitiveRClass = true`**
   多模块后每个模块的 `R` 只包含自己的资源。在 `:engine` 里引用自己的字符串/图标必须写
   `com.jiuguan.ailink.engine.R`，不能靠 `:app` 的 R 兜底。

3. **`useLegacyPackaging = true`（`:app`）**
   保持 native 库解压到磁盘。proot 必须执行 `nativeLibraryDir` 下的真实路径，
   关掉这个开关会让 `nativeLibraryDir` 为空。

---

## 五、验证

```bash
# 1. 模块边界（秒级，提交前必跑）
node tools/dev/check-module-deps.mjs -v

# 2. 单元测试（:data 的解析逻辑纯函数化，不依赖 Android 运行时）
cd android && ./gradlew :data:testDebugUnitTest

# 3. 全量构建
cd android && ./gradlew clean assembleDebug

# 4. 装机冒烟（模拟器可验证界面与容器链路，但跑不了 arm64 的 QQ）
adb install -r --no-streaming android/app/build/outputs/apk/debug/app-debug.apk
adb logcat -d | grep -iE "FATAL|AndroidRuntime|Resources.NotFound"
```

依赖版本集中在 `android/gradle/libs.versions.toml`，构建脚本只写 `libs.xxx` 别名，
**禁止在 `build.gradle.kts` 里硬编码版本号**。新增依赖先登记到版本目录。

校验脚本当前的基线输出：

```
模块统计：
  :app         1 个文件     4 条跨包 import
  :ui         29 个文件   196 条跨包 import
  :assembly    2 个文件     9 条跨包 import
  :engine     14 个文件    37 条跨包 import
  :data       21 个文件    41 条跨包 import
  :core       15 个文件     1 条跨包 import

✓ 模块边界校验通过：6 个模块之间无反向 / 越层依赖
```
