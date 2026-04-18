# 🪟 OverlayService —— 悬浮窗核心服务详解

## 这个文件是什么？

`OverlayService.kt` 是整个 App 中**最核心、最复杂的文件**（约 900 行代码）。它是一个 Android **前台服务（Foreground Service）**，负责：

1. 在屏幕上创建和管理**悬浮窗**
2. 使用 MediaProjection 进行**屏幕截图**
3. 使用 ML Kit 进行 **OCR 文字识别**
4. 调用 TranslationApi 进行**翻译**
5. 在原图上**渲染翻译结果**
6. 支持**编辑模式**（移动和调整窗口大小）

我们将按功能模块逐一讲解。

---

## 一、Service 的基础结构

### 什么是 Service？

Service 是 Android 的四大组件之一，用于在**后台执行长时间运行的操作**。它没有界面，但可以通过 WindowManager 创建悬浮窗。

### 什么是前台服务？

普通后台 Service 可能被系统随时杀掉。前台服务通过显示一个**通知**告诉用户"我正在运行"，系统会尽量保持它存活。

### Service 的生命周期

```
onCreate()         → 创建（只调用一次）
    ↓
onStartCommand()   → 每次启动时调用（可多次）
    ↓
... 服务运行中 ...
    ↓
onDestroy()        → 销毁
```

---

## 二、类定义和成员变量

```kotlin
class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: View          // 翻译窗口
    private lateinit var editFabView: View           // 编辑按钮
    private lateinit var params: WindowManager.LayoutParams      // 翻译窗口参数
    private lateinit var editFabParams: WindowManager.LayoutParams  // 编辑按钮参数
    private lateinit var config: AppConfig
    private lateinit var translationApi: TranslationApi

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    @Volatile
    private var latestBitmap: Bitmap? = null

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val handler = Handler(Looper.getMainLooper())
```

### 关键变量说明

| 变量 | 类型 | 作用 |
|------|------|------|
| `windowManager` | WindowManager | 系统窗口管理器，负责添加/移除悬浮窗 |
| `overlayView` | View | 翻译窗口的视图 |
| `editFabView` | View | 编辑/控制按钮的视图 |
| `mediaProjection` | MediaProjection | 屏幕录制投影，用于截取屏幕 |
| `virtualDisplay` | VirtualDisplay | 虚拟显示器，接收屏幕画面 |
| `imageReader` | ImageReader | 从虚拟显示器读取图像帧 |
| `latestBitmap` | Bitmap | 最新的屏幕截图 |
| `serviceScope` | CoroutineScope | 协程作用域，管理异步操作 |
| `handler` | Handler | 主线程消息处理器 |

> 💡 **`@Volatile`** 标注表示这个变量可能被多个线程同时读写，确保线程间的可见性。

---

## 三、单例模式和伴生对象

```kotlin
companion object {
    const val CHANNEL_ID = "translator_overlay"
    const val NOTIFICATION_ID = 1

    private var instance: OverlayService? = null

    fun getInstance(): OverlayService? = instance

    fun stopService() {
        instance?.stopSelf()
    }
}
```

通过 `instance` 静态变量实现**简单的单例模式**：
- `onCreate()` 时设置 `instance = this`
- `onDestroy()` 时设置 `instance = null`
- 其他组件（如 KeyEventService）可以通过 `OverlayService.getInstance()` 获取服务实例

---

## 四、`onCreate()` —— 初始化

```kotlin
override fun onCreate() {
    super.onCreate()
    instance = this
    config = AppConfig(this)
    translationApi = TranslationApi(config)

    windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

    val metrics = DisplayMetrics()
    windowManager.defaultDisplay.getMetrics(metrics)
    screenWidth = metrics.widthPixels
    screenHeight = metrics.heightPixels
    screenDensity = metrics.densityDpi

    val density = metrics.density
    overlayWidth = (350 * density).toInt()
    overlayHeight = (300 * density).toInt()
    edgeTouchSize = (30 * density).toInt()
    minOverlaySize = (100 * density).toInt()
    cornerRadiusPx = 12f * density
}
```

- 获取屏幕的宽/高/密度
- 根据屏幕密度计算悬浮窗的初始大小（350dp × 300dp）
- 设置边缘触摸区域大小（编辑模式下拖动调整大小时的触摸范围）

> 💡 乘以 `density` 是将 dp 转换为实际的像素值。

---

## 五、`onStartCommand()` —— 启动服务

