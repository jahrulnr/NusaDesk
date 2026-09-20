package gh.nusashell.nusadesk.infrastructure.battery;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.PowerManager;
import android.provider.Settings;

import gh.nusashell.nusadesk.infrastructure.workspace.WorkspaceFolderAccess;

/**
 * The Battery card's bridge to Android's battery-optimization surface: the
 * exemption-state probe plus the two system intents the card's one action can
 * offer.
 *
 * <p>NusaDesk keeps a Linux runtime alive under a user-visible foreground
 * service, and Doze / app standby can still throttle or kill it. Recommending
 * the exemption is the honest mitigation, and the ask is safe: "the user turns
 * off battery optimizations for the app" is itself one of the platform's
 * documented exemptions from background-start restrictions, so a granted
 * exemption strengthens the runtime host service instead of working around a
 * limit. Nothing here runs at launch or asks unprompted — the card only reads
 * state, and the intents fire solely from the card's explicit action
 * button.</p>
 *
 * <p>OEM additions are deliberately out of scope: MIUI's separate Autostart
 * switch, Samsung's sleeping-apps list, and similar screens exist, but a
 * per-OEM deep-link matrix is unmaintainable for one recommendation card
 * (KISS). The platform intents below are the whole surface.</p>
 */
public final class BatteryOptimizationAccess {

    /**
     * onActivityResult request code for the exemption dialogs. Neither dialog
     * returns a result, but the callback is the reliable signal that the
     * dialog closed, so the host can re-render the state row immediately
     * (a dialog-themed screen only pauses the Activity; onStart does not
     * fire when the dialog is dismissed).
     */
    public static final int REQUEST_OPTIMIZATION = 0x5703;

    private final Context context;

    public BatteryOptimizationAccess(Context context) {
        this.context = context.getApplicationContext();
    }

    /**
     * Whether the user already exempted this app from battery optimizations.
     *
     * <p>The probe is permission-free — {@code isIgnoringBatteryOptimizations}
     * is a plain platform-state read. It is still wrapped defensively for the
     * same reason as {@link WorkspaceFolderAccess#hasAllFilesAccess()}: the
     * framework consults system state that has been observed incomplete on some
     * builds, and a state row on a user-visible screen must never take the app
     * down. "Not exempt" is the safe, honest answer — the card then still
     * offers the action, and the next foreground event re-probes.</p>
     */
    public boolean isExempt() {
        try {
            PowerManager power =
                    (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            return power.isIgnoringBatteryOptimizations(context.getPackageName());
        } catch (RuntimeException probeFailed) {
            return false;
        }
    }

    /**
     * The platform's own "let the app ignore battery optimizations" dialog for
     * this app, for use only from the card's explicit action.
     *
     * <p>Starting it requires the already-declared
     * {@code REQUEST_IGNORE_BATTERY_OPTIMIZATIONS} permission (special access
     * via Settings, never assumed). Some platform builds refuse to show the
     * dialog at all, so the caller starts this intent first and falls back to
     * {@link #optimizationSettingsIntent()} when the start fails — the same
     * start-and-fall-back shape the workspace card uses, deliberately without a
     * {@code resolveActivity()} probe.</p>
     */
    public Intent requestExemptionIntent() {
        return new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(Uri.parse("package:" + context.getPackageName()));
    }

    /**
     * The generic battery-optimization list screen (Settings &gt; Special
     * access &gt; Battery optimization), where the user flips this app's entry
     * by hand. It needs no permission, so it is also the fallback when the
     * platform build refuses {@link #requestExemptionIntent()}.
     */
    public Intent optimizationSettingsIntent() {
        return new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
    }
}
