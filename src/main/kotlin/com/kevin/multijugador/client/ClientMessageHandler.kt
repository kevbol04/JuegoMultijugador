package com.kevin.multijugador.client

import com.kevin.multijugador.protocol.MessageType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference

class ClientMessageHandler(
    private val session: ClientSession,
    private val input: ConsoleInput,
    private val client: TcpClient
) {

    fun handle(type: String, payloadJson: String) {
        when (type) {
            MessageType.QUEUE_STATUS -> onQueueStatus(payloadJson)
            MessageType.GAME_START -> onGameStart(payloadJson)
            MessageType.GAME_STATE -> onGameState(payloadJson)
            MessageType.TIMEOUT -> onTimeout(payloadJson)
            MessageType.ROUND_END -> onRoundEnd(payloadJson)
            MessageType.ERROR -> onError(payloadJson)
            MessageType.RECORDS_SYNC -> client.setRecordsJson(payloadJson)
        }
    }

    private fun onQueueStatus(json: String) {
        if (json.contains("WAITING")) {
            println("\nEsperando rival...")
            session.clientState.set(ClientSession.ClientState.QUEUE)
        }
        if (json.contains("MATCHED")) println("\nRival encontrado.")
    }

    private fun onGameStart(json: String) {
        val sym = extractString(json, "yourSymbol")
        session.mySymbol.set(sym)
        session.clientState.set(ClientSession.ClientState.IN_GAME)

        val round = extractInt(json, "round")
        val totalRounds = extractInt(json, "totalRounds")
        val xWins = extractInt(json, "xWins")
        val oWins = extractInt(json, "oWins")
        val needed = extractInt(json, "winsNeeded")

        if (round != null && totalRounds != null) {
            println("\n=== RONDA $round/$totalRounds ===")
            if (xWins != null && oWins != null && needed != null) {
                println("Marcador -> X:$xWins | O:$oWins | (necesitas $needed)")
            }
        } else {
            println("\nPartida iniciada.")
        }

        println("Tu símbolo: $sym")

        session.deferredTimeoutMsg.set(null)

        cancelMovePrompt(session.moveJobRef)
        input.flush()

        session.turnToken.incrementAndGet()
    }

    private fun onGameState(json: String) {
        session.lastState.set(json)

        val size = extractInt(json, "boardSize") ?: 3
        printGameState(json, size)

        val next = extractString(json, "nextPlayer")
        val mine = session.mySymbol.get()

        val prev = session.nextPlayerRef.getAndSet(next)
        if (prev != next) session.turnToken.incrementAndGet()

        if (mine == null || next != mine) {
            cancelMovePrompt(session.moveJobRef)
            session.turnToken.incrementAndGet()
            input.flush()
            return
        }

        val existing = session.moveJobRef.get()
        if (existing != null && existing.isActive) return

        startMovePromptIfMyTurn(size)
    }

    private fun onTimeout(json: String) {
        val who = extractString(json, "timedOut") ?: ""
        val base = extractString(json, "message") ?: "Tiempo agotado. Pierdes el turno."
        val msg = if (who.isNotBlank()) "⏰ $base (jugador: $who)" else "⏰ $base"

        println("\n$msg\n")

        cancelMovePrompt(session.moveJobRef)
        session.turnToken.incrementAndGet()
        input.flush()
        session.deferredTimeoutMsg.set(null)
    }

    private fun onRoundEnd(json: String) {
        val roundWinner = extractString(json, "roundWinner") ?: "DRAW"
        val seriesOver = json.contains(""""seriesOver":true""")

        val xWins = extractInt(json, "xWins") ?: 0
        val oWins = extractInt(json, "oWins") ?: 0
        val round = extractInt(json, "round") ?: 1
        val totalRounds = extractInt(json, "totalRounds") ?: 3
        val needed = extractInt(json, "winsNeeded") ?: ((totalRounds / 2) + 1)

        println("\n=== FIN DE RONDA $round/$totalRounds ===")
        when (roundWinner) {
            "DRAW" -> println("🤝 Empate en la ronda.")
            "X", "O" -> println("🏁 Ronda ganada por: $roundWinner")
            else -> println("Fin de ronda.")
        }
        println("Marcador -> X:$xWins | O:$oWins | (necesitas $needed)")

        cancelMovePrompt(session.moveJobRef)
        input.flush()

        session.turnToken.incrementAndGet()

        if (seriesOver) {
            val winner = extractString(json, "winner") ?: "DRAW"
            val winnerUser = extractString(json, "winnerUser")

            when (winner) {
                "DRAW" -> println("\n🏁 SERIE FINALIZADA: EMPATE.")
                "X", "O" -> println("\n🏆 SERIE FINALIZADA. Ganador: $winner ${winnerUser?.let { "($it)" } ?: ""}")
                else -> println("\n🏁 SERIE FINALIZADA.")
            }

            session.mySymbol.set(null)
            session.lastState.set(null)
            session.clientState.set(ClientSession.ClientState.MENU)
            session.deferredTimeoutMsg.set(null)

            session.turnToken.incrementAndGet()
            input.flush()
        }
    }

    private fun onError(json: String) {
        val msg = extractString(json, "message") ?: "Error desconocido"
        println("\nERROR: $msg")

        if (session.clientState.get() == ClientSession.ClientState.IN_GAME) {
            val mine = session.mySymbol.get()
            val next = session.nextPlayerRef.get()

            if (mine != null && next == mine) {
                cancelMovePrompt(session.moveJobRef)
                input.flush()
                session.turnToken.incrementAndGet()

                val size = extractInt(session.lastState.get() ?: "", "boardSize") ?: 3
                startMovePromptIfMyTurn(size)
            }
        }
    }

    private fun startMovePromptIfMyTurn(size: Int) {
        val existing = session.moveJobRef.get()
        if (existing != null && existing.isActive) return

        val mySymbol = session.mySymbol.get() ?: return
        val myToken = session.turnToken.get()

        val job = GlobalScope.launch(Dispatchers.IO) {
            val move = askMoveClassicCancelable(size, mySymbol, myToken) ?: return@launch

            if (session.clientState.get() != ClientSession.ClientState.IN_GAME) return@launch
            if (session.nextPlayerRef.get() != mySymbol) return@launch
            if (session.turnToken.get() != myToken) return@launch

            client.send(MessageType.MAKE_MOVE, """{"row":${move.first},"col":${move.second}}""")
        }
        session.moveJobRef.set(job)
    }

    private fun askMoveClassicCancelable(size: Int, mySymbol: String, expectedToken: Long): Pair<Int, Int>? {
        val max = size - 1

        fun stillMyTurn(): Boolean {
            if (session.clientState.get() != ClientSession.ClientState.IN_GAME) return false
            if (session.nextPlayerRef.get() != mySymbol) return false
            if (session.turnToken.get() != expectedToken) return false
            return true
        }

        while (true) {
            if (!stillMyTurn()) return null

            print("Fila (0-$max): ")
            val rStr = input.pollNonBlankWhile(::stillMyTurn) ?: return null
            val r = rStr.toIntOrNull()

            if (!stillMyTurn()) return null

            print("Col  (0-$max): ")
            val cStr = input.pollNonBlankWhile(::stillMyTurn) ?: return null
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

    private fun cancelMovePrompt(moveJobRef: AtomicReference<Job?>) {
        moveJobRef.getAndSet(null)?.cancel()
    }

    private fun extractString(json: String, field: String): String? {
        val regex = """"$field"\s*:\s*"([^"]*)"""".toRegex()
        return regex.find(json)?.groupValues?.getOrNull(1)
    }

    private fun extractInt(json: String, field: String): Int? {
        val regex = """"$field"\s*:\s*(\d+)""".toRegex()
        return regex.find(json)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    private fun printGameState(payload: String, size: Int) {
        val board = extractBoard(payload, size)
        val next = extractString(payload, "nextPlayer")

        println()
        println("   " + (0 until size).joinToString("   "))
        for (r in 0 until size) {
            val row = (0 until size).joinToString(" | ") { c -> board[r][c].ifBlank { " " } }
            println("$r  $row")
            if (r != size - 1) println("   " + (0 until size).joinToString("+") { "---" })
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
}