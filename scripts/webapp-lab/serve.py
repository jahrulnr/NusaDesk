#!/usr/bin/env python3
"""Multi-port web-app favicon/cache test lab for NusaDesk.

Each scenario is its own port because a registered web app's endpoint is
always ``http://127.0.0.1:<port>/`` — the document root is the only document
the favicon fetcher reads, so one scenario = one registered launcher tile.

Run on the workstation and map the device loopback with adb::

    python3 scripts/webapp-lab/serve.py
    for p in $(seq 8401 8416); do adb reverse tcp:$p tcp:$p; done

or run it inside the guest session when it has python3. Then register each
port as a web app and compare each tile against the expectation the page
itself prints. Stdlib only; every image is generated in memory.
"""

import binascii
import json
import struct
import threading
import zlib
from base64 import b64decode
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

BASE_PORT = 8400

# ---- pre-generated raster fixtures (ImageMagick, 64x64) ---------------------

JPEG_ICON = b64decode(
    b"/9j/4AAQSkZJRgABAQAAAAAAAAD/2wBDAAMCAgICAgMCAgIDAwMDBAYEBAQEBAgGBgUGCQgKCgkICQkKDA8MCgsOCwkJ"
    b"DRENDg8QEBEQCgwSExIQEw8QEBD/2wBDAQMDAwQDBAgEBAgQCwkLEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEB"
    b"AQEBAQEBAQEBAQEBAQEBAQEBAQEBD/wAARCABAAEADAREAAhEBAxEB/8QAGQABAAMBAQAAAAAAAAAAAAAAAAgICQM"
    b"F/8QAIxABAAAGAAcBAQAAAAAAAAAAAAECAwQGBxc3V3WVs9IRIf/EABkBAQEBAQEBAAAAAAAAAAAAAAAIBQcCBP/E"
    b"ACYRAQABAgQGAwEBAAAAAAAAAAACAQUDFTWSBFNUcrLRESFzMVH/2gAMAwEAAhEDEQA/AJgnpSoAAAAAAAAAAAAAAA"
    b"ADSNQqagAAFANmcx8r73fe+dwq76hj98vKqg7LpvD/AJw8aI2z2kAA0jUKmoAABQDZnMfK+933vncKu+oY/fLyqoO"
    b"y6bw/5w8aI2z2kAA0jUKmoAABQDZnMfK+933vncKu+oY/fLyqoOy6bw/5w8aI2z2kAAknEzY/UDJPK1/toZvcOfPd"
    b"L2zcltvT4eyPo4mbH6gZJ5Wv9mb3Dnz3S9mS23p8PZH0cTNj9QMk8rX+zN7hz57pezJbb0+Hsj6OJmx+oGSeVr/Zm"
    b"9w5890vZktt6fD2R9PAubm5vbmreXlxVr3FeearVq1Z4zT1J5o/s0000f7GMYxjGMYvhnOWJKs51+a1+61r/AGtWh"
    b"CEcKNIQp8Up9UpT+Up/lHJ5egAAAAAAAH//2Q=="
)

WEBP_ICON = b64decode(
    b"UklGRqgBAABXRUJQVlA4IJwBAADwCQCdASpAAEAAPn0uk0ekoqGhN/1aAJAPiWYAxy3sPe/ykSA2wFgAdKvgF76B2Ar"
    b"bf6qtqYFye3kBy+kxmdYiHv70KY0wziDbXvNMzwz41Tur48HFX/uAAP7aJrpIcS1f/U4/8nH/k4/rzU+jOL42vEAVPgk"
    b"OI1P3osu7p5seEYZIUgSfWYBpp9PWkkCiUD4PVnfvwG4jfzkUtGwgdB3mGA7Ykc8Gcjp6bMEPUT1e4n793fSFoh26FZ70"
    b"/4BkVhD+Wm02+pg0ZGK4tn5mYZ1egCh+NCtLYKxxixsIwi4jDjRCV4I7LpbQYKyJfBMTiAcpaSaKd64IG3TSxBX9tj4Jz"
    b"FMtFKtaDXJ6sbi5CMuSYPBPqdZE4OCb63jbx+SRVx345wo1VFOrEhSful8+izCOjLbaaG/V4Yo9ub+Ci2RUE235Gw7QV8"
    b"ms/s0i5o6b1E/2tx4x0zyhA+X4cEQOOqmiAxR7T5Wt3QiMMspRROazs4dbx05TeUNdHLgC8ZJzDxm2LiDov7Wg7HxKU0W"
    b"nx3dTfVXiLBsvYKOc8gAA"
)

