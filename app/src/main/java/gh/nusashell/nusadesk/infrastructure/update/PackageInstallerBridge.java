package gh.nusashell.nusadesk.infrastructure.update;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.PackageInstaller;
import android.os.Build;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Hands a verified update APK to the platform {@link PackageInstaller} as an
 * install session (ADR-0038, assisted install flow).
 *
 * <p>{@link SessionParams#MODE_FULL_INSTALL} is used because an update is a
 * complete APK, not a split diff: the platform stages the whole artifact
 * inside its own session storage. The bytes never leave app-private handling
 * — they are copied into the session stream, so no world-readable copy or
 * {@code FileProvider} grant is needed.</p>
 *
 * <p>This app never installs silently: {@link PackageInstaller.Session#commit}
 * only stages the session. The platform then reports
 * {@link PackageInstaller#STATUS_PENDING_USER_ACTION} with a confirmation
 * intent the app launches, and the user taps Install in the system dialog.
 * The session is abandoned on any staging failure so a half-written APK can
 * never reach the installer.</p>
 *
 * <p>The status {@link PendingIntent} carries an app-chosen random token in
 * {@link #EXTRA_STATUS_TOKEN}. Status broadcasts are not authenticated by the
 * platform — any app can send one with our action — so the coordinator must
 * compare the token before trusting {@link #parseStatus(Intent)} output; a
 * spoofed broadcast must not be able to fake a successful install.</p>
 */
public final class PackageInstallerBridge {

    /**
     * Extra on the status broadcast carrying the app-chosen token that
     * authenticates the result. Value is package-scoped so it can never
     * collide with platform extras.
     */
    public static final String EXTRA_STATUS_TOKEN =
            "gh.nusashell.nusadesk.update.STATUS_TOKEN";

    /**
     * Extra on the status broadcast carrying the session id this app
     * recorded when the PendingIntent was built (informational; the
     * platform-reported {@link PackageInstaller#EXTRA_SESSION_ID} fill-in is
     * authoritative).
     */
    public static final String EXTRA_SESSION_ID =
            "gh.nusashell.nusadesk.update.SESSION_ID";

    /** Stream name inside the install session; arbitrary but stable. */
    private static final String APK_STREAM_NAME = "nusadesk-update-apk";

    /**
     * Wire name of the confirmation intent the platform fills into a
     * {@link PackageInstaller#STATUS_PENDING_USER_ACTION} broadcast. The
     * platform's {@code EXTRA_INTENT} constant is a system API not present in
     * the SDK stubs; the wire key is the standard intent extra name, verified
     * on-device (S10e, API 31): the broadcast carried the confirmation under
     * {@code android.intent.extra.INTENT}.
     */
    private static final String PLATFORM_EXTRA_INTENT =
            "android.intent.extra.INTENT";

    private static final int COPY_BUFFER_BYTES = 8 * 1024;

    private PackageInstallerBridge() {
    }

    /**
     * Stages {@code apk} into a new install session and commits it.
     *
     * <p>After commit the platform owns the session: it sends the status
     * broadcast described by {@code statusSender} — first
     * {@link PackageInstaller#STATUS_PENDING_USER_ACTION} with the
     * confirmation activity the app must launch, then the terminal result.
     * This method only throws for staging failures; the install outcome
     * arrives exclusively through the broadcast.</p>
     *
     * @param context      any context of this app
     * @param apk          the digest-verified APK in app-private storage
     * @param statusSender sender from {@link #buildStatusPendingIntent}
     * @return the created session id
     * @throws IOException when staging fails; the session is abandoned before
     *                     the exception propagates
     */
    public static int stageApk(Context context, File apk, IntentSender statusSender)
            throws IOException {
        if (context == null || apk == null || statusSender == null) {
            throw new IllegalArgumentException(
                    "context, apk and statusSender must not be null");
        }
        PackageInstaller installer = context.getPackageManager().getPackageInstaller();
        int sessionId = -1;
        PackageInstaller.Session session = null;
        try {
            sessionId = installer.createSession(new PackageInstaller.SessionParams(
                    PackageInstaller.SessionParams.MODE_FULL_INSTALL));
            session = installer.openSession(sessionId);
            try (InputStream in = new FileInputStream(apk);
                    OutputStream out = session.openWrite(
                            APK_STREAM_NAME, 0, declaredLength(apk))) {
                byte[] buffer = new byte[COPY_BUFFER_BYTES];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
                session.fsync(out);
            }
            session.commit(statusSender);
            return sessionId;
        } catch (IOException | RuntimeException e) {
            if (sessionId != -1) {
                installer.abandonSession(sessionId);
            }
            throw e;
        } finally {
            if (session != null) {
                session.close();
            }
        }
    }

    /**
     * Builds the broadcast PendingIntent the platform fires with the session
     * outcome. The intent is action-only (no component), scoped to this
     * package, and marked mutable on API 31+ because the system writes the
     * status extras into it.
     *
     * @param action    the app-private broadcast action the coordinator's
     *                  dynamically registered receiver filters on
     * @param token     random per-install token; the receiver must compare
     *                  {@link #EXTRA_STATUS_TOKEN} against it
     * @param sessionId session to embed, or {@code 0} when unknown — the
     *                  PendingIntent is usually built before the session
     *                  exists and {@link PackageInstaller#EXTRA_SESSION_ID}
     *                  reports the real id on each broadcast
     */
    public static PendingIntent buildStatusPendingIntent(
            Context context, String action, String token, int sessionId) {
        if (context == null || action == null || token == null) {
            throw new IllegalArgumentException(
                    "context, action and token must not be null");
        }
        Intent intent = new Intent(action)
                .setPackage(context.getPackageName())
                .putExtra(EXTRA_STATUS_TOKEN, token)
                .putExtra(EXTRA_SESSION_ID, sessionId);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // The system mutates this intent with the status extras.
            flags |= PendingIntent.FLAG_MUTABLE;
        }
        return PendingIntent.getBroadcast(context, 0, intent, flags);
    }

    /**
     * Parses a status broadcast. Equivalent to {@link InstallStatus#from};
     * returns {@code null} when the intent carries no
     * {@link PackageInstaller#EXTRA_STATUS} (e.g. an unrelated broadcast).
     */
    public static InstallStatus parseStatus(Intent intent) {
        return InstallStatus.from(intent);
    }

    private static long declaredLength(File apk) {
        long length = apk.length();
        return length > 0 ? length : -1L;
    }

    /**
     * Parsed result of a {@link PackageInstaller} status broadcast.
     *
     * <p>{@link PackageInstaller#STATUS_PENDING_USER_ACTION} is not a
     * failure: for a normal app it is the expected first status and carries
     * the system confirmation intent in the platform's
     * {@code android.content.pm.extra.INTENT} extra, which the app must
     * launch so the user can confirm the install. Terminal failures keep
     * the platform message for diagnostics.</p>
     */
    public static final class InstallStatus {

        private final int code;
        private final int sessionId;
        private final String message;
        private final boolean userAborted;
        private final Intent confirmationIntent;

        private InstallStatus(int code, int sessionId, String message,
                Intent confirmationIntent) {
            this.code = code;
            this.sessionId = sessionId;
            this.message = message;
            this.userAborted = code == PackageInstaller.STATUS_FAILURE_ABORTED;
            this.confirmationIntent = confirmationIntent;
        }

        /**
         * Reads the platform extras; {@code null} when the intent carries no
         * {@link PackageInstaller#EXTRA_STATUS}.
         */
        public static InstallStatus from(Intent intent) {
            if (intent == null || !intent.hasExtra(PackageInstaller.EXTRA_STATUS)) {
                return null;
            }
            int code = intent.getIntExtra(
                    PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
            int sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1);
            String message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            Intent confirmation = null;
            if (code == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                confirmation = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                        ? intent.getParcelableExtra(PLATFORM_EXTRA_INTENT, Intent.class)
                        : (Intent) intent.getParcelableExtra(PLATFORM_EXTRA_INTENT);
            }
            return new InstallStatus(code, sessionId, message, confirmation);
        }

        /** The raw {@code PackageInstaller#STATUS_*} code. */
        public int getCode() {
            return code;
        }

        /** The session the platform reports, or {@code -1} when absent. */
        public int getSessionId() {
            return sessionId;
        }

        /** The platform status message, or {@code null}. */
        public String getMessage() {
            return message;
        }

        /** True for {@link PackageInstaller#STATUS_SUCCESS}. */
        public boolean isSuccess() {
            return code == PackageInstaller.STATUS_SUCCESS;
        }

        /**
         * True for {@link PackageInstaller#STATUS_FAILURE_ABORTED} — the user
         * declined the system confirmation; the verified cache file is kept
         * so the next Install tap can skip the download.
         */
        public boolean isUserAborted() {
            return userAborted;
        }

        /** True when the platform asks the app to launch the confirmation UI. */
        public boolean isPendingUserAction() {
            return code == PackageInstaller.STATUS_PENDING_USER_ACTION;
        }

        /** The confirmation activity to launch when {@link #isPendingUserAction()}. */
        public Intent getConfirmationIntent() {
            return confirmationIntent;
        }
    }
}
