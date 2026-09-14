package gh.nusashell.nusadesk.presentation.webapp;

import gh.nusashell.nusadesk.R;
import gh.nusashell.nusadesk.application.webapp.WebAppRegistryException;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * The add/edit form renders the registry's rejections, so which field a failure
 * belongs to is pure policy and is asserted here without a device.
 */
public class WebAppFormErrorTest {

    private static WebAppRegistryException failure(WebAppRegistryException.Reason reason) {
        return new WebAppRegistryException(reason, "detail for " + reason);
    }

    @Test
    public void everyRegistryReasonRendersSomewhereOnTheForm() {
        for (WebAppRegistryException.Reason reason : WebAppRegistryException.Reason.values()) {
            WebAppFormError error = WebAppFormError.from(failure(reason), 8080);

            assertNotNull(reason.name(), error.getField());
            assertTrue(reason.name(), error.getMessageRes() != 0);
        }
    }

    @Test
    public void nameAndImageFailuresAreAttachedToTheirOwnField() {
        assertEquals(WebAppFormError.Field.NAME, WebAppFormError.from(
                failure(WebAppRegistryException.Reason.INVALID_NAME), 8080).getField());
        assertEquals(WebAppFormError.Field.IMAGE, WebAppFormError.from(
                failure(WebAppRegistryException.Reason.INVALID_ICON_URI), 8080).getField());
    }

    @Test
    public void everyPortFailureIsAttachedToThePortField() {
        assertEquals(WebAppFormError.Field.PORT, WebAppFormError.from(
                failure(WebAppRegistryException.Reason.INVALID_PORT), 8080).getField());
        assertEquals(WebAppFormError.Field.PORT, WebAppFormError.from(
                failure(WebAppRegistryException.Reason.PORT_RESERVED), 8080).getField());
        assertEquals(WebAppFormError.Field.PORT, WebAppFormError.from(
                failure(WebAppRegistryException.Reason.PORT_IN_USE), 8080).getField());
    }

    @Test
    public void theReservedPortSaysWhichPortIsReservedAndNeedsNoArgument() {
        WebAppFormError error = WebAppFormError.from(
                failure(WebAppRegistryException.Reason.PORT_RESERVED), 22022);

        assertEquals(R.string.webapp_error_port_reserved, error.getMessageRes());
        assertFalse(error.hasPortArgument());
    }

    @Test
    public void portInUseNamesThePortTheUserTyped() {
        WebAppFormError error = WebAppFormError.from(
                failure(WebAppRegistryException.Reason.PORT_IN_USE), 8080);

        assertEquals(R.string.webapp_error_port_in_use, error.getMessageRes());
        assertTrue(error.hasPortArgument());
        assertEquals(8080, error.getPortArgument());
    }

    @Test
    public void reasonsWithNoFieldOfTheirOwnAreReportedAtFormLevel() {
        assertEquals(WebAppFormError.Field.FORM, WebAppFormError.from(
                failure(WebAppRegistryException.Reason.UNKNOWN_APP), 8080).getField());
        assertEquals(WebAppFormError.Field.FORM, WebAppFormError.from(
                failure(WebAppRegistryException.Reason.STORAGE_FAILURE), 8080).getField());
    }

    @Test
    public void aStorageFailureCarriesTheNonSecretReasonItWasGiven() {
        WebAppFormError error = WebAppFormError.from(
                new WebAppRegistryException(WebAppRegistryException.Reason.STORAGE_FAILURE,
                        "could not persist web app abc"), 8080);

        assertEquals(R.string.webapp_error_storage, error.getMessageRes());
        assertTrue(error.hasDetail());
        assertEquals("could not persist web app abc", error.getDetail());
    }

    @Test
    public void aFailureWithNoMessageStillRendersWithoutPrintingNull() {
        WebAppFormError error = WebAppFormError.from(
                new WebAppRegistryException(WebAppRegistryException.Reason.STORAGE_FAILURE, null),
                8080);

        assertTrue(error.hasDetail());
        assertEquals("", error.getDetail());
    }
}