GIF_ICON = b64decode(
    b"R0lGODlhQABAAPEDAI1al45cmLCMt////yH5BAAAAAAAIf8LSW1hZ2VNYWdpY2sOZ2FtbWE9MC40NTQ1NDUALAAAAABAAEA"
    b"AAAALRhI+py+0Po5y02ouz3rz7D4biSJbmiSbBEKScMAiutg7sjMG2jFe1fetNdECeEPIDBo8OotLIXCSVy2jCSYVaDVNq"
    b"dYv1aqNd75cZNo+FZfO5l3avZ2332xW3z1F1+/2U57dX0uf3RxJoOChSaHgYkui4+NHo+OgRaTnJUWl5uZHpuZnR6fmZYy"
    b"pKUpraIhKaGsMY2wpJazoqwXrr2gF7K9uxC9yr8QscTINMW3xxvJwrtcyb8TwdjTA8bdMsYb2NbbCNvFVufo6err7O3u4e"
    b"UQAAOw=="
)

# ---- image generators (pure stdlib) ----------------------------------------


def _chunk(kind, payload):
    out = struct.pack(">I", len(payload)) + kind + payload
    return out + struct.pack(">I", binascii.crc32(kind + payload) & 0xFFFFFFFF)


def png(width, height, pixel):
    """Minimal truecolor+alpha PNG. ``pixel(x, y)`` returns an (r, g, b, a) tuple."""
    rows = bytearray()
    for y in range(height):
        rows.append(0)  # filter: none
        for x in range(width):
            rows.extend(pixel(x, y))
    ihdr = struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0)
    return (b"\x89PNG\r\n\x1a\n" + _chunk(b"IHDR", ihdr)
            + _chunk(b"IDAT", zlib.compress(bytes(rows))) + _chunk(b"IEND", b""))


def solid_png(size, rgb):
    return png(size, size, lambda x, y: (*rgb, 255))


# 3x5 bitmap digits for the cache-lab badge.
_DIGITS = {
    "0": ("111", "101", "101", "101", "111"), "1": ("010", "110", "010", "010", "111"),
    "2": ("111", "001", "111", "100", "111"), "3": ("111", "001", "111", "001", "111"),
    "4": ("101", "101", "111", "001", "001"), "5": ("111", "100", "111", "001", "111"),
    "6": ("111", "100", "111", "101", "111"), "7": ("111", "001", "001", "010", "010"),
    "8": ("111", "101", "111", "101", "111"), "9": ("111", "101", "111", "001", "111"),
}


