package gh.nusashell.nusadesk.infrastructure.network;

import gh.nusashell.nusadesk.domain.network.ResolvConf;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

/**
 * Check-and-repair policy for the one host-owned guest resolver file.
 *
 * <p>This class deliberately knows only one product-owned resource: the
 * app-private source file that PRoot binds over the guest
 * {@code /etc/resolv.conf}. It does not inspect or rewrite the active rootfs,
 * package database, workspace, or any arbitrary guest path. Android-specific
 * network discovery is supplied through the {@link Supplier} seam so the
 * policy remains deterministic and JVM-testable.</p>
 *
 * <p>{@link #check()} is read-only. {@link #repair()} is idempotent and writes
 * only when the validated Android DNS content is available and the current
 * source file is missing or different. With no usable Android DNS it reports
 * {@link Status#NO_ACTIVE_DNS} and leaves any existing file untouched.</p>
 */
public final class GuestResolverDoctor {

    /** Result categories used by the listener and diagnostics. */
    public enum Status {
        /** Source exists and exactly matches the current validated DNS content. */
        OK,
        /** Android currently exposes no usable literal DNS server. */
        NO_ACTIVE_DNS,
        /** Source file does not exist. */
        MISSING,
        /** Source exists but is not a regular bounded resolver file. */
        INVALID,
        /** Source exists but differs from the current validated DNS content. */
        MODIFIED,
        /** A repair was written and verified. */
        REPAIRED,
        /** A repair was attempted but the source still is not correct. */
        REPAIR_FAILED
    }

    /** Immutable, non-sensitive doctor result. */
    public static final class Report {
        private final Status status;
        private final String detail;

        private Report(Status status, String detail) {
            this.status = status;
            this.detail = detail == null ? "" : detail;
        }

        public Status getStatus() {
            return status;
        }

        public String getDetail() {
            return detail;
        }

        /** Whether the current source can be bound as a resolver. */
        public boolean hasUsableResolver() {
            return status == Status.OK || status == Status.REPAIRED;
        }

        @Override
        public String toString() {
            return status + (detail.isEmpty() ? "" : ": " + detail);
        }
    }

    private final Path targetFile;
    private final Supplier<List<String>> dnsCandidates;

    /**
     * @param targetFile app-private host source that is bound over the guest
     *                   resolver path
     * @param dnsCandidates supplier of current Android DNS literal candidates
     */
    public GuestResolverDoctor(Path targetFile, Supplier<List<String>> dnsCandidates) {
        if (targetFile == null) {
            throw new IllegalArgumentException("targetFile must not be null");
        }
        if (dnsCandidates == null) {
            throw new IllegalArgumentException("dnsCandidates must not be null");
        }
        this.targetFile = targetFile;
        this.dnsCandidates = dnsCandidates;
    }

    /** Return the current resolver state without writing anything. */
    public Report check() {
        ResolvConf expected = expectedConf();
        if (expected == null) {
            return new Report(Status.NO_ACTIVE_DNS, "Android has no usable DNS server");
        }
        byte[] expectedBytes = expected.toText().getBytes(StandardCharsets.US_ASCII);
        if (!Files.exists(targetFile, LinkOption.NOFOLLOW_LINKS)) {
            return new Report(Status.MISSING, "resolver source is missing");
        }
        if (!Files.isRegularFile(targetFile, LinkOption.NOFOLLOW_LINKS)) {
            return new Report(Status.INVALID, "resolver source is not a regular file");
        }
        try {
            if (Files.size(targetFile) > GuestResolvConfWriter.MAX_FILE_BYTES) {
                return new Report(Status.INVALID, "resolver source exceeds size limit");
            }
            byte[] actual = Files.readAllBytes(targetFile);
            if (Arrays.equals(expectedBytes, actual)) {
                return new Report(Status.OK, "resolver source matches Android DNS");
            }
            return new Report(Status.MODIFIED, "resolver source differs from Android DNS");
        } catch (IOException | RuntimeException e) {
            return new Report(Status.INVALID, "resolver source could not be read");
        }
    }

    /**
     * Repair the source when possible, then verify the resulting bytes.
     *
     * @return a report describing the no-DNS, unchanged, repaired, or failed
     *         outcome; no exception escapes for ordinary filesystem failures
     */
    public Report repair() {
        Report before = check();
        if (before.getStatus() == Status.NO_ACTIVE_DNS
                || before.getStatus() == Status.OK) {
            return before;
        }
        List<String> candidates = currentCandidates();
        if (expectedConf(candidates) == null) {
            return new Report(Status.NO_ACTIVE_DNS, "Android has no usable DNS server");
        }
        try {
            if (GuestResolvConfWriter.write(targetFile, candidates) == null) {
                return new Report(Status.NO_ACTIVE_DNS, "Android has no usable DNS server");
            }
        } catch (IOException | RuntimeException e) {
            return new Report(Status.REPAIR_FAILED, "could not rewrite resolver source");
        }
        Report after = check();
        if (after.getStatus() == Status.OK) {
            return new Report(Status.REPAIRED, "resolver source repaired");
        }
        return new Report(Status.REPAIR_FAILED, "resolver source remains unhealthy");
    }

    private ResolvConf expectedConf() {
        return expectedConf(currentCandidates());
    }

    private List<String> currentCandidates() {
        try {
            List<String> candidates = dnsCandidates.get();
            return candidates == null ? Collections.emptyList() : candidates;
        } catch (RuntimeException e) {
            return Collections.emptyList();
        }
    }

    private static ResolvConf expectedConf(List<String> candidates) {
        return ResolvConf.of(candidates);
    }
}
