package gh.nusashell.nusadesk.domain.webapp;

import java.nio.charset.StandardCharsets;

/** A download is one bounded filename, never a guest path supplied by a server. */
public final class DownloadFileName {
    private DownloadFileName() { }

    public static String safe(String suggested) {
        if (suggested == null) return "download";
        StringBuilder result = new StringBuilder();
        int bytes = 0;
        for (int offset = 0; offset < suggested.length();) {
            int point = suggested.codePointAt(offset);
            offset += Character.charCount(point);
            String part = point == '/' || point == '\\' || Character.isISOControl(point)
                    ? "_" : new String(Character.toChars(point));
            int length = part.getBytes(StandardCharsets.UTF_8).length;
            if (bytes + length > 200) break;
            result.append(part);
            bytes += length;
        }
        String name = result.toString().trim();
        return name.isEmpty() || name.equals(".") || name.equals("..") ? "download" : name;
    }

    /** Leaves the extension recognizable when an existing file requires a suffix. */
    public static String numbered(String safeName, int number) {
        if (number == 0) return safeName;
        int dot = safeName.lastIndexOf('.');
        if (dot <= 0) return safeName + " (" + number + ")";
        return safeName.substring(0, dot) + " (" + number + ")" + safeName.substring(dot);
    }
}
