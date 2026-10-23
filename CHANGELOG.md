# 更新日志

本项目的所有重要变更都记录在本文件中。

格式基于 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循[语义化版本](https://semver.org/lang/zh-CN/)。

## [未发布]

## [1.1.2] - 2026-10-23

### 新增

- **图片识别**：AI 配置新增「图片识别」开关与自动检测（模型名快判 + 一张内置测试图实测，结论按地址与模型缓存，配置改动自动失效）；仅在判定支持时，把收到的图片、表情包下载转码为 JPEG 后作为多模态输入交给模型；不支持或关闭时保持原有 `[图片]` 文本行为
- 消息解析保留 OneBot `image` 段的地址与摘要，纯图片消息不再因文本为空被丢弃

### 变更

- 数据库升级到 v3：`ai_profiles` 增加 `visionEnabled` 列（默认开启），带 `MIGRATION_2_3`，既有配置升级后行为与升级前一致

## [1.1.1] - 2026-10-23

### 新增

- AI 服务「快速配置」：内置 DeepSeek / 通义千问 / Kimi / 智谱 GLM / 豆包方舟 / 硅基流动 / 腾讯混元 / OpenAI / Ollama 九家厂商模板，点选后自动填好接口地址与常用模型，只需再粘贴 API Key；手动填写任意 OpenAI 兼容地址的能力保持不变

### 修复

- 手机内置 QQ：二维码首轮只登记基线、不推送，避免用户扫到上一轮遗留的过期二维码
- 手机内置 QQ：掉线自愈窗口与 NapCat 自身的 worker 重启对齐（默认 90 秒，NapCat 自愈中延长至 180 秒），不再抢先销毁进程树抹掉其自愈机会
- 手机内置 QQ：容器重启后清除已登录状态，确保新二维码能正常推送
- 手机内置 QQ：按 NapCat 4.18.38 的实际 stdout 格式识别「账号已离线 / 正在重启 Worker」等状态

### 文档

- 新增《手机后台保活设置指南》（Android 8.0–16 与主流国产 ROM 的电池白名单、自启动、后台活动、任务加锁配置步骤），README 已链接
- 新增 `tools/docs/build-pdf.py`：把 Markdown 文档渲染为面向普通用户的 A4 PDF

## [1.1.0] - 2026-10-22

首个正式版（正式签名，包名 `com.jiuguan.ailink`）。

### 新增

- 账号接入：QQ / 微信账号经自建 OneBot v11 桥接端接入，支持「测试连接」与配对码（`ailink://`）扫码/粘贴一键导入
- AI 自动回复：兼容 OpenAI Chat Completions 的任意服务；触发范围、回复延迟、忽略关键词、会话级开关、人设提示词、最近 8 条多轮上下文；未配置时内置本地模拟回复兜底
- 消息管理：前台服务常驻收发、消息落库、会话搜索与筛选、未读数、手动发消息、失败重试、AI 回复打标
- 运行统计：在线数、连接状态、心跳 RTT、回复成功率与平均首响、消息量趋势、账号分布（全部本地聚合）
- 手机内置 QQ 运行时：proot 容器 + Ubuntu rootfs + QQ Linux + NapCat 随 APK 内置，手机内单机运行桥接端（含二维码登录、掉线自愈、开机自启）
- **应用内自更新**：启动时静默检查 GitHub Releases（6 小时节流），设置页可手动检查；发现新版本后应用内下载（带进度）并交给系统安装器安装；下载支持可配置镜像前缀，缓解国内直连 GitHub 不稳定
- 可靠性：指数退避重连、JSON 心跳、消息幂等去重、AI 调用失败降级与系统提示

### 变更

- 正式版包名为 `com.jiuguan.ailink`；此前装过 `.debug` 后缀调试包的设备需手动安装一次 1.1.0

### 已知限制

- 内置 QQ 运行时首次登录需第二块屏幕扫码（平台安全策略）
- NapCat 组件禁止商用，含该组件的 APK 不可商业分发（详见 THIRD-PARTY-NOTICES.md）

[未发布]: https://github.com/wenshi0721/ailink/compare/1.1.2...HEAD
[1.1.2]: https://github.com/wenshi0721/ailink/releases/tag/1.1.2
[1.1.1]: https://github.com/wenshi0721/ailink/releases/tag/1.1.1
[1.1.0]: https://github.com/wenshi0721/ailink/releases/tag/1.1.0
