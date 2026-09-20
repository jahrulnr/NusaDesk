package gh.nusashell.nusadesk.application.backup;

/**
 * Bounded, typed vocabulary for why a backup export or import failed.
 *
 * <p>The wire {@link #getCode() code} is the stable machine-readable name used in
 * tests and logs; the human-readable sentence travels separately as the
 * result detail so the typed set stays small.</p>
 */
public enum BackupFailure {

    /** The archive's first tar entry is not a readable {@code manifest.json}. */
    MANIFEST_MISSING("manifest-missing"),

    /** The manifest is malformed, or its format version is not supported. */
    UNSUPPORTED_FORMAT("unsupported-format"),

    /** The manifest names a runtime that is not in the curated catalog. */
    RUNTIME_MISMATCH("runtime-mismatch"),

    /** A HOME/CUSTOM restore needs an installed active runtime to merge into. */
    RUNTIME_REQUIRED("runtime-required"),

    /** The payload failed the archive safety rules or rootfs validation. */
    UNSAFE_ARCHIVE("unsafe-archive"),

    /** The device has too little free space to finish the operation. */
    STORAGE_FULL("storage-full"),

    /** Any other I/O problem while reading or writing the archive. */
    IO_FAILURE("io-failure"),

    /** Another install or backup/restore operation is already running. */
    BUSY("busy");

    private final String code;

    BackupFailure(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
