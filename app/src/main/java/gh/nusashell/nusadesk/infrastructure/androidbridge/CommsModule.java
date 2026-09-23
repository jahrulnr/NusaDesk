package gh.nusashell.nusadesk.infrastructure.androidbridge;

import android.Manifest;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.PersistableBundle;
import android.telephony.SmsManager;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.util.JsonWriter;
import android.util.Log;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import gh.nusashell.nusadesk.infrastructure.androidbridge.AndroidKeystoreSource.KeystoreException;
import gh.nusashell.nusadesk.infrastructure.androidbridge.foreground.CallForegroundOperation;
import gh.nusashell.nusadesk.infrastructure.androidbridge.foreground.CapabilityForegroundHost;
import gh.nusashell.nusadesk.infrastructure.service.HostRuntimeStatus;
import gh.nusashell.nusadesk.infrastructure.service.RuntimeStatusBus;

/**
 * Communications capability domain (W2e): real SMS sending, phone calls,
 * AndroidKeyStore operations, and guest job scheduling, behind ten bridge
 * methods.
 *
 * <p>{@code sms.send} sends real multipart SMS through {@link SmsManager}
 * with per-part {@code sent} PendingIntents, and reports each recipient as
 * {@code sent}/{@code failed}/{@code unconfirmed} from the platform result
 * codes — a fired call is never reported as delivered.</p>
 *
 * <p>{@code phone.call} launches {@code ACTION_CALL} through the foreground
 * host: background activity starts are silently dropped on API 29+, so the
 * call only proceeds from a visible activity and the guest gets the host's
 * typed {@code foreground-required} otherwise.</p>
 *
 * <p>{@code keystore.*} delegates to {@link AndroidKeystoreSource}
 * (list/delete/generate/sign/verify, upstream JSON shape); private keys never
 * cross the bridge. {@code jobscheduler.*} schedules real {@link JobInfo}
 * entries for {@link CommsJobService}, which executes the bound guest script
 * only while the one guest session is running — a trigger on a dead session
 * is recorded as {@code session-down}, never faked as background work.</p>
 *
 * <p>Every method validates with {@link CapabilityParams} first, checks its
 * runtime permissions through {@link AndroidPermissionChecker}, and maps
 * platform failures to typed lowercase-kebab errors without leaking platform
 * exception text.</p>
 */
public final class CommsModule implements CapabilityModule {
    private static final String TAG = "CommsModule";

    private static final String METHOD_SMS_SEND = "sms.send";
    private static final String METHOD_PHONE_CALL = "phone.call";
    private static final String METHOD_KEYSTORE_LIST = "keystore.list";
    private static final String METHOD_KEYSTORE_DELETE = "keystore.delete";
    private static final String METHOD_KEYSTORE_GENERATE = "keystore.generate";
    private static final String METHOD_KEYSTORE_SIGN = "keystore.sign";
    private static final String METHOD_KEYSTORE_VERIFY = "keystore.verify";
    private static final String METHOD_JOB_SCHEDULE = "jobscheduler.schedule";
    private static final String METHOD_JOB_LIST = "jobscheduler.list";
    private static final String METHOD_JOB_CANCEL = "jobscheduler.cancel";

    private static final String JOB_SESSION_DOWN_ERROR =
            "jobscheduler-unavailable:the guest session must be running";

    private static final int MAX_SMS_RECIPIENTS = 10;
    private static final int MAX_NUMBER_CHARS = 64;
    private static final int MAX_SMS_TEXT_CHARS = 8192;
    private static final int MAX_SIM_SLOT = 32;
    private static final long SMS_RESULT_WAIT_MS = 10_000L;
    /**
     * Window used to confirm a dispatched SMS against the platform's sent
     * box when the per-part result broadcast never arrives. Device-observed
     * on the S7 Edge (Android 10, 2026-09-23): the message was stored in
     * {@code content://sms/sent} while no result broadcast reached the app
     * within {@link #SMS_RESULT_WAIT_MS}, so the bridge reported a false
     * "unconfirmed" for a message that had actually gone out.
     */
    private static final long SMS_SENTBOX_WINDOW_MS = 120_000L;
    private static final long CALL_FOREGROUND_TIMEOUT_MS = 60_000L;

    private static final int MAX_ALIAS_CHARS = 256;
    private static final int MAX_ALGORITHM_CHARS = 64;
    private static final int MAX_CURVE_CHARS = 64;
    private static final int MAX_BASE64_CHARS = 8192;
    private static final long MAX_KEYSTORE_FILE_BYTES = 256L * 1024L;
    private static final long MAX_KEY_SIZE = 8192L;
    private static final long MIN_KEY_SIZE = 512L;
    private static final long MAX_VALIDITY_SECONDS = 31_536_000L;
    private static final int MAX_PURPOSE_ITEMS = 6;
    private static final int MAX_DIGEST_ITEMS = 7;

    private static final long MAX_JOB_ID = Integer.MAX_VALUE;
    private static final long MAX_PERIOD_MS = 7L * 24L * 60L * 60L * 1000L;
    private static final int MAX_SCRIPT_CHARS = 4096;
    private static final int MAX_TRIGGER_URI_CHARS = 512;
    private static final int MAX_NETWORK_CHARS = 16;
    private static final int MAX_RECENT_OUTCOMES = 10;

