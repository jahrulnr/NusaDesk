# ADR-0064: Web-app surface cache actions

## Status

Accepted, implemented in `presentation/webapp/WebAppSurfaceView`,
`presentation/MainActivity`, and `res/values/strings.xml`. Covered by
`WebAppSurfaceViewCacheTest` (Robolectric, through `ShadowWebView`'s recorded
`clearCache`/`reload`) and the menu-wiring contract in
`WebAppWindowPolicyContractTest`. Device verification is pending; the actions
are the platform's `WebView.clearCache`/`reload`, applied on the surface that
owns the WebViews.

## Context

A user web app renders in a locked-down WebView on `WebSettings.LOAD_DEFAULT`,
so its HTTP cache is shared, persistent, and invisible: once a resource is
cached the page keeps using it, and the only reload the surface offered is the
task-bar refresh, which reloads from the registered entry point on a fresh
renderer (ADR-0061) but does not drop the cache. When an app's UI changes but
its server keeps serving cached bytes, the user cannot tell a stale cache from
an app that never updated — reported while developing a web app against this
shell, where the only escape was Android's own "clear storage".

## Decision

The web-app options menu gains two actions next to `Edit app…`:

1. **Reload without cache.** `WebAppSurfaceView.reloadWithoutCache()` calls
   `clearCache(true)` on the selected tab's WebView and then `reload()` on it.
   The page the user is on is reloaded in place — the current URL and the
   in-page history are kept, which is exactly what ADR-0061's refresh action
   deliberately does not do. With nothing loaded (probing, unreachable,
   failed) it falls back to the ordinary `reload()`, so the action never lies
   about bypassing a cache it could not touch. Because `clearCache` is
   process-wide, one call is the whole app's cache: other tabs re-fetch on
   their next load too.
2. **Clear cache.** `WebAppSurfaceView.clearWebCache()` drops the same cache
   without touching the open page. When the surface has no live WebView it
   uses a throwaway `WebView` instance — the store is app-wide and outlives a
   process run, so the action still works — and the outcome is always stated
   in a toast, because a successful clear changes nothing the user can see.

Scope of "cache": the HTTP resource cache only. DOM storage, cookies, and the
stored HTTP-auth pair (ADR-0058) are *not* cleared — they are application
data, not cache, and dropping them would sign the user out of the app they
were debugging. The WebView boundary itself is unchanged: `LOAD_DEFAULT`
stays the cache mode; these actions are user-invoked events, not a policy
change.

## Alternatives considered

- **Make the existing task-bar refresh bypass the cache.** That action is
  ADR-0061's recovery from a page with no way back; coupling a state reset
  to "drop the cache" would conflate two different repairs. The menu action
  is the place for the in-place variant.
- **`LOAD_NO_CACHE` around the reload instead of clearing.** It would leave
  stale entries resident and readable by the revalidated load path; dropping
  the cache is simpler and also answers the second action for free.
- **Clear `WebStorage`/cookies too.** That is "clear app data", a different
  and destructive operation (it signs the user out); nothing in the request
  asked for it, so it stays out.
- **Group-clear all open tabs.** The reported case is the page in view; other
  tabs' caches are already dropped by the same process-wide clear, and
  reloading unseen tabs would only burn guest work.

## Consequences

- A user testing their own app can force a fresh render in one tap and, when
  something still looks stale, can trust the tile is not showing cached bytes.
- The two actions are offered whenever a web app surface is open, in every
  state; when no WebView exists yet they degrade to the same safe answers
  (ordinary reload, toast-reported clear).
- Clearing is process-wide: the terminal surface and other apps' surfaces
  share the store and simply re-fetch afterwards. No credential, DOM storage,
  or tab state is lost.
- `WebAppWindowPolicyContractTest` pins the menu wiring so a later refactor
  cannot silently drop either action.
