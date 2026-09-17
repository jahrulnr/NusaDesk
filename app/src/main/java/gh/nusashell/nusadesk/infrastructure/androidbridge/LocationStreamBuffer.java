package gh.nusashell.nusadesk.infrastructure.androidbridge;

import java.util.ArrayDeque;
import java.util.concurrent.TimeUnit;

/**
 * Bounded event queue of a location stream, kept Android-free for testing.
 *
 * <p>The queue never grows beyond its capacity: when a new event arrives at
 * capacity the oldest event is dropped, so a consumer that polls more slowly
 * than fixes arrive loses history, never memory. {@link #latestReading()}
 * provides the latest-value view — the most recent validated fix regardless
 * of how many events were dropped — so a caller that only needs the current
 * position can ignore the queue entirely. {@link #clear()} resets the buffer
 * when a new session starts, so a restarted stream never mixes old events
 * with new ones. The buffer is thread-safe: producers (the Android adapter's
 * delivery looper) and the single consumer can use it concurrently.</p>
 */
public final class LocationStreamBuffer {
    private final int capacity;
    private final ArrayDeque<LocationStreamEvent> queue = new ArrayDeque<>();
    private final Object monitor = new Object();
    private volatile LocationSnapshot latestReading;

    /**
     * @param capacity maximum queued events; must be positive
     */
    public LocationStreamBuffer(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
    }

    /**
     * Append an event, dropping the oldest event when the queue is at
     * capacity. A {@link LocationStreamEvent.State#READING} event also
     * becomes the latest reading.
     */
    public void offer(LocationStreamEvent event) {
        if (event == null) {
            throw new IllegalArgumentException("event must not be null");
        }
        synchronized (monitor) {
            if (queue.size() == capacity) {
                queue.removeFirst();
            }
            queue.addLast(event);
            if (event.getState() == LocationStreamEvent.State.READING) {
                latestReading = event.getSnapshot();
            }
            monitor.notifyAll();
        }
    }

    /**
     * Remove and return the oldest event, blocking up to {@code timeoutMillis}
     * when the queue is empty.
     *
     * @param timeoutMillis maximum wait; {@code 0} returns immediately
     * @return the oldest event, or {@code null} when the deadline passed with
     *         no event (or the calling thread was interrupted)
     */
    public LocationStreamEvent poll(long timeoutMillis) {
        if (timeoutMillis < 0) {
            throw new IllegalArgumentException("timeoutMillis must be non-negative");
        }
        synchronized (monitor) {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
            while (queue.isEmpty()) {
                long remainingNanos = deadline - System.nanoTime();
                if (remainingNanos <= 0) {
                    return null;
                }
                long remainingMillis = TimeUnit.NANOSECONDS.toMillis(remainingNanos);
                try {
                    monitor.wait(Math.max(1L, remainingMillis));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
            return queue.removeFirst();
        }
    }

    /**
     * Most recent validated fix, or {@code null} when no reading was offered
     * since the last {@link #clear()}.
     */
    public LocationSnapshot latestReading() {
        return latestReading;
    }

    /** Number of queued events. */
    public int size() {
        synchronized (monitor) {
            return queue.size();
        }
    }

    /** Drop all queued events and the latest reading; used at session start. */
    public void clear() {
        synchronized (monitor) {
            queue.clear();
            latestReading = null;
            monitor.notifyAll();
        }
    }
}
