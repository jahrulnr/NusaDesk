package gh.nusashell.nusadesk.infrastructure.display;

/** One window manager per display; each GUI app has a separate supervised channel. */
final class GuestDesktopWorkload {
    static final String READY_MARKER = "__NUSADESK_GUI_READY__";
    static final String WM_READY_MARKER = "__NUSADESK_WM_READY__";
    private static final String ENV = "set -eu\nexport DISPLAY="
            + gh.nusashell.nusadesk.application.display.NativeDesktopPort.X11_DISPLAY
            + " GDK_BACKEND=x11 LIBGL_ALWAYS_SOFTWARE=1\n";
    static final String WM_SCRIPT = ENV + "xdpyinfo >/dev/null\n"
            + "openbox --config-file /tmp/nusadesk-openbox.xml >/tmp/nusadesk-wm.log 2>&1 &\nwm=$!\n"
            + "cleanup() { trap - EXIT HUP INT TERM; kill \"$wm\" 2>/dev/null || :; wait 2>/dev/null || :; }\n"
            + "trap cleanup EXIT\ntrap 'exit 0' HUP INT TERM\ntries=0\n"
            + "while kill -0 \"$wm\" 2>/dev/null; do\n"
            + " if xprop -root _NET_SUPPORTING_WM_CHECK 2>/dev/null | grep -q 'window id # 0x'; then "
            + "printf '__NUSADESK_WM_READY__\\n'; wait \"$wm\"; exit $?; fi\n"
            + " tries=$((tries + 1)); [ \"$tries\" -lt 50 ] || exit 70\n sleep 0.1\ndone\nwait \"$wm\"\n";
    private GuestDesktopWorkload() { }
    static String windowManagerCommand() { return shell(WM_SCRIPT); }
    static String command(String desktopId, String busAddress) {
        if (desktopId == null || desktopId.isEmpty()) throw new IllegalArgumentException("desktop ID required");
        if (busAddress == null || !busAddress.matches("unix:abstract=nusadesk-display-[0-9a-f-]{36}")) throw new IllegalArgumentException("owned display bus address required");
        return shell(ENV + "export NUSADESK_DISPLAY_BUS=" + quote(busAddress) + "\n"
                + "exec dbus-run-session --dbus-daemon=/opt/lw-desktop/session-bus.sh -- python3 " + GuestDesktopCatalog.SCRIPT + " launch " + quote(desktopId));
    }
    private static String shell(String value) { return "/bin/sh -c " + quote(value); }
    private static String quote(String value) { return "'" + value.replace("'", "'\\''") + "'"; }
}
