package gh.nusashell.nusadesk.infrastructure.display;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;

import java.io.DataInputStream;
import java.io.IOException;

/** Android Unix-socket readiness: a successful connect plus an accepted X11 setup. */
final class X11SocketProbe {
    private X11SocketProbe() { }

    static void connect(LocalSocket socket, LocalSocketAddress address) throws IOException {
        // The timeout overload is unimplemented on Android. Materialize the
        // fd first, then set SO_SNDTIMEO (setSoTimeout sets both send/receive
        // timeouts in Android's LocalSocketImpl) before the supported connect.
        // Unix connect also observes the send timeout when its backlog is full.
        socket.getInputStream();
        socket.setSoTimeout(500);
        socket.connect(address);
    }

    static void exchange(LocalSocket socket, LocalSocketAddress address) throws IOException {
        connect(socket, address);
        socket.setSoTimeout(1000);
        socket.getOutputStream().write(new byte[]{'l', 0, 11, 0, 0, 0, 0, 0, 0, 0, 0, 0});
        byte[] setup = new byte[8];
        new DataInputStream(socket.getInputStream()).readFully(setup);
        if (setup[0] != 1) throw new IOException("X11 setup was refused");
    }
}
