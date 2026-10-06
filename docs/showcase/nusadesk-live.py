#!/usr/bin/env python3
"""NusaDesk Live — the showcase web app that runs inside the guest.

A single file, no dependencies beyond the container's own Python 3.12 (which the
product's service overlay already provides), so the demo is one command:

    python3 nusadesk-live.py            # serves http://127.0.0.1:8080/

Everything on the page is real guest state: /proc for load, memory, uptime and
per-process CPU, /etc/os-release for the identity (including the product's own
NUSADESK_* fields), statvfs for the disks, the vendored `systemctl` bridge for
the running services, and ~/nusadesk for the workspace folder the Android side
bound into the session. Nothing is mocked; if a source is unreadable the card
says so instead of inventing a number.
"""

import http.server
import json
import os
import socket
import subprocess
import sys
import time
import urllib.parse

PORT = int(os.environ.get("NUSADESK_LIVE_PORT", "8080"))
HISTORY_LEN = 60
SERVICE_TIMEOUT_SECONDS = 3

_load_history = []
_cpu_previous = {"at": None, "ticks": {}, "total": None}


def read_text(path, limit=65536):
    try:
        with open(path, "r", encoding="utf-8", errors="replace") as handle:
            return handle.read(limit)
    except OSError:
        return None


def os_release():
    fields = {}
    text = read_text("/etc/os-release") or ""
    for line in text.splitlines():
        if "=" not in line or line.strip().startswith("#"):
            continue
        key, _, value = line.partition("=")
        fields[key.strip()] = value.strip().strip('"')
    return {
        "name": fields.get("NAME", "Linux"),
        "version": fields.get("VERSION", ""),
        "pretty": fields.get("PRETTY_NAME", "Linux"),
        "contributor": fields.get("NUSADESK_CONTRIBUTOR", ""),
        "source": fields.get("NUSADESK_SOURCE", ""),
    }


def meminfo():
    """MemTotal/MemAvailable in bytes, or None when /proc/meminfo is hidden."""
    values = {}
    text = read_text("/proc/meminfo")
    if text is None:
        return None
    for line in text.splitlines():
        key, _, rest = line.partition(":")
        number = rest.strip().split(" ")[0]
        if number.isdigit():
            values[key] = int(number) * 1024
    return values or None


def session_uptime():
    """The age of this Linux session, from the bridge file the host writes at start.

    PRoot does not virtualize /proc, so /proc/uptime is the device's uptime and
    nothing on the guest side can tell a five-minute session from an eleven-day
    one. The host's own session artifact can: /run/nusadesk/android-bridge.env
    is created when the session starts and removed when it stops.
    """
    for path in ("/run/nusadesk/android-bridge.env",):
        try:
            return time.time() - os.stat(path).st_mtime
        except OSError:
            continue
    return None


def device_uptime():
    text = read_text("/proc/uptime")
    if text is None:
        return None
    try:
        return float(text.split()[0])
    except (IndexError, ValueError):
        return None


def cpu_count():
    return os.cpu_count() or 1


def cpu_busy_from_proc():
    """Device-wide busy percentage from /proc/stat, or None where it is hidden."""
    text = read_text("/proc/stat") or ""
    for line in text.splitlines():
        if not line.startswith("cpu "):
            continue
        values = [int(value) for value in line.split()[1:] if value.isdigit()]
        if len(values) < 4:
            return None
        total = sum(values)
        idle = values[3] + (values[4] if len(values) > 4 else 0)
        previous = _cpu_previous.get("stat")
        _cpu_previous["stat"] = (total, idle)
        if previous is None or total <= previous[0]:
            return None
        delta_total = total - previous[0]
        delta_idle = idle - previous[1]
        return max(0.0, min(100.0, 100.0 * (delta_total - delta_idle) / delta_total))
    return None


