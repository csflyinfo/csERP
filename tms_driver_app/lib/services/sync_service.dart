import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../services/api_service.dart';
import '../services/connectivity_service.dart';
import '../services/local_db_service.dart';

/// 离线同步服务（P6 离线能力核心）。
///
/// 职责：
/// 1. 网络恢复后自动消费 pending_actions 队列（按优先级 + FIFO）
/// 2. GPS 轨迹批量补传
/// 3. 同步状态广播（UI 显示同步进度）
///
/// 优先级：
///   1=装车/发车  2=签收  3=照片上传  4=定位上报  5=其他
class SyncService {
  SyncService._();
  static final SyncService instance = SyncService._();

  bool _syncing = false;
  Timer? _gpsTimer;
  StreamSubscription<bool>? _connectivitySub;

  final _syncStateController = StreamController<SyncState>.broadcast();
  Stream<SyncState> get onSyncStateChanged => _syncStateController.stream;

  /// 初始化（App 启动后调用）。
  Future<void> init() async {
    // 监听网络恢复，自动触发同步
    _connectivitySub =
        ConnectivityService.instance.onOnlineChanged.listen((online) {
      if (online) {
        syncAll();
      }
    });

    // 启动 GPS 定时补传（每 30 秒检查一次）
    _gpsTimer = Timer.periodic(const Duration(seconds: 30), (_) {
      if (ConnectivityService.instance.isOnline) {
        _syncGpsTracks();
      }
    });
  }

  /// 同步所有待处理操作（手动触发或网络恢复自动触发）。
  Future<SyncResult> syncAll() async {
    if (_syncing) return SyncResult(skippedAll: true);
    if (!ConnectivityService.instance.isOnline) {
      return SyncResult(skippedAll: true);
    }

    _syncing = true;
    _syncStateController.add(SyncState.syncing);

    int success = 0;
    int failed = 0;
    int skipped = 0;

    try {
      // 1. 先同步 pending_actions（按优先级）
      final actions = await LocalDbService.instance.getPendingActions(limit: 20);
      for (final action in actions) {
        final result = await _processAction(action);
        if (result) {
          success++;
        } else {
          failed++;
        }
      }

      // 2. 再同步 GPS 轨迹
      final gpsResult = await _syncGpsTracks();
      success += gpsResult.success;
      failed += gpsResult.failed;

      // 3. 清理过期缓存
      await LocalDbService.instance.cleanExpiredTasks();
      await LocalDbService.instance.cleanSyncedGpsTracks();
    } finally {
      _syncing = false;
      _syncStateController.add(SyncState.idle);
    }

    return SyncResult(success: success, failed: failed, skipped: skipped);
  }

  /// 处理单条 pending_action。
  Future<bool> _processAction(Map<String, dynamic> action) async {
    final id = action['id'] as int;
    final method = action['method'] as String;
    final path = action['path'] as String;
    final bodyJson = action['body_json'] as String?;
    final filePath = action['file_path'] as String?;
    final bizType = action['biz_type'] as String?;
    final actionType = action['action_type'] as String;

    try {
      if (filePath != null) {
        // 文件上传（照片）
        final file = File(filePath);
        if (!await file.exists()) {
          await LocalDbService.instance.markActionSuccess(id);
          return true; // 文件已不存在，视为已处理
        }
        await ApiService.instance.uploadImage(file,
            bizType: bizType ?? 'SIGN');
      } else {
        // 普通 JSON 请求
        Map<String, dynamic>? body;
        if (bodyJson != null && bodyJson.isNotEmpty) {
          body = jsonDecode(bodyJson) as Map<String, dynamic>;
        }
        await ApiService.instance.post(path, body: body);
      }

      await LocalDbService.instance.markActionSuccess(id);
      await LocalDbService.instance
          .logSync(actionType, action['action_key'] as String?, 'SUCCESS');
      return true;
    } catch (e) {
      await LocalDbService.instance.markActionFailed(id, e.toString());
      await LocalDbService.instance
          .logSync(actionType, action['action_key'] as String?, 'FAILED', error: e.toString());
      return false;
    }
  }

  /// GPS 轨迹批量补传。
  Future<SyncResult> _syncGpsTracks() async {
    final tracks =
        await LocalDbService.instance.getUnsyncedGpsTracks(limit: 100);
    if (tracks.isEmpty) return SyncResult();

    final locations = tracks.map((t) {
      return {
        'dispatchId': t['dispatch_id'],
        'tripId': t['trip_id'],
        'longitude': t['longitude'],
        'latitude': t['latitude'],
        'speed': t['speed'],
        'heading': t['heading'],
        'accuracy': t['accuracy'],
        'locTime': t['loc_time'],
      };
    }).toList();

    try {
      await ApiService.instance
          .post('/tms/app/location/batch-report', body: {
        'locations': locations,
      });
      final ids = tracks.map((t) => t['id'] as int).toList();
      await LocalDbService.instance.markGpsSynced(ids);
      return SyncResult(success: tracks.length);
    } catch (e) {
      return SyncResult(failed: tracks.length);
    }
  }

  /// 释放资源。
  void dispose() {
    _gpsTimer?.cancel();
    _connectivitySub?.cancel();
    _syncStateController.close();
  }
}

/// 同步状态。
enum SyncState { idle, syncing }

/// 同步结果。
class SyncResult {
  final int success;
  final int failed;
  final int skipped;
  final bool skippedAll;

  const SyncResult({
    this.success = 0,
    this.failed = 0,
    this.skipped = 0,
    this.skippedAll = false,
  });
}

/// 同步状态 Provider（UI 监听用）。
final syncStateProvider = StreamProvider<SyncState>((ref) {
  return SyncService.instance.onSyncStateChanged;
});

/// 待处理数量 Provider（UI 展示徽标用，Web 模式下返回 0）。
final pendingCountProvider = FutureProvider<int>((ref) async {
  if (!LocalDbService.instance.isInitialized) return 0;
  return LocalDbService.instance.pendingCount();
});
