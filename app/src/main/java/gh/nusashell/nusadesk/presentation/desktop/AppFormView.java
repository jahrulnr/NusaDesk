package gh.nusashell.nusadesk.presentation.desktop;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.Spinner;
import android.widget.ScrollView;
import android.widget.TextView;

import gh.nusashell.nusadesk.R;
import gh.nusashell.nusadesk.application.terminal.TerminalCommandRegistry;
import gh.nusashell.nusadesk.application.terminal.TerminalCommandRegistryException;
import gh.nusashell.nusadesk.application.webapp.WebAppRegistry;
import gh.nusashell.nusadesk.application.webapp.WebAppRegistryException;
import gh.nusashell.nusadesk.domain.terminal.TerminalCommandApp;
import gh.nusashell.nusadesk.domain.webapp.WebAppDefinition;

/**
 * The add / edit launcher app form, covering both kinds of user-defined
 * launcher entry.
 *
 * <p>A local web app is a required name, an optional image chosen through the
 * system document picker, and the guest port its server listens on — still no
 * URL, host, credential, or profile field, because the endpoint is generated
 * from the validated port (ADR-0014). A terminal-command app is the same name
 * and image plus one guest command line; the host never executes it, so there
 * is deliberately no shell, cwd, or environment field.</p>
 *
 * <p>The kind is chosen once, while adding: a saved app's kind never changes,
 * so {@code bindExisting} hides the Type dropdown and shows only the matching
 * field group. Switching kinds while adding keeps the typed name and the
 * chosen image — both kinds share them — and clears the field errors, which no
 * longer describe the field the user is looking at.</p>
 *
 * <p>Validation is not re-implemented here. The form hands the raw values to
 * the matching registry ({@link WebAppRegistry} or {@link
 * TerminalCommandRegistry}) and renders whatever it rejects through {@link
 * AppFormError}, which keeps the domain rules the only enforcement and keeps
 * the UI honest: a field error is always the rule that actually failed, and it
 * is attached to the field that caused it, focused, and announced.</p>
 *
 * <p>The image is stored as the picker's opaque {@code content://} token after
 * the persistable read permission has been taken. A picker result whose
 * permission cannot be persisted is refused rather than stored as a token that
 * would stop resolving after a restart.</p>
 *
 * <p>This view is never inflated from XML — it is built by the shell, which
 * owns the registries — so it deliberately has no {@code (Context)}-only
 * constructor to offer.</p>
 */
@SuppressLint("ViewConstructor")
public final class AppFormView extends ScrollView {

    /** Request code the host forwards from {@code onActivityResult}. */
    public static final int REQUEST_OPEN_IMAGE = 0x5701;

    private static final String STATE_DRAFT = "appForm.draft";
    private static final String STATE_KIND = "appForm.kind";
    private static final String STATE_EDITING_ID = "appForm.editingId";
    private static final String STATE_NAME = "appForm.name";
    private static final String STATE_ICON_URI = "appForm.iconUri";
    private static final String STATE_PORT = "appForm.port";
    private static final String STATE_COMMAND = "appForm.command";

    /** Receives the outcome the host has to act on (navigate, refresh, remove). */
    public interface Listener {
        /** The form persisted a new or edited web app. */
        void onWebAppSaved(WebAppDefinition definition);

        /** The form persisted a new or edited terminal-command app. */
        void onCommandAppSaved(TerminalCommandApp app);

        /** The form removed a web app. */
        void onWebAppDeleted(String webAppId);

        /** The form removed a terminal-command app. */
        void onCommandAppDeleted(String commandAppId);
    }

    /** Which registry and which kind-specific field group the form submits to. */
    private enum Kind { WEB, COMMAND }

    private final WebAppRegistry webApps;
    private final TerminalCommandRegistry commands;

