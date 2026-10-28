# ADR-0068: Experimental native X11 app surface for Android 10+

## Status

ADR-0080 supersedes the build-default and public-distribution boundary below:
regular updates now include the renderer and matching desktop source materials.
The experimental runtime compatibility limits remain in force.

Accepted for the user-authorized best-effort experiment, behind the build-time
`-PnativeDesktop=true` switch. Not a released GUI support claim. Device results
and unresolved gates are tracked in `docs/limitations.md`.

## Context

The requested GUI runs locally in NusaDesk's existing ARM64 Ubuntu/PRoot guest
and renders into the Android app area without WebView or VNC/RFB. One selected
app fills that area, including its dialogs and popups. Android 10/API 29 is the
minimum target; no device-owner or lock-task integration is required.

The source audit recorded in the `native-desktop-display-feasibility` research
note (since removed — see git history) selects Termux:X11 as the smallest
viable native display basis. The upstream native
server calls process exit on termination/fatal failure and must not run inside
the launcher process. Importing its full Android module would also import its
Activity, manifest, input services and Kotlin dependencies.

## Decision

1. Build the pinned Xlorie native source and recursive submodules with NDK r29,
   `android-29`, ARM64 and 16 KB ELF alignment. The build script generates the
   native library and license assets under `app/build/`; no native binary is
   checked in. The source SHA, submodule lock, patch and build script are the
   provenance. Actual runtime compatibility on 16 KB pages remains unverified.
2. Host Xorg in a private, non-exported bound Android service in
   `:native_display`. No second guest session or foreground notification is
   introduced; the existing runtime host remains the owner. Exchange the
   renderer socket FD through private AIDL/Binder. Remove the upstream wildcard
   TCP knock listener and disable X11 TCP listening.
3. Keep the Android renderer in the retained app surface's FrameLayout, through
   a Java SurfaceView/JNI adapter. Preserve surface-independent X server
   lifetime. Release pressed keys/buttons when focus or the surface is lost.
   Keyboard/IME input is local to the focused app area; no accessibility/global
   capture service is imported. Clipboard sync is not enabled in this slice.
4. Share the real rootfs `/tmp` with the host server. Start the display only
   after the existing guest session is ready. Before each ADR-0027 cleanup,
   terminate the display and wait for Binder death; failure prevents that
   cleanup. Display retry does not create another Linux session.
5. The initial fixed Mousepad profile was superseded by ADR-0069/0070:
   one Openbox channel serves separately supervised desktop-ID launches through
   GIO. `xdpyinfo` verifies X11, EWMH `_NET_SUPPORTING_WM_CHECK` verifies the
   WM, and the helper verifies owned EWMH clients before app readiness. No
   `xwininfo` readiness dependency or fixed Mousepad launcher remains.
6. Provision the experiment's GUI packages explicitly in the existing guest;
   this first slice reports missing prerequisites instead of installing them
   silently. The experiment uses Ubuntu's signed package repositories and
   records installed versions. It is not yet a digest-pinned, product-managed
   GUI add-on or a general app installer.
7. The default build omits the optional native binary and launcher tile.
   Enabling the slice links GPL-3.0 Xlorie, matching NusaDesk's own GPL-3.0
   license. Preserve the root GPL-3.0 notice for NusaDesk's own source and
   package upstream
   native notices. Public distribution requires corresponding source/build
   materials and a combined-work license review; this experiment publishes no
   release and makes no legal clearance claim.

## Verification and consequences

Tests exercise coordinate mapping and guest command readiness/child cleanup;
Gradle lint and assembly check the enabled build. On-device acceptance requires
actual guest frames, touch/input, menu/dialog behavior, Home/return and clean
session stop/restart. Retained physical evidence covers the API 29 S7 Edge
FloydROM, including a bounded rotation sample; the earlier API 31 S10e
observations are historical and were not independently re-verified. This does
not prove stock Android 10, modern Android, 16 KB pages or other OEMs. GPU
presentation and guest GPU rendering remain separate, documented in
`docs/limitations.md`.

A companion APK or Android test pattern does not satisfy the feature. Piperella
(API 36) and the inspected wlroots bridge (API 34/x86_64 configuration) remain
research alternatives rather than dependencies of this slice.
