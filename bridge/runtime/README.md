# bridge/runtime/ —— 桥接端运行期状态

这里的文件由桥接端进程在运行中读写，**整体被 `.gitignore` 排除**（除本说明）。  
把运行期状态与 `bridge/wechat/`、`bridge/scripts/` 的代码分开，避免代码目录里  
混进带令牌、带 wxid 的配置与不断增长的日志。

| 路径                       | 生产者                               | 内容                                            |
| ------------------------ | --------------------------------- | --------------------------------------------- |
| `wechat-config.json`     | 人工维护                              | 微信适配层配置（端口、令牌、hook 地址、`selfWxid`）。**含凭据，勿外传** |
| `state/ids.json`         | `bridge/wechat/onebot-bridge.mjs` | 微信字符串 ID ↔ 数字 ID 映射表，重启不丢                     |
| `logs/wechat-bridge.log` | 同上                                | 适配层日志（含 `[RAW]` 原始回调）                         |
| `logs/napcat.log`        | `bridge/scripts/start-napcat.bat` | NapCat 控制台镜像                                  |
| `logs/emulator.log`      | 模拟器调试                             | 历史留档                                          |

## 首次部署

```bash
cp bridge/wechat/config.example.json bridge/runtime/wechat-config.json
# 然后按需修改 wsPort / wsToken / selfWxid
```

`selfWxid` 必须填你自己账号的 wxid：hook 的 `GetSelfProfile.wxid` 在收到消息后会  
漂移成「最近联系人」，适配层只用首次读到的值兜底，配置项是唯一可靠的锚点。

