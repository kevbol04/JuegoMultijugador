package com.kevin.multijugador.client

import com.kevin.multijugador.protocol.JsonCodec
import com.kevin.multijugador.protocol.MessageType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicReference

object ClientMain {

    private enum class ClientState {
        MENU, QUEUE, IN_GAME
    }

    @JvmStatic
    fun main(args: Array<String>) = runBlocking {
        val cfg = ClientConfigLoader.load()
        val client = TcpClient(cfg.host, cfg.port)

        val mySymbol = AtomicReference<String?>(null)
        val lastState = AtomicReference<String?>(null)
        val clientState = AtomicReference(ClientState.MENU)

        try {
            client.connect()

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
                            println("\n🎮 Partida iniciada. Tu símbolo: $sym")
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

                            when (winner) {
                                "DRAW" -> println("\n🤝 Empate.")
                                "X", "O" -> println("\n🏆 Ganador: $winner")
                                else -> println("\nFin de ronda.")
                            }

                            mySymbol.set(null)
                            lastState.set(null)
                            clientState.set(ClientState.MENU)
                        }

                        MessageType.ERROR -> {
                            val msg = extractString(env.payloadJson, "message")
                            println("ERROR: $msg")
                        }
                    }
                }
            }

            while (true) {

                if (clientState.get() != ClientState.MENU) {
                    Thread.sleep(200)
                    continue
                }

                println()
                println("===== MENÚ PRINCIPAL =====")
                println("1. Nueva Partida PVP")
                println("2. Nueva Partida PVE (pendiente)")
                println("3. Ver Records")
                println("4. Configuración (pendiente)")
                println("5. Salir")
                print("Elige opción: ")

                when (readLine()?.trim()) {
                    "1" -> {
                        client.send(MessageType.JOIN_QUEUE, """{}""")
                        clientState.set(ClientState.QUEUE)
                    }
                    "3" -> {
                        println("\n--- RECORDS ---")
                        println(client.recordsJson)
                    }
                    "5" -> {
                        println("Saliendo...")
                        client.close()
                        return@runBlocking
                    }
                    else -> println("Opción no válida.")
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

            if (r != null && c != null && r in 0..2 && c in 0..2) return r to c
            println("Entrada inválida.")
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

        val cells = values.take(9).map { it } + List((9 - values.take(9).size).coerceAtLeast(0)) { "" }

        return listOf(
            listOf(cells[0], cells[1], cells[2]),
            listOf(cells[3], cells[4], cells[5]),
            listOf(cells[6], cells[7], cells[8])
        )
    }
}