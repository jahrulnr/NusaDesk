package gh.nusashell.nusadesk.infrastructure.service;

import gh.nusashell.nusadesk.domain.session.SessionSnapshot;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Test;

public class RuntimeWorkloadRegistryTest {

    private final RuntimeWorkloadRegistry registry = RuntimeWorkloadRegistry.getInstance();

    @After
    public void tearDown() {
        registry.unregister();
    }

    @Test
    public void isNotRegisteredByDefault() {
        registry.unregister();
        assertFalse(registry.isRegistered());
        assertNull(registry.get());
    }

    @Test
    public void registerMakesWorkloadAvailable() {
        RuntimeWorkload workload = new FakeWorkload("ubuntu-base-arm64/ssh");
        registry.register(workload);
        assertTrue(registry.isRegistered());
        assertSame(workload, registry.get());
    }

    @Test
    public void registerReplacesPreviousWorkload() {
        RuntimeWorkload first = new FakeWorkload("first");
        RuntimeWorkload second = new FakeWorkload("second");
        registry.register(first);
        registry.register(second);
        assertSame(second, registry.get());
    }

    @Test
    public void unregisterClearsWorkload() {
        registry.register(new FakeWorkload("ubuntu-base-arm64/ssh"));
        registry.unregister();
        assertFalse(registry.isRegistered());
        assertNull(registry.get());
    }

    @Test(expected = IllegalArgumentException.class)
    public void registerNullThrows() {
        registry.register(null);
    }

    private static final class FakeWorkload implements RuntimeWorkload {
        private final String id;

        FakeWorkload(String id) {
            this.id = id;
        }

        @Override
        public String workloadId() {
            return id;
        }

        @Override
        public void start(SessionSnapshot session, WorkloadListener listener) {
        }

        @Override
        public void stop(WorkloadListener listener) {
        }
    }
}
