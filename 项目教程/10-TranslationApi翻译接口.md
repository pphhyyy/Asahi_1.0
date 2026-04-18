# 🌐 TranslationApi —— 翻译 API 调用详解

## 这个文件是什么？

`TranslationApi.kt` 是翻译功能的"**翻译官**"，负责将 OCR 识别到的文字发送给大语言模型 API（如 DeepSeek、OpenAI），并获取翻译结果。

---

## 涉及的核心技术

| 技术 | 用途 |
|------|------|
| **OkHttp** | 发送 HTTP 网络请求 |
| **Gson** | 构建和解析 JSON 数据 |
| **Kotlin 协程** | 异步执行网络请求（不阻塞主线程）|
| **OpenAI API 格式** | 业界标准的大模型 API 调用格式 |

---

## 完整代码逐段解析

### 1. 类定义和初始化

```kotlin
class TranslationApi(private val config: AppConfig) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
```

- 接收 `AppConfig` 以读取 API 配置
- 创建 `OkHttpClient` 实例，设置超时时间：
  - 连接超时 30秒
  - 读取超时 60秒（翻译可能需要较长时间）
- 创建 `Gson` 实例用于 JSON 处理

> 💡 **OkHttp** 是 Square 公司开发的 HTTP 客户端库，是 Android 开发中最流行的网络请求库。

---

### 2. 翻译函数

```kotlin
suspend fun translate(text: String): Result<String> = withContext(Dispatchers.IO) {
    try {
        // ... 翻译逻辑
    } catch (e: Exception) {
        Result.failure(e)
    }
}
```

#### 关键字解释

| 关键字 | 含义 |
|------|------|
| `suspend` | 这是一个**挂起函数**，只能在协程中调用 |
| `Result<String>` | 返回一个结果包装器，可能是成功（包含翻译文字）或失败（包含错误信息）|
| `withContext(Dispatchers.IO)` | 切换到 IO 线程执行（网络请求不能在主线程执行）|

#### 什么是协程（Coroutine）？

协程是 Kotlin 处理异步操作的方式。简单理解：

```
主线程（界面线程）                IO 线程（后台线程）
     │                              │
     ├── 用户点击翻译                 │
     │                              │
     ├── launch {                   │
     │     val result = translate() ──→ 发送网络请求
     │     // 这里会"挂起"等待       │    等待 API 响应
     │                              │    接收响应数据
     │     // 响应回来后继续 ←─────    │
     │     显示翻译结果               │
     │   }                          │
     │                              │
     ├── 界面保持响应（不卡顿）        │
```

- 主线程负责 UI 更新
- 网络请求在 IO 线程执行
- 协程让异步代码写起来像同步代码

---

### 3. 构建 System Prompt

```kotlin
val sourceLang = config.sourceLang
val targetLang = config.targetLang

val systemPrompt = "你是一个专业翻译器。将用户输入的${sourceLang}文本翻译成${targetLang}。" +
    "只输出翻译结果，不要添加任何解释、注释或额外内容。" +
    "输入中可能包含 |||| 分隔符，表示不同的文字区域。" +
    "你必须保留所有 |||| 分隔符在输出中的对应位置，每个区域独立翻译。"
```

这个 System Prompt 非常关键，它指导大模型：
1. 角色定位：你是一个翻译器
2. 翻译方向：从 X 语言到 Y 语言
3. 输出规范：只输出译文，不要额外解释
4. 分隔符保留：`||||` 用于区分不同文字块，翻译后要对应保留

> `${sourceLang}` 是 Kotlin 的**字符串模板**，在字符串中嵌入变量值。

---

### 4. 构建请求体

```kotlin
val requestBody = JsonObject().apply {
    addProperty("model", config.apiModel)
    add("messages", gson.toJsonTree(listOf(
        mapOf("role" to "system", "content" to systemPrompt),
        mapOf("role" to "user", "content" to text)
    )))
    addProperty("temperature", 0.3)
    addProperty("max_tokens", 4096)
}
```

这构建了一个 **OpenAI 兼容格式**的 JSON 请求体：

```json
{
    "model": "deepseek-chat",
    "messages": [
        {
            "role": "system",
            "content": "你是一个专业翻译器..."
        },
        {
            "role": "user",
            "content": "Hello World\n||||\nGood Morning"
        }
    ],
    "temperature": 0.3,
    "max_tokens": 4096
}
```

| 参数 | 含义 |
|------|------|
| `model` | 使用的模型名称 |
| `messages` | 对话消息列表 |
| `messages[0]` (system) | 系统提示，定义 AI 的行为 |
| `messages[1]` (user) | 用户输入（要翻译的内容）|
| `temperature` | 随机性（0.3 = 较确定性，翻译不需要太多创造力）|
| `max_tokens` | 最大输出长度 |