    private TextView heading;
    private TextView typeLabel;
    private Spinner kindSpinner;
    private boolean bindingKind;
    private TextView body;
    private EditText nameInput;
    private TextView nameError;
    private ImageView imagePreview;
    private Button imageChoose;
    private Button imageRemove;
    private TextView imageError;
    private View portGroup;
    private EditText portInput;
    private TextView portError;
    private View commandGroup;
    private EditText commandInput;
    private TextView commandError;
    private TextView formError;
    private Button saveButton;
    private Button deleteButton;

    private Kind kind = Kind.WEB;
    private String editingId;
    private String iconUri;
    private Listener listener;

    public AppFormView(Context context, WebAppRegistry webApps,
                       TerminalCommandRegistry commands) {
        super(context);
        if (webApps == null) {
            throw new IllegalArgumentException("webApps must not be null");
        }
        if (commands == null) {
            throw new IllegalArgumentException("commands must not be null");
        }
        this.webApps = webApps;
        this.commands = commands;
        init();
    }

    public AppFormView(Context context, AttributeSet attrs, WebAppRegistry webApps,
                       TerminalCommandRegistry commands) {
        super(context, attrs);
        if (webApps == null) {
            throw new IllegalArgumentException("webApps must not be null");
        }
        if (commands == null) {
            throw new IllegalArgumentException("commands must not be null");
        }
        this.webApps = webApps;
        this.commands = commands;
        init();
    }

