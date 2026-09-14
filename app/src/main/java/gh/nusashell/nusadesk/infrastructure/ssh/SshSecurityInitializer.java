package gh.nusashell.nusadesk.infrastructure.ssh;

import android.content.Context;

import java.nio.file.Path;
import java.security.Security;
import java.util.function.Supplier;

import org.apache.sshd.common.util.OsUtils;
import org.apache.sshd.common.util.io.PathUtils;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

/**
 * One-time Android initialisation required before any {@link SshClientBridge}
 * session starts.
 *
 * <p>Apache MINA SSHD is not officially tested on Android (see the project's
 * {@code docs/android.md}). It relies on two system properties that Android
 * omits ({@code user.home}, {@code user.dir}) and on a JCA security provider
 * for SSH cryptography. This class performs the documented workarounds so the
 * bridge can load keys and select a provider on Android 10+ (API 29+):</p>
 *
 * <ul>
 *   <li>Mark the runtime as Android via {@link OsUtils#setAndroid(Boolean)}.</li>
 *   <li>Redirect {@code user.home}/{@code user.dir} resolution to app-private
 *       storage via {@link PathUtils#setUserHomeFolderResolver(Supplier)} and
 *       {@link OsUtils#setCurrentWorkingDirectoryResolver(Supplier)}.</li>
 *   <li>Register Bouncy Castle as the JCA provider (the Android-bundled BC
 *       provider was removed in API 28, so on API 29+ this is additive).</li>
 * </ul>
 *
 * <p>The presentation layer must call {@link #initialize(Context)} exactly once
 * from {@code Application.onCreate}, before constructing or starting an
 * {@link SshClientBridge}. It is idempotent and thread-safe.</p>
 */
public final class SshSecurityInitializer {

    private static volatile boolean initialized;

    private SshSecurityInitializer() {
    }

    /**
     * Perform MINA SSHD Android initialisation. Safe to call more than once.
     *
     * @param context any application/activity context; uses app-private files dir
     */
    public static void initialize(Context context) {
        if (initialized) {
            return;
        }
        synchronized (SshSecurityInitializer.class) {
            if (initialized) {
                return;
            }
            Context app = context.getApplicationContext();
            Path filesPath = app.getFilesDir().toPath();

            OsUtils.setAndroid(Boolean.TRUE);
            System.setProperty("user.home", filesPath.toString());
            PathUtils.setUserHomeFolderResolver(() -> filesPath);
            System.setProperty("user.dir", filesPath.toString());
            OsUtils.setCurrentWorkingDirectoryResolver(() -> filesPath);

            // The Android-bundled "BC" provider is gone since API 28; remove any
            // stale registration then add the shipped Bouncy Castle provider.
            Security.removeProvider("BC");
            Security.removeProvider("BouncyCastle");
            if (Security.getProvider("BC") == null) {
                Security.addProvider(new BouncyCastleProvider());
            }

            initialized = true;
        }
    }
}
