package gh.nusashell.nusadesk.infrastructure.ssh;

import gh.nusashell.nusadesk.domain.session.HostKeyFingerprint;
import gh.nusashell.nusadesk.infrastructure.session.KeystoreBridgeCredential;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.TreeSet;

import org.junit.Test;

/**
 * Verifies the production SSH configuration boundary: the app can only build a
 * session for the fixed in-app guest endpoint, using the app-managed Keystore
 * credential, and it accepts only the host key the runtime host pinned.
 */
public class LocalSshSessionFactoryTest {

    @Test
    public void createsAConfigForTheFixedEndpointAndAppCredential() {
        SshSessionConfig config = LocalSshSessionFactory.create(80, 24);

        assertEquals(LocalSshEndpoint.HOST, config.getHost());
        assertEquals(LocalSshEndpoint.PORT, config.getPort());
        assertEquals("root", config.getUsername());
        assertEquals(KeystoreBridgeCredential.CREDENTIAL_ID, config.getCredentialId());
        assertEquals(LocalSshEndpoint.HOST_KEY_SCOPE, config.hostKeyScope());
        assertEquals(80, config.getInitialCols());
        assertEquals(24, config.getInitialRows());
        assertEquals(SshSessionConfig.DEFAULT_TERMINAL_TYPE, config.getTerminalType());
        assertTrue(config.getConnectTimeoutMillis() > 0);
        assertTrue(config.getAuthTimeoutMillis() > 0);
        assertTrue(config.getChannelOpenTimeoutMillis() > 0);
    }

    @Test
    public void ptyDimensionsRemainTheOnlyVariable() {
        SshSessionConfig wide = LocalSshSessionFactory.create(132, 43);

        assertEquals(132, wide.getInitialCols());
        assertEquals(43, wide.getInitialRows());
        assertEquals(LocalSshEndpoint.HOST, wide.getHost());
        assertEquals(LocalSshEndpoint.PORT, wide.getPort());
    }

    /**
     * API-shape lock. A host is a {@code String} and a port is an {@code int};
     * the factory must expose no way to pass either. If a later change adds such
     * a parameter, this test fails on purpose: the product is not a general SSH
     * client, so that change needs its own decision record.
     */
    @Test
    public void productionApiCannotAcceptAHostOrPort() throws Exception {
        TreeSet<String> publicMethods = new TreeSet<>();
        for (Method method : LocalSshSessionFactory.class.getDeclaredMethods()) {
            if (!Modifier.isPublic(method.getModifiers())) {
                continue;
            }
            publicMethods.add(method.getName());
            for (Class<?> parameter : method.getParameterTypes()) {
                assertNotSame("a local-only factory must not accept a host",
                        String.class, parameter);
            }
        }
        assertEquals(new TreeSet<>(Arrays.asList("create", "pinnedHostKeyOnly")), publicMethods);

        Method create = LocalSshSessionFactory.class.getMethod("create", int.class, int.class);
        assertArrayEquals("only PTY dimensions may vary",
                new Class<?>[]{int.class, int.class}, create.getParameterTypes());
        assertEquals(SshSessionConfig.class, create.getReturnType());

        Method trust = LocalSshSessionFactory.class.getMethod("pinnedHostKeyOnly");
        assertEquals(0, trust.getParameterTypes().length);
        assertEquals(FirstHostKeyTrustCallback.class, trust.getReturnType());

        Constructor<?>[] constructors = LocalSshSessionFactory.class.getDeclaredConstructors();
        assertEquals(1, constructors.length);
        assertTrue("the factory must not be constructible",
                Modifier.isPrivate(constructors[0].getModifiers()));
        assertEquals(0, constructors[0].getParameterTypes().length);
    }

    @Test
    public void refusesAHostKeyThatWasNotPinnedByTheRuntimeHost() {
        FirstHostKeyTrustCallback callback = LocalSshSessionFactory.pinnedHostKeyOnly();
        HostKeyFingerprint presented = new HostKeyFingerprint("0".repeat(64));

        assertFalse("an unpinned key on the local endpoint must never be trusted",
                callback.trustNewHost(LocalSshEndpoint.HOST_KEY_SCOPE, presented));
    }
}
