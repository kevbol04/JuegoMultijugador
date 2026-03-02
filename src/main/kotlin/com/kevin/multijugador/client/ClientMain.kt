package com.kevin.multijugador.client

import com.kevin.multijugador.protocol.JsonCodec
import com.kevin.multijugador.protocol.MessageType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

object ClientMain {

    @JvmStatic
    fun main(args: Array<String>) = runBlocking {
        val cfg = ClientConfigLoader.load()
        val client = TcpClient(cfg.host, cfg.port)

        val session = ClientSession()
        val input = ConsoleInput()
        val menus = ClientMenus(session, input)
        val handler = ClientMessageHandler(session, input, client)

        try {
            client.connect()
            input.start(this)

            var username: String
            while (true) {
                println("===== LOGIN =====")
                print("Introduce tu nombre de usuario: ")
                username = input.takeNonBlank(session)

                client.send(MessageType.LOGIN, """{"username":"$username"}""")

                val line = client.readBlockingLine()
                val env = JsonCodec.decode(line) ?: continue

                when (env.type) {
                    MessageType.LOGIN_OK -> {
                        println("Sesión iniciada como $username")
                        session.usernameRef.set(username.lowercase())
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
                    handler.handle(env.type, env.payloadJson)
                }
            }

            while (true) {

                if (session.clientState.get() != ClientSession.ClientState.MENU) {
                    Thread.sleep(200)
                    continue
                }

                menus.printMainMenu()

                when (menus.readOption()) {

                    "1" -> {
                        val c = session.configRef.get()
                        client.send(
                            MessageType.JOIN_QUEUE,
                            """{"boardSize":${c.boardSize},"rounds":${c.rounds},"timeLimit":${c.timeLimit},"turbo":${c.turbo}}"""
                        )
                        session.clientState.set(ClientSession.ClientState.QUEUE)
                    }

                    "2" -> {
                        val c = session.configRef.get()
                        client.send(
                            MessageType.START_PVE,
                            """{"boardSize":${c.boardSize},"rounds":${c.rounds},"difficulty":"${c.difficulty}","timeLimit":${c.timeLimit},"turbo":${c.turbo}}"""
                        )
                        session.clientState.set(ClientSession.ClientState.QUEUE)
                    }

                    "3" -> {
                        println("\n===== RECORDS =====")
                        printFormattedRecords(client.recordsJson)
                        println()
                    }

                    "4" -> menus.configMenu()

                    "5" -> {
                        println("Saliendo...")
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

    private fun extractString(json: String, field: String): String? {
        val regex = """"$field"\s*:\s*"([^"]*)"""".toRegex()
        return regex.find(json)?.groupValues?.getOrNull(1)
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

            fun statInt(field: String): Int =
                """"$field"\s*:\s*(\d+)""".toRegex()
                    .find(stats)
                    ?.groupValues?.getOrNull(1)
                    ?.toIntOrNull() ?: 0

            fun statLong(field: String): Long =
                """"$field"\s*:\s*(\d+)""".toRegex()
                    .find(stats)
                    ?.groupValues?.getOrNull(1)
                    ?.toLongOrNull() ?: 0L

            val pvpWins = statInt("pvpWins")
            val pvpLosses = statInt("pvpLosses")
            val pvpDraws = statInt("pvpDraws")

            val pveWins = statInt("pveWins")
            val pveLosses = statInt("pveLosses")
            val pveDraws = statInt("pveDraws")

            val pvpBestStreak = statInt("pvpBestStreak")
            val pveBestStreak = statInt("pveBestStreak")

            val pvpWins3 = statInt("pvpWins3")
            val pvpWins4 = statInt("pvpWins4")
            val pvpWins5 = statInt("pvpWins5")

            val pveWins3 = statInt("pveWins3")
            val pveWins4 = statInt("pveWins4")
            val pveWins5 = statInt("pveWins5")

            val easyPlayed = statInt("pveEasyPlayed")
            val easyWins = statInt("pveEasyWins")
            val medPlayed = statInt("pveMediumPlayed")
            val medWins = statInt("pveMediumWins")
            val hardPlayed = statInt("pveHardPlayed")
            val hardWins = statInt("pveHardWins")

            val totalMoveTimeMs = statLong("totalMoveTimeMs")
            val totalMoves = statInt("totalMoves")
            val avgSec = if (totalMoves > 0) (totalMoveTimeMs.toDouble() / totalMoves.toDouble()) / 1000.0 else 0.0

            var bestR = 0
            var bestC = 0
            var bestCount = 0
            for (r in 0..4) {
                for (c in 0..4) {
                    val key = "m${r}${c}"
                    val count = statInt(key)
                    if (count > bestCount) {
                        bestCount = count
                        bestR = r
                        bestC = c
                    }
                }
            }

            fun pct(w: Int, p: Int): String {
                if (p <= 0) return "0%"
                val v = (w.toDouble() * 100.0) / p.toDouble()
                return String.format("%.1f%%", v)
            }

            println("Usuario: $username")

            println("   🎮 PVP -> 🏆 $pvpWins | ❌ $pvpLosses | 🤝 $pvpDraws")
            println("   🤖 PVE -> 🏆 $pveWins | ❌ $pveLosses | 🤝 $pveDraws")

            println("   📏 Victorias por tablero (PVP): 3x3=$pvpWins3, 4x4=$pvpWins4, 5x5=$pvpWins5")
            println("   📏 Victorias por tablero (PVE): 3x3=$pveWins3, 4x4=$pveWins4, 5x5=$pveWins5")

            println("   🔥 Récord de victorias consecutivas: PVP=$pvpBestStreak | PVE=$pveBestStreak")

            println("   ⏱️ Tiempo promedio por movimiento: ${String.format("%.2f", avgSec)}s (sobre $totalMoves movimientos)")

            println("   🤖 % victorias vs IA: EASY ${pct(easyWins, easyPlayed)} ($easyWins/$easyPlayed), " +
                    "MEDIUM ${pct(medWins, medPlayed)} ($medWins/$medPlayed), " +
                    "HARD ${pct(hardWins, hardPlayed)} ($hardWins/$hardPlayed)")

            if (bestCount > 0) {
                println("   ⭐ Movimiento favorito: ($bestR,$bestC) -> $bestCount veces")
            } else {
                println("   ⭐ Movimiento favorito: (sin datos todavía)")
            }

            println("---------------------------------")
        }
    }
}