package gh.nusashell.nusadesk.presentation.desktop;

import gh.nusashell.nusadesk.R;
import gh.nusashell.nusadesk.application.terminal.TerminalCommandRegistryException;
import gh.nusashell.nusadesk.application.webapp.WebAppRegistryException;
import gh.nusashell.nusadesk.domain.terminal.TerminalCommand;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * The add/edit form renders the registries' rejections, so which field a
 * failure belongs to — and which kind's wording it carries — is pure policy
 * and is asserted here without a device.
 */
public class AppFormErrorTest {

    private static WebAppRegistryException webFailure(WebAppRegistryException.Reason reason) {
        return new WebAppRegistryException(reason, "detail for " + reason);
    }

    private static TerminalCommandRegistryException commandFailure(
            TerminalCommandRegistryException.Reason reason) {
        return new TerminalCommandRegistryException(reason, "detail for " + reason);
    }

    @Test
    public void everyRegistryReasonRendersSomewhereOnTheForm() {
        for (WebAppRegistryException.Reason reason : WebAppRegistryException.Reason.values()) {
            AppFormError error = AppFormError.from(webFailure(reason), 8080);

            assertNotNull(reason.name(), error.getField());
            assertTrue(reason.name(), error.getMessageRes() != 0);
        }
        for (TerminalCommandRegistryException.Reason reason
                : TerminalCommandRegistryException.Reason.values()) {
            AppFormError error = AppFormError.from(commandFailure(reason));

            assertNotNull(reason.name(), error.getField());
            assertTrue(reason.name(), error.getMessageRes() != 0);
        }
    }

    @Test
    public void nameAndImageFailuresAreAttachedToTheirOwnFieldForBothKinds() {
        assertEquals(AppFormError.Field.NAME, AppFormError.from(
                webFailure(WebAppRegistryException.Reason.INVALID_NAME), 8080).getField());
        assertEquals(AppFormError.Field.NAME, AppFormError.from(
                commandFailure(TerminalCommandRegistryException.Reason.INVALID_NAME)).getField());
        assertEquals(AppFormError.Field.IMAGE, AppFormError.from(
                webFailure(WebAppRegistryException.Reason.INVALID_ICON_URI), 8080).getField());
        assertEquals(AppFormError.Field.IMAGE, AppFormError.from(
                commandFailure(TerminalCommandRegistryException.Reason.INVALID_ICON_URI)).getField());
    }

    @Test
    public void sharedFieldFailuresUseTheSharedStrings() {
        assertEquals(R.string.webapp_error_name, AppFormError.from(
                commandFailure(TerminalCommandRegistryException.Reason.INVALID_NAME))
                .getMessageRes());
        assertEquals(R.string.webapp_error_image, AppFormError.from(
                commandFailure(TerminalCommandRegistryException.Reason.INVALID_ICON_URI))
                .getMessageRes());
    }

    @Test
    public void everyPortFailureIsAttachedToThePortField() {
        assertEquals(AppFormError.Field.PORT, AppFormError.from(
                webFailure(WebAppRegistryException.Reason.INVALID_PORT), 8080).getField());
        assertEquals(AppFormError.Field.PORT, AppFormError.from(
                webFailure(WebAppRegistryException.Reason.PORT_RESERVED), 8080).getField());
        assertEquals(AppFormError.Field.PORT, AppFormError.from(
                webFailure(WebAppRegistryException.Reason.PORT_IN_USE), 8080).getField());
    }

    @Test
    public void theReservedPortSaysWhichPortIsReservedAndNeedsNoArgument() {
        AppFormError error = AppFormError.from(
                webFailure(WebAppRegistryException.Reason.PORT_RESERVED), 22022);

        assertEquals(R.string.webapp_error_port_reserved, error.getMessageRes());
        assertFalse(error.hasIntArgument());
    }

    @Test
    public void portInUseNamesThePortTheUserTyped() {
        AppFormError error = AppFormError.from(
                webFailure(WebAppRegistryException.Reason.PORT_IN_USE), 8080);

        assertEquals(R.string.webapp_error_port_in_use, error.getMessageRes());
        assertTrue(error.hasIntArgument());
        assertEquals(8080, error.getIntArgument());
    }

    @Test
    public void anInvalidCommandIsAttachedToTheCommandFieldAndNamesTheLimit() {
        AppFormError error = AppFormError.from(
                commandFailure(TerminalCommandRegistryException.Reason.INVALID_COMMAND));

        assertEquals(AppFormError.Field.COMMAND, error.getField());
        assertEquals(R.string.terminal_error_command, error.getMessageRes());
        assertTrue(error.hasIntArgument());
        assertEquals(TerminalCommand.MAX_LENGTH, error.getIntArgument());
    }

    @Test
    public void reasonsWithNoFieldOfTheirOwnAreReportedAtFormLevel() {
        assertEquals(AppFormError.Field.FORM, AppFormError.from(
                webFailure(WebAppRegistryException.Reason.UNKNOWN_APP), 8080).getField());
        assertEquals(AppFormError.Field.FORM, AppFormError.from(
                webFailure(WebAppRegistryException.Reason.STORAGE_FAILURE), 8080).getField());
        assertEquals(AppFormError.Field.FORM, AppFormError.from(
                commandFailure(TerminalCommandRegistryException.Reason.UNKNOWN_APP)).getField());
        assertEquals(AppFormError.Field.FORM, AppFormError.from(
                commandFailure(TerminalCommandRegistryException.Reason.STORAGE_FAILURE)).getField());
    }

    @Test
    public void formLevelFailuresUseTheKindOwnWording() {
        assertEquals(R.string.webapp_error_unknown_app, AppFormError.from(
                webFailure(WebAppRegistryException.Reason.UNKNOWN_APP), 8080).getMessageRes());
        assertEquals(R.string.terminal_error_unknown_app, AppFormError.from(
                commandFailure(TerminalCommandRegistryException.Reason.UNKNOWN_APP))
                .getMessageRes());
        assertEquals(R.string.webapp_error_storage, AppFormError.from(
                webFailure(WebAppRegistryException.Reason.STORAGE_FAILURE), 8080).getMessageRes());
        assertEquals(R.string.terminal_error_storage, AppFormError.from(
                commandFailure(TerminalCommandRegistryException.Reason.STORAGE_FAILURE))
                .getMessageRes());
    }

    @Test
    public void aStorageFailureCarriesTheNonSecretReasonItWasGiven() {
        AppFormError web = AppFormError.from(new WebAppRegistryException(
                WebAppRegistryException.Reason.STORAGE_FAILURE,
                "could not persist web app abc"), 8080);
        AppFormError command = AppFormError.from(new TerminalCommandRegistryException(
                TerminalCommandRegistryException.Reason.STORAGE_FAILURE,
                "could not persist terminal-command app abc"));

        assertTrue(web.hasDetail());
        assertEquals("could not persist web app abc", web.getDetail());
        assertTrue(command.hasDetail());
        assertEquals("could not persist terminal-command app abc", command.getDetail());
    }

    @Test
    public void aFailureWithNoMessageStillRendersWithoutPrintingNull() {
        AppFormError web = AppFormError.from(new WebAppRegistryException(
                WebAppRegistryException.Reason.STORAGE_FAILURE, null), 8080);
        AppFormError command = AppFormError.from(new TerminalCommandRegistryException(
                TerminalCommandRegistryException.Reason.STORAGE_FAILURE, null));

        assertTrue(web.hasDetail());
        assertEquals("", web.getDetail());
        assertTrue(command.hasDetail());
        assertEquals("", command.getDetail());
    }
}
