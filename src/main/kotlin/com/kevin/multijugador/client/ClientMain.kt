package com.kevin.multijugador.client

import com.kevin.multijugador.protocol.JsonCodec
import com.kevin.multijugador.protocol.MessageType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicReference

object ClientMain {

    private enum class ClientState { MENU, QUEUE, IN_GAME }

    @JvmStatic
    fun main(args: Array<String>) = runBlocking {
        val cfg = ClientConfigLoader.load()
        val client = TcpClient(cfg.host, cfg.port)

        val mySymbol = AtomicReference<String?>(null)
        val lastState = AtomicReference<String?>(null)
        val clientState = AtomicReference(ClientState.MENU)
        val usernameRef = AtomicReference<String?>(null)

        try {
            client.connect()

            var username: String

            while (true) {
                println("===== LOGIN =====")
                print("Introduce tu nombre de usuario: ")
                username = readLine()?.trim().orEmpty()

                client.send(MessageType.LOGIN, """{"username":"$username"}""")

                val line = client.readBlockingLine()
                val env = JsonCodec.decode(line) ?: continue

                when (env.type) {
                    MessageType.LOGIN_OK -> {
                        println("Sesión iniciada como $username")
                        usernameRef.set(username.lowercase())
                        break
                    }
                    MessageType.LOGIN_ERROR -> {
                        val msg = extractString(env.payloadJson, "message") ?: "Error"
                        println(" $msg")
                        println("Inténtalo de nuevo.\n")
                    }
                }
            }

            while (true) {
                val line = client.readBlockingLine()
                val env = JsonCodec.decode(line) ?: continue
                if (env.type == MessageType.RECORDS_SYNC) {
                    client.setRecordsJson(env.payloadJson)
                    println("Records sincronizados\n")
                    break
                }
            }

            launch(Dispatchers.IO) {
                client.readLoop { line ->
                    val env = JsonCodec.decode(line) ?: return@readLoop

                    when (env.type) {

                        MessageType.QUEUE_STATUS -> {
                            if (env.payloadJson.contains("WAITING")) {
                                println("\nEsperando rival...")
                                clientState.set(ClientState.QUEUE)
                            }
                            if (env.payloadJson.contains("MATCHED")) {
                                println("\nRival encontrado.")
                            }
                        }

                        MessageType.GAME_START -> {
                            val sym = extractString(env.payloadJson, "yourSymbol")
                            mySymbol.set(sym)
                            clientState.set(ClientState.IN_GAME)
                            println("\nPartida iniciada. Tu símbolo: $sym")
                        }

                        MessageType.GAME_STATE -> {
                            lastState.set(env.payloadJson)
                            printGameState(env.payloadJson)

                            val next = extractString(env.payloadJson, "nextPlayer")
                            val mine = mySymbol.get()
                            if (mine != null && next == mine) {
                                val (r, c) = askMove()
                                client.send(MessageType.MAKE_MOVE, """{"row":$r,"col":$c}""")
                            }
                        }

                        MessageType.ROUND_END -> {
                            val winner = extractString(env.payloadJson, "winner")
                            val winnerUser = extractString(env.payloadJson, "winnerUser")
                            val loserUser = extractString(env.payloadJson, "loserUser")

                            val mineUser = usernameRef.get()

                            when (winner) {
                                "DRAW" -> println("\n🤝 Empate.")
                                "X", "O" -> {
                                    if (winnerUser != null) {
                                        println("\n🏆 Ganador: $winner ($winnerUser)")
                                    } else {
                                        println("\n🏆 Ganador: $winner")
                                    }

                                    if (mineUser != null && winnerUser != null && loserUser != null) {
                                        if (mineUser == winnerUser.lowercase()) {
                                            println("Has ganado contra $loserUser")
                                        } else if (mineUser == loserUser.lowercase()) {
                                            println("Has perdido contra $winnerUser")
                                        }
                                    }
                                }
                                else -> println("\nFin de ronda.")
                            }

                            mySymbol.set(null)
                            lastState.set(null)
                            clientState.set(ClientState.MENU)
                        }

                        MessageType.ERROR -> {
                            val msg = extractString(env.payloadJson, "message") ?: "Error desconocido"
                            println("\nERROR: $msg")

                            val mine = mySymbol.get()
                            val stateJson = lastState.get()
                            val next = if (stateJson != null) extractString(stateJson, "nextPlayer") else null

                            if (clientState.get() == ClientState.IN_GAME && mine != null && next == mine) {
                                println("Repite la tirada.")
                                val (r, c) = askMove()
                                client.send(MessageType.MAKE_MOVE, """{"row":$r,"col":$c}""")
                            }
                        }

                        MessageType.RECORDS_SYNC -> {
                            client.setRecordsJson(env.payloadJson)
                        }
                    }
                }
            }

            while (true) {
                if (clientState.get() != ClientState.MENU) {
                    Thread.sleep(200)
                    continue
                }

                println("===== MENÚ PRINCIPAL =====")
                println("1. Nueva Partida PVP")
                println("2. Nueva Partida PVE")
                println("3. Ver Records")
                println("4. Configuración (pendiente)")
                println("5. Salir")
                print("Elige opción: ")

                when (readLine()?.trim()) {
                    "1" -> {
                        client.send(MessageType.JOIN_QUEUE, """{}""")
                        clientState.set(ClientState.QUEUE)
                    }
                    "2" -> {
                        val diff = askDifficulty()
                        client.send(MessageType.START_PVE, """{"difficulty":"$diff"}""")
                        clientState.set(ClientState.QUEUE)
                    }
                    "3" -> {
                        println("\n===== RECORDS =====")
                        printFormattedRecords(client.recordsJson)
                        println()
                    }

                    "5" -> {
                        println("Saliendo...")
                        mySymbol.set(null)
                        lastState.set(null)
                        clientState.set(ClientState.MENU)
                        client.close()
                        return@runBlocking
                    }
                    else -> println("Opción no válida.\n")
                }
            }

        } catch (e: Exception) {
            println("Error cliente: ${e.message}")
        } finally {
            client.close()
        }
    }

