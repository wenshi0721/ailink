# tools/ —— 开发与部署脚本

工作区级脚本的统一入口。这里的脚本**不参与 APK 编译**，只服务于开发、部署与排障；
参与构建的只有 `runtime/install-qq.sh`，它由 `:app` 的 `SyncContainerScript` 任务在
`preBuild` 阶段复制到 `android/app/build/generated/containerAssets/container/`，再作为
assets 打进 APK（见根 README「构建与安装」与 `docs/architecture/module-map.md`）。

| 路径 | 用途 | 运行方式 | 关键前提 |
| --- | --- | --- | --- |
| `dev/check-module-deps.mjs` | 校验 6 个 Gradle 模块之间无反向 / 越层依赖，并核对 `build.gradle.kts` 的依赖声明 | `node tools/dev/check-module-deps.mjs [-v]` | 无；违规时退出码 1，可直接接 CI |
| `pairing/make-pairing.mjs` | 读取桥接端真实端口与令牌，生成 `ailink://` 配对码与 `release/pairing.html` | `node tools/pairing/make-pairing.mjs` | 需存在 `bridge/napcat/napcat/config/onebot11.json` 或 `bridge/runtime/wechat-config.json` |
| `runtime/prepare-proot.py` | 从 Termux deb 装配 proot 系列二进制到 `android/app/src/main/jniLibs/` | `python tools/runtime/prepare-proot.py` | 需要网络；缓存落在 `.cache/termux-deb/` |
| `runtime/install-qq.sh` | 在 proot 容器内安装 QQ Linux + 注入 NapCat 的**唯一真源** | 由 App 投放执行，不手工跑 | 必须 LF 行尾；改动后同步提升 `LocalRuntime.SCRIPT_VERSION` |
| `termux/termux-napcat.sh` | 在手机 Termux 中一键部署 proot + QQ + NapCat | `bash tools/termux/termux-napcat.sh` | 手机需装 F-Droid 版 Termux |
| `vendor/qrcode.js` | 第三方二维码库（`qrcode-generator`），仅被 pairing 脚本引用 | — | 纯文本，入库 |

## 约定

- 所有脚本用 `__file__` / `import.meta.url` 定位工作区根，**不依赖调用者的当前目录**。
  新增脚本若要读写工作区内的路径，请沿用 `tools/<分类>/` 向上两级（Python 用三级）的写法。
- `tools/` 下不放一次性排障脚本，这类脚本进 `archive/diagnostics/`。
