# ADR-0024: Guest service management via a vendored systemctl replacement under one PRoot tree

## Status

Accepted and implemented: `CuratedRuntimeCatalog.guestServiceBridge()`,
`GuestServiceBridge`, the `guest-service-bridge` curated add-on, the vendored
`systemctl3.py` + `service` shim + `lw-session-supervisor` assets, the
synthesized `/proc` stand-ins, the shared-tracer session supervision in
`GuestSshdWorkload`, and the Phase 3B compose payload vendored under
`assets/compose/` (product-owned runner, pinned udocker/PyYAML sources, and
the global supervisor unit).

Verified by unit tests (`GuestServiceBridgeTest`, `ProotLauncherTest`,
`GuestAddonPayloadProfileTest`), the full unit suite, lint, and a physical
device run on the Samsung S10e (Android 12, serial `R39M209Q3TM`) recorded at
the end of this document.

## Context

The curated guest is a plain Ubuntu Base rootfs — it ships unit files but no
init system: no `systemctl`, no Python, and under PRoot there is no PID 1, no
cgroups, and no namespaces, so real systemd cannot run. Services like the
user's own daemons nevertheless need a sane `systemctl`-shaped surface and a
way for enabled services to come up with the session and go down with it.

Constraints that shaped the decision:

- **The host owns the session** (ADR-0013): the Android foreground service is
  the supervisor of record; the guest service manager is a guest process in
  that session, never an Android `systemd` install.
- **Java-only host** (AGENTS.md): the manager had to be guest-side.
- **PRoot mediates `kill(2)`**: verified on-device, a tracee may only signal
  processes inside its own tracer's tree — a `systemctl stop` typed in a
  terminal can only reach a service when the terminal, the manager, and the
  service all share one tracer.
- **SELinux denies the app domains `/proc/uptime` and `/proc/stat`**: the
  replacement's boot-time probe then degenerates to "now", after which every
  status-file read truncates the file it just read — services always read
  `inactive` and the manager reports `offline`.

## Decision

**Vendor `systemctl3.py` (docker-systemctl-replacement v1.7.1097) plus a
curated Python 3 payload as a dedicated `guest-service-bridge` add-on, and
run the guest service manager inside the session's single PRoot tree.**

- The vendored script is byte-pinned to upstream commit
  `8bd65bec50650fcc740e2d55c32162ba7bafdfc8` (tag `v1.7.1097`,
  SHA-256 `5f5a47f3…74fea93`), shipped in `app/src/main/assets/services/`
  verbatim with its EUPL-1.2 licence text and a third-party notice entry.
- The Python runtime is not vendored source: it is fourteen digest-pinned
  Ubuntu Noble arm64 `.deb` artifacts from `ports.ubuntu.com`
  (`python3`, `python3-minimal`, `python3.12`, `python3.12-minimal`,
  `libpython3.12-minimal`, `libpython3.12-stdlib`, `libpython3-stdlib`,
  `libexpat1`, `libsqlite3-0`, `libreadline8t64`, `readline-common`,
  `tzdata`, `media-types`, `netbase`) — the dependency closure derived from
  the signed package index minus everything the base rootfs already ships.
- The add-on installs through the same download → verify → safe-extract →
  atomic-activate pipeline as the SSH add-on, at
  `files/linux-wrapper/addons/guest-service-bridge/active`, bound at
  `/opt/lw-services`. `GuestServiceBridge.wireInto()` then links the overlay's
  public members into the conventional guest paths (`/usr/bin/systemctl`,
  `/usr/bin/service`, the python interpreters, stdlib, shared libraries, and
  the missing `etc/` data files), creates `/run/systemd/system`, and wipes
  stale `/run/*.status` marks — a session start is the guest's "boot". Real
  rootfs content is preserved physically, but the four product entrypoints
  (`/usr/bin/systemctl`, `/usr/bin/service`, `/usr/bin/python3`, and
  `/usr/bin/python3.12`) also receive fixed PRoot strict file binds using the
  documented trailing `!` no-dereference form. This effective runtime override
  prevents an apt-installed native/system `systemctl` or Python interpreter
  from shadowing the product bridge without deleting guest-owned files.
