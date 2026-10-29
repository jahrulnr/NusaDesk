# ADR-0083: WebView GET downloads into guest /root/Downloads

- Status: Accepted
- Date: 2026-10-29
- Extended by: ADR-0084 for renderer-created Blob/data exports.

## Context

User-added local web apps could upload files (ADR-0059) but attachment/download
links silently did nothing because their WebViews had no DownloadListener.
The requested destination is the Linux user's `~/Downloads`, which is
`/root/Downloads`. Android DownloadManager cannot target that app-private rootfs.

## Decision

Attach a DownloadListener to every root and child WebView created by the web-app
surface. The existing exact-origin policy still routes URLs: owned HTTP GETs
are fetched by a per-surface serial worker; external URLs go to the Android
browser; blocked URLs use the existing failure callback. Recheck every redirect
before opening a connection, with at most five redirects. `about:blank` remains
valid for WebView initialization but is not a download URL.

Capture CookieManager cookies for the requested URL and the WebView User-Agent
at the tap (again on manual Retry). Forward them only to owned connections.
If the owned server returns 401, retry once with this registered app's saved
Basic credential, if present; downloading does not introduce a second sign-in
prompt or use another app's credentials. Disable automatic redirects so cookies
and Authorization cannot follow a redirect outside the owned origin.

Write directly into the active rootfs's existing `root/Downloads`, creating
only that final directory if absent. No new bind is needed: native Linux
downloads already use this path, existing data stays visible, and the files
retain normal guest backup semantics. Fixed directory components must be real
directories, as with the existing guest writers; a guest symlink is reported
as failure instead of redirecting the Android writer outside the rootfs.

Use Android URLUtil for the suggested filename from Content-Disposition/URL,
then normalize it to one bounded UTF-8 filename. Stream to a hidden temporary
file, check the advertised length when present, then publish the completed
file with a same-directory `Files.move`, without replacement options. An
existing destination makes the move fail; try `name (1).ext`, and so on,
without replacing guest files. Do not request ATOMIC_MOVE: that option permits
replacement of an existing destination. A hardlink publication attempt passed
JVM tests but failed on the API 29 physical device, so it is not used. Remove
the temporary name after publication or an ordinary failure/cancel.

The application port carries immutable queued/downloading/saved/failed states.
The infrastructure adapter owns network/file I/O; the native presentation banner
shows progress (or bytes for unknown length), destination, and failure with Retry
and Dismiss. Ordinary download failure keeps the web page intact. There is no
new JavaScript interface, permission, dependency, or foreground service.

## Lifecycle and limits

One worker serializes downloads for one registered app. Switching to the
launcher retains the surface and its work. Rebinding to an edited definition,
removing the app, or destroying its Activity/surface closes the adapter,
interrupts queued work and disconnects active HTTP I/O. Late callbacks cannot
revive a released banner. Connect/read timeouts are 15/30 seconds. There is no
system download notification, background survival guarantee, resume or automatic
network retry. Abrupt process death can leave a hidden partial file; it is never
published under a final filename.

Only GETs exposed by Android's DownloadListener are supported. A download can
re-fetch the URL after WebView's initial request; one-shot URLs are not
guaranteed. POST bodies are not replayed. ADR-0084 adds a separate renderer
transfer for Blob/data exports while their ordinary page navigation stays
outside the navigation policy. External downloads belong to the
system browser and use its destination. This does not change the capability
bridge's explicit `download.request`, which targets Android public Download/.

## Verification

Focused JVM tests exercise filename/path handling, exact bytes, cookies/UA,
saved Basic authentication, owned/external/blocked redirects, HTTP failures,
truncated responses, collision preservation, cancellation/cleanup and symlink
destinations. Robolectric tests execute listener callbacks, progress/result,
failure/retry, fresh cookie capture, release behavior and root/child wiring.
Physical-device results and the complete local gate are recorded in
`docs/test-plan.md`; other API/OEM/page-size configurations require their own
runtime evidence.
