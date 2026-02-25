package com.kevin.multijugador.server

import com.kevin.multijugador.protocol.Difficulty
import com.kevin.multijugador.protocol.MessageType
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class ServerGameService(
    private val recordsStore: RecordsStore,
    private val aiService: AiService = AiService()
) {
    private val sessionsByClient = ConcurrentHashMap<ClientConnection, GameSession>()

    private val scheduler = Executors.newScheduledThreadPool(1)
    private val timeoutTaskBySessionId = ConcurrentHashMap<String, ScheduledFuture<*>>()

    fun startPvpGame(a: ClientConnection, b: ClientConnection, cfg: MatchmakingQueue.GameConfig) {
        val size = cfg.boardSize.coerceIn(3, 5)
        val rounds = cfg.rounds.coerceIn(3, 7).let { if (it % 2 == 0) it + 1 else it }

        val timeLimit = if (cfg.turbo) 10 else cfg.timeLimit.coerceAtLeast(0)

        val session = GameSession(
            id = java.util.UUID.randomUUID().toString(),
            mode = GameMode.PVP,
            playerX = a,
            playerO = b,
            difficulty = null,
            totalRounds = rounds,
            round = 1,
            xWins = 0,
            oWins = 0,
            timeLimitSec = timeLimit,
            turbo = cfg.turbo,
            board = Array(size) { CharArray(size) { ' ' } },
            next = 'X'
        )

        sessionsByClient[a] = session
        sessionsByClient[b] = session

        sendGameStart(session)
        broadcastState(session)
        scheduleTurnTimeout(session)
    }

    fun startPveGame(human: ClientConnection, diffStr: String, boardSize: Int, rounds: Int, timeLimitSec: Int, turbo: Boolean) {
        val diff = runCatching { com.kevin.multijugador.protocol.Difficulty.valueOf(diffStr.uppercase()) }
            .getOrElse { com.kevin.multijugador.protocol.Difficulty.EASY }

        val size = boardSize.coerceIn(3, 5)
        val totalRounds = rounds.coerceIn(3, 7).let { if (it % 2 == 0) it + 1 else it }

        val tl = if (turbo) 10 else timeLimitSec.coerceAtLeast(0)

        val session = GameSession(
            id = java.util.UUID.randomUUID().toString(),
            mode = GameMode.PVE,
            playerX = human,
            playerO = null,
            difficulty = diff,
            totalRounds = totalRounds,
            round = 1,
            xWins = 0,
            oWins = 0,
            timeLimitSec = tl,
            turbo = turbo,
            board = Array(size) { CharArray(size) { ' ' } },
            next = 'X'
        )

        sessionsByClient[human] = session

        sendGameStart(session)
        broadcastState(session)
        scheduleTurnTimeout(session)
    }

    fun handleMove(client: ClientConnection, row: Int, col: Int) {
        val session = sessionsByClient[client] ?: run {
            client.send(MessageType.ERROR, """{"message":"No estás en una partida"}""")
            return
        }

        val size = session.board.size

        val symbol = when (session.mode) {
            GameMode.PVP -> if (client == session.playerX) 'X' else 'O'
            GameMode.PVE -> 'X'
        }

        if (symbol != session.next) {
            client.send(MessageType.ERROR, """{"message":"No es tu turno"}""")
            return
        }

        if (row !in 0 until size || col !in 0 until size) {
            client.send(MessageType.ERROR, """{"message":"Movimiento fuera de rango"}""")
            return
        }

        if (session.board[row][col] != ' ') {
            client.send(MessageType.ERROR, """{"message":"Casilla ocupada"}""")
            return
        }

        cancelTurnTimeout(session)

        session.board[row][col] = symbol

        val winner = checkWinnerNxN(session.board)
        val draw = winner == null && isFull(session.board)

        if (winner != null) {
            onRoundFinished(session, winner)
            return
        }
        if (draw) {
            onRoundFinished(session, "DRAW")
            return
        }

        session.next = if (session.next == 'X') 'O' else 'X'
        broadcastState(session)

        if (session.mode == GameMode.PVE && session.next == 'O') {
            playAiTurn(session)
            return
        }

        scheduleTurnTimeout(session)
    }

    private fun playAiTurn(session: GameSession) {
        val move = chooseAiMoveSafe(session)
        session.board[move.first][move.second] = 'O'

        val winner = checkWinnerNxN(session.board)
        val draw = winner == null && isFull(session.board)

        if (winner != null) {
            onRoundFinished(session, winner)
            return
        }
        if (draw) {
            onRoundFinished(session, "DRAW")
            return
        }

        session.next = 'X'
        broadcastState(session)

        scheduleTurnTimeout(session)
    }

    private fun scheduleTurnTimeout(session: GameSession) {
        val tl = session.timeLimitSec
        if (tl <= 0) return

        cancelTurnTimeout(session)

        val sessionId = session.id
        val expectedTurn = session.next

        val task = scheduler.schedule({
            val stillSession = findSessionById(sessionId) ?: return@schedule

            if (stillSession.next != expectedTurn) return@schedule

            if (stillSession.mode == GameMode.PVE && expectedTurn == 'O') return@schedule

            broadcastTimeout(stillSession, expectedTurn)
            stillSession.next = if (stillSession.next == 'X') 'O' else 'X'
            broadcastState(stillSession)

            if (stillSession.mode == GameMode.PVE && stillSession.next == 'O') {
                playAiTurn(stillSession)
                return@schedule
            }

            scheduleTurnTimeout(stillSession)

        }, tl.toLong(), TimeUnit.SECONDS)

        timeoutTaskBySessionId[session.id] = task
    }

    private fun broadcastTimeout(session: GameSession, timedOutSymbol: Char) {
        val payload = """{"timedOut":"$timedOutSymbol","message":"Tiempo agotado. Pierdes el turno."}"""
        when (session.mode) {
            GameMode.PVP -> {
                session.playerX.send(MessageType.TIMEOUT, payload)
                session.playerO?.send(MessageType.TIMEOUT, payload)
            }
            GameMode.PVE -> {
                session.playerX.send(MessageType.TIMEOUT, payload)
            }
        }
    }

    private fun cancelTurnTimeout(session: GameSession) {
        timeoutTaskBySessionId.remove(session.id)?.cancel(false)
    }

    private fun findSessionById(id: String): GameSession? {
        return sessionsByClient.values.firstOrNull { it.id == id }
    }

    private fun onRoundFinished(session: GameSession, roundWinner: String) {
        cancelTurnTimeout(session)

        when (roundWinner) {
            "X" -> session.xWins++
            "O" -> session.oWins++
            "DRAW" -> {}
        }

        val needed = session.winsNeeded()
        val seriesOverByWins = session.xWins >= needed || session.oWins >= needed
        val seriesOverByRounds = session.round >= session.totalRounds
        val seriesOver = seriesOverByWins || seriesOverByRounds

        val finalWinner = if (seriesOver) {
            when {
                session.xWins > session.oWins -> "X"
                session.oWins > session.xWins -> "O"
                else -> "DRAW"
            }
        } else ""

        broadcastRoundEnd(session, roundWinner, seriesOver, finalWinner)

        if (seriesOver) {
            applySeriesToRecords(session, finalWinner)
            pushRecords(session)
            endSession(session)
            return
        }

        session.round++
        session.board = Array(session.board.size) { CharArray(session.board.size) { ' ' } }
        session.next = 'X'

        sendGameStart(session)
        broadcastState(session)
        scheduleTurnTimeout(session)
    }

    private fun sendGameStart(session: GameSession) {
        val payloadBase =
            """{"gameId":"${session.id}","round":${session.round},"totalRounds":${session.totalRounds},"xWins":${session.xWins},"oWins":${session.oWins},"winsNeeded":${session.winsNeeded()}}"""

        when (session.mode) {
            GameMode.PVP -> {
                session.playerX.send(MessageType.GAME_START, payloadBase.dropLast(1) + ""","yourSymbol":"X"}""")
                session.playerO?.send(MessageType.GAME_START, payloadBase.dropLast(1) + ""","yourSymbol":"O"}""")
            }
            GameMode.PVE -> {
                session.playerX.send(MessageType.GAME_START, payloadBase.dropLast(1) + ""","yourSymbol":"X"}""")
            }
        }
    }

    private fun broadcastState(session: GameSession) {
        val size = session.board.size

        val boardJson = session.board.joinToString(prefix = "[", postfix = "]") { row ->
            row.joinToString(prefix = "[", postfix = "]") { cell ->
                """"${if (cell == ' ') "" else cell.toString()}""""
            }
        }

        val payload = """{"gameId":"${session.id}","boardSize":$size,"board":$boardJson,"nextPlayer":"${session.next}"}"""

        session.playerX.send(MessageType.GAME_STATE, payload)
        session.playerO?.send(MessageType.GAME_STATE, payload)
    }

    private fun broadcastRoundEnd(session: GameSession, roundWinner: String, seriesOver: Boolean, finalWinner: String) {
        val xUser = session.playerX.username ?: "PlayerX"
        val oUser = session.playerO?.username ?: "PlayerO"
        val aiUser = "AI"

        val (winnerUser, loserUser) = when {
            !seriesOver -> Pair(null, null)
            finalWinner == "DRAW" -> Pair(null, null)
            finalWinner == "X" -> {
                val loser = if (session.mode == GameMode.PVE) aiUser else oUser
                Pair(xUser, loser)
            }
            finalWinner == "O" -> {
                val winner = if (session.mode == GameMode.PVE) aiUser else oUser
                Pair(winner, xUser)
            }
            else -> Pair(null, null)
        }

        val payload =
            """{"gameId":"${session.id}","roundWinner":"$roundWinner","seriesOver":$seriesOver,"winner":"${if (seriesOver) finalWinner else ""}","winnerUser":${winnerUser?.let { "\"$it\"" } ?: "null"},"loserUser":${loserUser?.let { "\"$it\"" } ?: "null"},"round":${session.round},"totalRounds":${session.totalRounds},"xWins":${session.xWins},"oWins":${session.oWins},"winsNeeded":${session.winsNeeded()}}"""

        session.playerX.send(MessageType.ROUND_END, payload)
        session.playerO?.send(MessageType.ROUND_END, payload)
    }

    private fun applySeriesToRecords(session: GameSession, finalWinner: String) {
        val xUser = session.playerX.username ?: return
        val oUser = session.playerO?.username
        val aiUser = "AI"

        if (session.mode == GameMode.PVP) {
            val ou = oUser ?: return
            when (finalWinner) {
                "X" -> {
                    recordsStore.updateResult(xUser, RecordsStore.Outcome.WIN)
                    recordsStore.updateResult(ou, RecordsStore.Outcome.LOSS)
                }
                "O" -> {
                    recordsStore.updateResult(ou, RecordsStore.Outcome.WIN)
                    recordsStore.updateResult(xUser, RecordsStore.Outcome.LOSS)
                }
                "DRAW" -> {
                    recordsStore.updateResult(xUser, RecordsStore.Outcome.DRAW)
                    recordsStore.updateResult(ou, RecordsStore.Outcome.DRAW)
                }
            }
        } else {
            when (finalWinner) {
                "X" -> {
                    recordsStore.updateResult(xUser, RecordsStore.Outcome.WIN)
                    recordsStore.updateResult(aiUser, RecordsStore.Outcome.LOSS)
                }
                "O" -> {
                    recordsStore.updateResult(aiUser, RecordsStore.Outcome.WIN)
                    recordsStore.updateResult(xUser, RecordsStore.Outcome.LOSS)
                }
                "DRAW" -> {
                    recordsStore.updateResult(xUser, RecordsStore.Outcome.DRAW)
                    recordsStore.updateResult(aiUser, RecordsStore.Outcome.DRAW)
                }
            }
        }
    }

    private fun pushRecords(session: GameSession) {
        val records = recordsStore.loadRawJson()
        session.playerX.send(MessageType.RECORDS_SYNC, records)
        session.playerO?.send(MessageType.RECORDS_SYNC, records)
    }

    private fun endSession(session: GameSession) {
        cancelTurnTimeout(session)
        sessionsByClient.remove(session.playerX)
        session.playerO?.let { sessionsByClient.remove(it) }
    }

    private fun chooseAiMoveSafe(session: GameSession): Pair<Int, Int> {
        val size = session.board.size
        val diff = session.difficulty ?: Difficulty.EASY

        if (size == 3) return aiService.chooseMove(session.board, diff)
        return availableMoves(session.board).random()
    }

    private fun isFull(board: Array<CharArray>): Boolean =
        board.all { row -> row.all { it != ' ' } }

    private fun availableMoves(board: Array<CharArray>): List<Pair<Int, Int>> =
        buildList {
            for (r in board.indices) for (c in board.indices) if (board[r][c] == ' ') add(r to c)
        }

    private fun checkWinnerNxN(b: Array<CharArray>): String? {
        val n = b.size

        for (r in 0 until n) {
            val first = b[r][0]
            if (first != ' ' && (1 until n).all { c -> b[r][c] == first }) return first.toString()
        }

        for (c in 0 until n) {
            val first = b[0][c]
            if (first != ' ' && (1 until n).all { r -> b[r][c] == first }) return first.toString()
        }

        val d0 = b[0][0]
        if (d0 != ' ' && (1 until n).all { i -> b[i][i] == d0 }) return d0.toString()

        val d1 = b[0][n - 1]
        if (d1 != ' ' && (1 until n).all { i -> b[i][n - 1 - i] == d1 }) return d1.toString()

        return null
    }
}