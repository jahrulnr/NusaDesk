package gh.nusashell.nusadesk.presentation.desktop;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import gh.nusashell.nusadesk.R;
import gh.nusashell.nusadesk.domain.runtime.RuntimeSnapshot;
import gh.nusashell.nusadesk.domain.runtime.RuntimeState;
import gh.nusashell.nusadesk.domain.terminal.TerminalCommandApp;
import gh.nusashell.nusadesk.domain.webapp.WebAppDefinition;
import gh.nusashell.nusadesk.infrastructure.service.HostRuntimeStatus;
import gh.nusashell.nusadesk.infrastructure.service.RuntimeStatusBus;
import gh.nusashell.nusadesk.presentation.GuestSshStatusAware;
import gh.nusashell.nusadesk.presentation.GuestSshUiState;
import gh.nusashell.nusadesk.presentation.ScreenView;
import gh.nusashell.nusadesk.presentation.SessionStatusAware;
import gh.nusashell.nusadesk.presentation.widget.InstallPhaseSnapshot;
import gh.nusashell.nusadesk.presentation.widget.InstallerWizardView;
import gh.nusashell.nusadesk.presentation.widget.LinePatternDrawable;
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
 * hidden until all core components are active.</p>
 *
 * <p>The wizard's single action is pinned to the bottom edge of the screen
 * (a fixed footer over the backdrop, ADR-0057 layout amendment) instead of
 * scrolling with the log, so it never hangs in the middle of an otherwise
 * empty page; the setup content scrolls behind it.</p>
 *
 * <p>Linux is background infrastructure (ADR-0013), so there is no start, stop,
 * or session control anywhere on this surface. The launcher keeps the normal
 * ready state visually quiet and shows a passive status pill only when setup,
 * transition, stopped, or failed state needs explanation — see
 * {@link LauncherStatus}. It never offers an action the automatic path already
 * performs.</p>
 *
 * <p>The launcher owns no runtime policy: it forwards the install intent to
 * the host and renders whatever the host publishes.</p>
 */
