package gh.nusashell.nusadesk.infrastructure.ssh;

import gh.nusashell.nusadesk.domain.session.HostKeyFingerprint;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;

import org.apache.sshd.common.config.keys.KeyUtils;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class SshHostKeyFingerprintCodecTest {

    @Test
    public void fingerprintMatchesMinaOpenSshFormat() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        PublicKey key = pair.getPublic();

        HostKeyFingerprint fingerprint = SshHostKeyFingerprintCodec.toFingerprint(key);

        assertNotNull(fingerprint);
        // MINA's getFingerPrint returns the OpenSSH SHA256:<base64> form.
        assertEquals(KeyUtils.getFingerPrint(key), fingerprint.getValue());
        assertTrue("expected SHA256: prefix, got " + fingerprint.getValue(),
                fingerprint.getValue().startsWith("SHA256:"));
    }

    @Test
    public void rejectsNullKey() {
        try {
            SshHostKeyFingerprintCodec.toFingerprint(null);
            org.junit.Assert.fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }
}
