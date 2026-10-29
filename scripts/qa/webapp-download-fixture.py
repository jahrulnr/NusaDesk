#!/usr/bin/env python3
"""Run inside the guest: bounded loopback fixture for ADR-0083 manual QA.

Register a web app on port 18995. GET /verify reports only fixture files;
POST /cleanup deletes only those files and stops this fixture server.
"""
import hashlib
import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import threading
import time

PREFIX = "qa-nusadesk-download-20261029"
PAYLOAD = bytes(range(256)) * 1024
DIRECTORY = Path("/root/Downloads")
PAGE = """<!doctype html><meta name="viewport" content="width=device-width,initial-scale=1">
<style>body{background:#102126;color:#eee;font:18px sans-serif;padding:20px}
a{display:block;padding:18px;margin:12px 0;background:#25434a;color:#9ff;border-radius:8px}</style>
<h2>Guest download QA</h2><p>Cookie-protected HTTP downloads</p>
<a href="/file" download>Download attachment</a>
<a href="/slow" download>Slow download</a>
<a href="/retry" download>Failure then retry</a>
<a href="/child" target="_blank">Open child tab</a>
"""


class Handler(BaseHTTPRequestHandler):
    retry_requests = 0

    def do_GET(self):
        if self.path == "/verify":
            result = [{"name": p.name, "size": p.stat().st_size,
                       "sha256": hashlib.sha256(p.read_bytes()).hexdigest()}
                      for p in sorted(DIRECTORY.glob(PREFIX + "*"))]
            self.reply(json.dumps(result).encode(), "application/json")
            return
        if self.path in ("/", "/child"):
            self.reply(PAGE.encode(), "text/html", cookie=True)
            return
        if self.path not in ("/file", "/slow", "/retry"):
            self.send_error(404)
            return
        if "qa_download=session-ok" not in self.headers.get("Cookie", ""):
            self.send_error(403)
            return
        if self.path == "/retry":
            Handler.retry_requests += 1
            if Handler.retry_requests == 2:
                self.send_error(503, "Fixture retry")
                return
        slow = self.path == "/slow"
        data = PAYLOAD * (8 if slow else 1)
        name = PREFIX + self.path.replace("/", "-") + ".bin"
        self.send_response(200)
        self.send_header("Content-Type", "application/octet-stream")
        self.send_header("Content-Disposition", 'attachment; filename="' + name + '"')
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        try:
            for start in range(0, len(data), 32768):
                self.wfile.write(data[start:start + 32768])
                self.wfile.flush()
                if slow:
                    time.sleep(0.15)
        except (BrokenPipeError, ConnectionResetError):
            pass  # WebView hands the URL to the host; cancellation is expected.

    def reply(self, body, mime, cookie=False):
        self.send_response(200)
        self.send_header("Content-Type", mime)
        self.send_header("Content-Length", str(len(body)))
        if cookie:
            self.send_header("Set-Cookie", "qa_download=session-ok; HttpOnly; Path=/")
        self.end_headers()
        self.wfile.write(body)

    def do_POST(self):
        if self.path != "/cleanup":
            self.send_error(404)
            return
        for path in DIRECTORY.glob(PREFIX + "*"):
            path.unlink()
        self.reply(b"cleaned", "text/plain")
        threading.Thread(target=self.server.shutdown, daemon=True).start()


if __name__ == "__main__":
    print("QA SHA256", hashlib.sha256(PAYLOAD).hexdigest(), flush=True)
    server = ThreadingHTTPServer(("127.0.0.1", 18995), Handler)
    try:
        server.serve_forever()
    finally:
        server.server_close()
