package gh.nusashell.nusadesk.infrastructure.androidbridge;

import gh.nusashell.nusadesk.infrastructure.proot.ProotBindMount;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Projects the latest Android battery snapshot into a small Linux-compatible
 * {@code /sys/class/power_supply/battery} tree.
 *
 * <p>This is intentionally a projection, not a claim that Android exposes
 * kernel sysfs to the guest. It is product-owned, read-only by convention,
 * refreshed periodically, and bound only for the active session. A guest write
 * may be overwritten by the next refresh or the next session.</p>
 */
public final class GuestBatterySysfs {
    public static final String STATE_RELATIVE_PATH =
            "linux-wrapper/state/android-bridge/power_supply";
    /** Guest path of the product-owned power-supply directory. */
    public static final String GUEST_PATH = "/sys/class/power_supply";
    private static final String BATTERY_DIR = "battery";
    /** Guest path of the projected battery directory. */
    public static final String GUEST_BATTERY_PATH = GUEST_PATH + "/battery";
    private static final String[] VALUE_FILES = {
            "capacity", "status", "health", "present", "online", "temp",
            "voltage_now", "current_now", "charge_counter", "energy_now", "uevent"
    };

    private final Path powerSupplyDir;
    private final BatteryStatusSource source;
    private volatile boolean usable;

    public GuestBatterySysfs(Path filesDir, BatteryStatusSource source) {
        if (filesDir == null || source == null) {
            throw new IllegalArgumentException("filesDir and source are required");
        }
        if (!filesDir.isAbsolute()) {
            throw new IllegalArgumentException("filesDir must be absolute");
        }
        this.powerSupplyDir = filesDir.resolve(STATE_RELATIVE_PATH);
        this.source = source;
    }

    /**
     * Create the fixed host tree and perform an initial refresh.
     *
     * @return {@code true} when the tree is safe and usable; false means the
     *         RPC bridge may continue without a sysfs bind.
     */
    public synchronized boolean prepare() {
        try {
            ensureDirectory(powerSupplyDir);
            Path battery = powerSupplyDir.resolve(BATTERY_DIR);
            ensureDirectory(battery);
            for (String file : VALUE_FILES) {
                Path path = battery.resolve(file);
                if (Files.isSymbolicLink(path) || Files.isDirectory(path)) {
                    usable = false;
                    return false;
                }
            }
            usable = true;
            refresh();
            return true;
        } catch (IOException | RuntimeException e) {
            usable = false;
            return false;
        }
    }

    /** Refresh the files from the Android-backed source, best-effort. */
    public synchronized void refresh() {
        if (!usable) {
            return;
        }
        BatteryStatus status;
        try {
            status = source.read();
        } catch (RuntimeException e) {
            return;
        }
        if (status == null) {
            status = BatteryStatus.unavailable();
        }
        Path battery = powerSupplyDir.resolve(BATTERY_DIR);
        List<FileValue> values = fileValues(status);
        for (FileValue value : values) {
            try {
                atomicWrite(battery.resolve(value.name), value.value + "\n");
            } catch (IOException | RuntimeException e) {
                // A guest read should see the last complete snapshot, not a
                // partially written value. The next refresh retries it.
            }
        }
    }

    /** Host directory to bind at the guest's conventional power-supply path. */
    public Path getPowerSupplyDir() {
        return powerSupplyDir;
    }

    /** Strictly bind the projection over the literal guest path. */
    public List<ProotBindMount> requiredBinds(Path rootfs) {
        if (!usable || rootfs == null) {
            return Collections.emptyList();
        }
        Path targetParent = rootfs.resolve("sys/class");
        if (!createGuestParentDirs(rootfs, targetParent)) {
            return Collections.emptyList();
        }
        return Collections.singletonList(
                ProotBindMount.ofStrict(powerSupplyDir.toString(), GUEST_PATH));
    }

