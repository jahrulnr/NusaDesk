/* NusaDesk's origin-scoped export channel. No filesystem or command API. */
(() => {
  if (!window.NusaDeskDownload || window.__nusaDeskExport) return;
  const channel = window.NusaDeskDownload;
  const pending = new Map();
  const blobs = new Map();
  let sequence = 0;
  const binary = url => typeof url === 'string' && /^(blob:|data:)/.test(url);
  channel.onmessage = event => {
    const reply = JSON.parse(event.data);
    const key = reply.id + ':' + reply.seq;
    const task = pending.get(key);
    if (!task) return;
    pending.delete(key);
    if (reply.error) task.reject(new Error(reply.error)); else task.resolve();
  };
  function send(id, op, fields = {}) {
    const seq = ++sequence;
    return new Promise((resolve, reject) => {
      pending.set(id + ':' + seq, { resolve, reject });
      channel.postMessage(JSON.stringify({ ...fields, id, seq, op }));
    });
  }
  const create = URL.createObjectURL;
  const revoke = URL.revokeObjectURL;
  URL.createObjectURL = function (object) {
    const url = create.call(this, object);
    if (object instanceof Blob) blobs.set(url, object);
    return url;
  };
  URL.revokeObjectURL = function (url) {
    blobs.delete(String(url));
    return revoke.call(this, url);
  };
  function exportFile(url, name) {
    if (!binary(url)) return false;
    // Retain the Blob synchronously: callers commonly revoke immediately after click().
    const retained = blobs.get(url);
    const source = retained ? Promise.resolve(retained) : fetch(url).then(response => response.blob());
    const id = Array.from(crypto.getRandomValues(new Uint32Array(4)), n => n.toString(16)).join('-');
    (async () => {
      try {
        const blob = await source;
        // The guest filename is capped at 200 UTF-8 bytes; bound metadata too.
        await send(id, 'start', { name: (name || blob.name || '').slice(0, 200), size: blob.size, mime: blob.type });
        for (let offset = 0; offset < blob.size; offset += 32768) {
          const bytes = new Uint8Array(await blob.slice(offset, offset + 32768).arrayBuffer());
          let text = '';
          for (let i = 0; i < bytes.length; i++) text += String.fromCharCode(bytes[i]);
          await send(id, 'chunk', { data: btoa(text) });
        }
        await send(id, 'finish');
      } catch (error) {
        // An error before start is reported too; exports must never silently disappear.
        channel.postMessage(JSON.stringify({ id, seq: ++sequence, op: 'abort', name: name || '',
          error: String(error && error.message || error) }));
      }
    })();
    return true;
  }
  window.__nusaDeskExport = exportFile;
  function click(event) {
    if (event.defaultPrevented) return;
    const anchor = event.target && event.target.closest && event.target.closest('a[href]');
    if (anchor && exportFile(anchor.href, anchor.download)) event.preventDefault();
  }
  document.addEventListener('click', click, false);
  // Detached anchors do not bubble through document; preserve normal click handlers.
  const nativeClick = HTMLAnchorElement.prototype.click;
  HTMLAnchorElement.prototype.click = function () {
    this.addEventListener('click', click);
    try { return nativeClick.call(this); }
    finally { this.removeEventListener('click', click); }
  };
  const open = window.open;
  window.open = function (url, ...args) {
    if (binary(url) && exportFile(url, '')) return null;
    return open.call(this, url, ...args);
  };
})();