def top_processes(limit=6):
    """CPU% per process from two /proc samples, plus its resident memory."""
    now = time.monotonic()
    ticks = {}
    try:
        entries = os.listdir("/proc")
    except OSError:
        entries = []
    for entry in entries:
        if not entry.isdigit():
            continue
        stat = read_text("/proc/%s/stat" % entry, 4096)
        if not stat:
            continue
        close = stat.rfind(")")
        if close < 0:
            continue
        name = stat[stat.find("(") + 1:close]
        fields = stat[close + 2:].split()
        if len(fields) < 22:
            continue
        try:
            ticks[entry] = (name, int(fields[11]) + int(fields[12]))  # utime + stime
        except (ValueError, IndexError):
            continue
    previous = _cpu_previous
    elapsed = None if previous["at"] is None else now - previous["at"]
    rows = []
    for pid, (name, value) in ticks.items():
        previous_ticks = previous["ticks"].get(pid)
        if previous_ticks is None or elapsed is None or elapsed <= 0:
            continue
        delta = value - previous_ticks
        percent = 100.0 * delta / (100.0 * elapsed) / cpu_count() * cpu_count()
        rows.append({"pid": pid, "name": name, "cpu": max(0.0, percent)})
    _cpu_previous["at"] = now
    _cpu_previous["ticks"] = {pid: value for pid, (_, value) in ticks.items()}
    rows.sort(key=lambda row: row["cpu"], reverse=True)
    return rows[:limit]


def disk(path):
    try:
        stat = os.statvfs(path)
    except OSError:
        return None
    total = stat.f_frsize * stat.f_blocks
    free = stat.f_frsize * stat.f_bavail
    return {"path": path, "total": total, "free": free, "used": total - free}


def services():
    """The running services as the session's own systemctl bridge reports them."""
    try:
        result = subprocess.run(
            ["systemctl", "list-units", "--type=service", "--state=running",
             "--no-pager", "--plain", "--no-legend"],
            capture_output=True, text=True, timeout=SERVICE_TIMEOUT_SECONDS)
    except (OSError, subprocess.SubprocessError):
        return None
    if result.returncode != 0:
        return None
    rows = []
    for line in result.stdout.splitlines():
        parts = line.split(None, 4)
        if len(parts) < 4 or not parts[0].endswith(".service"):
            continue
        rows.append({"unit": parts[0], "description": parts[4] if len(parts) > 4 else ""})
    return rows[:14]


def workspace():
    root = os.path.expanduser("~/nusadesk")
    if not os.path.isdir(root):
        return None
    entries = []
    try:
        for name in sorted(os.listdir(root))[:12]:
            path = os.path.join(root, name)
            size = os.path.getsize(path) if os.path.isfile(path) else None
            entries.append({
                "name": name,
                "directory": os.path.isdir(path),
                "size": size,
            })
    except OSError:
        return None
    return {"root": root, "entries": entries}


def state():
    memory_raw = meminfo()
    if memory_raw is None:
        memory = None
    else:
        total = memory_raw.get("MemTotal", 0)
        available = memory_raw.get("MemAvailable", memory_raw.get("MemFree", 0))
        memory = {"total": total, "used": max(0, total - available),
                  "available": available}
    processes = top_processes()
    busy = cpu_busy_from_proc()
    source = "proc"
    if busy is None and not processes and not os.path.isdir("/proc/self"):
        busy, source = None, "unavailable"
    elif busy is None:
        # /proc/stat can be hidden from an app; the sampled processes still say
        # how busy the machine is, so the number stays real and says where it
        # came from.
        busy = max(0.0, min(100.0,
                            sum(row["cpu"] for row in processes) / cpu_count()))
        source = "sampled"
    if busy is not None:
        _load_history.append(round(busy, 1))
    del _load_history[:-HISTORY_LEN]
    return {
        "host": {
            "name": socket.gethostname(),
            "kernel": os.uname().release,
            "machine": os.uname().machine,
        },
        "os": os_release(),
        "clock": time.time(),
        "sessionUptime": session_uptime(),
        "deviceUptime": device_uptime(),
        "cpuBusy": None if busy is None else round(busy, 1),
        "cpuBusySource": source,
        "loadHistory": list(_load_history),
        "cpuCount": cpu_count(),
        "memory": memory,
        "storage": disk("/"),
        "processes": processes,
        "services": services(),
        "workspace": workspace(),
    }


