#!/usr/bin/env node
/**
 * Minimal Chrome DevTools Protocol probe.
 *
 * Prerequisite: Chrome started with --remote-debugging-port=9222.
 *
 * Usage:
 *   node tools/cdp_probe.js --match zhlj.whu.edu.cn
 *   node tools/cdp_probe.js --match zhlj --eval "document.title"
 *   node tools/cdp_probe.js --match zhlj --eval "await fetch('/x').then(r=>r.text())"
 */

const DEFAULT_EXPR = `(() => ({
  title: document.title,
  url: location.href,
  text: document.body.innerText.slice(0, 3000),
  resources: [...new Set(performance.getEntriesByType('resource')
    .map(e => e.name)
    .filter(u => /api|\\/pc\\/|course|kebiao|schedule|notice/i.test(u)))]
}))()`;

function arg(name, fallback = null) {
  const i = process.argv.indexOf(`--${name}`);
  if (i === -1) return fallback;
  return process.argv[i + 1] ?? true;
}

async function pickTarget(match) {
  const response = await fetch('http://127.0.0.1:9222/json/list');
  const targets = await response.json();
  const pages = targets.filter(t => t.type === 'page' && t.url.includes(match));
  if (!pages.length) {
    console.error('No matching page. Open pages:');
    for (const t of targets) console.error(`  ${t.type}  ${t.url}`);
    process.exit(2);
  }
  return pages[0];
}

async function main() {
  const match = arg('match', 'zhlj.whu.edu.cn');
  const expression = arg('eval', DEFAULT_EXPR);
  const target = await pickTarget(match);

  const socket = new WebSocket(target.webSocketDebuggerUrl);
  const pending = new Map();
  let nextId = 1;

  socket.addEventListener('message', event => {
    const message = JSON.parse(event.data);
    if (message.id && pending.has(message.id)) {
      pending.get(message.id)(message);
      pending.delete(message.id);
    }
  });

  await new Promise((resolve, reject) => {
    socket.addEventListener('open', resolve, { once: true });
    socket.addEventListener('error', reject, { once: true });
  });

  const send = (method, params = {}) => new Promise((resolve, reject) => {
    const id = nextId++;
    pending.set(id, resolve);
    socket.send(JSON.stringify({ id, method, params }));
    setTimeout(() => {
      if (pending.delete(id)) reject(new Error(`timeout: ${method}`));
    }, 30000);
  });

  const result = await send('Runtime.evaluate', {
    expression,
    awaitPromise: true,
    returnByValue: true,
    userGesture: true,
  });
  socket.close();

  if (result.result?.exceptionDetails) {
    console.error(JSON.stringify(result.result.exceptionDetails, null, 2));
    process.exit(1);
  }
  const value = result.result?.result?.value;
  if (typeof value === 'string') {
    console.log(value);
  } else {
    console.log(JSON.stringify(value, null, 2));
  }
}

main().catch(error => {
  console.error(error.message);
  process.exit(1);
});
