package gh.nusashell.nusadesk.infrastructure.androidbridge;

import java.util.Collections;
import java.util.List;

/**
 * USB pass-through source for the guest (ADR-0041).
 *
 * <p>The Android host is the only component that may talk to {@code UsbManager}
 * and obtain a usbfs descriptor; this port is how the bridge exposes exactly
 * that, and nothing more. There is deliberately no raw-transfer method here:
 * after {@link #open(int, int, String)} the descriptor lives in the guest, and
 * raw URBs are the guest's business.</p>
 */
public interface UsbPassThroughSource {

    /** Enumerates attached USB devices, deterministically sorted. */
    List<UsbDeviceEntry> list();

    /**
     * Requests permission if needed (a bounded system-dialog wait), opens the
     * device, and delivers its descriptor to the guest's abstract unix socket
     * named {@code socketName} via SCM_RIGHTS.
     */
    OpenResult open(int vendorId, int productId, String socketName);

    /** Immutable description of one attached USB device. */
    final class UsbDeviceEntry {

        private final int vendorId;
        private final int productId;
        private final String name;
        private final String manufacturer;
        private final String product;
        private final String interfaces;

        public UsbDeviceEntry(int vendorId, int productId, String name,
                String manufacturer, String product) {
            this(vendorId, productId, name, manufacturer, product, null);
        }

        public UsbDeviceEntry(int vendorId, int productId, String name,
                String manufacturer, String product, String interfaces) {
            this.vendorId = vendorId;
            this.productId = productId;
            this.name = name;
            this.manufacturer = manufacturer;
            this.product = product;
            this.interfaces = interfaces;
        }

        public int getVendorId() {
            return vendorId;
        }

        public int getProductId() {
            return productId;
        }

        public String getName() {
            return name;
        }

        public String getManufacturer() {
            return manufacturer;
        }

        public String getProduct() {
            return product;
        }

        /**
         * Comma-joined {@code class/subclass/protocol} hex pairs, one per
         * interface, or {@code null} when the platform did not report any.
         * The guest USB driver filters on the adb interface signature
         * ({@code ff4201}) so non-adb devices are never opened.
         */
        public String getInterfaces() {
            return interfaces;
        }
    }

    /** Typed outcome of one open attempt. */
    enum OpenState {
        OPENED,
        DEVICE_NOT_FOUND,
        PERMISSION_DENIED,
        PERMISSION_TIMEOUT,
        OPEN_FAILED,
        SOCKET_FAILED
    }

    /** Immutable open outcome; {@code state} is the machine-readable truth. */
    final class OpenResult {

        private final OpenState state;
        private final int vendorId;
        private final int productId;

        private OpenResult(OpenState state, int vendorId, int productId) {
            this.state = state;
            this.vendorId = vendorId;
            this.productId = productId;
        }

        public static OpenResult of(OpenState state, int vendorId, int productId) {
            return new OpenResult(state, vendorId, productId);
        }

        public OpenState getState() {
            return state;
        }

        public int getVendorId() {
            return vendorId;
        }

        public int getProductId() {
            return productId;
        }

        public boolean opened() {
            return state == OpenState.OPENED;
        }
    }

    /**
     * A source that has nothing to offer devices. Used in JVM tests and
     * environments without a USB manager.
     */
    final class Empty implements UsbPassThroughSource {

        @Override
        public List<UsbDeviceEntry> list() {
            return Collections.emptyList();
        }

        @Override
        public OpenResult open(int vendorId, int productId, String socketName) {
            return OpenResult.of(OpenState.DEVICE_NOT_FOUND, vendorId, productId);
        }
    }
}