> 💡 **`apply {}` 是什么？**
> Kotlin 的作用域函数，在 `{}` 内部可以直接调用对象的方法。这里创建 `JsonObject` 后立即配置它。

---

### 5. 发送 HTTP 请求

```kotlin
val request = Request.Builder()
    .url(config.apiUrl)
    .addHeader("Authorization", "Bearer ${config.apiKey}")
    .addHeader("Content-Type", "application/json")
    .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
    .build()

val response = client.newCall(request).execute()
```

#### HTTP 请求结构

```
POST https://api.deepseek.com/chat/completions
Headers:
  Authorization: Bearer sk-xxx...
  Content-Type: application/json
Body:
  {"model": "deepseek-chat", "messages": [...], ...}
```

| 部分 | 含义 |
|------|------|
| `POST` | HTTP 方法（发送数据用 POST）|
| `url` | API 地址 |
| `Authorization: Bearer xxx` | 身份验证（API Key）|
| `Content-Type: application/json` | 请求体格式为 JSON |

---

### 6. 处理响应

```kotlin
val body = response.body?.string() 
    ?: return@withContext Result.failure(Exception("空响应"))

if (!response.isSuccessful) {
    return@withContext Result.failure(Exception("API 错误 ${response.code}: $body"))
}

val json = gson.fromJson(body, JsonObject::class.java)
val content = json
    .getAsJsonArray("choices")
    ?.get(0)?.asJsonObject
    ?.getAsJsonObject("message")
    ?.get("content")?.asString
    ?: return@withContext Result.failure(Exception("解析响应失败"))

Result.success(content.trim())
```

#### API 响应格式

```json
{
    "choices": [
        {
            "message": {
                "role": "assistant",
                "content": "你好世界\n||||\n早上好"
            }
        }
    ]
}
```

解析路径：`choices[0].message.content`

#### 错误处理

代码处理了三种错误情况：
1. **响应体为空** → "空响应"
2. **HTTP 状态码不成功**（如 401、500）→ "API 错误 + 状态码"
3. **JSON 解析失败**（格式不对）→ "解析响应失败"
4. **网络异常**（如超时、无网络）→ 被外层 `catch` 捕获

---

## `Result` 类型

`Result<T>` 是 Kotlin 标准库的类型，封装了操作的成功或失败：

```kotlin
// 创建
Result.success("翻译结果")    // 成功
Result.failure(Exception("错误"))  // 失败

// 使用
result.onSuccess { translated ->
    // 成功时执行
    显示翻译结果(translated)
}
result.onFailure { error ->
    // 失败时执行
    显示错误信息(error.message)
}
```

---

## 整体流程图

```
OCR 识别结果（多个文字块）
    │
    ├── "Hello World"
    ├── "Good Morning"
    └── "Thank you"
        │
        │ joinToString("\n||||\n")
        ▼
"Hello World\n||||\nGood Morning\n||||\nThank you"
        │
        │ 构建 JSON 请求体
        ▼
┌─────────────────────────────────────────┐
│ POST /chat/completions                  │
│ Authorization: Bearer sk-xxx            │
│                                         │
│ { "model": "deepseek-chat",            │
│   "messages": [...],                    │
│   "temperature": 0.3 }                 │
└──────────────────┬──────────────────────┘
                   │
                   │ HTTP 请求 → API 服务器
                   │
                   ▼
┌─────────────────────────────────────────┐
│ { "choices": [{                         │
│     "message": {                        │
│       "content": "你好世界\n||||\n..."    │
│     }                                   │
│   }] }                                  │
└──────────────────┬──────────────────────┘
                   │
                   │ 解析 JSON → 提取 content
                   ▼
"你好世界\n||||\n早上好\n||||\n谢谢"
        │
        │ split("||||")
        ▼
    ├── "你好世界"
    ├── "早上好"
    └── "谢谢"
        │
        │ 一一对应回图片中的位置
        ▼
    渲染到原图上
```

---

## 小结

| 要点 | 说明 |
|------|------|
| `suspend` 函数 | 协程挂起函数，异步不阻塞 |
| `Dispatchers.IO` | 在 IO 线程执行网络请求 |
| OkHttp | 发送 HTTP POST 请求 |
| OpenAI API 格式 | `messages` 数组 + `system`/`user` 角色 |
| `||||` 分隔符 | 保持文字块和翻译结果的对应关系 |
| `Result<T>` | 表示成功或失败的结果包装 |
| Gson | JSON 的构建和解析 |
