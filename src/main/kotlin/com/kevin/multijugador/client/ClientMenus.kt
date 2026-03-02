package com.kevin.multijugador.client

class ClientMenus(
    private val session: ClientSession,
    private val input: ConsoleInput
) {

    fun printMainMenu() {
        val cfgLocal = session.configRef.get()

        println("===== MENÚ PRINCIPAL =====")
        println("1. Nueva Partida PVP")
        println("2. Nueva Partida PVE")
        println("3. Ver Records")
        println("4. Configuración")
        println("5. Salir")
        println(
            "Config actual: tablero ${cfgLocal.boardSize}x${cfgLocal.boardSize}, " +
                    "mejor de ${cfgLocal.rounds}, IA ${cfgLocal.difficulty}, " +
                    "timeLimit ${cfgLocal.timeLimit}, turbo ${cfgLocal.turbo}"
        )
        print("Elige opción: ")
    }

    fun readOption(): String = input.takeNonBlank(session)

    fun configMenu() {
        while (true) {
            val cfg = session.configRef.get()

            println("\n===== CONFIGURACIÓN =====")
            println("1. Tamaño tablero (actual: ${cfg.boardSize}x${cfg.boardSize})")
            println("2. Número de partidas (al mejor de) (actual: ${cfg.rounds})")
            println("3. Dificultad IA PVE (actual: ${cfg.difficulty})")
            println("4. Tiempo por movimiento (seg) (actual: ${cfg.timeLimit})")
            println("5. Modo Turbo (actual: ${if (cfg.turbo) "ON" else "OFF"})")
            println("6. Volver al menú")
            print("Elige opción: ")

            when (input.takeNonBlank(session)) {
                "1" -> cfg.boardSize = askBoardSize()
                "2" -> cfg.rounds = askRounds()
                "3" -> cfg.difficulty = askDifficulty()
                "4" -> cfg.timeLimit = askTimeLimit(cfg.turbo)
                "5" -> {
                    cfg.turbo = !cfg.turbo
                    if (cfg.turbo) cfg.timeLimit = 10
                }
                "6" -> {
                    session.configRef.set(cfg)
                    println()
                    return
                }
                else -> println("Opción no válida.")
            }

            session.configRef.set(cfg)
        }
    }

    private fun askBoardSize(): Int {
        while (true) {
            println("\nTamaño tablero:")
            println("1. 3x3")
            println("2. 4x4")
            println("3. 5x5")
            print("Elige: ")

            return when (input.takeNonBlank(session)) {
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

    private fun askRounds(): Int {
        while (true) {
            println("\nMejor de:")
            println("1. 3")
            println("2. 5")
            println("3. 7")
            print("Elige: ")

            return when (input.takeNonBlank(session)) {
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

    private fun askDifficulty(): String {
        while (true) {
            println("\nDificultad IA:")
            println("1. EASY")
            println("2. MEDIUM")
            println("3. HARD")
            print("Elige: ")

            return when (input.takeNonBlank(session)) {
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

    private fun askTimeLimit(turbo: Boolean): Int {
        while (true) {
            if (turbo) {
                println("Turbo ON: el tiempo por turno es fijo a 10 segundos.")
                return 10
            }

            print("\nTiempo por movimiento en segundos (0 = sin límite): ")
            val v = input.takeNonBlank(session).toIntOrNull()

            if (v == null || v < 0) {
                println("Valor inválido.")
                continue
            }
            return v
        }
    }
}