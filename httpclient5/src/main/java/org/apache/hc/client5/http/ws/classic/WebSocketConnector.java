package org.apache.hc.client5.http.ws.classic;

import java.io.IOException;
import java.net.Socket;
import java.net.URI;

import org.apache.hc.core5.annotation.Internal;

@Internal
public interface WebSocketConnector {
    Socket connect(URI uri) throws IOException;
}
