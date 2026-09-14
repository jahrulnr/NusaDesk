package gh.nusashell.nusadesk.infrastructure.runtime;

import gh.nusashell.nusadesk.application.runtime.RuntimeInstallationException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * Minimal ELF header check used to prove an extracted payload binary targets
 * the declared guest ABI before activation.
 */
final class ElfAbi {
    private static final byte[] ELF_MAGIC = { 0x7f, 'E', 'L', 'F' };
    private static final int ELFCLASS64 = 2;
    private static final int EM_AARCH64 = 0xB7;

    private ElfAbi() {
    }

    /**
     * Verifies {@code file} is a 64-bit little-endian AArch64 ELF object.
     * Executables ({@code ET_EXEC}), PIE/shared objects ({@code ET_DYN}), and
     * relocatables all satisfy this; the loader decides usability at runtime.
     */
    static void requireAarch64(Path file, String description)
            throws IOException, RuntimeInstallationException {
        byte[] header = new byte[20];
        int total = 0;
        try (InputStream input = Files.newInputStream(file)) {
            while (total < header.length) {
                int read = input.read(header, total, header.length - total);
                if (read < 0) {
                    break;
                }
                total += read;
            }
        }
        if (total < header.length
                || !Arrays.equals(Arrays.copyOf(header, 4), ELF_MAGIC)
                || (header[4] & 0xff) != ELFCLASS64
                || (header[5] & 0xff) != 1) {
            throw new RuntimeInstallationException(
                    description + " is not a 64-bit little-endian ELF binary");
        }
        int machine = (header[18] & 0xff) | ((header[19] & 0xff) << 8);
        if (machine != EM_AARCH64) {
            throw new RuntimeInstallationException(
                    description + " targets the wrong guest ABI");
        }
    }
}
