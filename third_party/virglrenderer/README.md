# Android GLES renderer

virglrenderer **1.3.0** and libepoxy **1.5.10** are MIT licensed. They are
established Mesa/virtual-GPU components, also packaged by Termux. The build
script pins and verifies both source archives; it builds only the GLES/EGL
vtest server, without Venus, video, DRM renderers, GBM, or ANGLE.

`android.patch` adapts upstream to public NDK APIs: Android EGL without GBM,
Clang offsetof warnings, portable Mesa logging (as in Termux's Android build),
and `ASharedMemory_create` for transferable buffers. The executable sets
`PR_SET_PDEATHSIG` before starting, so a killed Android owner cannot leave a
renderer child. It runs without forking, with multiple vtest clients.
Mali-T880 multisample WebGL framebuffer creation remains a known limitation;
non-MSAA guest/Electron drawing is the verified subset. No sample-capability
workaround is shipped (see ADR-0081).

Sources and hashes are in `scripts/build-virgl-arm64.sh`. Android NDK
29.0.14206865 targets API 29, arm64, with 16 KB ELF segment alignment. This is
not proof of operation on a 16 KB device. The installer extracts the executable
PIE as `libvirglserver.so` into the read-only native library directory; it is
executed there, never dlopened or executed from writable storage. Generated
binaries and source downloads are not committed. Both upstream license files
are bundled in the APK.

References: [upstream](https://gitlab.freedesktop.org/virgl/virglrenderer),
[libepoxy](https://github.com/anholt/libepoxy),
[Termux build](https://github.com/termux/termux-packages/tree/master/packages/virglrenderer-android).
