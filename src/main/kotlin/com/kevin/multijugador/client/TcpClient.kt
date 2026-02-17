package com.kevin.multijugador.client

import com.kevin.multijugador.protocol.JsonCodec
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.Socket

class TcpClient(
    private val host: String,
    private val port: Int
) {
    private lateinit var socket: Socket
    private lateinit var reader: BufferedReader
    private lateinit var writer: PrintWriter

    var recordsJson: String = """{"players":{}}"""
        private set

    fun connect() {
        socket = Socket(host, port)
        reader = BufferedReader(InputStreamReader(socket.getInputStream()))
        writer = PrintWriter(BufferedWriter(OutputStreamWriter(socket.getOutputStream())), true)
        println("Conectado a $host:$port")
    }

    fun send(type: String, payloadJson: String) {
        writer.println(JsonCodec.encode(type, payloadJson))
        writer.flush()
    }

    fun readBlockingLine(): String {
        return reader.readLine() ?: throw IllegalStateException("Servidor cerró la conexión")
    }

    fun readLoop(onLine: (String) -> Unit) {
        try {
            while (true) {
                val line = reader.readLine() ?: break
                onLine(line)
            }
        } catch (_: java.net.SocketException) {
        } catch (_: java.io.IOException) {
        } catch (e: Exception) {
        }
    }


    fun setRecordsJson(json: String) {
        recordsJson = json
    }

    fun close() {
        try { socket.close() } catch (_: Exception) {}
    }
}