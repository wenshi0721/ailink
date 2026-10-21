# 桥接端（NapCat + OneBot v11）

本目录是 AiLink 的**真实协议接入端**：NapCat 把 QQ 账号包装成 OneBot v11 服务，
AiLink 通过正向 WebSocket 连接它完成收消息、发消息、自动回复。

```
bridge/
├── napcat/                  NapCat Shell (Windows/Node) 4.18.38 —— 不入库，见下方「从零安装」
│   └── napcat/config/       ← 本项目的配置文件在这里
│       ├── onebot11.json    OneBot v11 网络配置（正向 WS:3001）
│       ├── webui.json       WebUI 配置（6099）
│       └── napcat.json      日志与注入选项
├── packages/                安装包留档（可删）
├── scripts/
│   ├── start-napcat.bat     启动桥接端（双击运行）
│   ├── stop-napcat.bat      结束 QQ / NapCat
│   ├── allow-firewall.bat   放行 3001 / 6099（需管理员，只需跑一次）
│   └── test-onebot.mjs      OneBot 连通性自检
└── logs/napcat.log          运行日志
```

## 〇、从零安装 NapCat（仓库不含该目录）

`bridge/napcat/` 属于第三方分发包，不随仓库分发，需自行获取：

1. 安装 QQ NT（Windows）：`winget install Tencent.QQ.NT`；
2. 下载 NapCat Shell（Windows）：从 <https://github.com/NapNeko/NapCatQQ/releases> 获取 `NapCat.Shell.zip`；
3. 解压到 `bridge/napcat/`（解压后应存在 `bridge/napcat/napcat/loadNapCat.js`）；
4. 继续下面的「启动与登录」。

 NapCat 与 QQ 版本强绑定：换 QQ 版本须确认 NapCat 支持，否则注入会失败。

## 一、当前安装状态

| 组件 | 版本 | 位置 |
| -- | -- | -- |
| QQ NT | 9.9.33.52230 | `C:\Program Files\Tencent\QQNT\QQ.exe` |
| NapCat Shell | 4.18.38 | `bridge/napcat/`（自带 node.exe，无需另装 Node） |
| OneBot 正向 WS | `ws://0.0.0.0:3001` | 已启用，token 见配置 |
| WebUI | `http://127.0.0.1:6099/webui` | 已启用 |

**实测结果（2026-10-20 17:15）**：注入成功、WebUI 200、扫码登录成功（测试账号），
3001 已监听、`get_login_info` 与 `get_status` 均返回 ok（online=true），
内网地址 `ws://<电脑内网IP>:3001` 与 `ws://127.0.0.1:3001` 双向可达。
AiLink 侧已建号并握手成功（耗时 73 ms），账号状态「在线」，服务总开关 2/2 在线。

> 为什么不选 LLOneBot：LLOneBot 是 LiteLoaderQQNT 插件，链路为「QQ + LiteLoader 注入器 + 插件」，
> QQ 每次自动更新都可能让注入器失效，需要重装。NapCat 是独立 Shell + 单点注入，
> 升级只需替换 `bridge/napcat` 目录，更适合长期挂着跑。两者协议一致，
> AiLink 侧无需任何改动即可切换。

## 二、启动与登录（三步）

### 1. 启动桥接端

双击 `scripts/start-napcat.bat`。

- 脚本以当前权限启动 NapCat；若注入失败（控制台无二维码、QQ 一闪而过），
  右键该 bat 选「以管理员身份运行」重试一次；
- 若提示 `QQ.exe is already running`，先完全退出 QQ（托盘右键退出）或跑 `stop-napcat.bat`；
- 控制台会打印 ASCII 二维码，同时保存到 `napcat\napcat\cache\qrcode.png`。

### 2. 扫码登录

用手机 QQ「扫一扫」扫控制台二维码或那张 PNG。

登录成功后控制台会出现：

```
[info] [OneBot11] WsServer 服务启动: 0.0.0.0:3001
[info] [NapCat] 登录成功
```

### 3. 放行局域网（手机连 PC 时必做一次）

双击 `scripts/allow-firewall.bat`（需管理员）。它会为 TCP **3001**、**6099** 添加入站允许规则。

