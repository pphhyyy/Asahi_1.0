# 📋 AndroidManifest.xml 详解

## 什么是 AndroidManifest.xml？

`AndroidManifest.xml` 是每个 Android 应用**必须有的**文件，位于 `app/src/main/` 目录下。它是 App 的"**身份证 + 户口本**"，告诉 Android 系统关于这个 App 的一切信息：

- App 叫什么名字
- 需要什么权限
- 有哪些界面（Activity）
- 有哪些后台服务（Service）
- 入口在哪里（哪个界面最先打开）

---

## 完整文件内容

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">

    <!-- 悬浮窗权限 -->
    <uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />
    <!-- 网络权限（调用大模型 API） -->
    <uses-permission android:name="android.permission.INTERNET" />
    <!-- 前台服务权限 -->
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

    <application
        android:allowBackup="true"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:supportsRtl="true"
        android:theme="@style/Theme.Translator"
        tools:targetApi="34">

        <activity
            android:name=".MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <activity
            android:name=".SettingsActivity"
            android:exported="false" />

        <service
            android:name=".overlay.OverlayService"
            android:foregroundServiceType="mediaProjection"
            android:exported="false" />

        <service
            android:name=".KeyEventService"
            android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE"
            android:exported="false">
            <intent-filter>
                <action android:name="android.accessibilityservice.AccessibilityService" />
            </intent-filter>
            <meta-data
                android:name="android.accessibilityservice"
                android:resource="@xml/accessibility_service_config" />
        </service>

    </application>
</manifest>
```

---

## 逐段详解

### 1. XML 声明和根标签

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">
```

- `<?xml ...?>` 是标准的 XML 声明
- `<manifest>` 是根标签
- `xmlns:android` 定义了 Android 的命名空间（这样才能使用 `android:name` 等属性）
- `xmlns:tools` 定义了工具命名空间（用于提供编译期提示，不会出现在最终 APK 中）

### 2. 权限声明

```xml
<uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```

Android 的安全模型要求 App **必须声明它需要的权限**。未声明的权限是无法使用的。

| 权限 | 作用 | 为什么需要 |
|------|------|-----------|
| `SYSTEM_ALERT_WINDOW` | 在其他 App 上方显示悬浮窗 | 翻译窗口需要悬浮在所有界面上 |
| `INTERNET` | 访问网络 | 调用翻译 API 需要联网 |
| `FOREGROUND_SERVICE` | 运行前台服务 | 悬浮窗服务需要在前台持续运行 |
| `FOREGROUND_SERVICE_MEDIA_PROJECTION` | 使用屏幕录制/截屏的前台服务 | 截取屏幕内容进行 OCR |
| `POST_NOTIFICATIONS` | 发送通知（Android 13+）| 前台服务**必须**显示通知 |

#### 权限的分类

Android 权限分为几种级别：

| 级别 | 说明 | 示例 |
|------|------|------|
| **普通权限** | 安装时自动授予 | `INTERNET` |
| **危险权限** | 需要用户手动同意 | `POST_NOTIFICATIONS` |
| **特殊权限** | 需要跳转系统设置 | `SYSTEM_ALERT_WINDOW` |

### 3. Application 标签

```xml
<application
    android:allowBackup="true"
    android:icon="@mipmap/ic_launcher"
    android:label="@string/app_name"
    android:supportsRtl="true"
    android:theme="@style/Theme.Translator"
    tools:targetApi="34">
```

| 属性 | 含义 |
|------|------|
| `allowBackup="true"` | 允许用户备份 App 数据 |
| `icon="@mipmap/ic_launcher"` | App 图标，引用 `res/mipmap/` 下的资源 |
| `label="@string/app_name"` | App 名称，引用 `strings.xml` 中的 `app_name`（"实时翻译"）|
| `supportsRtl="true"` | 支持从右到左的语言（如阿拉伯语）|
| `theme="@style/Theme.Translator"` | App 的默认主题，引用 `themes.xml` |
| `tools:targetApi="34"` | 编译时提示，不影响运行 |

#### `@` 符号是什么意思？

在 Android XML 中，`@` 表示**引用资源**：
- `@string/app_name` → 引用 `strings.xml` 中名为 `app_name` 的字符串
- `@mipmap/ic_launcher` → 引用 `mipmap` 目录中的 `ic_launcher` 图标
- `@style/Theme.Translator` → 引用 `themes.xml` 中的主题样式
- `@color/primary` → 引用 `colors.xml` 中名为 `primary` 的颜色

### 4. Activity 声明

#### MainActivity（主界面）

```xml
<activity
    android:name=".MainActivity"
    android:exported="true">
    <intent-filter>
        <action android:name="android.intent.action.MAIN" />
        <category android:name="android.intent.category.LAUNCHER" />
    </intent-filter>
</activity>
```

