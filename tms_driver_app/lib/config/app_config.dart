import 'package:flutter/foundation.dart' show kIsWeb;

/// 全局配置：API 地址、超时、开发期固定验证码。
class AppConfig {
  /// 开发期后端地址（不含 /api 前缀部分由下方拼接）。
  ///
  /// 这里用宿主机局域网 IP 而不是 10.0.2.2：
  /// 10.0.2.2 是 Android Studio 官方 AVD（QEMU 用户态网络）才有的宿主机别名，
  /// 雷电、MuMu 等基于 VirtualBox 的模拟器网段是 172.16.x.x，不存在该地址，
  /// 连接会一直挂到 connectTimeout 然后抛 DioException [connection timeout]。
  /// 局域网 IP 对两类模拟器和真机都通用，代价是换网络环境后需要改这里。
  ///
  /// 换 WiFi / 换机器后若连不上，改这个常量或用编译参数覆盖：
  ///   flutter build apk --release --dart-define=API_BASE=http://x.x.x.x:8080/api
  static const String devHost = '192.168.3.16';

  static String get apiBase {
    const env = String.fromEnvironment('API_BASE', defaultValue: '');
    if (env.isNotEmpty) return env;
    return kIsWeb ? 'http://localhost:8080/api' : 'http://$devHost:8080/api';
  }

  static const Duration connectTimeout = Duration(seconds: 10);
  static const Duration receiveTimeout = Duration(seconds: 30);

  // 开发期固定验证码（与后端 TmsAuthService.DEV_VERIFY_CODE 一致）
  static const String devVerifyCode = '888888';

  // token 持久化 key
  static const String tokenKey = 'tms_driver_token';
  static const String driverIdKey = 'tms_driver_id';
  static const String driverNameKey = 'tms_driver_name';

  // ==================== 留证照片采集与上传 ====================

  /// 拍照后压缩到的最长边像素。
  ///
  /// 必须限制分辨率：imageQuality 只降 JPEG 编码质量、不改像素尺寸，
  /// 主摄直出 4000×3000 即使质量 70 仍有 1.5~3MB，
  /// 司机一单拍 3~5 张、在门店弱网下串行上传能卡到分钟级。
  /// 1600px 对「看清货物外观/破损处/门牌」这类留证用途完全够用，
  /// 压缩后单张通常 200~400KB，是原图的 1/6 左右。
  static const int photoMaxEdge = 1600;

  /// 拍照 JPEG 质量（0~100）。
  static const int photoQuality = 70;

  /// 批量上传的并发数。
  ///
  /// 不设为无限并发：司机现场多为 3G/弱 4G，同时开太多连接会互相抢带宽，
  /// 单张超时的概率反而上升；3 条并发在弱网下是吞吐与稳定性的折中。
  static const int photoUploadConcurrency = 3;
}
