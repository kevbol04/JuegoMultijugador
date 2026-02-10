package com.kevin.multijugador.server

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.Socket

class ClientHandler(
    private val socket: Socket,
    private val recordsStore: RecordsStore
) {
    fun run() {
        val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
        val writer = PrintWriter(socket.getOutputStream(), true)

        println("Cliente conectado: ${socket.inetAddress.hostAddress}")

        try {
            val records = recordsStore.loadRawJson()
            writer.println("""{"type":"RECORDS_SYNC","payload":$records}""")

            while (true) {
                val line = reader.readLine() ?: break
                println("Recibido: $line")

                writer.println("""{"type":"OK","payload":{"message":"Recibido"}}""")
            }
        } catch (e: Exception) {
            println("⚠Error cliente: ${e.message}")
        } finally {
            try { socket.close() } catch (_: Exception) {}
            println("Cliente desconectado")
        }
    }
}