    private fun askMove(): Pair<Int, Int> {
        while (true) {
            print("Fila (0-2): ")
            val r = readLine()?.trim()?.toIntOrNull()
            print("Col  (0-2): ")
            val c = readLine()?.trim()?.toIntOrNull()

            if (r == null || c == null) {
                println("Debes escribir números.")
                continue
            }
            if (r !in 0..2 || c !in 0..2) {
                println("Fuera de rango. Debe ser entre 0 y 2.")
                continue
            }
            return r to c
        }
    }

    private fun printGameState(payload: String) {
        val board = extractBoard(payload)
        val next = extractString(payload, "nextPlayer")

        println()
        println("   0   1   2")
        for (r in 0..2) {
            val a = cell(board, r, 0)
            val b = cell(board, r, 1)
            val c = cell(board, r, 2)
            println("$r  $a | $b | $c")
            if (r != 2) println("  ---+---+---")
        }
        println("Turno: $next")
    }

    private fun printFormattedRecords(json: String) {

        val playersSection = """"players"\s*:\s*\{(.*)\}""".toRegex()
            .find(json)?.groupValues?.getOrNull(1)
            ?: run {
                println("No hay estadísticas aún.")
                return
            }

        val playerRegex = """"([^"]+)"\s*:\s*\{([^}]*)\}""".toRegex()

        val players = playerRegex.findAll(playersSection).toList()

        if (players.isEmpty()) {
            println("No hay estadísticas aún.")
            return
        }

        for (match in players) {
            val username = match.groupValues[1]
            val stats = match.groupValues[2]

            val wins = extractStat(stats, "wins")
            val losses = extractStat(stats, "losses")
            val draws = extractStat(stats, "draws")
            val streak = extractStat(stats, "streak")
            val bestStreak = extractStat(stats, "bestStreak")

            println("Usuario: $username")
            println("   🏆 Victorias: $wins")
            println("   ❌ Derrotas: $losses")
            println("   🤝 Empates: $draws")
            println("   🔥 Racha actual: $streak")
            println("   ⭐ Mejor racha: $bestStreak")
            println("---------------------------------")
        }
    }

    private fun extractStat(stats: String, field: String): Int {
        val regex = """"$field"\s*:\s*(\d+)""".toRegex()
        return regex.find(stats)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
    }


    private fun cell(board: List<List<String>>, r: Int, c: Int): String {
        val v = board.getOrNull(r)?.getOrNull(c) ?: ""
        return if (v.isBlank()) " " else v
    }

    private fun extractString(json: String, field: String): String? {
        val regex = """"$field"\s*:\s*"([^"]*)"""".toRegex()
        return regex.find(json)?.groupValues?.getOrNull(1)
    }

    private fun extractBoard(payload: String): List<List<String>> {
        val boardStart = payload.indexOf("\"board\":")
        if (boardStart == -1) return List(3) { List(3) { "" } }

        val firstBracket = payload.indexOf('[', boardStart)
        val nextPlayerIdx = payload.indexOf("\"nextPlayer\"", boardStart).let { if (it == -1) payload.length else it }
        val boardChunk = payload.substring(firstBracket, nextPlayerIdx)

        val values = """"([^"]*)"""".toRegex()
            .findAll(boardChunk)
            .map { it.groupValues[1].trim() }
            .toList()

        val cells = values.take(9) + List((9 - values.take(9).size).coerceAtLeast(0)) { "" }

        return listOf(
            listOf(cells[0], cells[1], cells[2]),
            listOf(cells[3], cells[4], cells[5]),
            listOf(cells[6], cells[7], cells[8])
        )
    }

    private fun askDifficulty(): String {
        while (true) {
            println("\nDificultad IA:")
            println("1. EASY")
            println("2. MEDIUM")
            println("3. HARD")
            print("Elige: ")
            return when (readLine()?.trim()) {
                "1" -> "Facil"
                "2" -> "MEDIUM"
                "3" -> "HARD"
                else -> {
                    println("Opción no válida.")
                    continue
                }
            }
        }
    }
}