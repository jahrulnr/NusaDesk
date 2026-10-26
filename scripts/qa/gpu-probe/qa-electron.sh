#!/bin/bash
# QA only: (re)launch the NusaShell Electron window with a temporary loopback
# DevTools port so the host can drive and measure scroll input.
# Usage: qa-electron.sh <gpu|sw> [port]
set -eu
MODE=${1:?mode required: gpu or sw}
PORT=${2:-19224}
APP=/root/.local/bin/nusashell-desktop

export DISPLAY=:1 GDK_BACKEND=x11

# Stop the previous QA window only; the X11 display and GPU renderer stay up.
# The desktop entry's own supervisor exits with the window it started.
pkill -f 'nusashell-desktop' 2>/dev/null || :
for _ in $(seq 1 20); do
    pgrep -f 'nusashell-desktop' >/dev/null 2>&1 || break
    sleep 0.5
done
pkill -9 -f 'nusashell-desktop' 2>/dev/null || :
sleep 1

FLAGS=(--remote-debugging-address=127.0.0.1 "--remote-debugging-port=$PORT")

if [ "$MODE" = gpu ]; then
    # The same explicit wrapper the shipped desktop entry uses.
    nohup /opt/lw-desktop/gpu-electron.sh "$APP" "${FLAGS[@]}" >/tmp/nusadesk-qa-electron.log 2>&1 &
else
    # Software comparison arm: identical app and flags, llvmpipe driver.
    export LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe
    unset VTEST_SOCKET_NAME MESA_GL_VERSION_OVERRIDE MESA_GLES_VERSION_OVERRIDE MESA_GLSL_VERSION_OVERRIDE
    nohup "$APP" --no-sandbox --use-gl=angle --use-angle=gles --ignore-gpu-blocklist \
        "${FLAGS[@]}" >/tmp/nusadesk-qa-electron.log 2>&1 &
fi

echo "launched mode=$MODE"
for _ in $(seq 1 40); do
    if (exec 3<>/dev/tcp/127.0.0.1/"$PORT") 2>/dev/null; then
        echo "devtools ready on $PORT"
        exit 0
    fi
    sleep 0.5
done
echo "devtools did not open on $PORT" >&2
tail -n 20 /tmp/nusadesk-qa-electron.log 2>&1 || :
exit 1
