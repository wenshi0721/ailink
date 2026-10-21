#!/data/data/com.termux/files/usr/bin/bash
# =====================================================================
# AiLink 手机端桥接端一键部署（QQ / NapCat）
#
# 作用：在一台 Android 手机上装好 proot 容器 + QQ Linux arm64 + NapCat，
#      让 AiLink 直接连 ws://127.0.0.1:3001，不再需要电脑。
#
# 用法（在手机 Termux 里）：
#   bash termux-napcat.sh          # 安装并给出启动命令
#   bash termux-napcat.sh --start  # 安装后直接启动 QQ（首次需扫码）
#
# 依据：NapCat 官方 Shell 文档 https://napneko.github.io/guide/boot/Shell
# 说明：本脚本是对官方步骤的封装，NapCat 更新后若命令变化，以官方文档为准。
# =====================================================================

set -u

START_AFTER_INSTALL=0
[ "${1:-}" = "--start" ] && START_AFTER_INSTALL=1

echo "=============================================="
echo " AiLink 手机端桥接端部署"
echo "=============================================="

# ---------- 0. 环境检查 ----------
if ! command -v pkg >/dev/null 2>&1; then
  echo "[ERR] 当前不是 Termux 环境。"
  echo "      请从 F-Droid 或 GitHub 安装 Termux（应用商店版本过旧，缺少 proot-distro）。"
  exit 1
fi

ARCH=$(uname -m)
echo "[INFO] 架构: $ARCH"
case "$ARCH" in
  aarch64|arm64) ;;
  *)
    echo "[ERR] 当前架构 $ARCH 不受支持：QQ Linux 仅提供 amd64 / arm64 版本，"
    echo "      Android 手机需要 arm64（2019 年后的机型基本都是）。"
    exit 1
    ;;
esac

free_mb=$(df -m "$HOME" | awk 'NR==2 {print $4}')
echo "[INFO] 可用空间: ${free_mb} MB（建议 ≥ 4096 MB）"
if [ "${free_mb:-0}" -lt 3000 ]; then
  echo "[WARN] 空间偏紧：Linux QQ + 容器约需 2-3 GB。"
  echo "       可先执行 pkg clean 或清理下载目录后再继续。"
fi

# ---------- 1. 依赖 ----------
echo ""
echo "[1/4] 安装依赖（proot-distro / curl）"
pkg update -y >/dev/null 2>&1 || true
pkg install -y proot-distro curl || { echo "[ERR] 依赖安装失败"; exit 1; }

# ---------- 2. NapCat 官方安装器 ----------
echo ""
echo "[2/4] 拉取并执行 NapCat 官方 Termux 安装器"
INSTALLER="$HOME/napcat.termux.sh"
if ! curl -fL --connect-timeout 20 -o "$INSTALLER" \
  https://raw.githubusercontent.com/NapNeko/NapCat-Installer/main/script/install.termux.sh; then
  echo "[ERR] 安装器下载失败（GitHub 直连受限）。"
  echo "      可先配置代理，或改用镜像地址后重试。"
  exit 1
fi
bash "$INSTALLER" || { echo "[ERR] NapCat 安装器执行失败，请查看上方日志"; exit 1; }

# ---------- 3. 启动命令 ----------
START_CMD="proot-distro login napcat -- bash -c 'xvfb-run -a /root/Napcat/opt/QQ/qq --no-sandbox'"

echo ""
echo "[3/4] 安装完成"
echo "----------------------------------------------"
echo " 启动命令："
echo "   $START_CMD"
echo ""
echo " 登录方式："
echo "   启动后控制台会打印二维码，或见容器内"
echo "   /root/Napcat/opt/QQ/resources/app/app_launcher/napcat/cache/qrcode.png"
echo "   用手机 QQ 扫码登录（与你日常用的手机 QQ 可同时在线）。"
echo ""
echo " AiLink 侧配置："
echo "   账号 → 编辑 → 接入方式 OneBot v11 → 地址 ws://127.0.0.1:3001"
echo "   若 NapCat 配了 token，同步填入；点「测试连接」应显示握手成功。"
echo "----------------------------------------------"

# ---------- 4. 可选：直接启动 ----------
echo ""
if [ "$START_AFTER_INSTALL" -eq 1 ]; then
  echo "[4/4] 启动 QQ（Ctrl+C 退出；退出后桥接端随之停止）"
  echo ""
  eval "$START_CMD"
else
  echo "[4/4] 未自动启动。需要立即运行请执行："
  echo "      bash $0 --start"
  echo ""
  echo " 后台常驻提示："
  echo "   1) 在 Termux 通知栏点「Acquire wakelock」，避免息屏被挂起；"
  echo "   2) 系统设置里把 Termux 加入电池优化白名单；"
  echo "   3) 长期运行建议配合 tmux：pkg install tmux，再在 tmux 里执行启动命令。"
fi
