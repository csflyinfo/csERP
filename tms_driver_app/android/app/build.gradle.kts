// 必须用顶层 import 而不是内联写 java.util.Properties：
// Android 插件在本脚本注册了名为 java 的扩展访问器（JavaPluginExtension），
// 它会遮蔽 java 这个包名，导致 java.util.Properties 被解析成
// 「读 java 扩展的 util 属性」并报 Unresolved reference 'util'。
import java.util.Properties

plugins {
    id("com.android.application")
    // The Flutter Gradle Plugin must be applied after the Android and Kotlin Gradle plugins.
    id("dev.flutter.flutter-gradle-plugin")
}

// 个推 AppID 从 local.properties 读取，不入版本库。
// 未配置时回落到占位值，保证未申请个推应用的开发者也能正常构建、跑通站内消息，
// 只是厂商推送通道不可用——避免因缺一个 ID 就整个工程编不过。
val getuiAppId: String =
    run {
        val props = Properties()
        val f = rootProject.file("local.properties")
        if (f.exists()) f.inputStream().use { props.load(it) }
        props.getProperty("getui.appId") ?: "GETUI_APPID_PLACEHOLDER"
    }

android {
    namespace = "com.erp.tms.tms_driver_app"
    compileSdk = flutter.compileSdkVersion
    ndkVersion = flutter.ndkVersion

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // flutter_local_notifications 依赖 java.time 等 API 的脱糖支持，
        // 不开启会在 minSdk < 26 的设备上抛 NoClassDefFoundError。
        isCoreLibraryDesugaringEnabled = true
    }

    defaultConfig {
        // TODO: Specify your own unique Application ID (https://developer.android.com/studio/build/application-id.html).
        applicationId = "com.erp.tms.tms_driver_app"
        // You can update the following values to match your application needs.
        // For more information, see: https://flutter.dev/to/review-gradle-config.
        minSdk = flutter.minSdkVersion
        targetSdk = flutter.targetSdkVersion
        versionCode = flutter.versionCode
        versionName = flutter.versionName
        // 个推 SDK 通过 manifest 占位符注入 AppID。
        // 3.1.2.0 起占位符名由 GETUI_APP_ID 统一改为 GETUI_APPID，
        // 且 APP_KEY / APP_SECRET 不再需要在 gradle 中配置。
        manifestPlaceholders["GETUI_APPID"] = getuiAppId
        // flutter_local_notifications 文档明确要求开启。
        // minSdk 24 下 Android 原生支持 multidex，此处开启只是让 dex 分包显式化，
        // 避免个推 SDK 与脱糖库叠加后触碰单 dex 方法数上限。
        multiDexEnabled = true
    }

    buildTypes {
        release {
            // TODO: Add your own signing config for the release build.
            // Signing with the debug keys for now, so `flutter run --release` works.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}

dependencies {
    // 脱糖库，与 isCoreLibraryDesugaringEnabled 必须成对出现，缺一方都不生效。
    // 插件文档写的 1.2.2 只适配 AGP 7.3；本工程 AGP 9.0.1，
    // 需用 2.1.x 分支（要求 AGP 8.0.0+），2.1.5 为当前最新 release。
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")

    // 个推推送 SDK。getuiflut 插件只封装了 Dart 侧调用，
    // 原生 SDK 需在宿主工程显式声明；两者版本存在强约束：
    // gtsdk 3.2.18.0 及以上必须搭配 gtc 3.2.5.0 及以上。
    implementation("com.getui:gtsdk:3.3.15.0")
    implementation("com.getui:gtc:3.3.3.0")
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

flutter {
    source = "../.."
}
