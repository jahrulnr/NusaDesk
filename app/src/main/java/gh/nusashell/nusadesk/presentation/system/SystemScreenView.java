package gh.nusashell.nusadesk.presentation.system;

import android.content.Context;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ScrollView;

import gh.nusashell.nusadesk.R;
import gh.nusashell.nusadesk.domain.runtime.RuntimeSnapshot;
import gh.nusashell.nusadesk.infrastructure.service.HostRuntimeStatus;
import gh.nusashell.nusadesk.infrastructure.service.RuntimeStatusBus;
import gh.nusashell.nusadesk.presentation.GuestSshStatusAware;
import gh.nusashell.nusadesk.presentation.GuestSshUiState;
import gh.nusashell.nusadesk.presentation.ScreenView;
import gh.nusashell.nusadesk.presentation.SessionStatusAware;
import gh.nusashell.nusadesk.presentation.workspace.WorkspaceUiState;

/**
 * Linux system screen (ADR-0043): a hub with three grouped rows — Settings,
 * One-click install, and About NusaDesk — each opening its own page, in the
 * Android-Settings shape. Exactly one pane is visible at a time.
 *
 * <p>Back contract: a sub-page's back row and the system back action both
 * return to the hub before Back leaves the screen; leaving the surface at
 * all lands the next open back on the hub. The page renderers keep the
 * semantics the single System scroll had — state is reported, never owned:
 * Linux starts from an app launch and is stopped from the platform's own
 * foreground-service notification (ADR-0013).</p>
 */
public final class SystemScreenView extends FrameLayout
        implements ScreenView, SessionStatusAware, GuestSshStatusAware {

    private ScrollView hubPane;
    private SystemSettingsPageView settingsPage;
    private SystemInstallPageView installPage;
    private SystemAboutPageView aboutPage;

    /** The visible sub-page, or {@code null} while the hub is shown. */
    private ScrollView activePage;

    private final RuntimeStatusBus.Listener statusListener = this::renderSessionStatus;

    public SystemScreenView(Context context) {
        super(context);
        init();
    }

    public SystemScreenView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        LayoutInflater.from(getContext()).inflate(R.layout.widget_system_screen, this, true);
        hubPane = findViewById(R.id.system_hub_pane);
        settingsPage = findViewById(R.id.system_settings_page);
        installPage = findViewById(R.id.system_install_page);
        aboutPage = findViewById(R.id.system_about_page);
        findViewById(R.id.system_hub_settings_row)
                .setOnClickListener(view -> showPage(settingsPage));
        findViewById(R.id.system_hub_install_row)
                .setOnClickListener(view -> showPage(installPage));
        findViewById(R.id.system_hub_about_row)
                .setOnClickListener(view -> showPage(aboutPage));
        OnClickListener backToHub = view -> showHub();
        settingsPage.setOnBackListener(backToHub);
        installPage.setOnBackListener(backToHub);
        aboutPage.setOnBackListener(backToHub);
    }

    /**
     * Steps one level up inside the screen. The host calls this from its Back
     * handling while this surface is open; on the hub the call is not
     * consumed so Back can leave the screen.
     *
     * @return true when Back moved from a sub-page to the hub.
     */
    public boolean navigateBack() {
        if (activePage == null) {
            return false;
        }
        showHub();
        return true;
    }

    private void showPage(ScrollView page) {
        activePage = page;
        hubPane.setVisibility(GONE);
        settingsPage.setVisibility(page == settingsPage ? VISIBLE : GONE);
        installPage.setVisibility(page == installPage ? VISIBLE : GONE);
        aboutPage.setVisibility(page == aboutPage ? VISIBLE : GONE);
        page.scrollTo(0, 0);
    }

    private void showHub() {
        activePage = null;
        hubPane.setVisibility(VISIBLE);
        settingsPage.setVisibility(GONE);
        installPage.setVisibility(GONE);
        aboutPage.setVisibility(GONE);
        hubPane.scrollTo(0, 0);
    }

    /** Wires the "How it works" contract disclosure on the About page. */
    public void setOnHowItWorksListener(OnClickListener listener) {
        aboutPage.setOnHowItWorksListener(listener);
    }

    /** Wires the workspace row's single action on the Settings page. */
    public void setOnWorkspaceActionListener(OnClickListener listener) {
        settingsPage.setOnWorkspaceActionListener(listener);
    }

    /** Wires the app-permissions shortcut on the Settings page. */
    public void setOnOpenAppSettingsListener(OnClickListener listener) {
        settingsPage.setOnOpenAppSettingsListener(listener);
    }

    /** Wires the battery card's single action on the Settings page. */
    public void setOnBatteryActionListener(OnClickListener listener) {
        settingsPage.setOnBatteryActionListener(listener);
    }

    /** Wires the boot card's single action on the Settings page. */
    public void setOnBootActionListener(OnClickListener listener) {
        settingsPage.setOnBootActionListener(listener);
    }

    /** Renders the workspace folder state on the Settings page. */
    public void renderWorkspace(WorkspaceUiState state) {
        settingsPage.renderWorkspace(state);
    }

    /** States how many user-defined web apps are registered. */
    public void setWebAppCount(int count) {
        aboutPage.setWebAppCount(count);
    }

    /** Renders the battery-optimization card on the Settings page. */
    public void renderBattery(boolean exempt) {
        settingsPage.renderBattery(exempt);
    }

    /** Renders the boot-start card on the Settings page. */
    public void renderBoot(boolean enabled) {
        settingsPage.renderBoot(enabled);
    }

    /** Shows which curated runtime profile and version this build expects. */
    public void setRuntimeProfile(String appId, String version) {
        aboutPage.setRuntimeProfile(appId, version);
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
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        // Any leave — launcher, another surface, another app — lands the next
        // open back on the hub instead of a stranded sub-page.
        if (visibility != VISIBLE && activePage != null) {
            showHub();
        }
    }

    @Override
    public void render(RuntimeSnapshot snapshot) {
        aboutPage.renderState(snapshot);
    }

    @Override
    public void renderSessionStatus(HostRuntimeStatus status) {
        aboutPage.renderSessionStatus(status);
    }

    @Override
    public void renderGuestSsh(GuestSshUiState state) {
        aboutPage.renderGuestSsh(state);
    }
}
