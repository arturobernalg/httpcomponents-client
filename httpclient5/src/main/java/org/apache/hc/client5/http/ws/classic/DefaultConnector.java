package org.apache.hc.client5.http.ws.classic;

import java.io.IOException;
import java.net.Socket;
import java.net.URI;

import javax.net.SocketFactory;
import javax.net.ssl.SSLSocketFactory;

import org.apache.hc.core5.annotation.Internal;

@Internal
final class DefaultConnector implements WebSocketConnector {

    private final SocketFactory plain;
    private final SSLSocketFactory tls;

    DefaultConnector() {
        this(null, null);
    }

    DefaultConnector(final SocketFactory plain, final SSLSocketFactory tls) {
        this.plain = plain != null ? plain : SocketFactory.getDefault();
        this.tls = tls != null ? tls : (SSLSocketFactory) SSLSocketFactory.getDefault();
    }

    @Override
    public Socket connect(final URI uri) throws IOException {
        final boolean secure = "wss".equalsIgnoreCase(uri.getScheme());
        final int port = uri.getPort() > 0 ? uri.getPort() : (secure ? 443 : 80);
        final Socket s = (secure ? tls : plain).createSocket(uri.getHost(), port);
        s.setTcpNoDelay(true);
        return s;
    }
}
