package gh.nusashell.nusadesk.infrastructure.runtimehost;

import java.io.InputStream;
import java.util.concurrent.TimeUnit;

/**
 * {@link ProcessHandle} over a JDK {@link java.lang.Process}.
 *
 * <p>Graceful termination maps to {@link Process#destroy()} and forced
 * termination to {@link Process#destroyForcibly()}, so the supervisor can stop
 * the guest gracefully first and then forcibly after a bounded grace period.</p>
 */
public final class JdkProcessHandle implements ProcessHandle {
    private final Process process;

    public JdkProcessHandle(Process process) {
        if (process == null) {
            throw new IllegalArgumentException("process must not be null");
        }
        this.process = process;
    }

    @Override
    public InputStream getStdout() {
        return process.getInputStream();
    }

    @Override
    public InputStream getStderr() {
        return process.getErrorStream();
    }

    @Override
    public boolean isAlive() {
        return process.isAlive();
    }

    @Override
    public void destroyGracefully() {
        process.destroy();
    }

    @Override
    public void destroyForcibly() {
        process.destroyForcibly();
    }

    @Override
    public boolean waitFor(long timeoutMillis) throws InterruptedException {
        return process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS);
    }

    @Override
    public int exitValue() {
        return process.exitValue();
    }
}
