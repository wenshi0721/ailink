# vendor/ —— 第三方二进制与安装包

本目录存放**不参与编译、只用于部署或重装**的第三方资产，整体被 `.gitignore` 排除，
仅本说明文件入库。体积约 592 MB，删除后不影响 `android/` 的构建（但影响桥接端重装）。

| 路径 | 内容 | 来源 | 用途 |
| --- | --- | --- | --- |
| `napcat/NapCat.Shell.Windows.Node.zip` | NapCat Shell 4.18.38（124 MB，自带 node.exe） | GitHub Releases `NapNeko/NapCatQQ` | 解压到 `bridge/napcat/` 即为可运行的桥接端 |
| `wechat/WeChatWin_4.1.10.27.exe` | 微信 4.1.10.27 安装包（239 MB） | `aixed/WeChat-Hook` Release | 安装免安装副本 `D:\connect\wechat\wx4110` 供 hook 使用 |
| `wechat/wechat-version-hook.dll` | WeChat-Hook 的 `version.dll`（606 KB） | 同上 | 注入微信主进程，起本地 HTTP `:30001` |
| `wechat/OneKey.zip` | Hook 一键包 | 同上 | 备用 |
| `wechat/wx4110-stage/` | 微信 4.1.10.27 的解包暂存目录 | 本地生成 | 组装免安装副本时的中间产物 |
| `wechat/wechat-backup/` | 微信 4.1.15.55 的全局配置备份（11 个文件） | 本地备份 | 多版本共存时回滚用 |

## 为什么这些文件不放在 bridge/ 里

- `bridge/napcat/` 必须留在原位：NapCat 自身把配置写在 `napcat/config/` 下，
  `bridge/scripts/start-napcat.bat` 与 `tools/pairing/make-pairing.mjs` 都按该固定路径读取。
- `bridge/packages/` 只是「安装包留档」，与运行无关，因此统一收进 `vendor/`。

## 注意

`vendor/` 不做版本控制，换机器时需要重新下载；请按上表记录版本号，
NapCat 与 QQ 的版本是**硬绑定的**（换 QQ 版本必须确认 NapCat 支持，注入点会变）。
