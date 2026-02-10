package com.kevin.multijugador.server

import com.kevin.multijugador.util.ConfigLoader
import kotlinx.coroutines.runBlocking

object ServerMain {
    @JvmStatic
    fun main(args: Array<String>) = runBlocking {
        val config = ConfigLoader.loadServerConfig()
        val recordsStore = RecordsStore()

        TcpServer(config, recordsStore).start()
    }
}