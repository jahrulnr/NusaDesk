package gh.nusashell.nusadesk.infrastructure.proot;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Shared access to the bundled guest docs under
 * {@code app/src/main/assets/guest-docs/} for unit tests: a
 * {@link GuestAwarenessReadmeWriter.GuestDocSource} backed by the source
 * tree, plus helpers to read one doc with the version token substituted.
 */
final class GuestDocsFixture {

    /** The bundled guest-docs tree as shipped in main assets. */
    static final Path ASSET_DIR =
            Paths.get("src/main/assets/" + GuestAwarenessReadmeWriter.GUEST_DOCS_ASSET_ROOT);

    /** A doc source reading straight from the shipped asset files. */
    static final GuestAwarenessReadmeWriter.GuestDocSource SOURCE =
            GuestAwarenessReadmeWriter.docsFrom(ASSET_DIR);

    private GuestDocsFixture() {
    }

    /** One bundled doc's raw asset text (tokens still present). */
    static String raw(String assetPath) throws IOException {
        return new String(SOURCE.read(assetPath), StandardCharsets.UTF_8);
    }

    /** One bundled doc with {@code %APP_VERSION%} substituted. */
    static String doc(String assetPath, String version) throws IOException {
        return new String(
                GuestAwarenessReadmeWriter.loadDoc(SOURCE, assetPath, version),
                StandardCharsets.UTF_8);
    }

    /** The AGENTS.md body the agent-seed writer installs. */
    static String agentsMd() throws IOException {
        return raw("agents/AGENTS.md");
    }

    /** The SKILL.md body the agent-seed writer installs (name substituted). */
    static String skill() throws IOException {
        return raw("agents/skills/" + GuestAgentSeedWriter.SKILL_NAME + "/SKILL.md")
                .replace("{{SKILL_NAME}}", GuestAgentSeedWriter.SKILL_NAME);
    }
}
