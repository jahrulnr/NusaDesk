package gh.nusashell.nusadesk.infrastructure.service;

import android.os.SystemClock;

/**
 * Process-local state for the capture capability: the one in-flight camera
 * photo and the one microphone recording the guest can own at a time.
 *
 * <p>Mirrors the {@link LiveMediaRegistry} pattern: {@link CaptureService}
 * publishes typed results here and the bridge side
 * ({@code CaptureModule} / {@code CaptureForegroundOperation}) awaits them
 * with a bounded wait. The registry carries no secret material and is
 * process-local, so a process death resets every slot to idle and no false
 * {@code recording} survives a restart.</p>
 *
 * <p>The recording deliberately outlives the guest command that started it
 * (upstream {@code termux-microphone-record} semantics): the service holds
 * the recorder, this registry holds the state, and
 * {@code microphone.record.info}/{@code .stop} query it later. A recording
 * that ended on its own (duration limit, service teardown after a clean
 * finalize) is kept as {@code lastCompleted} so a late {@code stop} call can
 * still report the finished file exactly once.</p>
 *
 * <p>Every mutating call is sequence-guarded: an outcome published by a
 * stale, abandoned capture task can never complete a newer wait.</p>
 */
public final class CaptureRegistry {

    private static final CaptureRegistry INSTANCE = new CaptureRegistry();

    public static CaptureRegistry getInstance() {
        return INSTANCE;
    }

    private static final int PHOTO_IDLE = 0;
    private static final int PHOTO_CAPTURING = 1;

    private static final int REC_IDLE = 0;
    private static final int REC_STARTING = 1;
    private static final int REC_RECORDING = 2;
    private static final int REC_STOPPING = 3;

    /** Terminal result of one photo capture. */
    public static final class PhotoOutcome {
        /** Bounded typed error code; null on success. */
        public final String error;
        /** Bytes written to the staging file; 0 on failure. */
        public final long bytes;

        PhotoOutcome(String error, long bytes) {
            this.error = error;
            this.bytes = bytes;
        }
    }

    /** Terminal result of one recording start. */
    public static final class RecordStartOutcome {
        /** Bounded typed error code; null on success. */
        public final String error;

        RecordStartOutcome(String error) {
            this.error = error;
        }
    }

    /** Snapshot of the recorder state for {@code microphone.record.info}. */
    public static final class RecordingInfo {
        public final boolean recording;
        /** Guest staging path the recorder writes to; null when idle. */
        public final String guestPath;
        /** Milliseconds since the recorder actually started. */
        public final long durationMs;
        /** Configured max-duration bound in ms; 0 when unknown. */
        public final long limitMs;

        RecordingInfo(boolean recording, String guestPath, long durationMs,
                      long limitMs) {
            this.recording = recording;
            this.guestPath = guestPath;
            this.durationMs = durationMs;
            this.limitMs = limitMs;
        }
    }

    /** Terminal result of one stop, or a completed recording awaiting a -q. */
    public static final class StopOutcome {
        /** Bounded typed error code; null on a clean finalize. */
        public final String error;
        /** Guest staging path of the finalized file. */
        public final String guestPath;
        /** Final file size in bytes; -1 when unknown. */
        public final long bytes;

        StopOutcome(String error, String guestPath, long bytes) {
            this.error = error;
            this.guestPath = guestPath;
            this.bytes = bytes;
        }
    }

    private int photoState = PHOTO_IDLE;
    private long photoSeq;
    private PhotoOutcome photoOutcome;

    private int recState = REC_IDLE;
    private long recSeq;
    private String recGuestPath;
    private long recLimitMs;
    private long recStartedElapsedMs;
    private RecordStartOutcome recOutcome;
    private StopOutcome stopOutcome;
    private StopOutcome lastCompleted;

    private CaptureRegistry() {
    }

    // ------------------------------------------------------------------
    // photo
    // ------------------------------------------------------------------

    /**
     * Claim the single photo slot. Returns the capture sequence to hand to
     * the service, or -1 when a capture is already in flight.
     */
    public synchronized long beginPhoto() {
        if (photoState != PHOTO_IDLE) {
            return -1;
        }
        photoState = PHOTO_CAPTURING;
        photoOutcome = null;
        return ++photoSeq;
    }

