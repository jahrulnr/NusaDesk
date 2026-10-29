# ADR-0084: Renderer-created Blob and data exports

- Status: Accepted
- Date: 2026-10-29

## Context and decision

The user explicitly requested Blob/data exports after ADR-0083: local PHP/Node
dashboards frequently generate JSON/CSV/PDF in JavaScript, with no HTTP URL the
Android downloader can fetch. Reuse `/root/Downloads`, filename collisions,
partial-file publication and the native status banner. Import remains the
existing file picker.

Pin AndroidX WebKit 1.15.0 (Apache-2.0, official stable release 2025-12-17).
It supplies document-start injection and origin-scoped WebMessageListener;
vetted upstream APIs replace a broad addJavascriptInterface or asynchronous
page-finished injection that loses immediately revoked object URLs. The exact
registered loopback origin is the only injection rule, for root/child WebViews
and same-origin frames. Native callbacks independently check the source origin.
General blob/data page navigation remains blocked; the exception transfers file
bytes into one fixed guest Downloads directory, never opens a page or accepts
an arbitrary host path/command.

`binary-downloads.js` observes ordinary anchor clicks and preserves the native
click event when intercepting detached anchors' `.click()`. It also handles
`window.open` binary URLs and host-observed binary download navigation. Track
Blob objects while their object URLs are live; capture the Blob reference
synchronously before a caller's immediate revoke. Mirror revoke to release the
map reference. A transfer holds its source only while needed. Preserve the
anchor's filename, or a File object's name, otherwise use the SDK MIME guess.
Decode data URLs using the browser's fetch implementation, including percent
encoding and base64; do not implement a second URI decoder.

Slice Blobs into 32 KiB chunks and encode only each chunk as base64. The
renderer awaits a native acknowledgement after file creation, every write and
completion. This bounds native payload memory and applies disk backpressure;
there is no complete-file base64 string in native memory. A data URL already
contains its entire payload in the renderer; decoding may allocate a Blob there.
Use strings for compatibility rather than require optional ArrayBuffer support.

`WebAppDownloadPort.BinaryTransfer` exposes only append/finish/abort. The existing
serial infrastructure worker owns file I/O. `GuestDownloadFile` is shared with
HTTP downloads and refuses incomplete or excessive byte counts before publishing
a name. Per-document transfers are cancelled on navigation/tab close/surface
release and after 60 seconds without an acknowledged step; partial files are
removed on ordinary failures/cancellation. Abrupt process death retains the
ADR-0083 hidden-partial limitation.

Binary failures remain on the existing page with a failure banner. Run Export
again in the source app: HTTP Retry cannot recreate a revoked Blob. Direct POST
attachment responses are still not replayed; JavaScript POST fetches converted
to Blob/data exports use this supported renderer path.

## Compatibility and verification

Runtime-check DOCUMENT_START_SCRIPT and WEB_MESSAGE_LISTENER. Older providers
keep HTTP support; a binary URL arriving at the host receives an explicit
Android System WebView update instruction. Do not fall back to an unrestricted
JavaScript interface or claim early export compatibility on those providers.
Opaque sandbox/cross-origin frames do not receive the channel. A Blob already
revoked before the export starts cannot be recovered. MediaSource streams and
arbitrary browser filesystem/download APIs are outside this slice.

Node tests execute the shipped script for detached/attached anchors, immediate
revoke, data encodings, Unicode/binary/empty/large files, backpressure,
window.open and failure. JVM tests execute native transfer writes, size/length
failures, origin checks, navigation teardown and shared HTTP regression cases.
Physical QA uses `scripts/qa/webapp-binary-download-fixture.py`; device evidence
is recorded in `docs/test-plan.md` rather than inferred from unit tests.

Official sources: [WebKit release](https://developer.android.com/jetpack/androidx/releases/webkit#1.15.0),
[WebViewCompat messaging and document-start contracts](https://developer.android.com/reference/androidx/webkit/WebViewCompat).
