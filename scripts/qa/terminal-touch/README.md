# Physical terminal gesture reproduction

This QA-only instrumentation injects real Android finger events into the app
currently visible on a connected 1080×1920 device. It has no Activity and does
not execute guest commands. Use a disposable terminal fixture or an idle TUI;
it will move the visible content. Native GUI surfaces are a different test.

From the repository root:

```sh
bash scripts/qa/terminal-touch/replay.sh zigzag
bash scripts/qa/terminal-touch/replay.sh long
bash scripts/qa/terminal-touch/replay.sh hold
```

`zigzag` keeps one finger down across down/up/down phases, 180 move injections
at a minimum 20ms interval. `long` moves continuously in one direction. `hold`
checks native long-press selection without moving. Injection waits for Android
processing, so actual duration exceeds the nominal 3.6 seconds; this is a
continuity/reversal test, not an input-rate or FPS benchmark. Coordinates stay
inside the content above the keyboard accessory row. Adapt the source explicitly
for another resolution rather than treating it as universal device coverage.

The script uses the configured Android SDK, JDK, zip and standard Android debug
keystore (`make build` creates it), builds under ignored `app/build/`, installs
the separate `qa.nusadesk.gestures` test package, injects the gesture, then
uninstalls that test package. It does not replace the NusaDesk APK. With multiple
devices set `ANDROID_SERIAL`; the serial is not written into repository evidence.

For event evidence on a debuggable NusaDesk build, inspect its owned WebView
through the Android WebView DevTools endpoint. Evaluate `trace.js` in the actual
terminal frame, call `NusaDeskTouchQa.start()`, run a gesture, then retain
`NusaDeskTouchQa.take()` as JSON. `start(term)` also records fractional xterm
positions in an isolated fixture where that instance is explicitly exposed.
Call `NusaDeskTouchQa.dispose()` and remove the temporary adb forward afterward.
The observer never prevents default or synthesizes input, so it cannot fix a
failing gesture by itself.

Check touchend delivery, event duration, direction reversal and detached-target
events, alongside screenshots/video. A moving TUI can still redraw only in whole
rows. Do not equate that with pixel scrollback or claim a constant frame rate
from this input driver. Both TUI and normal-buffer cases must be tested; one
passing short swipe is insufficient. See the gesture-lifetime evidence ledger.
