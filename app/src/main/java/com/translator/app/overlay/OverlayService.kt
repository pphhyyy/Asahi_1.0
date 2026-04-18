package com.translator.app.overlay

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.translator.app.AppConfig
import com.translator.app.MainActivity
import com.translator.app.R
import com.translator.app.TranslationApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: View
    private lateinit var editFabView: View
    private lateinit var params: WindowManager.LayoutParams
    private lateinit var editFabParams: WindowManager.LayoutParams
    private lateinit var config: AppConfig
    private lateinit var translationApi: TranslationApi

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    @Volatile
    private var latestBitmap: Bitmap? = null

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val handler = Handler(Looper.getMainLooper())

    private val TAG = "OverlayService"

    private var screenWidth = 0
    private var screenHeight = 0
    private var screenDensity = 0

    private var overlayWidth = 350
    private var overlayHeight = 300

    // 编辑模式
    private var isEditMode = false
    private var edgeTouchSize = 0
    private var minOverlaySize = 0
    private var cornerRadiusPx = 0f

    // 窗口隐藏状态
    private var isOverlayHidden = false

    private enum class DragMode {
        NONE, MOVE,
        RESIZE_LEFT, RESIZE_RIGHT, RESIZE_TOP, RESIZE_BOTTOM,
        RESIZE_TOP_LEFT, RESIZE_TOP_RIGHT, RESIZE_BOTTOM_LEFT, RESIZE_BOTTOM_RIGHT
    }

    private var currentDragMode = DragMode.NONE
    private var dragInitialX = 0
    private var dragInitialY = 0
    private var dragInitialRawX = 0f
    private var dragInitialRawY = 0f
    private var dragInitialWidth = 0
    private var dragInitialHeight = 0
    private var isDragging = false

    companion object {
        const val CHANNEL_ID = "translator_overlay"
        const val NOTIFICATION_ID = 1

        private var instance: OverlayService? = null

        fun getInstance(): OverlayService? = instance

        fun stopService() {
            instance?.stopSelf()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        config = AppConfig(this)
        translationApi = TranslationApi(config)

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                createNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, createNotification())
        }

        val resultCode = MainActivity.pendingResultCode
        val resultData = MainActivity.pendingResultData
        MainActivity.pendingResultCode = android.app.Activity.RESULT_CANCELED
        MainActivity.pendingResultData = null

        if (resultCode == android.app.Activity.RESULT_OK && resultData != null) {
            try {
                val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                mediaProjection = projectionManager.getMediaProjection(resultCode, resultData)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                        override fun onStop() {
                            Log.d(TAG, "MediaProjection stopped")
                            cleanupProjection()
                        }
                    }, handler)
                }

                setupImageReader()
                Log.d(TAG, "MediaProjection 初始化成功")
            } catch (e: Exception) {
                Log.e(TAG, "MediaProjection 初始化失败", e)
            }
        } else {
            Log.e(TAG, "未收到截屏授权数据, resultCode=$resultCode")
        }

        setupOverlay()
        return START_NOT_STICKY
    }

    private fun setupImageReader() {
        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)

        imageReader!!.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val planes = image.planes
                val buffer = planes[0].buffer
                val pixelStride = planes[0].pixelStride
                val rowStride = planes[0].rowStride
                val rowPadding = rowStride - pixelStride * screenWidth

                val bmp = Bitmap.createBitmap(
                    screenWidth + rowPadding / pixelStride,
                    screenHeight,
                    Bitmap.Config.ARGB_8888
                )
                bmp.copyPixelsFromBuffer(buffer)

                val finalBmp = if (bmp.width > screenWidth) {
                    val cropped = Bitmap.createBitmap(bmp, 0, 0, screenWidth, screenHeight)
                    bmp.recycle()
                    cropped
                } else {
                    bmp
                }

                val old = latestBitmap
                latestBitmap = finalBmp
                old?.recycle()
            } catch (e: Exception) {
                Log.e(TAG, "解析屏幕帧失败", e)
            } finally {
                image.close()
            }
        }, handler)

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenCapture",
            screenWidth, screenHeight, screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface, null, handler
        )
        Log.d(TAG, "VirtualDisplay 已创建: ${screenWidth}x${screenHeight}")
    }

    private fun cleanupProjection() {
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        latestBitmap?.recycle()
        latestBitmap = null
    }

    // ==================== 隐藏/显示翻译窗口 ====================

    private fun hideOverlay() {
        if (isOverlayHidden) return
        isOverlayHidden = true
        overlayView.visibility = View.GONE
        windowManager.updateViewLayout(overlayView, params)
        // 更新编辑按钮图标为"显示"
        val editIcon = editFabView.findViewById<TextView>(R.id.editFabIcon)
        if (!isEditMode) editIcon.text = "▢"
    }

    private fun showOverlay() {
        if (!isOverlayHidden) return
        isOverlayHidden = false
        overlayView.visibility = View.VISIBLE
        windowManager.updateViewLayout(overlayView, params)
        val editIcon = editFabView.findViewById<TextView>(R.id.editFabIcon)
        if (!isEditMode) editIcon.text = "✎"
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupOverlay() {
        val inflater = getSystemService(LAYOUT_INFLATER_SERVICE) as LayoutInflater

        // === 翻译窗口 ===
        overlayView = inflater.inflate(R.layout.overlay_layout, null)
        applyOverlayAlpha()

        params = WindowManager.LayoutParams(
            overlayWidth,
            overlayHeight,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (screenWidth - overlayWidth) / 2
            y = screenHeight / 4
        }

        // 点击窗口外部 → 清空翻译结果
        overlayView.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_OUTSIDE) {
                doClearTranslation()
                true
            } else {
                false
            }
        }

        // 隐藏按钮
        overlayView.findViewById<TextView>(R.id.btnMinimize).setOnClickListener {
            hideOverlay()
        }

        // ScrollView 触摸处理
        val scrollView = overlayView.findViewById<ScrollView>(R.id.scrollResult)
        val touchSlopSq = 25f * 25f

        scrollView.setOnTouchListener { _, event ->
            if (isEditMode) {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        dragInitialX = params.x
                        dragInitialY = params.y
                        dragInitialRawX = event.rawX
                        dragInitialRawY = event.rawY
                        dragInitialWidth = params.width
                        dragInitialHeight = params.height
                        isDragging = false
                        currentDragMode = detectDragMode(event.x, event.y)
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - dragInitialRawX
                        val dy = event.rawY - dragInitialRawY
                        if (!isDragging && dx * dx + dy * dy > touchSlopSq) {
                            isDragging = true
                        }
                        if (isDragging) handleEditModeDrag(dx, dy)
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        currentDragMode = DragMode.NONE
                        true
                    }
                    else -> true
                }
            } else {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        dragInitialRawX = event.rawX
                        dragInitialRawY = event.rawY
                        isDragging = false
                        false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - dragInitialRawX
                        val dy = event.rawY - dragInitialRawY
                        if (dx * dx + dy * dy > touchSlopSq) isDragging = true
                        false
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!isDragging) {
                            captureAndTranslate()
                        }
                        false
                    }
                    else -> false
                }
            }
        }

        windowManager.addView(overlayView, params)

        // === 编辑/控制按钮（✎）===
        editFabView = inflater.inflate(R.layout.edit_button, null)

        val fabSize = (48 * resources.displayMetrics.density).toInt()
        editFabParams = WindowManager.LayoutParams(
            fabSize,
            fabSize,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = screenWidth - fabSize - (16 * resources.displayMetrics.density).toInt()
            y = screenHeight / 2
        }

        var editFabInitialX = 0
        var editFabInitialY = 0
        var editFabInitialTouchX = 0f
        var editFabInitialTouchY = 0f
        var editFabIsDragging = false

        editFabView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    editFabInitialX = editFabParams.x
                    editFabInitialY = editFabParams.y
                    editFabInitialTouchX = event.rawX
                    editFabInitialTouchY = event.rawY
                    editFabIsDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - editFabInitialTouchX
                    val dy = event.rawY - editFabInitialTouchY
                    if (dx * dx + dy * dy > 25) editFabIsDragging = true
                    editFabParams.x = editFabInitialX + dx.toInt()
                    editFabParams.y = editFabInitialY + dy.toInt()
                    windowManager.updateViewLayout(editFabView, editFabParams)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!editFabIsDragging) {
                        // 如果窗口隐藏了，先显示
                        if (isOverlayHidden) {
                            showOverlay()
                        } else {
                            // 切换编辑模式
                            isEditMode = !isEditMode
                            updateEditModeVisual()
                            val editIcon = editFabView.findViewById<TextView>(R.id.editFabIcon)
                            editIcon.text = if (isEditMode) "✓" else "✎"
                        }
                    }
                    true
                }
                else -> false
            }
        }

        editFabView.setOnLongClickListener {
            stopSelf()
            true
        }

        windowManager.addView(editFabView, editFabParams)
    }

    // ==================== 编辑模式 ====================

    private fun updateEditModeVisual() {
        val root = overlayView.findViewById<View>(R.id.overlayRoot) ?: overlayView
        if (isEditMode) {
            val bg = GradientDrawable().apply {
                setColor(Color.argb(config.overlayAlpha, 0, 0, 0))
                setStroke((2 * resources.displayMetrics.density).toInt(), Color.parseColor("#4FC3F7"))
                cornerRadius = cornerRadiusPx
            }
            root.background = bg
        } else {
            applyOverlayAlpha()
        }
    }

    private fun detectDragMode(localX: Float, localY: Float): DragMode {
        val w = params.width
        val h = params.height
        val edge = edgeTouchSize

        val isLeft = localX < edge
        val isRight = localX > w - edge
        val isTop = localY < edge
        val isBottom = localY > h - edge

        return when {
            isTop && isLeft -> DragMode.RESIZE_TOP_LEFT
            isTop && isRight -> DragMode.RESIZE_TOP_RIGHT
            isBottom && isLeft -> DragMode.RESIZE_BOTTOM_LEFT
            isBottom && isRight -> DragMode.RESIZE_BOTTOM_RIGHT
            isLeft -> DragMode.RESIZE_LEFT
            isRight -> DragMode.RESIZE_RIGHT
            isTop -> DragMode.RESIZE_TOP
            isBottom -> DragMode.RESIZE_BOTTOM
            else -> DragMode.MOVE
        }
    }

    private fun handleEditModeDrag(dx: Float, dy: Float) {
        val dxi = dx.toInt()
        val dyi = dy.toInt()

        when (currentDragMode) {
            DragMode.MOVE -> {
                params.x = dragInitialX + dxi
                params.y = dragInitialY + dyi
            }
            DragMode.RESIZE_RIGHT -> {
                params.width = (dragInitialWidth + dxi).coerceAtLeast(minOverlaySize)
            }
            DragMode.RESIZE_BOTTOM -> {
                params.height = (dragInitialHeight + dyi).coerceAtLeast(minOverlaySize)
            }
            DragMode.RESIZE_LEFT -> {
                val newW = (dragInitialWidth - dxi).coerceAtLeast(minOverlaySize)
                params.x = dragInitialX + dragInitialWidth - newW
                params.width = newW
            }
            DragMode.RESIZE_TOP -> {
                val newH = (dragInitialHeight - dyi).coerceAtLeast(minOverlaySize)
                params.y = dragInitialY + dragInitialHeight - newH
                params.height = newH
            }
            DragMode.RESIZE_TOP_LEFT -> {
                val newW = (dragInitialWidth - dxi).coerceAtLeast(minOverlaySize)
                val newH = (dragInitialHeight - dyi).coerceAtLeast(minOverlaySize)
                params.x = dragInitialX + dragInitialWidth - newW
                params.y = dragInitialY + dragInitialHeight - newH
                params.width = newW
                params.height = newH
            }
            DragMode.RESIZE_TOP_RIGHT -> {
                params.width = (dragInitialWidth + dxi).coerceAtLeast(minOverlaySize)
                val newH = (dragInitialHeight - dyi).coerceAtLeast(minOverlaySize)
                params.y = dragInitialY + dragInitialHeight - newH
                params.height = newH
            }
            DragMode.RESIZE_BOTTOM_LEFT -> {
                val newW = (dragInitialWidth - dxi).coerceAtLeast(minOverlaySize)
                params.x = dragInitialX + dragInitialWidth - newW
                params.width = newW
                params.height = (dragInitialHeight + dyi).coerceAtLeast(minOverlaySize)
            }
            DragMode.RESIZE_BOTTOM_RIGHT -> {
                params.width = (dragInitialWidth + dxi).coerceAtLeast(minOverlaySize)
                params.height = (dragInitialHeight + dyi).coerceAtLeast(minOverlaySize)
            }
            DragMode.NONE -> {}
        }
        windowManager.updateViewLayout(overlayView, params)
    }

    // ==================== 透明度控制 ====================

    private fun applyOverlayAlpha() {
        val alpha = config.overlayAlpha
        val root = overlayView.findViewById<View>(R.id.overlayRoot) ?: overlayView
        val bg = root.background
        if (bg is GradientDrawable) {
            bg.setColor(Color.argb(alpha, 0, 0, 0))
        } else {
            root.setBackgroundColor(Color.argb(alpha, 0, 0, 0))
        }
    }

    // ==================== 翻译功能 ====================

    fun triggerTranslate() {
        handler.post {
            if (isOverlayHidden) showOverlay()
            captureAndTranslate()
        }
    }

    fun clearTranslation() {
        handler.post { doClearTranslation() }
    }

    private fun doClearTranslation() {
        val tvTranslation = overlayView.findViewById<TextView>(R.id.tvTranslation)
        val ivTranslation = overlayView.findViewById<ImageView>(R.id.ivTranslation)
        val scrollView = overlayView.findViewById<ScrollView>(R.id.scrollResult)
        tvTranslation.text = ""
        ivTranslation.visibility = View.GONE
        ivTranslation.setImageBitmap(null)
        scrollView.visibility = View.VISIBLE
        applyOverlayAlpha()
    }

    private fun captureAndTranslate() {
        val tvTranslation = overlayView.findViewById<TextView>(R.id.tvTranslation)
        val ivTranslation = overlayView.findViewById<ImageView>(R.id.ivTranslation)
        val progressBar = overlayView.findViewById<ProgressBar>(R.id.progressBar)
        val scrollView = overlayView.findViewById<ScrollView>(R.id.scrollResult)

        progressBar.visibility = View.VISIBLE
        tvTranslation.text = "识别中..."
        ivTranslation.visibility = View.GONE
        scrollView.visibility = View.VISIBLE

        // 隐藏悬浮窗以捕获下方内容
        overlayView.visibility = View.INVISIBLE
        editFabView.visibility = View.INVISIBLE
        windowManager.updateViewLayout(overlayView, params)
        windowManager.updateViewLayout(editFabView, editFabParams)

        handler.postDelayed({
            overlayView.visibility = View.VISIBLE
            editFabView.visibility = View.VISIBLE
            windowManager.updateViewLayout(overlayView, params)
            windowManager.updateViewLayout(editFabView, editFabParams)

            val bitmap = latestBitmap?.copy(Bitmap.Config.ARGB_8888, false)

            if (bitmap == null) {
                val msg = when {
                    mediaProjection == null -> "截屏失败：未获取到截屏授权"
                    imageReader == null -> "截屏失败：图像读取器未初始化"
                    virtualDisplay == null -> "截屏失败：虚拟显示器创建失败"
                    else -> "截屏失败：暂未捕获到屏幕画面，请稍后重试"
                }
                tvTranslation.text = msg
                progressBar.visibility = View.GONE
                return@postDelayed
            }

            val cropBitmap = cropOverlayRegion(bitmap)
            bitmap.recycle()

            if (cropBitmap == null) {
                tvTranslation.text = "裁剪截图失败"
                progressBar.visibility = View.GONE
                return@postDelayed
            }

            // 带位置信息的 OCR
            performOcrWithBlocks(cropBitmap) { textBlocks ->
                if (textBlocks.isEmpty()) {
                    cropBitmap.recycle()
                    tvTranslation.text = "未识别到文字"
                    progressBar.visibility = View.GONE
                    return@performOcrWithBlocks
                }

                val fullText = textBlocks.joinToString("\n||||\n") { it.text }
                tvTranslation.text = "翻译中..."

                serviceScope.launch {
                    val result = translationApi.translate(fullText)
                    result.onSuccess { translated ->
                        // 将翻译结果按分隔符拆分，对应各个文字块
                        val translatedParts = translated.split("||||")
                            .map { it.trim() }
                            .filter { it.isNotEmpty() }

                        // 生成图片替换式翻译结果
                        val resultBitmap = renderTranslatedBitmap(
                            cropBitmap, textBlocks, translatedParts
                        )
                        cropBitmap.recycle()

                        // 显示图片
                        ivTranslation.setImageBitmap(resultBitmap)
                        ivTranslation.visibility = View.VISIBLE
                        scrollView.visibility = View.GONE
                        tvTranslation.text = ""

                        // 让背景透明以显示图片
                        val root = overlayView.findViewById<View>(R.id.overlayRoot)
                        root?.setBackgroundColor(Color.TRANSPARENT)
                    }.onFailure { error ->
                        cropBitmap.recycle()
                        tvTranslation.text = "翻译失败: ${error.message}"
                    }
                    progressBar.visibility = View.GONE
                }
            }
        }, 200)
    }

    // ==================== 图片替换式翻译渲染 ====================

    data class OcrBlock(
        val text: String,
        val boundingBox: Rect
    )

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
                handler.post { callback(blocks) }
            }
            .addOnFailureListener {
                handler.post { callback(emptyList()) }
            }
    }

    /**
     * 在原始截图上替换文字：
     * 1. 用文字区域的背景色涂掉原文
     * 2. 在同样位置绘制翻译文字，字号自适应区域大小
     */
    private fun renderTranslatedBitmap(
        original: Bitmap,
        blocks: List<OcrBlock>,
        translations: List<String>
    ): Bitmap {
        val result = original.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(result)
        val bgPaint = Paint()
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.DEFAULT
        }

        for (i in blocks.indices) {
            val block = blocks[i]
            val translated = translations.getOrElse(i) { block.text }
            val box = block.boundingBox

            // 采样背景色：取边框外围一圈像素的中值颜色
            val bgColor = sampleBackgroundColor(original, box)
            bgPaint.color = bgColor

            // 用背景色覆盖原文区域
            canvas.drawRect(
                box.left.toFloat(), box.top.toFloat(),
                box.right.toFloat(), box.bottom.toFloat(),
                bgPaint
            )

            // 计算合适的文字大小（适配区域）
            val boxWidth = (box.right - box.left).toFloat()
            val boxHeight = (box.bottom - box.top).toFloat()
            val fontSize = fitTextSize(translated, boxWidth, boxHeight, textPaint)
            textPaint.textSize = fontSize
            textPaint.color = getContrastColor(bgColor)

            // 绘制翻译文字（自动换行）
            drawTextInBox(canvas, translated, box, textPaint)
        }

        return result
    }

    /**
     * 采样文字区域周围的背景颜色
     */
    private fun sampleBackgroundColor(bitmap: Bitmap, box: Rect): Int {
        val pixels = mutableListOf<Int>()
        val margin = 2

        // 采样上边和下边
        for (x in box.left..box.right step 3) {
            val sx = x.coerceIn(0, bitmap.width - 1)
            val topY = (box.top - margin).coerceIn(0, bitmap.height - 1)
            val bottomY = (box.bottom + margin).coerceIn(0, bitmap.height - 1)
            pixels.add(bitmap.getPixel(sx, topY))
            pixels.add(bitmap.getPixel(sx, bottomY))
        }
        // 采样左边和右边
        for (y in box.top..box.bottom step 3) {
            val sy = y.coerceIn(0, bitmap.height - 1)
            val leftX = (box.left - margin).coerceIn(0, bitmap.width - 1)
            val rightX = (box.right + margin).coerceIn(0, bitmap.width - 1)
            pixels.add(bitmap.getPixel(leftX, sy))
            pixels.add(bitmap.getPixel(rightX, sy))
        }

        if (pixels.isEmpty()) return Color.WHITE

        // 计算平均颜色
        var r = 0L; var g = 0L; var b = 0L
        for (p in pixels) {
            r += Color.red(p)
            g += Color.green(p)
            b += Color.blue(p)
        }
        val n = pixels.size
        return Color.rgb((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
    }

    /**
     * 根据背景色计算对比文字颜色
     */
    private fun getContrastColor(bgColor: Int): Int {
        val luminance = (0.299 * Color.red(bgColor) +
            0.587 * Color.green(bgColor) +
            0.114 * Color.blue(bgColor)) / 255.0
        return if (luminance > 0.5) Color.BLACK else Color.WHITE
    }

    /**
     * 计算能填满指定区域的文字大小
     */
    private fun fitTextSize(
        text: String, boxWidth: Float, boxHeight: Float, paint: Paint
    ): Float {
        // 从区域高度的一半开始尝试，逐步缩小
        var size = boxHeight * 0.8f
        val minSize = 8f
        paint.textSize = size

        while (size > minSize) {
            paint.textSize = size
            val lines = wrapText(text, paint, boxWidth)
            val totalHeight = lines.size * size * 1.2f
            if (totalHeight <= boxHeight && lines.all { paint.measureText(it) <= boxWidth }) {
                return size
            }
            size -= 1f
        }
        return minSize
    }

    /**
     * 将文字按宽度自动换行
     */
    private fun wrapText(text: String, paint: Paint, maxWidth: Float): List<String> {
        val lines = mutableListOf<String>()
        for (paragraph in text.split("\n")) {
            if (paragraph.isEmpty()) {
                lines.add("")
                continue
            }
            var start = 0
            while (start < paragraph.length) {
                var end = paragraph.length
                while (end > start + 1 && paint.measureText(paragraph, start, end) > maxWidth) {
                    end--
                }
                lines.add(paragraph.substring(start, end))
                start = end
            }
        }
        return lines
    }

    /**
     * 在指定矩形区域内绘制自动换行的文字
     */
    private fun drawTextInBox(canvas: Canvas, text: String, box: Rect, paint: Paint) {
        val lines = wrapText(text, paint, (box.right - box.left).toFloat())
        val lineHeight = paint.textSize * 1.2f
        val totalHeight = lines.size * lineHeight
        var y = box.top + (box.bottom - box.top - totalHeight) / 2f + paint.textSize

        for (line in lines) {
            if (y > box.bottom) break
            canvas.drawText(line, box.left.toFloat(), y, paint)
            y += lineHeight
        }
    }

    // ==================== 工具 ====================

    private fun cropOverlayRegion(screenBitmap: Bitmap): Bitmap? {
        return try {
            val x = params.x.coerceIn(0, screenBitmap.width - 1)
            val y = params.y.coerceIn(0, screenBitmap.height - 1)
            val w = params.width.coerceAtMost(screenBitmap.width - x)
            val h = params.height.coerceAtMost(screenBitmap.height - y)
            if (w <= 0 || h <= 0) return null
            Bitmap.createBitmap(screenBitmap, x, y, w, h)
        } catch (e: Exception) {
            null
        }
    }

    // ==================== 通知 ====================

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel),
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setContentIntent(pendingIntent)
            .build()
    }

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
}
