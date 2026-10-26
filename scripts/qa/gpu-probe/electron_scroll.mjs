// QA only: compare chat scroll smoothness between the virgl and llvmpipe arms.
// The page must expose a temporary loopback DevTools port. Node 22+ supplies
// fetch/WebSocket; this opens no listener of its own.
//
// The measurement runs inside the page so the harness's own DevTools
// round-trips cannot starve the animation frames it is measuring. It records
// frame intervals during sustained wheel input, plus how long the first frame
// after each wheel takes to arrive. Chromium animates wheel scrolling, so the
// animation ramp is *not* claimed as input latency: a slow ramp and a stuttered
// paint both show up as long frame intervals, which is the comparable signal.
//
// Both arms run this identical drive sequence against the same content.
//
// Usage: node electron_scroll.mjs <port> [wheelCount] [cadenceMs] [selector]
const port = process.argv[2] || '19224';
const wheelCount = Number(process.argv[3] || 120);
const cadenceMs = Number(process.argv[4] || 33);
const selector = process.argv[5] || '#event-stream';
const WHEEL_DELTA = 120;

const targets = await (await fetch(`http://127.0.0.1:${port}/json/list`)).json();
const page = targets.find(item => item.type === 'page');
if (!page) throw new Error('No Electron page target');

const ws = new WebSocket(page.webSocketDebuggerUrl);
let nextId = 1;
const pending = new Map();
ws.onmessage = event => {
  const message = JSON.parse(event.data);
  const resolve = pending.get(message.id);
  if (resolve) { pending.delete(message.id); resolve(message); }
};
await new Promise((resolve, reject) => { ws.onopen = resolve; ws.onerror = reject; });

const send = (method, params = {}) => new Promise(resolve => {
  const id = nextId++;
  pending.set(id, resolve);
  ws.send(JSON.stringify({id, method, params}));
});

async function evaluate(expression) {
  const reply = await send('Runtime.evaluate', {expression, returnByValue: true, awaitPromise: true});
  if (reply.error || reply.result?.exceptionDetails) {
    throw new Error(JSON.stringify(reply.error ?? reply.result.exceptionDetails));
  }
  return reply.result.result.value;
}

const setup = await evaluate(`(() => {
  const target = document.querySelector(${JSON.stringify(selector)});
  if (!target) return {found: false};
  const overflow = target.scrollHeight - target.clientHeight;
  if (overflow < ${WHEEL_DELTA} * 6) return {found: false, overflow, reason: 'not enough scrollable content'};

  if (window.__qaCleanup) window.__qaCleanup();
  const state = {frames: [], wheels: 0, movedFrames: 0};
  const onWheel = event => { state.wheels++; };
  target.addEventListener('wheel', onWheel, {passive: true});
  let handle = requestAnimationFrame(function record(now) {
    state.frames.push(now);
    handle = requestAnimationFrame(record);
  });
  window.__qaCleanup = () => {
    cancelAnimationFrame(handle);
    target.removeEventListener('wheel', onWheel);
  };
  window.__qaState = state;
  window.__qaScroller = target;

  const box = target.getBoundingClientRect();
  return {found: true, overflow, scrollHeight: target.scrollHeight,
    clientHeight: target.clientHeight, tag: target.tagName, id: target.id || null,
    point: {x: Math.round(box.left + box.width / 2), y: Math.round(box.top + box.height / 2)}};
})()`);
if (!setup.found) throw new Error(`No usable scroll container: ${JSON.stringify(setup)}`);

const wheel = deltaY => send('Input.dispatchMouseEvent', {
  type: 'mouseWheel', x: setup.point.x, y: setup.point.y, deltaX: 0, deltaY,
  modifiers: 0, pointerType: 'mouse',
});

// Warm up so the first sample is not a cold raster or first-paint hitch.
for (let index = 0; index < 10; index++) { await wheel(WHEEL_DELTA); await new Promise(resolve => setTimeout(resolve, 60)); }
await evaluate(`window.__qaScroller.scrollTop = 0; window.__qaState.frames.length = 0; window.__qaState.wheels = 0;`);
await new Promise(resolve => setTimeout(resolve, 400));

