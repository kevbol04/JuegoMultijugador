package com.kevin.multijugador.server

import com.kevin.multijugador.protocol.MessageType
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class ServerGameService(
    private val recordsStore: RecordsStore
) {
    private val sessionsByClient = ConcurrentHashMap<ClientConnection, GameSession>()

    fun startPvpGame(a: ClientConnection, b: ClientConnection) {
        val session = GameSession(
            id = UUID.randomUUID().toString(),
            playerX = a,
            playerO = b,
            board = Array(3) { CharArray(3) { ' ' } },
            next = 'X'
        )

        sessionsByClient[a] = session
        sessionsByClient[b] = session

        a.send(MessageType.GAME_START, """{"gameId":"${session.id}","yourSymbol":"X"}""")
        b.send(MessageType.GAME_START, """{"gameId":"${session.id}","yourSymbol":"O"}""")

        broadcastState(session)
    }

    fun handleMove(client: ClientConnection, row: Int, col: Int) {
        val session = sessionsByClient[client] ?: run {
            client.send(MessageType.ERROR, """{"message":"No estás en una partida"}""")
            return
        }

        val symbol = if (client == session.playerX) 'X' else 'O'
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
            broadcastState(session, nextPlayerOverride = "")
            applyResultToRecords(session, winner)
            broadcastRoundEnd(session, winner)
            endSession(session)
            return
        }

        if (draw) {
            broadcastState(session, nextPlayerOverride = "")
            applyDrawToRecords(session)
            broadcastRoundEnd(session, "DRAW")
            endSession(session)
            return
        }

        session.next = if (session.next == 'X') 'O' else 'X'
        broadcastState(session)
    }

    private fun applyResultToRecords(session: GameSession, winner: String) {
        val xUser = session.playerX.username ?: return
        val oUser = session.playerO.username ?: return

        if (winner == "X") {
            recordsStore.updateResult(xUser, RecordsStore.Outcome.WIN)
            recordsStore.updateResult(oUser, RecordsStore.Outcome.LOSS)
        } else if (winner == "O") {
            recordsStore.updateResult(oUser, RecordsStore.Outcome.WIN)
            recordsStore.updateResult(xUser, RecordsStore.Outcome.LOSS)
        }
    }

    private fun applyDrawToRecords(session: GameSession) {
        val xUser = session.playerX.username ?: return
        val oUser = session.playerO.username ?: return
        recordsStore.updateResult(xUser, RecordsStore.Outcome.DRAW)
        recordsStore.updateResult(oUser, RecordsStore.Outcome.DRAW)
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
        session.playerO.send(MessageType.GAME_STATE, payload)
    }

    private fun broadcastRoundEnd(session: GameSession, winner: String) {
        val xUser = session.playerX.username ?: "PlayerX"
        val oUser = session.playerO.username ?: "PlayerO"

        val payload = when (winner) {
            "DRAW" -> """{"gameId":"${session.id}","winner":"DRAW","winnerUser":null,"loserUser":null}"""
            "X" -> """{"gameId":"${session.id}","winner":"X","winnerUser":"$xUser","loserUser":"$oUser"}"""
            "O" -> """{"gameId":"${session.id}","winner":"O","winnerUser":"$oUser","loserUser":"$xUser"}"""
            else -> """{"gameId":"${session.id}","winner":"$winner","winnerUser":null,"loserUser":null}"""
        }

        session.playerX.send(MessageType.ROUND_END, payload)
        session.playerO.send(MessageType.ROUND_END, payload)
    }

    private fun endSession(session: GameSession) {
        sessionsByClient.remove(session.playerX)
        sessionsByClient.remove(session.playerO)
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