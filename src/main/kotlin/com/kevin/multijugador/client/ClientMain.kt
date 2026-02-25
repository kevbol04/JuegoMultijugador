package com.kevin.multijugador.client

import com.kevin.multijugador.protocol.JsonCodec
import com.kevin.multijugador.protocol.MessageType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

object ClientMain {

    private enum class ClientState { MENU, QUEUE, IN_GAME }

    data class GameConfig(
        var boardSize: Int = 3,
        var rounds: Int = 3,
        var difficulty: String = "EASY",
        var timeLimit: Int = 30,
        var turbo: Boolean = false
    )

    private enum class InputStep { NONE, WAIT_ROW, WAIT_COL }

    @JvmStatic
    fun main(args: Array<String>) = runBlocking {
        val cfg = ClientConfigLoader.load()
        val client = TcpClient(cfg.host, cfg.port)

        val mySymbol = AtomicReference<String?>(null)
        val lastState = AtomicReference<String?>(null)
        val clientState = AtomicReference(ClientState.MENU)
        val usernameRef = AtomicReference<String?>(null)
        val configRef = AtomicReference(GameConfig())

        val inputLines = LinkedBlockingQueue<String>()

        val inputStep = AtomicReference(InputStep.NONE)
        val pendingRow = AtomicReference<Int?>(null)

        val nextPlayerRef = AtomicReference<String?>(null)

        val turnToken = AtomicLong(0L)

        val moveJobRef = AtomicReference<Job?>(null)

        val deferredTimeoutMsg = AtomicReference<String?>(null)

        try {
            client.connect()

            launch(Dispatchers.IO) {
                while (true) {
                    val line = readLine() ?: break
                    inputLines.offer(line.trim())
                }
            }

            var username: String
            while (true) {
                println("===== LOGIN =====")
                print("Introduce tu nombre de usuario: ")
                username = takeLineBlocking(inputLines, clientState, deferredTimeoutMsg)

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

                            val round = extractInt(env.payloadJson, "round")
                            val totalRounds = extractInt(env.payloadJson, "totalRounds")
                            val xWins = extractInt(env.payloadJson, "xWins")
                            val oWins = extractInt(env.payloadJson, "oWins")
                            val needed = extractInt(env.payloadJson, "winsNeeded")

                            if (round != null && totalRounds != null) {
                                println("\n=== RONDA $round/$totalRounds ===")
                                if (xWins != null && oWins != null && needed != null) {
                                    println("Marcador -> X:$xWins | O:$oWins | (necesitas $needed)")
                                }
                            } else {
                                println("\nPartida iniciada.")
                            }

                            println("Tu símbolo: $sym")
                            inputStep.set(InputStep.NONE)
                            pendingRow.set(null)

                            deferredTimeoutMsg.set(null)

                            cancelMovePrompt(moveJobRef)
                            flushInputQueue(inputLines)

                            turnToken.incrementAndGet()
                        }

                        MessageType.GAME_STATE -> {
                            lastState.set(env.payloadJson)

                            val size = extractInt(env.payloadJson, "boardSize") ?: 3
                            printGameState(env.payloadJson, size)

                            val next = extractString(env.payloadJson, "nextPlayer")
                            val mine = mySymbol.get()

                            val prev = nextPlayerRef.getAndSet(next)
                            if (prev != next) turnToken.incrementAndGet()

                            if (mine == null || next != mine) {
                                cancelMovePrompt(moveJobRef)

                                inputStep.set(InputStep.NONE)
                                pendingRow.set(null)

                                turnToken.incrementAndGet()

                                flushInputQueue(inputLines)

                                return@readLoop
                            }

                            val existing = moveJobRef.get()
                            if (existing != null && existing.isActive) return@readLoop

                            startMovePromptIfMyTurn(
                                size = size,
                                inputLines = inputLines,
                                clientState = clientState,
                                mySymbol = mine,
                                nextPlayerRef = nextPlayerRef,
                                turnToken = turnToken,
                                moveJobRef = moveJobRef,
                                client = client
                            )
                        }

                        MessageType.TIMEOUT -> {
                            val who = extractString(env.payloadJson, "timedOut") ?: ""
                            val base = extractString(env.payloadJson, "message") ?: "Tiempo agotado. Pierdes el turno."
                            val msg = if (who.isNotBlank()) "⏰ $base (jugador: $who)" else "⏰ $base"

                            println("\n$msg\n")

                            cancelMovePrompt(moveJobRef)
                            inputStep.set(InputStep.NONE)
                            pendingRow.set(null)

                            turnToken.incrementAndGet()

                            flushInputQueue(inputLines)

                            deferredTimeoutMsg.set(null)
                        }

                        MessageType.ROUND_END -> {
                            val roundWinner = extractString(env.payloadJson, "roundWinner") ?: "DRAW"
                            val seriesOver = env.payloadJson.contains(""""seriesOver":true""")

                            val xWins = extractInt(env.payloadJson, "xWins") ?: 0
                            val oWins = extractInt(env.payloadJson, "oWins") ?: 0
                            val round = extractInt(env.payloadJson, "round") ?: 1
                            val totalRounds = extractInt(env.payloadJson, "totalRounds") ?: 3
                            val needed = extractInt(env.payloadJson, "winsNeeded") ?: ((totalRounds / 2) + 1)

                            println("\n=== FIN DE RONDA $round/$totalRounds ===")
                            when (roundWinner) {
                                "DRAW" -> println("🤝 Empate en la ronda.")
                                "X", "O" -> println("🏁 Ronda ganada por: $roundWinner")
                                else -> println("Fin de ronda.")
                            }
                            println("Marcador -> X:$xWins | O:$oWins | (necesitas $needed)")

                            cancelMovePrompt(moveJobRef)
                            flushInputQueue(inputLines)

                            inputStep.set(InputStep.NONE)
                            pendingRow.set(null)

                            turnToken.incrementAndGet()

                            if (seriesOver) {
                                val winner = extractString(env.payloadJson, "winner") ?: "DRAW"
                                val winnerUser = extractString(env.payloadJson, "winnerUser")
                                val loserUser = extractString(env.payloadJson, "loserUser")

                                when (winner) {
                                    "DRAW" -> println("\n🏁 SERIE FINALIZADA: EMPATE.")
                                    "X", "O" -> println("\n🏆 SERIE FINALIZADA. Ganador: $winner ${winnerUser?.let { "($it)" } ?: ""}")
                                    else -> println("\n🏁 SERIE FINALIZADA.")
                                }

                                mySymbol.set(null)
                                lastState.set(null)
                                clientState.set(ClientState.MENU)
                                deferredTimeoutMsg.set(null)

                                turnToken.incrementAndGet()
                                flushInputQueue(inputLines)
                            }
                        }

                        MessageType.ERROR -> {
                            val msg = extractString(env.payloadJson, "message") ?: "Error desconocido"
                            println("\nERROR: $msg")

                            if (clientState.get() == ClientState.IN_GAME) {
                                val mine = mySymbol.get()
                                val next = nextPlayerRef.get()

                                if (mine != null && next == mine) {
                                    cancelMovePrompt(moveJobRef)
                                    flushInputQueue(inputLines)

                                    turnToken.incrementAndGet()

                                    val size = extractInt(lastState.get() ?: "", "boardSize") ?: 3
                                    startMovePromptIfMyTurn(
                                        size = size,
                                        inputLines = inputLines,
                                        clientState = clientState,
                                        mySymbol = mine,
                                        nextPlayerRef = nextPlayerRef,
                                        turnToken = turnToken,
                                        moveJobRef = moveJobRef,
                                        client = client
                                    )
                                }
                            }
                        }

                        MessageType.RECORDS_SYNC -> client.setRecordsJson(env.payloadJson)
                    }
                }
            }

            while (true) {
                if (clientState.get() != ClientState.MENU) {
                    Thread.sleep(200)
                    continue
                }

                val cfgLocal = configRef.get()

                println("===== MENÚ PRINCIPAL =====")
                println("1. Nueva Partida PVP")
                println("2. Nueva Partida PVE")
                println("3. Ver Records")
                println("4. Configuración")
                println("5. Salir")
                println("Config actual: tablero ${cfgLocal.boardSize}x${cfgLocal.boardSize}, mejor de ${cfgLocal.rounds}, IA ${cfgLocal.difficulty}, timeLimit ${cfgLocal.timeLimit}, turbo ${cfgLocal.turbo}")
                print("Elige opción: ")

                val opt = takeLineBlocking(inputLines, clientState, deferredTimeoutMsg)

                when (opt) {
                    "1" -> {
                        val cfgSend = configRef.get()
                        client.send(
                            MessageType.JOIN_QUEUE,
                            """{"boardSize":${cfgSend.boardSize},"rounds":${cfgSend.rounds},"timeLimit":${cfgSend.timeLimit},"turbo":${cfgSend.turbo}}"""
                        )
                        clientState.set(ClientState.QUEUE)
                    }

                    "2" -> {
                        val cfgSend = configRef.get()
                        client.send(
                            MessageType.START_PVE,
                            """{"boardSize":${cfgSend.boardSize},"rounds":${cfgSend.rounds},"difficulty":"${cfgSend.difficulty}","timeLimit":${cfgSend.timeLimit},"turbo":${cfgSend.turbo}}"""
                        )
                        clientState.set(ClientState.QUEUE)
                    }

                    "3" -> {
                        println("\n===== RECORDS =====")
                        printFormattedRecords(client.recordsJson)
                        println()
                    }

                    "4" -> configMenu(configRef, inputLines, clientState, deferredTimeoutMsg)

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

    private fun flushInputQueue(q: LinkedBlockingQueue<String>) {
        while (q.poll() != null) { /* vaciar */ }
    }

    private fun cancelMovePrompt(moveJobRef: AtomicReference<Job?>) {
        moveJobRef.getAndSet(null)?.cancel()
    }

    private fun startMovePromptIfMyTurn(
        size: Int,
        inputLines: LinkedBlockingQueue<String>,
        clientState: AtomicReference<ClientState>,
        mySymbol: String,
        nextPlayerRef: AtomicReference<String?>,
        turnToken: AtomicLong,
        moveJobRef: AtomicReference<Job?>,
        client: TcpClient
    ) {
        val existing = moveJobRef.get()
        if (existing != null && existing.isActive) return

        val myToken = turnToken.get()
        val job = kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
            val move = askMoveClassicCancelable(
                size = size,
                inputLines = inputLines,
                clientState = clientState,
                mySymbol = mySymbol,
                nextPlayerRef = nextPlayerRef,
                turnToken = turnToken,
                expectedToken = myToken
            ) ?: return@launch

            if (clientState.get() != ClientState.IN_GAME) return@launch
            if (nextPlayerRef.get() != mySymbol) return@launch
            if (turnToken.get() != myToken) return@launch

            client.send(MessageType.MAKE_MOVE, """{"row":${move.first},"col":${move.second}}""")
        }
        moveJobRef.set(job)
    }

    private fun takeLineBlocking(
        q: LinkedBlockingQueue<String>,
        clientState: AtomicReference<ClientState>,
        deferredTimeoutMsg: AtomicReference<String?>
    ): String {
        while (true) {
            val v = q.take()

            val pending = deferredTimeoutMsg.getAndSet(null)
            if (pending != null && clientState.get() == ClientState.IN_GAME) {
                println("\n$pending\n")
            }

            val s = v.trim()
            if (s.isNotBlank()) return s
        }
    }

    private fun askMoveClassic(
        size: Int,
        inputLines: LinkedBlockingQueue<String>,
        clientState: AtomicReference<ClientState>,
        deferredTimeoutMsg: AtomicReference<String?>
    ): Pair<Int, Int> {
        val max = size - 1

        while (true) {
            print("Fila (0-$max): ")
            val r = takeLineBlocking(inputLines, clientState, deferredTimeoutMsg).toIntOrNull()

            print("Col  (0-$max): ")
            val c = takeLineBlocking(inputLines, clientState, deferredTimeoutMsg).toIntOrNull()

            if (r == null || c == null) {
                println("Movimiento inválido: debes escribir números.")
                continue
            }
            if (r !in 0..max || c !in 0..max) {
                println("Movimiento fuera de rango (0..$max).")
                continue
            }
            return r to c
        }
    }

    private fun askMoveClassicCancelable(
        size: Int,
        inputLines: LinkedBlockingQueue<String>,
        clientState: AtomicReference<ClientState>,
        mySymbol: String,
        nextPlayerRef: AtomicReference<String?>,
        turnToken: AtomicLong,
        expectedToken: Long
    ): Pair<Int, Int>? {
        val max = size - 1

        fun stillMyTurn(): Boolean {
            if (clientState.get() != ClientState.IN_GAME) return false
            if (nextPlayerRef.get() != mySymbol) return false
            if (turnToken.get() != expectedToken) return false
            return true
        }

        fun readLineCancelable(): String? {
            while (stillMyTurn()) {
                val s = inputLines.poll(150, TimeUnit.MILLISECONDS)
                if (s != null) {
                    val t = s.trim()
                    if (t.isNotBlank()) return t
                }
            }
            return null
        }

        while (true) {
            if (!stillMyTurn()) return null

            print("Fila (0-$max): ")
            val rStr = readLineCancelable() ?: return null
            val r = rStr.toIntOrNull()

            if (!stillMyTurn()) return null

            print("Col  (0-$max): ")
            val cStr = readLineCancelable() ?: return null
            val c = cStr.toIntOrNull()

            if (!stillMyTurn()) return null

            if (r == null || c == null) {
                println("Movimiento inválido: debes escribir números.")
                continue
            }
            if (r !in 0..max || c !in 0..max) {
                println("Movimiento fuera de rango (0..$max).")
                continue
            }
            return r to c
        }
    }

    private fun printGameState(payload: String, size: Int) {
        val board = extractBoard(payload, size)
        val next = extractString(payload, "nextPlayer")

        println()
        println("   " + (0 until size).joinToString("   "))
        for (r in 0 until size) {
            val row = (0 until size).joinToString(" | ") { c -> board[r][c].ifBlank { " " } }
            println("$r  $row")
            if (r != size - 1) {
                println("   " + (0 until size).joinToString("+") { "---" })
            }
        }
        println("Turno: $next")
    }

    private fun extractBoard(payload: String, size: Int): List<List<String>> {
        val boardStart = payload.indexOf("\"board\":")
        if (boardStart == -1) return List(size) { List(size) { "" } }

        val firstBracket = payload.indexOf('[', boardStart)
        val nextPlayerIdx = payload.indexOf("\"nextPlayer\"", boardStart).let { if (it == -1) payload.length else it }
        val boardChunk = payload.substring(firstBracket, nextPlayerIdx)

        val values = """"([^"]*)"""".toRegex()
            .findAll(boardChunk)
            .map { it.groupValues[1].trim() }
            .toList()

        val total = size * size
        val cells = values.take(total) + List((total - values.take(total).size).coerceAtLeast(0)) { "" }

        val grid = mutableListOf<List<String>>()
        var idx = 0
        for (r in 0 until size) {
            val row = mutableListOf<String>()
            for (c in 0 until size) row.add(cells[idx++])
            grid.add(row)
        }
        return grid
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

    private fun extractString(json: String, field: String): String? {
        val regex = """"$field"\s*:\s*"([^"]*)"""".toRegex()
        return regex.find(json)?.groupValues?.getOrNull(1)
    }

    private fun extractInt(json: String, field: String): Int? {
        val regex = """"$field"\s*:\s*(\d+)""".toRegex()
        return regex.find(json)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    private fun configMenu(
        configRef: AtomicReference<GameConfig>,
        inputLines: LinkedBlockingQueue<String>,
        clientState: AtomicReference<ClientState>,
        deferredTimeoutMsg: AtomicReference<String?>
    ) {
        while (true) {
            val cfg = configRef.get()
            println("\n===== CONFIGURACIÓN =====")
            println("1. Tamaño tablero (actual: ${cfg.boardSize}x${cfg.boardSize})")
            println("2. Número de partidas (al mejor de) (actual: ${cfg.rounds})")
            println("3. Dificultad IA PVE (actual: ${cfg.difficulty})")
            println("4. Tiempo por movimiento (seg) (actual: ${cfg.timeLimit})")
            println("5. Modo Turbo (actual: ${if (cfg.turbo) "ON" else "OFF"})")
            println("6. Volver al menú")
            print("Elige opción: ")

            when (takeLineBlocking(inputLines, clientState, deferredTimeoutMsg)) {
                "1" -> cfg.boardSize = askBoardSize(inputLines, clientState, deferredTimeoutMsg)
                "2" -> cfg.rounds = askRounds(inputLines, clientState, deferredTimeoutMsg)
                "3" -> cfg.difficulty = askDifficulty(inputLines, clientState, deferredTimeoutMsg)
                "4" -> cfg.timeLimit = askTimeLimit(cfg.turbo, inputLines, clientState, deferredTimeoutMsg)
                "5" -> {
                    cfg.turbo = !cfg.turbo
                    if (cfg.turbo) cfg.timeLimit = 10
                }
                "6" -> {
                    configRef.set(cfg)
                    println()
                    return
                }
                else -> println("Opción no válida.")
            }
            configRef.set(cfg)
        }
    }

    private fun askBoardSize(
        inputLines: LinkedBlockingQueue<String>,
        clientState: AtomicReference<ClientState>,
        deferredTimeoutMsg: AtomicReference<String?>
    ): Int {
        while (true) {
            println("\nTamaño tablero:")
            println("1. 3x3")
            println("2. 4x4")
            println("3. 5x5")
            print("Elige: ")
            return when (takeLineBlocking(inputLines, clientState, deferredTimeoutMsg)) {
                "1" -> 3
                "2" -> 4
                "3" -> 5
                else -> {
                    println("Opción no válida.")
                    continue
                }
            }
        }
    }

    private fun askRounds(
        inputLines: LinkedBlockingQueue<String>,
        clientState: AtomicReference<ClientState>,
        deferredTimeoutMsg: AtomicReference<String?>
    ): Int {
        while (true) {
            println("\nMejor de:")
            println("1. 3")
            println("2. 5")
            println("3. 7")
            print("Elige: ")
            return when (takeLineBlocking(inputLines, clientState, deferredTimeoutMsg)) {
                "1" -> 3
                "2" -> 5
                "3" -> 7
                else -> {
                    println("Opción no válida.")
                    continue
                }
            }
        }
    }

    private fun askDifficulty(
        inputLines: LinkedBlockingQueue<String>,
        clientState: AtomicReference<ClientState>,
        deferredTimeoutMsg: AtomicReference<String?>
    ): String {
        while (true) {
            println("\nDificultad IA:")
            println("1. EASY")
            println("2. MEDIUM")
            println("3. HARD")
            print("Elige: ")
            return when (takeLineBlocking(inputLines, clientState, deferredTimeoutMsg)) {
                "1" -> "EASY"
                "2" -> "MEDIUM"
                "3" -> "HARD"
                else -> {
                    println("Opción no válida.")
                    continue
                }
            }
        }
    }

    private fun askTimeLimit(
        turbo: Boolean,
        inputLines: LinkedBlockingQueue<String>,
        clientState: AtomicReference<ClientState>,
        deferredTimeoutMsg: AtomicReference<String?>
    ): Int {
        while (true) {
            if (turbo) {
                println("Turbo ON: el tiempo por turno es fijo a 10 segundos.")
                return 10
            }

            print("\nTiempo por movimiento en segundos (0 = sin límite): ")
            val v = takeLineBlocking(inputLines, clientState, deferredTimeoutMsg).toIntOrNull()

            if (v == null || v < 0) {
                println("Valor inválido.")
                continue
            }
            return v
        }
    }
}