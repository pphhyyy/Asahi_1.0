# ⌨️ KeyEventService —— 无障碍按键服务详解

## 这个文件是什么？

`KeyEventService.kt` 是一个**无障碍服务（AccessibilityService）**，用于拦截外接键盘或手柄的按键事件，实现**快捷键触发翻译和清除**功能。

---

## 什么是 AccessibilityService？

**AccessibilityService（无障碍服务）** 是 Android 提供的一种特殊系统级服务，最初是为了帮助残障人士使用手机（如屏幕朗读、手势辅助等）。

它拥有**极高的系统权限**，可以：
- 监听界面变化
- 读取屏幕内容
- **拦截按键事件**（本项目使用的功能）
- 执行手势操作

> ⚠️ 正因为权限很高，Android 要求无障碍服务**必须由用户在系统设置中手动开启**。

---

## 完整代码解析

### 1. 类定义

```kotlin
class KeyEventService : AccessibilityService() {

    private val TAG = "KeyEventService"
```

继承自 `AccessibilityService`，这是 Android SDK 提供的基类。

---

### 2. 服务连接回调

```kotlin
override fun onServiceConnected() {
    val info = AccessibilityServiceInfo().apply {
        flags = AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        eventTypes = 0  // 不需要处理 AccessibilityEvent
    }
    serviceInfo = info
    Log.d(TAG, "无障碍按键服务已连接")
}
```

#### 这段代码做了什么？

当用户在系统设置中开启此无障碍服务后，系统会调用 `onServiceConnected()`。这里配置服务的行为：

| 配置 | 含义 |
|------|------|
| `FLAG_REQUEST_FILTER_KEY_EVENTS` | 请求拦截按键事件 |
| `eventTypes = 0` | 不监听任何无障碍事件（只需要按键拦截）|

> ⚠️ 这个代码配置和 `accessibility_service_config.xml` 中的配置是**冗余的**（两者都能生效）。XML 配置在安装时就确定了，代码配置可以在运行时动态修改。

---

### 3. 必须实现的接口方法

```kotlin
override fun onAccessibilityEvent(event: AccessibilityEvent?) {
    // 不处理
}

override fun onInterrupt() {
    // 不处理
}
```

这两个是 `AccessibilityService` 的**抽象方法**，必须实现：

| 方法 | 用途 | 本项目 |
|------|------|--------|
| `onAccessibilityEvent()` | 接收无障碍事件（如窗口变化、文字变化）| 不需要，留空 |
| `onInterrupt()` | 服务被中断时调用 | 不需要处理，留空 |

---

### 4. 按键事件处理 —— 核心逻辑

```kotlin
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
```

#### 逐行解释

1. **`event.action != KeyEvent.ACTION_DOWN`**
   - 按键事件有两种：按下（ACTION_DOWN）和松开（ACTION_UP）
   - 只处理按下事件，松开不管

2. **`AppConfig(this)`**
   - 读取配置（获取用户绑定的按键代码）

3. **`OverlayService.getInstance()`**
   - 获取正在运行的悬浮窗服务实例
   - 如果服务没在运行（返回 null），直接退出（不処理按键）

4. **按键匹配**
   - 如果按下的键等于翻译键 → 调用 `service.triggerTranslate()` 触发翻译
   - 如果按下的键等于清除键 → 调用 `service.clearTranslation()` 清除翻译
   - `translateKey != 0` 检查：0 表示未绑定，不处理

5. **返回值的含义**
   - `return true` → 表示按键被消费（不会传递给其他应用）
   - `return false` → 表示按键未被处理（继续传递给其他应用）

---

## 工作流程

```
用户按下外接键盘的某个键
        │
        ▼
Android 系统捕获按键事件
        │
        ▼
KeyEventService.onKeyEvent() 被调用
        │
        ├── 是松开事件？→ return false（忽略）
        │
        ├── OverlayService 未运行？→ return false（忽略）
        │
        ├── 按键 == 翻译键？→ triggerTranslate() → return true（消费）
        │
        ├── 按键 == 清除键？→ clearTranslation() → return true（消费）
        │
        └── 都不匹配 → return false（传递给其他应用）
```

---

## 配置文件：`accessibility_service_config.xml`

```xml
<accessibility-service xmlns:android="http://schemas.android.com/apk/res/android"
    android:accessibilityEventTypes=""
    android:canRequestFilterKeyEvents="true"
    android:accessibilityFlags="flagRequestFilterKeyEvents"
    android:description="@string/accessibility_service_description"
    android:settingsActivity="com.translator.app.SettingsActivity" />
```

| 属性 | 含义 |
|------|------|
| `accessibilityEventTypes=""` | 不监听无障碍事件 |
| `canRequestFilterKeyEvents="true"` | 声明可以拦截按键事件 |
| `accessibilityFlags="flagRequestFilterKeyEvents"` | 启用按键过滤 |
| `description` | 在系统设置中显示的服务说明 |
| `settingsActivity` | 点击"设置"时跳转到的 Activity |

在系统的无障碍服务设置中，用户会看到：

```
┌─────────────────────────────────┐
│  实时翻译                         │
│                                 │
│  用于拦截外接键盘/手柄按键，       │
│  触发翻译和清除操作。             │
│  仅在按键绑定功能启用时使用。      │
│                                 │
│         [ 开关按钮 ]              │
└─────────────────────────────────┘
```

---

## 使用场景

这个功能主要面向以下场景：
- 连接了**外接键盘**的 Android 平板
- 使用**游戏手柄**的用户
- 需要**快速翻译**而不想触摸屏幕的情况

例如：
1. 在设置中将 F1 键绑定为翻译键，F2 键绑定为清除键
2. 把悬浮窗拖到游戏中的日文对话框上
3. 按 F1 → 自动截图 + OCR + 翻译
4. 看完翻译后按 F2 → 清除翻译结果（恢复透明窗口）

---

## 安全性说明

无障碍服务权限非常高，Android 对其有严格的安全要求：

1. **必须在 Manifest 中声明 `BIND_ACCESSIBILITY_SERVICE` 权限**
   - 只有系统才能绑定这个服务，其他 App 无法控制它

2. **必须由用户手动开启**
   - App 无法通过代码自动开启无障碍服务

3. **系统会定期提醒用户**
   - Android 可能会弹窗提醒用户正在使用无障碍服务，确认是否继续

4. **最小权限原则**
   - 本项目只请求了 `flagRequestFilterKeyEvents` 一个权限
   - 不监听任何无障碍事件，不读取屏幕内容

---

## 小结

| 要点 | 说明 |
|------|------|
| `AccessibilityService` | Android 系统级服务，权限很高 |
| `onKeyEvent()` | 拦截按键事件的回调方法 |
| `return true/false` | 消费/传递按键事件 |
| XML 配置 | 声明服务需要的能力 |
| 需手动开启 | 用户必须在系统设置中启用 |
| 最小权限 | 只请求按键拦截，不监听其他事件 |
