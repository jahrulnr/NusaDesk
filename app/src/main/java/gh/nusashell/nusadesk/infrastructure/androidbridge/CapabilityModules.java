package gh.nusashell.nusadesk.infrastructure.androidbridge;

import android.content.Context;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the capability modules the bridge serves, in capability-list order.
 *
 * <p>This is the single registration point: adding a capability domain means
 * adding its module here, which keeps parallel module work from touching the
 * bridge, the handler, or each other. Modules are constructed from the
 * application context and are closed with the bridge.</p>
 */
final class CapabilityModules {
    private CapabilityModules() {
    }

    /** Every module the app ships, in the order they are advertised. */
    static List<CapabilityModule> build(Context context) {
        if (context == null) {
            throw new IllegalArgumentException("context must not be null");
        }
        List<CapabilityModule> modules = new ArrayList<>();
        modules.add(new DeviceStateModule(context));
        modules.add(new PermissionModule(context));
        modules.add(new CommsModule(context));
        modules.add(new TextModule(context));
        modules.add(new NotificationModule(context));
        modules.add(new SpeechModule(context));
        modules.add(new DialogModule(context));
        modules.add(new CaptureModule(context));
        modules.add(new MediaPlayerModule(context));
        modules.add(new NfcModule(context));
        modules.add(new FingerprintModule(context));
        modules.add(new StorageModule(context));
        modules.add(new SensorCatalogModule(context));
        modules.add(new WifiModule(context));
        modules.add(new InfraredModule(context));
        return List.copyOf(modules);
    }
}
