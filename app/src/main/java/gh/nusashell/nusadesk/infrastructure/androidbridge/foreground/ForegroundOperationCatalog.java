package gh.nusashell.nusadesk.infrastructure.androidbridge.foreground;

import java.util.Map;

/**
 * Registry of the foreground operations the bridge can run, keyed by
 * {@link ForegroundOperation#kind()}.
 *
 * <p>This is the single registration point: adding an interactive
 * capability (dialog, fingerprint, SAF picker, share sheet, speech
 * recognition, NFC reader) means adding its {@link ForegroundOperation}
 * file plus one line here, which keeps parallel module work from touching
 * the host, the activity, or each other.</p>
 */
final class ForegroundOperationCatalog {
    private static final Map<String, ForegroundOperation> OPERATIONS = Map.of(
            PermissionForegroundOperation.KIND, new PermissionForegroundOperation(),
            CallForegroundOperation.KIND, new CallForegroundOperation(),
            SafForegroundOperation.KIND, new SafForegroundOperation(),
            ShareForegroundOperation.KIND, new ShareForegroundOperation(),
            SpeechForegroundOperation.KIND, new SpeechForegroundOperation(),
            DialogForegroundOperation.KIND, new DialogForegroundOperation(),
            CaptureForegroundOperation.KIND, new CaptureForegroundOperation(),
            NfcForegroundOperation.KIND, new NfcForegroundOperation(),
            FingerprintForegroundOperation.KIND, new FingerprintForegroundOperation(),
            BluetoothConsentForegroundOperation.KIND,
            new BluetoothConsentForegroundOperation());

    private ForegroundOperationCatalog() {
    }

    /** Every registered operation, keyed by kind. */
    static Map<String, ForegroundOperation> operations() {
        return OPERATIONS;
    }
}
