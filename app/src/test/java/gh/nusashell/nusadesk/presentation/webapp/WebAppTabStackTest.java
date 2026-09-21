package gh.nusashell.nusadesk.presentation.webapp;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The tab stack owns which WebView the surface renders, so its limits, root
 * protection, and deterministic selection policy are pure and asserted here
 * without a device.
 */
public class WebAppTabStackTest {

    @Test
    public void aNewStackStartsWithOnlyTheRootTabSelected() {
        WebAppTabStack stack = new WebAppTabStack();

        assertEquals(WebAppTabStack.ROOT_TAB_ID, stack.selectedTabId());
        assertEquals(1, stack.tabs().size());
        assertEquals(0, stack.childCount());
        assertFalse(stack.isFull());

        WebAppTabStack.Tab root = stack.tabs().get(0);
        assertEquals(WebAppTabStack.ROOT_TAB_ID, root.getId());
        assertTrue(root.isRoot());
    }

    @Test
    public void addAppendsAChildWithADeterministicIdAndSelectsIt() {
        WebAppTabStack stack = new WebAppTabStack();

        WebAppTabStack.Tab first = stack.addTab();
        WebAppTabStack.Tab second = stack.addTab();

        assertNotNull(first);
        assertNotNull(second);
        assertFalse(first.isRoot());
        assertEquals("tab-1", first.getId());
        assertEquals("tab-2", second.getId());
        assertEquals(second.getId(), stack.selectedTabId());
        assertEquals(2, stack.childCount());
    }

    @Test
    public void childIdsAreNeverReusedAfterAClose() {
        WebAppTabStack stack = new WebAppTabStack();
        String firstId = stack.addTab().getId();
        stack.addTab();
        stack.close(firstId);

        WebAppTabStack.Tab third = stack.addTab();

        assertNotNull(third);
        assertFalse(third.getId().equals(firstId));
        assertEquals("tab-3", third.getId());
    }

    @Test
    public void theStackRefusesAFifthChildTab() {
        WebAppTabStack stack = new WebAppTabStack();
        for (int i = 0; i < WebAppTabStack.MAX_CHILD_TABS; i++) {
            assertNotNull(stack.addTab());
        }

        assertTrue(stack.isFull());
        assertNull(stack.addTab());
        assertEquals(WebAppTabStack.MAX_CHILD_TABS, stack.childCount());
        assertEquals(1 + WebAppTabStack.MAX_CHILD_TABS, stack.tabs().size());
    }

    @Test
    public void aRefusedAddLeavesTheSelectionAlone() {
        WebAppTabStack stack = new WebAppTabStack();
        for (int i = 0; i < WebAppTabStack.MAX_CHILD_TABS; i++) {
            stack.addTab();
        }
        stack.select(WebAppTabStack.ROOT_TAB_ID);

        assertNull(stack.addTab());
        assertEquals(WebAppTabStack.ROOT_TAB_ID, stack.selectedTabId());
    }

    @Test
    public void selectMovesBetweenLiveTabs() {
        WebAppTabStack stack = new WebAppTabStack();
        String childId = stack.addTab().getId();

        assertTrue(stack.select(WebAppTabStack.ROOT_TAB_ID));
        assertEquals(WebAppTabStack.ROOT_TAB_ID, stack.selectedTabId());
        assertTrue(stack.select(childId));
        assertEquals(childId, stack.selectedTabId());
    }

    @Test
    public void selectingAnUnknownOrNullIdKeepsTheCurrentSelection() {
        WebAppTabStack stack = new WebAppTabStack();
        String childId = stack.addTab().getId();

        assertFalse(stack.select("tab-99"));
        assertFalse(stack.select(null));
        assertFalse(stack.select(""));
        assertEquals(childId, stack.selectedTabId());
    }

    @Test
    public void closingTheRootTabIsRefused() {
        WebAppTabStack stack = new WebAppTabStack();

        assertFalse(stack.close(WebAppTabStack.ROOT_TAB_ID));

        assertTrue(stack.contains(WebAppTabStack.ROOT_TAB_ID));
        assertEquals(WebAppTabStack.ROOT_TAB_ID, stack.selectedTabId());
        assertEquals(1, stack.tabs().size());
    }

    @Test
    public void closingAnUnknownOrNullIdIsRefused() {
        WebAppTabStack stack = new WebAppTabStack();
        stack.addTab();

        assertFalse(stack.close("tab-99"));
        assertFalse(stack.close(null));
        assertFalse(stack.close(""));
        assertEquals(1, stack.childCount());
    }

    @Test
    public void closingABackgroundChildKeepsTheCurrentSelection() {
        WebAppTabStack stack = new WebAppTabStack();
        String firstId = stack.addTab().getId();
        String secondId = stack.addTab().getId();

        assertTrue(stack.close(firstId));

        assertEquals(secondId, stack.selectedTabId());
        assertFalse(stack.contains(firstId));
        assertEquals(1, stack.childCount());
    }

    @Test
    public void closingTheOnlySelectedChildFallsBackToRoot() {
        WebAppTabStack stack = new WebAppTabStack();
        String childId = stack.addTab().getId();

        assertTrue(stack.close(childId));

        assertEquals(WebAppTabStack.ROOT_TAB_ID, stack.selectedTabId());
        assertEquals(0, stack.childCount());
    }

    @Test
    public void closingTheSelectedChildSelectsThePreviousSibling() {
        WebAppTabStack stack = new WebAppTabStack();
        String firstId = stack.addTab().getId();
        String secondId = stack.addTab().getId();
        String thirdId = stack.addTab().getId();

        assertTrue(stack.close(thirdId));
        assertEquals(secondId, stack.selectedTabId());

        assertTrue(stack.close(firstId));
        assertEquals(secondId, stack.selectedTabId());

        assertTrue(stack.close(secondId));
        assertEquals(WebAppTabStack.ROOT_TAB_ID, stack.selectedTabId());
    }

    @Test
    public void theTabListIsAnImmutableSnapshot() {
        WebAppTabStack stack = new WebAppTabStack();
        stack.addTab();

        List<WebAppTabStack.Tab> snapshot = stack.tabs();
        try {
            snapshot.clear();
            fail("tabs() must return an unmodifiable list");
        } catch (UnsupportedOperationException expected) {
            // expected
        }

        stack.addTab();
        assertEquals(2, snapshot.size());
        assertEquals(3, stack.tabs().size());
    }
}