    private void init() {
        LayoutInflater.from(getContext()).inflate(R.layout.widget_add_app, this, true);
        heading = findViewById(R.id.app_form_heading);
        typeLabel = findViewById(R.id.app_form_type_label);
        kindSpinner = findViewById(R.id.app_kind_spinner);
        body = findViewById(R.id.app_form_body);
        nameInput = findViewById(R.id.app_name_input);
        nameError = findViewById(R.id.app_name_error);
        imagePreview = findViewById(R.id.app_image_preview);
        imageChoose = findViewById(R.id.app_image_choose);
        imageRemove = findViewById(R.id.app_image_remove);
        imageError = findViewById(R.id.app_image_error);
        portGroup = findViewById(R.id.app_port_group);
        portInput = findViewById(R.id.app_port_input);
        portError = findViewById(R.id.app_port_error);
        commandGroup = findViewById(R.id.app_command_group);
        commandInput = findViewById(R.id.app_command_input);
        commandError = findViewById(R.id.app_command_error);
        formError = findViewById(R.id.app_form_error);
        saveButton = findViewById(R.id.app_save);
        deleteButton = findViewById(R.id.app_delete);

        ArrayAdapter<String> kindAdapter = new ArrayAdapter<>(getContext(),
                R.layout.nusadesk_dialog_spinner_item,
                new String[]{
                        getContext().getString(R.string.app_form_type_web),
                        getContext().getString(R.string.app_form_type_command)});
        kindAdapter.setDropDownViewResource(R.layout.nusadesk_dialog_spinner_dropdown_item);
        kindSpinner.setAdapter(kindAdapter);
        kindSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (!bindingKind) {
                    selectKind(position == 1 ? Kind.COMMAND : Kind.WEB);
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        imageChoose.setOnClickListener(view -> pickImage());
        imageRemove.setOnClickListener(view -> clearImage());
        saveButton.setOnClickListener(view -> save());
        deleteButton.setOnClickListener(view -> confirmDelete());
        renderImage();
    }

    /** Wires the outcome the host has to act on. */
    public void setListener(Listener listener) {
        this.listener = listener;
    }

    /** Prepares the form for a new app, with the Type dropdown on web app. */
    public void bindNew() {
        editingId = null;
        iconUri = null;
        nameInput.setText("");
        portInput.setText("");
        commandInput.setText("");
        // setSelection() with the suppression guard moves the dropdown without
        // re-firing selectKind; setting the fields again below keeps the end
        // state identical either way.
        kind = Kind.WEB;
        selectKindInSpinner(Kind.WEB);
        renderKind();
        clearErrors();
        heading.setText(R.string.webapp_add_title);
        setTypeFieldVisible(true);
        saveButton.setText(R.string.webapp_save);
        deleteButton.setVisibility(GONE);
        renderImage();
        focusFirstField();
    }

    /** Prepares the form for an existing web app. */
    public void bindExisting(WebAppDefinition definition) {
        editingId = definition.getId().value();
        kind = Kind.WEB;
        iconUri = definition.getIconUri();
        nameInput.setText(definition.getDisplayName());
        portInput.setText(String.valueOf(definition.getGuestPort()));
        commandInput.setText("");
        selectKindInSpinner(Kind.WEB);
        setTypeFieldVisible(false);
        renderKind();
        clearErrors();
        heading.setText(R.string.webapp_edit_title);
        saveButton.setText(R.string.webapp_save_edit);
        deleteButton.setVisibility(VISIBLE);
        renderImage();
    }

    /** Prepares the form for an existing terminal-command app. */
    public void bindExisting(TerminalCommandApp app) {
        editingId = app.getId().value();
        kind = Kind.COMMAND;
        iconUri = app.getIconUri();
        nameInput.setText(app.getDisplayName());
        portInput.setText("");
        commandInput.setText(app.getCommand().value());
        selectKindInSpinner(Kind.COMMAND);
        setTypeFieldVisible(false);
        renderKind();
        clearErrors();
        heading.setText(R.string.webapp_edit_title);
        saveButton.setText(R.string.webapp_save_edit);
        deleteButton.setVisibility(VISIBLE);
        renderImage();
    }

    /** True when the form is editing an existing app. */
    public boolean isEditing() {
        return editingId != null;
    }

    /**
     * Saves the unsaved form draft so it can survive Activity recreation while
     * the system document picker is in front. The icon is only a content URI
     * token; its persistable grant is acquired by {@link #onImagePicked}.
     */
    public void saveDraft(Bundle outState) {
        if (outState == null) {
            throw new IllegalArgumentException("outState must not be null");
        }
        outState.putBoolean(STATE_DRAFT, true);
        outState.putString(STATE_KIND, kind.name());
        outState.putString(STATE_EDITING_ID, editingId);
        outState.putString(STATE_NAME, nameInput.getText().toString());
        outState.putString(STATE_ICON_URI, iconUri);
        outState.putString(STATE_PORT, portInput.getText().toString());
        outState.putString(STATE_COMMAND, commandInput.getText().toString());
    }

    /**
     * Restores a draft captured before Activity recreation. A saved app's kind
     * remains fixed; a new draft restores the kind the user had selected before
     * the image picker interrupted the form.
     *
     * @return true when the state bundle held an app-form draft
     */
    public boolean restoreDraft(Bundle state) {
        if (state == null || !state.getBoolean(STATE_DRAFT, false)) {
            return false;
        }
        String rawKind = state.getString(STATE_KIND, Kind.WEB.name());
        try {
            kind = Kind.valueOf(rawKind);
        } catch (IllegalArgumentException invalidKind) {
            kind = Kind.WEB;
        }
        editingId = state.getString(STATE_EDITING_ID);
        iconUri = state.getString(STATE_ICON_URI);
        nameInput.setText(state.getString(STATE_NAME, ""));
        portInput.setText(state.getString(STATE_PORT, ""));
        commandInput.setText(state.getString(STATE_COMMAND, ""));
        selectKindInSpinner(kind);
        setTypeFieldVisible(!isEditing());
        renderKind();
        clearErrors();
        heading.setText(isEditing() ? R.string.webapp_edit_title : R.string.webapp_add_title);
        saveButton.setText(isEditing() ? R.string.webapp_save_edit : R.string.webapp_save);
        deleteButton.setVisibility(isEditing() ? VISIBLE : GONE);
        renderImage();
        return true;
    }

    /**
     * Switches the kind-specific field group. The typed name and the chosen
     * image survive because both kinds share them; the stale field errors do
     * not.
     */
    private void selectKind(Kind selected) {
        kind = selected;
        renderKind();
        clearErrors();
    }

    /**
     * Moves the Type dropdown to the given kind without treating it as a user
     * choice. {@code setSelection(position, false)} skips the drop animation,
     * and the guard keeps {@code onItemSelected} (which the framework delivers
     * as a queued message, after this method returns) inert, so programmatic
     * binding never re-enters {@link #selectKind}.
     */
    private void selectKindInSpinner(Kind selected) {
        bindingKind = true;
        kindSpinner.setSelection(selected == Kind.COMMAND ? 1 : 0, false);
        kindSpinner.post(() -> bindingKind = false);
    }

    /** Shows or hides the whole Type field: its label and its dropdown. */
    private void setTypeFieldVisible(boolean visible) {
        typeLabel.setVisibility(visible ? VISIBLE : GONE);
        kindSpinner.setVisibility(visible ? VISIBLE : GONE);
    }

    private void renderKind() {
        boolean web = kind == Kind.WEB;
        portGroup.setVisibility(web ? VISIBLE : GONE);
        commandGroup.setVisibility(web ? GONE : VISIBLE);
        body.setText(web ? R.string.webapp_form_body : R.string.terminal_form_body);
    }

    /** Opens the system image picker. Called by the form itself. */
    @SuppressWarnings("deprecation")
    private void pickImage() {
        Context context = getContext();
        if (!(context instanceof Activity)) {
            return;
        }
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("image/*")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            ((Activity) context).startActivityForResult(intent, REQUEST_OPEN_IMAGE);
        } catch (ActivityNotFoundException noPicker) {
            renderError(AppFormError.Field.IMAGE, R.string.webapp_error_image, null, null);
        }
    }

