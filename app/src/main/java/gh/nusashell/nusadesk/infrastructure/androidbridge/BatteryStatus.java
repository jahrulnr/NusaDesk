package gh.nusashell.nusadesk.infrastructure.androidbridge;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Immutable snapshot of the Android battery state translated into values that
 * have a useful Linux power-supply equivalent.
 *
 * <p>Unknown numeric values are {@code -1}; an unavailable platform snapshot
 * is represented by {@link #unavailable()}. The class is Android-free so the
 * protocol, fake sysfs projection, and their tests share one contract.</p>
 */
public final class BatteryStatus {
    private final boolean available;
    private final int capacityPercent;
    private final String status;
    private final String health;
    private final String plugged;
    private final boolean present;
    private final long temperatureDeciCelsius;
    private final long voltageMicrovolts;
    private final long currentMicroamps;
    private final long chargeCounterMicroampHours;
    private final long energyNanowattHours;

    public BatteryStatus(boolean available, int capacityPercent, String status,
                         String health, String plugged, boolean present,
                         long temperatureDeciCelsius, long voltageMicrovolts,
                         long currentMicroamps, long chargeCounterMicroampHours,
                         long energyNanowattHours) {
        if (capacityPercent < -1 || capacityPercent > 100) {
            throw new IllegalArgumentException("capacityPercent must be -1..100");
        }
        this.available = available;
        this.capacityPercent = capacityPercent;
        this.status = requireText(status, "status");
        this.health = requireText(health, "health");
        this.plugged = requireText(plugged, "plugged");
        this.present = present;
        this.temperatureDeciCelsius = temperatureDeciCelsius;
        this.voltageMicrovolts = voltageMicrovolts;
        this.currentMicroamps = currentMicroamps;
        this.chargeCounterMicroampHours = chargeCounterMicroampHours;
        this.energyNanowattHours = energyNanowattHours;
    }

    /** A bounded, explicit unavailable snapshot rather than a fabricated reading. */
    public static BatteryStatus unavailable() {
        return new BatteryStatus(false, -1, "unknown", "unknown", "none", false,
                -1L, -1L, -1L, -1L, -1L);
    }

    public boolean isAvailable() {
        return available;
    }

    public int getCapacityPercent() {
        return capacityPercent;
    }

    public String getStatus() {
        return status;
    }

    public String getHealth() {
        return health;
    }

    public String getPlugged() {
        return plugged;
    }

    public boolean isPresent() {
        return present;
    }

    public long getTemperatureDeciCelsius() {
        return temperatureDeciCelsius;
    }

    public long getVoltageMicrovolts() {
        return voltageMicrovolts;
    }

    public long getCurrentMicroamps() {
        return currentMicroamps;
    }

    public long getChargeCounterMicroampHours() {
        return chargeCounterMicroampHours;
    }

    public long getEnergyNanowattHours() {
        return energyNanowattHours;
    }

    /** Fields used by both the RPC response and the Linux projection. */
    public Map<String, Object> responseFields() {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("available", available);
        fields.put("capacity_percent", (long) capacityPercent);
        fields.put("status", status);
        fields.put("health", health);
        fields.put("plugged", plugged);
        fields.put("present", present);
        fields.put("temperature_deci_celsius", temperatureDeciCelsius);
        fields.put("voltage_microvolts", voltageMicrovolts);
        fields.put("current_microamps", currentMicroamps);
        fields.put("charge_counter_microamp_hours", chargeCounterMicroampHours);
        fields.put("energy_nanowatt_hours", energyNanowattHours);
        return fields;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
