package com.translator.app

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.translator.app.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var config: AppConfig

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        config = AppConfig(this)

        // 填充已保存的配置
        binding.etApiUrl.setText(config.apiUrl)
        binding.etApiKey.setText(config.apiKey)
        binding.etApiModel.setText(config.apiModel)

        // 设置语言下拉菜单
        val langAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, AppConfig.LANGUAGES)
        binding.spinnerSourceLang.setAdapter(langAdapter)
        binding.spinnerTargetLang.setAdapter(langAdapter)

        binding.spinnerSourceLang.setText(config.sourceLang, false)
        binding.spinnerTargetLang.setText(config.targetLang, false)

        // 保存按钮
        binding.btnSave.setOnClickListener {
            val url = binding.etApiUrl.text.toString().trim()
            val key = binding.etApiKey.text.toString().trim()
            val model = binding.etApiModel.text.toString().trim()
            val sourceLang = binding.spinnerSourceLang.text.toString()
            val targetLang = binding.spinnerTargetLang.text.toString()

            if (url.isBlank()) {
                binding.etApiUrl.error = "请输入 API URL"
                return@setOnClickListener
            }
            if (key.isBlank()) {
                binding.etApiKey.error = "请输入 API Key"
                return@setOnClickListener
            }
            if (model.isBlank()) {
                binding.etApiModel.error = "请输入模型名称"
                return@setOnClickListener
            }

            config.apiUrl = url
            config.apiKey = key
            config.apiModel = model
            config.sourceLang = sourceLang
            config.targetLang = targetLang

            Toast.makeText(this, "设置已保存", Toast.LENGTH_SHORT).show()
            finish()
        }

        // === 按键绑定 ===
        binding.btnBindTranslate.text = config.translateKeyName
        binding.btnBindClear.text = config.clearKeyName

        binding.btnBindTranslate.setOnClickListener {
            showKeyBindingDialog("绑定翻译键") { keyCode, keyName ->
                config.translateKeyCode = keyCode
                config.translateKeyName = keyName
                binding.btnBindTranslate.text = keyName
            }
        }

        binding.btnBindClear.setOnClickListener {
            showKeyBindingDialog("绑定清除键") { keyCode, keyName ->
                config.clearKeyCode = keyCode
                config.clearKeyName = keyName
                binding.btnBindClear.text = keyName
            }
        }

        binding.btnOpenAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    private fun showKeyBindingDialog(title: String, onKeyBound: (keyCode: Int, keyName: String) -> Unit) {
        val dialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage("请按下要绑定的按键（支持外接键盘/手柄按键）")
            .setNegativeButton("取消", null)
            .setNeutralButton("清除绑定") { _, _ ->
                onKeyBound(0, "未绑定")
            }
            .create()

        dialog.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && keyCode != KeyEvent.KEYCODE_BACK) {
                val keyName = KeyEvent.keyCodeToString(keyCode)
                    .removePrefix("KEYCODE_")
                onKeyBound(keyCode, keyName)
                dialog.dismiss()
                true
            } else {
                false
            }
        }
        dialog.show()
    }
}
