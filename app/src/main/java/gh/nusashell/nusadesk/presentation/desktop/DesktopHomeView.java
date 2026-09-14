package gh.nusashell.nusadesk.presentation.desktop;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;

import gh.nusashell.nusadesk.R;
import gh.nusashell.nusadesk.domain.runtime.RuntimeSnapshot;
import gh.nusashell.nusadesk.domain.runtime.RuntimeState;
import gh.nusashell.nusadesk.domain.webapp.WebAppDefinition;
import gh.nusashell.nusadesk.infrastructure.service.HostRuntimeStatus;
import gh.nusashell.nusadesk.infrastructure.service.RuntimeStatusBus;
import gh.nusashell.nusadesk.presentation.GuestSshStatusAware;
import gh.nusashell.nusadesk.presentation.GuestSshUiState;
import gh.nusashell.nusadesk.presentation.ScreenView;
import gh.nusashell.nusadesk.presentation.SessionStatusAware;
import gh.nusashell.nusadesk.presentation.widget.InstallPhaseSnapshot;
import gh.nusashell.nusadesk.presentation.widget.InstallerWizardView;
import gh.nusashell.nusadesk.presentation.widget.SetupAction;
import gh.nusashell.nusadesk.presentation.widget.SetupPhasePolicy;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * The launcher: the product's home surface.
 *
 * <p>It reads the way a home screen reads. A search field at the top, the app
 * header with its count, then one dense grid whose first tile is the dashed
 * {@code Add app} action, followed by the Linux surfaces this build ships and
 * the web apps the user registered. Setup appears above the grid only while a
 * component is actually missing, so an installed device shows a grid and
 * nothing else.</p>
 *
 * <p>Setup is one surface, not two. The curated rootfs and the guest-SSH
 * add-on install as one serialized pipeline (ADR-0017), so while either is
 * missing the launcher shows a single installer wizard with one thumb-reachable
 * action. There is no separate "Next: add terminal component" card or second
 * button: the one action starts the whole pipeline, and a retry runs only the
 * component that is missing or failed. App icons, search, and the grid stay
 * hidden until both components are active.</p>
 *
 * <p>Linux is background infrastructure (ADR-0013), so there is no start, stop,
 * or session control anywhere on this surface. What the launcher states instead
 * is one passive readiness pill — see {@link LauncherStatus} — which folds
 * install, terminal-component, and session truth into a single sentence and
 * never offers an action the automatic path already performs.</p>
 *
 * <p>The launcher owns no runtime policy: it forwards the install intent to
 * the host and renders whatever the host publishes.</p>
 */
