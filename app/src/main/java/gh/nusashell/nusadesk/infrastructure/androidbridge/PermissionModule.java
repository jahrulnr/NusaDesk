package gh.nusashell.nusadesk.infrastructure.androidbridge;

import android.content.Context;
import android.content.Intent;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import gh.nusashell.nusadesk.infrastructure.androidbridge.foreground.CapabilityForegroundHost;
import gh.nusashell.nusadesk.infrastructure.androidbridge.foreground.PermissionForegroundOperation;

/**
 * Permission surface of the capability bridge.
 *
 * <p>{@code bridge.permissions} (no params) reports every manifest-declared
 * permission as {@code name:state} — {@code granted}, {@code denied},
 * {@code required}, or {@code unsupported} — plus a {@code hints} list of
 * {@code name=<settings action>} for anything not granted, so the guest can
 * point the user at the right screen. {@code permission.request} carries
 * {@code permissions} (comma list, manifest-declared only) and {@code mode}
 * ({@code runtime} default, {@code settings}) and runs the
 * {@link PermissionForegroundOperation} through the foreground host with a
 * bounded 120 s wait; success fields are {@code granted} and {@code denied}
 * comma lists.</p>
 *
 * <p>The module never throws into the handler: schema violations throw
 * {@link CapabilityParams.Invalid} (mapped to {@code invalid-argument} by
 * the framework), undeclared names answer {@code permission-unknown}, and
 * the foreground host answers its own typed errors
 * ({@code foreground-busy}, {@code foreground-required},
 * {@code foreground-timeout}, {@code foreground-cancelled}).</p>
 */
public final class PermissionModule implements CapabilityModule {
    public static final String METHOD_PERMISSIONS = "bridge.permissions";
    public static final String METHOD_REQUEST = "permission.request";

    /** Bounded wait for the parked foreground operation, per the contract. */
    private static final long FOREGROUND_TIMEOUT_MILLIS = 120_000L;
    private static final int MAX_PERMISSIONS = 32;
    private static final int MAX_PERMISSION_CHARS = 128;

    private final AndroidPermissionChecker checker;
    private final CapabilityForegroundHost foregroundHost;

    public PermissionModule(Context context) {
        if (context == null) {
            throw new IllegalArgumentException("context must not be null");
        }
        Context application = context.getApplicationContext();
        this.checker = new AndroidPermissionChecker(application);
        this.foregroundHost = new CapabilityForegroundHost(application);
    }

    @Override
    public List<String> methods() {
        return List.of(METHOD_PERMISSIONS, METHOD_REQUEST);
    }

    @Override
    public Set<String> parameterMethods() {
        return Set.of(METHOD_REQUEST);
    }

    @Override
    public AndroidCapabilityProtocol.Response handle(AndroidCapabilityProtocol.Request request) {
        if (METHOD_PERMISSIONS.equals(request.getMethod())) {
            return permissions(request);
        }
        if (METHOD_REQUEST.equals(request.getMethod())) {
            return requestPermission(request);
        }
        return AndroidCapabilityProtocol.Response.error(request.getId(), "unsupported-method");
    }

    @Override
    public void close() {
        foregroundHost.close();
    }

    private AndroidCapabilityProtocol.Response permissions(
            AndroidCapabilityProtocol.Request request) {
        StringBuilder states = new StringBuilder();
        StringBuilder hints = new StringBuilder();
        for (String permission : checker.declaredPermissions()) {
            String state = checker.describe(permission);
            append(states, permission + ":" + state);
            if (!"granted".equals(state)) {
                Intent intent = checker.settingsIntent(permission);
                append(hints, permission + "=" + intent.getAction());
            }
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("permissions", states.toString());
        fields.put("hints", hints.toString());
        return AndroidCapabilityProtocol.Response.success(request.getId(), fields);
    }

    private AndroidCapabilityProtocol.Response requestPermission(
            AndroidCapabilityProtocol.Request request) {
        CapabilityParams params = CapabilityParams.of(request);
        params.rejectUnknown(Set.of("permissions", "mode"));
        List<String> names = params.optionalStringList(
                "permissions", MAX_PERMISSIONS, MAX_PERMISSION_CHARS);
        String mode = params.optionalString("mode", 16, "runtime");
        if (!"runtime".equals(mode) && !"settings".equals(mode)) {
            throw new CapabilityParams.Invalid("unsupported parameter value: mode");
        }
        if (names.isEmpty()) {
            throw new CapabilityParams.Invalid("missing list parameter: permissions");
        }
        for (String name : names) {
            if (!checker.declared(name)) {
                return AndroidCapabilityProtocol.Response.error(
                        request.getId(), "permission-unknown:" + name);
            }
        }
        AndroidCapabilityProtocol.Response result = foregroundHost.execute(
                PermissionForegroundOperation.KIND, request.getParams(),
                FOREGROUND_TIMEOUT_MILLIS);
        // The host answers with a blank request id; attach the real one.
        return result.isOk()
                ? AndroidCapabilityProtocol.Response.success(request.getId(), result.getFields())
                : AndroidCapabilityProtocol.Response.error(request.getId(), result.getError());
    }

    private static void append(StringBuilder list, String entry) {
        if (list.length() > 0) {
            list.append(',');
        }
        list.append(entry);
    }
}
