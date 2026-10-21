#!/usr/bin/env node
/**
 * 生成 AiLink 一键配对码（含二维码）。
 *
 * 从本机桥接端配置里读出端口与令牌，拼成一条可扫码 / 可粘贴的字符串：
 *   ailink://pair?platform=qq&host=192.168.1.10&port=3001&token=xxx&name=QQ
 *
 * 用法：
 *   node tools/pairing/make-pairing.mjs                  # 生成 release/pairing.html 并打印配对码
 *   node tools/pairing/make-pairing.mjs --qr             # 额外在终端直接打印二维码
 *   node tools/pairing/make-pairing.mjs --host 1.2.3.4   # 指定对外地址（云主机场景）
 *   node tools/pairing/make-pairing.mjs --only wechat    # 只看微信
 *   node tools/pairing/make-pairing.mjs --no-html --json # 纯文本 / 机器可读
 */

import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';

const require = createRequire(import.meta.url);
const qrlib = require('../vendor/qrcode.js');

// tools/pairing/ 位于工作区下两级，root 指向工作区根
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');

const has = (flag) => process.argv.includes(`--${flag}`);
const valueOf = (name, fallback = '') => {
  const i = process.argv.indexOf(`--${name}`);
  return i >= 0 && process.argv[i + 1] ? process.argv[i + 1] : fallback;
};

const only = valueOf('only', '');
const wantJson = has('json');
// 默认生成 HTML（SVG 二维码，任何环境都能打开）；终端二维码需显式 --qr，
// 因为部分 Windows 终端不解析 ANSI 背景色，直接打印会变成乱码。
const wantHtml = !has('no-html');
const wantQr = has('qr');

function readJson(p) {
  try {
    return JSON.parse(fs.readFileSync(p, 'utf8'));
  } catch {
    return null;
  }
}

/** 选一个最可能被手机访问到的内网地址。 */
function detectLanIp() {
  const override = valueOf('host', '');
  if (override) return override;

  const candidates = [];
  for (const [name, list] of Object.entries(os.networkInterfaces())) {
    for (const net of list || []) {
      if (net.family !== 'IPv4' || net.internal) continue;
      const isPrivate = /^192\.168\./.test(net.address)
        || /^10\./.test(net.address)
        || /^172\.(1[6-9]|2\d|3[01])\./.test(net.address);
      if (!isPrivate) continue;
      // 无线网卡优先，虚拟机 / WSL 网段靠后
      const penalty = /vEthernet|VirtualBox|VMware|WSL|Loopback/i.test(name) ? 100 : 0;
      const wifiBonus = /Wi-?Fi|WLAN|无线/i.test(name) ? -10 : 0;
      candidates.push({ address: net.address, score: penalty + wifiBonus });
    }
  }
  candidates.sort((a, b) => a.score - b.score);
  return candidates[0]?.address || '127.0.0.1';
}

function buildPairing({ platform, name, host, port, token }) {
  // 用 encodeURIComponent 而非 URLSearchParams：避免把空格编成 "+"，
  // Android 侧 Uri.getQueryParameter 对 "+" 的处理容易踩坑。
  let query = `platform=${encodeURIComponent(platform)}`
    + `&host=${encodeURIComponent(host)}&port=${port}`;
  if (token) query += `&token=${encodeURIComponent(token)}`;
  if (name) query += `&name=${encodeURIComponent(name)}`;
  return `ailink://pair?${query}`;
}

function makeQr(text) {
  const qr = qrlib(0, 'M');
  qr.addData(text);
  qr.make();
  return qr;
}

/** 终端二维码：每个模块两个空格 + 背景色，深色模块用黑底。 */
function renderTerminalQr(text) {
  const qr = makeQr(text);
  const n = qr.getModuleCount();
  const BLACK = '\x1b[40m  ';
  const WHITE = '\x1b[47m  ';
  const RESET = '\x1b[0m';
  const quiet = 2;
  const blankRow = WHITE.repeat(n + quiet * 2) + RESET;

  const lines = [];
  for (let i = 0; i < quiet; i++) lines.push(blankRow);
  for (let r = 0; r < n; r++) {
    let line = WHITE.repeat(quiet);
    for (let c = 0; c < n; c++) line += qr.isDark(r, c) ? BLACK : WHITE;
    line += WHITE.repeat(quiet) + RESET;
    lines.push(line);
  }
  for (let i = 0; i < quiet; i++) lines.push(blankRow);
  return lines.join('\n');
}

const escapeHtml = (s) => String(s).replace(/[&<>"]/g, (c) => (
  { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]
));

