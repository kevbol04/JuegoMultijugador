import com.kevin.multijugador.server.ServerMain

fun main() {
    println("1) Iniciar servidor")
    println("2) (Luego) Iniciar cliente")
    print("Elige opción: ")

    when (readLine()?.trim()) {
        "1" -> ServerMain.main(emptyArray())
        else -> println("De momento solo está implementado el servidor")
    }
}