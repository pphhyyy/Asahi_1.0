# ⚙️ Gradle 构建系统详解

## Gradle 是什么？

**Gradle** 是 Android 项目的**自动化构建工具**。它负责：

1. **编译代码**：把你写的 Kotlin/Java 代码编译成 Android 能运行的格式
2. **管理依赖**：自动下载项目需要的第三方库
3. **打包 APK**：把代码、资源、库打包成可安装的 APK 文件
4. **签名**：给 APK 签名以便安装到手机上

你可以把 Gradle 理解为一个"**智能编译管家**"——你告诉它需要什么，它就去准备好一切。

---

## 本项目的 Gradle 文件

项目中有 **4 个** Gradle 相关文件，我们逐一解读。

---

## 1. `gradle-wrapper.properties` —— Gradle 自身的版本

```properties
distributionBase=GRADLE_USER_HOME
distributionPath=wrapper/dists
distributionUrl=https\://services.gradle.org/distributions/gradle-8.5-bin.zip
networkTimeout=10000
validateDistributionUrl=true
zipStoreBase=GRADLE_USER_HOME
zipStorePath=wrapper/dists
```

### 关键行解读

| 属性 | 含义 |
|------|------|
| `distributionUrl` | 指定使用 Gradle **8.5** 版本 |
| `distributionBase` | Gradle 下载后存储的位置（用户主目录下）|
| `networkTimeout` | 下载超时时间（10秒）|

> 💡 **为什么用 Gradle Wrapper？**
> 为了确保所有开发者用的是**同一版本**的 Gradle。当你运行 `./gradlew` 命令时，它会自动检查并下载指定版本的 Gradle。

---

## 2. `gradle.properties` —— 全局属性

```properties
android.useAndroidX=true
kotlin.code.style=official
org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
```

### 逐行解读

| 属性 | 含义 |
|------|------|
| `android.useAndroidX=true` | 使用 AndroidX 库（Android 支持库的新版本）|
| `kotlin.code.style=official` | 使用 Kotlin 官方代码风格 |
| `org.gradle.jvmargs=-Xmx2048m` | 给 Gradle JVM 分配最大 2GB 内存 |
| `-Dfile.encoding=UTF-8` | 文件编码使用 UTF-8 |

> 💡 **什么是 AndroidX？**
> AndroidX 是 Google 官方的支持库集合，提供了向后兼容的 UI 组件和工具。简单说，AndroidX 让你的 App 在新旧版本的 Android 上都能正常运行。

---

## 3. `build.gradle.kts`（根级）—— 定义插件版本

```kotlin
// Top-level build file
plugins {
    id("com.android.application") version "8.2.0" apply false
    id("org.jetbrains.kotlin.android") version "1.9.22" apply false
}
```

### 这段代码做了什么？

声明了两个 Gradle 插件及其版本，但通过 `apply false` 表示在根项目中**不应用**，由子模块（app）自己决定是否应用。

| 插件 | 版本 | 作用 |
|------|------|------|
| `com.android.application` | 8.2.0 | Android 应用构建插件（AGP）|
| `org.jetbrains.kotlin.android` | 1.9.22 | Kotlin Android 编译插件 |

> 💡 **为什么在根级声明、子模块应用？**
> 这是一种常见的"集中管理"模式。如果项目有多个模块（如 app、library），可以确保它们使用同一版本的插件。

---

## 4. `app/build.gradle.kts`（模块级）—— 最重要的配置

这个文件是**整个项目最核心的配置文件**之一，定义了 App 的一切编译规则。让我们一段一段来看：

### 4.1 应用插件

```kotlin
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
```

这里**真正应用**了根级声明的两个插件（注意没有 `apply false`）。

### 4.2 Android 配置块

```kotlin
android {
    namespace = "com.translator.app"    // 包命名空间
    compileSdk = 34                     // 编译时使用的 SDK 版本
    ...
}
```

#### `namespace`
定义了这个 App 的 Kotlin/Java 包名空间，用于生成 R 类（资源引用类）。

#### `compileSdk = 34`
**编译SDK版本** —— 告诉编译器使用 Android 14（API 34）的 API 来编译代码。这意味着你可以使用 API 34 中的所有新特性。

### 4.3 默认配置

```kotlin
defaultConfig {
    applicationId = "com.translator.app"   // 应用唯一标识
    minSdk = 26                            // 最低支持 Android 8.0
    targetSdk = 34                         // 目标 Android 14
    versionCode = 1                        // 版本号（数字，每次升级+1）
    versionName = "1.0"                    // 版本名（显示给用户看的）
}
```

#### 三个 SDK 版本的区别

这是新手最容易混淆的地方：

| 属性 | 含义 | 本项目值 |
|------|------|---------|
| `minSdk` | App 能运行的**最低** Android 版本 | 26（Android 8.0）|
| `targetSdk` | App **针对优化**的 Android 版本 | 34（Android 14）|
| `compileSdk` | **编译时**使用的 SDK 版本 | 34（Android 14）|

打个比方：
- `minSdk` = 你的客户最低需要什么学历才能来 → "至少本科"
- `targetSdk` = 你的产品主要面向谁设计 → "面向研究生"
- `compileSdk` = 你用什么版本的工具来制造 → "用最新的工具"

#### `applicationId`
App 在 Google Play 和设备上的**唯一标识**。一旦发布就不能更改。

