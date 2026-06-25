package com.example.mousetotouch.input

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent

/**
 * Injeta toques sintéticos no app em primeiro plano usando a API PÚBLICA
 * de Acessibilidade do Android (AccessibilityService.dispatchGesture).
 *
 * Essa é a mesma abordagem documentada publicamente pelo concorrente GG
 * Mouse Pro: usar Acessibilidade pra criar uma camada de toque virtual,
 * em vez de injeção via InputManager (que exigiria MONITOR_INPUT/
 * INJECT_EVENTS — permissões que o shell não tem completamente, como já
 * confirmamos).
 *
 * IMPORTANTE: diferente da captura do mouse (que precisa de Shizuku),
 * essa parte só precisa que o usuário habilite o serviço em:
 * Configurações > Acessibilidade > MouseToTouch. É uma permissão normal,
 * concedida pelo próprio usuário, sem Shizuku/root.
 */
class MouseToTouchAccessibilityService : AccessibilityService() {

    companion object {
        // Referência estática simples pro resto do app conseguir chamar
        // os métodos sem precisar de bind/AIDL.
        var instance: MouseToTouchAccessibilityService? = null
            private set
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    // Controla a continuidade do "dedo virtual" — precisamos saber se já
    // existe um toque em andamento pra decidir entre iniciar um novo
    // (ACTION_DOWN implícito) ou continuar o atual (willContinue=true).
    private var dragInProgress = false
    private var lastStrokeId: GestureDescription.StrokeDescription? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Não precisamos reagir a eventos de acessibilidade — só usamos
        // dispatchGesture — mas o método precisa existir (classe abstrata).
    }

    override fun onInterrupt() {}

    /**
     * Inicia um novo "dedo virtual" numa posição (equivalente a um
     * ACTION_DOWN de toque). Chame uma vez ao começar o drag de câmera.
     */
    fun startDrag(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        // duration curta + willContinue=true: sinaliza que esse toque vai
        // ser estendido por chamadas futuras de continueDrag(), em vez de
        // ser solto imediatamente.
        val stroke = GestureDescription.StrokeDescription(
            path, /* startTime = */ 0, /* duration = */ 16, /* willContinue = */ true
        )
        lastStrokeId = stroke
        dragInProgress = true
        dispatch(stroke)
    }

    /**
     * Estende o drag em andamento até uma nova posição. Chame
     * continuamente conforme o mouse se move (já traduzido pelo
     * RawMouseReader + acumulador de posição virtual).
     */
    fun continueDrag(x: Float, y: Float) {
        val previous = lastStrokeId ?: return
        val path = Path().apply { moveTo(x, y) }
        val stroke = previous.continueStroke(
            path, /* startTime = */ 0, /* duration = */ 16, /* willContinue = */ true
        )
        lastStrokeId = stroke
        dispatch(stroke)
    }

    /**
     * Solta o "dedo virtual" (equivalente a um ACTION_UP). Chame quando
     * o jogador soltar o botão de "olhar" ou quando recentralizarmos o
     * cursor virtual pra evitar a borda da tela.
     */
    fun endDrag(x: Float, y: Float) {
        val previous = lastStrokeId
        val path = Path().apply { moveTo(x, y) }
        val stroke = if (previous != null) {
            previous.continueStroke(path, 0, 16, false) // willContinue = false → solta
        } else {
            GestureDescription.StrokeDescription(path, 0, 16, false)
        }
        dragInProgress = false
        lastStrokeId = null
        dispatch(stroke)
    }

    fun isDragInProgress(): Boolean = dragInProgress

    /** Toque simples (clique único) numa coordenada — ex: botão esquerdo do mouse. */
    fun dispatchTap(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 50)
        dispatch(stroke)
    }

    private fun dispatch(stroke: GestureDescription.StrokeDescription) {
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        mainHandler.post {
            dispatchGesture(gesture, null, null)
        }
    }
}

/*
    NOTA HONESTA SOBRE TIMING:

    Os valores de "duration" (16ms acima, ~1 frame a 60fps) são um ponto
    de partida razoável, mas a responsividade real do "olhar" via
    dispatchGesture pode ter uma latência perceptível comparada a um
    toque real — isso é uma limitação conhecida dessa API (ela foi
    desenhada pra automação de testes/acessibilidade, não pra input de
    jogo em tempo real). Espere precisar ajustar esse valor e a forma de
    "debounce" das chamadas continueDrag() testando direto no Roblox.
*/
