package gh.nusashell.nusadesk.infrastructure.androidbridge;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class GuestBatterySysfsTest {
    private Path filesDir;
    private GuestBatterySysfs sysfs;
    private BatteryStatusSource source;

    @Before
    public void setUp() throws Exception {
        filesDir = Files.createTempDirectory("battery-bridge-");
        source = () -> new BatteryStatus(true, 81, "discharging", "good", "none", true,
                301, 4_100_000L, -250_000L, 2_000_000L, 7_500_000_000L);
        sysfs = new GuestBatterySysfs(filesDir, source);
    }

    @After
    public void tearDown() throws Exception {
        deleteTree(filesDir);
    }

    @Test
    public void preparesRefreshesAndMapsAndroidUnitsToLinuxPowerSupplyFiles() throws Exception {
        assertTrue(sysfs.prepare());
        Path battery = sysfs.getPowerSupplyDir().resolve("battery");
        assertEquals("81\n", read(battery.resolve("capacity")));
        assertEquals("Discharging\n", read(battery.resolve("status")));
        assertEquals("301\n", read(battery.resolve("temp")));
        assertEquals("4100000\n", read(battery.resolve("voltage_now")));
        assertEquals("-250000\n", read(battery.resolve("current_now")));
        assertEquals("2000000\n", read(battery.resolve("charge_counter")));
        assertEquals("7500000\n", read(battery.resolve("energy_now")));
        assertTrue(read(battery.resolve("uevent")).contains("POWER_SUPPLY_CAPACITY=81"));
    }

    @Test
    public void requiredBindIsStrictAndCreatesOnlyGuestParents() throws Exception {
        assertTrue(sysfs.prepare());
        Path rootfs = Files.createTempDirectory("battery-rootfs-");
        try {
            List<gh.nusashell.nusadesk.infrastructure.proot.ProotBindMount> binds =
                    sysfs.requiredBinds(rootfs);
            assertEquals(1, binds.size());
            assertTrue(binds.get(0).isStrict());
            assertEquals(GuestBatterySysfs.GUEST_PATH, binds.get(0).getGuestPath());
            assertTrue(Files.isDirectory(rootfs.resolve("sys/class")));
            assertFalse(Files.exists(rootfs.resolve("sys/class/power_supply")));
        } finally {
            deleteTree(rootfs);
        }
    }

    @Test
    public void refusesSymlinkedStateWithoutFollowingIt() throws Exception {
        Path state = filesDir.resolve(GuestBatterySysfs.STATE_RELATIVE_PATH);
        Files.createDirectories(state.getParent());
        Path outside = Files.createTempDirectory("battery-outside-");
        try {
            Files.createSymbolicLink(state, outside);
            assertFalse(sysfs.prepare());
            assertFalse(Files.exists(outside.resolve("battery"), LinkOption.NOFOLLOW_LINKS));
        } finally {
            deleteTree(outside);
        }
    }

    private static String read(Path path) throws Exception {
        return new String(Files.readAllBytes(path), java.nio.charset.StandardCharsets.UTF_8);
    }

    private static void deleteTree(Path path) throws Exception {
        if (path == null || !Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (Files.isSymbolicLink(path)) {
            Files.deleteIfExists(path);
            return;
        }
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            try (java.nio.file.DirectoryStream<Path> entries = Files.newDirectoryStream(path)) {
                for (Path entry : entries) {
                    deleteTree(entry);
                }
            }
        }
        Files.deleteIfExists(path);
    }
}