// Idle frame health with no harness traffic, so it is not measured against
// the instrument itself.
await new Promise(resolve => setTimeout(resolve, 1500));
const idle = await evaluate(`(() => {
  const intervals = window.__qaState.frames.slice(1).map((now, index) => now - window.__qaState.frames[index]);
  window.__qaState.frames.length = 0;
  return quantiles(intervals);
  function quantiles(values) {
    const sorted = [...values].sort((a, b) => a - b);
    const at = q => sorted.length ? sorted[Math.min(sorted.length - 1, Math.floor(sorted.length * q))] : null;
    return {count: sorted.length, p50: at(0.5), p95: at(0.95), max: sorted[sorted.length - 1] ?? null};
  }
})()`);

// Sustained scroll: alternate direction so the container never hits an end,
// which would stop the content moving and produce a false stall. Alternating
// +delta/-delta around the middle nets to zero, so no per-wheel DOM read is
// needed and the harness adds no traffic of its own to the measurement.
const middle = Math.floor(setup.overflow / 2);
await evaluate(`window.__qaScroller.scrollTop = ${middle}`);
await new Promise(resolve => setTimeout(resolve, 150));
await evaluate('window.__qaState.frames.length = 0; window.__qaState.wheels = 0;');

const started = Date.now();
let index = 0;
while (index < wheelCount) {
  const due = started + index * cadenceMs;
  const wait = due - Date.now();
  if (wait > 0) await new Promise(resolve => setTimeout(resolve, wait));
  await wheel(index % 2 ? -WHEEL_DELTA : WHEEL_DELTA);
  index++;
}
const elapsedMs = Date.now() - started;
await new Promise(resolve => setTimeout(resolve, 500));

const collected = await evaluate(`(() => {
  const state = window.__qaState;
  window.__qaCleanup();
  const frames = state.frames;
  const intervals = [];
  for (let index = 1; index < frames.length; index++) intervals.push(frames[index] - frames[index - 1]);
  const sorted = [...intervals].sort((a, b) => a - b);
  const at = q => sorted.length ? sorted[Math.min(sorted.length - 1, Math.floor(sorted.length * q))] : null;
  return {wheelEvents: state.wheels, frameCount: intervals.length,
    frameMs: {p50: at(0.5), p75: at(0.75), p95: at(0.95), p99: at(0.99), max: sorted[sorted.length - 1] ?? null},
    longFrames32ms: intervals.filter(value => value > 32).length,
    longFrames50ms: intervals.filter(value => value > 50).length,
    longFrames100ms: intervals.filter(value => value > 100).length,
    scrolledTo: Math.round(window.__qaScroller.scrollTop)};
})()`);
ws.close();

const round = value => value === null || value === undefined ? null : Math.round(value * 10) / 10;
const asRounded = object => Object.fromEntries(Object.entries(object).map(([key, value]) => [key, round(value)]));
console.log(JSON.stringify({
  selector,
  content: {scrollHeight: setup.scrollHeight, clientHeight: setup.clientHeight, overflow: setup.overflow},
  durationMs: elapsedMs,
  wheelEventsReceived: collected.wheelEvents,
  wheelEventsDispatched: index,
  idleFrameMs: asRounded({p50: idle.p50, p95: idle.p95, max: idle.max}),
  scrollFrameMs: asRounded(collected.frameMs),
  scrollLongFrames: {
    over32ms: collected.longFrames32ms, over50ms: collected.longFrames50ms,
    over100ms: collected.longFrames100ms, ofFrames: collected.frameCount,
  },
  effectiveFps: collected.frameCount && elapsedMs ? round(collected.frameCount / (elapsedMs / 1000)) : null,
  scrolledTo: collected.scrolledTo,
}, null, 2));
