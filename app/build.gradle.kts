import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

// 版本号集中定义：versionName 与 release 产物文件名共用
val appVersionName = "0.1.4"

// 签名材料放在仓库根目录的 keystore.properties（已 gitignore），文件不存在时
// release 产物退化为未签名——这样 clone 后的仓库仍能 assembleRelease，只是装不上设备。
// 首次生成：keytool -genkeypair -keystore release.jks -keyalg RSA -keysize 4096 \
//   -validity 10000 -alias d7 -dname "CN=D7 Sunlight, O=D7, C=CN"
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}

android {
    namespace = "io.github.hecate2.D7"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.hecate2.D7"
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        versionName = appVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // 真机只需 arm64-v8a 与 armeabi-v7a，裁掉 x86/x86_64 的 native 库
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }

        // 只保留中文资源，剔除各家库自带的几十种语言翻译，显著缩减 resources.arsc
        // 只保留界面用得到的八种语言；不写这一行会把 AndroidX 自带的一百多种
        // 翻译一并打进包（实测每个几 KB），而那些永远不会被用户看到。
        resourceConfigurations += setOf("zh", "en", "ja", "ko", "de", "fr", "es", "ru")
    }

    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 有 keystore 才签名；没有时产物未签名，装不上设备但不阻断构建
            if (keystoreProps.isNotEmpty()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = true
    }

    // 剔除 Kotlin 编译器调试探针与工具链元数据：它们只服务于 IDE 断点与插件，release 用不到
    packaging {
        resources.excludes += setOf(
            "DebugProbesKt.bin",
            "kotlin-tooling-metadata.json",
            "**/DebugProbesKt.bin",
            "**/kotlin-tooling-metadata.json",
            // Kotlin 的 7 个 *.kotlin_builtins（共 29 KB，占 release 包 4.8%）：给编译器与
            // kotlin-reflect 读的类库元数据。本工程没有 kotlin-reflect 依赖（见依赖树），
            // 也不做任何运行时反射——kotlinx.serialization 的序列化器是编译期生成的代码，
            // 不读这些文件；会读它们的只有 kotlin-reflect 与 kotlinx-metadata 这类工具。
            "**/*.kotlin_builtins",
            "**/*.kotlin_metadata",
        )
        // camera-core 的 AAR 自带 libimage_processing_util_jni.so（arm64 4.8 KB + v7a 3.4 KB = 8.3 KB，占 APK 1.4%）。
        // 它只服务 YUV/bitmap 互转与 OpenGL 渲染，D7 只有 Preview + ImageCapture 两条用例，
        // 走不到这些 native 方法；ImageProcessingUtil 类本身仍保留（R8 按 native 方法名 keep），
        // 缺的只是库文件。真机 arm64 实拍已验证：照片正常落盘、EXIF 完整、无 UnsatisfiedLinkError。
        jniLibs.excludes += setOf("**/libimage_processing_util_jni.so")
    }

    lint {
        // 报错即失败：防止 NewApi / 可用性回归再次静默积累
        abortOnError = true
        warningsAsErrors = false
        checkDependencies = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// release 产物文件名固定为中文名并带上版本号，便于分发（内部类只因 AGP 未公开该设置项）
androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        variant.outputs.forEach { output ->
            (output as? com.android.build.api.variant.impl.VariantOutputImpl)
                ?.outputFileName?.set("七日-看房拍照测日照时间-v$appVersionName.apk")
        }
    }
}

dependencies {
    implementation(project(":core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.exifinterface)

    // 纯函数单测（不碰 Android 运行时，Surface.ROTATION_* 为编译期常量）
    testImplementation(libs.junit)

    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view) {
        // camera-view 的 POM 挂着 appcompat→fragment→viewpager，但它的字节码对这些库零引用
        // （PreviewView 继承的是 android.widget.FrameLayout）。不显式 exclude 的话，删掉直接依赖
        // 只会让 Gradle 把 appcompat 降到 camera-view 要求的 1.1.0，体积一点不降。
        exclude(group = "androidx.appcompat")
    }

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.androidx.test.espresso.intents)
    androidTestImplementation(libs.androidx.test.uiautomator)
}