    /**
     * Handles the picker result. The host forwards it because the framework
     * delivers it to the Activity.
     */
    public void onImagePicked(int resultCode, Intent data) {
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        Uri uri = data.getData();
        try {
            getContext().getContentResolver().takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException | IllegalArgumentException notPersistable) {
            // A token whose permission cannot be persisted would stop resolving
            // after a restart, so it is refused now instead of stored.
            renderError(AppFormError.Field.IMAGE, R.string.webapp_error_image, null, null);
            return;
        }
        try {
            // Each kind's model owns its own identical copy of the token rule,
            // so the icon is checked against the kind it will be stored under.
            iconUri = kind == Kind.COMMAND
                    ? TerminalCommandApp.normalizeIconUri(uri.toString())
                    : WebAppDefinition.normalizeIconUri(uri.toString());
        } catch (IllegalArgumentException invalid) {
            renderError(AppFormError.Field.IMAGE, R.string.webapp_error_image, null, null);
            return;
        }
        clearErrors();
        renderImage();
    }

    private void clearImage() {
        iconUri = null;
        clearErrors();
        renderImage();
    }

    // ---- Save / delete ----

    private void save() {
        clearErrors();
        if (kind == Kind.COMMAND) {
            saveCommandApp();
        } else {
            saveWebApp();
        }
    }

    private void saveWebApp() {
        int typedPort = parsePort(portInput.getText().toString());
        try {
            WebAppDefinition saved = isEditing()
                    ? webApps.update(editingId,
                            nameInput.getText().toString(), iconUri, typedPort)
                    : webApps.add(
                            nameInput.getText().toString(), iconUri, typedPort);
            if (listener != null) {
                listener.onWebAppSaved(saved);
            }
        } catch (WebAppRegistryException failure) {
            AppFormError error = AppFormError.from(failure, typedPort);
            renderError(error.getField(), error.getMessageRes(),
                    error.hasIntArgument() ? Integer.valueOf(error.getIntArgument()) : null,
                    error.hasDetail() ? error.getDetail() : null);
        }
    }

