package com.kevin.multijugador.server

import com.kevin.multijugador.protocol.Difficulty
import com.kevin.multijugador.protocol.MessageType
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class ServerGameService(
    private val recordsStore: RecordsStore,
    private val aiService: AiService = AiService()
) {
    private val sessionsByClient = ConcurrentHashMap<ClientConnection, GameSession>()

    fun startPvpGame(a: ClientConnection, b: ClientConnection) {
        val session = GameSession(
            id = UUID.randomUUID().toString(),
            mode = GameMode.PVP,
            playerX = a,
            playerO = b,
            difficulty = null,
            board = Array(3) { CharArray(3) { ' ' } },
            next = 'X'
        )

        sessionsByClient[a] = session
        sessionsByClient[b] = session

        a.send(MessageType.GAME_START, """{"gameId":"${session.id}","yourSymbol":"X"}""")
        b.send(MessageType.GAME_START, """{"gameId":"${session.id}","yourSymbol":"O"}""")

        broadcastState(session)
    }

    fun startPveGame(human: ClientConnection, diffStr: String) {
        val diff = runCatching { Difficulty.valueOf(diffStr.uppercase()) }
            .getOrElse { Difficulty.EASY }

        val session = GameSession(
            id = UUID.randomUUID().toString(),
            mode = GameMode.PVE,
            playerX = human,
            playerO = null,
            difficulty = diff,
            board = Array(3) { CharArray(3) { ' ' } },
            next = 'X'
        )

        sessionsByClient[human] = session

        human.send(MessageType.GAME_START, """{"gameId":"${session.id}","yourSymbol":"X"}""")
        broadcastState(session)
    }

    fun handleMove(client: ClientConnection, row: Int, col: Int) {
        val session = sessionsByClient[client] ?: run {
            client.send(MessageType.ERROR, """{"message":"No estás en una partida"}""")
            return
        }

        val symbol = when (session.mode) {
            GameMode.PVP -> if (client == session.playerX) 'X' else 'O'
            GameMode.PVE -> 'X'
        }

        if (symbol != session.next) {
            client.send(MessageType.ERROR, """{"message":"No es tu turno"}""")
            return
        }

        if (row !in 0..2 || col !in 0..2) {
            client.send(MessageType.ERROR, """{"message":"Movimiento fuera de rango"}""")
            return
        }

        if (session.board[row][col] != ' ') {
            client.send(MessageType.ERROR, """{"message":"Casilla ocupada"}""")
            return
        }

        session.board[row][col] = symbol

        val winner = checkWinner(session.board)
        val draw = winner == null && isFull(session.board)

        if (winner != null) {
            finishSession(session, winner)
            return
        }
        if (draw) {
            finishSession(session, "DRAW")
            return
        }

        session.next = if (session.next == 'X') 'O' else 'X'
        broadcastState(session)

        if (session.mode == GameMode.PVE && session.next == 'O') {
            val aiMove = aiService.chooseMove(session.board, session.difficulty ?: Difficulty.EASY)
            session.board[aiMove.first][aiMove.second] = 'O'

            val winner2 = checkWinner(session.board)
            val draw2 = winner2 == null && isFull(session.board)

            if (winner2 != null) {
                finishSession(session, winner2)
                return
            }
            if (draw2) {
                finishSession(session, "DRAW")
                return
            }

            session.next = 'X'
            broadcastState(session)
        }
    }

    private fun finishSession(session: GameSession, winner: String) {
        broadcastState(session, nextPlayerOverride = "")

        when (session.mode) {
            GameMode.PVP -> {
                if (winner == "DRAW") applyDrawToRecordsPvp(session)
                else applyResultToRecordsPvp(session, winner)

                pushRecordsPvp(session)
                broadcastRoundEndPvp(session, winner)
                endSessionPvp(session)
            }

            GameMode.PVE -> {
                if (winner == "DRAW") applyDrawToRecordsPve(session)
                else applyResultToRecordsPve(session, winner)

                pushRecordsPve(session)
                broadcastRoundEndPve(session, winner)
                endSessionPve(session)
            }
        }
    }

    private fun pushRecordsPvp(session: GameSession) {
        val records = recordsStore.loadRawJson()
        session.playerX.send(MessageType.RECORDS_SYNC, records)
        session.playerO?.send(MessageType.RECORDS_SYNC, records)
    }

    private fun applyResultToRecordsPvp(session: GameSession, winner: String) {
        val xUser = session.playerX.username ?: return
        val oUser = session.playerO?.username ?: return

        if (winner == "X") {
            recordsStore.updateResult(xUser, RecordsStore.Outcome.WIN)
            recordsStore.updateResult(oUser, RecordsStore.Outcome.LOSS)
        } else if (winner == "O") {
            recordsStore.updateResult(oUser, RecordsStore.Outcome.WIN)
            recordsStore.updateResult(xUser, RecordsStore.Outcome.LOSS)
        }
    }

    private fun applyDrawToRecordsPvp(session: GameSession) {
        val xUser = session.playerX.username ?: return
        val oUser = session.playerO?.username ?: return
        recordsStore.updateResult(xUser, RecordsStore.Outcome.DRAW)
        recordsStore.updateResult(oUser, RecordsStore.Outcome.DRAW)
    }

    private fun broadcastRoundEndPvp(session: GameSession, winner: String) {
        val xUser = session.playerX.username ?: "PlayerX"
        val oUser = session.playerO?.username ?: "PlayerO"

        val payload = when (winner) {
            "DRAW" -> """{"gameId":"${session.id}","winner":"DRAW","winnerUser":null,"loserUser":null}"""
            "X" -> """{"gameId":"${session.id}","winner":"X","winnerUser":"$xUser","loserUser":"$oUser"}"""
            "O" -> """{"gameId":"${session.id}","winner":"O","winnerUser":"$oUser","loserUser":"$xUser"}"""
            else -> """{"gameId":"${session.id}","winner":"$winner","winnerUser":null,"loserUser":null}"""
        }

        session.playerX.send(MessageType.ROUND_END, payload)
        session.playerO?.send(MessageType.ROUND_END, payload)
    }

    private fun endSessionPvp(session: GameSession) {
        sessionsByClient.remove(session.playerX)
        session.playerO?.let { sessionsByClient.remove(it) }
    }

    private fun pushRecordsPve(session: GameSession) {
        val records = recordsStore.loadRawJson()
        session.playerX.send(MessageType.RECORDS_SYNC, records)
    }

    private fun applyResultToRecordsPve(session: GameSession, winner: String) {
        val human = session.playerX.username ?: return
        val aiUser = "AI"

        if (winner == "X") {
            recordsStore.updateResult(human, RecordsStore.Outcome.WIN)
            recordsStore.updateResult(aiUser, RecordsStore.Outcome.LOSS)
        } else if (winner == "O") {
            recordsStore.updateResult(aiUser, RecordsStore.Outcome.WIN)
            recordsStore.updateResult(human, RecordsStore.Outcome.LOSS)
        }
    }

    private fun applyDrawToRecordsPve(session: GameSession) {
        val human = session.playerX.username ?: return
        val aiUser = "AI"
        recordsStore.updateResult(human, RecordsStore.Outcome.DRAW)
        recordsStore.updateResult(aiUser, RecordsStore.Outcome.DRAW)
    }

    private fun broadcastRoundEndPve(session: GameSession, winner: String) {
        val humanUser = session.playerX.username ?: "Player"
        val aiUser = "AI"

        val payload = when (winner) {
            "DRAW" -> """{"gameId":"${session.id}","winner":"DRAW","winnerUser":null,"loserUser":null}"""
            "X" -> """{"gameId":"${session.id}","winner":"X","winnerUser":"$humanUser","loserUser":"$aiUser"}"""
            "O" -> """{"gameId":"${session.id}","winner":"O","winnerUser":"$aiUser","loserUser":"$humanUser"}"""
            else -> """{"gameId":"${session.id}","winner":"$winner","winnerUser":null,"loserUser":null}"""
        }

        session.playerX.send(MessageType.ROUND_END, payload)
    }

    private fun endSessionPve(session: GameSession) {
        sessionsByClient.remove(session.playerX)
    }

    private fun broadcastState(session: GameSession, nextPlayerOverride: String? = null) {
        val boardJson = session.board.joinToString(prefix = "[", postfix = "]") { row ->
            row.joinToString(prefix = "[", postfix = "]") { cell ->
                """"${if (cell == ' ') "" else cell.toString()}""""
            }
        }

        val next = nextPlayerOverride ?: session.next.toString()
        val payload = """{"gameId":"${session.id}","board":$boardJson,"nextPlayer":"$next"}"""

        session.playerX.send(MessageType.GAME_STATE, payload)
        session.playerO?.send(MessageType.GAME_STATE, payload)
    }

    private fun isFull(board: Array<CharArray>): Boolean =
        board.all { row -> row.all { it != ' ' } }

    private fun checkWinner(b: Array<CharArray>): String? {
        val lines = mutableListOf<List<Char>>()
        for (r in 0..2) lines.add(listOf(b[r][0], b[r][1], b[r][2]))
        for (c in 0..2) lines.add(listOf(b[0][c], b[1][c], b[2][c]))
        lines.add(listOf(b[0][0], b[1][1], b[2][2]))
        lines.add(listOf(b[0][2], b[1][1], b[2][0]))

        for (line in lines) {
            if (line.all { it == 'X' }) return "X"
            if (line.all { it == 'O' }) return "O"
        }
        return null
    }
}