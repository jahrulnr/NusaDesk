# ADR-0048: Internal tabs for web-app new windows

## Status

Accepted and implemented in `presentation/webapp/` and the desktop shell. The
initial implementation is in-memory and capped; device verification is tracked
in `docs/test-plan.md`.

## Context

A registered web app originally had one retained `WebView`. The host did not
install a `WebChromeClient` or handle `onCreateWindow()`/`onCloseWindow()`.
Android WebView therefore had no product-owned window lifecycle. With multiple
window support disabled, `target="_blank"` and `window.open()` can become a
same-WebView top-level navigation, replacing the app's root page and leaving
no reliable path back to it.

The existing product contracts still apply: the launcher is home, each app owns
one generated loopback origin, different loopback ports are blocked, external
links leave for the system handler, and no broad JavaScript interface exists.

## Decision

1. Each registered web app has one permanent in-memory root tab and up to four
   in-memory child tabs. Tabs are scoped to that app; there is no global browser
   tab pool.
2. WebView settings explicitly enable multiple-window callbacks while disabling
   JavaScript-created windows without a user gesture. `onCreateWindow()` accepts
   only `isUserGesture == true`, creates a child `WebViewTransport`, and selects
   the new child immediately. A full tab stack rejects the request without
   replacing the root.
3. Root and child WebViews use the same `WebAppWebViewBoundary` and
   `LoopbackWebViewClient`. A child cannot widen the origin allowlist, reach a
   different loopback port, or install a JavaScript interface. External HTTP(S)
   and supported intent links keep using the system handler.
4. `onCloseWindow()` closes only child tabs. The root is permanent. Renderer
   failure or explicit close cleans up the child without taking down the root.
5. The existing taskbar options menu exposes tab selection and child close
   actions alongside `Edit app`. Android Back first follows the selected
   WebView's history, then closes a selected child, then returns from the root
   surface to the launcher.
6. Tab metadata and WebViews are not persisted in this MVP. Activity recreation
   follows the existing surface contract and reloads the app root; bounded
   cleanup is preferred over serializing unbounded Chromium state.

## Alternatives considered

- **Leave multiple windows disabled.** Safe against arbitrary popups, but on the
  current WebView behavior a new-window request can replace the root page. It
  does not solve the reported loss of the main app.
- **Open every new window in the system browser.** This loses the local app
  context and is not dependable for an app whose UI is bound to a guest-local
  loopback origin.
- **Use a new Android Activity per window.** This fragments the launcher-first
  shell and makes Back/lifecycle recovery depend on an Activity stack rather
  than the app surface's single origin policy.
- **Persist WebView state and build a full browser tab system.** Not justified
  for the first fix; it increases memory, saved-state, and renderer recovery
  complexity without being required to keep the root reachable.

## Consequences

- Web apps that use user-initiated `target="_blank"` or `window.open()` retain
  their main page and can return to it from the taskbar menu or Back.
- Background popups are refused, and the fourth child limit bounds renderer
  growth. Apps that require arbitrary background windows must provide an
  in-page flow or be handled by a later explicit capability.
- Tab behavior is device/WebView behavior and requires real-device QA in
  addition to the pure `WebAppTabStack` tests. The JVM suite verifies the
  bounded state machine and a contract guard for the Android callback wiring;
  it does not run a Chromium renderer.
