package gh.nusashell.nusadesk.domain.compose;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ComposeServiceSpecTest {
    private static final String SHA256_HEX =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final String SHA256_HEX_UPPER =
            "0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF";

    @Test
    public void buildsAMinimalService() {
        ComposeServiceSpec spec = minimalSpec("web");
        assertEquals("web", spec.getServiceName());
        assertEquals("ubuntu-base-arm64", spec.getImage());
        assertTrue(spec.getCommand().isEmpty());
        assertTrue(spec.getEntrypoint().isEmpty());
        assertTrue(spec.getEnvironment().isEmpty());
        assertTrue(spec.getVolumes().isEmpty());
        assertTrue(spec.getPorts().isEmpty());
        assertNull(spec.getWorkingDirectory());
        assertTrue(spec.getDependsOn().isEmpty());
        assertEquals(ComposeRestartPolicy.NO, spec.getRestartPolicy());
    }

    @Test
    public void buildsAFullySpecifiedService() {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("APP_HOME", "/app");
        env.put("EMPTY_FLAG", "");
        ComposeServiceSpec spec = new ComposeServiceSpec(
                "web",
                "ubuntu-base-arm64",
                Arrays.asList("--serve", "8080"),
                Arrays.asList("/entry.sh"),
                env,
                Arrays.asList(new ComposeVolumeMapping("./site", "/srv/www", true)),
                Arrays.asList(new ComposePortMapping(8080, 80, "tcp", "127.0.0.1")),
                "/app",
                Arrays.asList("db"),
                ComposeRestartPolicy.UNLESS_STOPPED);
        assertEquals(Arrays.asList("--serve", "8080"), spec.getCommand());
        assertEquals(Arrays.asList("/entry.sh"), spec.getEntrypoint());
        assertEquals("/app", spec.getEnvironment().get("APP_HOME"));
        assertEquals("", spec.getEnvironment().get("EMPTY_FLAG"));
        assertEquals(1, spec.getVolumes().size());
        assertEquals(1, spec.getPorts().size());
        assertEquals("/app", spec.getWorkingDirectory());
        assertEquals(Arrays.asList("db"), spec.getDependsOn());
        assertEquals(ComposeRestartPolicy.UNLESS_STOPPED, spec.getRestartPolicy());
    }

    @Test
    public void trimsServiceAndImageNames() {
        ComposeServiceSpec spec = new ComposeServiceSpec(
                "  web  ", " ubuntu-base-arm64 ",
                Collections.<String>emptyList(), Collections.<String>emptyList(),
                Collections.<String, String>emptyMap(),
                Collections.<ComposeVolumeMapping>emptyList(),
                Collections.<ComposePortMapping>emptyList(),
                null, Collections.<String>emptyList(), null);
        assertEquals("web", spec.getServiceName());
        assertEquals("ubuntu-base-arm64", spec.getImage());
    }

    @Test
    public void rejectsMissingOrBlankServiceAndImageNames() {
        assertRejectedName(null);
        assertRejectedName("");
        assertRejectedName("   ");
        assertRejectedImage(null);
        assertRejectedImage("");
        assertRejectedImage("   ");
    }

    @Test
    public void rejectsNamesUnsafeForGeneratedIdentifiers() {
        assertRejectedName("my web");
        assertRejectedName("my/web");
        assertRejectedName("my\\web");
        assertRejectedName("my:web");
        assertRejectedName(".web");
        assertRejectedName("-web");
    }

    @Test
    public void acceptsRegistryTagAndDigestImageRefs() {
        assertAcceptedImage("nginx");
        assertAcceptedImage("nginx:alpine");
        assertAcceptedImage("ubuntu:24.04");
        assertAcceptedImage("library/nginx");
        assertAcceptedImage("ghcr.io/org/app:1.2");
        assertAcceptedImage("registry.local:5000/img");
        assertAcceptedImage("Repo/App_1-x.y");
        assertAcceptedImage("repo/app@sha256:" + SHA256_HEX);
        assertAcceptedImage("repo/app:1.0@sha256:" + SHA256_HEX);
        assertAcceptedImage("repo/app@sha256:" + SHA256_HEX_UPPER);
    }

    @Test
    public void rejectsShellControlAndTraversalImageRefs() {
        assertRejectedImage("my image");
        assertRejectedImage("img\0x");
        assertRejectedImage("img\nx");
        assertRejectedImage("img;rm");
        assertRejectedImage("$(evil)");
        assertRejectedImage("img`x`");
        assertRejectedImage("img\\x");
        assertRejectedImage("'img'");
        assertRejectedImage("img&x");
        assertRejectedImage(".img");
        assertRejectedImage("-img");
        assertRejectedImage("/img");
        assertRejectedImage("repo//img");
        assertRejectedImage("repo/../img");
        assertRejectedImage("../img");
        assertRejectedImage("repo/");
        assertRejectedImage("repo@a@b");
        assertRejectedImage("@sha256:" + SHA256_HEX);
        assertRejectedImage("img@sha256:");
        assertRejectedImage("img@sha256:" + SHA256_HEX.substring(1));
        assertRejectedImage("img@sha256:" + SHA256_HEX + "0");
        assertRejectedImage("img@sha256:" + SHA256_HEX.replace('a', 'g'));
        assertRejectedImage("img@md5:" + SHA256_HEX);
        assertRejectedImage("app:");
        assertRejectedImage(":tag");
        assertRejectedImage("host:/img");
        assertRejectedImage("a:b:c");
    }

    @Test
    public void acceptsUnderscoreAndInteriorPunctuationInNames() {
        ComposeServiceSpec spec = minimalSpec("_web-1.x");
        assertEquals("_web-1.x", spec.getServiceName());
    }

    @Test
    public void nullRestartPolicyMeansNo() {
        ComposeServiceSpec spec = new ComposeServiceSpec(
                "web", "ubuntu-base-arm64",
                Collections.<String>emptyList(), Collections.<String>emptyList(),
                Collections.<String, String>emptyMap(),
                Collections.<ComposeVolumeMapping>emptyList(),
                Collections.<ComposePortMapping>emptyList(),
                null, Collections.<String>emptyList(), null);
        assertEquals(ComposeRestartPolicy.NO, spec.getRestartPolicy());
    }

    @Test
    public void rejectsNullArgumentLists() {
        try {
            new ComposeServiceSpec("web", "img", null, Collections.<String>emptyList(),
                    Collections.<String, String>emptyMap(),
                    Collections.<ComposeVolumeMapping>emptyList(),
                    Collections.<ComposePortMapping>emptyList(),
                    null, Collections.<String>emptyList(), null);
            fail("expected rejection of null command");
        } catch (IllegalArgumentException expected) {
            // expected
        }
        try {
            new ComposeServiceSpec("web", "img", Collections.<String>emptyList(), null,
                    Collections.<String, String>emptyMap(),
                    Collections.<ComposeVolumeMapping>emptyList(),
                    Collections.<ComposePortMapping>emptyList(),
                    null, Collections.<String>emptyList(), null);
            fail("expected rejection of null entrypoint");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    @Test
    public void rejectsNullBlankAndNulArguments() {
        assertRejectedArg(null);
        assertRejectedArg("");
        assertRejectedArg("   ");
        assertRejectedArg("--flag\0x");
    }

    @Test
    public void keepsArgumentTextVerbatim() {
        ComposeServiceSpec spec = new ComposeServiceSpec(
                "web", "img",
                Arrays.asList("echo  spaced ", "-n"),
                Collections.<String>emptyList(),
                Collections.<String, String>emptyMap(),
                Collections.<ComposeVolumeMapping>emptyList(),
                Collections.<ComposePortMapping>emptyList(),
                null, Collections.<String>emptyList(), null);
        assertEquals(Arrays.asList("echo  spaced ", "-n"), spec.getCommand());
    }

    @Test
    public void rejectsInvalidEnvironmentEntries() {
        assertRejectedEnvironment(null, "value");
        assertRejectedEnvironment("", "value");
        assertRejectedEnvironment("   ", "value");
        assertRejectedEnvironment("KEY\0X", "value");
        assertRejectedEnvironment("KEY=VALUE", "x");
        assertRejectedEnvironment("=LEADING", "x");
        assertRejectedEnvironment("TRAILING=", "x");
        assertRejectedEnvironment("KEY", null);
        assertRejectedEnvironment("KEY", "va\0lue");
    }

    @Test
    public void allowsEqualsSignsAndEmptyEnvironmentValues() {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("EMPTY_FLAG", "");
        env.put("CONNECTION", "host=a;port=b");
        ComposeServiceSpec spec = specWithEnvironment(env);
        assertEquals("", spec.getEnvironment().get("EMPTY_FLAG"));
        assertEquals("host=a;port=b", spec.getEnvironment().get("CONNECTION"));
    }

    @Test
    public void environmentPreservesDeclaredOrder() {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("B", "2");
        env.put("A", "1");
        ComposeServiceSpec spec = specWithEnvironment(env);
        assertEquals(Arrays.asList("B", "A"),
                new ArrayList<>(spec.getEnvironment().keySet()));
    }

    @Test
    public void rejectsAnInvalidWorkingDirectory() {
        assertRejectedWorkingDirectory("work");
        assertRejectedWorkingDirectory("./work");
        assertRejectedWorkingDirectory("/a/../b");
        assertRejectedWorkingDirectory("/a/b\0c");
        assertRejectedWorkingDirectory("   ");
    }

    @Test
    public void rejectsDuplicateVolumeTargets() {
        List<ComposeVolumeMapping> volumes = Arrays.asList(
                new ComposeVolumeMapping("one", "/data", false),
                new ComposeVolumeMapping("two", "/data", true));
        assertRejectedSpec(volumes);
    }

    @Test
    public void allowsTheSameSourceMountedAtDifferentTargets() {
        ComposeServiceSpec spec = new ComposeServiceSpec(
                "web", "img",
                Collections.<String>emptyList(), Collections.<String>emptyList(),
                Collections.<String, String>emptyMap(),
                Arrays.asList(
                        new ComposeVolumeMapping("vol", "/a", false),
                        new ComposeVolumeMapping("vol", "/b", true)),
                Collections.<ComposePortMapping>emptyList(),
                null, Collections.<String>emptyList(), null);
        assertEquals(2, spec.getVolumes().size());
    }

    @Test
    public void rejectsNullVolumesAndPortsListsAndElements() {
        try {
            specWithVolumes(null);
            fail("expected rejection of null volumes");
        } catch (IllegalArgumentException expected) {
            // expected
        }
        assertRejectedSpec(Arrays.asList(new ComposeVolumeMapping("v", "/a", false), null));
        try {
            specWithPorts(null);
            fail("expected rejection of null ports");
        } catch (IllegalArgumentException expected) {
            // expected
        }
        assertRejectedSpecPorts(Arrays.asList(new ComposePortMapping(1, 1, "tcp", null), null));
    }

    @Test
    public void rejectsDuplicateHostContainerProtocolPorts() {
        List<ComposePortMapping> ports = Arrays.asList(
                new ComposePortMapping(8080, 80, "tcp", null),
                new ComposePortMapping(8080, 80, "tcp", "127.0.0.1"));
        assertRejectedSpecPorts(ports);
    }

    @Test
    public void allowsTheSamePortsOnDifferentProtocols() {
        ComposeServiceSpec spec = new ComposeServiceSpec(
                "web", "img",
                Collections.<String>emptyList(), Collections.<String>emptyList(),
                Collections.<String, String>emptyMap(),
                Collections.<ComposeVolumeMapping>emptyList(),
                Arrays.asList(
                        new ComposePortMapping(53, 53, "tcp", null),
                        new ComposePortMapping(53, 53, "udp", null)),
                null, Collections.<String>emptyList(), null);
        assertEquals(2, spec.getPorts().size());
    }

    @Test
    public void rejectsBlankAndSelfDependencies() {
        assertRejectedDependsOn(Arrays.asList(""));
        assertRejectedDependsOn(Arrays.asList("   "));
        assertRejectedDependsOn(Arrays.asList("db", null));
        assertRejectedDependsOn(Arrays.asList("web"));
    }

    @Test
    public void collectionsAreImmutable() {
        List<String> command = new ArrayList<>(Arrays.asList("run"));
        Map<String, String> env = new LinkedHashMap<>();
        env.put("K", "v");
        List<ComposeVolumeMapping> volumes =
                new ArrayList<>(Arrays.asList(new ComposeVolumeMapping("v", "/a", false)));
        List<ComposePortMapping> ports =
                new ArrayList<>(Arrays.asList(new ComposePortMapping(1, 1, "tcp", null)));
        List<String> dependsOn = new ArrayList<>(Arrays.asList("db"));

        ComposeServiceSpec spec = new ComposeServiceSpec(
                "web", "img", command, Collections.<String>emptyList(), env,
                volumes, ports, null, dependsOn, ComposeRestartPolicy.ALWAYS);

        command.add("later");
        env.put("LATE", "x");
        volumes.add(new ComposeVolumeMapping("w", "/b", false));
        ports.add(new ComposePortMapping(2, 2, "tcp", null));
        dependsOn.add("cache");

        assertEquals(1, spec.getCommand().size());
        assertEquals(1, spec.getEnvironment().size());
        assertEquals(1, spec.getVolumes().size());
        assertEquals(1, spec.getPorts().size());
        assertEquals(1, spec.getDependsOn().size());

        assertUnmodifiable(spec.getCommand());
        assertUnmodifiable(spec.getEntrypoint());
        assertUnmodifiable(spec.getVolumes());
        assertUnmodifiable(spec.getPorts());
        assertUnmodifiable(spec.getDependsOn());
        try {
            spec.getEnvironment().put("X", "y");
            fail("expected unmodifiable environment");
        } catch (UnsupportedOperationException expected) {
            // expected
        }
    }

    @Test
    public void equalityIsByValue() {
        ComposeServiceSpec first = minimalSpec("web");
        ComposeServiceSpec same = minimalSpec("web");
        ComposeServiceSpec other = minimalSpec("worker");
        assertEquals(first, same);
        assertEquals(first.hashCode(), same.hashCode());
        assertNotEquals(first, other);
    }

    private static ComposeServiceSpec minimalSpec(String name) {
        return new ComposeServiceSpec(
                name, "ubuntu-base-arm64",
                Collections.<String>emptyList(), Collections.<String>emptyList(),
                Collections.<String, String>emptyMap(),
                Collections.<ComposeVolumeMapping>emptyList(),
                Collections.<ComposePortMapping>emptyList(),
                null, Collections.<String>emptyList(), ComposeRestartPolicy.NO);
    }

    private static ComposeServiceSpec specWithVolumes(List<ComposeVolumeMapping> volumes) {
        return new ComposeServiceSpec(
                "web", "img",
                Collections.<String>emptyList(), Collections.<String>emptyList(),
                Collections.<String, String>emptyMap(),
                volumes, Collections.<ComposePortMapping>emptyList(),
                null, Collections.<String>emptyList(), null);
    }

    private static ComposeServiceSpec specWithPorts(List<ComposePortMapping> ports) {
        return new ComposeServiceSpec(
                "web", "img",
                Collections.<String>emptyList(), Collections.<String>emptyList(),
                Collections.<String, String>emptyMap(),
                Collections.<ComposeVolumeMapping>emptyList(), ports,
                null, Collections.<String>emptyList(), null);
    }

    private static ComposeServiceSpec specWithEnvironment(Map<String, String> env) {
        return new ComposeServiceSpec(
                "web", "img",
                Collections.<String>emptyList(), Collections.<String>emptyList(),
                env,
                Collections.<ComposeVolumeMapping>emptyList(),
                Collections.<ComposePortMapping>emptyList(),
                null, Collections.<String>emptyList(), null);
    }

    private static void assertRejectedName(String name) {
        try {
            new ComposeServiceSpec(name, "img",
                    Collections.<String>emptyList(), Collections.<String>emptyList(),
                    Collections.<String, String>emptyMap(),
                    Collections.<ComposeVolumeMapping>emptyList(),
                    Collections.<ComposePortMapping>emptyList(),
                    null, Collections.<String>emptyList(), null);
            fail("expected rejection of serviceName: " + name);
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    private static void assertAcceptedImage(String image) {
        ComposeServiceSpec spec = new ComposeServiceSpec("web", image,
                Collections.<String>emptyList(), Collections.<String>emptyList(),
                Collections.<String, String>emptyMap(),
                Collections.<ComposeVolumeMapping>emptyList(),
                Collections.<ComposePortMapping>emptyList(),
                null, Collections.<String>emptyList(), null);
        assertEquals(image.trim(), spec.getImage());
    }

    private static void assertRejectedImage(String image) {
        try {
            new ComposeServiceSpec("web", image,
                    Collections.<String>emptyList(), Collections.<String>emptyList(),
                    Collections.<String, String>emptyMap(),
                    Collections.<ComposeVolumeMapping>emptyList(),
                    Collections.<ComposePortMapping>emptyList(),
                    null, Collections.<String>emptyList(), null);
            fail("expected rejection of image: " + image);
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    private static void assertRejectedArg(String arg) {
        try {
            new ComposeServiceSpec("web", "img",
                    Arrays.asList("ok", arg), Collections.<String>emptyList(),
                    Collections.<String, String>emptyMap(),
                    Collections.<ComposeVolumeMapping>emptyList(),
                    Collections.<ComposePortMapping>emptyList(),
                    null, Collections.<String>emptyList(), null);
            fail("expected rejection of arg: " + arg);
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    private static void assertRejectedEnvironment(String key, String value) {
        Map<String, String> env = new LinkedHashMap<>();
        env.put(key, value);
        try {
            specWithEnvironment(env);
            fail("expected rejection of env entry: " + key);
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    private static void assertRejectedWorkingDirectory(String dir) {
        try {
            new ComposeServiceSpec("web", "img",
                    Collections.<String>emptyList(), Collections.<String>emptyList(),
                    Collections.<String, String>emptyMap(),
                    Collections.<ComposeVolumeMapping>emptyList(),
                    Collections.<ComposePortMapping>emptyList(),
                    dir, Collections.<String>emptyList(), null);
            fail("expected rejection of workingDirectory: " + dir);
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    private static void assertRejectedSpec(List<ComposeVolumeMapping> volumes) {
        try {
            specWithVolumes(volumes);
            fail("expected rejection of volumes: " + volumes);
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    private static void assertRejectedSpecPorts(List<ComposePortMapping> ports) {
        try {
            specWithPorts(ports);
            fail("expected rejection of ports: " + ports);
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    private static void assertRejectedDependsOn(List<String> dependsOn) {
        try {
            new ComposeServiceSpec("web", "img",
                    Collections.<String>emptyList(), Collections.<String>emptyList(),
                    Collections.<String, String>emptyMap(),
                    Collections.<ComposeVolumeMapping>emptyList(),
                    Collections.<ComposePortMapping>emptyList(),
                    null, dependsOn, null);
            fail("expected rejection of dependsOn: " + dependsOn);
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    private static void assertUnmodifiable(List<?> list) {
        try {
            list.add(null);
            fail("expected unmodifiable list");
        } catch (UnsupportedOperationException expected) {
            // expected
        }
    }
}
