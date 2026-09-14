package gh.nusashell.nusadesk.infrastructure.ssh;

import gh.nusashell.nusadesk.application.session.HostKeyTrustStore;
import gh.nusashell.nusadesk.domain.session.HostKeyFingerprint;
import gh.nusashell.nusadesk.domain.session.HostKeyRecord;
import gh.nusashell.nusadesk.domain.session.HostKeyTrust;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class StrictHostKeyVerifierTest {

    private static final String SCOPE = "127.0.0.1:2222";

    private static KeyPair rsaKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    private static final class InMemoryTrustStore implements HostKeyTrustStore {
        final Map<String, HostKeyRecord> records = new HashMap<>();

        @Override
        public HostKeyRecord load(String host) {
            return records.get(host);
        }

        @Override
        public void save(HostKeyRecord record) {
            records.put(record.getHost(), record);
        }

        @Override
        public void clear(String host) {
            records.remove(host);
        }
    }

    @Test
    public void unknownHostAcceptedByCallbackPersistsVerified() throws Exception {
        InMemoryTrustStore store = new InMemoryTrustStore();
        AtomicInteger calls = new AtomicInteger();
        StrictHostKeyVerifier verifier = new StrictHostKeyVerifier(
                store, (host, fp) -> {
                    calls.incrementAndGet();
                    return true;
                }, SCOPE, () -> 100L);

        KeyPair serverKey = rsaKey();
        boolean accepted = verifier.verifyServerKey(null, null, serverKey.getPublic());

        assertTrue(accepted);
        assertEquals(1, calls.get());
        HostKeyRecord stored = store.records.get(SCOPE);
        assertEquals(HostKeyTrust.VERIFIED, stored.getTrust());
        assertEquals(100L, stored.getTrustedSinceEpochMillis());
    }

    @Test
    public void unknownHostRejectedByCallbackStoresNothing() throws Exception {
        InMemoryTrustStore store = new InMemoryTrustStore();
        StrictHostKeyVerifier verifier = new StrictHostKeyVerifier(
                store, (host, fp) -> false, SCOPE, () -> 100L);

        KeyPair serverKey = rsaKey();
        boolean accepted = verifier.verifyServerKey(null, null, serverKey.getPublic());

        assertFalse(accepted);
        assertNull(store.records.get(SCOPE));
    }

    @Test
    public void matchingTrustedKeyIsAcceptedWithoutCallback() throws Exception {
        InMemoryTrustStore store = new InMemoryTrustStore();
        KeyPair serverKey = rsaKey();
        HostKeyFingerprint fp = SshHostKeyFingerprintCodec.toFingerprint(serverKey.getPublic());
        store.save(new HostKeyRecord(SCOPE, fp, HostKeyTrust.VERIFIED, 50L));

        AtomicInteger calls = new AtomicInteger();
        StrictHostKeyVerifier verifier = new StrictHostKeyVerifier(
                store, (host, f) -> {
                    calls.incrementAndGet();
                    return true;
                }, SCOPE, () -> 200L);

        boolean accepted = verifier.verifyServerKey(null, null, serverKey.getPublic());
        assertTrue(accepted);
        assertEquals(0, calls.get());
    }

    @Test
    public void mismatchedKeyIsRejectedWithoutCallback() throws Exception {
        InMemoryTrustStore store = new InMemoryTrustStore();
        KeyPair trusted = rsaKey();
        KeyPair presented = rsaKey();
        HostKeyFingerprint trustedFp = SshHostKeyFingerprintCodec.toFingerprint(trusted.getPublic());
        store.save(new HostKeyRecord(SCOPE, trustedFp, HostKeyTrust.VERIFIED, 50L));

        AtomicInteger calls = new AtomicInteger();
        AtomicInteger rejects = new AtomicInteger();
        StrictHostKeyVerifier verifier = new StrictHostKeyVerifier(
                store, (host, f) -> {
                    calls.incrementAndGet();
                    return true;
                }, SCOPE, () -> 200L, reason -> rejects.incrementAndGet());

        PublicKey presentedKey = presented.getPublic();
        boolean accepted = verifier.verifyServerKey(null, null, presentedKey);

        assertFalse(accepted);
        assertEquals(0, calls.get());
        assertEquals(1, rejects.get());
        // Trusted record must remain unchanged.
        assertEquals(trustedFp, store.records.get(SCOPE).getFingerprint());
    }
}
