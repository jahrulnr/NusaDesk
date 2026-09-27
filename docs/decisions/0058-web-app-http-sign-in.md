# ADR-0058: HTTP sign-in for auth-protected web apps

## Status

Accepted. Adds an explicit `AUTH_REQUIRED` state to the web-app surface that
ADR-0014 introduced — alongside its probing, unreachable, failed, and loaded
states — and extends ADR-0015's favicon fetch so an auth-protected app keeps
its tile icon.

JVM unit and Robolectric tests cover the pure decisions: the credential value
object, the codec, the challenge policy, and the one-try bound on a stored
pair. The real cycle — a guest server answering 401, the card, a stored pair,
the rendered page — is device/WebView behaviour and is **not device-verified**;
it is tracked as WA-028 through WA-034 in `docs/test-plan.md`. Per AGENTS.md, a
unit-test-only result is not evidence for lifecycle or visual behaviour.

## Context

A registered web app is allowed to protect its own loopback endpoint; the
guest app `goclaw` does exactly that with HTTP Basic auth. Until now the host
could not answer the challenge at all:

1. `WebAppReadinessObserver` correctly treats the 401 as reachable — any HTTP
   status proves the endpoint answers — so the tile is healthy and the
   WebView load starts.
2. `LoopbackWebViewClient` never overrode `onReceivedHttpAuthRequest`, so the
   framework default queried the empty `WebViewDatabase`, found nothing, and
   called `handler.cancel()`. The load is aborted.
3. `onReceivedHttpError(401)` then drove `WebAppSurfaceView` into
   `State.FAILED` reading "could not be shown / HTTP 401", with a "Try again"
   action that can never succeed.

An app that was running perfectly was reported as broken, and the user had no
way to sign in.

Three constraints shape the fix:

- **The SDK cannot pin a challenge to a port.** `onReceivedHttpAuthRequest`
  supplies a host and a realm but no port, and the public
  `android.webkit.HttpAuthHandler` exposes only `cancel()`,
  `proceed(String, String)`, and `useHttpAuthUsernamePassword()` — verified
  with `javap` against `platforms/android-37.0/android.jar`. Every registered
  app is generated as `http://127.0.0.1:<port>/`
  (`WebAppDefinition.getEndpointUrl()`), so all of them share the one owned
  host `127.0.0.1`. Answering a challenge is also not a navigation, so the
  navigation policy never sees it: a page's own document can make a
  subresource request to a different loopback port without
  `shouldOverrideUrlLoading` being consulted.
- **`WebViewDatabase` stores plaintext.** The one-line fix — letting the
  framework consult the WebView's own credential database — keeps username
  and password unencrypted in app-private storage, and keys them by host and
  realm, both of which are weaker scopes than the app itself.
- **The surface has no JavaScript bridge on purpose.** ADR-0014/ADR-0048 keep
  the web-app WebView free of `addJavascriptInterface`. An in-page sign-in
  form would need a `WebMessagePort`-style channel back to the host and would
  put the plaintext password inside the WebView renderer process — the one
  place this design keeps the secret out of.

## Decision

1. **Every HTTP auth challenge is answered by an explicit policy, never the
   platform default.** `LoopbackWebViewClient` overrides
   `onReceivedHttpAuthRequest`; `WebViewDatabase` is never queried. A
   challenge is answered only when `LoopbackAuthChallengePolicy` classifies
   its host as `OWNED` — an exact match on the host this WebView owns, which
   for a registered web app is always the literal `127.0.0.1`. A challenge
   for any other host — a LAN address, a public name, anything that is not
   the owned host — gets `handler.cancel()` and is never answered.
2. **"Needs sign-in" is its own surface state.** `WebAppSurfaceView.State`
   gains `AUTH_REQUIRED`. A reachable app that only wants credentials is
   never described as broken: no `FAILED`, no "could not be shown", and no
   "Try again" that cannot work. Dismissing the card is a settled
   `AUTH_REQUIRED`, not an error.
3. **The sign-in UI is a native card owned by the surface.** A centered card
   in NusaDesk styling carrying the app's own launcher icon (the NusaDesk mark
   is the fallback until the launcher has decoded one for the tile), the app's
   name, the server-supplied realm for context, username and password fields,
   a submit action, and a "remember" checkbox. The password goes from the
   field to `proceed()` and — only when the checkbox is left ticked — to the
   vault; the checkbox is checked by default, so remembering is an opt-*out*,
   not a hidden default. A wrong remembered pair is recovered from by
   re-submitting: the card prefills the username and the next accepted pair
   overwrites the stored one, and removing the app clears it outright. It
   never enters the WebView renderer, and the surface still registers no
   JavaScript interface.
4. **Storage reuses the existing Keystore vault, scoped per app.** A
   remembered pair is encoded by the pure `WebAppSignInCodec`
   (`username`, `NUL`, `password` in UTF-8 — `NUL` is the one character a
   username may not contain, so the split is unambiguous and the password
   stays opaque) and stored through the existing
   `infrastructure/session/KeystoreCredentialVault` — AES-256-GCM under a
   non-exportable `AndroidKeyStore` key, `CredentialType.PASSWORD` — under
   the credential id `webapp.<webAppId>`. No new key, cipher, or dependency.
   The `application` port `WebAppCredentialStore` is keyed by `WebAppId`, not
   by host or realm: a registered app owns exactly one generated endpoint, so
   its id already names the only endpoint the pair may ever be sent to, and
   one app can never be answered with another's pair.
   `WebAppSignInCredential.toString()` redacts both fields; neither field
   reaches a log, an exception message, or `WebViewDatabase`.
