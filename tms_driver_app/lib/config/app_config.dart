import 'package:flutter/foundation.dart' show kIsWeb, kDebugMode;
import 'package:shared_preferences/shared_preferences.dart';

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
  /// 换 WiFi / 换机器后若连不上，有三种改法（优先用第一种，不必重新出包）：
  ///   1. APP 内「我的」页长按「版本说明」→ 服务器地址，直接填新 IP；
  ///   2. 编译参数覆盖：
  ///      flutter build apk --release --dart-define=API_BASE=http://x.x.x.x:8080/api
  ///   3. 改下面这个常量。
  static const String devHost = '192.168.0.237';

  /// 运行时手工配置的服务端地址（来自「我的」页隐藏入口，空串表示未配置）。
  ///
  /// 单独用一个内存字段而不是每次读 SharedPreferences：apiBase 是同步 getter，
  /// 被 dio 构造和各处直接引用，改成 async 会牵连整条调用链。
  static String _override = '';

  /// 运行时地址持久化 key。
  static const String apiBaseKey = 'tms_api_base';

  /// 当前生效地址。优先级：运行时配置 > 编译参数 > 内置默认。
  ///
  /// 运行时配置放在最高位：编译参数是打包时钉死的，而现场换网络后
  /// 只有运行时配置能救——否则又得重新出包。
  static String get apiBase {
    if (_override.isNotEmpty) return _override;
    const env = String.fromEnvironment('API_BASE', defaultValue: '');
    if (env.isNotEmpty) return env;
    return kIsWeb ? 'http://localhost:8080/api' : 'http://$devHost:8080/api';
  }

  /// 编译参数/内置默认给出的地址（不含运行时覆盖），用于配置页展示「恢复默认后是什么」。
  static String get defaultApiBase {
    const env = String.fromEnvironment('API_BASE', defaultValue: '');
    if (env.isNotEmpty) return env;
    return kIsWeb ? 'http://localhost:8080/api' : 'http://$devHost:8080/api';
  }

  /// 是否正在使用运行时手工配置的地址。
  static bool get hasApiBaseOverride => _override.isNotEmpty;

  /// 启动时载入本地保存的地址。必须在任何网络请求之前调用。
  static Future<void> loadApiBaseOverride() async {
    try {
      final prefs = await SharedPreferences.getInstance();
      _override = prefs.getString(apiBaseKey) ?? '';
    } catch (_) {
      // 读取失败就走默认地址，不能让配置项把 APP 卡在启动阶段
      _override = '';
    }
  }

  /// 保存运行时地址；传空串表示恢复默认。
  static Future<void> saveApiBaseOverride(String value) async {
    final normalized = normalizeApiBase(value);
    _override = normalized;
    final prefs = await SharedPreferences.getInstance();
    if (normalized.isEmpty) {
      await prefs.remove(apiBaseKey);
    } else {
      await prefs.setString(apiBaseKey, normalized);
    }
  }

  /// 把用户手输的内容补全成合法 baseUrl。
  ///
  /// 现场只想输 IP，容错在这里一次做掉，避免因为漏了 http:// 或 /api
  /// 而白排查一轮「明明地址对了还是连不上」：
  ///   192.168.1.5            -> http://192.168.1.5:8080/api
  ///   192.168.1.5:9090       -> http://192.168.1.5:9090/api
  ///   http://host/api/       -> http://host/api
  static String normalizeApiBase(String input) {
    var s = input.trim();
    if (s.isEmpty) return '';
    if (!s.startsWith('http://') && !s.startsWith('https://')) {
      s = 'http://$s';
    }
    // 去掉结尾多余的斜杠，否则 dio 拼路径会出现 //tms/app/login
    while (s.endsWith('/')) {
      s = s.substring(0, s.length - 1);
    }
    final schemeEnd = s.indexOf('://') + 3;
    final rest = s.substring(schemeEnd);
    // 只有主机（没写端口也没写路径）时补默认端口
    if (!rest.contains('/') && !rest.contains(':')) {
      s = '$s:8080';
    }
    // 路径部分缺失时补 /api：后端 context-path 固定为 /api
    if (!s.substring(schemeEnd).contains('/')) {
      s = '$s/api';
    }
    return s;
  }

  static const Duration connectTimeout = Duration(seconds: 10);
  static const Duration receiveTimeout = Duration(seconds: 30);

  // 开发期固定验证码（与后端 TmsAuthService.DEV_VERIFY_CODE 一致）
  static const String devVerifyCode = '888888';

  // token 持久化 key
  static const String tokenKey = 'tms_driver_token';
  static const String driverIdKey = 'tms_driver_id';
  static const String driverNameKey = 'tms_driver_name';

  /// 后端下发的运行参数快照缓存 key（PRD-26 §5.5）。
  ///
  /// 与 token 同生命周期但独立存放：退出登录会清 token，参数快照可以保留，
  /// 这样下次进登录页/弱网首屏也能按上次的配置渲染，不必等接口回来。
  static const String appParamsKey = 'tms_app_params';

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

  /// 相机不可用时，是否允许退回相册选图。
  ///
  /// 留证照片原则上必须现场直拍，禁止相册选图——否则等于允许事后补图，
  /// 签收/退货凭证的取证价值会被架空。
  ///
  /// 但部分模拟器镜像与精简 ROM 只声明了相机硬件、并未预装任何相机应用，
  /// 此时 ACTION_IMAGE_CAPTURE 无人响应，签收全流程会被硬堵死、无法联调。
  /// 因此这里只在**相机确实不可用**时才降级到相册，且：
  ///   - 默认仅调试包开启（release 恒为 false），线上取证强度不变；
  ///   - 降级拍摄的照片会被标记来源，由界面明确提示「相册选取」，不静默混入。
  ///
  /// 如需在真机灰度包临时开启，用 `--dart-define=ALLOW_GALLERY_FALLBACK=true`。
  static const bool allowGalleryFallback = bool.fromEnvironment(
    'ALLOW_GALLERY_FALLBACK',
    defaultValue: kDebugMode,
  );
}
