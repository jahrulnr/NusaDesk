package gh.nusashell.nusadesk.presentation.webapp;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.TextView;

import gh.nusashell.nusadesk.R;
import gh.nusashell.nusadesk.application.webapp.WebAppRegistry;
import gh.nusashell.nusadesk.application.webapp.WebAppRegistryException;
import gh.nusashell.nusadesk.domain.webapp.WebAppDefinition;

/**
 * The add / edit web app form.
 *
 * <p>Three inputs and no more: a required name, an optional image chosen through
 * the system document picker, and the guest port the app serves on. There is
 * deliberately no URL, host, credential, or profile field — the endpoint is
 * generated from the validated port, so a user-defined app can never become a
 * general-purpose URL launcher (ADR-0014).</p>
 *
 * <p>Validation is not re-implemented here. The form hands the raw values to
 * {@link WebAppRegistry} and renders whatever it rejects, which keeps the
 * domain rules the only enforcement and keeps the UI honest: a field error is
 * always the rule that actually failed, and it is attached to the field that
 * caused it, focused, and announced.</p>
 *
 * <p>The image is stored as the picker's opaque {@code content://} token after
 * the persistable read permission has been taken. A picker result whose
 * permission cannot be persisted is refused rather than stored as a token that
 * would stop resolving after a restart.</p>
 *
 * <p>This view is never inflated from XML — it is built by the shell, which
 * owns the registry — so it deliberately has no {@code (Context)}-only
 * constructor to offer.</p>
 */
@SuppressLint("ViewConstructor")
public final class WebAppFormView extends ScrollView {

    /** Request code the host forwards from {@code onActivityResult}. */
    public static final int REQUEST_OPEN_IMAGE = 0x5701;

    /** Receives the outcome the host has to act on (navigate, refresh, remove). */
    public interface Listener {
        /** The form persisted a new or edited app. */
        void onWebAppSaved(WebAppDefinition definition);

        /** The form removed an app. */
        void onWebAppDeleted(String webAppId);
    }

    private final WebAppRegistry registry;

    private TextView heading;
    private EditText nameInput;
    private TextView nameError;
    private ImageView imagePreview;
    private Button imageChoose;
    private Button imageRemove;
    private TextView imageError;
    private EditText portInput;
    private TextView portError;
    private TextView formError;
    private Button saveButton;
    private Button deleteButton;

    private String editingId;
    private String iconUri;
    private Listener listener;

    public WebAppFormView(Context context, WebAppRegistry registry) {
        super(context);
        if (registry == null) {
            throw new IllegalArgumentException("registry must not be null");
        }
        this.registry = registry;
        init();
    }

    public WebAppFormView(Context context, AttributeSet attrs, WebAppRegistry registry) {
        super(context, attrs);
        if (registry == null) {
            throw new IllegalArgumentException("registry must not be null");
        }
        this.registry = registry;
        init();
    }

    private void init() {
        LayoutInflater.from(getContext()).inflate(R.layout.widget_add_web_app, this, true);
        heading = findViewById(R.id.webapp_form_heading);
        nameInput = findViewById(R.id.webapp_name_input);
        nameError = findViewById(R.id.webapp_name_error);
        imagePreview = findViewById(R.id.webapp_image_preview);
        imageChoose = findViewById(R.id.webapp_image_choose);
        imageRemove = findViewById(R.id.webapp_image_remove);
        imageError = findViewById(R.id.webapp_image_error);
        portInput = findViewById(R.id.webapp_port_input);
        portError = findViewById(R.id.webapp_port_error);
        formError = findViewById(R.id.webapp_form_error);
        saveButton = findViewById(R.id.webapp_save);
        deleteButton = findViewById(R.id.webapp_delete);

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

    /** Prepares the form for a new app. */
    public void bindNew() {
        editingId = null;
        iconUri = null;
        nameInput.setText("");
        portInput.setText("");
        clearErrors();
        heading.setText(R.string.webapp_add_title);
        saveButton.setText(R.string.webapp_save);
        deleteButton.setVisibility(GONE);
        renderImage();
        focusFirstField();
    }

    /** Prepares the form for an existing app. */
    public void bindExisting(WebAppDefinition definition) {
        editingId = definition.getId().value();
        iconUri = definition.getIconUri();
        nameInput.setText(definition.getDisplayName());
        portInput.setText(String.valueOf(definition.getGuestPort()));
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
            renderError(WebAppFormError.Field.IMAGE, R.string.webapp_error_image, null, null);
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
            renderError(WebAppFormError.Field.IMAGE, R.string.webapp_error_image, null, null);
            return;
        }
        try {
            iconUri = WebAppDefinition.normalizeIconUri(uri.toString());
        } catch (IllegalArgumentException invalid) {
            renderError(WebAppFormError.Field.IMAGE, R.string.webapp_error_image, null, null);
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
        int typedPort = parsePort(portInput.getText().toString());
        try {
            WebAppDefinition saved = isEditing()
                    ? registry.update(editingId,
                            nameInput.getText().toString(), iconUri, typedPort)
                    : registry.add(
                            nameInput.getText().toString(), iconUri, typedPort);
            if (listener != null) {
                listener.onWebAppSaved(saved);
            }
        } catch (WebAppRegistryException failure) {
            WebAppFormError error = WebAppFormError.from(failure, typedPort);
            renderError(error.getField(), error.getMessageRes(),
                    error.hasPortArgument() ? Integer.valueOf(error.getPortArgument()) : null,
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
                        R.string.webapp_delete_confirm_body, nameInput.getText().toString().trim()))
                .setPositiveButton(R.string.webapp_delete_confirm_action, (dialog, which) -> {
                    registry.delete(editingId);
                    if (listener != null) {
                        listener.onWebAppDeleted(editingId);
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
        hide(formError);
    }

    private void hide(TextView view) {
        view.setText("");
        view.setVisibility(GONE);
    }

    private void renderError(WebAppFormError.Field field, int messageRes,
                             Integer portArgument, String detail) {
        clearErrors();
        Object[] args = portArgument != null
                ? new Object[]{portArgument}
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
