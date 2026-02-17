package com.kevin.multijugador.server

import com.kevin.multijugador.protocol.JsonCodec
import com.kevin.multijugador.protocol.MessageType
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.Socket

class ClientHandler(
    private val socket: Socket,
    private val recordsStore: RecordsStore,
    private val queue: MatchmakingQueue,
    private val gameService: ServerGameService
) {
    fun run() {
        val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
        val writer = PrintWriter(BufferedWriter(OutputStreamWriter(socket.getOutputStream())), true)
        val conn = ClientConnection(socket, writer)

        println("Cliente conectado: ${socket.inetAddress.hostAddress}")

        try {
            val records = recordsStore.loadRawJson()
            conn.send(MessageType.RECORDS_SYNC, records)

            while (true) {
                val line = reader.readLine() ?: break

                val env = JsonCodec.decode(line) ?: continue
                if (env == null) continue

                when (env.type) {
                    MessageType.JOIN_QUEUE -> {
                        conn.send(MessageType.QUEUE_STATUS, """{"status":"WAITING"}""")

                        val (a, b) = queue.tryEnqueue(conn)
                        if (a != null && b != null) {
                            a.send(MessageType.QUEUE_STATUS, """{"status":"MATCHED"}""")
                            b.send(MessageType.QUEUE_STATUS, """{"status":"MATCHED"}""")
                            gameService.startPvpGame(a, b)
                        }
                    }

                    MessageType.MAKE_MOVE -> {
                        val row = extractInt(env.payloadJson, "row")
                        val col = extractInt(env.payloadJson, "col")
                        if (row == null || col == null) {
                            conn.send(MessageType.ERROR, """{"message":"Formato MAKE_MOVE inválido"}""")
                        } else {
                            gameService.handleMove(conn, row, col)
                        }
                    }

                    else -> conn.send(MessageType.ERROR, """{"message":"Tipo no soportado: ${env.type}"}""")
                }
            }
        } catch (e: Exception) {
            println("Error cliente (stacktrace):")
            e.printStackTrace()
        } finally {
            queue.removeIfWaiting(conn)
            conn.close()
            println("Cliente desconectado")
        }
    }

    private fun extractInt(json: String, field: String): Int? {
        val key = """"$field":"""
        val idx = json.indexOf(key)
        if (idx == -1) return null
        val start = idx + key.length
        val end = json.indexOfAny(charArrayOf(',', '}', ' '), start).let { if (it == -1) json.length else it }
        return json.substring(start, end).trim().toIntOrNull()
    }
}