#!/bin/bash
# =====================================================================
# 在容器内安装 QQ Linux arm64 3.2.34-53644 + NapCat Shell
#
# 由 App 通过 proot 执行：
#   proot ... /bin/bash /opt/ailink/install-qq.sh
#
# 事实依据（来自官方包，非推测）：
#   * QQ deb 实测 198 MB，Installed-Size 640 MB，Depends 见下方 PKGS
#   * NapCat 注入方式取自官方 install.sh：
#       1) 复制到 resources/app/app_launcher/napcat
#       2) 写 resources/app/loadNapCat.js
#       3) 改 resources/app/package.json 的 main = "./loadNapCat.js"
#
# 需要网络；首次约 5–10 分钟。容器内占用约 800 MB。
# =====================================================================
set -o pipefail

QQ_VERSION="3.2.34-53644"
QQ_DEB_URL="https://qqdl.gtimg.cn/qqfile/QQNT/9.9.36/beta/9ee04bef/linuxqq_${QQ_VERSION}_arm64.deb"
NAPCAT_URL="https://github.com/NapNeko/NapCatQQ/releases/latest/download/NapCat.Shell.zip"

QQ_BASE=/opt/QQ
APP_DIR="$QQ_BASE/resources/app"
WORKDIR=/opt/ailink
STAMP="$WORKDIR/.qq-installed"

log() { echo "[$(date +%H:%M:%S)] $*"; }

# 诊断：定位 shell 与 PATH（proot 下曾出现 unzip 找不到的情况）
log "shell=$BASH_VERSION PATH=$PATH"
if [ -x /usr/bin/unzip ]; then
    UNZIP=/usr/bin/unzip
elif [ -x /bin/unzip ]; then
    UNZIP=/bin/unzip
else
    UNZIP=unzip
fi
log "unzip 路径：$UNZIP（command -v 结果：$(command -v unzip || echo 未找到)）"
fail() { echo "[ERR] $*" >&2; exit 1; }

if [ -f "$STAMP" ]; then
    log "QQ 运行时已安装，跳过。重装请先删除 $STAMP"
    exit 0
fi

mkdir -p "$WORKDIR"

# 临时目录自愈：容器的 TMPDIR 可能指向 Android 路径（rootfs 内不存在）
mkdir -p /tmp 2>/dev/null
if ! mktemp -d >/dev/null 2>&1; then
    export TMPDIR="$WORKDIR/tmp"
    mkdir -p "$TMPDIR"
    log "[WARN] 默认临时目录不可用，改用 $TMPDIR"
else
    log "临时目录正常：$(mktemp -d)"
fi

# 日志由 App 侧落盘（proot 下 `exec > >(tee ...)` 的进程替换不可靠）

# ---------- 1. 依赖 ----------
log "步骤 1/5：安装系统依赖（约 150 MB，视网速需数分钟）"
export DEBIAN_FRONTEND=noninteractive

# 上一次失败可能留下半配置状态（dpkg 卡住），先修复
if ! dpkg --audit >/dev/null 2>&1 || [ -n "$(dpkg --audit 2>/dev/null)" ]; then
    log "检测到 dpkg 未完成状态，尝试修复"
    dpkg --configure -a >/dev/null 2>&1 || true
fi
# update 偶发失败不应中断整个安装：镜像索引多已缓存在 rootfs 中
apt-get update -y -qq || log "[WARN] apt-get update 返回非零，继续尝试安装"

# QQ deb 的 Depends + NapCat 官方 Debian 依赖清单
PKGS="unzip zip jq curl ca-certificates apt-utils debconf xvfb screen xauth procps \
libgtk-3-0 libnotify4 libnss3 libxss1 libxtst6 xdg-utils \
libatspi2.0-0 libuuid1 libsecret-1-0 \
libgbm1 libasound2 libx11-6 libxcb1 libxkbcommon0 libdrm2 \
libcups2 libpango-1.0-0 libcairo2 libgssapi-krb5-2 \
libxcb-icccm4 libxcb-image0 libxcb-keysyms1 libxcb-randr0 libxcb-render-util0 \
libxcb-shape0 libxcb-xinerama0 libxcb-xkb1 libxkbcommon-x11-0 \
libegl1 libgles2 libxdamage1 libxrandr2 libxcomposite1 libxfixes3 \
libxi6 libxtst6 libxrender1 fontconfig libfreetype6 libdbus-1-3"

