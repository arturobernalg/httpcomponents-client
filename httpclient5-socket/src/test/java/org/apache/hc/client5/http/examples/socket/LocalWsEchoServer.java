package org.apache.hc.client5.http.examples.socket;

import java.nio.ByteBuffer;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.WebSocketAdapter;
import org.eclipse.jetty.websocket.servlet.WebSocketServlet;
import org.eclipse.jetty.websocket.servlet.WebSocketServletFactory;

public final class LocalWsEchoServer {
    public static void main(String[] args) throws Exception {
        final Server server = new Server(8080);
        final ServletContextHandler ctx = new ServletContextHandler(ServletContextHandler.SESSIONS);
        ctx.setContextPath("/");
        server.setHandler(ctx);
        ctx.addServlet(new ServletHolder(new EchoServlet()), "/echo");
        server.start();
        System.out.println("[WS-Server] up at ws://localhost:8080/echo");
        server.join();
    }

    public static final class EchoServlet extends WebSocketServlet {
        @Override
        public void configure(WebSocketServletFactory factory) {
            factory.getPolicy().setIdleTimeout(30000);
            factory.setCreator((req, resp) -> new EchoSocket());
        }
    }

    public static final class EchoSocket extends WebSocketAdapter {
        @Override
        public void onWebSocketText(String msg) {
            final Session s = getSession();
            if (s != null && s.isOpen()) {
                s.getRemote().sendString(msg, null);
            }
        }

        @Override
        public void onWebSocketBinary(byte[] payload, int off, int len) {
            final Session s = getSession();
            if (s != null && s.isOpen()) {
                s.getRemote().sendBytes(ByteBuffer.wrap(payload, off, len), null);
            }
        }
    }
}
