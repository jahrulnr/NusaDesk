package gh.nusashell.nusadesk.domain.session;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class HostKeyTrustPolicyTest {
    private static final String HOST = "127.0.0.1:2222";
    private static final String FP_A = "SHA256:aaaa";
    private static final String FP_B = "SHA256:bbbb";

    @Test
    public void unknownHostEvaluatesUnknown() {
        assertEquals(HostKeyTrust.UNKNOWN,
                HostKeyTrustPolicy.evaluate(null, new HostKeyFingerprint(FP_A)));
    }

    @Test
    public void matchingKeyEvaluatesVerified() {
        HostKeyRecord known = new HostKeyRecord(
                HOST, new HostKeyFingerprint(FP_A), HostKeyTrust.VERIFIED, 10L);

        assertEquals(HostKeyTrust.VERIFIED,
                HostKeyTrustPolicy.evaluate(known, new HostKeyFingerprint(FP_A)));
    }

    @Test
    public void changedKeyEvaluatesMismatch() {
        HostKeyRecord known = new HostKeyRecord(
                HOST, new HostKeyFingerprint(FP_A), HostKeyTrust.VERIFIED, 10L);

        assertEquals(HostKeyTrust.MISMATCH,
                HostKeyTrustPolicy.evaluate(known, new HostKeyFingerprint(FP_B)));
    }

    @Test
    public void trustCreatesVerifiedRecordOnFirstContact() {
        HostKeyRecord record = HostKeyTrustPolicy.trust(
                null, new HostKeyFingerprint(FP_A), HOST, 10L);

        assertEquals(HostKeyTrust.VERIFIED, record.getTrust());
        assertEquals(new HostKeyFingerprint(FP_A), record.getFingerprint());
        assertEquals(10L, record.getTrustedSinceEpochMillis());
    }

    @Test
    public void trustIsIdempotentForMatchingKey() {
        HostKeyRecord known = new HostKeyRecord(
                HOST, new HostKeyFingerprint(FP_A), HostKeyTrust.VERIFIED, 10L);

        HostKeyRecord record = HostKeyTrustPolicy.trust(
                known, new HostKeyFingerprint(FP_A), HOST, 20L);

        assertEquals(known, record);
    }

    @Test
    public void trustRefusesSilentReplacementOnMismatch() {
        HostKeyRecord known = new HostKeyRecord(
                HOST, new HostKeyFingerprint(FP_A), HostKeyTrust.VERIFIED, 10L);

        assertThrows(HostKeyMismatchException.class, () ->
                HostKeyTrustPolicy.trust(known, new HostKeyFingerprint(FP_B), HOST, 20L));
    }

    @Test
    public void explicitReplacementAfterMismatchSucceeds() {
        HostKeyRecord known = new HostKeyRecord(
                HOST, new HostKeyFingerprint(FP_A), HostKeyTrust.VERIFIED, 10L);

        HostKeyRecord replaced = HostKeyTrustPolicy.replaceAfterMismatch(
                known, new HostKeyFingerprint(FP_B), HOST, 20L);

        assertEquals(HostKeyTrust.VERIFIED, replaced.getTrust());
        assertEquals(new HostKeyFingerprint(FP_B), replaced.getFingerprint());
        assertEquals(20L, replaced.getTrustedSinceEpochMillis());
    }

    @Test
    public void replaceAfterMismatchRequiresExistingRecord() {
        assertThrows(IllegalArgumentException.class, () ->
                HostKeyTrustPolicy.replaceAfterMismatch(
                        null, new HostKeyFingerprint(FP_A), HOST, 10L));
    }

    @Test
    public void evaluateRejectsNullPresentedFingerprint() {
        assertThrows(IllegalArgumentException.class, () ->
                HostKeyTrustPolicy.evaluate(null, null));
    }
}