    /** SMS send-result broadcast action, kept inside this package. */
    private static final String SMS_SENT_ACTION_SUFFIX = ".comms.SMS_SENT";
    private static final String SMS_SENT_URI_PREFIX = "nusadesk-sms://sent/";

    private final Context context;
    private final AndroidPermissionChecker permissionChecker;
    private final CapabilityForegroundHost foregroundHost;
    private final AndroidKeystoreSource keystoreSource;
    private final GuestFilePathResolver pathResolver;

    public CommsModule(Context context) {
        if (context == null) {
            throw new IllegalArgumentException("context must not be null");
        }
        this.context = context.getApplicationContext();
        this.permissionChecker = new AndroidPermissionChecker(this.context);
        this.foregroundHost = new CapabilityForegroundHost(this.context);
        this.keystoreSource = new AndroidKeystoreSource();
        this.pathResolver = new GuestFilePathResolver(this.context);
    }

    @Override
    public List<String> methods() {
        return List.of(METHOD_SMS_SEND, METHOD_PHONE_CALL,
                METHOD_KEYSTORE_LIST, METHOD_KEYSTORE_DELETE,
                METHOD_KEYSTORE_GENERATE, METHOD_KEYSTORE_SIGN,
                METHOD_KEYSTORE_VERIFY,
                METHOD_JOB_SCHEDULE, METHOD_JOB_LIST, METHOD_JOB_CANCEL);
    }

    @Override
    public Set<String> parameterMethods() {
        return Set.of(METHOD_SMS_SEND, METHOD_PHONE_CALL,
                METHOD_KEYSTORE_LIST, METHOD_KEYSTORE_DELETE,
                METHOD_KEYSTORE_GENERATE, METHOD_KEYSTORE_SIGN,
                METHOD_KEYSTORE_VERIFY,
                METHOD_JOB_SCHEDULE, METHOD_JOB_CANCEL);
    }

    @Override
    public AndroidCapabilityProtocol.Response handle(
            AndroidCapabilityProtocol.Request request) {
        if (request == null || request.getMethod() == null) {
            return AndroidCapabilityProtocol.Response.error("", "unsupported-method");
        }
        switch (request.getMethod()) {
            case METHOD_SMS_SEND:
                return smsSend(request);
            case METHOD_PHONE_CALL:
                return phoneCall(request);
            case METHOD_KEYSTORE_LIST:
                return keystoreList(request);
            case METHOD_KEYSTORE_DELETE:
                return keystoreDelete(request);
            case METHOD_KEYSTORE_GENERATE:
                return keystoreGenerate(request);
            case METHOD_KEYSTORE_SIGN:
                return keystoreSign(request);
            case METHOD_KEYSTORE_VERIFY:
                return keystoreVerify(request);
            case METHOD_JOB_SCHEDULE:
                return jobSchedule(request);
            case METHOD_JOB_LIST:
                return jobList(request);
            case METHOD_JOB_CANCEL:
                return jobCancel(request);
            default:
                return AndroidCapabilityProtocol.Response.error(
                        request.getId(), "unsupported-method");
        }
    }

    @Override
    public void close() {
        foregroundHost.close();
    }

    // ---------------------------------------------------------------
    // sms.send
    // ---------------------------------------------------------------

