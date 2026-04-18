package com.translator.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.translator.app.databinding.ActivityMainBinding
import com.translator.app.overlay.OverlayService

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var config: AppConfig
    private var isServiceRunning = false

    companion object {
        private const val TAG = "MainActivity"
        private const val REQUEST_MEDIA_PROJECTION = 1001
        private const val REQUEST_OVERLAY_PERMISSION = 1002
        private const val REQUEST_NOTIFICATION_PERMISSION = 1003

        // 通过静态变量传递截屏授权数据，在 Service 启动前台后再创建 MediaProjection
        var pendingResultCode: Int = Activity.RESULT_CANCELED
        var pendingResultData: Intent? = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        config = AppConfig(this)

        updateUI()

        binding.btnToggle.setOnClickListener {
            if (isServiceRunning) {
                stopOverlayService()
            } else {
                startOverlayFlow()
            }
        }

        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // 透明度滑块
        binding.seekBarAlpha.progress = config.overlayAlpha
        updateAlphaLabel(config.overlayAlpha)
        binding.seekBarAlpha.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                updateAlphaLabel(progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val value = seekBar?.progress ?: 128
                config.overlayAlpha = value
            }
        })
    }

    private fun updateAlphaLabel(alpha: Int) {
        val percent = (alpha * 100 / 255)
        binding.tvAlphaValue.text = "${percent}%"
    }

    override fun onResume() {
        super.onResume()
        updateUI()
    }

    private fun updateUI() {
        binding.tvApiStatus.text = if (config.isConfigured) {
            "✅ API 已配置: ${config.apiModel}"
        } else {
            "⚠️ 请先在设置中配置 API"
        }

        binding.btnToggle.text = if (isServiceRunning) {
            getString(R.string.stop_overlay)
        } else {
            getString(R.string.start_overlay)
        }
    }

    private fun startOverlayFlow() {
        if (!config.isConfigured) {
            Toast.makeText(this, "请先在设置中配置 API Key", Toast.LENGTH_LONG).show()
            startActivity(Intent(this, SettingsActivity::class.java))
            return
        }

        // 1. 检查通知权限 (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    REQUEST_NOTIFICATION_PERMISSION
                )
                return
            }
        }

        // 2. 检查悬浮窗权限
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, getString(R.string.grant_overlay_permission), Toast.LENGTH_LONG).show()
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivityForResult(intent, REQUEST_OVERLAY_PERMISSION)
            return
        }

        // 3. 请求截屏权限
        requestScreenCapture()
    }

    private fun requestScreenCapture() {
        val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(projectionManager.createScreenCaptureIntent(), REQUEST_MEDIA_PROJECTION)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        when (requestCode) {
            REQUEST_MEDIA_PROJECTION -> {
                Log.d(TAG, "onActivityResult: resultCode=$resultCode, data=$data")
                if (resultCode == Activity.RESULT_OK && data != null) {
                    // 保存授权数据到静态变量，等 Service 启动前台后再创建 MediaProjection
                    pendingResultCode = resultCode
                    pendingResultData = data.clone() as Intent
                    startOverlayService()
                } else {
                    Log.e(TAG, "用户拒绝截屏权限或数据为空")
                    Toast.makeText(this, "需要截屏权限才能进行 OCR 识别", Toast.LENGTH_LONG).show()
                }
            }
            REQUEST_OVERLAY_PERMISSION -> {
                if (Settings.canDrawOverlays(this)) {
                    requestScreenCapture()
                } else {
                    Toast.makeText(this, "需要悬浮窗权限", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_NOTIFICATION_PERMISSION) {
            // 无论是否授予通知权限，都继续流程
            startOverlayFlow()
        }
    }

    private fun startOverlayService() {
        val serviceIntent = Intent(this, OverlayService::class.java)
        startForegroundService(serviceIntent)
        isServiceRunning = true
        updateUI()
        Toast.makeText(this, "翻译悬浮窗已启动", Toast.LENGTH_SHORT).show()
    }

    private fun stopOverlayService() {
        OverlayService.stopService()
        stopService(Intent(this, OverlayService::class.java))
        isServiceRunning = false
        updateUI()
        Toast.makeText(this, "翻译悬浮窗已关闭", Toast.LENGTH_SHORT).show()
    }
}
