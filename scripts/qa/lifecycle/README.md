# Physical lifecycle acceptance

This QA-only instrumentation runs the installed NusaDesk code in its Android app
process. It **restarts the app process and stops active guest sessions**, uses
installed Mousepad with an empty document, and creates then removes one isolated
Web + Terminal registration. The default mode does not install guest packages
or edit user documents. It requires the installed GUI prerequisites and guest
user-service manager. Use only when interruption of the current guest is authorized.

Build and install the native QA release first:

```sh
make release GRADLE_FLAGS='--no-daemon -PnativeDesktop=true -PqaDebuggable=true'
adb install -r app/build/outputs/apk/release/app-release.apk
bash scripts/qa/lifecycle/run.sh
```

The script compares the installed APK with the local release before running,
compiles against that release's classes, and signs the separate instrumentation
APK using the existing ignored signing material. No production QA endpoint or
dependency is added. Outputs live under ignored `app/build/lifecycle-qa/` and
the target's `files/qa-lifecycle/`. The driver package is uninstalled on exit;
copy and inspect the evidence, then remove the target's QA output directory.
Instrumenting the target ends its process at completion; reopen NusaDesk normally
afterwards to restore an ordinary runtime session.

The native case forwards a real Android service binding through a test context
that delays `onServiceConnected` by 1.5 seconds. It requests the real guest Stop
while that callback is pending, then checks display PID death, socket removal,
guest temporary-storage cleanup and successful new display/app startup. This is
deterministic callback fault injection, not a measurement of natural cold-start
latency. The screenshot must be inspected; a lockscreen is not GUI evidence.

The combined app runs a guest loopback Python HTTP fixture returning its PID.
Its signal handler delays exit for two seconds. The cases cover Home/reopen,
three deduplicated restarts (one across Activity recreation), an independently
owned occupied TCP port, edit cancellation, guest Stop during restart followed
by a new session, and delete cancellation. Trigger/ready times and old/new PIDs
are recorded. Registration edits call the same application boundaries as the
form callbacks; these are not automated Add/Edit form or menu-touch acceptance.

The result JSON must say `passed: true`; an absent/stale result cannot pass.
The script clears the old result before starting. Screen wake/dismiss is for an
unsecured QA device; it cannot unlock a device requiring user authentication.
The Activity's temporary keep-screen-on flag is cleared at completion.

`QA_MODE=environment` runs only the DISPLAY checks. `QA_MODE=nusashell` also
uses the user-authorized `/root/NusaShell` checkout and runs its actual
`make install` from an interactive login shell, preserving its NVM/Go PATH.
It declines the optional login-service installation and answers yes to the
normal Electron and Pet prompts, without forcing desktop detection through an
installer override. It installs the documented Pet build prerequisites using
Ubuntu apt and supplies the installer's supported `NUSASHELL_BUILD_DIR` for
Electron builder's `dist/linux-arm64-unpacked` output on this device. Build output is available live at the target's
`files/qa-lifecycle/nusashell-install.log`. This mode deliberately changes the
guest's installed NusaShell components and needs that authorization; it is not
part of the default disposable fixture suite.

`QA_MODE=nusashell-ui` tests already-installed components: it opens the real
Electron desktop entry through the native launcher, then briefly runs Pet on
the same display and captures both. Inspect the images and log; a live PID alone
does not prove a correct window. The standalone Pet fixture is stopped after
15 seconds and its temporary PID/log files are removed.
