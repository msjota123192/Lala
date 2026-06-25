package com.example.mousetotouch.input

import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Lê o stream de eventos brutos do mouse físico via "getevent", executado
 * com privilégio de shell através do Shizuku.
 *
 * POR QUE ISSO FUNCIONA SEM MONITOR_INPUT:
 * Não estamos usando a API Java de alto nível do InputManager (que exige
 * a permissão de sistema MONITOR_INPUT, que o shell nunca tem). Em vez
 * disso, lemos diretamente o stdout do binário "getevent", que por sua
 * vez lê o nó de dispositivo bruto (/dev/input/eventX) usando permissão
 * de arquivo Unix — algo que o shell historicamente tem (é por isso que
 * "adb shell getevent" sempre funcionou sem root). Confirmado funcionando
 * no MIUI 14 (testado no device real).
 *
 * SOBRE O newProcess VIA REFLECTION:
 * Nas versões recentes da Shizuku-API, Shizuku.newProcess() foi marcado
 * como não-público (o time está migrando todo mundo pra UserService).
 * Por enquanto, chamamos via reflection — é o workaround confirmado pela
 * própria comunidade de devs do Shizuku (issue #276 do repositório).
 * Se isso parar de funcionar em uma atualização futura do Shizuku, o
 * caminho definitivo é migrar pra Shizuku.UserService (ver nota no final
 * do arquivo).
 */
class RawMouseReader(
    private val devicePath: String, // ex: "/dev/input/event8"
    private val onDelta: (dx: Int, dy: Int) -> Unit,
    private val onButton: (button: MouseButton, pressed: Boolean) -> Unit
) {
    enum class MouseButton { LEFT, RIGHT, MIDDLE }

    @Volatile private var running = false
    private var remoteProcess: Any? = null // na prática: rikka.shizuku.ShizukuRemoteProcess
    private var readerThread: Thread? = null

    fun start() {
        if (running) return
        running = true

        readerThread = Thread {
            try {
                remoteProcess = runShizukuProcess(arrayOf("getevent", "-l", devicePath))
                val inputStream = remoteProcess!!.javaClass
                    .getMethod("getInputStream")
                    .invoke(remoteProcess) as java.io.InputStream

                val reader = BufferedReader(InputStreamReader(inputStream))
                var pendingDx = 0
                var pendingDy = 0

                while (running) {
                    val line = reader.readLine() ?: break
                    val parts = line.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
                    if (parts.size < 3) continue

                    val type = parts[0]
                    val code = parts[1]
                    val value = parts[2]

                    when {
                        type == "EV_SYN" && code == "SYN_REPORT" -> {
                            if (pendingDx != 0 || pendingDy != 0) {
                                onDelta(pendingDx, pendingDy)
                                pendingDx = 0
                                pendingDy = 0
                            }
                        }
                        type == "EV_REL" && code == "REL_X" -> pendingDx += hexToSignedInt(value)
                        type == "EV_REL" && code == "REL_Y" -> pendingDy += hexToSignedInt(value)

                        type == "EV_KEY" && code == "BTN_MOUSE"  -> onButton(MouseButton.LEFT, value == "DOWN")
                        type == "EV_KEY" && code == "BTN_RIGHT"  -> onButton(MouseButton.RIGHT, value == "DOWN")
                        type == "EV_KEY" && code == "BTN_MIDDLE" -> onButton(MouseButton.MIDDLE, value == "DOWN")
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        readerThread?.isDaemon = true
        readerThread?.start()
    }

    fun stop() {
        running = false
        readerThread?.interrupt()
        try {
            remoteProcess?.javaClass?.getMethod("destroy")?.invoke(remoteProcess)
        } catch (_: Exception) { /* já encerrado, ignora */ }
    }

    /** Converte um hex de 32 bits (complemento de dois) pro Int com sinal correto. */
    private fun hexToSignedInt(hex: String): Int {
        return hex.toLong(16).toInt() // o overflow intencional já resolve o sinal
    }

    /**
     * Chama Shizuku.newProcess(cmd, env, dir) via reflection.
     * Workaround necessário porque o método foi ocultado em versões
     * recentes da Shizuku-API (ver issue #276 do RikkaApps/Shizuku-API).
     */
    private fun runShizukuProcess(cmd: Array<String>): Any {
        val shizukuClass = Class.forName("rikka.shizuku.Shizuku")
        val method = shizukuClass.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java
        )
        method.isAccessible = true
        return method.invoke(null, cmd, null, null)
            ?: throw IllegalStateException("Shizuku.newProcess retornou null — Shizuku está rodando e com permissão concedida?")
    }
}

/*
    NOTA SOBRE O FUTURO (UserService):

    Se uma atualização do Shizuku remover de vez o newProcess (mesmo via
    reflection), o caminho oficial recomendado é Shizuku.UserService:
    em vez de rodar um comando de texto, você sobe um processo separado
    rodando SEU PRÓPRIO código Kotlin/Java com privilégio de shell,
    conectado ao app principal via AIDL/Binder. É mais robusto (binário,
    não depende de parsear texto do getevent) mas exige configurar um
    serviço AIDL à parte. Por enquanto, pra prototipagem rápida, o
    newProcess via reflection já está validado funcionando no seu
    aparelho — migramos pra UserService depois, se necessário.
*/
