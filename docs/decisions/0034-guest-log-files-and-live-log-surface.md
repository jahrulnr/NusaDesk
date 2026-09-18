# ADR-0034: Guest log files, the Logs surface, and in-place rotation

## Status

Accepted — implemented in `infrastructure/logs`, `infrastructure/proot`,
`infrastructure/integration`, `presentation/logs`, and `presentation/widget`,
with JVM tests covering the writer, the trim, the catalog, the tail, and the
monitor sink. Device-verified on Samsung SM-G970F Android 12/API 31 arm64
(2026-09-17): the catalog lists boot and service items, `boot.log` captures
the real session console, journals rotate per session, a marker line appended
to an open journal streamed into the live viewer, an oversized journal was
tail-trimmed in place on the same inode, a planted symlink was rejected, item
switching cleared the terminal, and the tail thread stopped on exit. The pass
exposed and fixed a buffered-writer bug that left `boot.log` empty until
close; other API levels and OEMs remain open.

## Context

A Linux product needs somewhere for output to go and somewhere to read it.
Before this change the guest had pieces but no coherent story:

- The vendored `systemctl3` already writes per-unit text logs to
  `/var/log/journal/<unit>.log` (system units) and
  `/root/.config/log/journal/` (`--user` units), with `journalctl -f`
  implemented as `tail -F`. Those files have **no size policy at all** — a
  chatty service grows its log forever.
- The session's console — the setup script, `systemctl init`, and the sshd
  `-e` log — reached the host through the supervised process pipes and was
  retained as only 16 in-memory diagnostic lines. Once drained, it was gone:
  no boot log existed anywhere, host or guest.
- There was no UI to read any of it.

The user-facing requirement: a `Logs` entry after `System` in the launcher,
logs organized like a real Linux box (boot/session separate from live
services), a pick-an-item flow into a live view, terminal-style rendering,
and rotation so nothing grows unbounded.

## Decision

**Persist the session console inside the rootfs.** `SessionLogWriter` opens
`<rootfs>/var/log/lw/boot.log` for append before the first guest process of a
session launches. `GuestSshdStderrMonitor` gained a `lineSink` fed by both
drain threads (stderr and stdout), so the whole supervised tree's console —
supervisor, `systemctl init`, `sshd -e` — lands in the file with a
syslog-style timestamp per line. The guest setup process's pipes are drained
into the same file, which also removes a latent pipe-fill wedge. The writer
is a `SessionLogWriter` field of `ActiveDaemon`: closed by `teardown`, and by
the start path's `finally` when a start is rejected, so exactly one session
owns it.

**Catalog curated files; never browse arbitrarily.** `GuestLogCatalog.list`
scans the active rootfs into a `GuestLog` list: `BOOT` items (`boot.log`,
`boot.log.1`) first, then `SYSTEM` items (system-unit journals, user-unit
journals tagged `user`, compose supervisor and per-project service logs
tagged `compose`) sorted by title. Only regular, non-symlink files resolving
under the rootfs are listed — the terminal lets the user plant anything in
there, and a planted symlink must not turn the Logs surface into a generic
file reader.

**Tail host-side, render in the existing terminal.** `GuestLogTail` is a
polling `tail -n 64K -F` over the host path: line-aligned backfill, then a
300 ms size/inode poll that follows appends, reseeks on in-place shrink, and
reopens on rename-replace. Output feeds the packaged xterm WebView through
two new host→page commands: `RESET` (`TerminalBridgeView.clear()`) wipes
screen and scrollback so a newly picked file never inherits the previous
one's output, and `fontSize` (`TerminalBridgeView.setFontSize`) renders the
read-only viewer denser (11 px) than the interactive shell (13 px) so full
log lines fit a phone row. File lines carry LF only, so the view maps
`\n` → `\r\n` at the write boundary — the ONLCR translation a PTY gives the
shell path for free; without it each line keeps the previous column and
stair-steps. The packaged assets are served `Cache-Control: no-store` because
a cached page silently drops bridge commands it predates. The view
(`LogsScreenView`) owns only pane switching; the Activity owns the tail and
stops it whenever the viewer closes — including the surface being hidden — so
a poll thread never outlives its consumer.

**Rotate by generation at boot; bound in place during a session.**
`SessionLogWriter.rotateAtSessionStart` runs before any guest process holds
the files: `boot.log` → `boot.log.1` and every journal `*.log` → `*.log.1`,
one generation each, mirroring `journalctl -b`/`-b -1`. During a session, a
sweeper in `GuestSshdWorkload` tail-trims any journal file past 2 MiB to the
newest 1 MiB every 60 s, and `SessionLogWriter` self-trims `boot.log` the
same way at the same thresholds.

The in-place mechanism (`GuestLogTrim.shrinkToTail`) is the load-bearing
detail: services hold their journal files open with `O_APPEND`. Rotation by
rename would orphan that descriptor — the writer would keep appending to the
unlinked inode while the new file stays empty and disk use keeps growing
invisibly. The trim instead reads the newest bounded tail, drops the partial
first line, rewrites those bytes at offset zero, and truncates the same
inode, so open appenders keep working on a bounded file.

## Consequences

- `boot.log` is simultaneously the Logs surface's "This boot" item and a file
  the user can read inside the guest (`cat /var/log/lw/boot.log`) — one
  artifact, two honest views of it.
- A log-writing failure never fails the session it describes: the writer is
  opened best-effort and every append/rotate error is swallowed by design.
- The Logs viewer is read-only by construction: no input path reaches the
  file, and the bridge listener drops page input.
- One previous boot is retained, not a history. Journal services that log
  only to the console (their output lands in `boot.log` via `inherit`) are
  visible but not separately filed; services writing `file:`/`append:`
  targets outside the journal dirs are out of catalog scope.
- `boot.log` lines are the supervised tree's console — not a kernel ring
  buffer; "boot" here means the session's own boot, like `journalctl -b`
  under a container.
