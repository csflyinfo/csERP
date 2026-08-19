// 兼容仍在调用 jcenter() 的老插件。
//
// 起因：getuiflut 0.2.41 的 android/build.gradle 里有两处 jcenter()——
// 第 7 行在 buildscript { repositories } 内，第 21 行在 rootProject.allprojects 内。
// Gradle 9 已彻底移除该方法，构建直接失败：
//   Could not find method jcenter() for arguments [] on repository container
// 该插件项目求值随即中断，又连带抛出
//   'kotlin-android' plugin requires one of the Android Gradle plugins
//   java.lang.NullPointerException
// 后两条是表象，根因只有 jcenter。
//
// 插件源码在 pub 全局缓存里，不能改：一是会污染其他工程，
// 二是 flutter pub get 随时覆盖。所以在本工程侧兜底。
//
// 实现方式：往仓库容器注册一个名为 jcenter 的 Closure 扩展。
// Groovy 调不到 jcenter() 方法时会退而查找同名属性，
// 若该属性是 Closure 就执行它，于是老脚本得以继续。
// 指向 mavenCentral 是因为 JCenter 早已只读下线，其内容本就由 mavenCentral 承接。
//
// 注意必须同时挂到 buildscript.repositories 上：
// 那是脚本自身的类路径仓库容器，与 project.repositories 是两个不同对象，
// 且求值更早。只挂后者时第 7 行依旧会失败。
fun RepositoryHandler.installJcenterShim() {
    val handler = this
    val ext = (handler as ExtensionAware).extensions
    if (ext.findByName("jcenter") != null) return
    ext.add(
        "jcenter",
        object : groovy.lang.Closure<Any>(handler) {
            @Suppress("unused")
            fun doCall(): Any = handler.mavenCentral()
        },
    )
}

allprojects {
    buildscript.repositories.installJcenterShim()
    repositories.installJcenterShim()

    repositories {
        google()
        mavenCentral()
        // 个推 SDK 未发布到 mavenCentral，必须显式声明其私有仓库，
        // 否则 com.getui:gtsdk / gtc 无法解析。
        maven { url = uri("https://mvn.getui.com/nexus/content/repositories/releases/") }
    }
}

val newBuildDir: Directory =
    rootProject.layout.buildDirectory
        .dir("../../build")
        .get()
rootProject.layout.buildDirectory.value(newBuildDir)

subprojects {
    val newSubprojectBuildDir: Directory = newBuildDir.dir(project.name)
    project.layout.buildDirectory.value(newSubprojectBuildDir)

    // 把 CMake 原生构建的中间产物（.cxx）重定向到本项目 build 目录下。
    //
    // 起因：path_provider_android 2.3.1 起依赖 jni，该包用 CMake 做原生构建，
    // AGP 默认把 .cxx 写在插件源码目录里，也就是 pub 全局缓存
    // （%LOCALAPPDATA%\Pub\Cache\hosted\...\jni-x.y.z\android\.cxx）。
    // 往全局缓存写可变构建状态本身就不合理：多个项目会互相污染，
    // 且在受限环境下该目录不可写，会直接构建失败：
    //   Execution failed for task ':jni:configureCMakeDebug[arm64-v8a]'
    //   > java.io.FileNotFoundException: ...\.cxx\Debug\xxx\hash_key.txt (拒绝访问)
    // 重定向后每个项目各自持有原生中间产物，clean 也能真正清干净。
    plugins.withId("com.android.library") {
        extensions.configure<com.android.build.gradle.LibraryExtension>("android") {
            externalNativeBuild.cmake.buildStagingDirectory =
                newSubprojectBuildDir.dir("cxx").asFile
        }
    }

    // 统一抬高各插件的 compileSdk。
    //
    // 起因：部分插件（如 connectivity_plus）源码里写死 compileSdk 33，
    // 但它们传递依赖的 androidx.fragment:1.7.1、androidx.window:1.2.0、
    // androidx.activity:1.8.1 等已要求编译于 34 及以上，AAR 元数据校验直接失败：
    //   Execution failed for task ':connectivity_plus:checkReleaseAarMetadata'
    //   > ... depend on it to compile against version 34 or later
    //   > :connectivity_plus is currently compiled against android-33.
    // 插件源码同样在 pub 全局缓存里不可改，所以在此统一覆盖。
    // 只抬高编译期 SDK，不动 minSdk，因此不影响运行时兼容范围。
    //
    // 必须写在 afterEvaluate 里，不能用 plugins.withId：
    // 后者的回调在插件 apply 的瞬间就执行，此时插件自己 build.gradle 中的
    // android { compileSdk 33 } 尚未求值，随后会把这里设的值原样盖回去。
    // 本回调在根项目求值期注册，早于 AGP 自身的 afterEvaluate，
    // 因此赋值发生在 AGP 读取该属性之前，能够生效。
    afterEvaluate {
        val androidLib =
            extensions.findByType(com.android.build.gradle.LibraryExtension::class.java)
        if (androidLib != null) {
            val current = androidLib.compileSdk
            if (current == null || current < 35) {
                androidLib.compileSdk = 35
            }
        }
    }
}
subprojects {
    project.evaluationDependsOn(":app")
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
