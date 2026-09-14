package gh.nusashell.nusadesk.infrastructure.proot;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Pure tests for {@link ProotLaunchSpec} builder validation. No Android.
 */
public class ProotLaunchSpecTest {

    private static ProotLaunchSpec.Builder validBuilder() {
        return ProotLaunchSpec.builder()
                .prootBinary("/data/app/lib/arm64/libproot.so")
                .rootfs("/data/data/gh.nusashell.nusadesk/files/linux-wrapper/runtimes/ubuntu-base-arm64/active")
                .guestArgv(Arrays.asList("/bin/sh", "-c", "uname -a"))
                .bindMounts(ProotCommandFactory.DEFAULT_SYSTEM_BINDS)
                .hostWorkingDir("/data/data/gh.nusashell.nusadesk/files");
    }

    @Test
    public void buildProducesImmutableCollections() {
        ProotLaunchSpec spec = validBuilder().build();
        try {
            spec.getGuestArgv().add("x");
            fail("expected UnsupportedOperationException");
        } catch (UnsupportedOperationException expected) {
            // expected
        }
        try {
            spec.getBindMounts().add(ProotBindMount.of("/proc", "/proc"));
            fail("expected UnsupportedOperationException");
        } catch (UnsupportedOperationException expected) {
            // expected
        }
        try {
            spec.getEnv().put("x", "y");
            fail("expected UnsupportedOperationException");
        } catch (UnsupportedOperationException expected) {
            // expected
        }
    }

    @Test
    public void buildRejectsRelativeProotBinary() {
        try {
            validBuilder().prootBinary("libproot.so").build();
            fail("expected exception");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("prootBinary"));
        }
    }

    @Test
    public void buildRejectsTraversalRootfs() {
        try {
            validBuilder().rootfs("/data/../etc/active").build();
            fail("expected exception");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("rootfs"));
        }
    }

    @Test
    public void buildRejectsTraversalHostWorkingDir() {
        try {
            validBuilder().hostWorkingDir("/data/../etc").build();
            fail("expected exception");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("hostWorkingDir"));
        }
    }

    @Test
    public void buildRejectsTraversalGuestWorkdir() {
        try {
            validBuilder().guestWorkdir("/root/../etc").build();
            fail("expected exception");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("guestWorkdir"));
        }
    }

    @Test
    public void buildRejectsEmptyGuestArgv() {
        try {
            validBuilder().guestArgv(Collections.<String>emptyList()).build();
            fail("expected exception");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("guestArgv"));
        }
    }

    @Test
    public void buildRejectsNullGuestArgvElement() {
        try {
            validBuilder().guestArgv(Arrays.asList("/bin/sh", null, "x")).build();
            fail("expected exception");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("null"));
        }
    }

    @Test
    public void buildRejectsBlankEntrypoint() {
        try {
            validBuilder().guestArgv(Arrays.asList("  ", "arg")).build();
            fail("expected exception");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("entrypoint"));
        }
    }

    @Test
    public void buildRejectsEntrypointStartingWithDash() {
        // No "--" separator is emitted, so an entrypoint beginning with '-'
        // would be misparsed by PRoot as an option.
        try {
            validBuilder().guestArgv(Arrays.asList("-n", "arg")).build();
            fail("expected exception");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("entrypoint"));
        }
    }

    @Test
    public void buildRejectsNullProotBinary() {
        try {
            validBuilder().prootBinary(null).build();
            fail("expected exception");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    @Test
    public void buildRejectsEnvWithNullValue() {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("PATH", null);
        try {
            validBuilder().env(env).build();
            fail("expected exception");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("PATH"));
        }
    }

    @Test
    public void buildRejectsEnvWithEmptyKey() {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("", "value");
        try {
            validBuilder().env(env).build();
            fail("expected exception");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("env keys"));
        }
    }

    @Test
    public void putEnvOverridesExisting() {
        ProotLaunchSpec spec = validBuilder()
                .putEnv("HOME", "/home/me")
                .build();
        assertEquals("/home/me", spec.getEnv().get("HOME"));
    }

    @Test
    public void defaultKillOnExitIsTrue() {
        ProotLaunchSpec spec = validBuilder().build();
        assertTrue(spec.isKillOnExit());
    }
}