    /** Remove only product-owned battery files; leave unrelated state intact. */
    public synchronized void clear() {
        if (!Files.isDirectory(powerSupplyDir, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(powerSupplyDir)) {
            return;
        }
        Path battery = powerSupplyDir.resolve(BATTERY_DIR);
        if (Files.isSymbolicLink(battery) || !Files.isDirectory(battery,
                LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        for (String file : VALUE_FILES) {
            try {
                Files.deleteIfExists(battery.resolve(file));
            } catch (IOException | RuntimeException e) {
                // State is non-sensitive and will be overwritten on next start.
            }
        }
        usable = false;
    }

    private static List<FileValue> fileValues(BatteryStatus status) {
        List<FileValue> values = new ArrayList<>();
        values.add(new FileValue("capacity", String.valueOf(
                status.getCapacityPercent() < 0 ? 0 : status.getCapacityPercent())));
        values.add(new FileValue("status", linuxStatus(status.getStatus())));
        values.add(new FileValue("health", linuxHealth(status.getHealth())));
        values.add(new FileValue("present", status.isPresent() && status.isAvailable() ? "1" : "0"));
        values.add(new FileValue("online", "none".equals(status.getPlugged()) ? "0" : "1"));
        values.add(new FileValue("temp", String.valueOf(status.getTemperatureDeciCelsius())));
        values.add(new FileValue("voltage_now", String.valueOf(status.getVoltageMicrovolts())));
        values.add(new FileValue("current_now", String.valueOf(status.getCurrentMicroamps())));
        values.add(new FileValue("charge_counter", String.valueOf(
                status.getChargeCounterMicroampHours())));
        long energyMicrowattHours = status.getEnergyNanowattHours() < 0
                ? -1L : status.getEnergyNanowattHours() / 1_000L;
        values.add(new FileValue("energy_now", String.valueOf(energyMicrowattHours)));
        values.add(new FileValue("uevent", uevent(status)));
        return values;
    }

    private static String uevent(BatteryStatus status) {
        StringBuilder text = new StringBuilder();
        text.append("POWER_SUPPLY_NAME=battery\n");
        text.append("POWER_SUPPLY_PRESENT=")
                .append(status.isPresent() && status.isAvailable() ? "1" : "0").append('\n');
        text.append("POWER_SUPPLY_STATUS=").append(linuxStatus(status.getStatus())).append('\n');
        text.append("POWER_SUPPLY_HEALTH=").append(linuxHealth(status.getHealth())).append('\n');
        text.append("POWER_SUPPLY_CAPACITY=")
                .append(status.getCapacityPercent() < 0 ? 0 : status.getCapacityPercent())
                .append('\n');
        return text.toString();
    }

    private static String linuxStatus(String value) {
        if ("charging".equals(value)) return "Charging";
        if ("discharging".equals(value)) return "Discharging";
        if ("full".equals(value)) return "Full";
        if ("not-charging".equals(value)) return "Not charging";
        return "Unknown";
    }

    private static String linuxHealth(String value) {
        if ("good".equals(value)) return "Good";
        if ("overheat".equals(value)) return "Overheat";
        if ("dead".equals(value)) return "Dead";
        if ("over-voltage".equals(value)) return "Over voltage";
        if ("unspecified-failure".equals(value)) return "Unspecified failure";
        if ("cold".equals(value)) return "Cold";
        return "Unknown";
    }

    private static void ensureDirectory(Path directory) throws IOException {
        if (Files.isSymbolicLink(directory)
                || (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)
                && !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS))) {
            throw new IOException("unsafe battery bridge path: " + directory);
        }
        Files.createDirectories(directory);
        try {
            Files.setPosixFilePermissions(directory,
                    java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"));
        } catch (UnsupportedOperationException | IOException | IllegalArgumentException
                 | SecurityException ignored) {
            // Providers without POSIX mode support still expose the directory.
        }
    }

    private static boolean createGuestParentDirs(Path rootfs, Path guestParent) {
        if (!guestParent.startsWith(rootfs)) {
            return false;
        }
        Path cursor = rootfs;
        for (Path segment : rootfs.relativize(guestParent)) {
            cursor = cursor.resolve(segment);
            if (Files.isSymbolicLink(cursor)
                    || (Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)
                    && !Files.isDirectory(cursor, LinkOption.NOFOLLOW_LINKS))) {
                return false;
            }
        }
        try {
            Files.createDirectories(guestParent);
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private static void atomicWrite(Path target, String text) throws IOException {
        if (Files.isSymbolicLink(target)) {
            throw new IOException("refusing symlink battery value: " + target);
        }
        Path temp = Files.createTempFile(target.getParent(),
                "." + target.getFileName() + "-", ".tmp");
        try {
            Files.write(temp, text.getBytes(StandardCharsets.UTF_8));
            try {
                Files.setPosixFilePermissions(temp,
                        java.nio.file.attribute.PosixFilePermissions.fromString("rw-r--r--"));
            } catch (UnsupportedOperationException | IOException | IllegalArgumentException
                     | SecurityException ignored) {
                // The guest still receives a readable snapshot on providers without modes.
            }
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static final class FileValue {
        private final String name;
        private final String value;

        private FileValue(String name, String value) {
            this.name = name;
            this.value = value;
        }
    }
}
