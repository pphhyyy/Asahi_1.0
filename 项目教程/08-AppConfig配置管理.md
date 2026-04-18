# 💾 AppConfig —— 配置管理详解

## 这个文件是什么？

`AppConfig.kt` 是一个**配置管理类**，负责读取和保存 App 的所有配置项。它是 `SharedPreferences` 的封装。

---

## 什么是 SharedPreferences？

**SharedPreferences** 是 Android 提供的一种**轻量级数据存储**方式，以**键值对**（Key-Value）的形式将数据保存在设备本地。

打个比方：它就像一个"**小本子**"，你可以往里面写东西，关掉 App 后内容不会丢失，下次打开还能读到。

特点：
- 适合存储简单数据（字符串、数字、布尔值）
- 数据保存在设备本地 XML 文件中
- 速度快，但不适合存大量数据
- App 卸载后会被清除

---

## 完整代码逐段解析

### 1. 类定义和初始化

```kotlin
class AppConfig(context: Context) {

    private val prefs: SharedPreferences = 
        context.getSharedPreferences("translator_config", Context.MODE_PRIVATE)
```

- `context: Context`：Android 的上下文对象，Activity 和 Service 都是 Context
- `getSharedPreferences("translator_config", Context.MODE_PRIVATE)`：
  - `"translator_config"`：文件名（数据存储的文件名）
  - `MODE_PRIVATE`：私有模式，只有本 App 能访问

---

### 2. API 配置项

```kotlin
var apiUrl: String
    get() = prefs.getString("api_url", "https://api.deepseek.com/chat/completions") ?: ""
    set(value) = prefs.edit().putString("api_url", value).apply()

var apiKey: String
    get() = prefs.getString("api_key", "sk-6e81f156077f43faa9bd4601feb9b421") ?: ""
    set(value) = prefs.edit().putString("api_key", value).apply()

var apiModel: String
    get() = prefs.getString("api_model", "deepseek-chat") ?: ""
    set(value) = prefs.edit().putString("api_model", value).apply()
```

#### Kotlin 属性的 getter/setter

Kotlin 允许为属性自定义 `get()` 和 `set()` 方法。当你读取 `config.apiUrl` 时会调用 `get()`，赋值时会调用 `set()`。

```kotlin
// 读取（自动调用 get()）
val url = config.apiUrl  // 实际执行: prefs.getString("api_url", 默认值)

// 写入（自动调用 set()）
config.apiUrl = "https://..."  // 实际执行: prefs.edit().putString("api_url", 值).apply()
```

#### SharedPreferences 的读写操作

| 操作 | 方法 | 示例 |
|------|------|------|
| 读取字符串 | `prefs.getString(key, default)` | `prefs.getString("api_url", "")` |
| 读取整数 | `prefs.getInt(key, default)` | `prefs.getInt("alpha", 128)` |
| 写入字符串 | `prefs.edit().putString(key, value).apply()` | ... |
| 写入整数 | `prefs.edit().putInt(key, value).apply()` | ... |

- `edit()` 开启编辑模式
- `putXxx()` 写入数据
- `apply()` 异步保存（不阻塞当前线程）

> 💡 还有一个 `commit()` 方法是同步保存的，但因为会阻塞主线程，一般推荐用 `apply()`。

---

### 3. 语言配置

```kotlin
var sourceLang: String
    get() = prefs.getString("source_lang", "英语") ?: "英语"
    set(value) = prefs.edit().putString("source_lang", value).apply()

var targetLang: String
    get() = prefs.getString("target_lang", "中文") ?: "中文"
    set(value) = prefs.edit().putString("target_lang", value).apply()
```

默认值：源语言"英语"，目标语言"中文"。

> `?: "英语"` 是 Kotlin 的**空值合并运算符**（Elvis 操作符）。`getString()` 可能返回 null，如果返回 null 就用 "英语" 代替。

---

### 4. 悬浮窗透明度

