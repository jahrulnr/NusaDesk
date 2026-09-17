# ADR-0025: A bounded `udocker compose` adapter with product-owned restart supervision

## Status

Accepted and implemented: the `domain/compose` immutable spec/validation
types, `infrastructure/compose/ComposeYamlParser` (strict subset on
SnakeYAML Engine), the `assets/compose/` payload vendored into the
`guest-service-bridge` add-on (the product-owned `lw_compose_runtime.py`
runner, the `udocker` and `lw-compose-supervisor` launchers, the pinned
udocker 1.3.17 and PyYAML 6.0.1 source tarballs, the global
`lw-compose-supervisor.service` unit, and the licence/notice texts), the
guest wiring and pre-init enablement in `GuestServiceBridge`, and the fixed
inner-runtime binds in `ProotLauncher`.

Verified by the host-runnable asset harness (35 tests in
`assets/compose/test/run_tests.py`, which extracts the real pinned tarballs,
parses against the extracted PyYAML, and drives the supervisor against a
fake `lw-udocker` child — no real udocker, PRoot, network, or systemd is
touched), the domain suite (64 tests), the parser suite (22 tests), the
payload/wiring/launcher suites (`GuestAddonPayloadProfileTest`,
`GuestServiceBridgeTest`, `ProotLauncherTest`), and lint.

**Device-verified end to end on the Samsung S10e** (SM-G970F,
`R39M209Q3TM`, Android 12/API 31, arm64, 4 KB pages, 2026-09-16): the wired
overlay executed `udocker compose up -d` through the real supervised
service, an uncached registry image pull, and `unless-stopped` across a
host force-stop/relaunch. The verified matrix, the same-version
overlay-refresh fix, and the residual limits are recorded under
*Verification status*. The manual spike evidence that shaped the design is
recorded under Context.

## Context

The parent plan asked for a `udocker compose` MVP whose contract stays honest
about what udocker on PRoot can deliver — in particular that
`restart: always` and `restart: unless-stopped` must not pretend to be
Docker. The spike findings that constrain the design:

- **Native udocker is only a pull/create/run CLI.** It has no daemon, no
  Compose subcommand, no restart policy, and no notification channel. Any
  Compose behaviour on this guest is a NusaDesk adapter, not an upstream
  feature.
- **udocker 1.3.17 runs on the S10e under its P1/P2 execution engines with
  the NusaDesk PRoot as the inner executable.** The bundled udocker proot
  does not work nested, and the upstream `udocker-englib` helper tarball
  (~46 MB of proot/pty/tools binaries) is both Android-incompatible and an
  unpinned binary payload the curated-asset rule does not allow.
- **P1/P2 share the guest network namespace — and `--publish` cannot bind
  a host address.** `udocker --publish` is not a Docker bridge/NAT rule —
  the published port is a guest-side listener, and a container process
  that binds a wildcard address is reachable on the guest's real
  interfaces. There is no container DNS, isolation, or NAT to inherit.
  The final S10e traffic test (2026-09-16) closed the question: a
  `ports: ["127.0.0.1:18924:8080/tcp"]` declaration produced **no**
  listener on `:18924` at all while the container kept listening on
  `*:8080`, reachable via the device LAN IP — udocker/PRoot strips
  `host_ip` rather than enforcing it.
- **The vendored `systemctl3` only restart-supervises units that were
  enabled when `systemctl init` ran** (ADR-0024). A unit enabled mid-session
  runs but is never added to the manager's supervision list until the next
  session, so `Restart=always` on a dynamically-enabled unit is a lie. A
  plain `stop` is also not persistent across a session restart; only
  `disable --now` is.
- **`Restart=always` under systemctl3 does restart clean and non-zero
  exits**, and its default start-limit eventually stops a crash loop — so
  the manager is trusted to supervise exactly one thing: the product-owned
  supervisor itself.

## Decision

1. **The adapter is a strict, reject-first subset — not a Compose
   implementation.** The guest CLI is `udocker compose -f FILE
   [--project-name NAME] ACTION` with actions `up -d`, `down`, `stop`,
   `start`, `ps`, and `logs [SERVICE]`; `udocker compose-supervise --all`
   runs the supervisor in the foreground. The project name defaults to the
   compose file's stem. Every other upstream Compose feature fails closed
   with a path-specific error (see *Supported subset* below). The host-side
   `ComposeYamlParser` mirrors the same contract so a future Android surface
   can validate the identical rules; the guest-side parser is the one that
   executes.

