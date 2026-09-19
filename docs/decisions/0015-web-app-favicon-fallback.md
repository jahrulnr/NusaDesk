# ADR-0015: Favicon fallback for user web-app launcher tiles

## Status

Accepted and implemented in `infrastructure/webapp/`, `presentation/`, and their
tests. UX-verified on the x86_64 UI emulator (API 35) against a
loopback fixture server; see `docs/test-plan.md`.

## Context

A user web app is registered with a name, a guest port, and an **optional**
image. In practice the image is the exception: the user has to pick a file
through the system document picker, so most tiles show a monogram of the app's
own name. The tile is therefore the least informative part of the launcher, even
though the app's own server usually already publishes a favicon — every browser
asks for `/favicon.ico` by convention, so a web app that is meant to be opened in
this product's WebView almost always has one.

Three constraints shape what may be fetched:

1. **The origin is generated, not stored.** `WebAppDefinition.getEndpointUrl()`
   is exactly `http://127.0.0.1:<guestPort>/`. A favicon must not widen that:
   no user URL, no HTML parsing to discover a `<link rel="icon">` (which can name
   any host), no redirect following (which would leave the origin).
2. **The response is untrusted input.** The server on the other end is a process
   this app supervises but does not control. A "favicon" can be an error page, a
   decompression bomb, or an unbounded stream.
3. **A missing favicon is not an error.** The user cannot act on it, and the
   tile already has an honest fallback. Reporting it would be a false error.

ADR-0014 deliberately rejected probing every web app when the launcher renders
("N bounded HTTP probes per render for information the tile cannot honestly
show"). A favicon is different: it *is* information the tile can show. The
decision below therefore keeps the spirit of that rejection — one bounded
request per app, never per render — while allowing the request itself.

## Decision

1. **One derived URL, never user input.**
   `WebAppFaviconEndpoint.forDefinition(definition)` returns the definition's own
   generated origin plus the single `favicon.ico` path, so the request is exactly
   `http://127.0.0.1:<guestPort>/favicon.ico`. The port is already validated by
   `GuestPortPolicy` (the terminal's reserved port is not a web-app port), and no
   record field, no stored icon token, and no response can change the host,
   scheme, port, or path.

2. **`FaviconResponsePolicy` is the whole acceptance rule, and it is pure.**
   Only `200 OK` is usable — a redirect is never followed, and the body of any
   other status is never read. The payload must fit a 64 KiB byte cap, declared or
   received. The image must fit a 1024 px source cap, and it is decoded with a
   power-of-two `inSampleSize` that keeps both sides within 256 px (the tile
   plate at 4x), so the decoded bitmap is bounded by the tile rather than by
   whatever the server chose. A bitmap outside that budget is recycled and
   rejected.

3. **The decode is the format check.**
   There is no `Content-Type` allowlist: a server that serves its favicon as
   `application/octet-stream` is common, and the only check that actually proves
   "this is an image" is `BitmapFactory` decoding it. `BitmapFactory` first reads
   the header with `inJustDecodeBounds` (cheap, no pixels), the policy judges the
   dimensions, and only then are pixels decoded.

4. **Bounded in every dimension, and blocking by contract.**
   `WebAppFaviconFetcher` uses explicit connect and read timeouts, no redirects,
   no cache, `Connection: close`, a hard byte cap on the read, and closes the
   stream and disconnects in a `finally`. It runs on `MainActivity`'s own
   favicon executor, never on the main thread.

5. **Nothing is persisted.**
   There is no disk cache, no file, and no `WebView` cache reuse. Decoded
   favicons live in one in-memory map on `MainActivity` for the life of the
   process and are dropped when the app is edited or deleted. A cache was not
   justified: the fetch is one loopback request per app, the image is a few
   kilobytes, and a persisted copy of a response from a process the app does not
   control would outlive the endpoint it came from.

6. **The user's image always wins, and the fallback order is explicit.**
   `LauncherIconPolicy` states the order — the user's picked image, then the
   app's favicon, then a monogram of the app's name — and the tile walks it,
   skipping a source that cannot actually be rendered (a revoked picker
   permission degrades to the favicon or the monogram instead of an empty plate).
   An app with a stored icon is never fetched for: the request is not made at all,
   so the user's choice cannot be replaced.

7. **A request is made once per app, from two honest moments.**
   `MainActivity` asks when the launcher is shown (never from a render — the
   launcher re-renders on every search keystroke) and once more when the app's own
   surface reports its endpoint reachable, because the first attempt happens
   before the user has ever opened the app, when the endpoint is usually still
   down. Each app is identified by port and edit time, so an image fetched from a
   previous endpoint is dropped rather than shown for the new one, and a result
   that arrives after the app was edited or deleted is ignored.

8. **Every failure is silent.**
   Unreachable, timed out, redirected, wrong status, too large, not an image,
   undecodable — all produce `null`, the tile keeps its monogram, and the user is
   shown no error, no toast, and no state change.

Rejected alternatives:

- **Parse the app's HTML for `<link rel="icon">`.** It is the only way to find a
  non-default icon path, but it means fetching and parsing an arbitrary document
  and then trusting a URL inside it, which is exactly the arbitrary-URL boundary
  this product refuses. `/favicon.ico` is a convention, not a promise; a miss is
  free.
- **Persist favicons to an app-private cache.** It would survive process death
  and save one loopback request per app, at the cost of storing bytes from an
  endpoint that may have changed, plus eviction, corruption, and lifecycle rules
  for a decorative image.
- **Reuse the `WebView`'s favicon or cache.** The launcher tile must be honest
  before the app is ever opened, and the WebView is created lazily per surface.
- **Probe reachability first, then fetch.** A separate probe doubles the requests
  and proves nothing the fetch itself does not: a favicon that arrives *is* the
  reachability proof.
- **Retry the fetch on a timer or on every launcher render.** That is the probe
  loop ADR-0014 rejected. The second attempt is triggered by a real event — the
  app's own surface proving the endpoint answers.
- **Add an image-loading library.** The JDK and the platform decoder already do
  this in ~200 lines, and a favicon is not worth a dependency.
- **Show "no favicon" or a warning on the tile.** A false error: the user chose
  no image, and the monogram is a complete answer.

## Consequences

- A registered app with no user image shows its own favicon as soon as its server
  is running, with no user action and no extra permission.
- The launcher's first attempt usually fails on a device where the app is not
  running; the favicon then appears after the user opens the app once and returns
  to the launcher. A favicon is never fetched again in that process, and an app
  that is never opened keeps its monogram — deliberately, because nothing is
  known to be listening.
- There is no cross-process cache: after the app process dies, favicons are
  fetched again (once per app) on the next launcher display.
- The fetch decision is unit-tested against a real loopback server
  (`WebAppFaviconFetcherTest`), but a JVM unit test cannot construct a real
  `Bitmap`, so the platform decode itself sits behind
  `WebAppFaviconFetcher.Decoder` and is verified on the emulator rather than in
  `./gradlew test`.
- `WebAppSurfaceView` gained one listener (`onEndpointReachable`) so the launcher
  can ask at the moment reachability is proven. It reports a fact the surface
  already knows; it does not add a control or a state.
- The favicon is decorative and stays out of the accessibility tree: the tile
  still reads as one node whose description is the app's name and its gestures.