```kotlin
var overlayAlpha: Int
    get() = prefs.getInt("overlay_alpha", 128)
    set(value) = prefs.edit().putInt("overlay_alpha", value.coerceIn(0, 255)).apply()
```

- 取值范围：0（全透明）~ 255（全不透明）
- 默认值：128（50% 透明）
- `coerceIn(0, 255)`：确保值在 0~255 范围内（防止越界）

> 💡 `coerceIn()` 是 Kotlin 标准库的函数，相当于 `Math.max(0, Math.min(255, value))`。

---

### 5. 按键绑定配置

```kotlin
var translateKeyCode: Int
    get() = prefs.getInt("translate_key_code", 0)
    set(value) = prefs.edit().putInt("translate_key_code", value).apply()

var translateKeyName: String
    get() = prefs.getString("translate_key_name", "未绑定") ?: "未绑定"
    set(value) = prefs.edit().putString("translate_key_name", value).apply()

var clearKeyCode: Int
    get() = prefs.getInt("clear_key_code", 0)
    set(value) = prefs.edit().putInt("clear_key_code", value).apply()

var clearKeyName: String
    get() = prefs.getString("clear_key_name", "未绑定") ?: "未绑定"
    set(value) = prefs.edit().putString("clear_key_name", value).apply()
```

每个按键绑定存两个值：
- `keyCode`：按键的数字代码（如 29 代表 A 键），0 表示未绑定
- `keyName`：按键的可读名称（如 "A"），"未绑定"表示没有绑定

---

### 6. 状态检查

```kotlin
val isConfigured: Boolean
    get() = apiKey.isNotBlank() && apiUrl.isNotBlank()
```

只有 `apiKey` 和 `apiUrl` 都不为空时，才认为 API 已配置。这是一个**只读属性**（只有 `get()`，没有 `set()`）。

---

### 7. 语言列表常量

```kotlin
companion object {
    val LANGUAGES = listOf(
        "中文", "英语", "日语", "韩语", 
        "法语", "德语", "西班牙语", "俄语", "阿拉伯语"
    )
}
```

定义了支持的语言列表，在设置界面的下拉菜单中使用。放在 `companion object` 中表示这是一个类级别的常量（所有实例共享）。

---

## 设计模式分析

### 为什么不直接在每个 Activity 中读写 SharedPreferences？

直接读写虽然可以，但会导致：
1. **键名分散**：`"api_url"` 这样的字符串散落在各处，容易打错字
2. **类型不安全**：忘记是 `getString` 还是 `getInt`
3. **修改困难**：想改默认值要改多个地方

封装成 `AppConfig` 后：
- 所有配置集中管理
- 使用 Kotlin 属性语法，像访问普通变量一样读写
- 默认值只定义一次
- 值域检查（如 `coerceIn`）在一处实现

---

## 数据在哪里存储？

SharedPreferences 的数据以 XML 文件的形式存储在设备上：

```
/data/data/com.translator.app/shared_prefs/translator_config.xml
```

文件内容大致如下：

```xml
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <string name="api_url">https://api.deepseek.com/chat/completions</string>
    <string name="api_key">sk-xxx</string>
    <string name="api_model">deepseek-chat</string>
    <string name="source_lang">英语</string>
    <string name="target_lang">中文</string>
    <int name="overlay_alpha" value="128" />
    <int name="translate_key_code" value="0" />
    <string name="translate_key_name">未绑定</string>
</map>
```

> ⚠️ 这个文件在未Root的手机上无法直接查看。调试时可以通过 Android Studio 的 **Device File Explorer** 查看。

---

## 小结

| 要点 | 说明 |
|------|------|
| SharedPreferences | Android 的轻量级键值对存储 |
| 自定义 getter/setter | 读写自动操作 SharedPreferences |
| `apply()` | 异步保存（推荐） |
| `coerceIn()` | 限制数值范围 |
| `?:` Elvis 操作符 | 处理 null 值 |
| `companion object` | 类级别的静态常量 |
| 封装的好处 | 集中管理、类型安全、减少出错 |
