package gh.nusashell.nusadesk.domain.runtime;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Contract guard for the product-owned user-service manager assets
 * (ADR-0024): the launcher must run the vendored replacement in {@code --user}
 * mode with the environment those two lookups need, and the unit that starts
 * it must point at the launcher's wired guest path and be enabled into the
 * system default target.
 */
public class GuestUserServiceManagerAssetTest {

    private static String asset(String name) throws IOException {
        Path workingDir = Paths.get("").toAbsolutePath();
        Path moduleAssets = workingDir.resolve("src/main/assets");
        Path base = Files.isDirectory(moduleAssets)
                ? moduleAssets : workingDir.resolve("app/src/main/assets");
        Path file = base.resolve("services").resolve(name);
        assertTrue("packaged asset missing: " + file, Files.isRegularFile(file));
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    @Test
    public void theLauncherRunsTheUserManagerWithTheRightEnvironment() throws IOException {
        String script = asset("lw-user-manager");

        assertTrue(script.startsWith("#!/bin/sh"));
        // The user unit folders are found relative to HOME; without it the
        // manager answers "Unit … not found" for a unit that exists.
        assertTrue(script.contains("export HOME=/root"));
        // The replacement keeps user state under the runtime dir.
        assertTrue(script.contains("export XDG_RUNTIME_DIR=/run/user/0"));
        assertTrue(script.contains("mkdir -p /run/user/0"));
        // User units are enabled into default.target; the replacement's
        // built-in default (multi-user.target) never exists in a user set.
        assertTrue(script.contains("export SYSTEMD_DEFAULT_TARGET=default.target"));
        assertTrue("the manager must replace the shell, so signals reach it",
                script.contains("exec /usr/bin/python3.12 /usr/bin/systemctl --user init"));
    }

    @Test
    public void theUnitStartsTheLauncherFromItsWiredPath() throws IOException {
        String unit = asset("lw-user-manager.service");

        assertTrue(unit.contains("[Unit]"));
        assertTrue(unit.contains("WantedBy=multi-user.target"));
        assertTrue(unit.contains("Type=simple"));
        assertTrue("the manager stops user units on SIGTERM before exiting",
                unit.contains("TimeoutStopSec="));

        String launcher = null;
        for (VendoredFile vendored
                : CuratedRuntimeCatalog.guestServiceBridge().getVendoredFiles()) {
            if ("usr/local/bin/lw-user-manager".equals(vendored.getOverlayPath())) {
                launcher = "/" + vendored.getOverlayPath();
                assertTrue("the launcher must be executable in the overlay",
                        vendored.isExecutable());
            }
        }
        assertTrue("the unit's ExecStart must point at the vendored launcher",
                launcher != null && unit.contains("ExecStart=" + launcher));
    }

    @Test
    public void theUnitIsEnabledByTheBridgeNotByAMidSessionEnable() throws IOException {
        // The manager snapshots the enabled-unit set at `systemctl init`, so
        // the unit must be part of the vendored set the bridge wires (and
        // enables) before session init.
        boolean unitVendored = false;
        for (VendoredFile vendored
                : CuratedRuntimeCatalog.guestServiceBridge().getVendoredFiles()) {
            if ("etc/systemd/system/lw-user-manager.service".equals(vendored.getOverlayPath())) {
                unitVendored = true;
                assertFalse("a unit file is not executable", vendored.isExecutable());
            }
        }
        assertTrue("the user-manager unit must ship with the bridge payload", unitVendored);
        assertTrue(CuratedRuntimeCatalog.guestServiceBridge().getVendoredFiles().stream()
                .anyMatch(file -> "usr/local/bin/lw-user-manager"
                        .equals(file.getOverlayPath())));
    }
}