    /**
     * {@code sms.send} — {@code recipients} (comma list, 1..10 entries),
     * {@code text} (required, ≤8192 chars), optional {@code slot} (SIM slot
     * index; requires {@code READ_PHONE_STATE} on top of {@code SEND_SMS}).
     * Success fields are {@code sent}, {@code failed}, {@code unconfirmed}
     * comma lists in recipient order. A recipient is {@code sent} only when
     * every part's platform result is {@code RESULT_OK}; a part that reports
     * an error code fails it; a part that never reports within
     * {@link #SMS_RESULT_WAIT_MS} is confirmed against the platform sent box
     * when {@code READ_SMS} happens to be granted (see
     * {@link #sentBoxReadable()}) and otherwise stays {@code unconfirmed}.
     * Permission gaps answer the typed
     * {@code sms-permission-required}/{@code sms-permission-denied}; a
     * missing telephony stack or subscription answers
     * {@code sms-unavailable}.
     */
    private AndroidCapabilityProtocol.Response smsSend(
            AndroidCapabilityProtocol.Request request) {
        CapabilityParams params = CapabilityParams.of(request);
        params.rejectUnknown(Set.of("recipients", "text", "slot"));
        List<String> recipients = params.optionalStringList(
                "recipients", MAX_SMS_RECIPIENTS, MAX_NUMBER_CHARS);
        if (recipients.isEmpty()) {
            throw new CapabilityParams.Invalid("missing parameter: recipients");
        }
        String text = params.requireString("text", MAX_SMS_TEXT_CHARS);
        boolean slotGiven = params.has("slot");
        long slot = params.optionalLong("slot", 0, MAX_SIM_SLOT, -1);

        AndroidCapabilityProtocol.Response denied = permissionError(
                request, Manifest.permission.SEND_SMS, "sms");
        if (denied != null) {
            return denied;
        }
        if (slotGiven) {
            denied = permissionError(
                    request, Manifest.permission.READ_PHONE_STATE, "sms");
            if (denied != null) {
                return denied;
            }
        }

        SmsManager smsManager = resolveSmsManager(slotGiven, slot);
        if (smsManager == null) {
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), "sms-unavailable");
        }

        ArrayList<String> parts;
        try {
            parts = smsManager.divideMessage(text);
        } catch (RuntimeException e) {
            Log.w(TAG, "sms.send: divideMessage failed", e);
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), "sms-unavailable");
        }
        if (parts == null || parts.isEmpty()) {
            throw new CapabilityParams.Invalid("empty parameter: text");
        }
        return sendParts(request, smsManager, recipients, parts, text);
    }

    private SmsManager resolveSmsManager(boolean slotGiven, long slot) {
        try {
            if (!slotGiven) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    return context.getSystemService(SmsManager.class);
                }
                @SuppressWarnings("deprecation")
                SmsManager manager = SmsManager.getDefault();
                return manager;
            }
            SubscriptionManager subscriptions =
                    context.getSystemService(SubscriptionManager.class);
            List<SubscriptionInfo> active = subscriptions == null
                    ? null : subscriptions.getActiveSubscriptionInfoList();
            if (active == null) {
                return null;
            }
            for (SubscriptionInfo info : active) {
                if (info.getSimSlotIndex() == slot) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        SmsManager manager =
                                context.getSystemService(SmsManager.class);
                        return manager == null
                                ? null : manager.createForSubscriptionId(
                                        info.getSubscriptionId());
                    }
                    return SmsManager.getSmsManagerForSubscriptionId(
                            info.getSubscriptionId());
                }
            }
            return null;
        } catch (SecurityException e) {
            Log.w(TAG, "sms.send: manager resolution denied", e);
            return null;
        } catch (RuntimeException e) {
            Log.w(TAG, "sms.send: manager resolution failed", e);
            return null;
        }
    }

    private AndroidCapabilityProtocol.Response sendParts(
            AndroidCapabilityProtocol.Request request,
            SmsManager smsManager, List<String> recipients,
            ArrayList<String> parts, String body) {
        String requestTag = Long.toHexString(System.nanoTime());
        Map<String, Integer> results = new ConcurrentHashMap<>();
        CountDownLatch pending =
                new CountDownLatch(recipients.size() * parts.size());
        Set<Integer> dispatchFailed = new HashSet<>();
        String action = context.getPackageName() + SMS_SENT_ACTION_SUFFIX;

        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context receiverContext, Intent intent) {
                Uri data = intent.getData();
                List<String> segments = data == null ? null : data.getPathSegments();
                if (segments == null || segments.size() != 3
                        || !requestTag.equals(segments.get(0))) {
                    return;
                }
                results.put(segments.get(1) + ":" + segments.get(2),
                        getResultCode());
                pending.countDown();
            }
        };
        try {
            registerReceiver(receiver, action);
            for (int r = 0; r < recipients.size(); r++) {
                ArrayList<PendingIntent> sentIntents = new ArrayList<>(parts.size());
                for (int p = 0; p < parts.size(); p++) {
                    Intent intent = new Intent(action)
                            .setPackage(context.getPackageName())
                            .setData(Uri.parse(SMS_SENT_URI_PREFIX
                                    + requestTag + "/" + r + "/" + p));
                    sentIntents.add(PendingIntent.getBroadcast(context, 0, intent,
                            PendingIntent.FLAG_IMMUTABLE
                                    | PendingIntent.FLAG_UPDATE_CURRENT));
                }
                try {
                    smsManager.sendMultipartTextMessage(
                            recipients.get(r), null, parts, sentIntents, null);
                } catch (RuntimeException e) {
                    Log.w(TAG, "sms.send: dispatch failed for one recipient", e);
                    dispatchFailed.add(r);
                    for (int p = 0; p < parts.size(); p++) {
                        pending.countDown();
                    }
                }
            }
            pending.await(SMS_RESULT_WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            try {
                context.unregisterReceiver(receiver);
            } catch (RuntimeException ignored) {
                // Already unregistered.
            }
        }

        List<String> sent = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        List<String> unconfirmed = new ArrayList<>();
        for (int r = 0; r < recipients.size(); r++) {
            boolean allOk = true;
            boolean anyError = false;
            boolean anyMissing = false;
            for (int p = 0; p < parts.size(); p++) {
                Integer code = results.get(r + ":" + p);
                if (code == null) {
                    anyMissing = true;
                    allOk = false;
                } else if (code != android.app.Activity.RESULT_OK) {
                    anyError = true;
                    allOk = false;
                }
            }
            if (dispatchFailed.contains(r) || anyError) {
                failed.add(recipients.get(r));
            } else if (allOk) {
                sent.add(recipients.get(r));
            } else if (anyMissing || !allOk) {
                unconfirmed.add(recipients.get(r));
            }
        }
        if (!unconfirmed.isEmpty() && sentBoxReadable()) {
            // No result broadcast: ask the platform's own sent box before
            // calling a dispatched message unsent. The confirmation reads
            // content://sms/sent, which needs READ_SMS; that grant is not
            // part of this method's required set, because sending an SMS must
            // never demand read access. Without it a dispatched part stays
            // honestly `unconfirmed`.
            long since = System.currentTimeMillis() - SMS_SENTBOX_WINDOW_MS;
            List<String> stillUnconfirmed = new ArrayList<>();
            for (String recipient : unconfirmed) {
                if (sentBoxHasRecent(recipient, body, since)) {
                    sent.add(recipient);
                } else {
                    stillUnconfirmed.add(recipient);
                }
            }
            unconfirmed = stillUnconfirmed;
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("sent", String.join(",", sent));
        fields.put("failed", String.join(",", failed));
        fields.put("unconfirmed", String.join(",", unconfirmed));
        return AndroidCapabilityProtocol.Response.success(request.getId(), fields);
    }

    /**
     * Whether the sent-box confirmation can run at all: it reads
     * {@code content://sms/sent}, which needs {@code READ_SMS}. That grant is
     * deliberately not part of {@code sms.send}'s required set (sending must
     * not demand read access), so the confirmation is opportunistic — it only
     * turns a dispatched-but-unreported part into {@code sent} when the user
     * already granted the read, and never turns a missing grant into a
     * permission error on the send path.
     */
    private boolean sentBoxReadable() {
        return context.checkSelfPermission(Manifest.permission.READ_SMS)
                == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Whether the platform recorded a recent outgoing message to this
     * recipient with this body. Numbers are compared by their digits so a
     * local-form recipient still matches the operator's normalised row.
     */
    private boolean sentBoxHasRecent(String recipient, String body, long sinceMillis) {
        String digits = recipient == null ? "" : recipient.replaceAll("[^0-9]", "");
        try (android.database.Cursor cursor = context.getContentResolver().query(
                Uri.parse("content://sms/sent"),
                new String[] {"address", "body", "date"},
                "date >= ?", new String[] {String.valueOf(sinceMillis)},
                "date DESC")) {
            if (cursor == null) {
                return false;
            }
            int addressColumn = cursor.getColumnIndex("address");
            int bodyColumn = cursor.getColumnIndex("body");
            while (cursor.moveToNext()) {
                String address = addressColumn < 0 ? null : cursor.getString(addressColumn);
                String rowBody = bodyColumn < 0 ? null : cursor.getString(bodyColumn);
                if (rowBody == null || !rowBody.equals(body)) {
                    continue;
                }
                String rowDigits = address == null ? "" : address.replaceAll("[^0-9]", "");
                if (!digits.isEmpty() && (rowDigits.equals(digits)
                        || rowDigits.endsWith(digits) || digits.endsWith(rowDigits))) {
                    return true;
                }
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "sms.send: sent-box confirmation unavailable", e);
        }
        return false;
    }

    private void registerReceiver(BroadcastReceiver receiver, String action) {
        IntentFilter filter = new IntentFilter(action);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter,
                    Context.RECEIVER_NOT_EXPORTED);
        } else {
            context.registerReceiver(receiver, filter);
        }
    }

    // ---------------------------------------------------------------
    // phone.call
    // ---------------------------------------------------------------

    /**
     * {@code phone.call} — {@code number} (required). {@code CALL_PHONE}
     * gates the typed {@code call-permission-*} errors; the actual
     * {@code ACTION_CALL} launch runs inside the foreground host's activity
     * so it is honoured on API 29+. Success is {@code called=true} and the
     * echoed {@code number}; host refusals (busy/timeout/foreground-required)
     * pass through as their own typed errors.
     */
    private AndroidCapabilityProtocol.Response phoneCall(
            AndroidCapabilityProtocol.Request request) {
        CapabilityParams params = CapabilityParams.of(request);
        params.rejectUnknown(Set.of("number"));
        String number = params.requireString("number", MAX_NUMBER_CHARS);
        if (number.trim().isEmpty()) {
            throw new CapabilityParams.Invalid("empty parameter: number");
        }
        AndroidCapabilityProtocol.Response denied = permissionError(
                request, Manifest.permission.CALL_PHONE, "call");
        if (denied != null) {
            return denied;
        }
        AndroidCapabilityProtocol.Response inner = foregroundHost.execute(
                CallForegroundOperation.KIND, Map.of("number", number),
                CALL_FOREGROUND_TIMEOUT_MS);
        if (inner.isOk()) {
            return AndroidCapabilityProtocol.Response.success(
                    request.getId(), inner.getFields());
        }
        return AndroidCapabilityProtocol.Response.error(
                request.getId(), inner.getError());
    }

    // ---------------------------------------------------------------
    // keystore.*
    // ---------------------------------------------------------------

    /**
     * {@code keystore.list} — optional {@code detailed} (bool). Success field
     * {@code keys_json} is the upstream-shaped alias array.
     */
    private AndroidCapabilityProtocol.Response keystoreList(
            AndroidCapabilityProtocol.Request request) {
        CapabilityParams params = CapabilityParams.of(request);
        params.rejectUnknown(Set.of("detailed"));
        boolean detailed = params.optionalBoolean("detailed", false);
        try {
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("keys_json", keystoreSource.listJson(detailed));
            return AndroidCapabilityProtocol.Response.success(
                    request.getId(), fields);
        } catch (KeystoreException e) {
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), e.code());
        }
    }

    /**
     * {@code keystore.delete} — {@code alias} (required). Success fields
     * {@code deleted=true} and {@code existed} (whether an entry was present).
     */
    private AndroidCapabilityProtocol.Response keystoreDelete(
            AndroidCapabilityProtocol.Request request) {
        CapabilityParams params = CapabilityParams.of(request);
        params.rejectUnknown(Set.of("alias"));
        String alias = params.requireString("alias", MAX_ALIAS_CHARS);
        try {
            boolean existed = keystoreSource.containsAlias(alias);
            keystoreSource.delete(alias);
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("deleted", Boolean.TRUE);
            fields.put("existed", existed);
            return AndroidCapabilityProtocol.Response.success(
                    request.getId(), fields);
        } catch (KeystoreException e) {
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), e.code());
        }
    }

    /**
     * {@code keystore.generate} — {@code alias} and {@code algorithm}
     * ({@code RSA}|{@code EC}) required; {@code size} (512..8192, RSA),
     * {@code curve} (EC, default {@code secp256r1}), {@code validity_seconds}
     * (0..31536000), {@code purposes} and {@code digests} (comma lists of the
     * upstream names). Success is {@code generated=true} + {@code alias}.
     */
    private AndroidCapabilityProtocol.Response keystoreGenerate(
            AndroidCapabilityProtocol.Request request) {
        CapabilityParams params = CapabilityParams.of(request);
        params.rejectUnknown(Set.of("alias", "algorithm", "size", "curve",
                "validity_seconds", "purposes", "digests"));
        String alias = params.requireString("alias", MAX_ALIAS_CHARS);
        String algorithm = params.requireString("algorithm", MAX_ALGORITHM_CHARS)
                .trim().toUpperCase(java.util.Locale.ROOT);
        if (!"RSA".equals(algorithm) && !"EC".equals(algorithm)) {
            throw new CapabilityParams.Invalid(
                    "unsupported algorithm: " + algorithm);
        }
        long size = params.optionalLong("size", MIN_KEY_SIZE, MAX_KEY_SIZE, 2048L);
        String curve = params.optionalString("curve", MAX_CURVE_CHARS, "secp256r1");
        long validitySeconds = params.optionalLong(
                "validity_seconds", 0, MAX_VALIDITY_SECONDS, 0);
        int purposes = purposesMask(
                params.optionalStringList("purposes", MAX_PURPOSE_ITEMS, 16));
        List<String> digests = digestNames(
                params.optionalStringList("digests", MAX_DIGEST_ITEMS, 16));
        try {
            keystoreSource.generate(alias, algorithm, size, curve,
                    validitySeconds, purposes, digests);
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("generated", Boolean.TRUE);
            fields.put("alias", alias);
            return AndroidCapabilityProtocol.Response.success(
                    request.getId(), fields);
        } catch (KeystoreException e) {
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), e.code());
        }
    }

    /**
     * {@code keystore.sign} — {@code alias}, {@code algorithm}, and exactly
     * one data source: {@code data} (base64 ≤8192 chars) or {@code path} (a
     * guest file ≤256 KiB, resolved inside the rootfs). Success field
     * {@code signature} is the base64-encoded signature.
     */
    private AndroidCapabilityProtocol.Response keystoreSign(
            AndroidCapabilityProtocol.Request request) {
        CapabilityParams params = CapabilityParams.of(request);
        params.rejectUnknown(Set.of("alias", "algorithm", "data", "path"));
        String alias = params.requireString("alias", MAX_ALIAS_CHARS);
        String algorithm = params.requireString("algorithm", MAX_ALGORITHM_CHARS);
        byte[] data = readDataParam(params);
        try {
            byte[] signature = keystoreSource.sign(alias, algorithm, data);
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("signature",
                    Base64.getEncoder().encodeToString(signature));
            return AndroidCapabilityProtocol.Response.success(
                    request.getId(), fields);
        } catch (KeystoreException e) {
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), e.code());
        }
    }

    /**
     * {@code keystore.verify} — {@code alias}, {@code algorithm}, a data
     * source like {@code keystore.sign}, and the signature as {@code
     * signature} (base64) or {@code signature_path} (guest file). Success
     * field {@code verified} is the platform verdict.
     */
    private AndroidCapabilityProtocol.Response keystoreVerify(
            AndroidCapabilityProtocol.Request request) {
        CapabilityParams params = CapabilityParams.of(request);
        params.rejectUnknown(Set.of("alias", "algorithm", "data", "path",
                "signature", "signature_path"));
        String alias = params.requireString("alias", MAX_ALIAS_CHARS);
        String algorithm = params.requireString("algorithm", MAX_ALGORITHM_CHARS);
        byte[] data = readDataParam(params);
        byte[] signature;
        if (params.has("signature") && params.has("signature_path")) {
            throw new CapabilityParams.Invalid(
                    "only one of signature/signature_path");
        } else if (params.has("signature_path")) {
            signature = readGuestFile(params.requireString(
                    "signature_path", MAX_SCRIPT_CHARS));
        } else {
            signature = decodeBase64(params.requireString(
                    "signature", MAX_BASE64_CHARS));
        }
        try {
            boolean verified = keystoreSource.verify(
                    alias, algorithm, data, signature);
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("verified", verified);
            return AndroidCapabilityProtocol.Response.success(
                    request.getId(), fields);
        } catch (KeystoreException e) {
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), e.code());
        }
    }

    /** Exactly one of {@code data} (base64) or {@code path} (guest file). */
    private byte[] readDataParam(CapabilityParams params) {
        boolean hasData = params.has("data");
        boolean hasPath = params.has("path");
        if (hasData == hasPath) {
            throw new CapabilityParams.Invalid(
                    "exactly one of data/path is required");
        }
        if (hasPath) {
            return readGuestFile(params.requireString("path", MAX_SCRIPT_CHARS));
        }
        return decodeBase64(params.requireString("data", MAX_BASE64_CHARS));
    }

    private byte[] decodeBase64(String value) {
        try {
            return Base64.getDecoder().decode(value);
        } catch (IllegalArgumentException e) {
            throw new CapabilityParams.Invalid("invalid base64 value");
        }
    }

    private byte[] readGuestFile(String guestPath) {
        Path resolved;
        try {
            resolved = pathResolver.resolveForRead(guestPath);
        } catch (GuestFilePathResolver.Invalid e) {
            throw new CapabilityParams.Invalid("path outside the guest rootfs");
        }
        try {
            if (!Files.isRegularFile(resolved)
                    || Files.size(resolved) > MAX_KEYSTORE_FILE_BYTES) {
                throw new CapabilityParams.Invalid(
                        "not a regular file or too large: path");
            }
            return Files.readAllBytes(resolved);
        } catch (NoSuchFileException e) {
            throw new CapabilityParams.Invalid("no such guest file: path");
        } catch (IOException e) {
            Log.w(TAG, "keystore: guest file read failed", e);
            throw new CapabilityParams.Invalid("unreadable guest file: path");
        }
    }

    private static int purposesMask(List<String> names) {
        if (names.isEmpty()) {
            return android.security.keystore.KeyProperties.PURPOSE_SIGN
                    | android.security.keystore.KeyProperties.PURPOSE_VERIFY;
        }
        int mask = 0;
        for (String name : names) {
            switch (normalize(name)) {
                case "encrypt":
                    mask |= android.security.keystore.KeyProperties.PURPOSE_ENCRYPT;
                    break;
                case "decrypt":
                    mask |= android.security.keystore.KeyProperties.PURPOSE_DECRYPT;
                    break;
                case "sign":
                    mask |= android.security.keystore.KeyProperties.PURPOSE_SIGN;
                    break;
                case "verify":
                    mask |= android.security.keystore.KeyProperties.PURPOSE_VERIFY;
                    break;
                case "wrapkey":
                    mask |= android.security.keystore.KeyProperties.PURPOSE_WRAP_KEY;
                    break;
                case "agreekey":
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                        throw new CapabilityParams.Invalid(
                                "unavailable before API 31: agreekey");
                    }
                    mask |= android.security.keystore.KeyProperties.PURPOSE_AGREE_KEY;
                    break;
                default:
                    throw new CapabilityParams.Invalid(
                            "unknown purpose: " + name);
            }
        }
        return mask;
    }

    private static List<String> digestNames(List<String> names) {
        if (names.isEmpty()) {
            return List.of(
                    android.security.keystore.KeyProperties.DIGEST_NONE,
                    android.security.keystore.KeyProperties.DIGEST_SHA1,
                    android.security.keystore.KeyProperties.DIGEST_SHA256,
                    android.security.keystore.KeyProperties.DIGEST_SHA384,
                    android.security.keystore.KeyProperties.DIGEST_SHA512);
        }
        List<String> digests = new ArrayList<>();
        for (String name : names) {
            switch (normalize(name)) {
                case "none":
                    digests.add(android.security.keystore.KeyProperties.DIGEST_NONE);
                    break;
                case "md5":
                    digests.add(android.security.keystore.KeyProperties.DIGEST_MD5);
                    break;
                case "sha1":
                    digests.add(android.security.keystore.KeyProperties.DIGEST_SHA1);
                    break;
                case "sha224":
                    digests.add(android.security.keystore.KeyProperties.DIGEST_SHA224);
                    break;
                case "sha256":
                    digests.add(android.security.keystore.KeyProperties.DIGEST_SHA256);
                    break;
                case "sha384":
                    digests.add(android.security.keystore.KeyProperties.DIGEST_SHA384);
                    break;
                case "sha512":
                    digests.add(android.security.keystore.KeyProperties.DIGEST_SHA512);
                    break;
                default:
                    throw new CapabilityParams.Invalid("unknown digest: " + name);
            }
        }
        return digests;
    }

    private static String normalize(String name) {
        return name.trim().toLowerCase(java.util.Locale.ROOT)
                .replace("-", "").replace("_", "");
    }

    // ---------------------------------------------------------------
    // jobscheduler.*
    // ---------------------------------------------------------------

    /**
     * {@code jobscheduler.schedule} — {@code script} (guest path, required),
     * {@code job_id} (0..2^31-1, default 0), {@code period_ms} (0..7d;
     * 0 = one-shot), {@code network} (any|unmetered|cellular|not_roaming|none),
     * {@code battery_not_low} (default true), {@code storage_not_low},
     * {@code charging}, {@code idle}, {@code persisted} (bools), and optional
     * {@code trigger_content_uri}+{@code trigger_content_flag}. Schedules a
     * real {@link JobInfo} for {@link CommsJobService}; the job runs the
     * script inside the guest session only while that session is alive.
     * Success fields: {@code scheduled}, {@code job_id}, {@code result_code},
     * and {@code jobs_json} (the pending list after scheduling).
     */
    private AndroidCapabilityProtocol.Response jobSchedule(
            AndroidCapabilityProtocol.Request request) {
        CapabilityParams params = CapabilityParams.of(request);
        params.rejectUnknown(Set.of("script", "job_id", "period_ms", "network",
                "battery_not_low", "storage_not_low", "charging", "idle",
                "persisted", "trigger_content_uri", "trigger_content_flag"));
        String script = params.requireString("script", MAX_SCRIPT_CHARS);
        long jobId = params.optionalLong("job_id", 0, MAX_JOB_ID, 0);
        long periodMs = params.optionalLong("period_ms", 0, MAX_PERIOD_MS, 0);
        int network = networkType(
                params.optionalString("network", MAX_NETWORK_CHARS, "any"));
        boolean batteryNotLow = params.optionalBoolean("battery_not_low", true);
        boolean storageNotLow = params.optionalBoolean("storage_not_low", false);
        boolean charging = params.optionalBoolean("charging", false);
        boolean idle = params.optionalBoolean("idle", false);
        boolean persisted = params.optionalBoolean("persisted", false);
        String triggerUri = params.optionalString(
                "trigger_content_uri", MAX_TRIGGER_URI_CHARS, null);
        long triggerFlag = params.optionalLong("trigger_content_flag", 0, 1, 1);

        Path resolved = resolveExecutable(script);
        if (!guestSessionRunning()) {
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), JOB_SESSION_DOWN_ERROR);
        }

        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        if (scheduler == null) {
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), "jobscheduler-unavailable");
        }
        PersistableBundle extras = new PersistableBundle();
        extras.putString(CommsJobService.EXTRA_SCRIPT, script);
        JobInfo.Builder builder = new JobInfo.Builder(
                (int) jobId, new ComponentName(context, CommsJobService.class))
                .setExtras(extras)
                .setRequiredNetworkType(network)
                .setRequiresBatteryNotLow(batteryNotLow)
                .setRequiresStorageNotLow(storageNotLow)
                .setRequiresCharging(charging)
                .setRequiresDeviceIdle(idle)
                .setPersisted(persisted);
        if (periodMs > 0) {
            builder.setPeriodic(periodMs);
        }
        if (triggerUri != null && !triggerUri.isEmpty()) {
            Uri uri = Uri.parse(triggerUri);
            if (uri.getScheme() == null) {
                throw new CapabilityParams.Invalid(
                        "invalid parameter: trigger_content_uri");
            }
            builder.addTriggerContentUri(new JobInfo.TriggerContentUri(
                    uri, (int) triggerFlag));
        }

        int resultCode;
        try {
            resultCode = scheduler.schedule(builder.build());
        } catch (RuntimeException e) {
            // E.g. the CommsJobService manifest entry is not wired yet.
            Log.w(TAG, "jobscheduler.schedule failed", e);
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), "jobscheduler-unavailable");
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("scheduled", resultCode == JobScheduler.RESULT_SUCCESS);
        fields.put("job_id", jobId);
        fields.put("result_code", (long) resultCode);
        fields.put("jobs_json", pendingJobsJson(scheduler));
        return AndroidCapabilityProtocol.Response.success(request.getId(), fields);
    }

    /**
     * {@code jobscheduler.list} — no params. Success fields {@code jobs_json}
     * (pending jobs, upstream description fields plus {@code last_result}
     * when a fired outcome exists) and {@code recent_json} (bounded outcomes
     * for jobs no longer pending, so a missed trigger stays visible).
     */
    private AndroidCapabilityProtocol.Response jobList(
            AndroidCapabilityProtocol.Request request) {
        CapabilityParams.of(request).rejectUnknown(Set.of());
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        if (scheduler == null) {
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), "jobscheduler-unavailable");
        }
        List<JobInfo> pending = scheduler.getAllPendingJobs();
        Set<Integer> pendingIds = new HashSet<>();
        for (JobInfo info : pending) {
            pendingIds.add(info.getId());
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("jobs_json", pendingJobsJson(scheduler));
        fields.put("recent_json", CommsJobStore.recentJson(
                CommsJobStore.recent(context, pendingIds, MAX_RECENT_OUTCOMES)));
        return AndroidCapabilityProtocol.Response.success(request.getId(), fields);
    }

    /**
     * {@code jobscheduler.cancel} — {@code all} (bool) or {@code job_id}.
     * Cancels real pending jobs and drops their stored outcomes. Success
     * fields: {@code cancelled}, {@code found} (single-id form) or
     * {@code count} (all form), {@code job_id} when given, and
     * {@code jobs_json} describing what was cancelled.
     */
    private AndroidCapabilityProtocol.Response jobCancel(
            AndroidCapabilityProtocol.Request request) {
        CapabilityParams params = CapabilityParams.of(request);
        params.rejectUnknown(Set.of("job_id", "all"));
        boolean all = params.optionalBoolean("all", false);
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        if (scheduler == null) {
            return AndroidCapabilityProtocol.Response.error(
                    request.getId(), "jobscheduler-unavailable");
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        if (all) {
            List<JobInfo> pending = scheduler.getAllPendingJobs();
            fields.put("jobs_json", jobsJson(pending));
            scheduler.cancelAll();
            CommsJobStore.clear(context);
            fields.put("cancelled", Boolean.TRUE);
            fields.put("count", (long) pending.size());
            return AndroidCapabilityProtocol.Response.success(
                    request.getId(), fields);
        }
        long jobId = params.optionalLong("job_id", 0, MAX_JOB_ID, -1);
        if (jobId < 0) {
            throw new CapabilityParams.Invalid(
                    "missing parameter: job_id (or set all)");
        }
        JobInfo info = scheduler.getPendingJob((int) jobId);
        if (info != null) {
            fields.put("jobs_json", jobsJson(List.of(info)));
            scheduler.cancel((int) jobId);
            CommsJobStore.delete(context, (int) jobId);
        } else {
            fields.put("jobs_json", "[]");
        }
        fields.put("cancelled", info != null);
        fields.put("found", info != null);
        fields.put("job_id", jobId);
        return AndroidCapabilityProtocol.Response.success(request.getId(), fields);
    }

    private Path resolveExecutable(String guestPath) {
        Path resolved;
        try {
            resolved = pathResolver.resolveForRead(guestPath);
        } catch (GuestFilePathResolver.Invalid e) {
            throw new CapabilityParams.Invalid(
                    "script outside the guest rootfs");
        }
        if (!Files.isRegularFile(resolved) || !Files.isExecutable(resolved)) {
            throw new CapabilityParams.Invalid(
                    "script is not an executable file");
        }
        return resolved;
    }

    private boolean guestSessionRunning() {
        HostRuntimeStatus status = RuntimeStatusBus.getInstance().current();
        return status != null && status.isRuntimeRunning();
    }

    private static int networkType(String name) {
        switch (normalize(name)) {
            case "any":
                return JobInfo.NETWORK_TYPE_ANY;
            case "unmetered":
                return JobInfo.NETWORK_TYPE_UNMETERED;
            case "cellular":
                return JobInfo.NETWORK_TYPE_CELLULAR;
            case "notroaming":
                return JobInfo.NETWORK_TYPE_NOT_ROAMING;
            case "none":
                return JobInfo.NETWORK_TYPE_NONE;
            default:
                throw new CapabilityParams.Invalid("unknown network: " + name);
        }
    }

    private static String networkName(int type) {
        switch (type) {
            case JobInfo.NETWORK_TYPE_UNMETERED:
                return "unmetered";
            case JobInfo.NETWORK_TYPE_CELLULAR:
                return "cellular";
            case JobInfo.NETWORK_TYPE_NOT_ROAMING:
                return "not_roaming";
            case JobInfo.NETWORK_TYPE_NONE:
                return "none";
            default:
                return "any";
        }
    }

    private String pendingJobsJson(JobScheduler scheduler) {
        List<JobInfo> pending;
        try {
            pending = scheduler.getAllPendingJobs();
        } catch (RuntimeException e) {
            pending = List.of();
        }
        return jobsJson(pending);
    }

    /**
     * Upstream description fields per pending job — {@code id},
     * {@code script}, {@code periodic}, {@code interval_ms},
     * {@code network}, {@code charging}, {@code idle}, {@code persisted},
     * {@code battery_not_low}, {@code storage_not_low} — plus the last
     * recorded outcome ({@code last_result}, {@code last_fired_ms},
     * {@code last_exit_code}) when one exists.
     */
    private String jobsJson(List<JobInfo> pending) {
        StringWriter out = new StringWriter();
        JsonWriter writer = new JsonWriter(out);
        try {
            writer.beginArray();
            for (JobInfo info : pending) {
                writer.beginObject();
                writer.name("id").value(info.getId());
                String script = info.getExtras() == null
                        ? "" : info.getExtras().getString(
                                CommsJobService.EXTRA_SCRIPT, "");
                writer.name("script").value(script);
                writer.name("periodic").value(info.isPeriodic());
                writer.name("interval_ms").value(info.getIntervalMillis());
                writer.name("network")
                        .value(networkName(info.getNetworkType()));
                writer.name("charging").value(info.isRequireCharging());
                writer.name("idle").value(info.isRequireDeviceIdle());
                writer.name("persisted").value(info.isPersisted());
                writer.name("battery_not_low")
                        .value(info.isRequireBatteryNotLow());
                writer.name("storage_not_low")
                        .value(info.isRequireStorageNotLow());
                CommsJobStore.Outcome outcome =
                        CommsJobStore.load(context, info.getId());
                if (outcome != null) {
                    writer.name("last_result").value(outcome.result);
                    writer.name("last_fired_ms").value(outcome.firedMs);
                    if (outcome.exitCode != null) {
                        writer.name("last_exit_code")
                                .value(outcome.exitCode.intValue());
                    }
                }
                writer.endObject();
            }
            writer.endArray();
            writer.flush();
        } catch (IOException e) {
            return "[]";
        } finally {
            try {
                writer.close();
            } catch (IOException ignored) {
            }
        }
        return out.toString();
    }

    // ---------------------------------------------------------------
    // shared
    // ---------------------------------------------------------------

    /**
     * Map a runtime-permission state to the capability's typed pair; returns
     * {@code null} when the permission is granted.
     */
    private AndroidCapabilityProtocol.Response permissionError(
            AndroidCapabilityProtocol.Request request, String permission,
            String capability) {
        CapabilityPermission state = permissionChecker.check(permission);
        if (state == CapabilityPermission.GRANTED) {
            return null;
        }
        return AndroidCapabilityProtocol.Response.error(request.getId(),
                capability + "-permission-"
                        + (state == CapabilityPermission.DENIED
                                ? "denied" : "required"));
    }
}
