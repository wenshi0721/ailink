# 贡献指南

感谢关注 AiLink。提交 Issue 与 PR 前请先阅读本文。

## 开发环境

| 依赖 | 版本 | 说明 |
| -- | -- | -- |
| JDK | 17 | Gradle 与 Kotlin 编译均按 17 |
| Android SDK | compileSdk 34 / minSdk 24 / targetSdk 34 | Android Studio 打开 `android/` 会自动提示补齐 |
| IDE | Android Studio（AGP 8.7.3 / Gradle 8.9） | 命令行同样可构建 |

```bash
# 构建（产物：android/app/build/outputs/apk/debug/app-debug.apk）
cd android && ./gradlew assembleDebug

# 单元测试（全部模块）
cd android && ./gradlew testDebugUnitTest

# Android Lint
cd android && ./gradlew lintDebug
```

依赖仓库在 `android/settings.gradle.kts` 与 `android/gradle/wrapper/gradle-wrapper.properties` 中配置了国内镜像（腾讯云 / 阿里云）。海外网络建议改回官方源（`google()`, `mavenCentral()`, `services.gradle.org`）。

## 架构约束（改动前必读）

- 工程分 6 个 Gradle 模块，依赖方向严格单向：`:app → :ui → :assembly → {:data, :engine} → :core`，**禁止回边与跨层引用**。PR 中引入反向依赖会被拒绝；
- 新的接入方式实现 `data/gateway/MessageGateway` 接口并在 `GatewayFactory` 注册，不要在引擎里写平台特判；
- ViewModel 只依赖 `assembly/AppContainer`，用 `appViewModel { XxxViewModel(it) }` 注入；
- 各模块职责边界见 [`docs/architecture/module-map.md`](docs/architecture/module-map.md)；
- **修改「手机内置运行时」（proot 容器 / QQ / NapCat 相关）前，务必阅读 [`HANDOVER.md`](HANDOVER.md)**——其中的 bind 顺序、环境变量、脚本版本号等约束都是真机踩坑结论，凭直觉改动会复现已修复的故障。

## 分支与提交规范

- 从 `main` 拉出功能分支，命名 `feat/xxx`、`fix/xxx`、`docs/xxx`；
- 提交信息遵循 [Conventional Commits](https://www.conventionalcommits.org/zh-hans/)：`feat:` / `fix:` / `docs:` / `refactor:` / `build:` / `chore:` / `ci:`，可带 scope，如 `refactor(engine): ...`；
- 每个提交保持可构建：`./gradlew assembleDebug` 能通过；
- 一次 PR 聚焦一件事，避免混入无关格式化改动。

## 代码风格

- Kotlin 官方代码风格（`gradle.properties` 已设 `kotlin.code.style=official`）；
- 第三方依赖版本统一登记在 `android/gradle/libs.versions.toml`，禁止在 `build.gradle.kts` 硬编码版本号；
- 状态色统一走 `ui/theme` 的 `statusColor()`，开关配色统一 `appSwitchColors()`；
- 枚举与数据库字符串的转换统一走 `core/model/Mappers.kt`。

## 测试

- 现有单元测试在 `android/data/src/test/`（OneBot v11 消息解析）与 `android/core/src/test/`（工具类）。`./gradlew testDebugUnitTest` 运行全部；
- 修复 bug 或新增纯逻辑（解析、编解码、策略）时请补充对应单元测试；涉及 Android 框架 API 的逻辑因需要真机/模拟器，暂不要求自动化测试；
- 提交前确保 `./gradlew testDebugUnitTest` 全绿。

## PR 流程

1. Fork（外部贡献者）或建分支（协作者）→ 提交改动；
2. PR 描述里说明：改了什么、为什么、如何验证（命令或截图）；
3. CI（构建 + 单元测试 + lint）必须全绿；
4. 维护者 review 后合并。

## 提交许可

提交即表示你同意按 [Apache-2.0](LICENSE) 许可你的贡献。注意：本仓库捆绑的第三方组件（如 NapCat）许可与主仓库不同，新增依赖前请在 PR 说明里附上其许可证，由维护者确认兼容性。
