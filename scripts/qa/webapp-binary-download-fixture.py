#!/usr/bin/env python3
"""Run in the guest; register port 18995. /verify reports QA files, POST /cleanup removes them."""
import hashlib
import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import threading

PREFIX = "qa-nusadesk-binary-20261029"
ROOT = Path("/root/Downloads")
SCRIPT = r"""
const prefix='qa-nusadesk-binary-20261029';
function save(blob,name) {
 const url=URL.createObjectURL(blob), a=document.createElement('a');
 a.href=url;a.download=prefix+'-'+name;a.click();URL.revokeObjectURL(url);
}
function csv() {save(new Blob(['nama,kota\nJahrul,Jakarta\n日本語,+%\n'],{type:'text/csv'}),'blob.csv');}
function dataJson() {
 const a=document.createElement('a');a.download=prefix+'-data.json';
 a.href='data:application/json;charset=utf-8,'+encodeURIComponent(JSON.stringify({hello:'日本語',value:42}));a.click();
}
function base64() {const a=document.createElement('a');a.download=prefix+'-base64.bin';a.href='data:application/octet-stream;base64,AAH+/w==';a.click();}
function large(size,name) {const b=new Uint8Array(size);for(let i=0;i<b.length;i++)b[i]=i%256;save(new Blob([b]),name);}
function openFile() {
 const url=URL.createObjectURL(new File(['from-window-open'],prefix+'-window.txt',{type:'text/plain'}));
 window.open(url);URL.revokeObjectURL(url);
}
"""
STYLE = "<style>body{background:#102126;color:#eee;font:18px sans-serif;padding:16px}button,a{display:block;width:100%;box-sizing:border-box;margin:8px 0;padding:12px;background:#25434a;color:#9ff;border:0;border-radius:6px;font:inherit}iframe{width:100%;height:100px;border:1px solid #466}</style>"
PAGE = """<!doctype html><meta name="viewport" content="width=device-width,initial-scale=1">""" + STYLE + "<script>" + SCRIPT + "</script>" + """
<h3>Binary export QA</h3>
<button onclick="csv()">Blob CSV + immediate revoke</button>
<button onclick="dataJson()">Data JSON Unicode</button>
<button onclick="base64()">Data base64 bytes</button>
<button onclick="save(new Blob([]),'empty.txt')">Empty blob</button>
<button onclick="large(16*1024*1024,'large.bin')">Large blob 16 MiB</button>
<button onclick="openFile()">window.open File</button>
<iframe src="/frame"></iframe>
<a href="/child" target="_blank">Open child tab</a>
<a href="/early">Early document export</a>
<button onclick="large(32*1024*1024,'cancel.bin');setTimeout(()=>location.href='/after',30)">Navigate during export</button>
<a href="/http" download>HTTP attachment</a>
"""


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path == "/verify":
            files = [{"name": p.name, "size": p.stat().st_size,
                      "sha256": hashlib.sha256(p.read_bytes()).hexdigest()} for p in sorted(ROOT.glob(PREFIX + "*"))]
            self.reply(json.dumps({"files": files, "partials": [p.name for p in ROOT.glob('.nusadesk-download-*.part')]}).encode(), "application/json")
        elif self.path == "/frame":
            self.reply((STYLE + '<script>' + SCRIPT + '</script><button onclick="save(new Blob([\'iframe export\']),\'iframe.txt\')">Iframe export</button>').encode(), "text/html")
        elif self.path == "/early":
            self.reply(('<script>' + SCRIPT + ';save(new Blob([\'early export\']),\'early.txt\');</script><p>Early export</p><a href="/">Back</a>').encode(), "text/html")
        elif self.path == "/after":
            self.reply(b'<p>Navigation completed</p><a href="/">Back</a>', "text/html")
        elif self.path == "/http":
            self.send_response(200)
            self.send_header("Content-Disposition", 'attachment; filename="' + PREFIX + '-http.bin"')
            self.send_header("Content-Type", "application/octet-stream")
            self.send_header("Content-Length", "4"); self.end_headers(); self.wfile.write(bytes([0, 1, 128, 255]))
        else:
            self.reply(PAGE.encode(), "text/html")

    def reply(self, body, mime):
        self.send_response(200); self.send_header("Content-Type", mime + "; charset=utf-8")
        self.send_header("Content-Length", str(len(body))); self.end_headers(); self.wfile.write(body)

    def do_POST(self):
        if self.path != "/cleanup": self.send_error(404); return
        for p in ROOT.glob(PREFIX + "*"): p.unlink()
        self.reply(b"cleaned", "text/plain")
        threading.Thread(target=self.server.shutdown, daemon=True).start()


if __name__ == "__main__":
    server = ThreadingHTTPServer(("127.0.0.1", 18995), Handler)
    try: server.serve_forever()
    finally: server.server_close()
