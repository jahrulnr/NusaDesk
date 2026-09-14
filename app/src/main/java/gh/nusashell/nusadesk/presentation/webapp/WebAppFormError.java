package gh.nusashell.nusadesk.presentation.webapp;

import gh.nusashell.nusadesk.R;
import gh.nusashell.nusadesk.application.webapp.WebAppRegistryException;

/**
 * Where an add/edit web app failure belongs on the form.
 *
 * <p>The registry publishes a machine-readable reason, so the form can attach
 * the message to the field the user has to fix instead of printing one line at
 * the bottom and making them guess. This mapping is pure so it can be asserted
 * without a device, and it is exhaustive over
 * {@link WebAppRegistryException.Reason} so a new reason cannot silently render
 * nowhere.</p>
 */
public final class WebAppFormError {

    /** The form field a failure belongs to. {@code FORM} means "not one field". */
    public enum Field { NAME, IMAGE, PORT, FORM }

    private final Field field;
    private final int messageRes;
    private final int portArgument;
    private final String detail;

    private WebAppFormError(Field field, int messageRes, int portArgument, String detail) {
        this.field = field;
        this.messageRes = messageRes;
        this.portArgument = portArgument;
        this.detail = detail;
    }

    /**
     * Maps a registry failure to a field and a message.
     *
     * @param failure   the expected failure the registry threw
     * @param typedPort the port the user typed, or any sentinel when the form had
     *                  no port to report; used only for the port-in-use message
     */
    public static WebAppFormError from(WebAppRegistryException failure, int typedPort) {
        switch (failure.getReason()) {
            case INVALID_NAME:
                return new WebAppFormError(Field.NAME, R.string.webapp_error_name, -1, null);
            case INVALID_ICON_URI:
                return new WebAppFormError(Field.IMAGE, R.string.webapp_error_image, -1, null);
            case INVALID_PORT:
                return new WebAppFormError(Field.PORT, R.string.webapp_error_port, -1, null);
            case PORT_RESERVED:
                return new WebAppFormError(
                        Field.PORT, R.string.webapp_error_port_reserved, -1, null);
            case PORT_IN_USE:
                return new WebAppFormError(
                        Field.PORT, R.string.webapp_error_port_in_use, typedPort, null);
            case UNKNOWN_APP:
                return new WebAppFormError(
                        Field.FORM, R.string.webapp_error_unknown_app, -1, null);
            case STORAGE_FAILURE:
            default:
                String reason = failure.getMessage();
                return new WebAppFormError(Field.FORM, R.string.webapp_error_storage, -1,
                        reason == null || reason.trim().isEmpty() ? "" : reason);
        }
    }

    public Field getField() {
        return field;
    }

    public int getMessageRes() {
        return messageRes;
    }

    /** True when the message needs the port the user typed as its argument. */
    public boolean hasPortArgument() {
        return portArgument >= 0;
    }

    public int getPortArgument() {
        return portArgument;
    }

    /** True when the message needs the registry's own non-secret detail. */
    public boolean hasDetail() {
        return detail != null;
    }

    public String getDetail() {
        return detail;
    }
}
