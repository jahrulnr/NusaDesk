package gh.nusashell.nusadesk.infrastructure.homescreen;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;

import android.content.SharedPreferences;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.net.Uri;

import gh.nusashell.nusadesk.R;
import gh.nusashell.nusadesk.infrastructure.webapp.IconBitmapDecoder;

/**
 * Android-launcher pinned shortcuts for launcher entries ("Add to Home
 * screen"). The system owns the shortcut once it lands: NusaDesk keeps no
 * registry of installed entries: it reconciles pins against the loaded
 * registries/catalog and records only disabled ids and icon fingerprints.
 *
 * <p>The icon follows the tile's own source order — the user's image, then
 * the endpoint's favicon, then the kind's bundled vector, then a monogram —
 * the same contract {@code LauncherIconPolicy} names for the tile — and the
 * NusaDesk mark is drawn as a launcher-style badge in the corner so a pinned
 * tile reads as "this app, through NusaDesk" the same way a pinned web app
 * reads "this site, through Chrome".</p>
 */
public final class PinnedAppShortcut {

    /** The badge's share of the icon diameter, like a launcher web-app badge. */
    private static final float BADGE_FRACTION = 0.42f;
    private static final int MONOGRAM_SIZE_PX = 144;
    /** Every shortcut this helper pins carries this prefix in its id. */
    private static final String ID_PREFIX = "nusadesk-entry-";
    /** The ids the app itself disabled — the only disabled set it can trust. */
    private static final String PREFERENCES = "pinned_shortcut";
    private static final String KEY_DISABLED = "disabled_ids";
    /** Per-shortcut fingerprint of the icon sources, for change detection. */
    private static final String KEY_ICON_FINGERPRINT = "icon_fp:";

    private PinnedAppShortcut() {
    }

    /**
     * The icon sources one entry renders from — exactly the inputs the
     * launcher tile uses, carried so pinning and later icon refreshes render
     * the same picture. {@code favicon} may be null before its first fetch.
     */
    public static final class EntryIcon {
        private final String iconUri;
        private final Bitmap favicon;
        private final int iconRes;
        private final String label;

        public EntryIcon(String iconUri, Bitmap favicon, int iconRes, String label) {
            this.iconUri = iconUri;
            this.favicon = favicon;
            this.iconRes = iconRes;
            this.label = label;
        }
    }

    /** Cheap change detector over the icon sources: a new image pick, a
     * re-fetched favicon (a fresh bitmap gets a fresh generation id), a
     * bundled-vector change, or a rename all move it; identical sources do
     * not, so renders never regenerate or re-push icons needlessly. */
    private static String fingerprint(EntryIcon sources) {
        return sources.iconUri + "|" + sources.iconRes + "|" + sources.label
                + "|" + (sources.favicon == null ? 0 : sources.favicon.getGenerationId())
                + "|" + fileRevision(sources.iconUri);
    }

    private static String fileRevision(String token) {
        if (token != null) {
            Uri uri = Uri.parse(token);
            if (uri.getScheme() == null || "file".equals(uri.getScheme())) {
                String path = uri.getScheme() == null ? token : uri.getPath();
                if (path != null) {
                    File file = new File(path);
                    return file.lastModified() + ":" + file.length();
                }
            }
        }
        return "";
    }

    /** True when the device's launcher accepts pinned shortcuts. */
    public static boolean isSupported(Context context) {
        ShortcutManager manager = context.getSystemService(ShortcutManager.class);
        return manager != null && manager.isRequestPinShortcutSupported();
    }

