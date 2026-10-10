# ADR-0061: Web-app surface recovery: a reload action and deferred child teardown

## Status

Accepted, implemented in `presentation/desktop/AppSurfaceHostView`,
`presentation/webapp/WebAppSurfaceView`, `presentation/MainActivity`, and the app
surface layout/drawable/strings. Covered by `AppSurfaceHostViewRefreshTest`,
`WebAppSurfaceViewReloadTest`, and the wiring contract inside
`WebAppWindowPolicyContractTest`. Device-verified on the Samsung SM-G970F
(Android 12/API 31): the build that preceded this ADR reproduced the force-close
with its native stack, and the fixed build passes WA-047/WA-048 in
`docs/test-plan.md`.

## Context

Two reports from the same surface (a user web app rendered in the owned loopback
WebView):

1. **No way back.** A page can navigate inside the app to a view that offers no
   route to the entry page. The surface's only reload affordance was the state
   panel's action, which is `GONE` while the app is loaded
   (`WebAppSurfaceView.render()`), so the only recovery was editing the app
   definition and saving it again. `WebAppDefinition.equals` includes
   `updatedAt`, so a save rebinds the surface, destroys the WebView, and loads
   `http://127.0.0.1:<port>/` again (ADR-0014, ADR-0048).
2. **A dead link force-closed the app.** Tapping a link to `http://127.0.0.1`
   (no port), shown by a guest-served page, killed the process. The address is
   `BLOCKED` by `LoopbackNavigationPolicy` (loopback host, unowned port) for both
   tab kinds, but the two kinds handled it differently: the root tab renders the
   explicit blocked state, while a child tab (the `target="_blank"` /
   `window.open` path, ADR-0048) called `closeTab(tabId)` **synchronously from
   inside the child's own WebView callback**. That path runs `removeView` →
   `stopLoading` → `setWebChromeClient(null)` → `setWebViewClient(null)` →
   `loadUrl("about:blank")` → `destroy()` while the renderer is committing the
   navigation, which takes the process down instead of closing a popup. A blocked
   popup was also silent: it simply vanished.

## Decision

1. **The task bar carries a reload action for a web app.** `AppSurfaceHostView`
   grows one icon button (`taskbar_refresh`, content description "Refresh this
   app") that is visible only while the open surface offers one, and
   `MainActivity.showWebApp` wires it to the surface's existing `reload()`: a
   fresh probe, a fresh renderer, child tabs closed, and the app's registered
   address loaded again. `showSurface` and `showTerminalApp` clear it, because a
   terminal session is not reloadable content and the launcher has nothing to
   reload. The state panel keeps its own action, which is the same method seen
   from a failure state.
2. **No WebView is destroyed from inside one of its own callbacks.** Every child
   teardown triggered by a callback (the four failure reports, the refused window
   transport, and `onCloseWindow`) is posted to the main looper through
   `postChildTabClose`, so the renderer is never torn down while it is on the
   stack. A teardown the user asked for (Back, a menu close, reload itself) stays
   synchronous, because no renderer callback is on the stack there.
3. **A refused link is never silent.** A blocked navigation in a child tab now
   closes the popup (deferred) and states why the tap did nothing
   (`webapp_link_blocked_toast`). The root tab keeps its blocked state and retry,
   which WA-012 already pins.
4. **The external handoff cannot kill the surface.** `startActivity` for an
   external link now catches `RuntimeException` rather than only
   `ActivityNotFoundException`: a resolver that refuses still means "no outside
   app can take this", and a link must never take the process down.

The loopback boundary itself is unchanged: the registered origin is still the
only origin that loads, an unowned loopback port is still never handed to the
system browser, and no JavaScript interface is added.

## Alternatives considered

- **A `Reload page` menu entry that calls `WebView.reload()` on the selected
  tab.** Cheaper, but it does not answer the reported problem: the user is stuck
  on a page they cannot leave, so reloading that page changes nothing. The
  registered address is what has to load again.
- **Keep the reload in the options menu instead of the bar.** One extra tap, and
  hidden exactly when the user is looking for a way out; the bar action is the
  discoverable answer, and the bar already reserves space for one button.
- **Give a blocked popup its own tab state instead of closing it.** A dead tab
  needs a per-tab failure UI plus a close affordance; ADR-0048 already says a
  failed child is cleaned up without taking down the root.
- **Keep the synchronous child close and only widen the catch.** The teardown
  happens in native WebView code; there is no Java exception to catch.

## Consequences

### Positive

- A user stuck on a page recovers in one tap, without editing the app.
- Opening a dead link in a popup no longer kills the app: the popup closes and
  the reason is stated, and the page the user was on stays.
- The teardown rule is stated once, so a later callback that closes a tab has one
  obvious place to go.

### Negative and limitations

- The reload action loads the app from its registered address, so in-page state
  (a form, an SPA's position) is lost. That is what the edit-and-save workaround
  did, and it is the only honest recovery when the page offers no route back.
- A popup whose address is refused is still closed, not offered elsewhere: an
  unowned loopback port is exactly what ADR-0003 forbids.
- The action is offered whenever a web app is open, including while the failure
  panel is up, where it duplicates that panel's action. One method, two
  affordances, no divergent behaviour.

## Verification

Robolectric: `AppSurfaceHostViewRefreshTest` (hidden until offered, visible and
clickable when offered, detached when taken away) and
`WebAppSurfaceViewReloadTest` (a reload re-probes the fixture and loads the
registered address on a *different* WebView instance, only the permanent root
tab remains, and an unreachable app stays honestly unreachable). Contract:
`WebAppWindowPolicyContractTest` pins that all four child failure listeners defer
their close, that a blocked popup reports the notice, and that the bar action is
wired for web apps and cleared for the surfaces that cannot reload.

Device pass (2026-10-10, Samsung SM-G970F, Android 12/API 31, arm64; the QA build
installed in place over the published one, so the guest data survived). The page
under test was a guest-served fixture whose whole viewport was one
`<a href="http://127.0.0.1" target="_blank">`. With the build that preceded this
ADR, tapping it killed the process: `logcat` recorded `Fatal signal 5 (SIGTRAP)`
with the stack `WebViewChromium.loadUrl` ← `android.webkit.WebView.loadUrl` ←
`WebAppSurfaceView.destroyTabWebView` ←
`LoopbackWebViewClient.shouldOverrideUrlLoading`. With this ADR's build, the same
tap left the process alive with no fatal signal, closed the popup, and showed
`That link is outside this app, so it was not opened.` while the page stayed.
`location.replace('/index.html?stuck=1')` (history length stayed 1) followed by
the task bar's `Refresh this app` returned the live page to
`http://127.0.0.1:10994/`. Row-by-row evidence: WA-047 and WA-048 in
`docs/test-plan.md`.
