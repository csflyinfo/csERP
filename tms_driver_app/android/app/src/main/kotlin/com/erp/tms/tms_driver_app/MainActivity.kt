package com.erp.tms.tms_driver_app

import android.content.Intent
import android.provider.MediaStore
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL)
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "hasCameraApp" -> result.success(hasCameraApp())
                    else -> result.notImplemented()
                }
            }
    }

    /**
     * 设备上是否存在能响应 ACTION_IMAGE_CAPTURE 的应用。
     *
     * 之所以要在原生侧主动探测，而不是「先调相机、失败再降级」：
     * 部分模拟器镜像与精简 ROM 上该 Intent 解析不到任何 Activity，系统却
     * 只记一条 START 便静默返回 RESULT_CANCELED，Dart 侧收到的是 null
     * （等同用户取消），不会抛 PlatformException。此时靠捕获异常来判断
     * 相机缺失是失效的，拍照会表现为「点了没反应」，签收流程被硬堵死。
     */
    private fun hasCameraApp(): Boolean {
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        return intent.resolveActivity(packageManager) != null
    }

    companion object {
        private const val CHANNEL = "com.erp.tms/device"
    }
}
