package com.translator.app

import android.content.Context
import android.content.SharedPreferences

/**
 * 管理 API 配置的存储和读取
 */
class AppConfig(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("translator_config", Context.MODE_PRIVATE)

    var apiUrl: String
        get() = prefs.getString("api_url", "https://api.deepseek.com/chat/completions") ?: ""
        set(value) = prefs.edit().putString("api_url", value).apply()

    var apiKey: String
        get() = prefs.getString("api_key", "sk-6e81f156077f43faa9bd4601feb9b421") ?: ""
        set(value) = prefs.edit().putString("api_key", value).apply()

    var apiModel: String
        get() = prefs.getString("api_model", "deepseek-chat") ?: ""
        set(value) = prefs.edit().putString("api_model", value).apply()

    var sourceLang: String
        get() = prefs.getString("source_lang", "英语") ?: "英语"
        set(value) = prefs.edit().putString("source_lang", value).apply()

    var targetLang: String
        get() = prefs.getString("target_lang", "中文") ?: "中文"
        set(value) = prefs.edit().putString("target_lang", value).apply()

    // 悬浮窗背景透明度 0~255, 0=全透明, 255=全不透明, 默认 128
    var overlayAlpha: Int
        get() = prefs.getInt("overlay_alpha", 128)
        set(value) = prefs.edit().putInt("overlay_alpha", value.coerceIn(0, 255)).apply()

    // 按键绑定：翻译键的 keyCode，0 表示未绑定
    var translateKeyCode: Int
        get() = prefs.getInt("translate_key_code", 0)
        set(value) = prefs.edit().putInt("translate_key_code", value).apply()

    var translateKeyName: String
        get() = prefs.getString("translate_key_name", "未绑定") ?: "未绑定"
        set(value) = prefs.edit().putString("translate_key_name", value).apply()

    // 按键绑定：清空键的 keyCode，0 表示未绑定
    var clearKeyCode: Int
        get() = prefs.getInt("clear_key_code", 0)
        set(value) = prefs.edit().putInt("clear_key_code", value).apply()

    var clearKeyName: String
        get() = prefs.getString("clear_key_name", "未绑定") ?: "未绑定"
        set(value) = prefs.edit().putString("clear_key_name", value).apply()

    val isConfigured: Boolean
        get() = apiKey.isNotBlank() && apiUrl.isNotBlank()

    companion object {
        val LANGUAGES = listOf("中文", "英语", "日语", "韩语", "法语", "德语", "西班牙语", "俄语", "阿拉伯语")
    }
}
