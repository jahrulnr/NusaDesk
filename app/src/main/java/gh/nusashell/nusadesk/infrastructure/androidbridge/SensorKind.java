package gh.nusashell.nusadesk.infrastructure.androidbridge;

/**
 * The allowlisted one-shot sensor types exposed by the capability bridge.
 *
 * <p>Each kind maps to exactly one fixed request method
 * ({@code sensor.accelerometer} / {@code sensor.gyroscope}) and carries the
 * stable wire name guests see in responses. The class is Android-free so the
 * protocol, adapters, and their tests share one contract.</p>
 */
public enum SensorKind {
    ACCELEROMETER("accelerometer"),
    GYROSCOPE("gyroscope");

    private final String name;

    SensorKind(String name) {
        this.name = name;
    }

    /** Stable wire name; matches the fixed request method suffix. */
    public String getName() {
        return name;
    }
}
