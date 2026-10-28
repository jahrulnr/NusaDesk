# NusaDesk Linux

NusaDesk provides:
- Ubuntu Base ARM64 Linux guest through PRoot
- Local guest terminal at 127.0.0.1:22022
- Native X11 desktop + Mesa/virgl GPU path: [GUI installation guide](docs/desktop.md)
- Product-owned systemctl3 bridge (system and `--user` units)
- Bounded `udocker compose` support
- Best-effort Android battery bridge at `/sys/class/power_supply/battery`
- Optional `termux-*` commands and USB/`adb` tools when enabled
- Optional Android workspace at `~/nusadesk`
- Seeded agent bundle at `~/.agents` (AGENTS.md + starter skills)
- Android-owned lifecycle, DNS doctor, and session-temporary `/tmp`

## Documentation

Guest-facing documentation is generated and owned by the app:

- [Guest documentation index](docs/README.md)
- [Guest environment, session, and services](docs/environment.md)
- [Android capability bridge](docs/bridge.md)
- [Camera and microphone (live + capture)](docs/media.md)
- [Battery, sensors, and location](docs/battery-sensors-location.md)
- [Messaging and telephony](docs/messaging-telephony.md)
- [Calendar events](docs/calendar.md)
- Termux command compatibility (`docs/termux-compat.md`) - optional toolkit

> This file is managed by the NusaDesk Android app. It is writable in
> the guest and may be regenerated after an app update.
> App version: %APP_VERSION%

This is a PRoot guest, not a VM or full Docker environment.

