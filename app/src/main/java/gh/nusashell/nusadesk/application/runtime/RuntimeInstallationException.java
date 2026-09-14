package gh.nusashell.nusadesk.application.runtime;

/** Checked failure raised while installing a curated runtime payload. */
public class RuntimeInstallationException extends Exception {
    public RuntimeInstallationException(String message) {
        super(message);
    }

    public RuntimeInstallationException(String message, Throwable cause) {
        super(message, cause);
    }
}
