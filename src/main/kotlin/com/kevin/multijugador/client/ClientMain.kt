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

            fun stat(field: String): Int =
                """"$field"\s*:\s*(\d+)""".toRegex()
                    .find(stats)
                    ?.groupValues?.getOrNull(1)
                    ?.toIntOrNull() ?: 0

            println("Usuario: $username")
            println("   🏆 Victorias: ${stat("wins")}")
            println("   ❌ Derrotas: ${stat("losses")}")
            println("   🤝 Empates: ${stat("draws")}")
            println("   🔥 Racha actual: ${stat("streak")}")
            println("   ⭐ Mejor racha: ${stat("bestStreak")}")
            println("---------------------------------")
        }
    }
}