PAGE = r"""<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>NusaDesk Live</title>
<style>
  :root {
    --bg: #08110f; --panel: #10201e; --panel-2: #142a27; --line: #22403c;
    --ink: #eaf7f4; --ink-dim: #9dbcba; --accent: #83ddd6; --accent-2: #a9eee9;
    --warm: #f1b878; --ok: #7ee2b8;
  }
  * { box-sizing: border-box; }
  body {
    margin: 0; background:
      radial-gradient(1200px 600px at 15% -10%, #123330 0%, transparent 60%),
      radial-gradient(900px 500px at 110% 10%, #102b2c 0%, transparent 55%), var(--bg);
    color: var(--ink);
    font: 14px/1.35 ui-sans-serif, system-ui, -apple-system, "Segoe UI", Roboto, sans-serif;
    padding: 10px 10px 12px;
  }
  header { display: flex; align-items: baseline; gap: 8px; flex-wrap: wrap; margin-bottom: 10px; }
  header h1 { font-size: 19px; margin: 0; }
  header h1 span { color: var(--accent); }
  header .sub { color: var(--ink-dim); font-size: 12px; }
  .clock { margin-left: auto; font-variant-numeric: tabular-nums; color: var(--accent-2); }
  .grid { display: grid; gap: 8px; grid-template-columns: minmax(0, 1fr); }
  @media (min-width: 900px) { .grid { grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); } .span { grid-column: 1 / -1; } }
  .card {
    min-width: 0; overflow-wrap: anywhere;
    background: linear-gradient(180deg, var(--panel-2), var(--panel));
    border: 1px solid var(--line); border-radius: 13px; padding: 9px 10px;
    box-shadow: 0 10px 30px rgba(0,0,0,.35), inset 0 1px 0 rgba(255,255,255,.03);
  }
  .card h2 {
    margin: 0 0 6px; font-size: 11.5px; text-transform: uppercase; letter-spacing: .14em;
    color: var(--ink-dim); font-weight: 600; display: flex; justify-content: space-between;
    align-items: baseline; gap: 8px; flex-wrap: wrap;
  }
  .card h2 .badge {
    text-transform: none; letter-spacing: 0; color: var(--accent); font-weight: 500;
    font-size: 11.5px; white-space: nowrap;
  }
  .hero { display: flex; align-items: center; gap: 14px; flex-wrap: wrap; min-width: 0; }
  .big { font-size: 21px; font-variant-numeric: tabular-nums; letter-spacing: .2px; }
  .row { display: flex; justify-content: space-between; gap: 10px; padding: 1px 0; }
  .row .k { color: var(--ink-dim); }
  .row .v { font-variant-numeric: tabular-nums; }
  .gauge { position: relative; width: 70px; height: 70px; flex: 0 0 auto; }
  .gauge svg { transform: rotate(-90deg); }
  .gauge .label { position: absolute; inset: 0; display: grid; place-items: center; font-size: 14px; font-variant-numeric: tabular-nums; }
  .spark { width: 100%; height: 54px; display: block; }
  ul { list-style: none; margin: 0; padding: 0; }
  li { display: flex; align-items: center; gap: 7px; padding: 2px 0; border-bottom: 1px dashed rgba(255,255,255,.05); }
  li:last-child { border-bottom: 0; }
  li .dot { width: 6px; height: 6px; border-radius: 50%; background: var(--ok); box-shadow: 0 0 8px var(--ok); flex: 0 0 auto; }
  li .name { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 13.5px; min-width: 0; }
  li .meta { margin-left: auto; color: var(--ink-dim); font-variant-numeric: tabular-nums; font-size: 12px; }
  button.stress {
    margin-left: auto; background: #0f5f63; color: #eafffb; border: 1px solid #1b7f81;
    border-radius: 10px; padding: 8px 12px; font-size: 13px; font-weight: 600; letter-spacing: .02em;
  }
  button.stress:disabled { opacity: .55; }
  footer { margin-top: 11px; color: var(--ink-dim); font-size: 11.5px; display: flex; gap: 8px 12px; flex-wrap: wrap; }
  footer .full { flex: 0 0 100%; }
  .muted { color: var(--ink-dim); }
</style>
</head>
<body>
<header>
  <h1>NusaDesk <span>Live</span></h1>
  <div class="sub" id="identity">loading…</div>
  <div class="clock" id="clock">--:--:--</div>
</header>

<div class="grid">
  <section class="card span">
    <div class="hero">
      <div class="gauge">
        <svg width="70" height="70" viewBox="0 0 108 108">
          <circle cx="54" cy="54" r="46" fill="none" stroke="#16302d" stroke-width="11"/>
          <circle id="mem-arc" cx="54" cy="54" r="46" fill="none" stroke="url(#g)" stroke-width="11"
                  stroke-linecap="round" stroke-dasharray="289" stroke-dashoffset="289"/>
          <defs><linearGradient id="g" x1="0" y1="0" x2="1" y2="1">
            <stop offset="0" stop-color="#83ddd6"/><stop offset="1" stop-color="#7ee2b8"/>
          </linearGradient></defs>
        </svg>
        <div class="label" id="mem-label">--%</div>
      </div>
      <div style="min-width: 160px">
        <div class="big" id="uptime">--</div>
        <div class="muted" style="font-size:12px" id="uptime-note">session uptime</div>
        <div class="row"><span class="k">memory</span><span class="v" id="mem-text">—</span></div>
        <div class="row"><span class="k">CPU busy</span><span class="v" id="busy">—</span></div>
      </div>
      <button class="stress" id="stress">Stress test · 6s</button>
    </div>
  </section>

  <section class="card span">
    <h2>CPU busy — last 60s <span class="badge" id="busy-source">sampled</span></h2>
    <svg class="spark" id="spark" viewBox="0 0 300 72" preserveAspectRatio="none">
      <polyline id="spark-line" fill="none" stroke="#83ddd6" stroke-width="2" points=""/>
      <polygon id="spark-fill" fill="rgba(131,221,214,.14)" points=""/>
    </svg>
  </section>

  <section class="card">
    <h2>Running services <span class="badge">systemd bridge</span></h2>
    <ul id="services"><li class="muted">reading…</li></ul>
  </section>

  <section class="card">
    <h2>Top processes <span class="badge" id="cores-badge">8 cores</span></h2>
    <ul id="processes"><li class="muted">sampling…</li></ul>
  </section>
</div>

<footer>
  <span class="full">served from <strong>127.0.0.1</strong> inside the guest · <span id="footer-os">—</span> · <span id="footer-note">—</span></span>
</footer>

<script>
const $ = (id) => document.getElementById(id);
const esc = (value) => String(value).replace(/[&<>"']/g,
  (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
const bytes = (n) => {
  if (!n) return "0 B";
  const units = ["B", "KiB", "MiB", "GiB", "TiB"];
  let i = 0, v = n;
  while (v >= 1024 && i < units.length - 1) { v /= 1024; i++; }
  return v.toFixed(v >= 100 || i === 0 ? 0 : 1) + " " + units[i];
};
const duration = (s) => {
  if (s === null || s === undefined) return "—";
  s = Math.max(0, Math.floor(s));
  const d = Math.floor(s / 86400); s -= d * 86400;
  const h = Math.floor(s / 3600); s -= h * 3600;
  const m = Math.floor(s / 60); s -= m * 60;
  return (d ? d + "d " : "") + (h || d ? h + "h " : "") + m + "m " + s + "s";
};

function sparkline(values) {
  const width = 300, height = 72, pad = 5;
  const max = Math.max(25, ...values) * 1.15;
  const step = values.length > 1 ? (width - pad * 2) / (values.length - 1) : 0;
  const points = values.map((v, i) => {
    const x = pad + i * step;
    const y = height - pad - (v / max) * (height - pad * 2);
    return x.toFixed(1) + "," + y.toFixed(1);
  });
  $("spark-line").setAttribute("points", points.join(" "));
  $("spark-fill").setAttribute("points",
    (pad.toFixed(1) + "," + (height - pad)) + " " + points.join(" ") + " " +
    (pad + (values.length - 1) * step).toFixed(1) + "," + (height - pad));
}

function render(state) {
  const os = state.os || {};
  $("identity").textContent = (os.pretty || "Linux") + (os.machine ? " · " + os.machine : "");
  $("clock").textContent = new Date(state.clock * 1000).toLocaleTimeString();
  $("uptime").textContent = duration(state.sessionUptime);
  $("uptime-note").textContent = (state.sessionUptime === null
      ? "session uptime unavailable" : "session uptime")
    + (state.deviceUptime === null ? "" : " · device up " + duration(state.deviceUptime));
  const mem = state.memory;
  if (mem === null || mem === undefined) {
    $("mem-label").textContent = "--%";
    $("mem-arc").setAttribute("stroke-dashoffset", 289);
    $("mem-text").textContent = "unavailable";
  } else {
    const percent = mem.total ? Math.round(100 * mem.used / mem.total) : 0;
    $("mem-label").textContent = percent + "%";
    $("mem-arc").setAttribute("stroke-dashoffset", (289 - 289 * percent / 100).toFixed(1));
    $("mem-text").textContent = bytes(mem.used) + " / " + bytes(mem.total);
  }
  $("busy").textContent = state.cpuBusy === null || state.cpuBusy === undefined
    ? "unavailable"
    : state.cpuBusy.toFixed(1) + "% of " + state.cpuCount + " cores";
  $("busy-source").textContent = state.cpuBusySource === "proc" ? "device-wide"
    : state.cpuBusySource === "sampled" ? "sampled" : "unavailable";
  $("cores-badge").textContent = state.cpuCount + " cores";
  if (state.cpuBusySource === "sampled") {
    $("busy-source").title = "device /proc/stat is hidden from the app; sampled from processes";
  }

  const history = state.loadHistory || [];
  sparkline(history.length ? history : [0]);

  const services = state.services;
  $("services").innerHTML = services === null
    ? '<li class="muted">systemctl bridge did not answer</li>'
    : (services.slice(0, 4).map((s) =>
        '<li><span class="dot"></span><span class="name">' + esc(s.unit.replace(".service", "")) +
        '</span><span class="meta">running</span></li>').join("") ||
       '<li class="muted">none running</li>');

  $("processes").innerHTML = (state.processes || []).slice(0, 3).map((p) =>
    '<li><span class="name">' + esc(p.name) + '</span><span class="meta">' + p.cpu.toFixed(1) +
    '% · pid ' + p.pid + '</span></li>').join("") || '<li class="muted">sampling…</li>';

  $("footer-os").textContent = state.host.name + " · kernel " + state.host.kernel;
  $("footer-note").textContent = os.contributor || os.pretty || "";
}

async function tick() {
  try {
    const response = await fetch("/api/state", { cache: "no-store" });
    render(await response.json());
  } catch (error) {
    $("identity").textContent = "connection lost — retrying";
  }
  setTimeout(tick, 1000);
}

$("stress").addEventListener("click", async () => {
  const button = $("stress");
  button.disabled = true;
  try {
    const response = await fetch("/api/stress", { method: "POST" });
    const result = await response.json();
    button.textContent = result.ok ? result.workers + " workers burning…" : "already running";
  } catch (error) {
    button.textContent = "could not start";
  }
  setTimeout(() => { button.disabled = false; button.textContent = "Stress test · 6s"; }, 7000);
});

tick();
</script>
</body>
</html>
"""