- The overlay also carries the compose payload (Phase 3B): byte-for-byte
  pinned udocker 1.3.17 (Apache-2.0) and PyYAML 6.0.1 (MIT) source tarballs
  under `usr/local/lib/nusadesk/compose/` next to the product-owned
  `lw_compose_runtime.py` runner, the `udocker` and `lw-compose-supervisor`
  launchers wired onto `usr/local/bin/`, and the global
  `lw-compose-supervisor.service` unit wired to `etc/systemd/system/`.
  Because this manager only restart-supervises units that were enabled at
  `systemctl init`, `wireInto()` also pre-creates the unit's
  `multi-user.target.wants` symlink — product-owned bootstrap enablement,
  never a per-project unit. Provenance, licence texts, and the honest
  runtime contract are vendored at
  `usr/share/lw-services/compose/THIRD_PARTY_NOTICES.md`; udocker's helper
  tarball is never shipped or downloaded — the inner PRoot is the packaged
  NusaDesk build (see `ProotLauncher`'s inner-runtime binds).
- **One session = one PRoot tree.** The session tracer's initial tracee is
  the vendored `lw-session-supervisor` script, which backgrounds
  `python3.12 systemctl init` under a wrapper subshell — recording the real
  manager pid in `/run/lw-services-init.pid` and its exit code in
  `/run/lw-services-init.exit` — then runs the session daemon (`sshd`) as a
  sibling. Terminal sessions spawned by sshd are therefore inside the same
  tree as the manager and every service it starts, so guest `systemctl`
  commands can signal them. When the bridge is absent the daemon is the
  initial tracee directly; the SSH session does not depend on the bridge.
- **The synthesized `/proc` stand-ins** (`lw-proc/uptime`, `lw-proc/stat`,
  content `"999999.00 1999998.00"` and `btime 1`) are vendored into the
  overlay and file-bound over the real `/proc/uptime` and `/proc/stat` by the
  launcher for every session process. A boot time near the epoch means "this
  session is the boot" — status files the bridge wipes at start stay readable
  afterwards.
- **The host still owns lifecycle.** Teardown signals the manager's real
  tracee pid (identity-checked against its command line) so it runs every
  enabled service's stop steps, waits a bounded window for the exit marker,
  then signals the daemon and destroys the tracer; `--kill-on-exit` reaps
  whatever remains. Orphaned managers and daemons are reclaimed the same way
  through their pid files. A manager exit mid-session is a degradation —
  logged loudly via the exit-marker watcher — not a session stop.

## Supported subset — what this is NOT

`systemctl3.py` is a partial replacement written for containers. Supported:
`start`, `stop`, `status`, `restart`, `is-active`, `is-enabled`,
`enable`/`disable` (including `--now`), `list-units`, `is-system-running`,
`init`, `halt`-family shutdown. Not supported: socket activation, timers,
dependency ordering beyond basic wants, `systemd` sandboxing directives, real
PID-1 semantics, D-Bus system bus integration, `journald` querying. There is
no client→manager IPC: a `systemctl start` issued in a terminal forks the
service under the invoking shell, so a manually started service lives with
that login session; services the *manager* starts (enabled units at session
start) live for the session. Cross-tree signalling is impossible by PRoot
design — a `systemctl` run in a *second* PRoot (not the product's session)
cannot reach the manager's services.

## Consequences

- `systemctl --version` reports `systemd 219 - via systemctl.py 1.7.1097`;
  units are the ordinary systemd unit-file format the base image already
  ships.
- Enabled services autostart with the session and are stopped when it stops;
  a host-process crash can still orphan service processes (Android's app-kill
  reaps them; the next session start cannot safely signal arbitrary service
  binaries, which is a documented boundary).
- EUPL-1.2 obligations: the verbatim file, licence text, and provenance are
  distributed inside the app assets; the licence applies to the vendored
  file, not to NusaDesk's own code.
- A rootfs that later gains an original/native `systemctl`, `service`, or Python
  interpreter through `apt` keeps that content physically, but the running
  PRoot session still uses the product bridge: `requiredBinds()` adds strict
  file binds (`host:guest!`) at the four conventional entrypoint paths. This
  preserves guest data while making the product `systemctl3` experience
  deterministic. The strict-bind path needs a fresh physical-device proof over
  a real apt-installed replacement.

## Device evidence (Samsung S10e, `R39M209Q3TM`, 2026-09-16)

Production process tree after session start (single tracer):

```
1884  libproot.so -r …/active -0 --link2symlink -b /proc:/proc -b /dev:/dev
      -b …/guest-service-bridge/active:/opt/lw-services
      -b …/lw-proc/uptime:/proc/uptime -b …/lw-proc/stat:/proc/stat
      -b /storage/emulated/0/Documents/nusadesk:/root/nusadesk
      --kill-on-exit /bin/sh /opt/lw-services/usr/sbin/lw-session-supervisor
      /run/lw-services-init.pid /run/lw-services-init.exit --
      /opt/lw-ssh/usr/sbin/sshd -D -e … -p 22022
 1888  supervisor (initial tracee)
  1890  init wrapper subshell
   1892  python3.12 /opt/lw-services/usr/bin/systemctl init
    1897  sh -c 'while true; do echo lwdemo-alive >> /run/lwdemo.log; sleep 30; done'
  1891  sshd … [listener]
```

- `systemctl --version` → `systemd 219 - via systemctl.py 1.7.1097`
- `systemctl is-system-running` → `running`; `list-units` enumerates the 17
  base-image units; `lwdemo.service` reports `MainPID=1897` in its status
  file, matching the ps tree.
- `systemctl start/stop/restart/status/is-active` verified working for a
  unit inside a single guest tree; `enable --now` produced the
  `multi-user.target.wants` symlink and an active service.
- Session restart: `am force-stop` left zero guest processes; the next
  session autostarted `lwdemo` under the new init (child of pid 1892).
- Manager death mid-session: host SIGTERM to the init tracee →
  `SubState=stopping`, exit marker `1`, service stopped, sshd unaffected;
  workload logged `guest service manager exited (exit 1); enabled services
  are no longer supervised`.
- Follow-up final-APK pass on the same SM-G970F plus SM-G935F (Android 10/API
  29) showed the live outer PRoot argv carrying strict `!` binds for all four
  product paths and the manager running as `python3.12
  /opt/lw-services/usr/bin/systemctl init`. This proves wiring reaches the
  live session; a package-installed native-file shadowing case remains a
  targeted follow-up because neither connected rootfs currently carries that
  replacement.
