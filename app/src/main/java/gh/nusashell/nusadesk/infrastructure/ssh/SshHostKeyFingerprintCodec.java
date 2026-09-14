package gh.nusashell.nusadesk.infrastructure.ssh;

import gh.nusashell.nusadesk.domain.session.HostKeyFingerprint;

import java.security.PublicKey;

import org.apache.sshd.common.config.keys.KeyUtils;

/**
 * Converts an SSH server {@link PublicKey} into the domain
 * {@link HostKeyFingerprint} format.
 *
 * <p>Delegates to Apache MINA SSHD's {@link KeyUtils#getFingerPrint(PublicKey)},
 * which returns the OpenSSH-style {@code SHA256:<base64>} digest. This matches
 * the {@code SHA256:<base64>} branch accepted by {@link HostKeyFingerprint}, so
 * trust records produced here compare equal across reconnects and persist
 * reliably. No homemade fingerprint computation is performed.</p>
 */
public final class SshHostKeyFingerprintCodec {
    private SshHostKeyFingerprintCodec() {
    }

    /**
     * @param serverKey the key presented by the remote SSH server
     * @return a {@link HostKeyFingerprint} wrapping the OpenSSH SHA256 digest
     * @throws IllegalArgumentException if the key is null or the digest is blank
     */
    public static HostKeyFingerprint toFingerprint(PublicKey serverKey) {
        if (serverKey == null) {
            throw new IllegalArgumentException("serverKey must not be null");
        }
        String print = KeyUtils.getFingerPrint(serverKey);
        if (print == null || print.trim().isEmpty()) {
            throw new IllegalArgumentException("computed fingerprint must not be blank");
        }
        return new HostKeyFingerprint(print);
    }
}
