import 'dart:io' show Platform;

import 'package:flutter/foundation.dart';
import 'package:flutter_local_notifications/flutter_local_notifications.dart';
import 'package:getuiflut/getuiflut.dart';

import '../models/notification.dart';
import 'api_service.dart';
import 'auth_service.dart';

/// 推送服务：个推通道接入 + 本地通知栏展示。
///
/// 分工说明：个推负责「把消息送到设备」（含厂商通道唤醒、离线补发），
/// 但个推的透传消息（payload）不会自动出现在通知栏，
/// 需要本服务收到 payload 后用 flutter_local_notifications 自行弹出，
/// 否则 APP 在后台时司机看不到任何提示。
///
/// 为什么不用个推的「通知消息」而坚持透传：通知消息由 SDK 直接展示，
/// APP 拿不到内容也无法控制点击跳转，而司机端需要点通知直达对应业务单据。
class PushService {
  PushService._();
  static final PushService instance = PushService._();

  final Getuiflut _getui = Getuiflut();
  final FlutterLocalNotificationsPlugin _local =
      FlutterLocalNotificationsPlugin();

  bool _inited = false;
  String _clientId = '';

  /// 个推下发的 ClientId（CID），即设备唯一标识，上报后端用于定向推送。
  String get clientId => _clientId;

  /// 已上报成功的 CID，避免重复注册同一个值。
  String _reportedCid = '';

  /// 通知点击回调：由 UI 层注入，用于跳转消息中心或业务详情。
  void Function(PushPayload payload)? onNotificationTap;

  /// 收到新消息回调：由 UI 层注入，用于立即刷新未读角标。
  void Function()? onMessageArrived;

  /// 通知渠道 id 必须与展示时一致，Android 8.0+ 渠道创建后属性不可改，
  /// 若要调整重要级别需换新 id，故这里带上版本后缀。
  static const String _channelId = 'tms_driver_notify_v1';
  static const String _channelName = 'TMS 配送消息';

  /// 初始化推送。
  ///
  /// Web 与桌面端直接跳过：getuiflut 只支持 Android/iOS，
  /// 在 Web 上调用会因缺少 MethodChannel 实现而抛异常，
  /// 而司机端 Web 模式仅用于开发调试，站内消息轮询已足够。
  Future<void> init() async {
    if (_inited) return;
    if (kIsWeb || !(Platform.isAndroid || Platform.isIOS)) {
      debugPrint('[Push] 当前平台不支持推送，跳过初始化');
      return;
    }
    _inited = true;

    try {
      await _initLocalNotification();
      _bindGetuiHandlers();
      // Android 侧走 initGetuiSdk（AppID 由 manifestPlaceholders 注入）；
      // iOS 需在此处显式传 appId/appKey/appSecret，当前工程尚无 ios 目录，
      // 待补 iOS 工程时在此分支调用 startSdk。
      if (Platform.isAndroid) {
        // 注意：initGetuiSdk 是 getter 而非方法，加括号会编译失败。
        _getui.initGetuiSdk;
      }
    } catch (e) {
      // 推送初始化失败不能影响 APP 启动：站内消息轮询是兜底通道，
      // 司机仍可正常查看消息，只是收不到系统级提醒。
      debugPrint('[Push] 初始化失败: $e');
    }
  }

  /// 初始化本地通知插件并申请 Android 13+ 通知权限。
  Future<void> _initLocalNotification() async {
    // 小图标复用个推约定的 push_small 资源名，与通知栏图标保持一致。
    const androidInit = AndroidInitializationSettings('@drawable/push_small');
    await _local.initialize(
      const InitializationSettings(android: androidInit),
      onDidReceiveNotificationResponse: (NotificationResponse res) {
        _handleTap(res.payload);
      },
    );

    if (Platform.isAndroid) {
      final android = _local.resolvePlatformSpecificImplementation<
          AndroidFlutterLocalNotificationsPlugin>();
      // Android 13+ 通知需运行时授权，仅在 manifest 声明权限不会自动弹窗。
      // 被拒绝也不重试：反复弹窗骚扰司机，系统也会直接忽略后续请求。
      final granted = await android?.requestNotificationsPermission();
      debugPrint('[Push] 通知权限: $granted');
    }
  }

