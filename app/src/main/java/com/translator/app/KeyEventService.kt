package com.translator.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.translator.app.overlay.OverlayService

class KeyEventService : AccessibilityService() {

    private val TAG = "KeyEventService"

    override fun onServiceConnected() {
        val info = AccessibilityServiceInfo().apply {
            flags = AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
            eventTypes = 0 // 不需要处理 AccessibilityEvent
        }
        serviceInfo = info
        Log.d(TAG, "无障碍按键服务已连接")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 不处理
    }

    override fun onInterrupt() {
        // 不处理
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return false

        val config = AppConfig(this)
        val service = OverlayService.getInstance() ?: return false

        val translateKey = config.translateKeyCode
        val clearKey = config.clearKeyCode

        if (translateKey != 0 && event.keyCode == translateKey) {
            service.triggerTranslate()
            return true
        }

        if (clearKey != 0 && event.keyCode == clearKey) {
            service.clearTranslation()
            return true
        }

        return false
    }
}
