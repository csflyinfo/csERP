import 'dart:math' as math;

import 'package:flutter/foundation.dart';
import 'package:url_launcher/url_launcher.dart';

/// 外部应用唤起服务（P1）：地图导航与电话拨号。
///
/// 设计取舍：**不内嵌地图 SDK**，改为唤起司机手机上已装的地图 App。
///   - 无需申请高德/百度 Key，不必处理签名校验与 Web 端兼容；
///   - 司机对自己常用的地图更熟练，路线偏好、货车限行等能力也更完整。
///
/// 逐级降级策略（任一步成功即返回）：
///   1. 高德地图 URI Scheme（司机装机率最高）
///   2. 百度地图 URI Scheme（坐标需先转 BD09）
///   3. 系统标准 geo: 协议（交由系统弹窗选择）
///   4. 高德网页版（连地图 App 都没装时兜底）
class LaunchService {
  LaunchService._();
  static final LaunchService instance = LaunchService._();

  /// 用 defaultTargetPlatform 而非 Platform.isIOS：后者在 Web 端会抛异常。
  bool get _isIos => defaultTargetPlatform == TargetPlatform.iOS;

  /// 唤起地图导航。
  ///
  /// [longitude]/[latitude] 为门店档案坐标（GCJ02，与高德同源）；
  /// 坐标缺失时退化为按 [address] 关键字搜索，因此地址不应为空。
  /// 返回 false 表示所有方案都失败，调用方需给出提示。
  Future<bool> navigate({
    double? longitude,
    double? latitude,
    String address = '',
    String name = '',
  }) async {
    // Web 端没有 App 可唤起，直接走网页地图
    if (kIsWeb) {
      return _open(_amapWebUrl(longitude, latitude, address, name));
    }

    final hasGeo = longitude != null && latitude != null;
    final label = Uri.encodeComponent(name.isNotEmpty ? name : address);

    if (hasGeo) {
      // 高德：sourceApplication 必填，否则部分版本拒绝跳转；dev=0 表示传入的是 GCJ02
      final amapScheme = _isIos ? 'iosamap' : 'androidamap';
      if (await _open('$amapScheme://navi?sourceApplication=tms_driver'
          '&lat=$latitude&lon=$longitude&poiname=$label&dev=0&style=2')) {
        return true;
      }
      // 百度用 BD09 坐标系，必须先做偏移转换，否则会偏差数百米
      final bd = _gcj02ToBd09(longitude, latitude);
      if (await _open('baidumap://map/direction?destination=name:$label'
          '|latlng:${bd.$2},${bd.$1}&coord_type=bd09ll&mode=driving')) {
        return true;
      }
      // 系统 geo：Android 弹出地图选择器，iOS 走 Apple 地图
      if (await _open('geo:$latitude,$longitude?q=$latitude,$longitude($label)')) {
        return true;
      }
    } else if (address.isNotEmpty) {
      // 无坐标时按地址搜索：只能定位到大致位置，仍好过无从下手
      final amapScheme = _isIos ? 'iosamap' : 'androidamap';
      if (await _open('$amapScheme://keywordNavi'
          '?sourceApplication=tms_driver&keyword=$label&style=2')) {
        return true;
      }
      if (await _open('geo:0,0?q=$label')) return true;
    }

    return _open(_amapWebUrl(longitude, latitude, address, name));
  }

  /// 拨打电话。
  ///
  /// 用 `tel:` 而非 `ACTION_CALL`：只跳到拨号盘让司机确认，
  /// 既不需要 CALL_PHONE 危险权限，也避免误触直接外呼。
  Future<bool> dial(String phone) async {
    final clean = phone.replaceAll(RegExp(r'[^0-9+#*;,]'), '');
    if (clean.isEmpty) return false;
    return _open('tel:$clean');
  }

  /// 统一唤起入口，任何异常都吞掉并返回 false，交由上层降级。
  Future<bool> _open(String url) async {
    try {
      final uri = Uri.parse(url);
      // tel / geo / 自定义 scheme 在部分机型上 canLaunchUrl 会误报 false，
      // 因此不做前置判断，直接尝试并以返回值和异常作为失败信号。
      return await launchUrl(uri, mode: LaunchMode.externalApplication);
    } catch (_) {
      return false;
    }
  }

  String _amapWebUrl(double? lng, double? lat, String address, String name) {
    final label = Uri.encodeComponent(name.isNotEmpty ? name : address);
    if (lng != null && lat != null) {
      return 'https://uri.amap.com/navigation?to=$lng,$lat,$label&mode=car&coordinate=gaode';
    }
    return 'https://uri.amap.com/search?keyword=$label';
  }

  /// GCJ02（高德/腾讯）转 BD09（百度）。
  ///
  /// 两个坐标系存在数百米级固定偏移，跨系直传会导航到错误位置。
  /// 返回 (经度, 纬度)。
  (double, double) _gcj02ToBd09(double lng, double lat) {
    const xPi = math.pi * 3000.0 / 180.0;
    final z = math.sqrt(lng * lng + lat * lat) + 0.00002 * math.sin(lat * xPi);
    final theta = math.atan2(lat, lng) + 0.000003 * math.cos(lng * xPi);
    return (z * math.cos(theta) + 0.0065, z * math.sin(theta) + 0.006);
  }
}
