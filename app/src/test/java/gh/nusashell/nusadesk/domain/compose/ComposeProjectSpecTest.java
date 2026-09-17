package gh.nusashell.nusadesk.domain.compose;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ComposeProjectSpecTest {

    @Test
    public void buildsAProjectWithOneService() {
        ComposeServiceSpec web = service("web");
        ComposeProjectSpec project = new ComposeProjectSpec("mysite", Arrays.asList(web));
        assertEquals("mysite", project.getProjectName());
        assertEquals(1, project.getServices().size());
        assertSame(web, project.getService("web"));
        assertNull(project.getService("missing"));
    }

    @Test
    public void preservesDeclaredServiceOrder() {
        ComposeProjectSpec project = new ComposeProjectSpec("mysite",
                Arrays.asList(service("db"), service("web"), service("cache")));
        assertEquals(Arrays.asList("db", "web", "cache"), serviceNames(project));
    }

    @Test
    public void resolvesDependenciesAcrossServices() {
        ComposeProjectSpec project = new ComposeProjectSpec("mysite",
                Arrays.asList(service("web", "db", "cache"), service("db"), service("cache")));
        assertEquals(Arrays.asList("db", "cache"),
                project.getService("web").getDependsOn());
    }

    @Test
    public void acceptsADiamondDependencyShape() {
        // web -> {api, worker} -> db is a DAG, not a cycle.
        ComposeProjectSpec project = new ComposeProjectSpec("mysite",
                Arrays.asList(
                        service("web", "api", "worker"),
                        service("api", "db"),
                        service("worker", "db"),
                        service("db")));
        assertEquals(4, project.getServices().size());
    }

    @Test
    public void rejectsAMissingOrInvalidProjectName() {
        assertRejectedProjectName(null);
        assertRejectedProjectName("");
        assertRejectedProjectName("   ");
        assertRejectedProjectName("my site");
        assertRejectedProjectName("my/site");
        assertRejectedProjectName(".mysite");
        assertRejectedProjectName("-mysite");
    }

    @Test
    public void rejectsNullEmptyAndNullContainingServiceLists() {
        try {
            new ComposeProjectSpec("mysite", null);
            fail("expected rejection of null services");
        } catch (IllegalArgumentException expected) {
            // expected
        }
        try {
            new ComposeProjectSpec("mysite", Collections.<ComposeServiceSpec>emptyList());
            fail("expected rejection of empty services");
        } catch (IllegalArgumentException expected) {
            // expected
        }
        try {
            new ComposeProjectSpec("mysite",
                    Arrays.asList(service("web"), null));
            fail("expected rejection of null service element");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    @Test
    public void rejectsDuplicateServiceNames() {
        try {
            new ComposeProjectSpec("mysite",
                    Arrays.asList(service("web"), service("web")));
            fail("expected rejection of duplicate service name");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    @Test
    public void rejectsDependenciesOnUnknownServices() {
        try {
            new ComposeProjectSpec("mysite",
                    Arrays.asList(service("web", "ghost")));
            fail("expected rejection of unknown dependency");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    @Test
    public void rejectsADirectDependencyCycle() {
        try {
            new ComposeProjectSpec("mysite",
                    Arrays.asList(service("a", "b"), service("b", "a")));
            fail("expected rejection of a -> b -> a cycle");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    @Test
    public void rejectsAnIndirectDependencyCycle() {
        try {
            new ComposeProjectSpec("mysite",
                    Arrays.asList(
                            service("a", "b"),
                            service("b", "c"),
                            service("c", "a"),
                            service("standalone")));
            fail("expected rejection of a -> b -> c -> a cycle");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    @Test
    public void servicesListIsImmutable() {
        List<ComposeServiceSpec> specs =
                new ArrayList<>(Arrays.asList(service("web"), service("db")));
        ComposeProjectSpec project = new ComposeProjectSpec("mysite", specs);
        specs.add(service("cache"));
        assertEquals(2, project.getServices().size());
        try {
            project.getServices().add(service("cache"));
            fail("expected unmodifiable services list");
        } catch (UnsupportedOperationException expected) {
            // expected
        }
    }

    @Test
    public void equalityIsByValue() {
        ComposeProjectSpec first = new ComposeProjectSpec("mysite",
                Arrays.asList(service("web"), service("db")));
        ComposeProjectSpec same = new ComposeProjectSpec("mysite",
                Arrays.asList(service("web"), service("db")));
        ComposeProjectSpec otherName = new ComposeProjectSpec("other",
                Arrays.asList(service("web"), service("db")));
        ComposeProjectSpec otherOrder = new ComposeProjectSpec("mysite",
                Arrays.asList(service("db"), service("web")));
        assertEquals(first, same);
        assertEquals(first.hashCode(), same.hashCode());
        assertNotEquals(first, otherName);
        assertNotEquals(first, otherOrder);
    }

    private static ComposeServiceSpec service(String name, String... dependsOn) {
        return new ComposeServiceSpec(
                name, "ubuntu-base-arm64",
                Collections.<String>emptyList(), Collections.<String>emptyList(),
                Collections.<String, String>emptyMap(),
                Collections.<ComposeVolumeMapping>emptyList(),
                Collections.<ComposePortMapping>emptyList(),
                null, Arrays.asList(dependsOn), ComposeRestartPolicy.NO);
    }

    private static List<String> serviceNames(ComposeProjectSpec project) {
        List<String> names = new ArrayList<>();
        for (ComposeServiceSpec spec : project.getServices()) {
            names.add(spec.getServiceName());
        }
        return names;
    }

    private static void assertRejectedProjectName(String name) {
        try {
            new ComposeProjectSpec(name, Arrays.asList(service("web")));
            fail("expected rejection of projectName: " + name);
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }
}