public final class DesktopHomeView extends ScrollView
        implements ScreenView, SessionStatusAware, GuestSshStatusAware {

    private View setupRuntimeCard;
    private InstallerWizardView wizard;
    private TextView titleView;
    private TextView countView;
    private TextView statusView;
    private View appsSearchRow;
    private View appsHeaderRow;
    private TextView appsNote;
    private TextView searchEmpty;
    private EditText searchInput;
    private View searchClear;
    private LauncherGridView grid;

    private List<WebAppDefinition> webApps = Collections.emptyList();
    private Map<String, Bitmap> favicons = Collections.emptyMap();

    private RuntimeSnapshot runtimeSnapshot;
    private InstallPhaseSnapshot addonPhase;
    private GuestSshUiState guestSsh = GuestSshUiState.missing();
    private HostRuntimeStatus session;

    private final RuntimeStatusBus.Listener statusListener = this::renderSessionStatus;

    private LauncherGridView.OpenListener entryOpenListener;
    private LauncherGridView.OpenListener entryEditListener;

    public DesktopHomeView(Context context) {
        super(context);
        init();
    }

    public DesktopHomeView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        setBackgroundResource(R.drawable.launcher_backdrop);
        LayoutInflater.from(getContext()).inflate(R.layout.widget_desktop_home, this, true);
        setupRuntimeCard = findViewById(R.id.setup_runtime_card);
        wizard = findViewById(R.id.installer_wizard);
        titleView = findViewById(R.id.launcher_title);
        countView = findViewById(R.id.launcher_count);
        statusView = findViewById(R.id.launcher_status);
        appsSearchRow = findViewById(R.id.apps_search_row);
        appsHeaderRow = findViewById(R.id.apps_header_row);
        appsNote = findViewById(R.id.apps_note);
        searchEmpty = findViewById(R.id.apps_search_empty);
        searchInput = findViewById(R.id.apps_search_input);
        searchClear = findViewById(R.id.apps_search_clear);
        grid = findViewById(R.id.apps_grid);

        grid.setOnEntryOpenListener(entry -> {
            if (entryOpenListener != null) {
                entryOpenListener.onEntryOpened(entry);
            }
        });
        grid.setOnEntryEditListener(entry -> {
            if (entryEditListener != null) {
                entryEditListener.onEntryOpened(entry);
            }
        });
        wireSearch();
        render();
    }

    /** Wires the single setup action to the host's combined install flow. */
    public void setOnInstallListener(OnClickListener listener) {
        wizard.setOnActionListener(listener);
    }

    /** Receives a tap on an openable tile. */
    public void setOnEntryOpenListener(LauncherGridView.OpenListener listener) {
        this.entryOpenListener = listener;
    }

    /** Receives a long press on a web app tile, which opens its edit form. */
    public void setOnEntryEditListener(LauncherGridView.OpenListener listener) {
        this.entryEditListener = listener;
    }

    /** States the real storage requirement for the first-run setup step. */
    public void setStorageRequirement(long totalBytes) {
        wizard.setStorageRequirement(totalBytes);
    }

    /** Replaces the registered web apps the grid renders. */
    public void setWebApps(List<WebAppDefinition> definitions) {
        this.webApps = definitions == null ? Collections.emptyList() : definitions;
        render();
    }

    /**
     * Replaces the favicons the grid may show. They are the app's own fallback
     * image, never a replacement for one the user chose: a tile with a user icon
     * ignores its entry here.
     */
    public void setFavicons(Map<String, Bitmap> favicons) {
        this.favicons = favicons == null ? Collections.emptyMap() : favicons;
        render();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        RuntimeStatusBus.getInstance().register(statusListener);
    }

    @Override
    protected void onDetachedFromWindow() {
        RuntimeStatusBus.getInstance().unregister(statusListener);
        super.onDetachedFromWindow();
    }

    @Override
    public void render(RuntimeSnapshot snapshot) {
        runtimeSnapshot = snapshot;
        wizard.appendLog(InstallPhaseSnapshot.rootfs(snapshot));
        render();
    }

    /**
     * Feeds one guest-SSH add-on install snapshot into the unified surface and
     * re-renders. The add-on snapshot is display-only: it is never persisted as
     * base runtime state (presence is derived from disk).
     */
    public void renderAddonPhase(InstallPhaseSnapshot phase) {
        this.addonPhase = phase;
        wizard.appendLog(phase);
        render();
    }

    @Override
    public void renderGuestSsh(GuestSshUiState state) {
        guestSsh = state == null ? GuestSshUiState.missing() : state;
        render();
    }

    @Override
    public void renderSessionStatus(HostRuntimeStatus status) {
        session = status;
        render();
    }

    // ---- Search ----

    private void wireSearch() {
        searchClear.setOnClickListener(view -> searchInput.setText(""));
        searchInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence text, int start, int count, int after) {
                // No pre-edit work: the filter runs on the settled text.
            }

            @Override
            public void onTextChanged(CharSequence text, int start, int before, int count) {
                // Filtering happens in afterTextChanged so the field has settled.
            }

            @Override
            public void afterTextChanged(Editable text) {
                searchClear.setVisibility(text.length() == 0 ? GONE : VISIBLE);
                render();
            }
        });
    }

    private String query() {
        return searchInput.getText().toString();
    }

    private String labelOf(LauncherEntry entry) {
        return entry.getLabelRes() != 0
                ? getContext().getString(entry.getLabelRes())
                : entry.getLabel();
    }

    // ---- Rendering ----

    private boolean isSystemReady() {
        RuntimeState state = runtimeSnapshot == null
                ? RuntimeState.NOT_INSTALLED
                : runtimeSnapshot.getState();
        return state == RuntimeState.READY;
    }

    private void render() {
        RuntimeState install = runtimeSnapshot == null
                ? RuntimeState.NOT_INSTALLED
                : runtimeSnapshot.getState();
        boolean systemReady = isSystemReady();
        boolean serviceReady = guestSsh.getKind() == GuestSshUiState.Kind.INSTALLED;
        boolean showApps = LauncherModel.shouldShowApps(systemReady, serviceReady);
        boolean filtering = showApps && LauncherModel.isFiltering(query());

        List<LauncherEntry> entries = LauncherModel.entries(webApps);
        List<LauncherEntry> visible = LauncherModel.filter(entries, query(), this::labelOf);

        // One installer surface while either component is missing. Hiding (and
        // not binding) launcher apps prevents users and accessibility services
        // from entering half-installed app flows.
        boolean setupNeeded = !systemReady || !serviceReady;
        appsSearchRow.setVisibility(showApps ? VISIBLE : GONE);
        appsHeaderRow.setVisibility(showApps ? VISIBLE : GONE);
        setupRuntimeCard.setVisibility(setupNeeded && !filtering ? VISIBLE : GONE);
        appsNote.setVisibility(GONE);
        if (setupNeeded) {
            InstallPhaseSnapshot phase = SetupPhasePolicy.phaseFor(runtimeSnapshot, addonPhase);
            SetupAction action = SetupPhasePolicy.actionFor(
                    runtimeSnapshot,
                    serviceReady,
                    guestSsh.getKind() == GuestSshUiState.Kind.FAILED);
            wizard.showPhase(phase, action);
        }

        renderStatus(LauncherStatus.of(runtimeSnapshot, guestSsh, session));
        titleView.setText(R.string.launcher_title);
        // The count follows what the grid actually shows, so a filtered grid
        // never reads like the full list.
        int visibleApps = openableCount(visible);
        countView.setText(getContext().getString(
                LauncherHeaderText.countRes(visibleApps), visibleApps));

        grid.setVisibility(showApps && !(filtering && visible.isEmpty()) ? VISIBLE : GONE);
        grid.setEntries(showApps ? visible : Collections.emptyList(), showApps, favicons);
        searchEmpty.setVisibility(
                showApps && filtering && visible.isEmpty() ? VISIBLE : GONE);
        searchEmpty.setText(getContext().getString(
                R.string.apps_search_no_results, query().trim()));
    }

    /** The grid's app count: the {@code Add app} action is not an app. */
    private static int openableCount(List<LauncherEntry> entries) {
        int count = 0;
        for (LauncherEntry entry : entries) {
            if (entry.getKind() != LauncherEntry.Kind.ADD_APP) {
                count++;
            }
        }
        return count;
    }

    /**
     * One passive pill. It states the readiness the host published and never
     * offers a control: Linux starts from an app launch, so a button here could
     * only duplicate the automatic path or contradict it.
     */
    private void renderStatus(LauncherStatus status) {
        Context context = getContext();
        statusView.setText(status.getLabelRes());
        statusView.setTextColor(context.getColor(status.getForegroundColorRes()));
        statusView.setBackground(badgeBackground(status));
        statusView.setContentDescription(context.getString(
                R.string.status_content_description,
                context.getString(status.getLabelRes()), statusDetail(status)));
    }

    private GradientDrawable badgeBackground(LauncherStatus status) {
        GradientDrawable badge = new GradientDrawable();
        badge.setShape(GradientDrawable.RECTANGLE);
        badge.setColor(getContext().getColor(status.getBackgroundColorRes()));
        badge.setCornerRadius(40 * getResources().getDisplayMetrics().density);
        return badge;
    }

    private String statusDetail(LauncherStatus status) {
        if (status.getKind() != LauncherStatus.Kind.FAILED) {
            return getContext().getString(status.getDetailRes());
        }
        String reason = status.getFailureReason();
        return getContext().getString(R.string.launcher_status_failed_detail,
                reason == null ? getContext().getString(R.string.session_failed_no_reason)
                        : reason);
    }
}