    private void saveCommandApp() {
        try {
            TerminalCommandApp saved = isEditing()
                    ? commands.update(editingId,
                            nameInput.getText().toString(), iconUri,
                            commandInput.getText().toString())
                    : commands.add(
                            nameInput.getText().toString(), iconUri,
                            commandInput.getText().toString());
            if (listener != null) {
                listener.onCommandAppSaved(saved);
            }
        } catch (TerminalCommandRegistryException failure) {
            AppFormError error = AppFormError.from(failure);
            renderError(error.getField(), error.getMessageRes(),
                    error.hasIntArgument() ? Integer.valueOf(error.getIntArgument()) : null,
                    error.hasDetail() ? error.getDetail() : null);
        }
    }

    private void confirmDelete() {
        if (!isEditing()) {
            return;
        }
        Context context = getContext();
        if (!(context instanceof Activity)) {
            return;
        }
        new AlertDialog.Builder(context)
                .setTitle(R.string.webapp_delete_confirm_title)
                .setMessage(getContext().getString(
                        kind == Kind.COMMAND
                                ? R.string.terminal_app_delete_confirm_body
                                : R.string.webapp_delete_confirm_body,
                        nameInput.getText().toString().trim()))
                .setPositiveButton(R.string.webapp_delete_confirm_action, (dialog, which) -> {
                    if (kind == Kind.COMMAND) {
                        commands.delete(editingId);
                        if (listener != null) {
                            listener.onCommandAppDeleted(editingId);
                        }
                    } else {
                        webApps.delete(editingId);
                        if (listener != null) {
                            listener.onWebAppDeleted(editingId);
                        }
                    }
                })
                .setNegativeButton(R.string.webapp_cancel, null)
                .show();
    }

    /** @return the typed port, or {@code -1} so the registry reports it as invalid. */
    private static int parsePort(String text) {
        if (text == null || text.trim().isEmpty()) {
            return -1;
        }
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException notANumber) {
            return -1;
        }
    }

    // ---- Rendering ----

    private void renderImage() {
        boolean hasImage = iconUri != null;
        imageRemove.setVisibility(hasImage ? VISIBLE : GONE);
        imagePreview.setImageResource(R.drawable.ic_image_placeholder);
        if (hasImage) {
            try {
                imagePreview.setImageURI(Uri.parse(iconUri));
            } catch (RuntimeException unresolved) {
                imagePreview.setImageResource(R.drawable.ic_image_placeholder);
            }
        }
        imagePreview.setContentDescription(hasImage
                ? getContext().getString(R.string.webapp_image_preview_desc)
                : getContext().getString(R.string.webapp_image_none));
    }

    private void focusFirstField() {
        nameInput.requestFocus();
    }

    private void clearErrors() {
        hide(nameError);
        hide(imageError);
        hide(portError);
        hide(commandError);
        hide(formError);
    }

    private void hide(TextView view) {
        view.setText("");
        view.setVisibility(GONE);
    }

    private void renderError(AppFormError.Field field, int messageRes,
                             Integer intArgument, String detail) {
        clearErrors();
        Object[] args = intArgument != null
                ? new Object[]{intArgument}
                : detail != null ? new Object[]{detail} : new Object[0];
        String message = getContext().getString(messageRes, args);
        TextView target;
        View focus;
        switch (field) {
            case NAME:
                target = nameError;
                focus = nameInput;
                break;
            case IMAGE:
                target = imageError;
                focus = imageChoose;
                break;
            case PORT:
                target = portError;
                focus = portInput;
                break;
            case COMMAND:
                target = commandError;
                focus = commandInput;
                break;
            default:
                target = formError;
                focus = saveButton;
                break;
        }
        target.setText(message);
        target.setVisibility(VISIBLE);
        focus.requestFocus();
        focus.announceForAccessibility(message);
    }
}
