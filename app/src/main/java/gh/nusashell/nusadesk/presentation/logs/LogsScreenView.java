package gh.nusashell.nusadesk.presentation.logs;

import android.content.Context;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import gh.nusashell.nusadesk.R;
import gh.nusashell.nusadesk.infrastructure.logs.GuestLog;
import gh.nusashell.nusadesk.presentation.widget.TerminalBridgeView;

import java.util.List;

/**
 * Logs surface: the guest's own log files, listed the way a real Linux box
 * keeps them — the boot/session stream separately from each service's log —
 * then tailed live in the packaged xterm surface when one is picked.
 *
 * <p>The view is deliberately thin: the host (MainActivity) scans the catalog
 * off the UI thread and pushes the result to {@link #renderLogs(List)}, and
 * owns the {@code GuestLogTail} lifecycle — the view only reports selection
 * ({@link Listener#onLogItemSelected(GuestLog)}) and viewer close
 * ({@link Listener#onLogViewClosed()}), so a tail never outlives the surface
 * that displays it. Output arrives through {@link #writeLogOutput(String)}.</p>
 *
 * <p>Pane flow: list → tap an item → viewer (terminal). Back inside the
 * viewer returns to the list; leaving the whole surface — launcher, another
 * app, process death — closes the viewer first, which is what stops the
 * tail. The bridge keeps its WebView across a launcher trip like the
 * terminal surface does; only the tail is dropped.</p>
 */
public final class LogsScreenView extends FrameLayout {

    /** Reports selection/close so the host owns the tail lifecycle. */
    public interface Listener {
        /** The user opened a log item: start tailing {@code log.getHostPath()}. */
        void onLogItemSelected(GuestLog log);
        /** The viewer closed (back, surface switch): stop tailing. */
        void onLogViewClosed();
    }

    private View listPane;
    private LinearLayout emptyPane;
    private TextView emptyTitle;
    private TextView emptyBody;
    private LinearLayout viewerPane;
    private TextView viewerTitle;
    private TextView viewerPath;
    private TextView bootHeader;
    private TextView systemHeader;
    private LinearLayout bootContainer;
    private LinearLayout systemContainer;
    private TerminalBridgeView bridge;

    /** Denser than the shell's 13px so full-length log lines fit a phone row. */
    private static final int LOG_FONT_PX = 11;

    private Listener listener;
    private GuestLog viewing;
    private boolean listHasItems;

    public LogsScreenView(Context context) {
        super(context);
        init();
    }

    public LogsScreenView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        LayoutInflater.from(getContext()).inflate(R.layout.widget_logs_screen, this, true);
        listPane = findViewById(R.id.logs_list_pane);
        emptyPane = findViewById(R.id.logs_empty_pane);
        emptyTitle = findViewById(R.id.logs_empty_title);
        emptyBody = findViewById(R.id.logs_empty_body);
        viewerPane = findViewById(R.id.logs_viewer_pane);
        viewerTitle = findViewById(R.id.logs_viewer_title);
        viewerPath = findViewById(R.id.logs_viewer_path);
        bootHeader = findViewById(R.id.logs_boot_header);
        systemHeader = findViewById(R.id.logs_system_header);
        bootContainer = findViewById(R.id.logs_boot_container);
        systemContainer = findViewById(R.id.logs_system_container);
        bridge = findViewById(R.id.logs_bridge);
        // A viewer has no stdin or PTY: input is dropped, and resize only
        // refits the page. The listener still has to exist — the bridge only
        // surfaces scroll-state (the "Live" jump button) through it.
        bridge.setListener(new TerminalBridgeView.Listener() {
            @Override
            public void onBridgeReady() {
                // Log viewers are read-only. Native long-press selection would
                // steal a held scroll gesture, so keep copy selection disabled
                // on this surface while the interactive shell retains it.
                bridge.setNativeTextSelectionEnabled(false);
                // Log lines are long and read-only: a denser font keeps most
                // entries on one row instead of wrapping mid-word.
                bridge.setFontSize(LOG_FONT_PX);
                bridge.fit();
            }

            @Override
            public void onInput(String data) {
                // Read-only surface: typed input has nowhere to go.
            }

            @Override
            public void onTerminalResize(int cols, int rows) {
                // No PTY to resize; the page's own fit keeps the view right.
            }
        });
        findViewById(R.id.logs_back).setOnClickListener(v -> showLogList());
        showReading();
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    /** Whether the viewer is showing a log rather than the list. */
    public boolean isViewingLog() {
        return viewing != null;
    }

    /**
     * "Reading…" state while the host scans the catalog on its executor.
     * Local file listing is fast, but the state must still be explicit.
     */
    public void showReading() {
        viewing = null;
        viewerPane.setVisibility(GONE);
        listPane.setVisibility(VISIBLE);
        emptyPane.setVisibility(VISIBLE);
        emptyTitle.setText(R.string.logs_empty_title);
        emptyBody.setText(R.string.logs_reading);
    }