  /// 绑定个推事件回调。
  ///
  /// addEventHandler 的 18 个参数全部为 required，即使本项目不关心
  /// iOS 实况活动、标签查询等回调，也必须逐一提供空实现，否则编译不过。
  void _bindGetuiHandlers() {
    _getui.addEventHandler(
      // CID 下发，可能在登录前就到达，故内部会判断登录态决定是否立即上报
      onReceiveClientId: (String cid) async {
        _clientId = cid;
        debugPrint('[Push] 收到 CID: $cid');
        await registerToken();
      },
      // 透传消息：个推不会自动展示，必须本地弹通知栏
      onReceivePayload: (Map<String, dynamic> msg) async {
        _handlePayload(msg);
      },
      onTransmitUserMessageReceive: (Map<String, dynamic> msg) async {
        _handlePayload(msg);
      },
      // 个推自带通知消息被点击（保留处理，便于服务端两种消息类型混用时也能跳转）
      onNotificationMessageClicked: (Map<String, dynamic> msg) async {
        _handleTap(_extractPayloadString(msg));
      },
      onNotificationMessageArrived: (Map<String, dynamic> msg) async {
        onMessageArrived?.call();
      },
      onReceiveOnlineState: (String state) async {
        debugPrint('[Push] 在线状态: $state');
      },
      onRegisterDeviceToken: (String token) async {
        debugPrint('[Push] 厂商 token: $token');
      },
      // 以下回调本项目未使用，但签名要求必须提供
      onReceiveNotificationResponse: (Map<String, dynamic> e) async {},
      onAppLinkPayload: (String e) async {},
      onPushModeResult: (Map<String, dynamic> e) async {},
      onSetTagResult: (Map<String, dynamic> e) async {},
      onAliasResult: (Map<String, dynamic> e) async {},
      onQueryTagResult: (Map<String, dynamic> e) async {},
      onWillPresentNotification: (Map<String, dynamic> e) async {},
      onOpenSettingsForNotification: (Map<String, dynamic> e) async {},
      onGrantAuthorization: (String e) async {},
      onLiveActivityResult: (Map<String, dynamic> e) async {},
      onRegisterPushToStartTokenResult: (Map<String, dynamic> e) async {},
    );
  }

  /// 处理透传消息：解析业务字段 → 弹本地通知 → 通知 UI 刷新角标。
  Future<void> _handlePayload(Map<String, dynamic> msg) async {
    final raw = _extractPayloadString(msg);
    final payload = PushPayload.parse(raw);

    // 未登录时不展示：可能是上一位司机的消息，
    // 换人登录后弹出他人配送信息属于信息泄露。
    if (AuthService.instance.current == null) {
      debugPrint('[Push] 未登录，忽略推送');
      return;
    }

    await _show(payload, raw);
    onMessageArrived?.call();

    // 回执上报，让个推后台统计到达率；失败不影响主流程
    final taskId = _str(msg['taskId'] ?? msg['taskid']);
    final messageId = _str(msg['messageId'] ?? msg['messageid']);
    if (taskId.isNotEmpty && messageId.isNotEmpty) {
      try {
        // 60001 为个推约定的「消息展示」回执动作
        _getui.sendFeedbackMessage(taskId, messageId, 60001);
      } catch (e) {
        debugPrint('[Push] 回执失败: $e');
      }
    }
  }