# 关键能力清单：只要这些就位就算可用
CRIT_BINS="/usr/bin/xvfb-run /usr/bin/unzip /usr/bin/jq /usr/bin/curl"
CRIT_LIBS="libgtk-3.so.0 libnss3.so libgbm.so.1 libasound.so.2 libxkbcommon.so.0"

check_missing() {
    MISSING=""
    for bin in $CRIT_BINS; do
        [ -x "$bin" ] || MISSING="$MISSING $bin"
    done
    for lib in $CRIT_LIBS; do
        ls /usr/lib/*/"$lib" >/dev/null 2>&1 || MISSING="$MISSING $lib"
    done
    echo "$MISSING" | sed 's/^ *//'
}

# 第一轮：正常安装。ca-certificates 的 postinst 在 perl 升级窗口期可能失败，
# 导致 apt 中止、部分包停在"已解包未配置"状态，因此不能只看 apt 退出码。
apt-get install -y -qq $PKGS || log "[WARN] apt 返回错误，继续按关键能力判定"

MISSING=$(check_missing)
if [ -n "$MISSING" ]; then
    log "[WARN] 缺少：$MISSING，尝试修复"
    dpkg --configure -a >/dev/null 2>&1 || true
    apt-get -f install -y -qq >/dev/null 2>&1 || true
    # 定向重装缺失的提供者（--reinstall 会把半解包状态纠正过来）
    apt-get install -y -qq --reinstall unzip zip jq curl xvfb screen xauth procps \
        libgtk-3-0 libnss3 libgbm1 libasound2 libxkbcommon0 >/dev/null 2>&1 || true
    MISSING=$(check_missing)
fi

if [ -n "$MISSING" ]; then
    fail "关键依赖缺失：$MISSING（详见上方 apt 输出）"
fi
log "关键依赖校验通过（忽略 ca-certificates 配置告警）"

# ---------- 2. QQ ----------
cd "$WORKDIR" || fail "无法进入 $WORKDIR"

if [ -x "$QQ_BASE/qq" ]; then
    log "步骤 2-3/5：QQ 已解包，跳过下载"
else
    log "步骤 2/5：下载 QQ Linux arm64（198 MB，支持断点续传）"
    # 移动网络下载 198 MB 容易中途断流（curl 56 / SSL_read abort）。
    # 用 -C - 断点续传 + 多轮重试，避免每次都从 0 开始。
    EXPECTED_SIZE=207538924
    attempt=1
    while [ "$attempt" -le 6 ]; do
        have=$(stat -c %s qq.deb 2>/dev/null || echo 0)
        if [ "$have" -ge "$EXPECTED_SIZE" ]; then
            break
        fi
        # 连续失败两轮后丢弃残缺文件，避免坏前缀导致解包失败
        if [ "$attempt" -ge 3 ]; then
            log "重新开始下载（丢弃已下载部分）"
            rm -f qq.deb
            have=0
        fi
        log "第 $attempt 次尝试（已下载 $((have / 1024 / 1024)) MB）"
        curl -fL -C - --retry 3 --retry-delay 2 --retry-all-errors \
             --connect-timeout 30 --max-time 1800 \
             -o qq.deb "$QQ_DEB_URL" && break
        attempt=$((attempt + 1))
        sleep 3
    done

    have=$(stat -c %s qq.deb 2>/dev/null || echo 0)
    if [ "$have" -lt "$EXPECTED_SIZE" ]; then
        fail "QQ 下载不完整（$((have / 1024 / 1024)) MB / 197 MB），请检查网络后重试；已下载部分会保留"
    fi
    log "下载完成：$((have / 1024 / 1024)) MB"

    # 完整性校验：能读出 control 信息才说明 deb 结构完整
    if ! dpkg-deb -I qq.deb >/dev/null 2>&1; then
        rm -f qq.deb
        fail "QQ 安装包校验失败（文件可能损坏），已删除，请重试"
    fi


    log "步骤 3/5：解包 QQ"
    # 注意：deb 内部路径是 ./opt/QQ/...，直接 -x 到 /opt 会得到 /opt/opt/QQ。
    # 先解到临时目录，再整体搬到 /opt/QQ。
    rm -rf "$WORKDIR/qq" && mkdir -p "$WORKDIR/qq"
    dpkg-deb -x qq.deb "$WORKDIR/qq" || fail "QQ 解包失败"
    [ -d "$WORKDIR/qq/opt/QQ" ] || fail "解包结构异常：未找到 opt/QQ"
    rm -rf /opt/QQ /opt/opt
    cp -a "$WORKDIR/qq/opt/QQ" /opt/QQ || fail "复制 QQ 到 /opt/QQ 失败"
    rm -rf "$WORKDIR/qq"
    [ -x "$QQ_BASE/qq" ] || fail "解包后未找到 $QQ_BASE/qq"