def badge_png(version):
    """Colored badge that draws ``version % 100`` so staleness is visible."""
    palette = [(230, 57, 70), (42, 157, 143), (69, 123, 157), (244, 162, 97)]
    bg = palette[version % len(palette)]
    text = str(version % 100)
    scale = 6
    cell_w, cell_h = 4, 6
    width, height = 64, 64
    total_w = (len(text) * cell_w - 1) * scale
    ox, oy = (width - total_w) // 2, (height - cell_h * scale) // 2

    def pixel(x, y):
        for i, ch in enumerate(text):
            gx = x - (ox + i * cell_w * scale)
            gy = y - oy
            if 0 <= gx < 3 * scale and 0 <= gy < 5 * scale:
                if _DIGITS[ch][gy // scale][gx // scale] == "1":
                    return (255, 255, 255, 255)
        return (*bg, 255)

    return png(width, height, pixel)


def bmp(size, rgb):
    """24-bit BMP file, bottom-up rows padded to 4 bytes."""
    row = (size * 3 + 3) & ~3
    pixels = bytearray()
    for _y in range(size):
        for _x in range(size):
            pixels.extend((rgb[2], rgb[1], rgb[0]))
        pixels.extend(b"\x00" * (row - size * 3))
    header = b"BM" + struct.pack("<IHHI", 54 + len(pixels), 0, 0, 54)
    info = struct.pack("<IiiHHIIiiII", 40, size, size, 1, 24, 0, len(pixels), 2835, 2835, 0, 0)
    return header + info + bytes(pixels)


def dib_frame(size, rgb):
    """32-bpp DIB frame for an ICO: BITMAPINFOHEADER + XOR + AND mask.

    ``biHeight`` is doubled per the ICO spec; the AND mask makes the top half
    transparent, exercising the mask path the BMP conversion alone would lose.
    """
    xor = bytearray()
    for _ in range(size):  # bottom-up rows
        for _ in range(size):
            xor.extend((rgb[2], rgb[1], rgb[0], 255))
    mask_row = (size + 31) // 32 * 4
    mask = bytearray()
    for row in range(size):  # stored bottom-up; stored row 0 = image bottom
        mask.extend(b"\xff" * mask_row if row >= size // 2 else b"\x00" * mask_row)
    header = struct.pack("<IiiHHIIiiII", 40, size, size * 2, 1, 32, 0,
                         len(xor) + len(mask), 2835, 2835, 0, 0)
    return header + bytes(xor) + bytes(mask)


def ico(frames):
    """ICO container. ``frames`` is a list of ``(size, payload)`` where a payload
    starting with the PNG magic is embedded as a PNG-compressed frame."""
    directory = bytearray()
    data = bytearray()
    offset = 6 + len(frames) * 16
    for size, payload in frames:
        directory += struct.pack("<BBBBHHII",
                                 size if size < 256 else 0,
                                 size if size < 256 else 0,
                                 0, 0, 1, 32, len(payload), offset)
        data += payload
        offset += len(payload)
    return struct.pack("<HHH", 0, 1, len(frames)) + bytes(directory) + bytes(data)


def svg(color):
    return ("<svg xmlns='http://www.w3.org/2000/svg' width='64' height='64'>"
            f"<rect width='64' height='64' fill='{color}'/></svg>").encode()


# ---- page scaffolding -------------------------------------------------------

_PAGE = """<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>{title}</title>{links}
<style>body{{font-family:sans-serif;margin:2rem;max-width:40rem}}
code{{background:#eee;padding:0 .3em}}.expect{{border-left:4px solid #2a9d8f;
padding:.5rem 1rem;background:#f0f9f8}}</style></head>
<body><h1>{title}</h1><p>{detail}</p>
<p class="expect"><b>Expected tile:</b> {expect}</p>{extra}</body></html>"""


def page(title, detail, expect, links=(), extra=""):
    joined = "\n".join(links)
    return _PAGE.format(title=title, detail=detail, links=joined,
                        expect=expect, extra=extra).encode()


# ---- scenarios --------------------------------------------------------------

PNG_RED_32 = solid_png(32, (230, 57, 70))
PNG_GREEN_64 = solid_png(64, (42, 157, 143))
PNG_BLUE_128 = solid_png(128, (69, 123, 157))
PNG_ORANGE_48 = solid_png(48, (244, 162, 97))
PNG_TEAL_48 = solid_png(48, (42, 157, 143))
ICO_PNG_MULTI = ico([
    (16, solid_png(16, (230, 57, 70))),
    (32, solid_png(32, (244, 162, 97))),
    (48, solid_png(48, (141, 90, 151))),
])
ICO_DIB_MASKED = ico([(32, dib_frame(32, (240, 180, 40)))])

SCENARIOS = [
    {
        "slug": "fallback-ico",
        "html": page(
            "fallback-ico",
            "No <code>&lt;link rel=icon&gt;</code> is declared. "
            "<code>/favicon.ico</code> exists and is a multi-size ICO "
            "(16/32/48, PNG-compressed frames).",
            "an icon — the fetcher must request /favicon.ico itself and unwrap "
            "the ICO container."),
        "routes": {"/favicon.ico": ("image/x-icon", ICO_PNG_MULTI)},
    },
    {
        "slug": "fallback-ico-dib",
        "html": page(
            "fallback-ico-dib",
            "No icon links; <code>/favicon.ico</code> is a classic DIB-in-ICO "
            "frame whose AND mask makes the top half transparent.",
            "an icon with its top half transparent (checkerboard/plate shows "
            "through) — exercises the BMP-wrap + mask path."),
        "routes": {"/favicon.ico": ("image/x-icon", ICO_DIB_MASKED)},
    },
    {
        "slug": "no-icon",
        "html": page(
            "no-icon",
            "No icon links and <code>/favicon.ico</code> answers 404.",
            "the monogram — a missing favicon is not an error."),
        "routes": {},
    },
    {
        "slug": "multi-size",
        "html": page(
            "multi-size",
            "Five declared icons: px16 (404s), red 32px, green 64px, "
            "blue 128px, and an SVG. The parser takes the first three usable "
            "declarations in order.",
            "the <b>red</b> icon — px16 fails, red-32 decodes first; blue-128 "
            "and the SVG are never reached.",
            links=[
                '<link rel="icon" sizes="16x16" href="/icons/px16.png" type="image/png">',
                '<link rel="icon" sizes="32x32" href="/icons/red32.png" type="image/png">',
                '<link rel="icon" sizes="64x64" href="/icons/green64.png" type="image/png">',
                '<link rel="icon" sizes="128x128" href="/icons/blue128.png" type="image/png">',
                '<link rel="icon" href="/icon.svg" type="image/svg+xml">',
            ]),
        "routes": {
            "/icons/red32.png": ("image/png", PNG_RED_32),
            "/icons/green64.png": ("image/png", PNG_GREEN_64),
            "/icons/blue128.png": ("image/png", PNG_BLUE_128),
            "/icon.svg": ("image/svg+xml", svg("#888")),
        },
    },
    {
        "slug": "fmt-png",
        "html": page("fmt-png", "Declared <code>icon.png</code>.",
                     "the teal icon.",
                     links=['<link rel="icon" href="/icon.png" type="image/png">']),
        "routes": {"/icon.png": ("image/png", PNG_TEAL_48)},
    },
    {
        "slug": "fmt-jpg",
        "html": page("fmt-jpg", "Declared <code>icon.jpg</code>.",
                     "the orange icon.",
                     links=['<link rel="icon" href="/icon.jpg" type="image/jpeg">']),
        "routes": {"/icon.jpg": ("image/jpeg", JPEG_ICON)},
    },
    {
        "slug": "fmt-webp",
        "html": page("fmt-webp", "Declared <code>icon.webp</code>.",
                     "the teal icon with a circle.",
                     links=['<link rel="icon" href="/icon.webp" type="image/webp">']),
        "routes": {"/icon.webp": ("image/webp", WEBP_ICON)},
    },
    {
        "slug": "fmt-gif",
        "html": page("fmt-gif", "Declared <code>icon.gif</code>.",
                     "the monogram — the platform decoder has no GIF codec "
                     "(verified on API 31), so it falls through to "
                     "/favicon.ico, which is absent.",
                     links=['<link rel="icon" href="/icon.gif" type="image/gif">']),
        "routes": {"/icon.gif": ("image/gif", GIF_ICON)},
    },
    {
        "slug": "fmt-bmp",
        "html": page("fmt-bmp", "Declared <code>icon.bmp</code>.",
                     "the blue icon.",
                     links=['<link rel="icon" href="/icon.bmp" type="image/bmp">']),
        "routes": {"/icon.bmp": ("image/bmp", bmp(48, (69, 123, 157)))},
    },
    {
        "slug": "fmt-ico-link",
        "html": page(
            "fmt-ico-link",
            "A declared <code>&lt;link rel=icon href=icon.ico&gt;</code> — the "
            "ICO arrives through a link, not the conventional path.",
            "the purple-family icon, decoded via the container parser.",
            links=['<link rel="icon" href="/icon.ico" type="image/x-icon">']),
        "routes": {"/icon.ico": ("image/x-icon", ICO_PNG_MULTI)},
    },
    {
        "slug": "svg-then-fallback",
        "html": page(
            "svg-then-fallback",
            "The only declaration is an SVG (skipped — no rasteriser), but "
            "<code>/favicon.ico</code> serves PNG bytes despite its name.",
            "the orange icon — SVG skipped, fallback fetched, and a PNG "
            "payload inside a .ico name still decodes.",
            links=['<link rel="icon" href="/icon.svg" type="image/svg+xml">']),
        "routes": {
            "/icon.svg": ("image/svg+xml", svg("#888")),
            "/favicon.ico": ("image/x-icon", PNG_ORANGE_48),
        },
    },
    {
        "slug": "cross-port",
        "html": page(
            "cross-port",
            "The declared icon lives on a <i>different loopback port</i> "
            f"(127.0.0.1:{BASE_PORT + 5}). A different port is a different "
            "origin, so the fetch must refuse it; /favicon.ico is absent.",
            "the monogram — the cross-port declaration is rejected.",
            links=[f'<link rel="icon" href="http://127.0.0.1:{BASE_PORT + 5}/icon.png">']),
        "routes": {},
    },
    {
        "slug": "apple-touch",
        "html": page("fmt-apple-touch",
                     "Only an <code>apple-touch-icon</code> is declared.",
                     "the red icon — apple-touch-icon is a recognised rel.",
                     links=['<link rel="apple-touch-icon" href="/touch.png">']),
        "routes": {"/touch.png": ("image/png", PNG_RED_32)},
    },
    {
        "slug": "mislabeled",
        "html": page(
            "mislabeled",
            "Declared <code>icon.png</code>, but the bytes served are a real "
            "ICO container — the extension lies.",
            "the icon — decoding follows the bytes, not the file name.",
            links=['<link rel="icon" href="/icon.png" type="image/png">']),
        "routes": {"/icon.png": ("image/png", ICO_PNG_MULTI)},
    },
    {
        "slug": "cache-lab",
        "html": None,  # rendered per-request; see handler
        "routes": {},
    },
    {
        "slug": "kbd-lab",
        "html": page(
            "kbd-lab",
            "A text field for the keyboard-settlement check (ADR-0065): "
            "focus it so the IME opens, then leave the surface — Back or the "
            "task bar must land on the launcher with the keyboard closed.",
            "the monogram; no icon is declared.",
            extra=(
                "<p><input type='text' placeholder='focus me and type' "
                "style='font-size:22px;padding:12px;width:100%;box-sizing:"
                "border-box'></p>")),
        "routes": {},
    },
]


# ---- HTTP handling ----------------------------------------------------------


def make_handler(scenario, port, state):
    class Handler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def log_message(self, fmt, *args):
            pass

        def _send(self, status, content_type, body, headers=()):
            print(f"  [{port}] {self.command} {self.path} -> {status}",
                  flush=True)
            self.send_response(status)
            self.send_header("Content-Type", content_type)
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Connection", "close")
            for key, value in headers:
                self.send_header(key, value)
            self.end_headers()
            self.wfile.write(body)

        def do_GET(self):
            path = self.path.split("?", 1)[0]
            if scenario["slug"] == "cache-lab":
                return self._cache_lab(path)
            if path == "/":
                return self._send(200, "text/html; charset=utf-8", scenario["html"])
            route = scenario["routes"].get(path)
            if route is None:
                return self._send(404, "text/plain", b"not found")
            content_type, body = route
            self._send(200, content_type, body)

        def _cache_lab(self, path):
            if path == "/":
                state["renders"] += 1
                expected = state["renders"]
                html = page(
                    "cache-lab",
                    "This page is <code>no-store</code> and re-renders every "
                    "load, but <code>badge.png</code> is cached for a day and "
                    "bears the render number that fetched it.",
                    "plain <i>Reload</i> leaves the badge behind; "
                    "<i>Reload without cache</i> resyncs it.",
                    extra=(
                        f"<p>Render <b>#{expected}</b> — badge should show "
                        f"<b>{expected % 100}</b> and colour "
                        f"{['red', 'teal', 'blue', 'amber'][expected % 4]}.</p>"
                        "<p><img src='/badge.png' width='64' height='64'></p>"
                        "<p id='s'>server state: …</p>"
                        "<script>fetch('/api/state').then(r=>r.json()).then(j=>"
                        "document.getElementById('s').textContent='server: renders='"
                        "+j.renders+' badge_fetches='+j.badge_fetches)</script>"),
                )
                return self._send(200, "text/html; charset=utf-8", html,
                                  (("Cache-Control", "no-store"),))
            if path == "/badge.png":
                state["badge_fetches"] += 1
                body = badge_png(state["renders"])
                return self._send(200, "image/png", body,
                                  (("Cache-Control", "max-age=86400"),))
            if path == "/api/state":
                body = json.dumps(state).encode()
                return self._send(200, "application/json", body,
                                  (("Cache-Control", "no-store"),))
            self._send(404, "text/plain", b"not found")

    return Handler


def main():
    servers = []
    for index, scenario in enumerate(SCENARIOS):
        port = BASE_PORT + 1 + index
        state = {"renders": 0, "badge_fetches": 0}
        server = ThreadingHTTPServer(
            ("127.0.0.1", port), make_handler(scenario, port, state))
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        servers.append(server)
        print(f"  {port}  {scenario['slug']}")
    print(f"\n{len(servers)} scenario ports listening on 127.0.0.1.")
    print("On the workstation, expose them to the device with:")
    print("  for p in $(seq {} {}); do adb reverse tcp:$p tcp:$p; done".format(
        BASE_PORT + 1, BASE_PORT + len(servers)))
    print("Then register each port as a web app in the launcher. Ctrl-C to stop.")
    try:
        threading.Event().wait()
    except KeyboardInterrupt:
        for server in servers:
            server.shutdown()


if __name__ == "__main__":
    main()
