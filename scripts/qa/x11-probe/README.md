# Android X11 socket probe regression

Run `bash scripts/qa/x11-probe/run.sh` with a connected device. It compiles the
actual production `X11SocketProbe.java` and a self-targeted instrumentation APK
using the existing SDK/JDK/debug keystore. It installs a separate QA package,
checks `INSTRUMENTATION_CODE: -1`, and removes that package on exit. Generated
files remain under ignored `app/build/x11-probe-qa/`. NusaDesk is not replaced
or stopped. With multiple devices set `ANDROID_SERIAL`.

The driver exercises an accepted X11 setup, a refused setup, a silent peer's
read timeout, and connect against a full Unix listener backlog. It checks the
real Android implementation, rather than Robolectric's socket shadow. Abstract
Unix addresses isolate the test from the guest's private filesystem. This
proves the API/socket mechanism; the guest filesystem endpoint, actual X server,
PRoot execution, renderer, and cold/warm app launch require a separate NusaDesk
APK/device test. No Android version/OEM matrix is inferred from one device.

The timeout overload of `LocalSocket.connect` exists but is unimplemented on
Android 10. `setSoTimeout` must follow descriptor creation and precede the
single-argument connect. Saturating the backlog verifies the connect bound;
a successful setup alone would not cover it.
