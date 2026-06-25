package com.example.mousetotouch

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import androidx.core.app.NotificationCompat
import com.example.mousetotouch.input.MouseToTouchAccessibilityService
import com.example.mousetotouch.input.RawMouseReader
import kotlin.math.roundToInt

/**
 * Serviço em foreground que junta as três peças:
 *  1. RawMouseReader  -> captura o mouse cru via Shizuku (sem limite de borda)
 *  2. lógica de "arrastar e recentralizar" (mesma idéia do LocalScript do Roblox,
 *     só que agora em nível de sistema, funciona em qualquer app)
 *  3. MouseToTouchAccessibilityService -> injeta o toque sintético no jogo
 */
class OverlayService : Service() {

    companion object {
        const val CHANNEL_ID = "mouse_to_touch_channel"
        const val NOTIFICATION_ID = 1
        const val EXTRA_DEVICE_PATH = "device_path"
    }

    private lateinit var windowManager: WindowManager
    private var reticleView: View? = null
    private var mouseReader: RawMouseReader? = null

    // Posição virtual do "dedo" (em pixels de tela)
    private var virtualX = 0f
    private var virtualY = 0f
    private var screenWidth = 0
    private var screenHeight = 0

    private val sensitivity = 1.2f
    private val edgeMargin = 120f

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val metrics = resources.displayMetrics
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        virtualX = screenWidth / 2f
        virtualY = screenHeight / 2f

        startForegroundNotification()
        addReticleOverlay()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val devicePath = intent?.getStringExtra(EXTRA_DEVICE_PATH) ?: "/dev/input/event8"
        startMouseCapture(devicePath)
        return START_STICKY
    }

    private fun startMouseCapture(devicePath: String) {
        mouseReader?.stop()
        mouseReader = RawMouseReader(
            devicePath = devicePath,
            onDelta = { dx, dy -> handleDelta(dx, dy) },
            onButton = { button, pressed -> handleButton(button, pressed) }
        )
        mouseReader?.start()
    }

    private fun handleDelta(dx: Int, dy: Int) {
        val accessibility = MouseToTouchAccessibilityService.instance ?: return // não habilitado ainda

        val nextX = virtualX + dx * sensitivity
        val nextY = virtualY + dy * sensitivity

        val nearEdge = nextX < edgeMargin || nextX > screenWidth - edgeMargin ||
                nextY < edgeMargin || nextY > screenHeight - edgeMargin

        if (nearEdge && accessibility.isDragInProgress()) {
            // Solta no ponto atual e recomeça no centro — igual um dedo
            // real faria ao levantar e tocar de novo a tela.
            accessibility.endDrag(virtualX, virtualY)
            virtualX = screenWidth / 2f
            virtualY = screenHeight / 2f
            accessibility.startDrag(virtualX, virtualY)
        } else {
            virtualX = nextX.coerceIn(0f, screenWidth.toFloat())
            virtualY = nextY.coerceIn(0f, screenHeight.toFloat())
            if (accessibility.isDragInProgress()) {
                accessibility.continueDrag(virtualX, virtualY)
            } else {
                accessibility.startDrag(virtualX, virtualY)
            }
        }

        updateReticlePosition()
    }

    private fun handleButton(button: RawMouseReader.MouseButton, pressed: Boolean) {
        // Botão esquerdo do mouse = clique/toque no jogo.
        // (Ajuste aqui se o seu jogo precisar de outro mapeamento.)
        if (button == RawMouseReader.MouseButton.LEFT && pressed) {
            MouseToTouchAccessibilityService.instance?.dispatchTap(virtualX, virtualY)
        }
        // TODO: mapear botão direito / scroll conforme a necessidade do jogo.
    }

    private fun addReticleOverlay() {
        val reticle = ImageView(this).apply {
            setBackgroundColor(Color.RED)
        }
        val size = 14
        val params = WindowManager.LayoutParams(
            size, size,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            // NOT_FOCUSABLE + NOT_TOUCHABLE: cosmético só, nunca rouba foco
            // de teclado nem intercepta toque — é só pra desenhar a mira.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = (screenWidth / 2f - size / 2f).roundToInt()
        params.y = (screenHeight / 2f - size / 2f).roundToInt()

        windowManager.addView(reticle, params)
        reticleView = reticle
    }

    private fun updateReticlePosition() {
        val view = reticleView ?: return
        val params = view.layoutParams as? WindowManager.LayoutParams ?: return
        params.x = (virtualX - 7f).roundToInt()
        params.y = (virtualY - 7f).roundToInt()
        windowManager.updateViewLayout(view, params)
    }

    private fun startForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Mouse to Touch", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Mouse to Touch ativo")
            .setContentText("Capturando mouse e traduzindo pra toque")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .build()
        startForeground(NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        mouseReader?.stop()
        reticleView?.let { runCatching { windowManager.removeView(it) } }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