```kotlin
override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    // 1. 创建通知渠道
    createNotificationChannel()

    // 2. 启动前台服务（必须在5秒内调用）
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        startForeground(NOTIFICATION_ID, createNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
    } else {
        startForeground(NOTIFICATION_ID, createNotification())
    }

    // 3. 获取截屏授权数据（从 MainActivity 的静态变量中取出）
    val resultCode = MainActivity.pendingResultCode
    val resultData = MainActivity.pendingResultData
    MainActivity.pendingResultCode = Activity.RESULT_CANCELED
    MainActivity.pendingResultData = null

    // 4. 初始化 MediaProjection
    if (resultCode == Activity.RESULT_OK && resultData != null) {
        val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, resultData)
        setupImageReader()
    }

    // 5. 创建悬浮窗
    setupOverlay()

    return START_NOT_STICKY
}
```

### 流程详解

#### 1. 前台服务通知

Android 8.0+ 要求前台服务**必须**在启动后 5 秒内显示通知：

```kotlin
private fun createNotification(): Notification {
    return Notification.Builder(this, CHANNEL_ID)
        .setContentTitle("实时翻译运行中")
        .setContentText("点击返回应用")
        .setSmallIcon(android.R.drawable.ic_menu_edit)
        .setContentIntent(pendingIntent)
        .build()
}
```

#### 2. MediaProjection —— 屏幕截图原理

```
┌──────────────────────────────────────┐
│ 手机屏幕（真实显示器）                  │
│                                      │
│  ┌──────────────────┐                │
│  │ 其他 App 的内容   │                │
│  │                  │                │
│  │ 需要翻译的文字    │                │
│  └──────────────────┘                │
└──────────────────────────────────────┘
        │
        │ MediaProjection（镜像）
        ▼
┌──────────────────────────────────────┐
│ VirtualDisplay（虚拟显示器）            │
│ - 接收屏幕画面的副本                   │
└──────────────────────────────────────┘
        │
        │ 输出到
        ▼
┌──────────────────────────────────────┐
│ ImageReader（图像读取器）              │
│ - 将每一帧画面转为 Bitmap（图片对象）   │
│ - 始终保持最新的一帧                   │
└──────────────────────────────────────┘
```

#### 3. `setupImageReader()` —— 图像捕获

```kotlin
private fun setupImageReader() {
    imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)

    imageReader!!.setOnImageAvailableListener({ reader ->
        val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
        try {
            // 从 image 中提取像素数据，创建 Bitmap
            val planes = image.planes
            val buffer = planes[0].buffer
            val bmp = Bitmap.createBitmap(...)
            bmp.copyPixelsFromBuffer(buffer)
            
            // 保存为最新截图
            val old = latestBitmap
            latestBitmap = finalBmp
            old?.recycle()  // 回收旧的 Bitmap 释放内存
        } finally {
            image.close()
        }
    }, handler)

    // 创建虚拟显示器
    virtualDisplay = mediaProjection?.createVirtualDisplay(
        "ScreenCapture",
        screenWidth, screenHeight, screenDensity,
        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
        imageReader!!.surface, null, handler
    )
}
```

关键点：
- `ImageReader` 持续接收屏幕画面帧
- 每一帧到达时，转换为 `Bitmap` 并保存
- 只保留最新一帧，旧的立即回收（否则会内存溢出）

---

## 六、悬浮窗创建 —— `setupOverlay()`

### 窗口参数

```kotlin
params = WindowManager.LayoutParams(
    overlayWidth,
    overlayHeight,
    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,    // 悬浮窗类型
    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or        // 不获取焦点
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or // 全屏布局
        WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,// 监听窗口外触摸
    PixelFormat.TRANSLUCENT                                 // 支持透明
).apply {
    gravity = Gravity.TOP or Gravity.START    // 从左上角开始定位
    x = (screenWidth - overlayWidth) / 2     // 初始位置：水平居中
    y = screenHeight / 4                      // 初始位置：偏上
}
```

| 参数 | 含义 |
|------|------|
| `TYPE_APPLICATION_OVERLAY` | 系统级悬浮窗（可以显示在其他 App 上面）|
| `FLAG_NOT_FOCUSABLE` | 不抢夺焦点（用户仍可操作下方的 App）|
| `FLAG_WATCH_OUTSIDE_TOUCH` | 监听窗口外部的触摸事件 |

### 添加到屏幕

```kotlin
windowManager.addView(overlayView, params)    // 添加翻译窗口
windowManager.addView(editFabView, editFabParams)  // 添加编辑按钮
```

`WindowManager.addView()` 将视图作为**系统级窗口**添加到屏幕上。

---

## 七、触摸事件处理

### 翻译窗口的触摸

```kotlin
scrollView.setOnTouchListener { _, event ->
    if (isEditMode) {
        // 编辑模式：拖动移动或调整大小
        when (event.action) {
            MotionEvent.ACTION_DOWN -> { /* 记录初始位置 */ }
            MotionEvent.ACTION_MOVE -> { /* 计算偏移量，移动/调整窗口 */ }
            MotionEvent.ACTION_UP -> { /* 结束拖动 */ }
        }
    } else {
        // 普通模式：点击触发翻译
        when (event.action) {
            MotionEvent.ACTION_DOWN -> { /* 记录初始位置 */ }
            MotionEvent.ACTION_MOVE -> { /* 检测是否在拖动 */ }
            MotionEvent.ACTION_UP -> {
                if (!isDragging) captureAndTranslate()  // 点击 → 翻译
            }
        }
    }
}
```

