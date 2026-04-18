# ⚙️ SettingsActivity —— 设置界面详解

## 这个文件是什么？

`SettingsActivity.kt` 是 App 的设置界面，用户在这里配置：
1. **API URL**（翻译服务的地址）
2. **API Key**（访问密钥）
3. **模型名称**（使用哪个翻译模型）
4. **源语言 / 目标语言**（从什么语言翻译到什么语言）
5. **快捷键绑定**（翻译键和清除键）

---

## 完整代码逐段解析

### 1. 类定义

```kotlin
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var config: AppConfig
```

和 MainActivity 一样，使用 ViewBinding 和 AppConfig。

---

### 2. `onCreate()` —— 初始化

```kotlin
override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    binding = ActivitySettingsBinding.inflate(layoutInflater)
    setContentView(binding.root)

    config = AppConfig(this)

    // 填充已保存的配置
    binding.etApiUrl.setText(config.apiUrl)
    binding.etApiKey.setText(config.apiKey)
    binding.etApiModel.setText(config.apiModel)
```

进入设置页面时，从 `AppConfig` 中读取已保存的值，填充到输入框中。

> 💡 `setText()` 是 EditText 的方法，用于设置输入框的内容。

---

### 3. 语言下拉菜单

```kotlin
val langAdapter = ArrayAdapter(
    this,
    android.R.layout.simple_dropdown_item_1line,
    AppConfig.LANGUAGES
)
binding.spinnerSourceLang.setAdapter(langAdapter)
binding.spinnerTargetLang.setAdapter(langAdapter)

binding.spinnerSourceLang.setText(config.sourceLang, false)
binding.spinnerTargetLang.setText(config.targetLang, false)
```

#### 这段代码做了什么？

1. 创建一个 `ArrayAdapter`（数组适配器）—— 将数据列表转换成下拉菜单的选项
2. 数据来源是 `AppConfig.LANGUAGES`：`["中文", "英语", "日语", "韩语", "法语", "德语", "西班牙语", "俄语", "阿拉伯语"]`
3. 将适配器绑定到两个下拉菜单（源语言和目标语言）
4. 设置默认选中的值（`false` 参数表示不触发过滤）

#### 什么是 Adapter？

**Adapter** 是 Android 中的一个核心概念。它是**数据和界面之间的桥梁**：

```
数据（List<String>）  →  Adapter  →  界面（下拉菜单）
```

`ArrayAdapter` 是最简单的适配器，直接将字符串数组转换为列表项。

---

### 4. 保存按钮

```kotlin
binding.btnSave.setOnClickListener {
    val url = binding.etApiUrl.text.toString().trim()
    val key = binding.etApiKey.text.toString().trim()
    val model = binding.etApiModel.text.toString().trim()
    val sourceLang = binding.spinnerSourceLang.text.toString()
    val targetLang = binding.spinnerTargetLang.text.toString()

    // 输入验证
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

    // 保存到配置
    config.apiUrl = url
    config.apiKey = key
    config.apiModel = model
    config.sourceLang = sourceLang
    config.targetLang = targetLang

    Toast.makeText(this, "设置已保存", Toast.LENGTH_SHORT).show()
    finish()  // 关闭当前界面，返回主界面
}
```

#### 逐步分析

1. **获取输入值**：`.text.toString().trim()` 获取输入框文字并去除首尾空格
2. **输入验证**：检查必填项是否为空
   - `isBlank()` 检查字符串是否为空或只有空格
   - `binding.etApiUrl.error = "..."` 在输入框下方显示红色错误提示
   - `return@setOnClickListener` 提前终止 lambda 函数（Lambda 中不能直接 `return`）
3. **保存配置**：将值存入 `AppConfig`（内部使用 SharedPreferences）
4. **`finish()`**：关闭当前 Activity，返回到上一个界面（MainActivity）

---

### 5. 快捷键绑定

```kotlin
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
```

点击按钮时，弹出对话框让用户按下要绑定的按键。

#### `{ keyCode, keyName -> ... }` 是什么？

这是 Kotlin 的 **Lambda 表达式**（匿名函数）。`showKeyBindingDialog` 接受一个回调函数，当用户按下按键后，这个回调会被调用，并传入按键的代码和名称。

---

### 6. 按键绑定对话框

```kotlin
private fun showKeyBindingDialog(
    title: String,
    onKeyBound: (keyCode: Int, keyName: String) -> Unit
) {
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
```

#### 逐步分析

1. **`AlertDialog.Builder`**：Android 的标准对话框构造器
   - `setTitle()`：标题
   - `setMessage()`：提示内容
   - `setNegativeButton("取消", null)`：取消按钮（`null` 表示点击后自动关闭）
   - `setNeutralButton("清除绑定")`：清除已绑定的按键

2. **`setOnKeyListener`**：监听对话框中的按键事件
   - `KeyEvent.ACTION_DOWN`：只响应按键按下（忽略松开事件）
   - `keyCode != KeyEvent.KEYCODE_BACK`：排除返回键（返回键应该关闭对话框）
   - `KeyEvent.keyCodeToString(keyCode)`：将按键代码转为可读名称（如 "KEYCODE_A" → 去掉前缀后为 "A"）
   - `dialog.dismiss()`：关闭对话框

3. **回调**：`onKeyBound(keyCode, keyName)` 将结果传回调用者

---

### 7. 打开无障碍服务设置

```kotlin
binding.btnOpenAccessibility.setOnClickListener {
    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
}
```

直接跳转到系统的无障碍服务设置页面，让用户手动开启本 App 的无障碍服务。

> 💡 无障碍服务出于安全考虑，**必须由用户在系统设置中手动开启**，App 无法自动开启。

---

## 涉及的 Android 核心概念

### `AlertDialog` —— 对话框

Android 提供了多种对话框，`AlertDialog` 是最常用的一种：

```kotlin
AlertDialog.Builder(context)
    .setTitle("标题")
    .setMessage("内容")
    .setPositiveButton("确定") { _, _ -> /* 确定操作 */ }
    .setNegativeButton("取消", null)
    .show()
```

三种按钮：
- `Positive`（肯定）：通常用于"确定"
- `Negative`（否定）：通常用于"取消"
- `Neutral`（中性）：额外操作

### `finish()` —— 关闭界面

调用 `finish()` 会销毁当前 Activity，返回到之前的界面。就像浏览器的"后退"按钮。

### 输入验证

在保存数据之前检查用户输入是否合法。这是一个基本的开发规范：
- 必填项是否为空
- 格式是否正确
- 不合法时显示错误提示，阻止保存

---

## 小结

| 功能 | 实现方式 |
|------|---------|
| 读取/保存配置 | `AppConfig`（SharedPreferences）|
| 下拉菜单 | `AutoCompleteTextView` + `ArrayAdapter` |
| 输入验证 | `isBlank()` 检查 + `error` 属性提示 |
| 按键绑定 | `AlertDialog` + `setOnKeyListener` |
| 跳转系统设置 | `Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)` |
| 关闭页面 | `finish()` |