    /**
     * Renders the catalog the host scanned: boot items under Boot, service
     * and compose logs under System. An empty catalog gets the honest empty
     * state — a device that never ran a session has no log files.
     */
    public void renderLogs(List<GuestLog> logs) {
        bootContainer.removeAllViews();
        systemContainer.removeAllViews();
        int boot = 0;
        int system = 0;
        if (logs != null) {
            for (GuestLog log : logs) {
                if (log == null) {
                    continue;
                }
                if (log.getSection() == GuestLog.Section.BOOT) {
                    bootContainer.addView(logRow(log));
                    boot++;
                } else {
                    systemContainer.addView(logRow(log));
                    system++;
                }
            }
        }
        bootHeader.setVisibility(boot > 0 ? VISIBLE : GONE);
        systemHeader.setVisibility(system > 0 ? VISIBLE : GONE);
        listHasItems = boot + system > 0;
        if (viewing == null) {
            applyListVisibility();
        }
    }

    /**
     * Returns to the list from the viewer and reports the close so the host
     * stops the tail. Safe to call when already on the list.
     */
    public void showLogList() {
        closeViewer();
    }

    /**
     * One chunk of tailed content for the open log. The host calls this on
     * the UI thread; calls made while no viewer is open are dropped.
     */
    public void writeLogOutput(String text) {
        if (viewing == null || text == null || text.isEmpty()) {
            return;
        }
        // Real output displaces the "waiting" cover; an empty file keeps it.
        bridge.showOverlay(null);
        // File lines end in LF only; a terminal needs CR+LF (the PTY's ONLCR
        // translation a shell session gets for free). Without the CR each new
        // line keeps the previous column and the output stair-steps right.
        bridge.writeStdout(text.replaceAll("\\r?\\n", "\r\n"));
    }

    private void enterViewer(GuestLog log) {
        viewing = log;
        listPane.setVisibility(GONE);
        emptyPane.setVisibility(GONE);
        viewerPane.setVisibility(VISIBLE);
        viewerTitle.setText(log.getTitle());
        viewerPath.setText(log.getGuestPath());
        // A different file must never inherit the previous one's output.
        bridge.clear();
        bridge.showOverlay(getContext().getString(R.string.logs_waiting));
        bridge.fit();
        Listener l = listener;
        if (l != null) {
            l.onLogItemSelected(log);
        }
    }

    private void closeViewer() {
        if (viewing == null) {
            return;
        }
        viewing = null;
        viewerPane.setVisibility(GONE);
        applyListVisibility();
        Listener l = listener;
        if (l != null) {
            l.onLogViewClosed();
        }
    }

    private void applyListVisibility() {
        listPane.setVisibility(VISIBLE);
        if (listHasItems) {
            emptyPane.setVisibility(GONE);
        } else {
            emptyPane.setVisibility(VISIBLE);
            emptyTitle.setText(R.string.logs_empty_title);
            emptyBody.setText(R.string.logs_empty_body);
        }
    }

    /**
     * One tappable card per log file: the display name, then the guest path
     * in monospace — the same string a terminal user would pass to
     * {@code tail -f}. The qualifier ({@code user}, {@code compose}) joins
     * the path line so a same-named user unit is never mistaken for the
     * system one.
     */
    private View logRow(GuestLog log) {
        Context context = getContext();
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.VERTICAL);
        int padH = dp(2);
        int padV = dp(8);
        row.setPadding(padH, padV, padH, padV);
        row.setMinimumHeight(getResources().getDimensionPixelSize(R.dimen.touch_target));

        TextView title = new TextView(context);
        title.setText(log.getTitle());
        title.setTextColor(getResources().getColor(R.color.ink, null));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        row.addView(title, new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        String detail = log.getQualifier().isEmpty()
                ? log.getGuestPath()
                : log.getGuestPath() + "  ·  " + log.getQualifier();
        TextView path = new TextView(context);
        path.setText(detail);
        path.setTextColor(getResources().getColor(R.color.ink_muted, null));
        path.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        path.setTypeface(Typeface.MONOSPACE);
        LinearLayout.LayoutParams pathParams = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        pathParams.topMargin = dp(2);
        row.addView(path, pathParams);

        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        rowParams.bottomMargin = dp(4);
        row.setLayoutParams(rowParams);
        row.setContentDescription(context.getString(
                R.string.logs_item_desc, log.getTitle(), log.getGuestPath()));
        row.setFocusable(true);
        row.setOnClickListener(v -> enterViewer(log));
        return row;
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        // Any leave — launcher, another surface, another app — closes the
        // viewer first, which is what stops the tail through the listener.
        if (changedView == this && visibility != VISIBLE && viewing != null) {
            closeViewer();
        }
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
