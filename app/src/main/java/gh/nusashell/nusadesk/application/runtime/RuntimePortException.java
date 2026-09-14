package gh.nusashell.nusadesk.application.runtime;

/** Checked failure at the runtime endpoint boundary. */
public class RuntimePortException extends Exception {
    public RuntimePortException(String message) {
        super(message);
    }

    public RuntimePortException(String message, Throwable cause) {
        super(message, cause);
    }
}
