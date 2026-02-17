package com.kevin.multijugador.server

import com.kevin.multijugador.protocol.JsonCodec
import java.io.PrintWriter
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

class ClientConnection(
    val socket: Socket,
    private val writer: PrintWriter
) {
    private val closed = AtomicBoolean(false)

    fun send(type: String, payloadJson: String) {
        if (closed.get()) return
        writer.println(JsonCodec.encode(type, payloadJson))
        writer.flush()
    }

    fun close() {
        if (closed.compareAndSet(false, true)) {
            try { socket.close() } catch (_: Exception) {}
        }
    }
}