public final class DesktopHomeView extends FrameLayout
        implements ScreenView, SessionStatusAware, GuestSshStatusAware {

    private ScrollView scroller;
    private LinearLayout content;
    private FrameLayout footer;
    private Button footerAction;
    private int contentBaseBottomPadding;
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

    private View updateBanner;
    private TextView updateText;
    private Button updateOpen;
    private Button updateDismiss;

    private List<WebAppDefinition> webApps = Collections.emptyList();
    private List<TerminalCommandApp> terminalApps = Collections.emptyList();
    private Map<String, Bitmap> favicons = Collections.emptyMap();

    private RuntimeSnapshot runtimeSnapshot;
    private InstallPhaseSnapshot addonPhase;
    private GuestSshUiState guestSsh = GuestSshUiState.missing();
    private HostRuntimeStatus session;
    /** Whether the mandatory service bridge (systemctl/Python overlay) is
     *  active on disk, as reported by the host. Fails closed: apps and the
     *  terminal stay locked behind setup until the host reports ready. */
    private boolean serviceReady;
    /** Whether the latest services-overlay phase snapshot ended in FAILED —
     *  tracked separately so a later toolkit snapshot cannot mask it. */
    private boolean serviceFailed;
    /** True while a user-requested optional toolkit install is running
     *  (ADR-0057): keeps the setup surface mounted so the toolkit's phases
     *  stay visible instead of the launcher unlocking mid-pipeline. */
    private boolean optionalInstallActive;

    private final RuntimeStatusBus.Listener statusListener = this::renderSessionStatus;

    private LauncherGridView.OpenListener entryOpenListener;
    private LauncherGridView.OpenListener entryEditListener;
    private View.OnClickListener updateOpenListener;
    private View.OnClickListener updateDismissListener;

    public DesktopHomeView(Context context) {
        super(context);
        init();
    }

    public DesktopHomeView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        // Backdrop plus the interlocking line texture drawn in code: the
        // gradient reads as a home screen, the pattern gives it depth without an
        // image asset and repeats infinitely at any size or density.
        setBackground(new LayerDrawable(new Drawable[]{
                getContext().getDrawable(R.drawable.launcher_backdrop),
                new LinePatternDrawable(getContext(), R.color.launcher_pattern_line)}));
        // The content scrolls; the one setup action lives in a footer pinned
        // to the bottom edge, so it is always thumb-reachable instead of
        // scrolling away with the log (ADR-0057 layout amendment).
        scroller = new ScrollView(getContext());
        scroller.setFillViewport(true);
        addView(scroller, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        LayoutInflater.from(getContext()).inflate(R.layout.widget_desktop_home, scroller, true);
        content = (LinearLayout) scroller.getChildAt(0);
        contentBaseBottomPadding = content.getPaddingBottom();
        footer = new FrameLayout(getContext());
        footer.setBackground(getContext().getDrawable(R.drawable.launcher_footer_scrim));
        int sidePadding = getResources().getDimensionPixelSize(R.dimen.content_padding);
        int bottomPadding = getResources().getDimensionPixelSize(R.dimen.content_padding);
        footer.setPadding(sidePadding, 0, sidePadding, bottomPadding);
        footer.setVisibility(GONE);
        addView(footer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM));

        setupRuntimeCard = findViewById(R.id.setup_runtime_card);
        wizard = findViewById(R.id.installer_wizard);
        // Reparent the wizard's single action into the footer. The wizard
        // keeps the view reference, so its text/visibility updates still land
        // on the button wherever it is attached.
        footerAction = wizard.findViewById(R.id.wizard_action);
        ((ViewGroup) footerAction.getParent()).removeView(footerAction);
        // Keep the button's declared height from the wizard layout; the footer
        // owns the surrounding padding.
        footer.addView(footerAction, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                getResources().getDimensionPixelSize(R.dimen.button_height)));
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
        updateBanner = findViewById(R.id.home_update_banner);
        updateText = findViewById(R.id.home_update_text);
        updateOpen = findViewById(R.id.home_update_open);
        updateDismiss = findViewById(R.id.home_update_dismiss);
        updateOpen.setOnClickListener(view -> {
            if (updateOpenListener != null) {
                updateOpenListener.onClick(view);
            }
        });
        updateDismiss.setOnClickListener(view -> {
            if (updateDismissListener != null) {
                updateDismissListener.onClick(view);
            }
        });

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

    /**
     * Receives a long press on a registered app tile — a web app or a terminal
     * command — which opens its edit form.
     */
    public void setOnEntryEditListener(LauncherGridView.OpenListener listener) {
        this.entryEditListener = listener;
    }

    /** Wires the update banner's "View release" action (ADR-0038). */
    public void setOnUpdateOpenListener(OnClickListener listener) {
        updateOpenListener = listener;
    }

    /** Wires the update banner's "Dismiss" action. */
    public void setOnUpdateDismissListener(OnClickListener listener) {
        updateDismissListener = listener;
    }

    /**
     * Renders the update banner. {@code null} hides it; a tag renders the
     * notice. The banner is presentation-only: it never fetches or stores
     * anything, and both actions belong to the host.
     */
    public void renderUpdateBanner(String tag) {
        if (tag == null || tag.trim().isEmpty()) {
            updateBanner.setVisibility(GONE);
            return;
        }
        updateBanner.setVisibility(VISIBLE);
        updateText.setText(getContext().getString(R.string.update_banner_text, tag));
    }

    /**
     * Offers — or withdraws — each optional toolkit independently on the
     * setup surface (ADR-0057): the USB / ADB driver and the Termux command
     * compatibility layer. The host derives each offer from disk truth; the
     * wizard only shows the card while a start/retry decision is pending, and
     * an offered toolkit is never pre-selected.
     */
    public void setOptionalToolsOffered(boolean usbOffered, boolean termuxOffered) {
        wizard.setOptionalToolsOffered(usbOffered, termuxOffered);
        // Re-render immediately: card visibility derives from the current
        // action inside the wizard, so the offer must reach showPhase now —
        // not whenever the next snapshot happens to arrive.
        render();
    }

    /** Whether the user ticked the USB / ADB driver toolkit for this setup run. */
    public boolean isUsbAdbSelected() {
        return wizard.isUsbAdbSelected();
    }

    /** Whether the user ticked the Termux commands toolkit for this setup run. */
    public boolean isTermuxSelected() {
        return wizard.isTermuxSelected();
    }

    /**
     * Reports whether the mandatory service bridge (systemctl/Python overlay)
     * is usable — the host derives this from the verified overlay on disk.
     * It is part of the core setup gate: apps and the terminal stay hidden
     * behind the setup card while it is missing, and a failed mandatory
     * install keeps the card mounted with a retry action.
     */
    public void setRuntimeServiceReady(boolean ready) {
        if (serviceReady == ready) {
            return;
        }
        serviceReady = ready;
        render();
    }

    /**
     * Keeps the setup surface visible while a user-requested optional toolkit
     * install runs — including one started from the System page — so its
     * {@code usb}/{@code termux} phases and failure stay on screen instead of
     * the launcher silently unlocking or hiding the outcome.
     */
    public void setOptionalInstallActive(boolean active) {
        if (optionalInstallActive == active) {
            return;
        }
        optionalInstallActive = active;
        render();
    }

    /**
     * Replaces the registered user apps the grid renders: the web apps and the
     * terminal commands, each in its own store's launcher order.
     */
    public void setApps(
            List<WebAppDefinition> webApps, List<TerminalCommandApp> commandApps) {
        this.webApps = webApps == null ? Collections.emptyList() : webApps;
        this.terminalApps = commandApps == null ? Collections.emptyList() : commandApps;
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
     * Feeds one add-on install snapshot — the guest-SSH terminal component,
     * the mandatory service bridge, or an optional toolkit — into the
     * unified surface and re-renders. The snapshot is display-only: it is
     * never persisted as base runtime state (presence is derived from disk).
     */
    public void renderAddonPhase(InstallPhaseSnapshot phase) {
        if (phase.getComponent() == InstallPhaseSnapshot.Component.SERVICES) {
            // Latch the mandatory overlay's failure so a later toolkit or
            // SSH snapshot cannot mask the retry the user is owed.
            serviceFailed = phase.getState() == RuntimeState.FAILED;
        }
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
        boolean systemReady = isSystemReady();
        boolean sshInstalled = guestSsh.getKind() == GuestSshUiState.Kind.INSTALLED;
        // The terminal needs all three core pieces: rootfs, the guest-SSH
        // add-on, and the mandatory systemctl/Python service bridge.
        boolean terminalReady = sshInstalled && serviceReady;
        boolean showApps = LauncherModel.shouldShowApps(systemReady, terminalReady);
        boolean filtering = showApps && LauncherModel.isFiltering(query());

        List<LauncherEntry> entries = LauncherModel.entries(webApps, terminalApps);
        List<LauncherEntry> visible = LauncherModel.filter(entries, query(), this::labelOf);

        // One installer surface while any core component is missing, and
        // while a user-requested optional toolkit install is still running.
        // Hiding (and not binding) launcher apps prevents users and
        // accessibility services from entering half-installed app flows.
        boolean setupNeeded = !systemReady || !terminalReady || optionalInstallActive;
        appsSearchRow.setVisibility(showApps ? VISIBLE : GONE);
        appsHeaderRow.setVisibility(showApps ? VISIBLE : GONE);
        setupRuntimeCard.setVisibility(setupNeeded && !filtering ? VISIBLE : GONE);
        appsNote.setVisibility(GONE);
        if (setupNeeded) {
            InstallPhaseSnapshot phase = SetupPhasePolicy.phaseFor(
                    runtimeSnapshot, addonPhase, sshInstalled);
            SetupAction action = SetupPhasePolicy.actionFor(
                    runtimeSnapshot,
                    sshInstalled,
                    guestSsh.getKind() == GuestSshUiState.Kind.FAILED,
                    serviceReady,
                    serviceFailed);
            wizard.showPhase(phase, action);
        }
        updateFooter(setupNeeded);

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

    /**
     * Shows or hides the pinned setup action. The footer follows the wizard's
     * own action visibility, so a running install (action hidden) or a ready
     * device never leaves an empty bar; while it is shown, the scroll content
     * reserves room so the last line is not hidden behind it.
     */
    private void updateFooter(boolean setupVisible) {
        boolean show = setupVisible && footerAction.getVisibility() == VISIBLE;
        footer.setVisibility(show ? VISIBLE : GONE);
        int extra = show
                ? getResources().getDimensionPixelSize(R.dimen.button_height)
                        + 3 * getResources().getDimensionPixelSize(R.dimen.content_padding)
                : 0;
        int wanted = contentBaseBottomPadding + extra;
        if (content.getPaddingBottom() != wanted) {
            content.setPadding(content.getPaddingLeft(), content.getPaddingTop(),
                    content.getPaddingRight(), wanted);
        }
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
     * One passive status pill for a state that needs attention. Normal readiness
     * is deliberately quiet so the launcher does not advertise infrastructure
     * that is already working.
     */
    private void renderStatus(LauncherStatus status) {
        Context context = getContext();
        boolean visible = status.isVisibleInLauncher();
        statusView.setVisibility(visible ? VISIBLE : GONE);
        if (!visible) {
            return;
        }
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
