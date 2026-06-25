package com.example.mousetotouch.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import com.example.mousetotouch.OverlayService
import com.example.mousetotouch.databinding.ActivityMainBinding
import rikka.shizuku.Shizuku

/**
 * Tela única: concede as 3 permissões necessárias (Shizuku, overlay,
 * Acessibilidade) e inicia o OverlayService.
 *
 * NOTA: a superfície da API do Shizuku muda entre versões (vimos isso na
 * prática com o newProcess). Os métodos abaixo seguem o padrão do app de
 * demonstração oficial (RikkaApps/Shizuku-API) — se algo não compilar
 * por causa de uma atualização da lib, vale checar o demo atual no
 * GitHub antes de tentar outra coisa.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val shizukuPermissionListener =
        Shizuku.OnRequestPermissionResultListener { _, _ -> updateShizukuStatus() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnShizuku.setOnClickListener { requestShizukuPermission() }
        binding.btnOverlay.setOnClickListener { requestOverlayPermission() }
        binding.btnAccessibility.setOnClickListener { openAccessibilitySettings() }
        binding.btnStart.setOnClickListener { startOverlayService() }

        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
        updateShizukuStatus()
    }

    override fun onDestroy() {
        super.onDestroy()
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
    }

    private fun requestShizukuPermission() {
        try {
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                updateShizukuStatus()
            } else {
                Shizuku.requestPermission(0)
            }
        } catch (e: Exception) {
            binding.tvStatus.text = "Shizuku não está rodando. Abra o app Shizuku primeiro."
        }
    }

    private fun updateShizukuStatus() {
        val granted = try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            false
        }
        binding.tvStatus.text = if (granted) {
            "Shizuku: permissão concedida ✅"
        } else {
            "Shizuku: sem permissão ❌ (abra o app Shizuku e ative)"
        }
    }

    private fun requestOverlayPermission() {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        }
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun startOverlayService() {
        val devicePath = binding.editDevicePath.text.toString().ifBlank { "/dev/input/event8" }
        val intent = Intent(this, OverlayService::class.java).apply {
            putExtra(OverlayService.EXTRA_DEVICE_PATH, devicePath)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }
}