### 编辑模式的拖动检测

根据触摸位置判断操作类型：

```
┌─────────────────────────────┐
│ ↖  │     ↑ 上边     │  ↗  │
│ 左上├───────────────┤ 右上 │
├────┤               ├────┤
│ ←  │    移动区域    │  → │
│ 左边│   （中间）     │ 右边│
├────┤               ├────┤
│ 左下├───────────────┤ 右下 │
│ ↙  │     ↓ 下边     │  ↘  │
└─────────────────────────────┘
```

- 触摸**边缘**（30dp 以内）→ 调整对应方向的大小
- 触摸**角落** → 同时调整两个方向
- 触摸**中间** → 移动窗口

### 编辑按钮的触摸

```kotlin
editFabView.setOnTouchListener { _, event ->
    when (event.action) {
        ACTION_DOWN -> { /* 记录位置 */ }
        ACTION_MOVE -> { /* 跟随手指移动 */ }
        ACTION_UP -> {
            if (!editFabIsDragging) {
                if (isOverlayHidden) showOverlay()     // 窗口隐藏时 → 显示
                else {
                    isEditMode = !isEditMode           // 切换编辑模式
                    updateEditModeVisual()              // 更新视觉效果
                }
            }
        }
    }
}

editFabView.setOnLongClickListener {
    stopSelf()  // 长按 → 关闭服务
    true
}
```

操作逻辑：
- **短按**：切换编辑模式（或显示隐藏的窗口）
- **拖动**：移动编辑按钮位置
- **长按**：关闭整个悬浮窗服务

---

## 八、核心功能 —— 截图并翻译

```kotlin
private fun captureAndTranslate() {
    // 1. 显示加载状态
    progressBar.visibility = View.VISIBLE
    tvTranslation.text = "识别中..."

    // 2. 隐藏悬浮窗（避免截到自己）
    overlayView.visibility = View.INVISIBLE
    editFabView.visibility = View.INVISIBLE

    // 3. 等待 200ms（让屏幕更新）
    handler.postDelayed({
        // 4. 恢复显示悬浮窗
        overlayView.visibility = View.VISIBLE
        editFabView.visibility = View.VISIBLE

        // 5. 获取最新截图并裁剪
        val bitmap = latestBitmap?.copy(...)
        val cropBitmap = cropOverlayRegion(bitmap)

        // 6. OCR 识别（带位置信息）
        performOcrWithBlocks(cropBitmap) { textBlocks ->
            
            // 7. 将识别的文字发送翻译
            val fullText = textBlocks.joinToString("\n||||\n") { it.text }
            
            serviceScope.launch {
                val result = translationApi.translate(fullText)
                result.onSuccess { translated ->
                    // 8. 渲染翻译结果到图片上
                    val resultBitmap = renderTranslatedBitmap(
                        cropBitmap, textBlocks, translatedParts
                    )
                    // 9. 显示翻译后的图片
                    ivTranslation.setImageBitmap(resultBitmap)
                }
            }
        }
    }, 200)
}
```

### 为什么要隐藏悬浮窗再截图？

因为 MediaProjection 截取的是**整个屏幕**的画面，如果不隐藏悬浮窗，截图中会包含悬浮窗本身，而不是下面的文字内容。所以流程是：

```
隐藏悬浮窗 → 等 200ms → 截图 → 显示悬浮窗 → 处理截图
```

### 裁剪窗口区域

```kotlin
private fun cropOverlayRegion(screenBitmap: Bitmap): Bitmap? {
    val x = params.x.coerceIn(0, screenBitmap.width - 1)
    val y = params.y.coerceIn(0, screenBitmap.height - 1)
    val w = params.width.coerceAtMost(screenBitmap.width - x)
    val h = params.height.coerceAtMost(screenBitmap.height - y)
    return Bitmap.createBitmap(screenBitmap, x, y, w, h)
}
```

从全屏截图中，只裁剪出悬浮窗覆盖的区域。

---

## 九、OCR 文字识别

```kotlin
private fun performOcrWithBlocks(bitmap: Bitmap, callback: (List<OcrBlock>) -> Unit) {
    val image = InputImage.fromBitmap(bitmap, 0)

    val recognizer = when (config.sourceLang) {
        "中文" -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        "日语" -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
        "韩语" -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
        else -> TextRecognition.getClient(TextRecognizerOptions.Builder().build())
    }

    recognizer.process(image)
        .addOnSuccessListener { visionText ->
            val blocks = mutableListOf<OcrBlock>()
            for (block in visionText.textBlocks) {
                val box = block.boundingBox ?: continue
                blocks.add(OcrBlock(block.text, box))
            }
            callback(blocks)
        }
        .addOnFailureListener {
            callback(emptyList())
        }
}
```

