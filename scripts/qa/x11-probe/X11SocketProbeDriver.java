package gh.nusashell.nusadesk.infrastructure.display;

import android.app.Instrumentation;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Bundle;
import android.os.SystemClock;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/** Executes the production probe against Android's real Unix-socket implementation. */
public class X11SocketProbeDriver extends Instrumentation {
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        Bundle results = new Bundle();
        try {
            exchange(1, false);
            exchange(0, false);
            exchange(1, true);
            long backlogMillis = backlog();
            results.putString("result", "PASS: accepted setup, refused setup, silent peer timeout, full backlog");
            results.putLong("backlogProbeMillis", backlogMillis);
            finish(-1, results);
        } catch (Throwable error) {
            results.putString("failure", android.util.Log.getStackTraceString(error));
            finish(0, results);
        }
    }
    private void exchange(int status, boolean silent) throws Exception {
        String name = "nusadesk-x11-qa-" + android.os.Process.myPid() + "-" + status + "-" + silent;
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try (LocalServerSocket server = new LocalServerSocket(name)) {
            Thread peer = new Thread(() -> {
                try (LocalSocket client = server.accept()) {
                    byte[] hello = new byte[12];
                    client.setSoTimeout(2000);
                    new DataInputStream(client.getInputStream()).readFully(hello);
                    if (hello[0] != 'l' || hello[2] != 11) throw new AssertionError("wrong X11 setup request");
                    if (silent) { SystemClock.sleep(2200); }
                    else client.getOutputStream().write(new byte[]{(byte) status, 0, 11, 0, 0, 0, 0, 0});
                } catch (Throwable error) { failure.set(error); }
            }, "x11-probe-peer");
            peer.start();
            long start = SystemClock.elapsedRealtime();
            try (LocalSocket socket = new LocalSocket()) {
                X11SocketProbe.exchange(socket, new LocalSocketAddress(name));
                if (status != 1 || silent) throw new AssertionError("unready peer was accepted");
            } catch (IOException expected) {
                if (status == 1 && !silent) throw expected;
                if (!silent && !expected.getMessage().contains("refused")) throw expected;
                if (silent && SystemClock.elapsedRealtime() - start > 2000) throw new AssertionError("read was not bounded");
            }
            peer.join(3000);
            if (peer.isAlive()) throw new AssertionError("peer did not finish");
            if (failure.get() != null) throw new AssertionError(failure.get());
        }
    }
    private long backlog() throws Exception {
        String name = "nusadesk-x11-backlog-" + android.os.Process.myPid();
        List<LocalSocket> held = new ArrayList<>();
        try (LocalServerSocket server = new LocalServerSocket(name)) {
            LocalSocketAddress address = server.getLocalSocketAddress();
            for (int i = 0; i < 128; i++) {
                LocalSocket socket = new LocalSocket();
                held.add(socket);
                long start = SystemClock.elapsedRealtime();
                try { X11SocketProbe.connect(socket, address); }
                catch (IOException full) {
                    if (i == 0) throw new AssertionError("no connection reached the listener", full);
                    long elapsed = SystemClock.elapsedRealtime() - start;
                    if (elapsed > 1000) throw new AssertionError("backlog connect blocked");
                    return elapsed;
                }
            }
            throw new AssertionError("test did not fill the listener backlog");
        } finally {
            for (LocalSocket socket : held) socket.close();
        }
    }
}
