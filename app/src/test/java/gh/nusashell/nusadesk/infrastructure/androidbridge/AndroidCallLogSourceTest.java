package gh.nusashell.nusadesk.infrastructure.androidbridge;

import android.Manifest;
import android.content.Context;
import android.provider.CallLog;

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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Bounded call-log read behavior on the JVM: permission mapping happens
 * before any provider query, the query is the only guest input (LIKE-escaped
 * number filter), types map to the bounded enum, unusable rows are skipped
 * rather than fabricated, and rows are capped even when the provider ignores
 * the SQL limit.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class AndroidCallLogSourceTest {

    private FakeQueryProvider provider;
    private Context context;

    @Before
    public void setUp() {
        provider = new FakeQueryProvider();
        context = RuntimeEnvironment.getApplication();
        // Robolectric 4.15 defaults checkSelfPermission to DENIED even for
        // manifest-declared permissions, so the grant is applied explicitly;
        // denial tests below override it.
        Shadow.<ShadowContextWrapper>extract(context).grantPermissions(Manifest.permission.READ_CALL_LOG);
        ShadowContentResolver.registerProviderInternal(CallLog.AUTHORITY, provider);
    }

    @Test
    public void permissionRequiredStopsBeforeAnyProviderQuery() {
        Shadow.<ShadowContextWrapper>extract(context).denyPermissions(Manifest.permission.READ_CALL_LOG);
        AndroidCallLogSource source = new AndroidCallLogSource(context);
        CallLogSnapshot snapshot = source.read(MessagingQuery.of(10));
        assertEquals(MessagingReadState.PERMISSION_REQUIRED, snapshot.getState());
        assertEquals("no provider query without a grant", 0, provider.getQueryCount());
    }

    @Test
    public void permissionDeniedStopsBeforeAnyProviderQuery() {
        Shadow.<ShadowContextWrapper>extract(context).denyPermissions(Manifest.permission.READ_CALL_LOG);
        AppOpsHelper.ignoreOp(context, Manifest.permission.READ_CALL_LOG);
        AndroidCallLogSource source = new AndroidCallLogSource(context);
        CallLogSnapshot snapshot = source.read(MessagingQuery.of(10));
        assertEquals(MessagingReadState.PERMISSION_DENIED, snapshot.getState());
        assertEquals("no provider query without a grant", 0, provider.getQueryCount());
    }

    @Test
    public void grantedReadCapsRowsEvenWhenTheProviderIgnoresTheSqlLimit() {
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            rows.add(new Object[]{"+6281" + i, "C" + i, 2, 1_000L + i, 10L});
        }
        provider.setRows(rows.toArray(new Object[0][]));
        AndroidCallLogSource source = new AndroidCallLogSource(context);

        CallLogSnapshot snapshot = source.read(MessagingQuery.of(2));

        assertEquals(MessagingReadState.READING, snapshot.getState());
        assertEquals(2, snapshot.getEntries().size());
        assertTrue("provider returned more rows than the cap", snapshot.isTruncated());
        assertTrue("newest-first ordering is requested",
                provider.getLastSortOrder().contains("date DESC"));
        assertFalse("a SQL LIMIT token is not portable across providers",
                provider.getLastSortOrder().toUpperCase(java.util.Locale.ROOT)
                        .contains("LIMIT"));
    }

    @Test
    public void grantedReadMapsNumberNameTypeTimestampAndDuration() {
        provider.setRows(
                new Object[]{"+628111", "Alice", 1, 1_000L, 30L},
                new Object[]{"+628222", null, 3, 2_000L, -5L},
                new Object[]{"+628333", "Bob", 7, 3_000L, 10L},
                new Object[]{"+628444", "X", 99, 4_000L, 0L});
        AndroidCallLogSource source = new AndroidCallLogSource(context);

        CallLogSnapshot snapshot = source.read(MessagingQuery.all());

        assertEquals(MessagingReadState.READING, snapshot.getState());
        List<CallLogSnapshot.CallLogEntry> entries = snapshot.getEntries();
        assertEquals(4, entries.size());

        CallLogSnapshot.CallLogEntry incoming = entries.get(0);
        assertEquals("+628111", incoming.getNumber());
        assertEquals("Alice", incoming.getCachedName());
        assertEquals(CallLogSnapshot.CallType.INCOMING, incoming.getType());
        assertEquals(1_000L, incoming.getTimestampUtcMillis());
        assertEquals(30L, incoming.getDurationSeconds());

        CallLogSnapshot.CallLogEntry missed = entries.get(1);
        assertNull("cached name is omitted when absent", missed.getCachedName());
        assertEquals(CallLogSnapshot.CallType.MISSED, missed.getType());
        assertEquals("negative durations are clamped, never fabricated", 0L,
                missed.getDurationSeconds());

        assertEquals(CallLogSnapshot.CallType.ANSWERED_EXTERNALLY, entries.get(2).getType());
        assertEquals(CallLogSnapshot.CallType.UNKNOWN, entries.get(3).getType());
    }

    @Test
    public void queryIsEscapedAndAppliedOnlyToTheNumber() {
        provider.setRows(new Object[]{"+628111", null, 1, 1_000L, 0L});
        AndroidCallLogSource source = new AndroidCallLogSource(context);

        source.read(MessagingQuery.of(10, "628%"));

        assertTrue(provider.getLastSelection().contains("number LIKE ?"));
        assertTrue(provider.getLastSelection().contains("ESCAPE"));
        assertEquals("%628\\%%", provider.getLastSelectionArgs()[0]);
        assertEquals(5, provider.getLastProjection().length);
    }

    @Test
    public void rowsWithoutNumberOrTimestampAreSkippedNotFabricated() {
        provider.setRows(
                new Object[]{null, "A", 1, 1_000L, 0L},
                new Object[]{"+628111", "B", 1, 0L, 0L},
                new Object[]{"+628222", "C", 1, 2_000L, 5L});
        AndroidCallLogSource source = new AndroidCallLogSource(context);

        CallLogSnapshot snapshot = source.read(MessagingQuery.all());

        assertEquals(MessagingReadState.READING, snapshot.getState());
        assertEquals(1, snapshot.getEntries().size());
        assertEquals("+628222", snapshot.getEntries().get(0).getNumber());
    }

    @Test
    public void nullCursorAndPlatformFailuresAreTypedStates() {
        provider.setReturnNullCursor();
        AndroidCallLogSource source = new AndroidCallLogSource(context);
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
        provider.setRows(new Object[]{"+628111", "Alice", 1, 1_000L, 30L});
        AndroidCallLogSource source = new AndroidCallLogSource(context);

        CallLogSnapshot snapshot = source.read(MessagingQuery.all());
        MessagingReadPolicy.EncodedRows encoded = snapshot.encodeRows();

        assertFalse(encoded.isTruncated());
        assertEquals("[{\"number\":\"+628111\",\"name\":\"Alice\",\"type\":\"incoming\","
                + "\"timestamp_utc_ms\":1000,\"duration_seconds\":30}]", encoded.getJson());
    }
}
