package gh.nusashell.nusadesk.infrastructure.proot;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Test;

/**
 * The packaged execution bridge must be exactly the binary the build script
 * pins. {@code scripts/build-proot-arm64.sh} fails closed against those hashes
 * when it builds, but a swapped or stale {@code libproot.so} /
 * {@code libproot-loader.so} in {@code jniLibs/} would otherwise ship silently
 * (the release workflow packages those files as they are). This guard makes
 * that drift fail the JVM suite instead (ADR-0004, ADR-0008).
 */
public class ProotBridgePinTest {

    @Test
    public void packagedBridgeMatchesTheBuildScriptPins() throws Exception {
        String script = read(repoPath("scripts/build-proot-arm64.sh"));

        assertEquals(
                "jniLibs libproot.so must match EXPECTED_SHA256 in the build script",
                match(script, "EXPECTED_SHA256=\"([0-9a-f]{64})\""),
                sha256(repoPath("app/src/main/jniLibs/arm64-v8a/libproot.so")));
        assertEquals(
                "jniLibs libproot-loader.so must match EXPECTED_LOADER_SHA256 in the build script",
                match(script, "EXPECTED_LOADER_SHA256=\"([0-9a-f]{64})\""),
                sha256(repoPath("app/src/main/jniLibs/arm64-v8a/libproot-loader.so")));
    }

    private static String match(String text, String regex) {
        Matcher matcher = Pattern.compile(regex).matcher(text);
        assertTrue("no match for " + regex, matcher.find());
        return matcher.group(1);
    }

    private static String sha256(Path path) throws Exception {
        assertTrue("missing file: " + path, Files.isRegularFile(path));
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(Files.readAllBytes(path));
        StringBuilder out = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            out.append(String.format("%02x", b & 0xff));
        }
        return out.toString();
    }

    private static String read(Path path) throws Exception {
        assertTrue("missing file: " + path, Files.isRegularFile(path));
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    /**
     * Resolve a repository-relative path from either launch directory: unit
     * tests normally run with the module directory as the working directory,
     * but a repo-root launch must work too.
     */
    private static Path repoPath(String relative) {
        Path workingDir = Paths.get("").toAbsolutePath();
        Path direct = workingDir.resolve(relative);
        return Files.isRegularFile(direct)
                ? direct : workingDir.resolve("..").resolve(relative).normalize();
    }
}
