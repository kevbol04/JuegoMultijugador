package com.kevin.multijugador.client

import kotlinx.coroutines.Job
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class ClientSession {

    enum class ClientState { MENU, QUEUE, IN_GAME }

    data class GameConfig(
        var boardSize: Int = 3,
        var rounds: Int = 3,
        var difficulty: String = "EASY",
        var timeLimit: Int = 30,
        var turbo: Boolean = false
    )

    val clientState = AtomicReference(ClientState.MENU)

    val mySymbol = AtomicReference<String?>(null)
    val lastState = AtomicReference<String?>(null)
    val usernameRef = AtomicReference<String?>(null)
    val configRef = AtomicReference(GameConfig())

    val nextPlayerRef = AtomicReference<String?>(null)

    val turnToken = AtomicLong(0L)
    val moveJobRef = AtomicReference<Job?>(null)

    val deferredTimeoutMsg = AtomicReference<String?>(null)
}