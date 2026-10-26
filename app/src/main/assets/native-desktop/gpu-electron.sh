#!/bin/sh
# Explicit compatibility launcher for Electron apps on the GLES virgl path.
# Version overrides expose a subset, not full GL 3.3 / ES 3.0 conformance.
set -eu
unset MESA_GL_VERSION_OVERRIDE MESA_GLES_VERSION_OVERRIDE MESA_GLSL_VERSION_OVERRIDE
# shellcheck source=/dev/null
. /opt/lw-desktop/graphics-env.sh
if [ "$GALLIUM_DRIVER" = virpipe ]; then
    export MESA_GL_VERSION_OVERRIDE=3.3COMPAT MESA_GLES_VERSION_OVERRIDE=3.0 MESA_GLSL_VERSION_OVERRIDE=330
    exec "$@" --use-gl=angle --use-angle=gles --ignore-gpu-blocklist
fi
exec "$@"