### ML Kit OCR 的工作方式

1. 将 `Bitmap` 转为 `InputImage`
2. 根据源语言选择对应的识别器
3. 调用 `recognizer.process(image)` 进行识别
4. 结果包含每个文字块的**文字内容**和**位置信息**（boundingBox）

### `OcrBlock` 数据类

```kotlin
data class OcrBlock(
    val text: String,      // 识别到的文字
    val boundingBox: Rect  // 文字在图片中的位置（矩形区域）
)
```

> `data class` 是 Kotlin 的**数据类**，自动生成 `equals()`、`hashCode()`、`toString()` 等方法。

---

## 十、翻译结果渲染

这是最精妙的部分——将翻译结果"替换"到原图上。

```kotlin
private fun renderTranslatedBitmap(
    original: Bitmap,
    blocks: List<OcrBlock>,
    translations: List<String>
): Bitmap {
    val result = original.copy(Bitmap.Config.ARGB_8888, true)  // 复制原图
    val canvas = Canvas(result)  // 在副本上绘制

    for (i in blocks.indices) {
        val block = blocks[i]
        val translated = translations.getOrElse(i) { block.text }
        val box = block.boundingBox

        // 1. 采样背景色
        val bgColor = sampleBackgroundColor(original, box)
        
        // 2. 用背景色覆盖原文
        canvas.drawRect(box, bgPaint)

        // 3. 计算合适的字体大小
        val fontSize = fitTextSize(translated, boxWidth, boxHeight, textPaint)

        // 4. 计算文字颜色（和背景对比）
        textPaint.color = getContrastColor(bgColor)

        // 5. 在原来的位置绘制翻译文字
        drawTextInBox(canvas, translated, box, textPaint)
    }
    return result
}
```

### 渲染原理图解

```
原图：                        结果图：
┌──────────────────┐         ┌──────────────────┐
│                  │         │                  │
│  Hello World     │  ──→   │  你好世界          │
│                  │         │                  │
│  Good Morning    │  ──→   │  早上好            │
│                  │         │                  │
└──────────────────┘         └──────────────────┘
```

步骤：
1. **采样背景色**：在文字周围取一圈像素，计算平均颜色
2. **覆盖原文**：用这个背景色画一个矩形，"擦掉"原文字
3. **计算字号**：尝试从大到小的字号，找到能装进原区域的最大值
4. **选择文字颜色**：根据背景亮度自动选黑色或白色（确保可读）
5. **绘制译文**：在同一位置写入翻译后的文字（自动换行）

### 背景采样

```kotlin
private fun sampleBackgroundColor(bitmap: Bitmap, box: Rect): Int {
    // 在文字区域的四条边外侧采样像素
    // 计算所有采样点的平均 RGB 值
}
```

### 对比色计算

```kotlin
private fun getContrastColor(bgColor: Int): Int {
    val luminance = (0.299 * Red + 0.587 * Green + 0.114 * Blue) / 255.0
    return if (luminance > 0.5) Color.BLACK else Color.WHITE
}
```

使用 ITU-R BT.601 公式计算亮度。亮背景用黑字，暗背景用白字。

---

## 十一、资源清理

```kotlin
override fun onDestroy() {
    super.onDestroy()
    instance = null
    try { windowManager.removeView(overlayView) } catch (_: Exception) {}
    try { windowManager.removeView(editFabView) } catch (_: Exception) {}
    cleanupProjection()
    mediaProjection?.stop()
    mediaProjection = null
    serviceScope.cancel()
}
```

服务销毁时必须清理所有资源：
- 移除悬浮窗视图
- 释放 MediaProjection 和 VirtualDisplay
- 取消所有协程

---

## 小结

| 模块 | 技术 | 作用 |
|------|------|------|
| 前台服务 | Notification + startForeground | 保持服务运行 |
| 悬浮窗 | WindowManager + LayoutParams | 系统级浮动窗口 |
| 屏幕截图 | MediaProjection + VirtualDisplay + ImageReader | 获取屏幕画面 |
| 触摸处理 | OnTouchListener + MotionEvent | 拖动、点击、调整大小 |
| OCR | ML Kit TextRecognition | 文字识别（含位置）|
| 翻译渲染 | Canvas + Paint + Bitmap | 图片上绘制翻译文字 |
| 编辑模式 | DragMode 枚举 + 边缘检测 | 八方向调整大小 |

这个文件综合运用了 Android Framework 的多个高级 API，是整个项目的技术精华所在。
