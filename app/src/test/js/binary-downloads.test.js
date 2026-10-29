'use strict';
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const { webcrypto } = require('node:crypto');
const script = fs.readFileSync('app/src/main/assets/webapp/binary-downloads.js', 'utf8');
const jobs = new Map(), saved = [], errors = [];
let inFlight = 0, maxInFlight = 0, largestChunk = 0, originalClicks = 0, externalOpens = 0;
let documentClick;
class Anchor {
  constructor(href, download = '') { this.href = href; this.download = download; this.listeners = new Set(); }
  closest() { return this; }
  addEventListener(type, listener) { this.listeners.add(listener); }
  removeEventListener(type, listener) { this.listeners.delete(listener); }
  click() {
    originalClicks++;
    const event = { target: this, defaultPrevented: false, preventDefault() { this.defaultPrevented = true; } };
    for (const listener of this.listeners) listener(event);
    if (this.connected) documentClick(event);
  }
}
const channel = {
  postMessage(raw) {
    const packet = JSON.parse(raw);
    if (packet.op === 'abort') { errors.push(packet.error); jobs.delete(packet.id); return; }
    inFlight++; maxInFlight = Math.max(maxInFlight, inFlight);
    setTimeout(() => {
      if (packet.op === 'start') jobs.set(packet.id, { name: packet.name, size: packet.size, chunks: [] });
      else if (packet.op === 'chunk') {
        const bytes = Buffer.from(packet.data, 'base64');
        largestChunk = Math.max(largestChunk, bytes.length);
        jobs.get(packet.id).chunks.push(bytes);
      } else if (packet.op === 'finish') {
        const job = jobs.get(packet.id);
        job.bytes = Buffer.concat(job.chunks);
        assert.equal(job.bytes.length, job.size);
        saved.push(job); jobs.delete(packet.id);
      }
      inFlight--;
      channel.onmessage({ data: JSON.stringify({ id: packet.id, seq: packet.seq }) });
    }, 0);
  }
};
class BrowserURL extends URL { }
const context = { URL: BrowserURL, Blob, Uint32Array, Uint8Array, crypto: webcrypto, fetch,
  btoa: text => Buffer.from(text, 'binary').toString('base64'), HTMLAnchorElement: Anchor,
  document: { addEventListener(type, listener) { documentClick = listener; } },
  NusaDeskDownload: channel, open() { externalOpens++; return 'external'; } };
context.window = context;
vm.runInNewContext(script, context);
async function until(predicate) {
  for (let i = 0; i < 1000; i++) { if (predicate()) return; await new Promise(resolve => setTimeout(resolve, 5)); }
  throw new Error('Export did not finish');
}
(async () => {
  const bytes = Buffer.from([0, 1, 128, 255]);
  const url = context.URL.createObjectURL(new Blob([bytes]));
  new Anchor(url, 'binary.bin').click();
  context.URL.revokeObjectURL(url);
  await until(() => saved.length === 1);
  assert.deepEqual(saved[0].bytes, bytes);
  assert.equal(saved[0].name, 'binary.bin');
  assert.equal(originalClicks, 1, 'original click handlers still run');

  const csv = 'nama,kota\nJahrul,Jakarta\n日本語,+%\n';
  new Anchor('data:text/csv;charset=utf-8,' + encodeURIComponent(csv), 'catatan.csv').click();
  await until(() => saved.length === 2);
  assert.equal(saved[1].bytes.toString('utf8'), csv);

  new Anchor('data:application/octet-stream;base64,AAH+/w==', 'data.bin').click();
  await until(() => saved.length === 3);
  assert.deepEqual(saved[2].bytes, Buffer.from([0, 1, 254, 255]));
  new Anchor('data:text/plain;base64,', 'empty.txt').click();
  await until(() => saved.length === 4);
  assert.equal(saved[3].bytes.length, 0);

  const large = Buffer.alloc(3 * 1024 * 1024);
  for (let i = 0; i < large.length; i++) large[i] = i % 256;
  const largeUrl = context.URL.createObjectURL(new Blob([large]));
  const attached = new Anchor(largeUrl, 'large.bin'); attached.connected = true; attached.click();
  context.URL.revokeObjectURL(largeUrl);
  await until(() => saved.length === 5);
  assert.deepEqual(saved[4].bytes, large);
  assert.equal(largestChunk, 32768);
  assert.equal(maxInFlight, 1, 'one native ACK before the next chunk');

  context.open('data:text/plain,from-window-open');
  await until(() => saved.length === 6);
  assert.equal(saved[5].bytes.toString(), 'from-window-open');
  assert.equal(context.open('https://example.org'), 'external');
  assert.equal(externalOpens, 1);
  const gone = context.URL.createObjectURL(new Blob(['gone'])); context.URL.revokeObjectURL(gone);
  new Anchor(gone, 'gone.txt').click();
  await until(() => errors.length === 1);
  assert.equal(saved.length, 6);
  console.log('Binary exports: detached/connected anchors, revoke, data encodings, empty/large files, ACK flow, window.open, failure passed');
})().catch(error => { console.error(error); process.exitCode = 1; });
