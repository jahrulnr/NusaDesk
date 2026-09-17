package gh.nusashell.nusadesk.infrastructure.androidbridge;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Bounded queue and latest-value backpressure of the location stream: the
 * queue never grows past capacity, polling is bounded, the latest reading is
 * always available, and a session restart resets the buffer.
 */
public class LocationStreamBufferTest {

    private static LocationSnapshot reading(double latitude, double longitude) {
        return LocationSnapshot.reading(latitude, longitude, 5.0, "test", 42L,
                null, null, null);
    }

    @Test
    public void offersAndPollsEventsInOrder() {
        LocationStreamBuffer buffer = new LocationStreamBuffer(4);
        LocationSnapshot first = reading(1.0, 2.0);
        buffer.offer(LocationStreamEvent.reading(first));
        buffer.offer(LocationStreamEvent.timeout());

        LocationStreamEvent firstEvent = buffer.poll(0);
        assertEquals(LocationStreamEvent.State.READING, firstEvent.getState());
        assertSame(first, firstEvent.getSnapshot());
        assertEquals(LocationStreamEvent.State.TIMEOUT, buffer.poll(0).getState());
        assertNull(buffer.poll(0));
    }

    @Test
    public void dropsOldestEventAtCapacity() {
        LocationStreamBuffer buffer = new LocationStreamBuffer(2);
        LocationSnapshot first = reading(1.0, 2.0);
        LocationSnapshot second = reading(3.0, 4.0);
        LocationSnapshot third = reading(5.0, 6.0);
        buffer.offer(LocationStreamEvent.reading(first));
        buffer.offer(LocationStreamEvent.reading(second));
        buffer.offer(LocationStreamEvent.reading(third));

        assertEquals(2, buffer.size());
        assertSame(second, buffer.poll(0).getSnapshot());
        assertSame(third, buffer.poll(0).getSnapshot());
        assertNull(buffer.poll(0));
    }

    @Test
    public void pollWithZeroTimeoutIsNonBlocking() {
        LocationStreamBuffer buffer = new LocationStreamBuffer(4);
        long start = System.nanoTime();
        assertNull(buffer.poll(0));
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000L;
        assertTrue("zero-timeout poll must return immediately", elapsedMillis < 1_000L);
    }

    @Test
    public void pollTimesOutWhenNoEventArrives() {
        LocationStreamBuffer buffer = new LocationStreamBuffer(4);
        long start = System.nanoTime();
        assertNull(buffer.poll(100L));
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000L;
        assertTrue("poll must respect its bounded window",
                elapsedMillis >= 50L && elapsedMillis <= 1_000L);
    }

    @Test
    public void pollWakesWhenAnEventIsOffered() throws Exception {
        LocationStreamBuffer buffer = new LocationStreamBuffer(4);
        AtomicReference<LocationStreamEvent> result = new AtomicReference<>();
        Thread poller = new Thread(() -> result.set(buffer.poll(10_000L)),
                "buffer-poll-test");
        poller.start();
        Thread.sleep(100L);
        buffer.offer(LocationStreamEvent.stopped());

        poller.join(5_000L);
        assertFalse("poll must wake on offer", poller.isAlive());
        assertEquals(LocationStreamEvent.State.STOPPED, result.get().getState());
    }

    @Test
    public void interruptDuringPollReturnsNullAndRestoresTheInterruptFlag() throws Exception {
        LocationStreamBuffer buffer = new LocationStreamBuffer(4);
        AtomicReference<LocationStreamEvent> result = new AtomicReference<>();
        Thread poller = new Thread(() -> result.set(buffer.poll(60_000L)),
                "buffer-poll-test");
        poller.start();
        Thread.sleep(100L);
        poller.interrupt();

        poller.join(5_000L);
        assertFalse("interrupted poll must exit", poller.isAlive());
        assertNull(result.get());
        assertTrue("interrupt flag must be restored", poller.isInterrupted());
    }

    @Test
    public void latestReadingTracksOnlyValidReadings() {
        LocationStreamBuffer buffer = new LocationStreamBuffer(4);
        assertNull(buffer.latestReading());

        LocationSnapshot first = reading(1.0, 2.0);
        buffer.offer(LocationStreamEvent.reading(first));
        assertSame(first, buffer.latestReading());

        // Failure events never replace the latest reading.
        buffer.offer(LocationStreamEvent.error());
        buffer.offer(LocationStreamEvent.timeout());
        assertSame(first, buffer.latestReading());

        LocationSnapshot second = reading(3.0, 4.0);
        buffer.offer(LocationStreamEvent.reading(second));
        assertSame(second, buffer.latestReading());
    }

    @Test
    public void clearResetsQueueAndLatestReading() {
        LocationStreamBuffer buffer = new LocationStreamBuffer(4);
        buffer.offer(LocationStreamEvent.reading(reading(1.0, 2.0)));
        buffer.offer(LocationStreamEvent.timeout());

        buffer.clear();

        assertEquals(0, buffer.size());
        assertNull(buffer.latestReading());
        assertNull(buffer.poll(0));
    }

    @Test
    public void rejectsInvalidInputs() {
        try {
            new LocationStreamBuffer(0);
            fail("non-positive capacity must be rejected");
        } catch (IllegalArgumentException expected) {
            // Boundary validation.
        }
        LocationStreamBuffer buffer = new LocationStreamBuffer(4);
        try {
            buffer.offer(null);
            fail("null events must be rejected");
        } catch (IllegalArgumentException expected) {
            // Boundary validation.
        }
        try {
            buffer.poll(-1L);
            fail("negative poll timeouts must be rejected");
        } catch (IllegalArgumentException expected) {
            // Boundary validation.
        }
    }
}
