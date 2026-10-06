# Showcase

Preview media for the README and the small demo app used to record it. All
footage comes from a physical Samsung S10e (SM-G970F, Android 12/API 31, 1080p),
with in-app tap feedback and notifications silenced; nothing is mocked or
narrated, and no clip is speed-ramped.

## Artifacts

| File | Length | Shows |
| --- | --- | --- |
| `clip-1-install.mp4` | 28 s | Fresh install: the setup card, the pinned Ubuntu Base download, verification, extraction, the OpenSSH and guest-services installs, and the launcher unlocking with its tiles |
| `clip-2-terminal.mp4` | 20 s | `apt update` running in the guest (real Get lines, `Fetched 37.0 MB in 9 s`), then `uname -a` and `/etc/os-release` with the `NUSADESK_*` fields |
| `clip-3-web-app.mp4` | 45 s | Adding a web app from the launcher: name, port, Save, the new tile, the dashboard opening, and the **Stress test** button driving the load sparkline up |
| `clip-4-terminal-app.mp4` | 33 s | Adding a terminal-command app: the Type selector, the `top` command, the new tile, and `top` running in its own PTY |
| `add-apps.mp4` | 78 s | `clip-3` and `clip-4` joined: both "add app" stories in one file |
| `preview.gif` | 17 s | The README preview: install progress, `apt update`, the dashboard stress spike, `top` |
| `nusadesk-live.py` | — | The showcase web app (clip 3). Single file, no dependencies: `python3 nusadesk-live.py` inside the guest serves `http://127.0.0.1:8080/` |

The five clips are H.264 (`-crf 24`, no audio) and total about 7 MB. These local
files are the sources: in the README the three grouped clips are embedded as
inline players from the uploads attached to
[issue #3](https://github.com/jahrulnr/NusaDesk/issues/3), because a repository
file cannot be played inline — GitHub only turns an uploaded attachment URL into
a player when that URL sits alone in its own paragraph (verified with the
markdown render API: three `<video controls>` elements for the block). The GIF
stays in the repository because it autoplays without any such help.

## The showcase web app

`nusadesk-live.py` reads real guest state and renders it for a phone screen:
`/proc` for per-process CPU, memory and the process table, `/etc/os-release`
(including `NUSADESK_CONTRIBUTOR` / `NUSADESK_SOURCE`), `statvfs` for storage,
the session's own `systemctl` bridge for the running services, `/run/nusadesk/android-bridge.env`
for the true session uptime, and `~/nusadesk` for the bound workspace. The
**Stress test** button spawns four bounded CPU workers for six seconds, which is
what makes the load sparkline spike on camera (the recorded clip peaks at 54.7% busy with
the `python3` workers at ~100%). The page is laid out to fit one portrait screen,
and every card degrades to "unavailable" instead of inventing a number when a
source cannot be read.

Two honesty notes that shaped the numbers: PRoot does not virtualize `/proc`, so
`/proc/uptime` is the *device's* uptime and the session age has to come from the
bridge file instead; and `/proc/loadavg` plus `/proc/stat` can be hidden from an
app, so CPU busy is measured from the sampled processes and the card says so.

## Re-recording

```sh
# clean status bar + visible taps
adb -s <serial> shell settings put system show_touches 1
adb -s <serial> shell svc power stayon true
adb -s <serial> shell cmd notification set_dnd on
adb -s <serial> shell settings put global sysui_demo_allowed 1
adb -s <serial> shell am broadcast -a com.android.systemui.demo -e command enter \
  -e clock 0930 -e battery level 100 -e network wifi -e wifi level 4 -e notifications 0

# capture one clip
adb -s <serial> shell screenrecord --bit-rate 12000000 --time-limit 180 /sdcard/clip.mp4
# ... drive the phone, then stop
adb -s <serial> shell pkill -INT screenrecord
adb -s <serial> pull /sdcard/clip.mp4 .
```

`screenrecord` writes variable-frame-rate footage, so a `-ss`/`-t` cut must be
verified by pulling frames back out (`ffmpeg -ss <t> -i clip.mp4 -frames:v 1
probe.png`); the segment offsets used here were chosen that way.

Two practical notes from the recording session: a stray `input keyevent` sent
while the launcher is in front will open whatever tile is focused, and `BACK`
first dismisses the IME before it leaves a surface. Verify the foreground with a
UI dump before typing into the terminal.
