package gh.nusashell.nusadesk.infrastructure.androidbridge;

import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.FileDescriptor;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * {@link UsbPassThroughSource} backed by the platform's {@link UsbManager}
 * (ADR-0041).
 *
 * <p>Opening a device uses the platform consent dialog — the receiver below
 * waits, bounded, for the user's answer, and a denied or ignored dialog is a
 * typed result, never a silent open. The descriptor handed to the guest is
 * the connection's own usbfs descriptor; the platform has already validated
 * that this app may hold it, and the fd survives in the guest after it is
 * delivered over SCM_RIGHTS even though the app keeps its own handle in
 * {@link #openConnections} for the life of the bridge.</p>
 */
public final class AndroidUsbPassThrough implements UsbPassThroughSource, AutoCloseable {

    private static final String TAG = "AndroidUsbPassThrough";
    private static final String ACTION_PERMISSION =
            "gh.nusashell.nusadesk.usb.PERMISSION";
    private static final long PERMISSION_TIMEOUT_MILLIS = 60_000L;

    private final Context context;
    private final UsbManager usbManager;

    /**
     * App-side handles of devices already handed to the guest. The guest's
     * duplicated fd is the durable reference; this list keeps the app handle
     * too, and {@link #close()} releases all of them when the bridge ends.
     * There is at most one live connection per (vendorId, productId): a new
     * open for the same device replaces the previous one, because a stale
     * claim on the old usbfs file blocks the next open's interface claim
     * (observed on device as EBUSY).
     */
    private final List<HeldConnection> openConnections = new ArrayList<>();

    /** One held connection with the identity needed to replace it. */
    private static final class HeldConnection {
        private final int vendorId;
        private final int productId;
        private final UsbDeviceConnection connection;

        HeldConnection(int vendorId, int productId, UsbDeviceConnection connection) {
            this.vendorId = vendorId;
            this.productId = productId;
            this.connection = connection;
        }
    }

    public AndroidUsbPassThrough(Context context) {
        if (context == null) {
            throw new IllegalArgumentException("context must not be null");
        }
        this.context = context.getApplicationContext();
        this.usbManager = (UsbManager) this.context.getSystemService(Context.USB_SERVICE);
    }

    @Override
    public List<UsbDeviceEntry> list() {
        List<UsbDeviceEntry> entries = new ArrayList<>();
        if (usbManager == null) {
            return entries;
        }
        Map<String, UsbDevice> devices;
        try {
            devices = usbManager.getDeviceList();
        } catch (RuntimeException e) {
            return entries;
        }
        if (devices == null) {
            return entries;
        }
        for (UsbDevice device : devices.values()) {
            entries.add(new UsbDeviceEntry(device.getVendorId(), device.getProductId(),
                    device.getDeviceName(), nameOrNull(device, true), nameOrNull(device, false)));
        }
        entries.sort(Comparator.comparingInt(UsbDeviceEntry::getVendorId)
                .thenComparingInt(UsbDeviceEntry::getProductId)
                .thenComparing(entry -> entry.getName() == null ? "" : entry.getName()));
        return entries;
    }

    @Override
    public OpenResult open(int vendorId, int productId, String socketName) {
        if (usbManager == null || socketName == null) {
            return OpenResult.of(OpenState.OPEN_FAILED, vendorId, productId);
        }
        UsbDevice device = find(vendorId, productId);
        if (device == null) {
            return OpenResult.of(OpenState.DEVICE_NOT_FOUND, vendorId, productId);
        }
        if (!usbManager.hasPermission(device)) {
            OpenState consent = awaitPermission(device);
            if (consent != OpenState.OPENED) {
                return OpenResult.of(consent, vendorId, productId);
            }
        }
        // Replace any previous connection to this device: its usbfs claim
        // would otherwise make the guest's claim on the new descriptor fail
        // while the old file is still open.
        closePrevious(vendorId, productId);
        UsbDeviceConnection connection;
        try {
            connection = usbManager.openDevice(device);
        } catch (RuntimeException e) {
            return OpenResult.of(OpenState.OPEN_FAILED, vendorId, productId);
        }
        if (connection == null) {
            return OpenResult.of(OpenState.OPEN_FAILED, vendorId, productId);
        }
        if (!sendDescriptor(connection, socketName)) {
            connection.close();
            return OpenResult.of(OpenState.SOCKET_FAILED, vendorId, productId);
        }
        synchronized (openConnections) {
            openConnections.add(new HeldConnection(vendorId, productId, connection));
        }
        return OpenResult.of(OpenState.OPENED, vendorId, productId);
    }

    /** Releases the app-side handle of a previous open for the same device. */
    private void closePrevious(int vendorId, int productId) {
        synchronized (openConnections) {
            java.util.Iterator<HeldConnection> iterator = openConnections.iterator();
            while (iterator.hasNext()) {
                HeldConnection held = iterator.next();
                if (held.vendorId == vendorId && held.productId == productId) {
                    iterator.remove();
                    try {
                        held.connection.close();
                    } catch (RuntimeException ignored) {
                        // Releasing a connection twice is harmless.
                    }
                }
            }
        }
    }

    /** Delivers the connection's descriptor to the guest's abstract socket. */
    private boolean sendDescriptor(UsbDeviceConnection connection, String socketName) {
        LocalSocket socket = new LocalSocket();
        ParcelFileDescriptor duplicated = null;
        try {
            // fromFd() dups the usbfs fd: the duplicate is what travels with
            // the message, so closing it after the send leaves both the
            // connection's own fd and the guest's copy untouched.
            duplicated = ParcelFileDescriptor.fromFd(connection.getFileDescriptor());
            socket.connect(new LocalSocketAddress(socketName,
                    LocalSocketAddress.Namespace.ABSTRACT));
            socket.setFileDescriptorsForSend(
                    new FileDescriptor[] {duplicated.getFileDescriptor()});
            // A single byte flushes the ancillary fd payload; the guest reads
            // exactly one message and takes the descriptor from it.
            socket.getOutputStream().write(0);
            socket.getOutputStream().flush();
            return true;
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "could not deliver USB descriptor to guest: " + e.getMessage());
            return false;
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {
                // Closing an already-broken socket is a no-op.
            }
            if (duplicated != null) {
                try {
                    duplicated.close();
                } catch (IOException ignored) {
                    // Closing an already-released duplicate is a no-op.
                }
            }
        }
    }

    private UsbDevice find(int vendorId, int productId) {
        Map<String, UsbDevice> devices;
        try {
            devices = usbManager.getDeviceList();
        } catch (RuntimeException e) {
            return null;
        }
        if (devices == null) {
            return null;
        }
        for (UsbDevice device : devices.values()) {
            if (device.getVendorId() == vendorId && device.getProductId() == productId) {
                return device;
            }
        }
        return null;
    }

    /**
     * Waits, bounded, for the system consent dialog. The dialog is the only
     * grant path; a denied answer and an unanswered dialog are distinct typed
     * results.
     */
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private OpenState awaitPermission(UsbDevice device) {
        CountDownLatch answered = new CountDownLatch(1);
        boolean[] granted = new boolean[1];
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context receiverContext, Intent intent) {
                if (intent != null && ACTION_PERMISSION.equals(intent.getAction())) {
                    granted[0] = intent.getBooleanExtra(
                            UsbManager.EXTRA_PERMISSION_GRANTED, false);
                    answered.countDown();
                }
            }
        };
        IntentFilter filter = new IntentFilter(ACTION_PERMISSION);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            context.registerReceiver(receiver, filter);
        }
        try {
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // The system writes the grant extra into this intent.
                flags |= PendingIntent.FLAG_MUTABLE;
            }
            PendingIntent permissionIntent = PendingIntent.getBroadcast(context, 0,
                    new Intent(ACTION_PERMISSION).setPackage(context.getPackageName()),
                    flags);
            usbManager.requestPermission(device, permissionIntent);
            if (!answered.await(PERMISSION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                return OpenState.PERMISSION_TIMEOUT;
            }
            return granted[0] ? OpenState.OPENED : OpenState.PERMISSION_DENIED;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return OpenState.PERMISSION_TIMEOUT;
        } catch (RuntimeException e) {
            return OpenState.PERMISSION_DENIED;
        } finally {
            try {
                context.unregisterReceiver(receiver);
            } catch (IllegalArgumentException notRegistered) {
                // Already gone; nothing to clean up.
            }
        }
    }

    /** Descriptor strings are best-effort and null before a grant. */
    private static String nameOrNull(UsbDevice device, boolean manufacturer) {
        try {
            return manufacturer ? device.getManufacturerName() : device.getProductName();
        } catch (RuntimeException e) {
            return null;
        }
    }

    @Override
    public void close() {
        synchronized (openConnections) {
            for (HeldConnection held : openConnections) {
                try {
                    held.connection.close();
                } catch (RuntimeException ignored) {
                    // Releasing a connection twice is harmless.
                }
            }
            openConnections.clear();
        }
    }
}
