/**
 * OneBot v11 连通性自检（零依赖，使用 Node 内置 WebSocket）
 *
 * 用法：
 *   node test-onebot.mjs                       # 默认 ws://127.0.0.1:3001
 *   node test-onebot.mjs ws://192.168.1.10:3001 <token>
 *
 * 检查项：
 *   1. WebSocket 握手（含 token 鉴权）
 *   2. get_login_info  → 确认 QQ 已登录、拿到账号与昵称
 *   3. get_status      → 确认在线状态
 */

const url = process.argv[2] || 'ws://127.0.0.1:3001';
const token = process.argv[3] || '';

const headers = token ? { Authorization: `Bearer ${token}` } : {};
const echo = (tag) => `ailink-${tag}-${Date.now()}`;

function fail(msg) {
  console.error(`\n[FAIL] ${msg}`);
  process.exit(1);
}

console.log(`[1/3] 连接 ${url} ...`);
const ws = new WebSocket(url, { headers });

const pending = new Map();
let done = false;

const timer = setTimeout(() => {
  if (!done) fail('超时 15s：WebSocket 未完成握手。常见原因：端口未监听 / 防火墙拦截 / token 不匹配');
}, 15000);

ws.addEventListener('open', () => {
  console.log('[2/3] 握手成功，发送 get_login_info ...');
  const e = echo('login');
  pending.set(e, 'login');
  ws.send(JSON.stringify({ action: 'get_login_info', echo: e }));
});

ws.addEventListener('error', (ev) => {
  if (!done) fail(`WebSocket 错误：${ev?.message || '连接被拒绝。确认 NapCat 已启动且 QQ 已扫码登录'}`);
});

ws.addEventListener('message', (ev) => {
  let data;
  try { data = JSON.parse(typeof ev.data === 'string' ? ev.data : ev.data.toString()); } catch { return; }

  if (data.echo && pending.has(data.echo)) {
    const kind = pending.get(data.echo);
    pending.delete(data.echo);

    if (data.status !== 'ok' && data.retcode !== 0) {
      fail(`${kind} 返回失败：retcode=${data.retcode} ${data.message || ''} ${data.wording || ''}`);
    }

    if (kind === 'login') {
      const d = data.data || {};
      console.log(`      已登录 QQ：${d.nickname || '(未取到昵称)'} (${d.user_id || '?'})`);
      console.log('[3/3] 发送 get_status ...');
      const e = echo('status');
      pending.set(e, 'status');
      ws.send(JSON.stringify({ action: 'get_status', echo: e }));
    } else if (kind === 'status') {
      const d = data.data || {};
      console.log(`      在线状态：online=${d.online}  good=${d.good}`);
      done = true;
      clearTimeout(timer);
      console.log('\n[PASS] OneBot v11 桥接端工作正常，AiLink 可以按此地址接入。');
      ws.close();
      setTimeout(() => process.exit(0), 200);
    }
  }
});

ws.addEventListener('close', () => {
  if (!done) fail('连接被关闭（登录态可能失效，或 token 错误）');
});
