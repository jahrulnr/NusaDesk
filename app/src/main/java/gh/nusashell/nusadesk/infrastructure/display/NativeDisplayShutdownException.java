package gh.nusashell.nusadesk.infrastructure.display;

import java.io.IOException;

/** Display shutdown failed before guest temporary storage may be cleared. */
public final class NativeDisplayShutdownException extends IOException {
    public NativeDisplayShutdownException(String message) { super(message); }
    public NativeDisplayShutdownException(String message, Throwable cause) { super(message, cause); }
}