fi

# ---------- 3. NapCat ----------
log "步骤 4/5：准备 NapCat"

# App 已把随包 NapCat 解压到 $WORKDIR/napcat（不依赖容器内的 unzip）
if [ -f "$WORKDIR/napcat/napcat.mjs" ]; then
    log "使用 App 解压好的 NapCat：$(ls "$WORKDIR/napcat" | wc -l) 个顶层条目"
    NAPCAT_DIR="$WORKDIR/napcat"
else
    log "[WARN] 未找到预解压的 NapCat，尝试在线下载"
    NAPCAT_URL="https://gh-proxy.com/https://github.com/NapNeko/NapCatQQ/releases/latest/download/NapCat.Shell.zip"
    curl -fL --retry 2 --connect-timeout 20 --max-time 300 -o "$WORKDIR/napcat.zip" "$NAPCAT_URL" \
        || fail "NapCat 下载失败"
    rm -rf "$WORKDIR/napcat-online" && mkdir -p "$WORKDIR/napcat-online"
    if [ -x /usr/bin/unzip ]; then
        /usr/bin/unzip -q -o "$WORKDIR/napcat.zip" -d "$WORKDIR/napcat-online" || fail "NapCat 解包失败"
    elif [ -x /bin/unzip ]; then
        /bin/unzip -q -o "$WORKDIR/napcat.zip" -d "$WORKDIR/napcat-online" || fail "NapCat 解包失败"
    else
        fail "容器内缺少 unzip，请重新安装运行时（App 会负责解压）"
    fi
    NAPCAT_DIR="$WORKDIR/napcat-online"
fi

[ -f "$NAPCAT_DIR/napcat.mjs" ] || fail "NapCat 目录结构异常：缺少 napcat.mjs"

# ---------- 4. 注入 ----------
log "步骤 5/5：注入 NapCat"
rm -rf "$APP_DIR/app_launcher/napcat"
mkdir -p "$APP_DIR/app_launcher"
cp -r "$NAPCAT_DIR" "$APP_DIR/app_launcher/napcat" || fail "复制 NapCat 失败"
[ -f "$APP_DIR/app_launcher/napcat/napcat.mjs" ] || fail "缺少 napcat.mjs"

cat > "$APP_DIR/loadNapCat.js" <<'JS'
const path = require('path');
const { pathToFileURL } = require('url');
const mainPath = process.env.NAPCAT_MAIN_PATH ||
  path.join(__dirname, 'app_launcher/napcat/napcat.mjs');
(async () => {
  await import(pathToFileURL(mainPath).href);
})();
JS

# 把 QQ 入口改成 loadNapCat.js
if command -v jq >/dev/null 2>&1; then
    jq '.main = "./loadNapCat.js"' "$APP_DIR/package.json" > "$APP_DIR/package.json.tmp" \
        && mv "$APP_DIR/package.json.tmp" "$APP_DIR/package.json" \
        || fail "改写 package.json 失败"
else
    fail "缺少 jq，无法改写入口"
fi

# ---------- 5. OneBot 配置 ----------
mkdir -p "$APP_DIR/app_launcher/napcat/config"
if [ ! -f "$APP_DIR/app_launcher/napcat/config/onebot11.json" ]; then
    cat > "$APP_DIR/app_launcher/napcat/config/onebot11.json" <<'JSON'
{
  "network": {
    "httpServers": [],
    "httpClients": [],
    "websocketServers": [
      {
        "name": "AiLink-WS",
        "enable": true,
        "host": "127.0.0.1",
        "port": 3001,
        "messagePostFormat": "array",
        "reportSelfMessage": false,
        "token": "ailink-local",
        "enableForcePushEvent": true,
        "debug": false,
        "heartInterval": 30000
      }
    ],
    "websocketClients": []
  },
  "musicSignUrl": "",
  "enableLocalFile2Url": false,
  "parseMultMsg": false
}
JSON
fi

rm -f "$WORKDIR/qq.deb"
touch "$STAMP"
log "安装完成"
log "启动命令：xvfb-run -a /opt/QQ/qq --no-sandbox"
log "二维码：/opt/QQ/resources/app/app_launcher/napcat/cache/qrcode.png"
