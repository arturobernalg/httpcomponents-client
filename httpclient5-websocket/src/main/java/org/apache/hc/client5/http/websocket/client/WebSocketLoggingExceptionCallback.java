package org.apache.hc.client5.http.websocket.client;

import org.apache.hc.core5.function.Callback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class WebSocketLoggingExceptionCallback implements Callback<Exception> {

    /**
     * Singleton instance of LoggingExceptionCallback.
     */
    static final WebSocketLoggingExceptionCallback INSTANCE = new WebSocketLoggingExceptionCallback();

    private static final Logger LOG = LoggerFactory.getLogger("org.apache.hc.client5.http.websocket.client");

    private WebSocketLoggingExceptionCallback() {
    }

    @Override
    public void execute(final Exception ex) {
        LOG.error(ex.getMessage(), ex);
    }

}

