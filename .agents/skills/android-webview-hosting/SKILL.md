---
name: android-webview-hosting
description: Use when implementing the Android WebView boundary for an owned local web app; handle localhost origin allowlists, readiness, navigation, external links, reconnects, JavaScript bridges, and failure UI securely.
---

# Android WebView hosting

Use this skill only when a runtime server and its endpoint contract already exist.

## Boundary

The host produces one exact origin after readiness:

```text
http://127.0.0.1:<owned-port>/
```

The WebView is a presentation client. It must not start the Linux process, choose arbitrary URLs, or make auth decisions that belong to the host/server.

## Implementation rules

1. Load the page only after a health/readiness check succeeds; a live PID is insufficient.
2. Allow navigation only to the current owned origin. A different port is a different origin and must be rejected unless explicitly part of the contract.
3. Send external HTTP(S) links to the system browser. Do not silently turn an external URL into an in-app navigation.
4. Enable JavaScript only because the target UI requires it. Do not expose a broad `addJavascriptInterface`.
5. If a native bridge is required, expose the smallest typed API, check the calling origin, avoid secrets in return values, and test malformed/untrusted input.
6. Keep cleartext permission scoped to loopback; never enable cleartext globally for convenience.
7. Handle loading, timeout, process stopped, process crashed, retry, rotation, Activity recreation, WebSocket close, and SSE reconnect states explicitly.
8. Do not use a fixed port assumption when the host supports ephemeral ports.
9. Treat WebView storage/cookies/cache as app data; clear or partition it when switching target app/profile.
10. Support system font scaling, small screens, tablets, orientation changes, and accessible labels.

## Security checklist

- exact loopback origin allowlist;
- no file URL access unless a later design proves it necessary;
- no universal file access;
- no remote content mixed into the trusted runtime origin;
- target app auth token is not placed in a URL or log;
- crash/error pages do not echo secrets or arbitrary server output.

## Verification

Test HTTP, WebSocket, and SSE against a deterministic dummy server before a real target. Include invalid navigation, different loopback port, server restart, malformed bridge input, and process death. Run the UI on a real Android device/emulator and inspect screenshots/interaction; unit tests alone are not sufficient.