  /// 弹出本地通知。
  Future<void> _show(PushPayload payload, String raw) async {
    // 用毫秒低位作为通知 id：固定 id 会让后一条覆盖前一条，
    // 司机可能同时收到多张派车单，必须并存展示。
    final id = DateTime.now().millisecondsSinceEpoch.remainder(100000);

    final android = AndroidNotificationDetails(
      _channelId,
      _channelName,
      channelDescription: '派车单、异常回执、交账结果等配送相关消息',
      // 紧急消息用 max/high 触发横幅弹出，普通消息安静进通知栏，
      // 避免非紧急消息在司机开车时强行抢占屏幕。
      importance:
          payload.isUrgent ? Importance.max : Importance.defaultImportance,
      priority: payload.isUrgent ? Priority.high : Priority.defaultPriority,
      icon: '@drawable/push_small',
      largeIcon: const DrawableResourceAndroidBitmap('@drawable/push'),
      styleInformation: BigTextStyleInformation(payload.content),
      autoCancel: true,
    );

    await _local.show(
      id,
      payload.title.isEmpty ? 'TMS 配送消息' : payload.title,
      payload.content,
      NotificationDetails(android: android),
      payload: raw,
    );
  }

  /// 处理通知点击。
  void _handleTap(String? raw) {
    if (raw == null || raw.isEmpty) {
      onNotificationTap?.call(const PushPayload());
      return;
    }
    onNotificationTap?.call(PushPayload.parse(raw));
  }

  /// 上报 CID 到后端，绑定到当前司机。
  ///
  /// 登录前 CID 就可能到达（个推初始化与登录是两条独立时间线），
  /// 此时后端无法从 JWT 取出 driverId，上报必然失败，
  /// 故此处校验登录态，登录成功后由 onLogin 再次触发。
  Future<void> registerToken() async {
    if (_clientId.isEmpty) return;
    if (AuthService.instance.current == null) {
      debugPrint('[Push] 尚未登录，暂缓上报 CID');
      return;
    }
    if (_reportedCid == _clientId) return;

    try {
      await ApiService.instance.post(
        '/tms/app/notification/register-token',
        body: {
          'deviceToken': _clientId,
          'channel': 'GETUI',
          'platform': Platform.isAndroid ? 'ANDROID' : 'IOS',
          'deviceModel': Platform.operatingSystemVersion,
          'appVersion': '1.3.0',
          'enabled': true,
        },
      );
      _reportedCid = _clientId;
      debugPrint('[Push] CID 上报成功');
    } catch (e) {
      // 上报失败不重试：下次启动或登录时会再走一遍，
      // 且站内轮询保证消息不丢，没必要在此处堆重试逻辑。
      debugPrint('[Push] CID 上报失败: $e');
    }
  }

  /// 登录成功后调用：补报登录前已拿到的 CID。
  Future<void> onLogin() async {
    await init();
    await registerToken();
  }

  /// 退出登录：解绑设备，避免换人登录后仍收到前一位司机的消息。
  Future<void> onLogout() async {
    if (_clientId.isEmpty) return;
    try {
      await ApiService.instance.post(
        '/tms/app/notification/register-token',
        body: {
          'deviceToken': _clientId,
          'channel': 'GETUI',
          'enabled': false,
        },
      );
    } catch (e) {
      debugPrint('[Push] 解绑失败: $e');
    }
    // 允许下次登录重新上报
    _reportedCid = '';
    await clearBadge();
  }

  /// 同步应用角标数（iOS 有效，Android 视厂商实现）。
  Future<void> setBadge(int count) async {
    if (!_inited) return;
    try {
      _getui.setBadge(count < 0 ? 0 : count);
    } catch (e) {
      debugPrint('[Push] 设置角标失败: $e');
    }
  }

  Future<void> clearBadge() async {
    await setBadge(0);
    try {
      await _local.cancelAll();
    } catch (e) {
      debugPrint('[Push] 清理通知失败: $e');
    }
  }

  /// 从个推回调 Map 中取出业务载荷字符串。
  ///
  /// 个推各平台/各消息类型的键名不统一（payload / payloadMsg / message），
  /// 逐个兜底比只认一个键更稳，否则换个下发方式就取不到内容。
  String _extractPayloadString(Map<String, dynamic> msg) {
    for (final k in ['payload', 'payloadMsg', 'message', 'content']) {
      final v = _str(msg[k]);
      if (v.isNotEmpty) return v;
    }
    return '';
  }

  String _str(Object? v) => v == null ? '' : v.toString();
}
