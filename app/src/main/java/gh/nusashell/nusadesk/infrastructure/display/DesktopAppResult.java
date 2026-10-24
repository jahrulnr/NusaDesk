package gh.nusashell.nusadesk.infrastructure.display;

/** Guest lifecycle markers distinguish a completed app from startup or transport failure. */
final class DesktopAppResult {
    static final String CLOSED = "__NUSADESK_GUI_CLOSED__";
    static final String FAILED = "__NUSADESK_GUI_FAILED__";
    private DesktopAppResult() { }
    /**
     * The caller latches the marker flags while appending, because the bounded
     * output buffer trims its head and can lose a marker before close.
     */
    static boolean closed(boolean sawClosed, boolean sawFailed, boolean transportFailed) {
        return !transportFailed && sawClosed && !sawFailed;
    }
    static String failure(String output, String reason) {
        int marker = output.indexOf(FAILED);
        if (marker >= 0) return output.substring(marker + FAILED.length()).split("[\r\n]", 2)[0].trim();
        return (reason + "\n" + output.replaceAll("__NUSADESK_GUI_(READY|CLOSED|EXIT)__[^\r\n]*", "")).trim();
    }
}
