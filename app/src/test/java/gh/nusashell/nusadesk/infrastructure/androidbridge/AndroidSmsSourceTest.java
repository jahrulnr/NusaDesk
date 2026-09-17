package gh.nusashell.nusadesk.infrastructure.androidbridge;

import android.Manifest;
import android.content.Context;
import android.provider.Telephony;

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

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Bounded SMS inbox-read behavior on the JVM: permission mapping happens
 * before any provider query, the query is the only guest input (LIKE-escaped
 * address filter), bodies are reduced to bounded single-line snippets, and
 * rows are capped even when the provider ignores the SQL limit.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class AndroidSmsSourceTest {

    private FakeQueryProvider provider;
    private Context context;

    @Before
    public void setUp() {
        provider = new FakeQueryProvider();
        context = RuntimeEnvironment.getApplication();
        // Robolectric 4.15 defaults checkSelfPermission to DENIED even for
        // manifest-declared permissions, so the grant is applied explicitly;
        // denial tests below override it.
        Shadow.<ShadowContextWrapper>extract(context).grantPermissions(Manifest.permission.READ_SMS);
        // The SMS provider authority ("sms") is a stable platform constant
        // that newer SDKs no longer expose as a field.
        ShadowContentResolver.registerProviderInternal("sms", provider);
    }

    @Test
    public void permissionRequiredStopsBeforeAnyProviderQuery() {
        Shadow.<ShadowContextWrapper>extract(context).denyPermissions(Manifest.permission.READ_SMS);
        AndroidSmsSource source = new AndroidSmsSource(context);
        SmsSnapshot snapshot = source.read(MessagingQuery.of(10));
        assertEquals(MessagingReadState.PERMISSION_REQUIRED, snapshot.getState());
        assertEquals("no provider query without a grant", 0, provider.getQueryCount());
    }

    @Test
    public void permissionDeniedStopsBeforeAnyProviderQuery() {
        Shadow.<ShadowContextWrapper>extract(context).denyPermissions(Manifest.permission.READ_SMS);
        AppOpsHelper.ignoreOp(context, Manifest.permission.READ_SMS);
        AndroidSmsSource source = new AndroidSmsSource(context);
        SmsSnapshot snapshot = source.read(MessagingQuery.of(10));
        assertEquals(MessagingReadState.PERMISSION_DENIED, snapshot.getState());
        assertEquals("no provider query without a grant", 0, provider.getQueryCount());
    }

    @Test
    public void grantedReadCapsRowsEvenWhenTheProviderIgnoresTheSqlLimit() {
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            rows.add(new Object[]{"+6281" + i, 1_000L + i, 0, "msg " + i});
        }
        provider.setRows(rows.toArray(new Object[0][]));
        AndroidSmsSource source = new AndroidSmsSource(context);

        SmsSnapshot snapshot = source.read(MessagingQuery.of(2));

        assertEquals(MessagingReadState.READING, snapshot.getState());
        assertEquals(2, snapshot.getEntries().size());
        assertTrue("provider returned more rows than the cap", snapshot.isTruncated());
        assertTrue("newest-first ordering is requested",
                provider.getLastSortOrder().contains("date DESC"));
    }

    @Test
    public void grantedReadMapsAddressTimestampReadAndSnippet() {
        provider.setRows(
                new Object[]{"+628111", 1_000L, 1, "hello\nworld\t!"});
        AndroidSmsSource source = new AndroidSmsSource(context);

        SmsSnapshot snapshot = source.read(MessagingQuery.all());

        assertEquals(MessagingReadState.READING, snapshot.getState());
        SmsSnapshot.SmsEntry entry = snapshot.getEntries().get(0);
        assertEquals("+628111", entry.getAddress());
        assertEquals(1_000L, entry.getTimestampUtcMillis());
        assertTrue(entry.isRead());
        assertEquals("hello world !", entry.getSnippet());
    }

    @Test
    public void snippetsAreCappedToTheBoundedLength() {
        StringBuilder longBody = new StringBuilder();
        for (int i = 0; i < 500; i++) {
            longBody.append('z');
        }
        provider.setRows(new Object[]{"+628111", 1_000L, 0, longBody.toString()});
        AndroidSmsSource source = new AndroidSmsSource(context);

        SmsSnapshot snapshot = source.read(MessagingQuery.all());

        assertEquals(MessagingReadPolicy.MAX_SNIPPET_CHARS,
                snapshot.getEntries().get(0).getSnippet().length());
    }

    @Test
    public void queryIsEscapedAndAppliedOnlyToTheAddress() {
        provider.setRows(new Object[]{"+628111", 1_000L, 0, "hi"});
        AndroidSmsSource source = new AndroidSmsSource(context);

        source.read(MessagingQuery.of(10, "628_1"));

        assertTrue(provider.getLastSelection().contains("address LIKE ?"));
        assertTrue(provider.getLastSelection().contains("ESCAPE"));
        assertEquals("%628\\_1%", provider.getLastSelectionArgs()[0]);
        assertEquals(4, provider.getLastProjection().length);
    }

    @Test
    public void rowsWithoutAddressOrTimestampAreSkippedNotFabricated() {
        provider.setRows(
                new Object[]{null, 1_000L, 0, "no address"},
                new Object[]{"+628111", 0L, 0, "no date"},
                new Object[]{"+628222", 2_000L, 0, "ok"});
        AndroidSmsSource source = new AndroidSmsSource(context);

        SmsSnapshot snapshot = source.read(MessagingQuery.all());

        assertEquals(MessagingReadState.READING, snapshot.getState());
        assertEquals(1, snapshot.getEntries().size());
        assertEquals("+628222", snapshot.getEntries().get(0).getAddress());
    }

    @Test
    public void nullCursorAndPlatformFailuresAreTypedStates() {
        provider.setReturnNullCursor();
        AndroidSmsSource source = new AndroidSmsSource(context);
        assertEquals(MessagingReadState.UNAVAILABLE,
                source.read(MessagingQuery.all()).getState());

        provider.setFailure(new SecurityException("revoked mid-read"));
        assertEquals(MessagingReadState.PERMISSION_DENIED,
                source.read(MessagingQuery.all()).getState());

        provider.setFailure(new IllegalStateException("provider broke"));
        assertEquals(MessagingReadState.ERROR,
                source.read(MessagingQuery.all()).getState());
    }

    @Test
    public void encodedRowsAreBoundedAndSingleLine() {
        provider.setRows(new Object[]{"+628111", 1_000L, 0, "hi"});
        AndroidSmsSource source = new AndroidSmsSource(context);

        SmsSnapshot snapshot = source.read(MessagingQuery.all());
        MessagingReadPolicy.EncodedRows encoded = snapshot.encodeRows();

        assertFalse(encoded.isTruncated());
        assertEquals("[{\"address\":\"+628111\",\"timestamp_utc_ms\":1000,"
                + "\"read\":false,\"snippet\":\"hi\"}]", encoded.getJson());
    }
}