- 手机与电脑必须同一局域网；
- 本机 WLAN 地址：`<电脑内网IP>`（以 `ipconfig` 实际为准，换网络会变）；
- 如果手机用的是**模拟器**，可跳过防火墙，改用端口反向映射：
  `adb reverse tcp:3001 tcp:3001`，然后在 App 里填 `ws://127.0.0.1:3001`。
  **模拟器每次重启后 reverse 映射会丢失，需重新执行该命令**；也可改填 `ws://10.0.2.2:3001`
  （模拟器访问宿主机的固定地址），后者不依赖 adb，但宿主机防火墙需放行。

## 三、验证桥接端

```bash
# 本机自测（QQ 已登录后）
node bridge/scripts/test-onebot.mjs

# 带 token
node bridge/scripts/test-onebot.mjs ws://127.0.0.1:3001 <token>
```

期望输出：

```
[2/3] 握手成功，发送 get_login_info ...
      已登录 QQ：xxx (123456789)
      在线状态：online=true  good=true
[PASS] OneBot v11 桥接端工作正常
```

## 四、AiLink 侧接入配置

打开 App → **账号** → 新增账号：

| 字段 | 填写 |
| -- | -- |
| 平台 | QQ |
| 备注名 | 任意，例如「主号」 |
| 账号号码 | 扫码登录的那个 QQ 号 |
| 接入方式 | **OneBot v11 桥接** |
| WebSocket 地址 | `ws://<电脑内网IP>:3001`（手机连 PC，用电脑内网 IP）<br>`ws://127.0.0.1:3001`（模拟器 + adb reverse） |
| 令牌 | 与 `config/onebot11.json` 里 `websocketServers[0].token` 一致 |
| 触发范围 | 先选「仅私聊」验证，跑通后再放开「私聊 + 群@」 |

保存前点 **测试连接**，返回成功后回概览页打开「接入服务」总开关，账号状态变「在线」即接通。

AI 服务：新增 AI 服务填 `https://api.deepseek.com/v1` + 密钥 + `deepseek-chat`；
地址留空则走内置模拟回复，可先验证链路。

## 五、修改端口与令牌

改 `bridge/napcat/napcat/config/onebot11.json`：

```json
"websocketServers": [{
  "name": "AiLink-WS",
  "enable": true,
  "host": "0.0.0.0",
  "port": 3001,
  "messagePostFormat": "array",
  "reportSelfMessage": false,
  "token": "<你的令牌>",
  "heartInterval": 30000
}]
```

改完重启桥接端生效。`host` 必须保持 `0.0.0.0`，改成 `127.0.0.1` 手机就连不上。

登录后 NapCat 会为每个账号生成 `onebot11_<QQ号>.json`，**该文件优先级高于 `onebot11.json`**，
后续改端口要改带 QQ 号的那份。

## 六、常见问题

| 现象 | 原因与处理 |
| -- | -- |
| `QQ installation was not found in the registry` | 用了 NapCat 自带 launcher。走 `scripts/start-napcat.bat`，它不依赖注册表。 |
| 控制台无二维码 / 立刻退出 | QQ 已在运行占用，先 `stop-napcat.bat`；或 QQ 版本与 NapCat 不匹配，升级 NapCat 或换 QQ 版本。 |
| 3001 不监听 | 未登录成功，或 `onebot11.json` 里 `enable` 为 false。看 WebUI 的「网络配置」。 |
| App 测试连接超时 | 防火墙未放行 / 手机不在同一网段 / 用了 `127.0.0.1` 却从手机连。 |
| App 连上但收不到消息 | `reportSelfMessage` 已关（自己发的消息不上报，属预期）；确认触发范围与忽略词。 |
| 扫码后提示版本过低 | QQ 需升级：`winget upgrade Tencent.QQ.NT`，然后重启桥接端。 |
| 想换账号 | 关掉桥接端，删除 `napcat/cache`，重启后重新扫码。 |

## 七、风险提示

NapCat 属于非官方协议实现，可能触发平台风控。请仅接入本人有合法使用权的账号，
先用小号验证；不要用于营销群发、自动加好友等行为。生产场景建议改用
企业微信应用或 QQ 官方机器人开放平台的正式通道（AiLink 的 `MessageGateway`
抽象已为此预留扩展点）。
