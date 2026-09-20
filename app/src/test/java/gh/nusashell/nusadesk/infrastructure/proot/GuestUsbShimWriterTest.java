package gh.nusashell.nusadesk.infrastructure.proot;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * Focused tests for the generated guest adb shim artifacts: the locked
 * guest paths, the shadow wrapper's compile/preload/fallback contract,
 * and the C source's locked interception surface and daemon protocol
 * tokens.
 */
public class GuestUsbShimWriterTest {

    @Test
    public void constantsPinTheLockedGuestPaths() {
        assertEquals("opt/nusadesk/libusb-shim.c",
                GuestUsbShimWriter.SHIM_GUEST_PATH);
        assertEquals("usr/local/bin/adb", GuestUsbShimWriter.WRAPPER_GUEST_PATH);
        assertEquals("libusb-shim.c", GuestUsbShimWriter.SHIM_FILE_NAME);
        assertEquals("adb", GuestUsbShimWriter.WRAPPER_FILE_NAME);
    }

    @Test
    public void writerRejectsUnsafeVersions() {
        assertThrows(IllegalArgumentException.class,
                () -> GuestUsbShimWriter.shimSource(null));
        assertThrows(IllegalArgumentException.class,
                () -> GuestUsbShimWriter.shimSource("  "));
        assertThrows(IllegalArgumentException.class,
                () -> GuestUsbShimWriter.shimSource("1.0\nrm -rf"));
        assertThrows(IllegalArgumentException.class,
                () -> GuestUsbShimWriter.adbWrapperContent(null));
        assertThrows(IllegalArgumentException.class,
                () -> GuestUsbShimWriter.adbWrapperContent("1.0\r"));
    }

    @Test
    public void wrapperPinsTheShadowContract() {
        String content = GuestUsbShimWriter.adbWrapperContent("0.1.0");

        assertTrue(content.startsWith("#!/bin/sh"));
        assertTrue(content.contains("App version: 0.1.0"));

        // The fixed guest layout: shim under /opt/nusadesk, daemon and
        // the real adb at their stock paths.
        assertTrue(content.contains("SHIM_DIR=/opt/nusadesk"));
        assertTrue(content.contains("libusb-shim.so"));
        assertTrue(content.contains("libusb-shim.c"));
        assertTrue(content.contains("DAEMON=/usr/local/bin/nusadesk-usbd"));
        assertTrue(content.contains("REAL_ADB=/usr/bin/adb"));

        // The lazy compile line and its cleanup on failure.
        assertTrue(content.contains("command -v gcc"));
        assertTrue(content.contains(
                "gcc -O2 -fPIC -shared -o \"$SHIM\" \"$SRC\" -ldl"));
        assertTrue(content.contains("rm -f \"$SHIM\""));

        // The preload exec: ADB_LIBUSB=1 selects adb's libusb backend,
        // LD_PRELOAD interposes the shim, and a prior LD_PRELOAD is kept.
        assertTrue(content.contains("ADB_LIBUSB=1"));
        assertTrue(content.contains(
                "LD_PRELOAD=\"$SHIM${LD_PRELOAD:+:$LD_PRELOAD}\""));
        assertTrue(content.contains("python3 \"$DAEMON\" --ensure"));

        // The safe fallback: an unadorned exec of the real adb.
        assertTrue(content.contains("exec \"$REAL_ADB\" \"$@\""));
        // The bridge env file gates the whole path.
        assertTrue(content.contains("/run/nusadesk/android-bridge.env"));
    }

