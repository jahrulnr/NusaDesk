#!/bin/sh
# dbus-run-session supplies its normal lifetime/print-address arguments.
set -eu
: "${NUSADESK_DISPLAY_BUS:?display bus address required}"
exec /usr/bin/dbus-daemon "$@" --address="$NUSADESK_DISPLAY_BUS"