function buildHtml(items, host) {
  const cards = items.map((it) => `
      <section class="card">
        <header>
          <h2>${escapeHtml(it.label)}</h2>
          <span class="ep">${escapeHtml(it.endpoint)}</span>
        </header>
        <div class="qr">${makeQr(it.pairing).createSvgTag({ cellSize: 5, margin: 6, scalable: true })}</div>
        <div class="code">${escapeHtml(it.pairing)}</div>
        <p class="note">${escapeHtml(it.note)}</p>
      </section>`).join('');

  return `<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8" />
<meta name="viewport" content="width=device-width, initial-scale=1" />
<title>接入助手 · 配对码</title>
<style>
  :root { --bg:#F5F6FA; --card:#fff; --line:#E5E8F0; --text:#14161C; --muted:#6B7280; }
  * { box-sizing: border-box; }
  body { margin:0; padding:40px 24px 56px; background:var(--bg); color:var(--text);
         font-family:-apple-system,BlinkMacSystemFont,"Segoe UI","PingFang SC","Microsoft YaHei",sans-serif; }
  .wrap { max-width: 900px; margin: 0 auto; }
  h1 { font-size: 22px; margin: 0 0 6px; font-weight: 600; }
  .sub { color: var(--muted); font-size: 13px; margin: 0 0 26px; }
  .grid { display: flex; gap: 20px; flex-wrap: wrap; }
  .card { flex: 1 1 320px; background: var(--card); border: 1px solid var(--line);
          border-radius: 18px; padding: 22px; }
  .card header { display: flex; align-items: baseline; gap: 10px; margin-bottom: 16px; flex-wrap: wrap; }
  .card h2 { font-size: 15px; margin: 0; font-weight: 600; }
  .ep { font-size: 12px; color: var(--muted); font-family: ui-monospace, Consolas, monospace; }
  .qr { display:grid; place-items:center; padding: 10px; background:#fff; border-radius:12px; }
  .qr svg { width: 100%; max-width: 240px; height: auto; }
  .code { margin-top: 14px; font-family: ui-monospace, Consolas, monospace; font-size: 11px;
          color:#4B5563; background:#F3F5F9; border-radius:10px; padding:10px 12px;
          word-break: break-all; line-height:1.6; }
  .note { margin: 10px 0 0; font-size: 12px; color: var(--muted); }
  .steps { margin-top: 28px; background: var(--card); border: 1px solid var(--line);
           border-radius: 18px; padding: 22px; }
  .steps h3 { font-size: 14px; margin: 0 0 10px; }
  .steps ol { margin: 0; padding-left: 20px; font-size: 13px; line-height: 2; color:#374151; }
  .steps code { background:#F3F5F9; padding: 1px 6px; border-radius: 5px; font-size: 12px; }
  .tip { margin-top: 18px; font-size: 12px; color:#8A5A00; background:#FFF7E8;
         border-radius: 12px; padding: 12px 14px; line-height: 1.7; }
</style>
</head>
<body>
  <div class="wrap">
    <h1>接入助手 · 配对码</h1>
    <p class="sub">生成于 ${new Date().toLocaleString('zh-CN')} · 本机地址 ${escapeHtml(host)} · 手机与桥接端需网络互通</p>
    <div class="grid">${cards}
    </div>
    <div class="steps">
      <h3>怎么用</h3>
      <ol>
        <li>手机用<b>系统相机</b>或浏览器扫码，识别到 <code>ailink://</code> 链接后会提示打开「接入助手」；</li>
        <li>若系统不识别自定义链接，复制下方那串配对码，到 App「账号 → 配对码导入」粘贴；</li>
        <li>导入后 App 会自动填好平台、地址、令牌，并立即测试连接；</li>
        <li>点「保存」，打开概览页的接入服务开关即可。</li>
      </ol>
      <div class="tip">
        安全提示：配对码里包含桥接端令牌，等同于该接入端的登录凭证，请勿公开发布。
        本页面由本机脚本生成，不联网、不上传。
      </div>
    </div>
  </div>
</body>
</html>`;
}

// ---------------- 收集桥接端信息 ----------------

const host = detectLanIp();
const napcatConfig = readJson(path.join(root, 'bridge/napcat/napcat/config/onebot11.json'));
const wxConfig = readJson(path.join(root, 'bridge/runtime/wechat-config.json'));

const targets = [];

const wsServer = napcatConfig?.network?.websocketServers?.find((s) => s.enable !== false);
if (wsServer) {
  targets.push({
    label: 'QQ（NapCat）',
    platform: 'qq',
    name: 'QQ 账号',
    host,
    port: wsServer.port,
    token: wsServer.token || '',
    note: `NapCat 监听 ${wsServer.host}:${wsServer.port}`
  });
}

if (wxConfig) {
  targets.push({
    label: '微信（本机 Hook 桥接）',
    platform: 'wechat',
    name: '微信账号',
    host,
    port: wxConfig.wsPort || 3002,
    token: wxConfig.wsToken || '',
    note: `Hook ${wxConfig.hookBase} → WebSocket ${wxConfig.wsPort}`
  });
}

const selected = only ? targets.filter((t) => t.platform === only) : targets;

if (selected.length === 0) {
  console.error('[ERR] 没有找到可用的桥接端配置。');
  console.error('      期望文件：bridge/napcat/napcat/config/onebot11.json 或 bridge/runtime/wechat-config.json');
  process.exit(1);
}

const items = selected.map((t) => ({
  ...t,
  endpoint: `ws://${t.host}:${t.port}`,
  pairing: buildPairing(t)
}));

if (wantJson) {
  console.log(JSON.stringify({ host, items }, null, 2));
  process.exit(0);
}

console.log('');
console.log('AiLink 配对码');
console.log('='.repeat(64));
console.log(`本机地址：${host}（不同网段请用 --host 指定）`);
console.log('');

for (const item of items) {
  console.log(`● ${item.label}`);
  console.log(`  地址：${item.endpoint}`);
  console.log(`  令牌：${item.token || '（无）'}`);
  console.log(`  配对：${item.pairing}`);
  console.log(`  说明：${item.note}`);
  if (wantQr) {
    console.log('');
    console.log(renderTerminalQr(item.pairing));
  }
  console.log('');
}

console.log('-'.repeat(64));
console.log('用法（任选其一）：');
console.log('  1) 手机系统相机 / 浏览器扫描上面的二维码，识别后直接唤起 App；');
console.log('  2) 复制「配对」那一行，在 App 的「账号 → 配对码导入」粘贴；');
console.log('  3) 打开 release/pairing.html，把二维码投屏给别人扫。');
console.log('');

if (wantHtml) {
  const file = path.join(root, 'release/pairing.html');
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, buildHtml(items, host), 'utf8');
  console.log(`[OK] 已生成：${file}`);
  console.log('');
}
