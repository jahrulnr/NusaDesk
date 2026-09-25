package gh.nusashell.nusadesk.presentation.desktop;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.os.Looper;
import android.view.View;
import android.widget.Spinner;

import gh.nusashell.nusadesk.R;
import gh.nusashell.nusadesk.application.terminal.TerminalCommandRegistry;
import gh.nusashell.nusadesk.application.terminal.TerminalCommandStore;
import gh.nusashell.nusadesk.application.webapp.WebAppRegistry;
import gh.nusashell.nusadesk.application.webapp.WebAppStore;
import gh.nusashell.nusadesk.domain.terminal.TerminalCommand;
import gh.nusashell.nusadesk.domain.terminal.TerminalCommandApp;
import gh.nusashell.nusadesk.domain.terminal.TerminalCommandAppId;
import gh.nusashell.nusadesk.domain.webapp.WebAppDefinition;
import gh.nusashell.nusadesk.domain.webapp.WebAppId;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowLooper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Add form's kind is now a Type dropdown (a native Spinner), and the
 * selection that reaches it — through real framework inflation and selection
 * dispatch — must map 1:1 to the two field groups and survive a draft
 * round-trip. Asserted here without a device.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 31})
public class AppFormKindSelectTest {

    private static final String ICON =
            "content://com.android.providers.media.documents/document/image%3A1000000123";

    private static AppFormView form() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        Context context = activity;
        AppFormView form = new AppFormView(context,
                new WebAppRegistry(new InMemoryWebAppStore()),
                new TerminalCommandRegistry(new InMemoryCommandStore()));
        activity.setContentView(form);
        idle();
        return form;
    }

    private static void idle() {
        // Shadow.extract, not Shadows.shadowOf: the latter class has an
        // overload that references FingerprintManager, which this project's
        // compile-time android.jar does not expose (see AndroidLocationStream
        // SessionTest for the same note).
        ShadowLooper main = Shadow.extract(Looper.getMainLooper());
        main.idle();
    }

    @Test
    public void aNewFormDefaultsToWebAppOnTheTypeDropdown() {
        AppFormView form = form();
        form.bindNew();

        Spinner spinner = form.findViewById(R.id.app_kind_spinner);
        assertEquals("new forms must default to the web-app kind (row 0)",
                0, spinner.getSelectedItemPosition());
        assertEquals(form.getContext().getString(R.string.app_form_type_web),
                spinner.getAdapter().getItem(0));
        assertEquals(form.getContext().getString(R.string.app_form_type_command),
                spinner.getAdapter().getItem(1));
        assertEquals(View.VISIBLE, form.findViewById(R.id.app_port_group).getVisibility());
        assertEquals(View.GONE, form.findViewById(R.id.app_command_group).getVisibility());
        assertEquals(View.VISIBLE, form.findViewById(R.id.app_form_type_label).getVisibility());
        assertEquals("the Type label must point at its control",
                R.id.app_kind_spinner,
                form.findViewById(R.id.app_form_type_label).getLabelFor());
    }

    @Test
    public void pickingCommandInTheDropdownSwapsTheFieldGroups() {
        AppFormView form = form();
        form.bindNew();
        idle(); // flush the bind-new selection/reset messages

        Spinner spinner = form.findViewById(R.id.app_kind_spinner);
        spinner.setSelection(1, false);
        idle();

        assertEquals(View.GONE, form.findViewById(R.id.app_port_group).getVisibility());
        assertEquals(View.VISIBLE, form.findViewById(R.id.app_command_group).getVisibility());

        spinner.setSelection(0, false);
        idle();
        assertEquals(View.VISIBLE, form.findViewById(R.id.app_port_group).getVisibility());
        assertEquals(View.GONE, form.findViewById(R.id.app_command_group).getVisibility());
    }

    @Test
    public void bindingAnExistingWebAppHidesTheTypeFieldAndShowsPort() {
        AppFormView form = form();
        form.bindExisting(new WebAppDefinition(
                WebAppId.of("notes"), "Notes", ICON, 8080, 1_000L, 1_000L, 0));
        idle();

        Spinner spinner = form.findViewById(R.id.app_kind_spinner);
        assertEquals(0, spinner.getSelectedItemPosition());
        assertEquals(View.GONE, form.findViewById(R.id.app_form_type_label).getVisibility());
        assertEquals(View.GONE, spinner.getVisibility());
        assertEquals(View.VISIBLE, form.findViewById(R.id.app_port_group).getVisibility());
        assertEquals(View.GONE, form.findViewById(R.id.app_command_group).getVisibility());
    }

    @Test
    public void bindingAnExistingCommandAppHidesTheTypeFieldAndShowsCommand() {
        AppFormView form = form();
        form.bindExisting(new TerminalCommandApp(
                TerminalCommandAppId.of("codex"), "Codex", ICON,
                TerminalCommand.of("codex"), 1_000L, 1_000L, 0));
        idle();

        Spinner spinner = form.findViewById(R.id.app_kind_spinner);
        assertEquals("an existing command app must leave the dropdown on its kind",
                1, spinner.getSelectedItemPosition());
        assertEquals(View.GONE, form.findViewById(R.id.app_form_type_label).getVisibility());
        assertEquals(View.GONE, spinner.getVisibility());
        assertEquals(View.GONE, form.findViewById(R.id.app_port_group).getVisibility());
        assertEquals(View.VISIBLE, form.findViewById(R.id.app_command_group).getVisibility());
    }

    @Test
    public void aSavedDraftRestoresTheKindAndTheDropdownSelection() {
        AppFormView form = form();
        form.bindNew();
        idle();
        Spinner original = form.findViewById(R.id.app_kind_spinner);
        original.setSelection(1, false);
        idle();

        Bundle draft = new Bundle();
        form.saveDraft(draft);

        AppFormView restored = form();
        assertTrue("the draft must be recognized", restored.restoreDraft(draft));
        idle();

        Spinner restoredSpinner = restored.findViewById(R.id.app_kind_spinner);
        assertEquals("the restored draft keeps the selected kind",
                1, restoredSpinner.getSelectedItemPosition());
        assertEquals(View.VISIBLE,
                restored.findViewById(R.id.app_command_group).getVisibility());
        assertEquals(View.GONE,
                restored.findViewById(R.id.app_port_group).getVisibility());
        assertEquals("a fresh draft keeps the Type dropdown visible",
                View.VISIBLE, restored.findViewById(R.id.app_form_type_label).getVisibility());
    }

    /** Deterministic in-memory stand-in for the persisted web-app store. */
    private static final class InMemoryWebAppStore implements WebAppStore {
        private final Map<WebAppId, WebAppDefinition> records = new LinkedHashMap<>();

        @Override
        public List<WebAppDefinition> loadAll() {
            return new ArrayList<>(records.values());
        }

        @Override
        public void save(WebAppDefinition definition) {
            records.put(definition.getId(), definition);
        }

        @Override
        public void delete(WebAppId webAppId) {
            records.remove(webAppId);
        }
    }

    /** Deterministic in-memory stand-in for the persisted command-app store. */
    private static final class InMemoryCommandStore implements TerminalCommandStore {
        private final Map<TerminalCommandAppId, TerminalCommandApp> records =
                new LinkedHashMap<>();

        @Override
        public List<TerminalCommandApp> loadAll() {
            return new ArrayList<>(records.values());
        }

        @Override
        public void save(TerminalCommandApp app) {
            records.put(app.getId(), app);
        }

        @Override
        public void delete(TerminalCommandAppId appId) {
            records.remove(appId);
        }
    }
}