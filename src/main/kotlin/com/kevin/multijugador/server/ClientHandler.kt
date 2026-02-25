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
            while (true) {
                val firstLine = reader.readLine() ?: return
                val firstEnv = JsonCodec.decode(firstLine)

                if (firstEnv == null || firstEnv.type != MessageType.LOGIN) {
                    conn.send(MessageType.LOGIN_ERROR, """{"message":"Debes enviar LOGIN primero"}""")
                    continue
                }

                val rawUsername = extractString(firstEnv.payloadJson, "username")?.trim().orEmpty()

                if (!isValidUsername(rawUsername)) {
                    conn.send(MessageType.LOGIN_ERROR, """{"message":"Usuario inválido (3-16, letras/números/_ )"}""")
                    continue
                }

                val username = rawUsername.lowercase()

                if (!ActiveUsers.tryAdd(username)) {
                    conn.send(MessageType.LOGIN_ERROR, """{"message":"Ese usuario ya está conectado"}""")
                    continue
                }

                conn.setUsername(username)
                conn.send(MessageType.LOGIN_OK, """{"username":"$rawUsername"}""")
                println("Usuarios activos: ${ActiveUsers.snapshot()}")
                break
            }

            val records = recordsStore.loadRawJson()
            conn.send(MessageType.RECORDS_SYNC, records)

            while (true) {
                val line = reader.readLine() ?: break
                val env = JsonCodec.decode(line) ?: continue

                when (env.type) {

                    MessageType.JOIN_QUEUE -> {
                        val boardSize = extractInt(env.payloadJson, "boardSize") ?: 3
                        val rounds = extractInt(env.payloadJson, "rounds") ?: 3
                        val timeLimit = extractInt(env.payloadJson, "timeLimit") ?: 30
                        val turbo = extractBoolean(env.payloadJson, "turbo") ?: false

                        val effectiveTimeLimit = if (turbo) 10 else timeLimit

                        val entry = MatchmakingQueue.QueueEntry(
                            client = conn,
                            config = MatchmakingQueue.GameConfig(
                                boardSize = boardSize,
                                rounds = rounds,
                                timeLimit = effectiveTimeLimit,
                                turbo = turbo
                            )
                        )

                        conn.send(MessageType.QUEUE_STATUS, """{"status":"WAITING"}""")

                        val (a, b) = queue.tryEnqueue(entry)
                        if (a != null && b != null) {
                            a.client.send(MessageType.QUEUE_STATUS, """{"status":"MATCHED"}""")
                            b.client.send(MessageType.QUEUE_STATUS, """{"status":"MATCHED"}""")
                            gameService.startPvpGame(a.client, b.client, a.config)
                        }
                    }

                    MessageType.START_PVE -> {
                        val diffStr = extractString(env.payloadJson, "difficulty") ?: "EASY"
                        val boardSize = extractInt(env.payloadJson, "boardSize") ?: 3
                        val rounds = extractInt(env.payloadJson, "rounds") ?: 3
                        val timeLimit = extractInt(env.payloadJson, "timeLimit") ?: 30
                        val turbo = extractBoolean(env.payloadJson, "turbo") ?: false

                        val effectiveTimeLimit = if (turbo) 10 else timeLimit

                        gameService.startPveGame(conn, diffStr, boardSize, rounds, effectiveTimeLimit, turbo)
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
            println("Error cliente: ${e.message}")
        } finally {
            conn.username?.let { ActiveUsers.remove(it) }
            println("Usuarios activos: ${ActiveUsers.snapshot()}")

            queue.removeIfWaiting(conn)
            conn.close()
            println("Cliente desconectado")
        }
    }

    private fun isValidUsername(u: String): Boolean {
        if (u.length !in 3..16) return false
        return u.all { it.isLetterOrDigit() || it == '_' }
    }

    private fun extractString(json: String, field: String): String? {
        val regex = """"$field"\s*:\s*"([^"]*)"""".toRegex()
        return regex.find(json)?.groupValues?.getOrNull(1)
    }

    private fun extractInt(json: String, field: String): Int? {
        val regex = """"$field"\s*:\s*(\d+)""".toRegex()
        return regex.find(json)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    private fun extractBoolean(json: String, field: String): Boolean? {
        val regex = """"$field"\s*:\s*(true|false)""".toRegex(RegexOption.IGNORE_CASE)
        return regex.find(json)?.groupValues?.getOrNull(1)?.lowercase()?.toBooleanStrictOrNull()
    }
}