_stress = {"running": False, "until": 0.0}


def start_stress():
    """Spawns a bounded CPU burn so the dashboard can prove the guest is real."""
    now = time.time()
    if _stress["running"] and now < _stress["until"]:
        return None
    workers = max(1, min(cpu_count(), 4))
    seconds = 6
    script = (
        "import time\n"
        "end = time.time() + %d\n"
        "value = 0\n"
        "while time.time() < end:\n"
        "    value = (value * 31 + 7) %% 1000003\n"
    ) % seconds
    for _ in range(workers):
        try:
            subprocess.Popen([sys.executable, "-c", script],
                             stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        except OSError:
            break
    _stress["running"] = True
    _stress["until"] = now + seconds
    return {"workers": workers, "seconds": seconds}


class Handler(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        print("REQ %s" % (fmt % args), flush=True)

    def send(self, status, body, content_type):
        payload = body if isinstance(body, bytes) else body.encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(payload)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(payload)

    def do_GET(self):
        path = urllib.parse.urlparse(self.path).path
        if path in ("/", "/index.html"):
            self.send(200, PAGE, "text/html; charset=utf-8")
        elif path == "/api/state":
            self.send(200, json.dumps(state()), "application/json")
        else:
            self.send(404, "not found", "text/plain")

    def do_POST(self):
        if urllib.parse.urlparse(self.path).path != "/api/stress":
            self.send(404, "not found", "text/plain")
            return
        started = start_stress()
        if started is None:
            self.send(429, json.dumps({"ok": False, "reason": "already running"}),
                      "application/json")
            return
        self.send(200, json.dumps(dict(started, ok=True)), "application/json")


def main():
    server = http.server.ThreadingHTTPServer(("127.0.0.1", PORT), Handler)
    print("NusaDesk Live on http://127.0.0.1:%d/ (Ctrl-C to stop)" % PORT, flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
