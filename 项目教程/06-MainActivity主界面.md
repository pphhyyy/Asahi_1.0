# 🏠 MainActivity —— 主界面详解

## 这个文件是什么？

`MainActivity.kt` 是 App 启动后**第一个显示的界面**。它负责：
1. 显示 API 配置状态
2. 提供"开始/停止翻译"按钮
3. 提供透明度调节滑块
4. 申请各种权限（悬浮窗、截屏、通知）
5. 启动/停止悬浮窗服务

---

## 完整代码逐段解析

### 1. 包声明和导入

```kotlin
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
```

#### 关键导入说明
- `android.Manifest`：系统权限常量
- `android.content.Intent`：组件间通信的"信使"
- `android.media.projection.MediaProjectionManager`：截屏管理器
- `android.provider.Settings`：系统设置页面
- `AppCompatActivity`：Activity 的基类，提供向后兼容功能
- `ActivityMainBinding`：ViewBinding 自动生成的类（对应 `activity_main.xml`）

---

### 2. 类定义和属性

```kotlin
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var config: AppConfig
    private var isServiceRunning = false
```

#### 逐行解释

| 代码 | 含义 |
|------|------|
| `class MainActivity : AppCompatActivity()` | 定义一个类，继承自 `AppCompatActivity` |
| `private lateinit var binding` | 声明布局绑定对象（`lateinit` 表示稍后初始化）|
| `private lateinit var config` | 声明配置对象 |
| `private var isServiceRunning = false` | 记录服务是否在运行 |

> 💡 **什么是 `lateinit`？**
> Kotlin 要求变量在声明时就初始化。但有些变量只能在特定时机初始化（比如 `onCreate` 中），`lateinit` 告诉编译器"我保证稍后会初始化它"。

### 3. 伴生对象（静态常量）

```kotlin
companion object {
    private const val TAG = "MainActivity"
    private const val REQUEST_MEDIA_PROJECTION = 1001
    private const val REQUEST_OVERLAY_PERMISSION = 1002
    private const val REQUEST_NOTIFICATION_PERMISSION = 1003

    var pendingResultCode: Int = Activity.RESULT_CANCELED
    var pendingResultData: Intent? = null
}
```

#### `companion object` 是什么？

Kotlin 没有 Java 的 `static` 关键字，用 `companion object` 来代替。放在里面的属性和方法属于**类本身**而不是类的实例。

| 常量 | 用途 |
|------|------|
| `TAG` | 日志标签，方便在 Logcat 中过滤 |
| `REQUEST_MEDIA_PROJECTION` | 截屏权限请求码 |
| `REQUEST_OVERLAY_PERMISSION` | 悬浮窗权限请求码 |
| `pendingResultCode/Data` | 临时存储截屏授权数据，传递给 Service |

> `pendingResultCode` 和 `pendingResultData` 是传递截屏权限的桥梁。因为截屏授权在 Activity 中获取，但实际使用在 Service 中，所以用静态变量传递。

---

### 4. `onCreate()` —— 界面创建

```kotlin
override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    binding = ActivityMainBinding.inflate(layoutInflater)
    setContentView(binding.root)

    config = AppConfig(this)
    updateUI()
    ...
}
```

#### `onCreate()` 是什么？

这是 Activity 的**生命周期方法**。当 Activity 被创建时，系统会自动调用它。你在这里做初始化工作。

#### Activity 生命周期

```
onCreate()   → 创建（初始化界面、数据）
    ↓
onStart()    → 即将可见
    ↓
onResume()   → 前台运行（用户可交互）
    ↓
onPause()    → 即将不可交互（如被覆盖）
    ↓
onStop()     → 完全不可见
    ↓
onDestroy()  → 销毁
```

#### 初始化步骤

1. `ActivityMainBinding.inflate(layoutInflater)` → 加载布局文件
2. `setContentView(binding.root)` → 将布局显示到屏幕
3. `AppConfig(this)` → 创建配置管理对象
4. `updateUI()` → 更新界面显示

### 5. 按钮点击事件

```kotlin
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
```

- `setOnClickListener { ... }` 为按钮设置点击事件处理
- `startActivity(Intent(...))` 打开新的界面（从 MainActivity 跳转到 SettingsActivity）

> 💡 **`Intent` 是什么？**
> Intent（意图）是 Android 组件间通信的机制。这里创建了一个 Intent，说"我想打开 SettingsActivity"。

### 6. 透明度滑块

```kotlin
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
```

- 从配置中读取已保存的透明度值，设置到滑块
- 监听滑块变化：拖动时更新显示，松手后保存到配置

#### `object : SeekBar.OnSeekBarChangeListener` 是什么？

这是 Kotlin 的**匿名内部类**写法。`SeekBar.OnSeekBarChangeListener` 是一个接口，定义了 3 个必须实现的方法：
- `onProgressChanged`：滑块值变化时调用
- `onStartTrackingTouch`：开始拖动时调用（这里不需要处理）
- `onStopTrackingTouch`：停止拖动时调用

