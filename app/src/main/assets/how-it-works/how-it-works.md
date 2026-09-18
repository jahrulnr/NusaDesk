# How NusaDesk works

NusaDesk is an Android host for one curated Linux session. The host owns lifecycle, storage, readiness, and the local app surface.

```mermaid
flowchart TD
    A[Android host] --> B[Verified Ubuntu Base]
    B --> C[Android-owned session]
    C --> D[Guest SSH loopback]
    D --> E[App surfaces]
```

## Session lifecycle

Linux starts from a visible app launch. The foreground service supervises the session while you move between the launcher, terminal, logs, and local web apps.

```mermaid
flowchart TD
    A[Open NusaDesk] --> B{Runtime ready?}
    B -->|No| C[Install or recover]
    B -->|Yes| D[Start or reuse session]
    C --> D
    D --> E[Health check]
    E -->|Ready| F[Expose loopback surface]
    E -->|Failed| G[Show honest failure]
```

## What stays inside the host

- The rootfs is verified before activation and kept in app-private storage.
- The guest endpoint binds to loopback only.
- Android owns process supervision and the foreground notification.
- A running process is not treated as ready until its health check succeeds.

## What the workspace means

The optional workspace is a user-selected host folder mounted at `~/nusadesk` inside Linux. It is a data mount, not a general host filesystem API.

## Logs and troubleshooting

The Logs surface lists the guest files that matter for diagnosis. Open one to follow it live with the same terminal renderer used by the shell. The System surface shows the current state, session, workspace, endpoint, and runtime profile.
