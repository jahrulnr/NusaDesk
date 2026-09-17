# Third-party notices — `assets/compose/`

This directory ships **Python source only**. It does not contain, and the
runtime never downloads, udocker's ~46 MB `udocker-englib` helper tarball
(proot/pty/tools binaries). The inner PRoot used for `udocker run` is the
NusaDesk-built PRoot bound into the guest at `/usr/local/bin/proot` with its
loader at `/usr/local/bin/proot-loader` — see `UDOCKER_ENV` in
`lw_compose_runtime.py` (`UDOCKER_USE_PROOT_EXECUTABLE`, `PROOT_LOADER`,
`PROOT_TMP_DIR`, `UDOCKER_TARBALL=` empty, which makes upstream's
auto-install a no-op and prevents any helper download).

**Packaging note:** the tarballs are stored here with a `.tgz` suffix, not
`.tar.gz`. AGP's asset merge gunzips every asset whose extension is `gz`
and strips the suffix before the APK is written (a `noCompress` rule cannot
prevent it), so a `.tar.gz` asset would reach the package renamed and
decompressed — failing the pinned digest check. The bytes are unchanged
upstream releases, and the install step still writes them into the overlay
at the `.tar.gz` names `lw_compose_runtime.py` requires.

## udocker 1.3.17 — Apache License 2.0

- File: `udocker-1.3.17.tgz` (source release `udocker-1.3.17.tar.gz`,
  byte-for-byte)
- Upstream: <https://github.com/indigo-dc/udocker> (tag `1.3.17`)
- SHA-256: `f97ec97679133b5ada780025e6e263607cb4ec3401786f1db135e46153c90bd1`
- License: Apache-2.0 — full text in `LICENSE-udocker-1.3.17.txt`
- Usage: at first run the wrapper extracts only the regular files under
  `udocker-1.3.17/udocker/` into a guest-private cache
  (`/root/.local/share/lw-udocker/source/udocker/`) after re-verifying the
  tarball SHA-256, and dispatches CLI commands to `udocker.maincmd.main()`.
  Extraction rejects absolute/traversal paths, links, devices and oversized
  payloads; the extracted source is never executed as a shell string.
- `udocker install` is disabled in the wrapper: NusaDesk supplies the inner
  PRoot and never installs the upstream helper tarball.

## PyYAML 6.0.1 — MIT License

- File: `PyYAML-6.0.1.tgz` (source release `PyYAML-6.0.1.tar.gz`,
  byte-for-byte; the shipped tar contains upstream's own `LICENSE` file)
- Upstream: <https://github.com/yaml/pyyaml> (tag `6.0.1`)
- SHA-256: `bfdf460b1736c775f2ba9f6a92bca30bc2095067b8a9d77876d1fad6cc3b4a43`
- License: MIT
- Usage: only `PyYAML-6.0.1/lib/yaml/` (the pure-Python package) is
  extracted, next to `udocker/` in the same cache. The libyaml C extension
  is never built or loaded. The compose parser uses `yaml.safe_load`
  machinery only, after a token scan that rejects anchors, aliases and tags.

## Product-owned files

`lw-udocker`, `lw-compose-supervisor`, `lw_compose_runtime.py`,
`etc/systemd/system/lw-compose-supervisor.service` and `test/` are NusaDesk
product code, not third-party.

Install note for the wiring phase: APK asset entries carry no unix mode —
the step that copies `lw-udocker`/`lw-compose-supervisor` into the guest must
`chmod 0755` them (both carry a `#!/usr/bin/python3.12` shebang and are also
runnable as `python3.12 <path>`).

## Runtime design notes / limitations (honest contract)

- **Supervision**: `restart: always|unless-stopped|no` is implemented by the
  product-owned global supervisor `lw-compose-supervisor`
  (`udocker compose-supervise --all`), which polls
  `/root/.config/nusadesk/compose/*/project.json` and owns child restart,
  exponential backoff (1 s doubling to 60 s, reset after 30 s healthy) and
  persisted manual-stop state. **No per-project systemd units are created**:
  on-device evidence shows the vendored `systemctl3` only supervises units
  that were enabled at `systemctl init`; a unit enabled mid-session runs
  unsupervised until the next session, so `Restart=always` on a
  dynamically-enabled unit would be a lie. `Restart=always` **is** used on
  the shipped `lw-compose-supervisor.service` unit, which is intended to be
  enabled before session init where the gap does not apply.
  `compose up/start/stop/down` only rewrite `project.json` atomically and
  send `SIGHUP` to the supervisor when it is running.
- **Ports**: rejected outright in this MVP. Device traffic verification on
  the S10e (2026-09-16) showed udocker/PRoot strips the declared
  `host_ip`: a `127.0.0.1:18924:8080/tcp` publish produced no listener on
  `:18924` while the container kept listening on `*:8080`, reachable via
  the device LAN IP. `up` therefore fails closed on any `ports:`
  declaration and no `--publish` argv is ever generated; the parser still
  models port syntax for future compatibility. P1/P2 share the guest
  network namespace — there is no Docker bridge/NAT to inherit.
- **Volumes**: bind mounts only, always read-write; `ro`/`read_only` is
  rejected. Sources must resolve (after symlink resolution) inside the
  workspace root `/root/nusadesk`.
- **Restart divergence**: `udocker run --pull=reuse --name=…` reuses the
  first-created container of that name, so a re-`up` with a changed image or
  container-level config takes effect on the next (re)start, not
  immediately; `down` removes the containers so the next `up` recreates
  them.
- **`${...}` interpolation** is rejected everywhere; the host environment is
  never read. `environment:` entries without `=`, anchors/aliases/tags,
  merge keys (`<<`), duplicate keys, unknown keys, `build`, `healthcheck`,
  long-form `depends_on`, `on-failure`, named/anonymous volumes and
  container-only ports are all rejected with path-specific errors.
- The guest wrapper runs under `/usr/bin/python3.12` (guest-service-bridge
  payload, ADR-0024).