    /** The service finalized the JPEG; ignored for a stale sequence. */
    public synchronized void publishPhoto(long seq, long bytes) {
        if (photoState != PHOTO_CAPTURING || seq != photoSeq) {
            return;
        }
        photoState = PHOTO_IDLE;
        photoOutcome = new PhotoOutcome(null, bytes);
        notifyAll();
    }

    /** The capture failed with a typed code; ignored for a stale sequence. */
    public synchronized void failPhoto(long seq, String code) {
        if (photoState != PHOTO_CAPTURING || seq != photoSeq) {
            return;
        }
        photoState = PHOTO_IDLE;
        photoOutcome = new PhotoOutcome(code, 0);
        notifyAll();
    }

    /**
     * Block until this capture publishes, up to {@code timeoutMillis}.
     * Returns the outcome, or {@code null} on timeout/abandonment.
     */
    public synchronized PhotoOutcome awaitPhoto(long seq, long timeoutMillis) {
        long deadline = SystemClock.elapsedRealtime() + timeoutMillis;
        while (photoState == PHOTO_CAPTURING && seq == photoSeq) {
            long remaining = deadline - SystemClock.elapsedRealtime();
            if (remaining <= 0) {
                return null;
            }
            try {
                wait(remaining);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return photoOutcome;
    }

    /**
     * Free the slot when the waiter gave up (timeout, cancelled operation).
     * The late task's own publish is then ignored by sequence.
     */
    public synchronized void abortPhoto(long seq) {
        if (photoState == PHOTO_CAPTURING && seq == photoSeq) {
            photoState = PHOTO_IDLE;
            photoOutcome = null;
        }
    }

    public synchronized boolean photoBusy() {
        return photoState == PHOTO_CAPTURING;
    }

    // ------------------------------------------------------------------
    // recording
    // ------------------------------------------------------------------

    /**
     * Claim the single recording slot. Returns the sequence to hand to the
     * service, or -1 when a start/record/stop is already in flight. A stored
     * {@code lastCompleted} outcome is consumed by the claim, so a new
     * recording never reports the previous one's file.
     */
    public synchronized long beginRecording(String guestPath, long limitMs) {
        if (recState != REC_IDLE) {
            return -1;
        }
        recState = REC_STARTING;
        recOutcome = null;
        stopOutcome = null;
        lastCompleted = null;
        recGuestPath = guestPath;
        recLimitMs = limitMs;
        recStartedElapsedMs = 0;
        return ++recSeq;
    }

    /**
     * The recorder is actually capturing audio. Returns {@code false} for a
     * stale/abandoned sequence — the service then stops the orphan recorder
     * itself, because nobody will ever ask about it.
     */
    public synchronized boolean publishRecording(long seq) {
        if (recState != REC_STARTING || seq != recSeq) {
            return false;
        }
        recState = REC_RECORDING;
        recStartedElapsedMs = SystemClock.elapsedRealtime();
        recOutcome = new RecordStartOutcome(null);
        notifyAll();
        return true;
    }

    /** The recorder failed to start; ignored for a stale sequence. */
    public synchronized void failRecording(long seq, String code) {
        if (recState != REC_STARTING || seq != recSeq) {
            return;
        }
        recState = REC_IDLE;
        recGuestPath = null;
        recOutcome = new RecordStartOutcome(code);
        notifyAll();
    }

    /**
     * Block until this start publishes, up to {@code timeoutMillis}.
     * Returns the outcome, or {@code null} on timeout.
     */
    public synchronized RecordStartOutcome awaitRecording(long seq,
                                                          long timeoutMillis) {
        long deadline = SystemClock.elapsedRealtime() + timeoutMillis;
        while (recState == REC_STARTING && seq == recSeq) {
            long remaining = deadline - SystemClock.elapsedRealtime();
            if (remaining <= 0) {
                return null;
            }
            try {
                wait(remaining);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return recOutcome;
    }

    /**
     * Free the slot when the starter gave up before the service reported.
     * A late success publish for this sequence is then ignored.
     */
    public synchronized void abortRecording(long seq) {
        if (recState == REC_STARTING && seq == recSeq) {
            recState = REC_IDLE;
            recGuestPath = null;
            recOutcome = null;
        }
    }

    /** True while any recording transition or active recording is held. */
    public synchronized boolean recordingBusy() {
        return recState != REC_IDLE;
    }

    /** Current recorder snapshot for {@code microphone.record.info}. */
    public synchronized RecordingInfo info() {
        boolean recording = recState == REC_RECORDING;
        return new RecordingInfo(recording,
                recording ? recGuestPath : null,
                recording ? SystemClock.elapsedRealtime() - recStartedElapsedMs : 0,
                recLimitMs);
    }

    /** The live recording sequence, or -1 when no start/record is held. */
    public synchronized long recordingSeq() {
        return (recState == REC_STARTING || recState == REC_RECORDING
                || recState == REC_STOPPING) ? recSeq : -1;
    }

    /**
     * Move RECORDING to STOPPING and return the sequence to hand to the
     * service stop intent; 0 when nothing is recording (including the
     * start/stop transitions, which answer {@code microphone-busy}).
     */
    public synchronized long requestStopSeq() {
        if (recState != REC_RECORDING) {
            return 0;
        }
        recState = REC_STOPPING;
        stopOutcome = null;
        return recSeq;
    }

    /**
     * The recording finalized (explicit stop, duration limit, or a service
     * teardown that still managed a clean {@code MediaRecorder.stop()}).
     * Sequence-guarded only: a stop that lands while the slot is STARTING or
     * already IDLE-but-unclaimed still produces a real file, so the outcome
     * is kept as {@code lastCompleted} for a later {@code stop} call.
     */
    public synchronized void publishStopped(long seq, long bytes) {
        if (seq != recSeq) {
            return;
        }
        StopOutcome outcome = new StopOutcome(null, recGuestPath, bytes);
        lastCompleted = outcome;
        if (recState == REC_STOPPING) {
            stopOutcome = outcome;
        }
        recState = REC_IDLE;
        recGuestPath = null;
        notifyAll();
    }

    /**
     * The recording died without a finalized file: a requested stop failed
     * inside the recorder, a duration-limit finalize failed, or a mid-record
     * platform error ended the capture. The partial file is unusable (no
     * container trailer), so nothing is retained for a late {@code stop}.
     */
    public synchronized void failStopped(long seq, String code) {
        if (seq != recSeq
                || (recState != REC_RECORDING && recState != REC_STOPPING)) {
            return;
        }
        if (recState == REC_STOPPING) {
            stopOutcome = new StopOutcome(code, null, -1);
        }
        recState = REC_IDLE;
        recGuestPath = null;
        notifyAll();
    }

    /**
     * Block until this stop publishes, up to {@code timeoutMillis}. Returns
     * the outcome, or {@code null} on timeout — in which case the pending
     * stop is abandoned and a late publish still lands in
     * {@code lastCompleted} for the next {@code stop} call.
     */
    public synchronized StopOutcome awaitStop(long seq, long timeoutMillis) {
        long deadline = SystemClock.elapsedRealtime() + timeoutMillis;
        while (recState == REC_STOPPING && seq == recSeq) {
            long remaining = deadline - SystemClock.elapsedRealtime();
            if (remaining <= 0) {
                recState = REC_IDLE;
                recGuestPath = null;
                return null;
            }
            try {
                wait(remaining);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return stopOutcome;
    }

    /**
     * Take the retained outcome of a recording that already ended (limit
     * auto-stop, or a stop whose waiter had given up). {@code null} when
     * nothing completed since the last consumption.
     */
    public synchronized StopOutcome consumeCompleted() {
        StopOutcome outcome = lastCompleted;
        lastCompleted = null;
        return outcome;
    }

    /**
     * The service died (or is being destroyed) with work in flight: an
     * in-flight photo reports {@code camera-unavailable}, a starting
     * recording reports {@code microphone-unavailable}, and an active or
     * stopping recording resets to idle without a completed outcome — an
     * unfinalized file is never reported as a finished recording.
     */
    public synchronized void onServiceLost() {
        if (photoState == PHOTO_CAPTURING) {
            photoState = PHOTO_IDLE;
            photoOutcome = new PhotoOutcome("camera-unavailable", 0);
        }
        if (recState == REC_STARTING) {
            recState = REC_IDLE;
            recOutcome = new RecordStartOutcome("microphone-unavailable");
        } else if (recState == REC_RECORDING || recState == REC_STOPPING) {
            recState = REC_IDLE;
            stopOutcome = new StopOutcome("microphone-unavailable", null, -1);
        }
        recGuestPath = null;
        notifyAll();
    }
}
