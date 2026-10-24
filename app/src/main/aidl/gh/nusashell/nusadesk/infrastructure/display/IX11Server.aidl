package gh.nusashell.nusadesk.infrastructure.display;
import android.os.ParcelFileDescriptor;
/** Private same-APK boundary; the server's fatal exit cannot terminate the shell. */
interface IX11Server {
    ParcelFileDescriptor connectRenderer();
    oneway void shutdown();
}
