package gh.nusashell.nusadesk.domain.update;

/**
 * The newest released section of the repository changelog, reduced to the
 * plain text an About page can show (ADR-0066). Parsing follows the
 * {@code VersionConsistencyTest} contract: the latest section is the first
 * {@code ## [x.y.z] - date} header after {@code ## [Unreleased]}, and its
 * body runs until the next {@code ## [} header or the end of the file.
 *
 * <p>The section body is flattened to display text: {@code ### Group} lines
 * keep only their word, {@code - } bullets become {@code • }, and {@code **}
 * emphasis markers are dropped. The body is capped at
 * {@link #MAX_BODY_CHARS} on a line boundary; the card's "Read more" action
 * carries the rest.</p>
 */
public final class ChangelogSection {

    /** Display cap for the flattened body; the full text stays in the repo. */
    public static final int MAX_BODY_CHARS = 2000;

    private final String version;
    private final String date;
    private final String body;

    private ChangelogSection(String version, String date, String body) {
        this.version = version;
        this.date = date;
        this.body = body;
    }

    /**
     * @param markdown the full {@code CHANGELOG.md} text
     * @return the newest released section, or {@code null} when the document
     *         carries no {@code ## [x.y.z]} release header at all
     */
    public static ChangelogSection parseLatest(String markdown) {
        if (markdown == null) {
            return null;
        }
        int scanFrom = 0;
        int unreleased = markdown.indexOf("## [Unreleased]");
        if (unreleased >= 0) {
            scanFrom = unreleased + 1;
        }
        int header = indexOfReleaseHeader(markdown, scanFrom);
        if (header < 0) {
            return null;
        }
        int headerEnd = markdown.indexOf('\n', header);
        if (headerEnd < 0) {
            headerEnd = markdown.length();
        }
        String headerLine = markdown.substring(header, headerEnd).trim();
        String version = bracketed(headerLine);
        if (version != null) {
            version = version.trim();
        }
        if (version == null) {
            return null;
        }
        String rest = headerLine.substring(headerLine.indexOf(']') + 1).trim();
        if (rest.startsWith("-")) {
            rest = rest.substring(1).trim();
        }
        String date = rest;
        int next = indexOfReleaseHeader(markdown, headerEnd);
        String rawBody = markdown.substring(headerEnd,
                next < 0 ? markdown.length() : next);
        return new ChangelogSection(version, date, flatten(rawBody));
    }

    /** The release version of this section, e.g. {@code "0.14.3"}. */
    public String getVersion() {
        return version;
    }

    /** The release date after the bracket, or {@code ""} when the header has none. */
    public String getDate() {
        return date;
    }

    /** The flattened, capped section body ready for display. */
    public String getBody() {
        return body;
    }

    /** Next {@code \n## [} (or leading {@code ## [}) header from {@code from}. */
    private static int indexOfReleaseHeader(String text, int from) {
        int hit = text.indexOf("\n## [", Math.max(0, from - 1));
        if (hit < 0 && from == 0 && text.startsWith("## [")) {
            return 0;
        }
        return hit < 0 ? -1 : hit + 1;
    }

    /** The text inside the header's brackets, or null when the header is malformed. */
    private static String bracketed(String headerLine) {
        int open = headerLine.indexOf('[');
        int close = headerLine.indexOf(']');
        return open >= 0 && close > open + 1
                ? headerLine.substring(open + 1, close)
                : null;
    }

    /**
     * Flattens markdown-ish section text to card text: group headers keep
     * their word, bullets become {@code • }, emphasis markers drop, runs of
     * blank lines collapse to one, and the result is capped on a line
     * boundary with an ellipsis marker.
     */
    private static String flatten(String raw) {
        StringBuilder out = new StringBuilder();
        boolean lastBlank = true; // leading whitespace is skipped entirely
        for (String line : raw.split("\n", -1)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                lastBlank = true;
                continue;
            }
            String flat = trimmed;
            if (flat.startsWith("### ")) {
                flat = flat.substring(4).trim();
            } else if (flat.startsWith("- ")) {
                flat = "\u2022 " + flat.substring(2).trim();
            }
            flat = flat.replace("**", "");
            if (out.length() > 0) {
                // A blank source line keeps one blank line in the output; a
                // wrapped paragraph line joins on a single newline.
                out.append(lastBlank ? "\n\n" : "\n");
            }
            out.append(flat);
            lastBlank = false;
            if (out.length() > MAX_BODY_CHARS) {
                break;
            }
        }
        String body = out.toString().trim();
        if (body.length() <= MAX_BODY_CHARS) {
            return body;
        }
        int cut = body.lastIndexOf('\n', MAX_BODY_CHARS);
        if (cut < 0) {
            cut = MAX_BODY_CHARS;
        }
        return body.substring(0, cut).trim() + " \u2026";
    }
}
