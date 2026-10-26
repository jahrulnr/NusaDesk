# Sourced by the host-owned desktop launch channel, not installed globally.
export LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe
unset VTEST_SOCKET_NAME
if [ -S /tmp/.virgl ]; then
    if timeout 8 env EGL_PLATFORM=surfaceless GALLIUM_DRIVER=virpipe VTEST_SOCKET_NAME=/tmp/.virgl \
        python3 /opt/lw-desktop/graphics_probe.py > /tmp/nusadesk-graphics.log 2>&1 \
        && grep -q '"verified": true' /tmp/nusadesk-graphics.log; then
        export GALLIUM_DRIVER=virpipe VTEST_SOCKET_NAME=/tmp/.virgl
    else
        printf '\nNusaDesk: virgl probe failed; using llvmpipe.\n' >> /tmp/nusadesk-graphics.log
    fi
fi
