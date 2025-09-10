package org.apache.hc.client5.http.socket.impl;


final class WsProtocolException extends RuntimeException {
    final int closeCode;


    WsProtocolException(final int closeCode, final String message) {
        super(message);
        this.closeCode = closeCode;
    }
}