    /**
     * Asks the launcher to pin a shortcut for {@code entryId}, rendering its
     * icon from {@code sources}. The launcher shows its own confirm UI; a
     * {@code false} return means it refused or is unsupported. The
     * deterministic id is what {@link #reconcilePins} later disables when the
     * entry stops existing, and records the icon fingerprint so later source
     * changes are detected.
     */
    public static boolean pin(Context context, String entryId, EntryIcon sources,
            Intent launchIntent) {
        ShortcutManager manager = context.getSystemService(ShortcutManager.class);
        if (manager == null || !manager.isRequestPinShortcutSupported()) {
            return false;
        }
        String shortcutId = ID_PREFIX + entryId;
        ShortcutInfo shortcut = new ShortcutInfo.Builder(context, shortcutId)
                .setShortLabel(sources.label)
                .setIcon(android.graphics.drawable.Icon.createWithBitmap(icon(context, sources)))
                .setIntent(new Intent(launchIntent).addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP))
                .build();
        boolean pinned = manager.requestPinShortcut(shortcut, null);
        if (pinned) {
            context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_ICON_FINGERPRINT + shortcutId, fingerprint(sources))
                    .apply();
        }
        return pinned;
    }

    /** The entry's full pinned icon: resolved base picture plus the badge. */
    private static Bitmap icon(Context context, EntryIcon sources) {
        return badgedIcon(context, baseIcon(context, sources.iconUri,
                sources.favicon, sources.iconRes, sources.label));
    }

    /**
     * Reconciles the launcher's pinned shortcuts with the entries that still
     * exist, and follows icon changes: a pin whose entry is gone is disabled
     * — the launcher removes or dims its homescreen icon and the tap reports
     * the removal message — a pin whose entry came back (a reinstalled Linux
     * app) is re-enabled, and a live pin whose icon sources changed (new
     * custom image, re-fetched favicon, rename) gets its icon and label
     * pushed again with {@code updateShortcuts}. There is no unpin API;
     * disabling is the strongest deletion signal the platform exposes.
     *
     * <p>Which pins are disabled is tracked here rather than read from
     * {@code ShortcutInfo.isEnabled()}: the flag's semantics differ across
     * OEM shortcuts implementations, while the set the app itself disabled is
     * a fact it owns. Disable and enable are idempotent, so the reconciliation
     * is a pure set difference. Icon refreshes only fire when the sources'
     * fingerprint actually moved, so steady-state renders cost nothing; a
     * throttled {@code updateShortcuts} is simply retried by a later render.</p>
     *
     * @param liveEntries the entries the launcher could pin today, keyed by
     *                    entry id with their current icon sources — only ids
     *                    whose source has actually reported, so an unloaded
     *                    catalog is not read as a deletion
     */
    public static void reconcilePins(Context context, Map<String, EntryIcon> liveEntries) {
        reconcilePins(context, liveEntries, true);
    }

    /** An unreported Linux catalog is unknown, rather than an empty installed set. */
    public static void reconcilePins(Context context, Map<String, EntryIcon> liveEntries,
            boolean linuxCatalogLoaded) {
        ShortcutManager manager = context.getSystemService(ShortcutManager.class);
        if (manager == null || liveEntries == null) {
            return;
        }
        SharedPreferences state = context.getSharedPreferences(
                PREFERENCES, Context.MODE_PRIVATE);
        Set<String> disabled = new HashSet<>(
                state.getStringSet(KEY_DISABLED, Collections.emptySet()));
        List<String> stale = new ArrayList<>();
        List<String> revived = new ArrayList<>();
        Map<String, EntryIcon> iconUpdates = new HashMap<>();
        Map<String, Intent[]> intentUpdates = new HashMap<>();
        for (ShortcutInfo info : manager.getPinnedShortcuts()) {
            String id = info.getId();
            if (id == null || !id.startsWith(ID_PREFIX)) {
                continue;
            }
            String entryId = id.substring(ID_PREFIX.length());
            EntryIcon sources = liveEntries.get(entryId);
            // Android may otherwise just foreground the task whose base intent
            // was this finished trampoline, skipping both onCreate/onNewIntent.
            // Migrate old pins too, including Linux pins not scanned yet.
            if (sources != null || !disabled.contains(id)
                    && !linuxCatalogLoaded && entryId.startsWith("linux:")) {
                Intent[] intents = info.getIntents();
                if (intents != null && intents.length > 0) {
                    Intent last = intents[intents.length - 1];
                    if (last.getComponent() != null && PinnedShortcutActivity.class.getName()
                            .equals(last.getComponent().getClassName())
                            && (last.getFlags() & Intent.FLAG_ACTIVITY_CLEAR_TOP) == 0) {
                        intents[intents.length - 1] = new Intent(last).addFlags(
                                Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                        intentUpdates.put(id, intents);
                    }
                }
            }
            if (!linuxCatalogLoaded && entryId.startsWith("linux:")) {
                continue;
            }
            if (sources == null) {
                if (!disabled.contains(id)) {
                    stale.add(id);
                }
            } else {
                if (disabled.contains(id)) {
                    revived.add(id);
                }
                if (!fingerprint(sources).equals(
                        state.getString(KEY_ICON_FINGERPRINT + id, null))) {
                    iconUpdates.put(id, sources);
                }
            }
        }
        if (!stale.isEmpty()) {
            manager.disableShortcuts(stale,
                    context.getString(R.string.launcher_pin_removed));
        }
        if (!revived.isEmpty()) {
            manager.enableShortcuts(revived);
        }
        if (!iconUpdates.isEmpty() || !intentUpdates.isEmpty()) {
            Set<String> updateIds = new HashSet<>(iconUpdates.keySet());
            updateIds.addAll(intentUpdates.keySet());
            List<ShortcutInfo> updates = new ArrayList<>(updateIds.size());
            SharedPreferences.Editor fingerprints = state.edit();
            for (String id : updateIds) {
                ShortcutInfo.Builder update = new ShortcutInfo.Builder(context, id);
                EntryIcon sources = iconUpdates.get(id);
                if (sources != null) {
                    update.setShortLabel(sources.label)
                            .setIcon(android.graphics.drawable.Icon.createWithBitmap(icon(context, sources)));
                }
                Intent[] intents = intentUpdates.get(id);
                if (intents != null) update.setIntents(intents);
                updates.add(update.build());
            }
            if (manager.updateShortcuts(updates)) {
                for (Map.Entry<String, EntryIcon> pending : iconUpdates.entrySet()) {
                    fingerprints.putString(KEY_ICON_FINGERPRINT + pending.getKey(),
                            fingerprint(pending.getValue()));
                }
                fingerprints.apply();
            }
        }
        if (!stale.isEmpty() || !revived.isEmpty()) {
            disabled.addAll(stale);
            disabled.removeAll(revived);
            state.edit().putStringSet(KEY_DISABLED, disabled).apply();
        }
    }

    /**
     * Resolves the entry's icon to a bitmap in the tile's own order: a
     * readable user-image token, then the favicon bitmap, then the bundled
     * vector resource, then a monogram of the label.
     */
    public static Bitmap baseIcon(Context context, String iconUri, Bitmap favicon,
            int iconRes, String label) {
        Bitmap image = decodeImageToken(context, iconUri);
        if (image != null) {
            return image;
        }
        if (favicon != null) {
            return favicon;
        }
        if (iconRes != 0) {
            Drawable vector = context.getDrawable(iconRes);
            if (vector != null) {
                return rasterize(vector, MONOGRAM_SIZE_PX);
            }
        }
        return monogram(context, label);
    }

    /**
     * A stored image token, or {@code null} when it cannot be read any more.
     * Decoding is bounded like the tile's own reads: a user-picked
     * {@code content://} image goes through {@link IconBitmapDecoder#decodePicked}
     * (the favicon byte/dimension/memory budgets), and a file path is sampled
     * down to the launcher's icon size instead of decoded at photo resolution.
     */
    private static Bitmap decodeImageToken(Context context, String iconUri) {
        if (iconUri == null) {
            return null;
        }
        try {
            Uri uri = Uri.parse(iconUri);
            if (uri.getScheme() == null || "file".equals(uri.getScheme())) {
                String path = uri.getScheme() == null ? iconUri : uri.getPath();
                return decodeFileBounded(path, launcherIconSize(context));
            }
            if ("content".equals(uri.getScheme())) {
                return IconBitmapDecoder.decodePicked(context.getContentResolver(), uri);
            }
        } catch (Exception unreadable) {
            return null;
        }
        return null;
    }

    /** A file decoded only as large as the launcher needs it. */
    private static Bitmap decodeFileBounded(String path, int targetSize) {
        if (path == null) {
            return null;
        }
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null;
        }
        int sample = 1;
        while (bounds.outWidth / (sample * 2) >= targetSize
                || bounds.outHeight / (sample * 2) >= targetSize) {
            sample *= 2;
        }
        BitmapFactory.Options decode = new BitmapFactory.Options();
        decode.inSampleSize = sample;
        try {
            return BitmapFactory.decodeFile(path, decode);
        } catch (RuntimeException unreadable) {
            return null;
        }
    }

    /**
     * The app icon with the NusaDesk mark badged in the lower corner, sized
     * to the launcher's large-icon grid. The badge gets a solid plate so the
     * mark stays readable over a busy icon.
     */
    public static Bitmap badgedIcon(Context context, Bitmap base) {
        int size = launcherIconSize(context);
        Bitmap output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(output);
        Paint scale = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        canvas.drawBitmap(base, null, new RectF(0, 0, size, size), scale);

        float badge = size * BADGE_FRACTION;
        float left = size - badge;
        float top = size - badge;
        Paint plate = new Paint(Paint.ANTI_ALIAS_FLAG);
        plate.setColor(Color.WHITE);
        plate.setStyle(Paint.Style.FILL);
        canvas.drawOval(new RectF(left, top, size, size), plate);

        Drawable mark = context.getDrawable(R.drawable.ic_source_nusadesk);
        if (mark != null) {
            float inset = badge * 0.18f;
            mark.setBounds(Math.round(left + inset), Math.round(top + inset),
                    Math.round(size - inset), Math.round(size - inset));
            mark.draw(canvas);
        }
        return output;
    }

    /** A letter on the tile plate — the same fallback a tile paints. */
    private static Bitmap monogram(Context context, String label) {
        Bitmap output = Bitmap.createBitmap(MONOGRAM_SIZE_PX, MONOGRAM_SIZE_PX,
                Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(output);
        float radius = MONOGRAM_SIZE_PX * 0.22f;
        Paint plate = new Paint(Paint.ANTI_ALIAS_FLAG);
        plate.setColor(context.getColor(R.color.tile_icon_plate));
        Path rounded = new Path();
        rounded.addRoundRect(new RectF(0, 0, MONOGRAM_SIZE_PX, MONOGRAM_SIZE_PX),
                radius, radius, Path.Direction.CW);
        canvas.drawPath(rounded, plate);

        // The tile's own monogram rule: the first code point — a surrogate
        // pair survives as one glyph — upper-cased like the tile does.
        String letter = "?";
        if (label != null && !label.trim().isEmpty()) {
            int first = label.trim().codePointAt(0);
            letter = new String(Character.toChars(Character.toUpperCase(first)));
        }
        Paint text = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        text.setColor(context.getColor(R.color.primary));
        text.setTypeface(android.graphics.Typeface.MONOSPACE);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTextSize(MONOGRAM_SIZE_PX * 0.55f);
        float baseline = MONOGRAM_SIZE_PX / 2f - (text.descent() + text.ascent()) / 2f;
        canvas.drawText(letter, MONOGRAM_SIZE_PX / 2f, baseline, text);
        return output;
    }

    private static Bitmap rasterize(Drawable drawable, int size) {
        Bitmap output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(output);
        drawable.setBounds(0, 0, size, size);
        drawable.draw(canvas);
        return output;
    }

    private static int launcherIconSize(Context context) {
        ActivityManager activity = (ActivityManager) context
                .getSystemService(Context.ACTIVITY_SERVICE);
        int size = activity == null ? 0 : activity.getLauncherLargeIconSize();
        return size > 0 ? size : MONOGRAM_SIZE_PX;
    }
}
