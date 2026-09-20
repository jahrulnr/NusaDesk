package gh.nusashell.nusadesk.domain.update;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An app release version parsed from a GitHub release tag or the installed APK
 * {@code versionName} (ADR-0038).
 *
 * <p>The release channel tags every release {@code vX.Y.Z} where the numeric
 * part equals {@code versionName}, so one comparator serves both sides of the
 * check. Parsing is deliberately forgiving on shape — a leading {@code v} (or
 * {@code V}) is stripped and a missing minor or patch component counts as 0 —
 * and deliberately strict on content: anything that is not a numeric triple
 * plus an optional {@code -}/{@code +} suffix is rejected. A non-empty suffix
 * marks the version as a prerelease, which is never newer than the same
 * numeric triple.</p>
 *
 * <p>Unparsable input yields {@code null} rather than an exception, because a
 * malformed remote tag or a synthetic local {@code versionName} must simply
 * never report an update.</p>
 */
public final class ReleaseVersion {

    private static final Pattern PATTERN =
            Pattern.compile("^[vV]?(\\d+)(?:\\.(\\d+))?(?:\\.(\\d+))?([-+].+)?$");

    private final String raw;
    private final int major;
    private final int minor;
    private final int patch;
    private final String suffix;

    private ReleaseVersion(String raw, int major, int minor, int patch, String suffix) {
        this.raw = raw;
        this.major = major;
        this.minor = minor;
        this.patch = patch;
        this.suffix = suffix;
    }

    /**
     * Parses a tag or version name such as {@code "v0.3.0"}, {@code "0.3"}, or
     * {@code "0.3.0-rc1"}.
     *
     * @param raw candidate tag/version string; surrounding whitespace is ignored
     * @return the parsed version, or {@code null} when the input is missing or
     *         does not match the numeric-triple shape
     */
    public static ReleaseVersion parseOrNull(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        Matcher matcher = PATTERN.matcher(trimmed);
        if (!matcher.matches()) {
            return null;
        }
        try {
            return new ReleaseVersion(
                    trimmed,
                    Integer.parseInt(matcher.group(1)),
                    component(matcher.group(2)),
                    component(matcher.group(3)),
                    matcher.group(4) == null ? "" : matcher.group(4));
        } catch (NumberFormatException e) {
            // A component too large for int cannot be ordered; reject it.
            return null;
        }
    }

    private static int component(String group) {
        return group == null ? 0 : Integer.parseInt(group);
    }

    /**
     * Numeric-triple ordering: any higher component wins, and a prerelease is
     * never newer than the same numeric release (a release IS newer than its
     * own prereleases). Comparing against {@code null} returns {@code false},
     * so an unparsable "other side" can never produce an update prompt.
     */
    public boolean isNewerThan(ReleaseVersion other) {
        if (other == null) {
            return false;
        }
        if (major != other.major) {
            return major > other.major;
        }
        if (minor != other.minor) {
            return minor > other.minor;
        }
        if (patch != other.patch) {
            return patch > other.patch;
        }
        return !isPrerelease() && other.isPrerelease();
    }

    /** The major component. */
    public int getMajor() {
        return major;
    }

    /** The minor component; 0 when the source had none. */
    public int getMinor() {
        return minor;
    }

    /** The patch component; 0 when the source had none. */
    public int getPatch() {
        return patch;
    }

    /** True when the version carried a {@code -}/{@code +} suffix. */
    public boolean isPrerelease() {
        return !suffix.isEmpty();
    }

    /** The original string this version was parsed from, trimmed. */
    public String getRaw() {
        return raw;
    }

    /** The canonical {@code "major.minor.patch"} form. */
    @Override
    public String toString() {
        return major + "." + minor + "." + patch;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ReleaseVersion)) {
            return false;
        }
        ReleaseVersion that = (ReleaseVersion) other;
        return major == that.major && minor == that.minor && patch == that.patch
                && suffix.equals(that.suffix);
    }

    @Override
    public int hashCode() {
        return Objects.hash(major, minor, patch, suffix);
    }
}
