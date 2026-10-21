# archive/ —— 开发过程产物归档

本目录**不是源码**，也不参与任何构建。按「疑似无用但不确定的东西先移动、不删除」的原则，
把散落在工程目录里的开发过程产物集中到这里，保持 `android/`、`bridge/` 的职责清晰。

整体已被根 `.gitignore` 排除，仅本说明文件入库。

| 子目录 | 来源 | 内容 | 为什么不删除 |
| --- | --- | --- | --- |
| `android-dev/` | 原 `android/` 工程根（38 个文件） | 16 张模拟器截图、21 份 `uiautomator dump` XML、1 份模拟器日志 | 这些是定位「proot 装不上 / QQ 起不来」时抓的现场，后续排障可能要对照 |
| `bridge-dev/` | 原 `bridge/logs/`（28 个文件） | 16 张界面截图、21 份 UI dump、`pe_strings.py`/`peinfo.py`（PE 头分析）、`postman.json`（WeChat-Hook 接口全集） | `postman.json` 是逆向 hook 接口时的原始依据；PE 分析脚本可复用于下一次版本适配 |
| `diagnostics/` | 原 `.cache/`（13 个文件） | `diag*.sh` × 11、`probe.sh`、`export-test.log` | 真机排查容器问题时的一次性诊断脚本，保留下来便于复现同类问题 |

## 注意

- 部分 UI dump 与截图**包含界面上的令牌、wxid、QQ 号**，因此本目录被 `.gitignore` 完全排除，
  也不得整体拷贝进仓库或对外分享。
- 需要恢复某个文件时，直接从对应子目录 `mv` 回原位置即可；本阶段不涉及任何代码改动，
  无需回滚。