    @Test
    public void shimSourceInterceptsTheLockedAdbSurface() {
        String source = GuestUsbShimWriter.shimSource("0.1.0");

        assertTrue(source.contains("App version: 0.1.0"));
        assertTrue(source.contains("#define _GNU_SOURCE"));
        assertTrue(source.contains("#include <libusb-1.0/libusb.h>"));

        // Every libusb symbol adb (platform-tools 34) calls must exist
        // in the shim, either virtualized or forwarded.
        String[] intercepted = {
                "libusb_init",
                "libusb_hotplug_register_callback",
                "libusb_handle_events",
                "libusb_open",
                "libusb_close",
                "libusb_get_device_descriptor",
                "libusb_get_active_config_descriptor",
                "libusb_free_config_descriptor",
                "libusb_ref_device",
                "libusb_unref_device",
                "libusb_get_bus_number",
                "libusb_get_device_address",
                "libusb_get_port_numbers",
                "libusb_claim_interface",
                "libusb_release_interface",
                "libusb_clear_halt",
                "libusb_reset_device",
                "libusb_alloc_transfer",
                "libusb_submit_transfer",
                "libusb_cancel_transfer",
                "libusb_free_transfer",
                "libusb_get_string_descriptor_ascii",
                "libusb_error_name",
                "libusb_strerror",
        };
        for (String name : intercepted) {
            assertTrue("missing intercept " + name,
                    source.contains(name + "("));
        }

        // Real functions are reached lazily through RTLD_NEXT only, and
        // the usbfs fd is wrapped through the real libusb entry point.
        assertTrue(source.contains("RTLD_NEXT"));
        assertTrue(source.contains("libusb_wrap_sys_device"));
        assertTrue(source.contains("-ldl"));

        // adb's hotplug contract: the stored callback is driven by the
        // shim's own event diff inside libusb_handle_events, and the
        // remaining slice is handed to the real event pump.
        assertTrue(source.contains("LIBUSB_HOTPLUG_ENUMERATE"));
        assertTrue(source.contains("LIBUSB_HOTPLUG_EVENT_DEVICE_ARRIVED"));
        assertTrue(source.contains("LIBUSB_HOTPLUG_EVENT_DEVICE_LEFT"));
        assertTrue(source.contains(
                "real.handle_events_timeout_completed(ctx, &tv, NULL)"));
        assertTrue(source.contains("tv_usec = 250 * 1000"));
    }

    @Test
    public void shimSourcePinsTheDaemonProtocol() {
        String source = GuestUsbShimWriter.shimSource("0.1.0");

        // Abstract unix socket in the Linux namespace (leading NUL), not
        // a filesystem path.
        assertTrue(source.contains("\"nusadesk-usbd\""));
        assertTrue(source.contains("sun_path[0] = '\\0'"));
        assertTrue(source.contains("AF_UNIX"));

        // Request tokens.
        assertTrue(source.contains("\"LIST\\n\""));
        assertTrue(source.contains("\"OPEN %04x %04x\\n\""));

        // Reply tokens: DEV lines, the END terminator, OK + fd, ERR code.
        assertTrue(source.contains("\"DEV %x %x %x %x %x %x %x %x %x %x %x\""));
        assertTrue(source.contains("\"END\\n\""));
        assertTrue(source.contains("\"OK \""));
        assertTrue(source.contains("\"ERR \""));

        // The fd arrives via SCM_RIGHTS ancillary data.
        assertTrue(source.contains("CMSG_SPACE(sizeof(int))"));
        assertTrue(source.contains("SCM_RIGHTS"));
        assertTrue(source.contains("recvmsg"));

        // Typed OPEN errors map onto libusb codes.
        assertTrue(source.contains("usb-device-not-found"));
        assertTrue(source.contains("usb-permission-denied"));
        assertTrue(source.contains("usb-permission-timeout"));
        assertTrue(source.contains("LIBUSB_ERROR_NOT_FOUND"));
        assertTrue(source.contains("LIBUSB_ERROR_ACCESS"));
        assertTrue(source.contains("LIBUSB_ERROR_IO"));
    }

    @Test
    public void shimSourcePinsTheFabricatedDescriptors() {
        String source = GuestUsbShimWriter.shimSource("0.1.0");

        // The adb interface signature adb's FindInterface accepts.
        assertTrue(source.contains("bInterfaceClass = 0xFF"));
        assertTrue(source.contains("bInterfaceSubClass = 0x42"));
        assertTrue(source.contains("bInterfaceProtocol = 0x01"));

        // The fabricated device descriptor keeps class per-interface so
        // adb does not skip the device before open.
        assertTrue(source.contains("LIBUSB_CLASS_PER_INTERFACE"));
        assertTrue(source.contains("bMaxPacketSize0"));
        assertTrue(source.contains("bNumConfigurations"));

        // The fabricated config advertises one bulk out/in pair.
        assertTrue(source.contains("LIBUSB_ENDPOINT_TRANSFER_TYPE_BULK"));
        assertTrue(source.contains("0x01; /* bulk OUT */"));
        assertTrue(source.contains("0x81; /* bulk IN */"));
    }
}
