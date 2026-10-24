package gh.nusashell.nusadesk.infrastructure.display;

/** JNI renderer callbacks, independent of any Activity instance. */
final class X11Callbacks {
    @SuppressWarnings("unused") long lastServerHeartbeat;
    private final Runnable disconnected;

    X11Callbacks(Runnable disconnected) { this.disconnected = disconnected; }

    /**
     * The pinned renderer calls this when the client connection drops. Connects
     * are attached by {@link X11Surface#attachConnection}, not through here.
     */
    @SuppressWarnings("unused") void clientConnectedStateChanged() { disconnected.run(); }
}
