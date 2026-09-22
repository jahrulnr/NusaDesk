package gh.nusashell.nusadesk.infrastructure.androidbridge.foreground;

import android.content.pm.PackageManager;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import gh.nusashell.nusadesk.infrastructure.androidbridge.AndroidPermissionChecker;
import gh.nusashell.nusadesk.infrastructure.androidbridge.CapabilityPermission;

/**
 * The {@code permission} foreground operation behind
 * {@code permission.request}.
 *
 * <p>Params: {@code permissions} — a comma list of permission names, each of
 * which must be declared in the manifest (anything else answers
 * {@code permission-unknown:<name>}) — and {@code mode}: {@code runtime}
 * (default) asks through the platform runtime-permission prompt, while
 * {@code settings} opens the Settings screen for the first not-yet-granted
 * permission instead of prompting (special access has no runtime dialog).</p>
 *
 * <p>Success fields are {@code granted} and {@code denied}, comma lists in
 * request order. {@code settings} mode additionally reports {@code opened},
 * the permission whose Settings screen was shown, and its granted/denied
 * split is the state at the moment the screen opened — the platform gives no
 * callback for what the user toggles there.</p>
 *
 * <p>The instance is a shared catalog entry: per-request state lives in the
 * {@code run} call and its result-handler closure, never in fields.</p>
 */
public final class PermissionForegroundOperation implements ForegroundOperation {
    /** Catalog kind and the operation name {@code permission.request} runs. */
    public static final String KIND = "permission";
    private static final int REQUEST_CODE = 0x4E50;
    private static final int MAX_PERMISSIONS = 32;
    private static final int MAX_PERMISSION_CHARS = 128;

    @Override
    public String kind() {
        return KIND;
    }

    @Override
    public void run(CapabilityForegroundActivity activity, Map<String, Object> params,
                    ResultSink sink) {
        String mode = stringParam(params.get("mode"), "runtime");
        List<String> permissions = listParam(params.get("permissions"));
        if (permissions == null || permissions.isEmpty()
                || (!"runtime".equals(mode) && !"settings".equals(mode))) {
            sink.error("invalid-argument");
            return;
        }
        AndroidPermissionChecker checker = new AndroidPermissionChecker(activity);
        for (String permission : permissions) {
            if (!checker.declared(permission)) {
                sink.error("permission-unknown:" + permission);
                return;
            }
        }
        if ("settings".equals(mode)) {
            openSettings(activity, permissions, checker, sink);
        } else {
            requestRuntime(activity, permissions, checker, sink);
        }
    }

    /**
     * Ask the platform for every not-yet-granted name, then report each
     * requested permission as granted or denied in request order. Names the
     * platform refuses to prompt for (already denied permanently, special
     * access) come back denied in the result bundle.
     */
    private void requestRuntime(CapabilityForegroundActivity activity,
                                List<String> permissions,
                                AndroidPermissionChecker checker,
                                ResultSink sink) {
        List<String> ask = new ArrayList<>();
        for (String permission : permissions) {
            if (checker.check(permission) != CapabilityPermission.GRANTED) {
                ask.add(permission);
            }
        }
        if (ask.isEmpty()) {
            sink.success(resultFields(permissions, List.of()));
            return;
        }
        activity.setPermissionResultHandler((requestCode, names, results) -> {
            if (requestCode != REQUEST_CODE) {
                return;
            }
            List<String> grantedResults = new ArrayList<>();
            for (int i = 0; i < names.length; i++) {
                if (i < results.length
                        && results[i] == PackageManager.PERMISSION_GRANTED) {
                    grantedResults.add(names[i]);
                }
            }
            List<String> granted = new ArrayList<>();
            List<String> denied = new ArrayList<>();
            for (String permission : permissions) {
                if (checker.check(permission) == CapabilityPermission.GRANTED
                        || grantedResults.contains(permission)) {
                    granted.add(permission);
                } else {
                    denied.add(permission);
                }
            }
            sink.success(resultFields(granted, denied));
        });
        try {
            activity.requestPermissions(ask.toArray(new String[0]), REQUEST_CODE);
        } catch (RuntimeException e) {
            sink.error("permission-unavailable");
        }
    }

    /**
     * Open the grant screen for the first not-yet-granted permission, then
     * report the state observed at that moment. When everything is already
     * granted no screen is opened and {@code opened} is omitted rather than
     * fabricated.
     */
    private void openSettings(CapabilityForegroundActivity activity,
                              List<String> permissions,
                              AndroidPermissionChecker checker,
                              ResultSink sink) {
        List<String> granted = new ArrayList<>();
        List<String> denied = new ArrayList<>();
        String opened = null;
        for (String permission : permissions) {
            if (checker.check(permission) == CapabilityPermission.GRANTED) {
                granted.add(permission);
            } else {
                denied.add(permission);
                if (opened == null) {
                    opened = permission;
                }
            }
        }
        if (opened != null) {
            try {
                activity.startActivity(checker.settingsIntent(opened));
            } catch (RuntimeException e) {
                sink.error("permission-unavailable");
                return;
            }
        }
        Map<String, Object> fields = resultFields(granted, denied);
        if (opened != null) {
            fields.put("opened", opened);
        }
        sink.success(fields);
    }

    private static Map<String, Object> resultFields(List<String> granted,
                                                    List<String> denied) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("granted", String.join(",", granted));
        fields.put("denied", String.join(",", denied));
        return fields;
    }

    private static String stringParam(Object value, String fallback) {
        return value instanceof String ? (String) value : fallback;
    }

    /**
     * The bounded comma list the module already validated, re-parsed
     * defensively. Returns null on a shape violation so the caller can
     * answer {@code invalid-argument}.
     */
    private static List<String> listParam(Object value) {
        if (!(value instanceof String) || ((String) value).trim().isEmpty()) {
            return null;
        }
        List<String> permissions = new ArrayList<>();
        for (String part : ((String) value).split(",")) {
            String permission = part.trim();
            if (permission.isEmpty()) {
                continue;
            }
            if (permission.length() > MAX_PERMISSION_CHARS) {
                return null;
            }
            permissions.add(permission);
            if (permissions.size() > MAX_PERMISSIONS) {
                return null;
            }
        }
        return permissions;
    }
}
