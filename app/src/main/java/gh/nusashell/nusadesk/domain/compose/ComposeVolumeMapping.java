package gh.nusashell.nusadesk.domain.compose;

import java.util.Objects;

/**
 * One bind/volume mount of a Compose service: a source made visible inside the
 * container at an absolute guest-side target.
 *
 * <p>The source is stored verbatim — it may be a host path, a guest path, or a
 * named volume; which of those the adapter supports and how it resolves them
 * is an adapter decision, not a domain one. The target must be an absolute
 * guest path without {@code ..} segments or NUL, and is likewise stored
 * verbatim: this object never canonicalises a path it did not create.</p>
 */
public final class ComposeVolumeMapping {
    private final String source;
    private final String target;
    private final boolean readOnly;

    /**
     * @param source   mount source, stored verbatim; must be non-blank and
     *                 contain no NUL
     * @param target   absolute guest path inside the container
     * @param readOnly whether the mount is read-only ({@code :ro})
     * @throws IllegalArgumentException when the source is blank/NUL-bearing or
     *                                  the target is not a safe absolute guest
     *                                  path
     */
    public ComposeVolumeMapping(String source, String target, boolean readOnly) {
        this.source = ComposeValidation.requireNonBlank(source, "source");
        ComposeValidation.requireNoNul(this.source, "source");
        this.target = ComposeValidation.requireAbsoluteGuestPath(target, "target");
        this.readOnly = readOnly;
    }

    /** Mount source, exactly as declared. */
    public String getSource() {
        return source;
    }

    /** Absolute guest path the source is mounted at. */
    public String getTarget() {
        return target;
    }

    /** Whether the container may only read the mount. */
    public boolean isReadOnly() {
        return readOnly;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ComposeVolumeMapping)) {
            return false;
        }
        ComposeVolumeMapping that = (ComposeVolumeMapping) other;
        return readOnly == that.readOnly
                && source.equals(that.source)
                && target.equals(that.target);
    }

    @Override
    public int hashCode() {
        return Objects.hash(source, target, readOnly);
    }

    /** Renders the Compose short form: {@code source:target[:ro]}. */
    @Override
    public String toString() {
        return source + ":" + target + (readOnly ? ":ro" : "");
    }
}
