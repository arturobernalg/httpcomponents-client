package org.apache.hc.client5.http.socket.impl;

final class WsOpcode {
    static final int CONT = 0x0, TEXT = 0x1, BINARY = 0x2, CLOSE = 0x8, PING = 0x9, PONG = 0xA;

    private WsOpcode() {
    }
}
