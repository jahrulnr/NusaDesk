package gh.nusashell.nusadesk.infrastructure.androidbridge;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;

/**
 * Reads Android's ordinary, permission-free battery broadcast and properties.
 *
 * <p>This adapter never invents a public-DNS-style fallback: if the platform
 * does not return a battery intent it reports an unavailable capability. The
 * returned units match the conventional Linux power-supply units where there
 * is a direct mapping (deci-degrees Celsius, microvolts, microamps, and
 * microamp-hours).</p>
 */
public final class AndroidBatteryStatusProvider implements BatteryStatusSource {
    private final Context context;
    private final BatteryManager batteryManager;

    public AndroidBatteryStatusProvider(Context context) {
        if (context == null) {
            throw new IllegalArgumentException("context must not be null");
        }
        this.context = context.getApplicationContext();
        this.batteryManager = (BatteryManager) this.context.getSystemService(
                Context.BATTERY_SERVICE);
    }

    @Override
    public BatteryStatus read() {
        Intent intent;
        try {
            intent = context.registerReceiver(null,
                    new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        } catch (RuntimeException e) {
            return BatteryStatus.unavailable();
        }
        if (intent == null) {
            return BatteryStatus.unavailable();
        }

        int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        int capacity = level >= 0 && scale > 0
                ? Math.max(0, Math.min(100, (level * 100) / scale))
                : propertyInt(BatteryManager.BATTERY_PROPERTY_CAPACITY, -1);

        int statusCode = intent.getIntExtra(BatteryManager.EXTRA_STATUS,
                BatteryManager.BATTERY_STATUS_UNKNOWN);
        int healthCode = intent.getIntExtra(BatteryManager.EXTRA_HEALTH,
                BatteryManager.BATTERY_HEALTH_UNKNOWN);
        int pluggedCode = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
        int temperature = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1);
        int voltageMillivolts = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1);
        boolean present = intent.getBooleanExtra(BatteryManager.EXTRA_PRESENT, true);

        long voltageMicrovolts = voltageMillivolts < 0
                ? -1L : voltageMillivolts * 1_000L;
        return new BatteryStatus(
                true,
                capacity,
                status(statusCode),
                health(healthCode),
                plugged(pluggedCode),
                present,
                temperature,
                voltageMicrovolts,
                propertyLong(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW),
                propertyLong(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER),
                propertyLong(BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER));
    }

    private int propertyInt(int property, int fallback) {
        if (batteryManager == null) {
            return fallback;
        }
        try {
            int value = batteryManager.getIntProperty(property);
            return value == Integer.MIN_VALUE ? fallback : value;
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private long propertyLong(int property) {
        if (batteryManager == null) {
            return -1L;
        }
        try {
            long value = batteryManager.getLongProperty(property);
            return value == Long.MIN_VALUE ? -1L : value;
        } catch (RuntimeException e) {
            return -1L;
        }
    }

    private static String status(int value) {
        switch (value) {
            case BatteryManager.BATTERY_STATUS_CHARGING:
                return "charging";
            case BatteryManager.BATTERY_STATUS_DISCHARGING:
                return "discharging";
            case BatteryManager.BATTERY_STATUS_FULL:
                return "full";
            case BatteryManager.BATTERY_STATUS_NOT_CHARGING:
                return "not-charging";
            default:
                return "unknown";
        }
    }

    private static String health(int value) {
        switch (value) {
            case BatteryManager.BATTERY_HEALTH_GOOD:
                return "good";
            case BatteryManager.BATTERY_HEALTH_OVERHEAT:
                return "overheat";
            case BatteryManager.BATTERY_HEALTH_DEAD:
                return "dead";
            case BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE:
                return "over-voltage";
            case BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE:
                return "unspecified-failure";
            case BatteryManager.BATTERY_HEALTH_COLD:
                return "cold";
            default:
                return "unknown";
        }
    }

    private static String plugged(int value) {
        switch (value) {
            case BatteryManager.BATTERY_PLUGGED_AC:
                return "ac";
            case BatteryManager.BATTERY_PLUGGED_USB:
                return "usb";
            case BatteryManager.BATTERY_PLUGGED_WIRELESS:
                return "wireless";
            case BatteryManager.BATTERY_PLUGGED_DOCK:
                return "dock";
            default:
                return "none";
        }
    }
}
