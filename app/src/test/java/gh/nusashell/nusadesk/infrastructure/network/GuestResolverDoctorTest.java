package gh.nusashell.nusadesk.infrastructure.network;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** JVM tests for the resolver doctor check/repair policy. */
public class GuestResolverDoctorTest {

    @Rule
    public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void missingSourceIsReportedAndRepairedFromValidatedDns() throws Exception {
        Path target = temporary.getRoot().toPath().resolve("state/resolv.conf");
        GuestResolverDoctor doctor = doctor(target, Arrays.asList("8.8.8.8"));

        assertEquals(GuestResolverDoctor.Status.MISSING, doctor.check().getStatus());
        GuestResolverDoctor.Report repaired = doctor.repair();

        assertEquals(GuestResolverDoctor.Status.REPAIRED, repaired.getStatus());
        assertEquals("nameserver 8.8.8.8\n",
                new String(Files.readAllBytes(target), StandardCharsets.US_ASCII));
    }

    @Test
    public void matchingSourceIsReadOnlyAndReportedOk() throws Exception {
        Path target = temporary.getRoot().toPath().resolve("resolv.conf");
        GuestResolvConfWriter.write(target, Collections.singletonList("1.1.1.1"));
        long modified = Files.getLastModifiedTime(target).toMillis();
        GuestResolverDoctor doctor = doctor(target, Collections.singletonList("1.1.1.1"));

        assertEquals(GuestResolverDoctor.Status.OK, doctor.check().getStatus());
        assertEquals(GuestResolverDoctor.Status.OK, doctor.repair().getStatus());
        assertEquals(modified, Files.getLastModifiedTime(target).toMillis());
    }

    @Test
    public void modifiedSourceIsDetectedAndRepaired() throws Exception {
        Path target = temporary.getRoot().toPath().resolve("resolv.conf");
        GuestResolvConfWriter.write(target, Collections.singletonList("9.9.9.9"));
        GuestResolverDoctor doctor = doctor(target, Collections.singletonList("1.1.1.1"));

        assertEquals(GuestResolverDoctor.Status.MODIFIED, doctor.check().getStatus());
        assertEquals(GuestResolverDoctor.Status.REPAIRED, doctor.repair().getStatus());
        assertEquals(GuestResolverDoctor.Status.OK, doctor.check().getStatus());
    }

    @Test
    public void invalidOversizedSourceIsDetectedAndRepaired() throws Exception {
        Path target = temporary.getRoot().toPath().resolve("resolv.conf");
        Files.createDirectories(target.getParent());
        Files.write(target, new byte[GuestResolvConfWriter.MAX_FILE_BYTES + 1]);
        GuestResolverDoctor doctor = doctor(target, Collections.singletonList("192.168.1.1"));

        assertEquals(GuestResolverDoctor.Status.INVALID, doctor.check().getStatus());
        assertEquals(GuestResolverDoctor.Status.REPAIRED, doctor.repair().getStatus());
        assertEquals(GuestResolverDoctor.Status.OK, doctor.check().getStatus());
    }

    @Test
    public void noActiveDnsNeverDeletesOrWritesExistingSource() throws Exception {
        Path target = temporary.getRoot().toPath().resolve("resolv.conf");
        GuestResolvConfWriter.write(target, Collections.singletonList("192.168.1.1"));
        byte[] before = Files.readAllBytes(target);
        GuestResolverDoctor doctor = doctor(target, Collections.emptyList());

        assertEquals(GuestResolverDoctor.Status.NO_ACTIVE_DNS, doctor.check().getStatus());
        assertEquals(GuestResolverDoctor.Status.NO_ACTIVE_DNS, doctor.repair().getStatus());
        assertTrue(Arrays.equals(before, Files.readAllBytes(target)));
    }

    @Test
    public void invalidCandidatesAreNotUsedAsFallback() throws Exception {
        Path target = temporary.getRoot().toPath().resolve("resolv.conf");
        GuestResolverDoctor doctor = doctor(target, Collections.singletonList("dns.google"));

        assertEquals(GuestResolverDoctor.Status.NO_ACTIVE_DNS, doctor.repair().getStatus());
        assertFalse(Files.exists(target));
    }

    @Test
    public void replacesSymlinkRatherThanWritingThroughIt() throws Exception {
        Path dir = temporary.getRoot().toPath();
        Path target = dir.resolve("resolv.conf");
        Path outside = dir.resolve("outside");
        Files.write(outside, "do not touch".getBytes(StandardCharsets.US_ASCII));
        Files.createSymbolicLink(target, outside.getFileName());
        GuestResolverDoctor doctor = doctor(target, Collections.singletonList("8.8.8.8"));

        assertEquals(GuestResolverDoctor.Status.INVALID, doctor.check().getStatus());
        assertEquals(GuestResolverDoctor.Status.REPAIRED, doctor.repair().getStatus());
        assertFalse(Files.isSymbolicLink(target));
        assertEquals("do not touch",
                new String(Files.readAllBytes(outside), StandardCharsets.US_ASCII));
    }

    private static GuestResolverDoctor doctor(Path target, List<String> dns) {
        AtomicReference<List<String>> current = new AtomicReference<>(dns);
        return new GuestResolverDoctor(target, current::get);
    }
}
