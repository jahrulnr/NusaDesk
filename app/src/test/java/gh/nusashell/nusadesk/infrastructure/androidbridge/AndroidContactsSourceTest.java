package gh.nusashell.nusadesk.infrastructure.androidbridge;

import android.Manifest;
import android.content.Context;
import android.provider.ContactsContract;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowContextWrapper;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowContentResolver;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Bounded contacts-read behavior on the JVM: permission mapping happens
 * before any provider query, the query string is the only guest input
 * (LIKE-escaped and fixed-projection), rows are grouped and capped per
 * contact, and provider failures are typed states, never fabricated data.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class AndroidContactsSourceTest {

    private static final String[] CONTACT_COLUMNS = {
            "contact_id", "display_name", "data1" // data1 is Phone.NUMBER
    };

    private FakeQueryProvider provider;
    private Context context;

    @Before
    public void setUp() {
        provider = new FakeQueryProvider();
        context = RuntimeEnvironment.getApplication();
        // Robolectric 4.15 defaults checkSelfPermission to DENIED even for
        // manifest-declared permissions, so the grant is applied explicitly;
        // denial tests below override it.
        Shadow.<ShadowContextWrapper>extract(context).grantPermissions(Manifest.permission.READ_CONTACTS);
        ShadowContentResolver.registerProviderInternal(
                ContactsContract.AUTHORITY, provider);
    }

    @Test
    public void permissionRequiredStopsBeforeAnyProviderQuery() {
        Shadow.<ShadowContextWrapper>extract(context).denyPermissions(Manifest.permission.READ_CONTACTS);
        AndroidContactsSource source = new AndroidContactsSource(context);
        ContactsSnapshot snapshot = source.read(MessagingQuery.of(10));
        assertEquals(MessagingReadState.PERMISSION_REQUIRED, snapshot.getState());
        assertEquals("no provider query without a grant", 0, provider.getQueryCount());
    }

    @Test
    public void permissionDeniedStopsBeforeAnyProviderQuery() {
        Shadow.<ShadowContextWrapper>extract(context).denyPermissions(Manifest.permission.READ_CONTACTS);
        AppOpsHelper.ignoreOp(context, Manifest.permission.READ_CONTACTS);
        AndroidContactsSource source = new AndroidContactsSource(context);
        ContactsSnapshot snapshot = source.read(MessagingQuery.of(10));
        assertEquals(MessagingReadState.PERMISSION_DENIED, snapshot.getState());
        assertEquals("no provider query without a grant", 0, provider.getQueryCount());
    }

    @Test
    public void grantedReadUsesOnlyTheFixedProjectionAndSortOrder() {
        provider.setRows(
                new Object[]{1L, "Alice", "+628111"},
                new Object[]{1L, "Alice", "+628222"});
        AndroidContactsSource source = new AndroidContactsSource(context);

        ContactsSnapshot snapshot = source.read(MessagingQuery.of(10));

        assertEquals(MessagingReadState.READING, snapshot.getState());
        assertEquals(1, snapshot.getEntries().size());
        assertEquals("Alice", snapshot.getEntries().get(0).getName());
        assertEquals(List.of("+628111", "+628222"),
                snapshot.getEntries().get(0).getNumbers());
        assertEquals(3, provider.getLastProjection().length);
        assertEquals("contact_id ASC", provider.getLastSortOrder());
    }

    @Test
    public void grantedReadGroupsNumbersPerContactAndCapsThem() {
        provider.setRows(
                new Object[]{1L, "Alice", "+6281"},
                new Object[]{1L, "Alice", "+6282"},
                new Object[]{2L, "Bob", "+6283"},
                new Object[]{2L, "Bob", "+6284"},
                new Object[]{2L, "Bob", "+6285"},
                new Object[]{2L, "Bob", "+6286"},
                new Object[]{2L, "Bob", "+6287"},
                new Object[]{2L, "Bob", "+6288"});
        AndroidContactsSource source = new AndroidContactsSource(context);

        ContactsSnapshot snapshot = source.read(MessagingQuery.of(10));

        assertEquals(MessagingReadState.READING, snapshot.getState());
        assertEquals(2, snapshot.getEntries().size());
        ContactsSnapshot.ContactEntry bob = snapshot.getEntries().get(1);
        assertEquals("Bob", bob.getName());
        assertEquals(MessagingReadPolicy.MAX_NUMBERS_PER_CONTACT, bob.getNumbers().size());
        assertTrue("numbers were dropped", snapshot.isTruncated());
    }

    @Test
    public void grantedReadCapsContactsAtTheLimitAndReportsTruncation() {
        provider.setRows(
                new Object[]{1L, "A", "+1"},
                new Object[]{2L, "B", "+2"},
                new Object[]{3L, "C", "+3"});
        AndroidContactsSource source = new AndroidContactsSource(context);

        ContactsSnapshot snapshot = source.read(MessagingQuery.of(2));

        assertEquals(MessagingReadState.READING, snapshot.getState());
        assertEquals(2, snapshot.getEntries().size());
        assertTrue("the third contact was cut", snapshot.isTruncated());
    }

    @Test
    public void queryIsEscapedAndAppliedOnlyToNameAndNumber() {
        provider.setRows(
                new Object[]{1L, "Alice 50%", "+628111"});
        AndroidContactsSource source = new AndroidContactsSource(context);

        ContactsSnapshot snapshot = source.read(MessagingQuery.of(10, "50%"));

        assertEquals(MessagingReadState.READING, snapshot.getState());
        assertTrue(provider.getLastSelection().contains("display_name LIKE ?"));
        assertTrue(provider.getLastSelection().contains("data1 LIKE ?"));
        assertTrue(provider.getLastSelection().contains("ESCAPE"));
        assertEquals("%50\\%%", provider.getLastSelectionArgs()[0]);
        assertEquals("%50\\%%", provider.getLastSelectionArgs()[1]);
        assertEquals(1, snapshot.getEntries().size());
    }

    @Test
    public void blankQuerySendsNoSelection() {
        provider.setRows(new Object[]{1L, "Alice", "+628111"});
        AndroidContactsSource source = new AndroidContactsSource(context);

        source.read(MessagingQuery.of(10, "   "));

        assertNull(provider.getLastSelection());
        assertNull(provider.getLastSelectionArgs());
    }

    @Test
    public void numberOnlyContactsCarryAnEmptyName() {
        provider.setRows(new Object[]{1L, null, "+628111"});
        AndroidContactsSource source = new AndroidContactsSource(context);

        ContactsSnapshot snapshot = source.read(MessagingQuery.of(10));

        assertEquals(MessagingReadState.READING, snapshot.getState());
        assertEquals("", snapshot.getEntries().get(0).getName());
        assertEquals(List.of("+628111"), snapshot.getEntries().get(0).getNumbers());
    }

    @Test
    public void nullCursorAndPlatformFailuresAreTypedStates() {
        provider.setReturnNullCursor();
        AndroidContactsSource source = new AndroidContactsSource(context);
        assertEquals(MessagingReadState.UNAVAILABLE,
                source.read(MessagingQuery.of(10)).getState());

        provider.setReturnNullCursor();
        provider.setFailure(new SecurityException("revoked mid-read"));
        assertEquals(MessagingReadState.PERMISSION_DENIED,
                source.read(MessagingQuery.of(10)).getState());

        provider.setFailure(new IllegalStateException("provider broke"));
        assertEquals(MessagingReadState.ERROR,
                source.read(MessagingQuery.of(10)).getState());
    }

    @Test
    public void truncatedFlagSurvivesEncodingWithBoundedRows() {
        provider.setRows(
                new Object[]{1L, "Alice", "+628111"},
                new Object[]{2L, "Bob", "+628222"});
        AndroidContactsSource source = new AndroidContactsSource(context);

        ContactsSnapshot snapshot = source.read(MessagingQuery.of(1));

        assertTrue(snapshot.isTruncated());
        MessagingReadPolicy.EncodedRows encoded = snapshot.encodeRows();
        assertFalse(encoded.isTruncated());
        assertTrue(encoded.getJson().startsWith("[{\"name\":\"Alice\""));
    }

    @Test
    public void skipsNumberRowsWithoutANumber() {
        provider.setRows(
                new Object[]{1L, "Alice", null},
                new Object[]{2L, "Bob", "+628222"});
        AndroidContactsSource source = new AndroidContactsSource(context);

        ContactsSnapshot snapshot = source.read(MessagingQuery.of(10));

        assertEquals(MessagingReadState.READING, snapshot.getState());
        assertEquals(1, snapshot.getEntries().size());
        assertEquals("Bob", snapshot.getEntries().get(0).getName());
    }
}
