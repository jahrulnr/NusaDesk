# Android GPU capability probe

Run `bash scripts/qa/gpu-probe/run.sh` with one attached ARM64 device. It uses
the project's existing SDK/NDK, Java tools and disposable Android debug key to
build a separate self-targeted instrumentation APK. It does not restart the
NusaDesk process, change guest packages, or request Android permissions. The
temporary QA package is uninstalled on exit; generated artifacts/results remain
under `app/build/gpu-probe-qa.*`.

The real Android app process records its SELinux domain, creates an EGL ES3
pbuffer, compiles shaders, draws a red triangle over a blue background and
checks the center pixel. The JNI probe enumerates a Vulkan physical device,
creates a logical device and graphics queue, submits a buffer-fill command,
synchronizes it, and checks the mapped bytes. Vulkan failures are retained in
the report rather than hidden behind the EGL result. Renderer names and API
versions are driver-reported metadata; these are not timing or throughput tests.

This establishes native Android GPU availability only. It does not establish
a guest Mesa driver, virgl connection, Electron hardware compositing, buffer
sharing, or improved chat scrolling. It does not exercise a visible Android
surface; pixel readback is the drawing assertion.

Prerequisites match the project SDK platform/build-tools and NDK 29.0.14206865.
`ADB`, `QA_NDK_ROOT`, `QA_DEBUG_KEYSTORE`, `ANDROID_SDK_ROOT`, and `PYTHON` may
override local tools. No native binary is committed. The default debug key is
the standard disposable `androiddebugkey`, not the production release key.

## Guest and Electron checks (ADR-0081)

The shipped `/opt/lw-desktop/graphics_probe.py` checks guest Mesa EGL readback.
Run it in the existing guest with `EGL_PLATFORM=surfaceless`,
`LIBGL_ALWAYS_SOFTWARE=1`, `GALLIUM_DRIVER=virpipe`, and
`VTEST_SOCKET_NAME=/tmp/.virgl` while the native display is running. It requires
the guest's Mesa EGL/GLES libraries and Python 3; it installs nothing.

For Electron, temporarily add `--remote-debugging-address=127.0.0.1
--remote-debugging-port=19224` to a QA launch through `gpu-electron.sh`, forward
that port with ADB, then run `node scripts/qa/gpu-probe/electron_webgl.mjs 19224`
(Node 22+). It compiles shaders, draws a triangle, and checks red/blue pixels.
The default tests the verified non-MSAA path; a fourth argument `true`
reproduces the known unsupported multisample case. Record the actual context attributes and Chromium GPU
status separately. Remove the temporary debug flags/forward and close that QA
instance afterwards. A successful native Android probe is not a substitute for
these guest/application checks.

### Scroll comparison (ADR-0081)

`scroll-ab.sh` and `electron_scroll.mjs` compare sustained scroll cost between
the virgl and llvmpipe arms. Copy `qa-electron.sh` into the guest (the shared
`nusadesk` folder is bind-mounted at `/root/nusadesk`), then run
`bash /root/nusadesk/qa-electron.sh gpu` (or `sw`) there to relaunch the same
app with the same flags plus the temporary DevTools port; both arms differ only
in `GALLIUM_DRIVER`. Forward that port with `adb forward`, open the view to
measure — the `#agent` conversation is `#event-stream`; the home view does not
scroll — then run `bash scripts/qa/gpu-probe/scroll-ab.sh 19224 150 33 4
'#event-stream'` on the host. Repeat per arm and compare: a single run does not
separate the arms reliably. Confirm the arm actually used with
`electron_webgl.mjs` first, because a silent fallback to llvmpipe otherwise
looks like a virgl result.

The script stamps frame intervals inside the page, so its own DevTools traffic
does not starve the animation frames it measures. It dispatches a fixed number
of wheels at a fixed cadence and alternates direction, so neither arm stops
against the container end. Frame intervals are the comparable signal: Chromium
animates wheel scrolling, so per-wheel time-to-first-movement measures the
animation ramp, not input latency. An idle baseline runs before the input block;
a slow idle page and a page that is slow only while drawing look different there.
`qa-electron.sh` and the measurement scripts are QA-only and install nothing in
the guest. Afterwards, close the QA instance (`Browser.close` over the DevTools
socket works), remove the forward and the copied script, and reopen the app's
own desktop tile so the supervised launch is what stays running.

The phone needs to stay awake across a measurement block, and the launcher's
search field takes typed input if focus is there instead of a terminal — both
cost me a wasted run each. Check `mWakefulness` and the focused window before
trusting a sequence of taps.
