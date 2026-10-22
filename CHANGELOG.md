# 更新日志

本项目的所有重要变更都记录在本文件中。

格式基于 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循[语义化版本](https://semver.org/lang/zh-CN/)。

## [未发布]

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

[未发布]: https://github.com/wenshi0721/ailink/compare/v1.1.0...HEAD
[1.1.0]: https://github.com/wenshi0721/ailink/releases/tag/v1.1.0