2. **udocker and PyYAML are digest-pinned source, run by the payload's own
   interpreter.** `udocker-1.3.17.tar.gz` (Apache-2.0, SHA-256
   `f97ec976…c90bd1`) and `PyYAML-6.0.1.tar.gz` (MIT, SHA-256
   `bfdf460b…b4a43`) ship byte-for-byte under
   `usr/local/lib/nusadesk/compose/` and are re-verified before the only
   extraction the product performs: regular files under fixed package
   prefixes into the guest-private cache `/root/.local/share/lw-udocker`,
   with absolute/traversal names, escaping links, non-regular members, and
   oversized payloads refused (8 MiB per member, 64 MiB total, 4096 members)
   and a staging-directory rename for activation. **The `udocker-englib`
   helper tarball is deliberately absent**: `UDOCKER_TARBALL=""` makes
   upstream's auto-install a no-op, and the wrapper rejects `udocker
   install` outright, so no udocker command can trigger the helper
   download. Provenance and licences live in
   `assets/compose/THIRD_PARTY_NOTICES.md`, vendored into the overlay.

3. **The inner runtime is the packaged NusaDesk PRoot behind fixed binds.**
   The wrapper pins `UDOCKER_USE_PROOT_EXECUTABLE=/usr/local/bin/proot`,
   `PROOT_LOADER=/usr/local/bin/proot-loader`, and `PROOT_TMP_DIR=/tmp`.
   `ProotLauncher` adds a closed set of file binds to every session spec:
   `nativeLibraryDir/libproot.so` → `/usr/local/bin/proot`,
   `libproot-loader.so` → `/usr/local/bin/proot-loader`, and the Android ELF
   interpreter plus Bionic `libc`/`libdl`/`libm` (`/system/bin/linker64`,
   `/system/lib64/{libc,libdl,libm}.so`) each bound at its identical guest
   path — the packaged bridge is an Android PIE and cannot exec inside a
   glibc guest without its host linker and libraries. Every source and
   target is a constant; a missing host source or existing guest content
   skips that one bind, and parent directories are created under the rootfs
   only for a bind that is actually added. The outer `PROOT_LOADER` still
   points at the host-side loader.

4. **One product-owned global supervisor owns all restart semantics.**
   `lw-compose-supervisor` (`udocker compose-supervise --all`) polls
   `/root/.config/nusadesk/compose/*/project.json` and is the only restart
   authority. **No per-project or per-service systemd units are created** —
   because of the systemctl3 init gap, such a unit would run unsupervised
   while claiming `Restart=always`. The single shipped unit,
   `lw-compose-supervisor.service`, exists solely so the supervisor itself
   is autostarted and `Restart=always`-supervised (`RestartSec=5s`): that is
   honest only because `GuestServiceBridge.wireInto()` pre-creates its
   `multi-user.target.wants` link at session wire-up, i.e. before
   `systemctl init`, where the gap does not apply.

5. **Restart and manual-stop semantics are explicit and persisted.**
   The supervisor respawns a service as `udocker run --pull=reuse
   --name=<project>_<service>` with the plan's env-file, volumes, workdir,
   entrypoint, and command, in declared `depends_on` order:

   - `restart: no` (the default, including a YAML-1.1 `no`/omitted key):
     exit is final — the service reports `exited` until the plan is
     rewritten by `up` or `start` (the plan's `updated_at` converges it
     again).
   - `restart: always`: every exit restarts after exponential backoff —
     1 s doubling to a 60 s cap — reset once the child stayed up 30 s.
   - `restart: unless-stopped`: identical, except the persisted
     `manual_stop` flag suppresses the restart.
   - `restart: on-failure` and every other value are rejected at parse
     time, not approximated.
   - `compose stop` and `compose down` set `desired=false` and
     `manual_stop=true` atomically in `project.json`, then the supervisor
     SIGTERMs each child (8 s grace, then SIGKILL). Because the flag is on
     disk, a manual stop survives a session restart **for every policy** —
     deliberate divergence from Docker, where an `always` container comes
     back on daemon restart even after `docker stop`. `compose start`/`up`
     clears the flag.
   - On a session restart the supervisor's orphan pass kills any leftover
     child a stale pid file still names (verified by `/proc/<pid>/cmdline`
     containing the wrapper name and this service's `--name=`), then
     converges: desired projects come up, manual-stopped ones stay down.
   - Deterministic input failures (bad env file, unenforceable argv) are a
     terminal `error` state retried only on plan rewrite; transient spawn
     failures and child exits take the backoff path. A service whose
     `depends_on` target never spawned waits (`waiting`); a dependency in
     `error` marks the dependent `blocked`.
   - `compose up` requires `-d`: there is no foreground `up`, because the
     CLI never owns containers. `up`/`down`/`stop`/`start` only rewrite
     `project.json` atomically and SIGHUP the running supervisor.
   - `compose down` additionally waits (bounded) until no pid file names a
     live child before running `udocker rm` and deleting the project state;
     it refuses to remove state while a child still lives, because deleting
     it would orphan the process.

6. **Environment values never touch argv or logs.** Each service's
   environment is written as a line-based `KEY=VALUE` file under
   `run/<service>.env`, mode 0600, atomic, and passed to udocker as
   `--env-file=<path>` — values on argv would leak through
   `/proc/<pid>/cmdline`. The file is deleted when the child exits, is
   stopped, or the project goes down; supervisor logs redact any
   `--env=` token; newline values are rejected because the env-file
   format cannot represent them. The host environment is never read:
   `${...}` interpolation and key-only `environment:` entries fail closed.

7. **No `shell=True` anywhere.** Every spawn is an explicit argv list; the
   only place a shell is ever involved is a scalar `command:`/`entrypoint:`,
   adapted to the literal argv `[/bin/sh, -c, <text>]` inside the guest as
   the service's own declared command.

8. **All state is guest-private and written atomically.** `project.json`,
   `*.status`, pid files, env files, and the supervisor pid file go through
   temp-file + fsync + rename + directory-fsync; state lives under
   `/root/.config/nusadesk/compose/` and is removed with the app's data on
   uninstall. Logs are bounded (4 MiB per-service single-generation
   rotation; `logs` tails the last 300 lines/128 KiB).

## Supported subset — what this is NOT

Per-service keys: `image` (required; refs validated for argv safety, not
allowlisted — pull is delegated to upstream udocker's own download path),
`command`, `entrypoint`, `environment`, `volumes`, `ports` (parsed into
the model for future compatibility, but **refused at admission** — see
below), `working_dir`, `depends_on` (short list only, acyclic), `restart`.
Top level: `services`, `name` (must equal the project name), `version`
(ignored scalar).

Rejected rather than ignored: `build`, `networks`, `healthcheck`, `deploy`,
`secrets`, `configs`, and every other key; long-form `depends_on`
conditions; `on-failure` and unknown restart values; named and anonymous
volumes; container-only ports; key-only `environment` entries; YAML
anchors, aliases, tags, merge keys, and duplicate keys; `${...}`
interpolation anywhere; documents over 1 MiB.

Runtime-enforced refusals (`up` fails closed): `ro`/`read_only` volumes
(`udocker --volume` is always read-write) and **every `ports`
declaration**. The earlier design admitted an explicit loopback
`host_ip` as a contract guard; the S10e traffic test then proved
udocker/PRoot strips `host_ip` and provides no loopback-only enforcement,
so the guard could give a false safety impression. The MVP therefore
rejects `ports:` at the `runtime_problems()` admission boundary (checked
by `up` and re-checked on every supervisor scan, so a persisted plan from
before this change is refused too) and again fail-closed at argv build —
a declaration is never silently dropped, and no `--publish` token reaches
udocker. Port syntax remains parseable so the model keeps future
compatibility; a real loopback-only proxy/isolation implementation can
revisit this. Volume sources must resolve inside the workspace
`/root/nusadesk` after symlink resolution, and the compose file itself
must live there too. There is no Docker bridge, NAT, service-name DNS, or
container isolation; no `logs -f` beyond bounded tails; no foreground
`up`.

Known divergence: `udocker run --pull=reuse --name=…` reuses the
first-created container of that name, so a re-`up` with a changed image or
container-level configuration does not recreate it — `down` removes the
containers so the next `up` recreates them.

## Alternatives considered

- **Per-service/per-project systemctl3 units with `Restart=always`.**
  Rejected: the init-gap makes restart supervision a lie for any unit
  enabled mid-session, and unit `stop` state does not persist across
  sessions anyway. One pre-enabled global supervisor keeps every claim
  true.
- **The upstream `udocker-englib` helper tarball.** Rejected: its binaries
  are Android-incompatible (verified nested on the S10e), it is an unpinned
  ~46 MB blob contrary to the curated-asset rule, and `udocker install`
  would fetch it at runtime. The packaged NusaDesk PRoot is already the
  device-verified execution bridge.
- **A host-side (Java) supervisor.** Rejected: restart authority must
  signal processes inside the guest's PRoot tree and read guest-side state;
  the Android host already owns the session boundary, and pushing per-child
  supervision up would duplicate the state the guest CLI must own for
  `stop`/`start` to work from the terminal.
- **A vendored docker-compose binary.** Rejected: it drives the Docker
  daemon API, which does not exist here; it would also violate the
  Java-only/pinned-source asset rules.
- **Silently ignoring unsupported keys.** Rejected on principle: an
  accepted spec must never drop behaviour the adapter cannot honour.

## Consequences

- `udocker compose` is a real guest CLI once the `guest-service-bridge`
  add-on is installed: projects are declared in a compose file inside the
  same `~/nusadesk` workspace folder the user picked on Android, so the
  file and its bind sources are editable from both sides.
- Restart honesty is enforceable: `ps` reports the supervisor's own states
  (`running`, `backoff`, `exited`, `stopped`, `error`, `waiting`,
  `blocked`), and `unless-stopped` keeps its promise across session
  restarts because manual-stop is persisted state, not a unit flag.
- Secrets in `environment:` land in a 0600 env file for the child's
  lifetime only — better than argv, but still a file on guest-private
  disk; users should treat it accordingly.
- Image pulls go through upstream udocker's own Python download path; no
  curl/pycurl capability was added, and pull failures surface as ordinary
  child failures under the restart policy.
- Licence obligations grow by Apache-2.0 (udocker) and MIT (PyYAML);
  notices ship in the overlay. EUPL obligations are unchanged.
- The adapter needs the workspace bind to exist for bind sources; with no
  workspace chosen, compose files and volumes under `~/nusadesk` simply
  have nothing to resolve to.

## Verification status

- **Host evidence (done):** asset harness 35 tests; domain 64; parser 22;
  `GuestAddonPayloadProfileTest`, `GuestServiceBridgeTest`,
  `ProotLauncherTest`; lint.
- **Device spike evidence (done, pre-implementation, S10e Android 12):**
  udocker 1.3.17 P1/P2 with the NusaDesk inner PRoot; bundled-proot
  failure; shared-netns publish behaviour; systemctl3 restart/init/stop
  findings above.
- **Port traffic test (done, 2026-09-16, S10e):** a project declaring
  `ports: ["127.0.0.1:18924:8080/tcp"]` produced no listener on `:18924`;
  the container itself listened on `*:8080` and was reachable via the
  device LAN IP. udocker/PRoot strips `host_ip` and offers no
  loopback-only enforcement, so the MVP now rejects every `ports`
  declaration instead of admitting an explicit-loopback one.
- **Device PASS (done, 2026-09-16, S10e SM-G970F `R39M209Q3TM`, Android
  12/API 31, arm64, 4 KB pages):** the APK carrying the `.tgz`
  asset-packaging fix and the wrapper fallback fix installed; the wired
  overlay verified on disk — the 14-file manifest, the compose paths, the
  inner-PRoot and `/system` binds, and the pre-created
  `multi-user.target.wants` link. `systemctl` reported the manager running
  with `lw-compose-supervisor` active and enabled, and the product
  `/usr/local/bin/udocker --version` and image list ran. A real project on
  a pre-existing alpine image — explicit `127.0.0.1` TCP port declaration
  (admitted under the pre-rejection contract; the traffic test recorded
  above is why `ports` is now refused), a read-write workspace bind, a
  command that appends a marker then exits/sleeps — passed `up -d`; the
  supervisor spawned the child argv
  through `/usr/local/bin/udocker`, the nested PRoot executed it, the
  marker accumulated through the bind, `restart: always` cycled 6 times,
  `stop` froze the marker with `manual_stop` persisted, and `down` removed
  the project and container; cleanup left nothing behind.
- **Image pull (done, same pass):** an uncached `udocker`-driven
  `busybox:latest` pull completed in 16.36 s through upstream udocker's
  own download path — no curl/pycurl capability was needed — and the arm64
  manifest was verified before the image was removed again.
- **`unless-stopped` across a session restart (done, same pass):** a
  service under `restart: unless-stopped` came back after a host
  force-stop and relaunch, and stayed down once `stop` had persisted
  `manual_stop` until an explicit `start` cleared it.
- **Same-version overlay refresh is digest-aware (fixed after the initial
  pass):** the first verification pass observed that an unchanged add-on
  version was not re-installed, and had to delete one stale compose file
  to force the refresh. The guard added in response —
  `GuestServiceBridge.detect()` re-hashes every vendored file against its
  catalog SHA-256 pin on each session — now marks a stale or partially
  upgraded overlay "not installed", so the install pipeline reinstalls it
  without a version bump. The earlier manual deletion is recorded here
  only as the historical cause of that fix, not a current limitation.
- **Runtime limitation — `udocker rm` is best-effort in `down`:** after
  every pid file is confirmed dead, `down` runs `udocker rm` per service
  with a bounded wait and ignores its result before removing project
  state; a container udocker fails to remove is left in udocker's own
  store for manual `udocker rm` cleanup. Project state is never blocked
  on it, and a live child still refuses the `down` entirely.
- **Still open:** the wider device matrix (other API levels, 16 KB pages,
  OEMs) remains open per AGENTS.md, and no full Compose compatibility is
  claimed.
