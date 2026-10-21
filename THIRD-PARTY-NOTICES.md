# 第三方组件声明（THIRD-PARTY NOTICES）

本项目（AiLink 自有代码）以 Apache-2.0 许可发布。但仓库与 APK 中包含以下第三方组件，它们受各自许可约束。使用、再分发本仓库或其 APK 前，请一并阅读本文件。

## 1. NapCat Shell（已捆绑）

- **组件**：`android/app/src/main/assets/runtime/NapCat.Shell.zip`（打包进 APK 的 assets）
- **来源**：<https://github.com/NapNeko/NapCatQQ>
- **许可**：**不是 MIT**。NapCat 使用其作者的「Limited Redistribution License」，要点：
  - **禁止任何商业用途**；
  - 允许原样再分发，但必须包含本许可全文并注明来源与版权信息；
  - 修改后的代码不得公开发布（本项目未修改该 zip 内的任何文件）。
- **许可全文**（取自 NapCatQQ 仓库 LICENSE，请以原仓库最新版为准）：

```
Limited Redistribution License for NapCat

Copyright © 2024 Mlikiowa

1. Usage and Reproduction:
   - Unauthorized use, reproduction, modification, or distribution of this code is prohibited without explicit permission from the main author of the NapCat repository.

2. Redistribution:
   - Redistribution of this code is permitted, provided that the full text of this license is included, and the source and copyright information is clearly stated.
   - Minor modifications and extensions are allowed for redistribution purposes, but the modified code must not be publicly released.

3. Non-Commercial Use:
   - This code is not to be used for any commercial purposes.

4. Additional Permissions:
   - Any rights not explicitly addressed in this license must be requested from and granted by the main author of the NapCat repository.

5. Disclaimer:
   - This code is provided "as is," without any express or implied warranties, including but not limited to the implied warranties of merchantability and fitness for a particular purpose. In no event shall the author be liable for any damages or other liability arising from, out of or in connection with the use or distribution of this code.
```

> **对使用本项目的直接影响**：内置 NapCat 的 APK 不可用于商业分发。商业场景请自行下载部署 NapCat 并向其作者确认授权边界。

## 2. proot 及其依赖（已捆绑，jniLibs）

- **组件**：`android/app/src/main/jniLibs/<abi>/` 下的 `libproot.so`、`libproot-loader.so`、`libtalloc.so`、`libandroid-shmem.so`、`libbusybox.so`
- **来源**：Termux 软件仓库（`packages.termux.dev`）预编译包，由 `tools/runtime/prepare-proot.py` 自动下载装配（该脚本即源码复现途径）
- **许可**：proot 为 GPL-2.0-or-later；busybox 为 GPL-2.0；libtalloc 为 LGPL；libandroid-shmem 为其上游许可。上述组件在 APK 中以独立可执行/动态库形式与本项目代码聚合（作为独立进程运行），不构成本项目代码的衍生作品；分发时请保留本声明并可通过上述脚本获取对应源码

## 3. Ubuntu base rootfs（已捆绑）

- **组件**：`android/app/src/main/assets/rootfs/<abi>.rootfs`
- **来源**：Ubuntu 官方 base rootfs（`cdimage.ubuntu.com/ubuntu-base`，Ubuntu 22.04）
- **许可**：其内容为 Ubuntu 发行版的组成包（以 GPL / LGPL / Apache / MIT 等各上游许可为主），按 Ubuntu 的发行条款再分发；运行时还会从腾讯官方 CDN 下载 QQ Linux 安装包与 apt 依赖

## 4. QQ Linux / QQ NT（运行时下载，未捆绑）

- QQ 客户端及其安装包的版权归腾讯所有，本项目仅在运行时从腾讯官方渠道下载，不包含、不修改、不再分发其任何二进制

## 5. tools/vendor/qrcode.js（已捆绑）

- **来源**：<https://www.d-project.com/>（Kazuhiko Arase）
- **许可**：MIT（版权头已完整保留在文件内）；"QR Code" 是 DENSO WAVE INCORPORATED 的注册商标

## 6. Gradle Wrapper

- `android/gradle/wrapper/gradle-wrapper.jar` 来自 Gradle 官方发行包（Apache-2.0）

## 7. AndroidX / Compose / OkHttp 等构建依赖

- 全部经 Maven 仓库解析，不随仓库分发；主要依赖为 Apache-2.0（AndroidX、Compose、OkHttp、kotlinx-serialization、kotlinx-coroutines、KSP），JUnit 为 EPL-1.0。完整清单见 `android/gradle/libs.versions.toml`
