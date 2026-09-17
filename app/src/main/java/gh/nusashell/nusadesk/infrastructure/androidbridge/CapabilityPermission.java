package gh.nusashell.nusadesk.infrastructure.androidbridge;

/**
 * Runtime-permission level resolved per operation, kept Android-free for
 * testing.
 *
 * <p>{@link #DENIED} records that the user previously refused the grant,
 * while {@link #REQUIRED} means no grant exists and no denial is recorded
 * (the later user-visible consent flow may ask). The adapter maps these to
 * the matching {@link MessagingReadState} for the operation.</p>
 */
public enum CapabilityPermission {
    /** The runtime permission is granted. */
    GRANTED,
    /** No grant exists and the user previously denied it. */
    DENIED,
    /** No grant exists and no denial is recorded; a consent flow may ask. */
    REQUIRED
}
