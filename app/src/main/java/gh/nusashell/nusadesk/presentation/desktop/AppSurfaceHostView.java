package gh.nusashell.nusadesk.presentation.desktop;

import android.content.Context;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;

import gh.nusashell.nusadesk.R;

import java.util.ArrayList;
import java.util.List;

/**
 * Chrome and container for an open surface.
 *
 * <p>The bar is deliberately small and contextual: a way back to the launcher,
 * the surface title, and — only for a surface that really has app-level actions
 * — one options button. It carries no session chrome, because Linux is
 * background infrastructure: an app surface says nothing about the session, and
 * the launcher owns the single readiness statement (ADR-0013).</p>
 *
 * <p>Surfaces stay attached and are switched by visibility, which is what keeps
 * a live terminal's WebView and scrollback alive across a trip back to the
 * launcher.</p>
 */
public final class AppSurfaceHostView extends LinearLayout {

    /** One entry in the surface's options menu. */
    public static final class MenuAction {
        private final int titleRes;
        private final Runnable action;

        public MenuAction(int titleRes, Runnable action) {
            this.titleRes = titleRes;
            this.action = action;
        }
    }

    private TextView titleView;
    private ImageButton moreButton;
    private FrameLayout surfaceContainer;
    private final List<MenuAction> menuActions = new ArrayList<>();

    public AppSurfaceHostView(Context context) {
        super(context);
        init();
    }

    public AppSurfaceHostView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        setOrientation(VERTICAL);
        LayoutInflater.from(getContext()).inflate(R.layout.widget_app_surface_host, this, true);
        titleView = findViewById(R.id.taskbar_title);
        surfaceContainer = findViewById(R.id.app_surface_container);
        findViewById(R.id.taskbar_home).setContentDescription(
                getContext().getString(R.string.taskbar_home_desc));
        moreButton = findViewById(R.id.taskbar_more);
        moreButton.setContentDescription(getContext().getString(R.string.taskbar_more_desc));
        moreButton.setOnClickListener(this::showMenu);
        renderMenuButton();
    }

    /** Container the host adds retained surfaces into. */
    public FrameLayout getSurfaceContainer() {
        return surfaceContainer;
    }

    public void setOnHomeListener(View.OnClickListener listener) {
        findViewById(R.id.taskbar_home).setOnClickListener(listener);
    }

    /** Sets the surface title; a user web app shows its own name. */
    public void setAppTitle(CharSequence title) {
        titleView.setText(title);
    }

    public void setAppTitle(int titleRes) {
        titleView.setText(titleRes);
    }

    /**
     * Sets the actions the options button offers for the current surface. An
     * empty list hides the button, so a surface with nothing to configure does
     * not show a menu that would only repeat the launcher.
     */
    public void setMenuActions(List<MenuAction> actions) {
        menuActions.clear();
        if (actions != null) {
            menuActions.addAll(actions);
        }
        renderMenuButton();
    }

    private void renderMenuButton() {
        moreButton.setVisibility(menuActions.isEmpty() ? GONE : VISIBLE);
    }

    private void showMenu(View anchor) {
        if (menuActions.isEmpty()) {
            return;
        }
        PopupMenu menu = new PopupMenu(getContext(), anchor);
        for (int index = 0; index < menuActions.size(); index++) {
            menu.getMenu().add(Menu.NONE, index, index, menuActions.get(index).titleRes);
        }
        menu.setOnMenuItemClickListener(item -> {
            int index = item.getItemId();
            if (index < 0 || index >= menuActions.size()) {
                return false;
            }
            menuActions.get(index).action.run();
            return true;
        });
        menu.show();
    }
}
