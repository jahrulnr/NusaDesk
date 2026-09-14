package gh.nusashell.nusadesk.infrastructure.sshserver;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

/**
 * Pure validation tests for {@link PtySize}. No I/O, no Android, no MINA.
 */
public class PtySizeTest {

    @Test
    public void defaultsAreEightyByTwentyFour() {
        PtySize size = PtySize.defaults();
        assertEquals(80, size.getCols());
        assertEquals(24, size.getRows());
        assertEquals(PtySize.DEFAULT_COLS, size.getCols());
        assertEquals(PtySize.DEFAULT_ROWS, size.getRows());
    }

    @Test
    public void preservesValidDimensions() {
        PtySize size = new PtySize(120, 40);
        assertEquals(120, size.getCols());
        assertEquals(40, size.getRows());
    }

    @Test
    public void rejectsZeroAndNegativeCols() {
        try {
            new PtySize(0, 24);
            fail("expected rejection of zero cols");
        } catch (IllegalArgumentException expected) {
            // expected
        }
        try {
            new PtySize(-1, 24);
            fail("expected rejection of negative cols");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    @Test
    public void rejectsZeroAndNegativeRows() {
        try {
            new PtySize(80, 0);
            fail("expected rejection of zero rows");
        } catch (IllegalArgumentException expected) {
            // expected
        }
        try {
            new PtySize(80, -5);
            fail("expected rejection of negative rows");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    @Test
    public void rejectsAbsurdDimensions() {
        try {
            new PtySize(5000, 24);
            fail("expected rejection of oversized cols");
        } catch (IllegalArgumentException expected) {
            // expected
        }
        try {
            new PtySize(80, 5000);
            fail("expected rejection of oversized rows");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    @Test
    public void equalsByDimensions() {
        assertEquals(new PtySize(100, 30), new PtySize(100, 30));
        assertEquals(new PtySize(100, 30).hashCode(), new PtySize(100, 30).hashCode());
    }
}
