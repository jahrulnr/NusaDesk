package gh.nusashell.nusadesk.infrastructure.runtime;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;

import gh.nusashell.nusadesk.application.runtime.RuntimeStateStore;
import gh.nusashell.nusadesk.domain.runtime.RuntimeSnapshot;
import gh.nusashell.nusadesk.domain.runtime.RuntimeState;

import java.io.File;

/** Small app-private state adapter for the first single-profile vertical slice. */
public final class AndroidRuntimeStateStore implements RuntimeStateStore {
    private static final String PREFERENCES = "runtime_state";
    private final Context context;
    private final SharedPreferences preferences;

    public AndroidRuntimeStateStore(Context context) {
        this.context = context.getApplicationContext();
        preferences = this.context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    @Override
    public RuntimeSnapshot load(String appId) {
        String prefix = prefix(appId);
        String stateValue;
        try {
            stateValue = preferences.getString(prefix + "state", null);
        } catch (ClassCastException exception) {
            clear(appId);
            return null;
        }
        if (stateValue == null) {
            return null;
        }
        try {
            RuntimeSnapshot snapshot = new RuntimeSnapshot(
                    appId,
                    RuntimeState.valueOf(stateValue),
                    preferences.getString(prefix + "detail", ""),
                    preferences.getInt(prefix + "port", 0),
                    preferences.getLong(prefix + "updatedAt", 0L));
            if (snapshot.getState() == RuntimeState.READY && !activeRootfsExists(appId)) {
                RuntimeSnapshot missingRuntime = new RuntimeSnapshot(
                        appId,
                        RuntimeState.FAILED,
                        "Installed metadata has no active runtime files. Retry installation.",
                        0,
                        System.currentTimeMillis());
                save(missingRuntime);
                return missingRuntime;
            }
            return snapshot;
        } catch (IllegalArgumentException | ClassCastException exception) {
            // Corrupt local metadata must not make the UI claim a live runtime.
            clear(appId);
            return null;
        }
    }

    @Override
    @SuppressLint("ApplySharedPref")
    public void save(RuntimeSnapshot snapshot) {
        String prefix = prefix(snapshot.getAppId());
        boolean committed = preferences.edit()
                .putString(prefix + "state", snapshot.getState().name())
                .putString(prefix + "detail", snapshot.getDetail())
                .putInt(prefix + "port", snapshot.getPort())
                .putLong(prefix + "updatedAt", snapshot.getUpdatedAtEpochMillis())
                .commit();
        if (!committed) {
            throw new IllegalStateException("could not persist runtime state");
        }
    }

    @Override
    @SuppressLint("ApplySharedPref")
    public void clear(String appId) {
        String prefix = prefix(appId);
        preferences.edit()
                .remove(prefix + "state")
                .remove(prefix + "detail")
                .remove(prefix + "port")
                .remove(prefix + "updatedAt")
                .commit();
    }

    private boolean activeRootfsExists(String appId) {
        File active = new File(
                new File(new File(context.getFilesDir(), "linux-wrapper"), "runtimes"),
                appId + "/active");
        return new File(active, "etc/os-release").isFile()
                && new File(active, "usr/bin/sh").exists();
    }

    private static String prefix(String appId) {
        if (appId == null || appId.trim().isEmpty()) {
            throw new IllegalArgumentException("appId must not be blank");
        }
        return appId.replaceAll("[^A-Za-z0-9._-]", "_") + ".";
    }
}
