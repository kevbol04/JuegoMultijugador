package com.kevin.multijugador.util

import java.io.InputStream
import java.util.Properties

data class ServerConfig(
    val host: String,
    val port: Int,
    val maxClients: Int
)

object ConfigLoader {

    fun loadServerConfig(resourceName: String = "server.properties"): ServerConfig {
        val props = Properties()

        val input: InputStream = Thread.currentThread().contextClassLoader
            .getResourceAsStream(resourceName)
            ?: throw IllegalStateException("No se encontró $resourceName en src/main/resources")

        input.use { props.load(it) }

        val host = props.getProperty("server.host") ?: "localhost"
        val port = (props.getProperty("server.port") ?: "5678").toInt()
        val max = (props.getProperty("max.clients") ?: "10").toInt()

        return ServerConfig(host, port, max)
    }
}