---

### 7. 权限申请流程 —— `startOverlayFlow()`

这是最复杂的部分，因为需要依次申请**三种权限**：

```kotlin
private fun startOverlayFlow() {
    // 0. 检查 API 是否配置
    if (!config.isConfigured) {
        Toast.makeText(this, "请先在设置中配置 API Key", Toast.LENGTH_LONG).show()
        startActivity(Intent(this, SettingsActivity::class.java))
        return
    }

    // 1. 检查通知权限 (Android 13+)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) {
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
```

#### 权限申请流程图

```
startOverlayFlow()
    │
    ├─ API 未配置？→ 跳转设置页面 → 结束
    │
    ├─ 通知权限未授予？→ 弹出权限对话框 → 等待结果
    │                                      ↓
    │                            onRequestPermissionsResult()
    │                                      ↓
    │                            再次调用 startOverlayFlow()
    │
    ├─ 悬浮窗权限未授予？→ 跳转系统设置 → 等待返回
    │                                     ↓
    │                            onActivityResult()
    │                                     ↓
    │                            requestScreenCapture()
    │
    └─ 全部权限就绪 → requestScreenCapture()
                            ↓
                    系统弹出截屏授权对话框
                            ↓
                    onActivityResult() → 保存授权 → 启动服务
```

#### 为什么每次 `return`？

注意每个权限检查后都有 `return`。这是因为权限申请是**异步的**——你发出请求后，用户什么时候同意是不确定的。所以流程是：
1. 发出请求 → `return`
2. 用户操作完毕后，系统回调 `onActivityResult` 或 `onRequestPermissionsResult`
3. 在回调中继续流程

### 8. 处理权限结果

#### 处理截屏权限和悬浮窗权限

```kotlin
override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
    super.onActivityResult(requestCode, resultCode, data)
    
    when (requestCode) {
        REQUEST_MEDIA_PROJECTION -> {
            if (resultCode == Activity.RESULT_OK && data != null) {
                pendingResultCode = resultCode
                pendingResultData = data.clone() as Intent
                startOverlayService()
            } else {
                Toast.makeText(this, "需要截屏权限才能进行 OCR 识别", Toast.LENGTH_LONG).show()
            }
        }
        REQUEST_OVERLAY_PERMISSION -> {
            if (Settings.canDrawOverlays(this)) {
                requestScreenCapture()
            }
        }
    }
}
```

`when` 是 Kotlin 的 switch-case 语法，根据 `requestCode` 判断是哪个权限的结果。

#### 处理通知权限

```kotlin
override fun onRequestPermissionsResult(
    requestCode: Int,
    permissions: Array<out String>,
    grantResults: IntArray
) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    if (requestCode == REQUEST_NOTIFICATION_PERMISSION) {
        startOverlayFlow()  // 无论结果如何，继续流程
    }
}
```

通知权限不是必须的（不授予也能运行），所以无论结果如何都继续。

---

### 9. 启动和停止服务

```kotlin
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
```

- `startForegroundService()` 启动前台服务（Android 8.0+ 要求使用此方法）
- `stopService()` 停止服务
- `Toast` 是 Android 的短提示消息，会在屏幕底部短暂显示

---

### 10. 更新 UI

```kotlin
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
```

根据当前状态更新界面显示：
- API 是否配置 → 显示不同提示
- 服务是否运行 → 按钮文字切换

`getString(R.string.xxx)` 是读取 `strings.xml` 中定义的字符串。

---

## 关键知识点总结

### `this` 关键字
在 Activity 中，`this` 代表当前 Activity 实例。很多 Android API 需要一个 `Context`（上下文），Activity 本身就是一个 Context。

### `Toast` —— 短提示消息
```kotlin
Toast.makeText(context, "提示内容", Toast.LENGTH_SHORT).show()
```
- `LENGTH_SHORT` ≈ 2秒
- `LENGTH_LONG` ≈ 3.5秒

### `Log` —— 日志输出
```kotlin
Log.d(TAG, "调试信息")    // Debug 级别
Log.e(TAG, "错误信息")    // Error 级别
```
在 Android Studio 的 **Logcat** 面板中可以查看，通过 TAG 过滤。

---

## 小结

| 要点 | 说明 |
|------|------|
| `onCreate()` | Activity 创建时的初始化入口 |
| ViewBinding | 通过 `binding.xxx` 安全访问控件 |
| `setOnClickListener` | 设置按钮点击事件 |
| `Intent` | 打开新界面或启动服务 |
| 权限申请 | 异步流程，需在回调中处理结果 |
| `startForegroundService()` | 启动前台服务 |
| `Toast` / `Log` | 用户提示 / 开发调试 |
