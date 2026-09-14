package gh.nusashell.nusadesk.presentation.widget;

import org.junit.Test;

import static org.junit.Assert.assertTrue;

/**
 * Pure tests for the launcher pattern's tile orientation.
 *
 * <p>The Truchet field is generated from a coordinate hash rather than a random
 * generator, so two properties matter and are asserted here without a device:
 * a tile must keep its orientation on every redraw (the pattern cannot shimmer
 * while the user scrolls), and both orientations must actually occur (otherwise
 * the field degenerates into a plain grid).</p>
 */
public class LinePatternDrawableTest {

    @Test
    public void orientationIsStableForTheSameCoordinate() {
        for (int row = -3; row < 40; row++) {
            for (int column = -3; column < 40; column++) {
                assertTrue(LinePatternDrawable.tileOrientation(column, row)
                        == LinePatternDrawable.tileOrientation(column, row));
            }
        }
    }

    @Test
    public void bothOrientationsOccurAcrossTheGrid() {
        int flipped = 0;
        int straight = 0;
        for (int row = 0; row < 16; row++) {
            for (int column = 0; column < 16; column++) {
                if (LinePatternDrawable.tileOrientation(column, row)) {
                    flipped++;
                } else {
                    straight++;
                }
            }
        }
        assertTrue("flipped tiles should appear", flipped > 16);
        assertTrue("straight tiles should appear", straight > 16);
        // A near-even split is what keeps the texture from reading as a grid.
        assertTrue("orientation mix should stay balanced",
                Math.abs(flipped - straight) < 64);
    }

    @Test
    public void orientationIsNotSimplyCheckerboard() {
        // A pure (column + row) parity would make the pattern a lattice; the hash
        // must diverge from it somewhere so the field looks woven, not ruled.
        boolean diverges = false;
        for (int row = 0; row < 8 && !diverges; row++) {
            for (int column = 0; column < 8; column++) {
                if (LinePatternDrawable.tileOrientation(column, row) != ((column + row) % 2 == 0)) {
                    diverges = true;
                    break;
                }
            }
        }
        assertTrue("hash orientation should differ from checkerboard parity", diverges);
    }
}