5. **A stored pair is tried at most once per surface open.** If the server
   challenges again after the stored pair was sent, the sign-in card is shown
   instead of the stored value being retried — the one automated repetition
   in the design, capped at one. A rejected saved credential re-prompts
   exactly once and is then reported as a sign-in failure rather than
   looping: a wrong pair is never resent automatically.
6. **The challenge handshake is asynchronous.** `HttpAuthResponder` receives
   `(host, realm, Answer)` and must return immediately, completing exactly
   one `Answer` later, because the challenge arrives on the main thread and
   the card may be the only way to obtain a pair — blocking that thread would
   deadlock the dialog it is waiting for.
7. **The favicon fetch reuses the stored pair.** `WebAppFaviconFetcher`
   answers the endpoint's challenge with the app's stored credential on its
   bounded same-origin requests, so an auth-protected app keeps its real tile
   icon instead of being pinned to the monogram by its own 401. The rest of
   ADR-0015 is unchanged: generated origin only, one attempt, bounded sizes,
   silent failure.
8. **The card is dismissed by a page that loads, never by the submit tap.**
   On submit the card goes inert and waits, because the answer is not known
   until the server has seen the pair. Dismissing on submit and reopening on a
   refused pair flashes the whole surface twice for every wrong password: the
   surface drops back to loaded and the aborted 401 page is briefly visible
   behind it. `HttpAuthResponder.handlePageLoaded()` is the only thing that
   closes it, so the sequence is one state rather than three.
9. **Removing an app forgets its credential.** Deleting a registration clears
   `webapp.<webAppId>`, so the password does not outlive the app it was saved
   for.
10. **The Add/Edit app form still has no credential field.** Credentials are
   entered only in response to a challenge the app's own origin raised —
   never collected up front and never editable in the form. The `strings.xml`
   comment's "no host, credential, or profile input" remains literally true;
   the sign-in path lives on the surface, not the form.

## Rejected alternatives

- **`WebViewDatabase` / `useHttpAuthUsernamePassword()`.** The one-line fix.
  It stores credentials in plaintext app-private storage, and it scopes them
  by host and realm — a realm is server-chosen text, so it cannot even pin a
  pair to the app the user saved it for.
- **An injected HTML sign-in page.** It could borrow the app's own styling,
  but returning the password to the host requires a `WebMessagePort` or
  `addJavascriptInterface` channel into a deliberately bridge-less surface,
  and the plaintext password would live inside the WebView renderer process.
- **The platform `AlertDialog` auth prompt.** A generic floating window the
  surface does not own: it cannot carry the app mark, name, realm, or the
  remember affordance, and it sits outside the explicit state model —
  the surface would still need an `AUTH_REQUIRED` state behind it, so the
  card simply *is* that state.
- **Per-host or per-realm credential scope.** A challenge carries no port and
  a realm is attacker-chosen text; only the app id names a durable truth, so
  the store is per app.
- **Prompt on every open, never store.** Honest but unusable: an app that
  guards every load would force retyping each time, and the favicon fetch
  could never answer. Remembering is offered on the card rather than
  demanded, and the card prefills the username so re-answering it is a
  one-field edit.

## Known limitation: a challenge cannot be pinned to a port

`onReceivedHttpAuthRequest` names a host and a realm but no port, and the
public `HttpAuthHandler` has no requesting-port accessor, so "answer only this
app" is enforceable only down to the owned host — `127.0.0.1`, which every
registered app shares. A page served by app A that references a subresource on
app B's port would be answered with A's stored pair if B's endpoint challenges
the fetch. Subresource requests are not navigations, so the navigation policy
does not see them, and the public SDK offers no way to close this.

The practical exposure is narrow: the referring page is itself content the
user installed in their own guest, and a process inside that guest could
already read the same password off the guest filesystem. The limit is still
real — a saved password can be sent to a listener on a loopback port the saved
app does not own — so it is recorded here and in `SECURITY.md` rather than
claimed solved.

## Consequences

- An auth-protected app opens: probe, 401 challenge, stored pair or the card,
  then the rendered page. A reachable app that wants credentials is never
  called broken again.
- Child tabs share the same boundary client (`WebAppWebViewBoundary` remains
  the single construction site for `LoopbackWebViewClient`), so a challenge
  raised inside a `window.open()` tab (ADR-0048) is covered by the same
  policy and the same card.
- The bridge-less boundary is unchanged: no `addJavascriptInterface` was
  added, and the sign-in path adds no JavaScript surface at all.
- The credential's blast radius is one app — a per-app vault id, a per-app
  store, deletion clears it — and a pair is sent only to challenges naming
  the owned host, subject to the port limitation above.
- The launcher tile keeps its real icon for a protected app; nothing new is
  cached or persisted for favicons.
- The terminal is untouched: `TerminalBridgeView` uses its own
  `TerminalWebViewClient`, so the xterm surface gains no auth behaviour it
  never needed.
- Entered-in-the-card credentials are the only ones that exist: nothing about
  the Add/Edit form, the registration record, or the generated origin
  changed.
- **Not device-verified.** The unit and Robolectric coverage stated in Status
  cannot prove a real Chromium challenge cycle; the 401 → card → signed-in →
  page-rendered path on a guest app such as `goclaw` needs a physical device
  and is tracked in `docs/test-plan.md`.
