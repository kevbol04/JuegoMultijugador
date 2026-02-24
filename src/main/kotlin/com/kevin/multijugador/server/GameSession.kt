package com.kevin.multijugador.server

import com.kevin.multijugador.protocol.Difficulty

enum class GameMode { PVP, PVE }

data class GameSession(
    val id: String,
    val mode: GameMode,
    val playerX: ClientConnection,
    val playerO: ClientConnection?,
    val difficulty: Difficulty? = null,
    var board: Array<CharArray>,
    var next: Char
)