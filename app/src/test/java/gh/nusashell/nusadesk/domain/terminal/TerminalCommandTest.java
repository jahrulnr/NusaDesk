package gh.nusashell.nusadesk.domain.terminal;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.fail;

public class TerminalCommandTest {

    @Test
    public void trimsSurroundingWhitespace() {
        assertEquals("ls -la", TerminalCommand.of("  ls -la  ").value());
    }

    @Test
    public void acceptsShellSyntaxTheGuestShellOwns() {
        // Quotes, pipes, redirects, expansions and unicode are the guest shell's
        // semantics; the host only guarantees a single printable line.
        assertEquals("docker exec -it codex bash",
                TerminalCommand.of("docker exec -it codex bash").value());
        assertEquals("echo $HOME | grep x",
                TerminalCommand.of("echo $HOME | grep x").value());
        assertEquals("sh -c 'echo \"hi\" && exit' > /tmp/out 2>&1",
                TerminalCommand.of("sh -c 'echo \"hi\" && exit' > /tmp/out 2>&1").value());
        assertEquals("echo caf\u00e9 \u2713",
                TerminalCommand.of("echo caf\u00e9 \u2713").value());
    }

    @Test
    public void rejectsNullBlankAndWhitespaceOnlyCommands() {
        assertRejected(null);
        assertRejected("");
        assertRejected("   ");
        assertRejected(" \t\n ");
    }

    @Test
    public void rejectsCommandsLongerThanTheMaximum() {
        StringBuilder tooLong = new StringBuilder();
        for (int i = 0; i < TerminalCommand.MAX_LENGTH + 1; i++) {
            tooLong.append('a');
        }
        assertRejected(tooLong.toString());
        assertEquals(TerminalCommand.MAX_LENGTH,
                TerminalCommand.of(tooLong.substring(0, TerminalCommand.MAX_LENGTH))
                        .value().length());
    }

    @Test
    public void rejectsControlCharactersSoARecordStaysOneLine() {
        assertRejected("echo hello\nrm -rf /");
        assertRejected("echo hello\rworld");
        assertRejected("echo\t-it");
        assertRejected("echo a\0b");
        assertRejected("echo\u001fb");
        assertRejected("echo\u007fb");
    }

    @Test
    public void acceptsPrintableBoundaryCharacters() {
        assertEquals("echo  spaced", TerminalCommand.of("echo  spaced").value());
        assertEquals("printf '~!@#%^&*()'",
                TerminalCommand.of("printf '~!@#%^&*()'").value());
    }

    @Test
    public void equalityIsByValue() {
        assertEquals(TerminalCommand.of("ls"), TerminalCommand.of(" ls "));
        assertEquals(TerminalCommand.of("ls").hashCode(), TerminalCommand.of("ls").hashCode());
        assertNotEquals(TerminalCommand.of("ls"), TerminalCommand.of("pwd"));
    }

    @Test
    public void toStringIsTheRawValue() {
        assertEquals("docker exec -it codex bash",
                TerminalCommand.of("docker exec -it codex bash").toString());
    }

    private static void assertRejected(String raw) {
        try {
            TerminalCommand.of(raw);
            fail("expected rejection of: " + raw);
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }
}
