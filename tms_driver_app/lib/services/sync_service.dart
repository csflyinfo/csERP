import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'package:flutter/foundation.dart';
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
    if (_syncing) return const SyncResult(skippedAll: true);
    if (!ConnectivityService.instance.isOnline) {
      return const SyncResult(skippedAll: true);
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
    final path = action['path'] as String;
    final bodyJson = action['body_json'] as String?;
    final filePath = action['file_path'] as String?;
    final bizType = action['biz_type'] as String?;
    final actionType = action['action_type'] as String;

    // pending_actions.method 是必填列，但这里只会用 POST 重放。
    // 保留断言而不是直接忽略该列：若日后有人入队 PUT/DELETE，
    // 静默按 POST 发出去会打到错误的接口语义上，debug 期就该炸出来。
    final method = (action['method'] as String?) ?? 'POST';
    assert(method.toUpperCase() == 'POST',
        '同步重放仅支持 POST，收到 $method（$path），需先扩展 _processAction');

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
          await _uploadDeferredMedia(body);
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

  /// body 里承载单张图片 URL 的字段名 → 上传业务类型。
  ///
  /// 这些字段离线时存的是本地路径，重放前需逐个补传。
  /// 用显式白名单而不是「扫所有 String 字段找像路径的值」：
  /// 备注、原因这类自由文本完全可能被司机打进类似路径的内容，误传会污染对象存储。
  static const Map<String, String> _deferredUrlFields = {
    'signatureUrl': 'SIGNATURE',
    'signatureImg': 'SIGNATURE',
    'storePhotoUrl': 'STORE',
  };

  /// 重放前把 body 里延后上传的本地图片补传成远端 URL。
  ///
  /// 离线提交单据时照片还没上传，body 里 photos[].url 与各单图字段
  /// 暂存的是本地绝对路径。这里在真正 POST 主单之前先补传，
  /// 保证后端永远只收到 http URL，也不必新增「照片回填」类接口。
  ///
  /// 补传失败会直接抛出，让整条 action 记为失败留在队列里下轮重试：
  /// 不能跳过照片继续提交主单，否则单据落库了但留证照片永久丢失，
  /// 而拒收、货损这类单子的照片就是事后唯一凭据。
  ///
  /// 文件已不存在（系统清了缓存）是唯一的例外：此时重试再多次也拿不回文件，
  /// 只能丢掉这一张、保住主单，否则整张单据会被一张缺失的图片永久卡死。
  Future<void> _uploadDeferredMedia(Map<String, dynamic> body) async {
    for (final entry in _deferredUrlFields.entries) {
      final v = body[entry.key];
      if (v is! String || v.isEmpty || ApiService.isRemoteUrl(v)) continue;
      final url = await _uploadLocal(v, entry.value);
      if (url == null) {
        body[entry.key] = '';
      } else {
        body[entry.key] = url;
      }
    }

    // photos 有两种形态，必须都支持：
    // - List<Map>：签收/拒收/改派/退货创建，元素含 url、photoType、bizType；
    // - List<String>：退货签收（/return/sign），后端直接按 List<String> 反序列化。
    // 早先只处理 Map，字符串元素会被 continue 跳过，
    // 导致退货签收离线重放时照片被静默丢弃——单据落库了但一张留证都没有。
    final photos = body['photos'];
    if (photos is! List) return;
    final kept = <dynamic>[];
    for (final p in photos) {
      if (p is String) {
        if (p.isEmpty) continue;
        if (ApiService.isRemoteUrl(p)) {
          kept.add(p);
          continue;
        }
        // 纯字符串形态无处携带 bizType，按所属单据类型取默认值。
        final url = await _uploadLocal(p, 'RETURN');
        if (url == null) continue; // 文件已丢失，跳过这张
        kept.add(url);
        continue;
      }
      if (p is! Map) continue;
      final item = Map<String, dynamic>.from(p);
      final raw = item['url'];
      if (raw is String && raw.isNotEmpty && !ApiService.isRemoteUrl(raw)) {
        final url = await _uploadLocal(
            raw, (item['bizType'] as String?) ?? 'SIGN');
        if (url == null) continue; // 文件已丢失，跳过这张
        item['url'] = url;
      }
      item.remove('bizType');
      item.remove('_deferred');
      kept.add(item);
    }
    body['photos'] = kept;
  }

  /// 上传本地图片，文件不存在时返回 null（调用方据此丢弃该条）。
  Future<String?> _uploadLocal(String localPath, String bizType) async {
    final file = File(localPath);
    if (!await file.exists()) {
      debugPrint('[Sync] 延后上传的图片已不存在，跳过: $localPath');
      return null;
    }
    final res = await ApiService.instance.uploadImage(file, bizType: bizType);
    return res['url'] as String;
  }

  /// GPS 轨迹批量补传。
  Future<SyncResult> _syncGpsTracks() async {
    final tracks =
        await LocalDbService.instance.getUnsyncedGpsTracks(limit: 100);
    if (tracks.isEmpty) return const SyncResult();

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

  /// 手动重试：把失败项重置回待同步并立即跑一轮。
  ///
  /// 传 id 只重试单条，不传则重试全部失败项。
  /// 放在服务层而不是让 UI 直接调 LocalDbService：重置状态本身不产生任何效果，
  /// 必须紧接着触发一轮同步，否则司机点了「重试」却什么都没发生，
  /// 只能等下次网络变化事件——体验上等于按钮失灵。
  Future<SyncResult> retryFailed({int? id}) async {
    if (!LocalDbService.instance.isInitialized) {
      return const SyncResult(skippedAll: true);
    }
    await LocalDbService.instance.resetFailedActions(id: id);
    return syncAll();
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
///
/// 用 StreamProvider 而不是 FutureProvider：FutureProvider 只在首次读取时算一次，
/// 司机离线签收入队后横幅数字不动、同步成功后也不归零，
/// 界面会长期停在一个过期数字上——比不显示更糟。
/// 这里订阅 LocalDbService.onQueueChanged，队列一变就重算。
final pendingCountProvider = StreamProvider<int>((ref) {
  return _queueMetric(() => LocalDbService.instance.pendingCount(), 0);
});

/// 同步失败数量 Provider。
///
/// 与 pendingCount 分开暴露：待同步是「正常排队，等联网就好」，
/// 失败是「已经重试到上限，不介入就永久丢单」，两者对司机的含义完全不同，
/// UI 必须用不同颜色和措辞区分，不能合成一个数字。
final failedCountProvider = StreamProvider<int>((ref) {
  return _queueMetric(() => LocalDbService.instance.failedCount(), 0);
});

/// 队列明细 Provider（同步中心列表用）。
final queuedActionsProvider = StreamProvider<List<Map<String, dynamic>>>((ref) {
  return _queueMetric<List<Map<String, dynamic>>>(
    () => LocalDbService.instance.getQueuedActions(),
    const [],
  );
});

/// 队列派生数据的统一取值流：先发一次当前值，之后每次队列变更重算。
///
/// 未初始化时（Web）只发兜底值且不订阅，避免 sqflite 不可用导致崩溃。
Stream<T> _queueMetric<T>(Future<T> Function() read, T fallback) async* {
  if (!LocalDbService.instance.isInitialized) {
    yield fallback;
    return;
  }
  yield await read();
  await for (final _ in LocalDbService.instance.onQueueChanged) {
    yield await read();
  }
}