| 要素 | 含义 |
|------|------|
| `android:name=".MainActivity"` | Activity 的类名（`.` 前缀表示相对于包名 `com.translator.app`）|
| `android:exported="true"` | 允许外部启动（必须为 true，因为它是入口）|
| `intent-filter` | 意图过滤器—— 声明这个 Activity 响应什么类型的请求 |
| `action.MAIN` | 表示这是应用的**主入口** |
| `category.LAUNCHER` | 表示要在**启动器**（桌面）显示图标 |

> 简单说，这段配置的意思是："`MainActivity` 是 App 的入口，在手机桌面上显示图标，用户点击图标时打开它。"

#### SettingsActivity（设置界面）

```xml
<activity
    android:name=".SettingsActivity"
    android:exported="false" />
```

- `exported="false"` 表示这个 Activity **只能由 App 内部启动**，外部无法直接打开

### 5. Service 声明

#### OverlayService（悬浮窗服务）

```xml
<service
    android:name=".overlay.OverlayService"
    android:foregroundServiceType="mediaProjection"
    android:exported="false" />
```

| 属性 | 含义 |
|------|------|
| `android:name=".overlay.OverlayService"` | Service 的类名 |
| `foregroundServiceType="mediaProjection"` | 声明这是一个使用屏幕录制功能的前台服务 |
| `exported="false"` | 不对外暴露 |

> 💡 **什么是前台服务（Foreground Service）？**
> 普通后台服务可能被系统随时杀掉以节省内存。前台服务必须显示一个通知栏通知，但系统会尽量保持它运行。悬浮窗需要持续运行，所以用前台服务。

#### KeyEventService（无障碍按键服务）

```xml
<service
    android:name=".KeyEventService"
    android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE"
    android:exported="false">
    <intent-filter>
        <action android:name="android.accessibilityservice.AccessibilityService" />
    </intent-filter>
    <meta-data
        android:name="android.accessibilityservice"
        android:resource="@xml/accessibility_service_config" />
</service>
```

这个声明比较复杂，因为**无障碍服务**是一种特殊的系统级服务：

| 要素 | 含义 |
|------|------|
| `permission="BIND_ACCESSIBILITY_SERVICE"` | 只有系统才能绑定这个服务（安全保障）|
| `intent-filter` | 声明这是一个 AccessibilityService |
| `meta-data` | 指向配置文件 `accessibility_service_config.xml` |

配置文件 `accessibility_service_config.xml` 的内容：

```xml
<accessibility-service xmlns:android="http://schemas.android.com/apk/res/android"
    android:accessibilityEventTypes=""
    android:canRequestFilterKeyEvents="true"
    android:accessibilityFlags="flagRequestFilterKeyEvents"
    android:description="@string/accessibility_service_description"
    android:settingsActivity="com.translator.app.SettingsActivity" />
```

这告诉系统：这个无障碍服务**只需要拦截按键事件**（`flagRequestFilterKeyEvents`），不需要监听其他无障碍事件。

---

## Activity 和 Service 的区别

这是 Android 开发中两个最重要的组件：

| 特性 | Activity | Service |
|------|----------|---------|
| 有界面？ | ✅ 有 | ❌ 没有 |
| 用户可见？ | ✅ 是 | ❌ 否（在后台运行）|
| 生命周期 | 跟随界面打开/关闭 | 独立于界面运行 |
| 用途 | 展示 UI、处理用户交互 | 长时间运行的后台任务 |
| 本项目示例 | MainActivity、SettingsActivity | OverlayService、KeyEventService |

---

## Intent-Filter 是什么？

**Intent（意图）** 是 Android 组件之间通信的一种方式。**Intent-Filter（意图过滤器）** 声明一个组件能**响应哪些类型的请求**。

打个比方：
- Intent 就像一封信："请帮我打开主界面"
- Intent-Filter 就像信箱上的标签："我是主界面，我接收这类信件"

MainActivity 的 Intent-Filter 表示：
- 我是 `MAIN`（主入口）
- 我属于 `LAUNCHER`（在启动器中显示）

---

## 小结

`AndroidManifest.xml` 虽然只有几十行，但它定义了 App 的所有核心信息：

| 内容 | 作用 |
|------|------|
| 权限声明 | 告诉系统 App 需要什么能力 |
| Application | 定义 App 名称、图标、主题 |
| Activity | 声明所有界面 |
| Service | 声明所有后台服务 |
| Intent-Filter | 定义组件的启动方式 |

> ⚠️ 所有 Activity 和 Service **必须**在 Manifest 中声明，否则运行时会崩溃。
