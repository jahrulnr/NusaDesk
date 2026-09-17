package gh.nusashell.nusadesk.infrastructure.androidbridge;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CellInfoAccessPolicyTest {

    @Test
    public void fineGrantAllowsEveryApiLevel() {
        for (int apiLevel : new int[]{29, 30, 31, 33, 37}) {
            assertTrue("fine must allow api " + apiLevel,
                    CellInfoAccessPolicy.allows(LocationGrant.FINE, apiLevel));
        }
    }

    @Test
    public void coarseGrantAllowsOnlyApi31AndLater() {
        assertFalse(CellInfoAccessPolicy.allows(LocationGrant.COARSE_ONLY, 29));
        assertFalse(CellInfoAccessPolicy.allows(LocationGrant.COARSE_ONLY, 30));
        assertTrue(CellInfoAccessPolicy.allows(LocationGrant.COARSE_ONLY, 31));
        assertTrue(CellInfoAccessPolicy.allows(LocationGrant.COARSE_ONLY, 37));
    }

    @Test
    public void missingOrDeniedGrantsNeverAllowTheRead() {
        for (int apiLevel : new int[]{29, 31, 37}) {
            assertFalse(CellInfoAccessPolicy.allows(LocationGrant.REQUIRED, apiLevel));
            assertFalse(CellInfoAccessPolicy.allows(LocationGrant.DENIED, apiLevel));
        }
    }
}