### 4.4 构建类型

```kotlin
buildTypes {
    release {
        isMinifyEnabled = false       // 不启用代码混淆
        proguardFiles(
            getDefaultProguardFile("proguard-android-optimize.txt"),
            "proguard-rules.pro"
        )
    }
}
```

Android 项目有两种构建类型：
- **debug**（调试版）：开发时使用，自动签名，可调试
- **release**（发布版）：发布时使用，需要手动签名

`isMinifyEnabled = false` 表示不启用代码混淆。生产环境通常应启用以保护代码。

### 4.5 编译选项

```kotlin
compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlinOptions {
    jvmTarget = "17"
}
```

指定 Java 和 Kotlin 的编译目标版本为 **Java 17**。

### 4.6 构建特性

```kotlin
buildFeatures {
    viewBinding = true
}
```

启用了 **ViewBinding**。ViewBinding 会为每个布局 XML 文件自动生成一个绑定类，让你安全地访问布局中的控件，不需要手动写 `findViewById()`。

例如，`activity_main.xml` 会自动生成 `ActivityMainBinding` 类。

### 4.7 依赖声明

```kotlin
dependencies {
    // Android 核心库
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")

    // Google ML Kit OCR
    implementation("com.google.mlkit:text-recognition:16.0.0")
    implementation("com.google.mlkit:text-recognition-chinese:16.0.0")
    implementation("com.google.mlkit:text-recognition-japanese:16.0.0")
    implementation("com.google.mlkit:text-recognition-korean:16.0.0")

    // OkHttp 网络请求
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // JSON 解析
    implementation("com.google.code.gson:gson:2.10.1")

    // 协程
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
}
```

#### 依赖是什么？

**依赖（dependency）** 就是别人写好的代码库，你可以直接拿来用，不需要自己从零实现。`implementation` 关键字表示这个库会被包含到最终的 APK 中。

#### 各依赖的作用

| 依赖 | 作用 | 为什么需要 |
|------|------|-----------|
| `core-ktx` | Android 核心 Kotlin 扩展 | 提供便捷的 Kotlin 扩展函数 |
| `appcompat` | 向后兼容库 | 让新特性在旧设备上也能用 |
| `material` | Material Design 组件 | 好看的按钮、输入框等 UI 组件 |
| `constraintlayout` | 约束布局 | 灵活的界面布局方式 |
| `lifecycle-runtime-ktx` | 生命周期感知协程 | 管理 Activity/Service 的生命周期 |
| `text-recognition` | ML Kit 文字识别（拉丁文）| 英文 OCR |
| `text-recognition-chinese` | ML Kit 中文识别 | 中文 OCR |
| `text-recognition-japanese` | ML Kit 日文识别 | 日文 OCR |
| `text-recognition-korean` | ML Kit 韩文识别 | 韩文 OCR |
| `okhttp` | HTTP 网络请求库 | 调用翻译 API |
| `gson` | JSON 解析库 | 解析 API 返回的 JSON 数据 |
| `kotlinx-coroutines-android` | Kotlin 协程 | 处理异步操作（不阻塞主线程）|

---

## Gradle 的工作流程

当你点击 Android Studio 的 **Run** 按钮时，Gradle 会执行以下步骤：

```
1. 读取 build.gradle.kts 配置
       ↓
2. 下载/检查所有依赖库
       ↓
3. 编译 Kotlin/Java 代码 → .class 文件
       ↓
4. 处理资源文件（布局、字符串、图片等）
       ↓
5. 生成 R 类（资源引用）和 ViewBinding 类
       ↓
6. 将 .class 转换为 Dalvik 字节码（.dex）
       ↓
7. 打包所有内容为 APK
       ↓
8. 签名 APK
       ↓
9. 安装到设备并启动
```

---

## 常用 Gradle 命令

你可以在 Terminal 中使用 `./gradlew` 运行 Gradle 命令：

| 命令 | 作用 |
|------|------|
| `./gradlew build` | 编译整个项目 |
| `./gradlew assembleDebug` | 打包 Debug 版 APK |
| `./gradlew assembleRelease` | 打包 Release 版 APK |
| `./gradlew clean` | 清除构建缓存 |
| `./gradlew dependencies` | 查看项目依赖树 |

> 在 Android Studio 中，这些操作都可以通过菜单完成，不一定需要命令行。

---

## `.kts` 后缀是什么意思？

你可能注意到 Gradle 文件的后缀是 `.gradle.kts` 而不是 `.gradle`：
- `.gradle` → 使用 **Groovy** 语言编写（旧式写法）
- `.gradle.kts` → 使用 **Kotlin Script** 编写（新式写法）

本项目使用的是 Kotlin Script，它的好处是：
- 有更好的 IDE 代码补全和错误提示
- 和项目代码使用同一种语言（Kotlin）
- 类型安全

---

## 小结

| 文件 | 作用 |
|------|------|
| `gradle-wrapper.properties` | 定义 Gradle 自身版本（8.5）|
| `gradle.properties` | 全局属性（内存、编码等）|
| `build.gradle.kts`（根级）| 声明插件版本 |
| `app/build.gradle.kts`（模块级）| App 的编译配置和依赖 |

Gradle 是 Android 项目的基石，理解了它你就理解了项目是如何从代码变成一个可安装的 App 的。
