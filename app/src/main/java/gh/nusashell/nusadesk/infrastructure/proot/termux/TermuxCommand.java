package gh.nusashell.nusadesk.infrastructure.proot.termux;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * One generated Termux-compat guest command.
 *
 * <p>A command is the guest-visible half of a fixed bridge method: the
 * {@link #name()} is the Termux command name installed into
 * {@code /usr/local/bin}, the {@link #body()} is the Python 3 source below the
 * generated header (flag parsing plus the mapping onto the shared
 * {@code termux_compat} runtime), and {@link #docLines()} is this command's
 * block in the generated {@code docs/termux-compat.md}.</p>
 *
 * <p>The body is deliberately thin: it may use {@code tc.*} helpers from the
 * shared runtime module and its own stdlib imports, but it never re-implements
 * the transport and never carries the {@code if __name__ == '__main__'} entry
 * point — {@code GuestTermuxCompatWriter} generates that so every command
 * exits through the same {@code tc.run} contract.</p>
 */
public final class TermuxCommand {

    /**
     * Installed command names stay inside Termux's own shape: lowercase,
     * {@code termux-} prefixed, digits and inner hyphens only. This is also
     * the filename written into the guest bin directory, so anything else
     * (uppercase, separators, dot segments) is rejected here rather than at
     * the filesystem.
     */
    private static final Pattern NAME_PATTERN =
            Pattern.compile("termux-[a-z0-9]([a-z0-9-]*[a-z0-9])?");

    private final String name;
    private final String body;
    private final List<String> docLines;

    /**
     * @param name Termux command name; must match {@code termux-<word>} in
     *             lowercase safe characters
     * @param body Python source for this command, after the generated import
     *             header; must not contain an entry point (the writer adds the
     *             uniform {@code tc.run} trailer)
     * @param docLines lines documenting this command in the generated
     *                 compatibility doc: the usage bullet and its emitted
     *                 field subset
     */
    public TermuxCommand(String name, String body, List<String> docLines) {
        if (name == null || !NAME_PATTERN.matcher(name).matches()) {
            throw new IllegalArgumentException(
                    "unsafe Termux command name: " + name);
        }
        if (body == null || body.trim().isEmpty() || body.indexOf('\r') >= 0) {
            throw new IllegalArgumentException(
                    "command body must be non-empty LF text: " + name);
        }
        if (body.contains("__main__") || body.contains("sys.exit(")) {
            throw new IllegalArgumentException(
                    "command body must not carry an entry point; the writer "
                            + "generates the tc.run trailer: " + name);
        }
        List<String> lines = new ArrayList<>();
        for (String line : docLines == null ? Collections.<String>emptyList() : docLines) {
            if (line == null || line.indexOf('\n') >= 0 || line.indexOf('\r') >= 0) {
                throw new IllegalArgumentException(
                        "doc lines must be single LF-free lines: " + name);
            }
            lines.add(line);
        }
        this.name = name;
        this.body = body;
        this.docLines = Collections.unmodifiableList(lines);
    }

    /** Termux command name; also the installed file name in the guest bin dir. */
    public String name() {
        return name;
    }

    /** Python source below the generated header, LF-terminated or bare lines. */
    public String body() {
        return body;
    }

    /** This command's documentation lines, in output order. */
    public List<String> docLines() {
        return docLines;
    }
}
