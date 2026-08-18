allprojects {
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
}
subprojects {
    project.evaluationDependsOn(":app")
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
