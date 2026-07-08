import 'dart:async';
import 'package:flutter/foundation.dart';
import 'package:geolocator/geolocator.dart';
import 'local_db_service.dart';

/// 位置采集服务（P6 离线能力）。
///
/// 职责：
/// 1. 定时采集 GPS 位置（每 15 秒一次）
/// 2. 写入本地 gps_tracks 表（不直接上报，由 SyncService 批量补传）
/// 3. 司机在途时自动开启，签收完成后停止
class LocationService {
  LocationService._();
  static final LocationService instance = LocationService._();

  Timer? _timer;
  String? _driverId;
  String? _dispatchId;
  String? _tripId;
  bool _running = false;

  /// 当前是否正在采集。
  bool get isRunning => _running;

  /// 开始采集（司机发车后调用）。
  Future<void> start({
    required String driverId,
    String? dispatchId,
    String? tripId,
  }) async {
    if (_running) return;
    _driverId = driverId;
    _dispatchId = dispatchId;
    _tripId = tripId;
    _running = true;

    // 立即采集一次
    await _collect();

    // 每 15 秒采集一次
    _timer = Timer.periodic(const Duration(seconds: 15), (_) => _collect());
    debugPrint('[Location] 开始采集: driver=$driverId, dispatch=$dispatchId');
  }

  /// 停止采集（全部签收完成后调用）。
  void stop() {
    _timer?.cancel();
    _timer = null;
    _running = false;
    _driverId = null;
    _dispatchId = null;
    _tripId = null;
    debugPrint('[Location] 停止采集');
  }

  /// 采集一次位置并缓存到本地。
  Future<void> _collect() async {
    if (!_running || _driverId == null) return;

    try {
      final serviceEnabled = await Geolocator.isLocationServiceEnabled();
      if (!serviceEnabled) return;

      var permission = await Geolocator.checkPermission();
      if (permission == LocationPermission.denied) {
        permission = await Geolocator.requestPermission();
        if (permission == LocationPermission.denied) return;
      }
      if (permission == LocationPermission.deniedForever) return;

      final position = await Geolocator.getCurrentPosition(
        locationSettings: const LocationSettings(
          accuracy: LocationAccuracy.medium,
          timeLimit: Duration(seconds: 10),
        ),
      );

      await LocalDbService.instance.cacheGpsTrack(
        driverId: _driverId!,
        dispatchId: _dispatchId,
        tripId: _tripId,
        longitude: position.longitude,
        latitude: position.latitude,
        speed: position.speed >= 0 ? position.speed : null,
        heading: position.heading >= 0 ? position.heading : null,
        accuracy: position.accuracy >= 0 ? position.accuracy : null,
        locTime: position.timestamp.toIso8601String(),
      );
    } catch (e) {
      debugPrint('[Location] 采集失败: $e');
    }
  }

  /// 释放资源。
  void dispose() {
    stop();
  }
}
