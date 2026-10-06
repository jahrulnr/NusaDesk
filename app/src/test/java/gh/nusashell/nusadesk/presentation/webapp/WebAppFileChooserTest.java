package gh.nusashell.nusadesk.presentation.webapp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.provider.MediaStore;
import android.view.View;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowActivity;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowApplication;
import org.robolectric.shadows.ShadowLooper;

import gh.nusashell.nusadesk.R;
import gh.nusashell.nusadesk.infrastructure.webapp.CameraCaptureProvider;

/**
 * The one-answer contract of the surface's file chooser (ADR-0059): the source
 * choice, the system picker's intent, the result read (including the clip data
 * a multi-selection arrives in, which the platform's own
 * {@code FileChooserParams.parseResult} drops), and the cancel paths that keep
 * a page's {@code <input type="file">} usable.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class WebAppFileChooserTest {

    private Activity activity;
    private WebAppFileChooser chooser;
    private RecordingCallback callback;

    @Before
    public void setUp() {
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        chooser = new WebAppFileChooser(activity);
        callback = new RecordingCallback();
    }

    @Test
    public void aReadOnlyRequestOpensTheSystemPickerWithTheFilter() {
        assertTrue(chooser.show(params(MODE_OPEN, new String[]{"image/*"}, false, null),
                callback));
        clickSource(SYSTEM_SOURCE);

        Intent started = startedIntent();
        assertEquals(Intent.ACTION_GET_CONTENT, started.getAction());
        assertEquals("image/*", started.getType());
        assertEquals(WebAppFileChooser.REQUEST_PICK_UPLOAD, startedRequestCode());
        assertTrue("the page waits for the picker's result", callback.answers.isEmpty());
    }

    @Test
    public void aFolderRequestSkipsTheSourceChoiceAndUsesTheTreePicker() {
        int dialogsBefore = ShadowAlertDialog.getShownDialogs().size();

        assertTrue(chooser.show(params(MODE_OPEN_FOLDER, new String[0], false, null), callback));

        assertEquals(Intent.ACTION_OPEN_DOCUMENT_TREE, startedIntent().getAction());
        assertEquals("a folder request needs no source chooser", dialogsBefore,
                ShadowAlertDialog.getShownDialogs().size());
    }

    @Test
    public void aMultipleSelectionIsReadFromTheClipData() {
        chooser.show(params(MODE_OPEN_MULTIPLE, new String[0], false, null), callback);
        clickSource(SYSTEM_SOURCE);
        Uri first = Uri.parse("content://documents/1");
        Uri second = Uri.parse("content://documents/2");

        Intent data = new Intent();
        ClipData clip = ClipData.newRawUri("files", first);
        clip.addItem(new ClipData.Item(second));
        data.setClipData(clip);

        assertTrue(chooser.deliver(WebAppFileChooser.REQUEST_PICK_UPLOAD, Activity.RESULT_OK, data));
        assertEquals(1, callback.answers.size());
        assertEquals(2, callback.answers.get(0).length);
        assertEquals(first, callback.answers.get(0)[0]);
        assertEquals(second, callback.answers.get(0)[1]);
        assertFalse("one answer per request", chooser.deliver(WebAppFileChooser.REQUEST_PICK_UPLOAD, Activity.RESULT_OK, data));
    }

    @Test
    public void aSingleSelectionComesFromTheDataUri() {
        chooser.show(params(MODE_OPEN, new String[0], false, null), callback);
        clickSource(SYSTEM_SOURCE);
        Intent data = new Intent();
        Uri picked = Uri.parse("content://documents/7");
        data.setData(picked);

        assertTrue(chooser.deliver(WebAppFileChooser.REQUEST_PICK_UPLOAD, Activity.RESULT_OK, data));
        assertEquals(picked, callback.answers.get(0)[0]);
    }

    @Test
    public void aCancelledPickerAnswersNullAndTheNextRequestStillWorks() {
        chooser.show(params(MODE_OPEN, new String[0], false, null), callback);
        clickSource(SYSTEM_SOURCE);

        assertTrue(chooser.deliver(WebAppFileChooser.REQUEST_PICK_UPLOAD, Activity.RESULT_CANCELED, null));
        assertEquals(1, callback.answers.size());
        assertEquals("a cancel must answer null so the input is not left waiting",
                null, callback.answers.get(0));

        assertTrue("the same surface can ask again",
                chooser.show(params(MODE_OPEN, new String[0], false, null), callback));
        clickSource(SYSTEM_SOURCE);
        Intent data = new Intent();
        Uri picked = Uri.parse("content://documents/8");
        data.setData(picked);
        assertTrue(chooser.deliver(WebAppFileChooser.REQUEST_PICK_UPLOAD, Activity.RESULT_OK, data));
        assertEquals(2, callback.answers.size());
        assertEquals(picked, callback.answers.get(1)[0]);
    }

    @Test
    public void aNewRequestCancelsThePendingOneInsteadOfStackingAnswers() {
        chooser.show(params(MODE_OPEN, new String[0], false, null), callback);

        chooser.show(params(MODE_OPEN, new String[0], false, null), callback);

        assertEquals(1, callback.answers.size());
        assertEquals(null, callback.answers.get(0));
        assertFalse("a result that no launched picker can answer is dropped",
                chooser.deliver(WebAppFileChooser.REQUEST_PICK_UPLOAD, Activity.RESULT_OK, new Intent()));

        clickSource(SYSTEM_SOURCE);
        assertTrue("the newest request is the one waiting", chooser.deliver(WebAppFileChooser.REQUEST_PICK_UPLOAD, 
                Activity.RESULT_CANCELED, null));
    }

    @Test
    public void aTeardownDropsTheRequestAndDismissesTheDialog() {
        chooser.show(params(MODE_OPEN, new String[0], false, null), callback);
        AlertDialog source = ShadowAlertDialog.getLatestAlertDialog();
        assertTrue(source.isShowing());

        chooser.cancel();

        assertFalse("a torn-down surface leaves no picker behind", source.isShowing());
        assertTrue(callback.answers.isEmpty());
        assertFalse("nothing is waiting any more",
                chooser.deliver(WebAppFileChooser.REQUEST_PICK_UPLOAD, Activity.RESULT_OK, new Intent()));
    }

    @Test
    public void theBuiltInBrowserOpensTheRealPathPickerAtTheFilesystemRoot() {
        chooser.show(params(MODE_OPEN, new String[0], false, null), callback);
        clickSource(BUILT_IN_SOURCE);

        AlertDialog picker = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull(picker);
        assertNotNull(picker.findViewById(R.id.local_picker_list));
        TextView path = picker.findViewById(R.id.local_picker_path);
        assertEquals("the built-in picker starts at /", "/", path.getText().toString());
        picker.dismiss();
    }

    @Test
    public void dismissingTheBuiltInPickerAnswersThePageWithNull() {
        chooser.show(params(MODE_OPEN, new String[0], false, null), callback);
        clickSource(BUILT_IN_SOURCE);
        AlertDialog picker = ShadowAlertDialog.getLatestAlertDialog();

        // Cancel, Back, and a touch outside all arrive as a dismissal.
        picker.dismiss();
        ShadowLooper.idleMainLooper();

        assertEquals("the page's input must not wait forever",
                1, callback.answers.size());
        assertEquals(null, callback.answers.get(0));
        assertFalse("the dismissed request is no longer waiting",
                chooser.deliver(WebAppFileChooser.REQUEST_PICK_UPLOAD, Activity.RESULT_OK, new Intent()));
    }

    @Test
    public void aStaleSystemResultIsNotDeliveredToANewerRequest() {
        chooser.show(params(MODE_OPEN, new String[0], false, null), callback);
        clickSource(SYSTEM_SOURCE);
        // A second request supersedes the first; the picker behind it is still
        // open, and its late result is for the request that is gone.
        chooser.show(params(MODE_OPEN, new String[0], false, null), callback);

        assertFalse("a result from a superseded picker is dropped",
                chooser.deliver(WebAppFileChooser.REQUEST_PICK_UPLOAD, Activity.RESULT_OK, new Intent()));
        assertEquals(1, callback.answers.size());
    }

    @Test
    public void aClipWithoutUsableUrisFallsBackToTheDataUri() {
        chooser.show(params(MODE_OPEN, new String[0], false, null), callback);
        clickSource(SYSTEM_SOURCE);
        Uri picked = Uri.parse("content://documents/9");
        Intent data = new Intent();
        data.setData(picked);
        data.setClipData(ClipData.newRawUri("empty", null));

        assertTrue(chooser.deliver(WebAppFileChooser.REQUEST_PICK_UPLOAD, Activity.RESULT_OK, data));
        assertEquals(picked, callback.answers.get(0)[0]);
    }

    @Test
    public void withoutAnActivityTheSurfaceCannotShowAChooserAtAll() {
        WebAppFileChooser detached =
                new WebAppFileChooser(RuntimeEnvironment.getApplication());

        assertFalse(detached.show(params(MODE_OPEN, new String[0], false, null), callback));
        assertTrue("returning false must leave the callback to the platform",
                callback.answers.isEmpty());
    }

    @Test
    public void theSourceChooserNamesTheSourcesPlainlyAndMarksThem() {
        chooser.show(params(MODE_OPEN, new String[0], false, null), callback);

        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        ListView list = dialog.getListView();
        assertEquals("an unconstrained input offers all three sources",
                3, list.getAdapter().getCount());

        String[] expected = {
                activity.getString(R.string.webapp_upload_source_android),
                activity.getString(R.string.webapp_upload_source_nusadesk),
                activity.getString(R.string.webapp_upload_source_camera)};
        for (int index = 0; index < expected.length; index++) {
            assertEquals("row " + index, expected[index], labelOf(list, index));
            assertFalse("an end-user label carries no scheme",
                    expected[index].contains("://"));
            assertFalse("an end-user label carries no path", expected[index].contains("/"));
            assertNotNull("each source is marked with its own icon",
                    iconOf(list, index).getDrawable());
        }
        assertNotEquals("no two sources share a mark",
                iconOf(list, 0).getDrawable().getConstantState(),
                iconOf(list, 1).getDrawable().getConstantState());
        assertNotEquals(iconOf(list, 1).getDrawable().getConstantState(),
                iconOf(list, 2).getDrawable().getConstantState());
    }

    @Test
    public void theCameraRowAppearsOnlyWhenAPhotoCouldSatisfyTheAccept() {
        chooser.show(params(MODE_OPEN, new String[]{"image/*"}, false, null), callback);
        ListView list = ShadowAlertDialog.getLatestAlertDialog().getListView();
        assertEquals("Android files, NusaDesk files, Camera", 3, list.getAdapter().getCount());
        assertEquals(activity.getString(R.string.webapp_upload_source_camera),
                labelOf(list, 2));
        chooser.cancel();

        chooser.show(params(MODE_OPEN, new String[]{".pdf"}, false, null), callback);
        list = ShadowAlertDialog.getLatestAlertDialog().getListView();
        assertEquals("a document input has no use for a camera",
                2, list.getAdapter().getCount());
        chooser.cancel();
    }

    @Test
    public void aVideoOnlyAcceptOffersNoCameraAtAll() {
        chooser.show(params(MODE_OPEN, new String[]{"video/*"}, false, null), callback);
        ListView list = ShadowAlertDialog.getLatestAlertDialog().getListView();

        assertEquals("the still camera cannot satisfy a video input",
                2, list.getAdapter().getCount());
    }

    @Test
    public void choosingTheCameraShootsIntoOurOwnFileAndDeliversIt() throws Exception {
        grantCamera();
        chooser.show(params(MODE_OPEN, new String[]{"image/*"}, false, null), callback);

        clickSource(2);

        Intent started = startedIntent();
        assertEquals(MediaStore.ACTION_IMAGE_CAPTURE, started.getAction());
        Uri output = started.getParcelableExtra(MediaStore.EXTRA_OUTPUT);
        assertNotNull("the camera must write into a file this app owns", output);
        assertEquals(activity.getPackageName() + ".camera", output.getAuthority());
        assertTrue("the camera app is granted write access for that one URI",
                (started.getFlags() & Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0);

        File target = new File(
                CameraCaptureProvider.directory(activity), output.getLastPathSegment());
        try (FileOutputStream out = new FileOutputStream(target)) {
            out.write(new byte[42]);
        }

        assertTrue(chooser.deliver(WebAppFileChooser.REQUEST_CAPTURE_PHOTO, Activity.RESULT_OK, null));
        assertEquals(1, callback.answers.size());
        assertEquals(output, callback.answers.get(0)[0]);
    }

    @Test
    public void aCancelledCaptureAnswersNullAndAnEmptyArtifactIsNotHandedOver() throws Exception {
        grantCamera();
        chooser.show(params(MODE_OPEN, new String[]{"image/*"}, false, null), callback);
        clickSource(2);
        Intent started = startedIntent();
        Uri output = started.getParcelableExtra(MediaStore.EXTRA_OUTPUT);
        File target = new File(
                CameraCaptureProvider.directory(activity), output.getLastPathSegment());

        // The camera answered OK but wrote nothing: the page gets "nothing
        // chosen" rather than a zero-byte file.
        assertTrue(target.createNewFile());
        assertTrue(chooser.deliver(WebAppFileChooser.REQUEST_CAPTURE_PHOTO, Activity.RESULT_OK, null));
        assertEquals(null, callback.answers.get(0));

        // A cancelled capture answers null the same way.
        chooser.show(params(MODE_OPEN, new String[]{"image/*"}, false, null), callback);
        clickSource(2);
        assertTrue(chooser.deliver(WebAppFileChooser.REQUEST_CAPTURE_PHOTO, Activity.RESULT_CANCELED, null));
        assertEquals(2, callback.answers.size());
        assertEquals(null, callback.answers.get(1));
    }

    @Test
    public void withoutTheCameraGrantTheChooserAsksForItFirst() {
        ShadowApplication application = Shadow.extract(RuntimeEnvironment.getApplication());
        application.denyPermissions(Manifest.permission.CAMERA);
        chooser.show(params(MODE_OPEN, new String[]{"image/*"}, false, null), callback);

        clickSource(2);

        ShadowActivity.IntentForResult launched = shadowActivity().peekNextStartedActivityForResult();
        assertNotNull("the CAMERA grant is asked for before anything else", launched);
        assertNotEquals("no capture runs before the grant",
                MediaStore.ACTION_IMAGE_CAPTURE, launched.intent.getAction());


        assertTrue("a refusal answers the page", chooser.deliverCameraPermissionResult(false));
        assertEquals(null, callback.answers.get(0));
    }

    @Test
    public void aGrantedPermissionLaunchesTheQueuedCapture() {
        chooser.show(params(MODE_OPEN, new String[]{"image/*"}, false, null), callback);
        clickSource(2);

        assertTrue(chooser.deliverCameraPermissionResult(true));

        assertEquals(MediaStore.ACTION_IMAGE_CAPTURE, startedIntent().getAction());
    }

    private void grantCamera() {
        ShadowApplication application = Shadow.extract(RuntimeEnvironment.getApplication());
        application.grantPermissions(Manifest.permission.CAMERA);
    }

    private static String labelOf(ListView list, int position) {
        View row = list.getAdapter().getView(position, null, list);
        TextView label = row.findViewById(R.id.upload_source_label);
        return label.getText().toString();
    }

    private static ImageView iconOf(ListView list, int position) {
        View row = list.getAdapter().getView(position, null, list);
        return row.findViewById(R.id.upload_source_icon);
    }

    private static final int SYSTEM_SOURCE = 0;
    private static final int BUILT_IN_SOURCE = 1;
    private static final int MODE_OPEN = WebChromeClient.FileChooserParams.MODE_OPEN;
    private static final int MODE_OPEN_MULTIPLE =
            WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE;
    private static final int MODE_OPEN_FOLDER =
            WebChromeClient.FileChooserParams.MODE_OPEN_FOLDER;

    /** Picks one item from the source chooser that is currently up. */
    private void clickSource(int index) {
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull("the source chooser must be up", dialog);
        dialog.getListView().performItemClick(null, index, index);
    }

    private Intent startedIntent() {
        ShadowActivity.IntentForResult started =
                shadowActivity().peekNextStartedActivityForResult();
        assertNotNull("a picker activity must have been started", started);
        return started.intent;
    }

    private int startedRequestCode() {
        return shadowActivity().peekNextStartedActivityForResult().requestCode;
    }

    private ShadowActivity shadowActivity() {
        return Shadow.extract(activity);
    }

    /** A page's callback, recording every answer it receives. */
    private static final class RecordingCallback implements ValueCallback<Uri[]> {
        private final List<Uri[]> answers = new ArrayList<>();

        @Override
        public void onReceiveValue(Uri[] value) {
            answers.add(value);
        }
    }

    private static WebChromeClient.FileChooserParams params(
            int mode, String[] acceptTypes, boolean capture, String filenameHint) {
        return new WebChromeClient.FileChooserParams() {
            @Override
            public int getMode() {
                return mode;
            }

            @Override
            public String[] getAcceptTypes() {
                return acceptTypes;
            }

            @Override
            public boolean isCaptureEnabled() {
                return capture;
            }

            @Override
            public CharSequence getTitle() {
                return null;
            }

            @Override
            public String getFilenameHint() {
                return filenameHint;
            }

            @Override
            public Intent createIntent() {
                throw new UnsupportedOperationException("the app builds its own intent");
            }
        };
    }
}
