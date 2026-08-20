import 'dart:async';
import 'dart:convert';
import 'package:path/path.dart' as p;
import 'package:path_provider/path_provider.dart';
import 'package:sqflite/sqflite.dart';

/// 本地 SQLite 数据库服务（P6 离线能力）。
///
/// 表结构：
/// - cached_tasks:       缓存当天配送任务（调度单+明细快照）
/// - pending_actions:    离线操作队列（签收/装车/发车/退货等）
/// - gps_tracks:         GPS 轨迹本地缓存（待批量补传）
/// - sync_log:           同步日志（成功/失败记录，用于排查）
/// - sign_drafts:        签收草稿（v2 新增，签收当场不回传，等结算时统一提交）
///
/// 队列优先级（pending_actions.priority 字段）：
///   1=装车/发车  2=签收  3=照片上传  4=定位上报  5=其他
class LocalDbService {
  LocalDbService._();
  static final LocalDbService instance = LocalDbService._();

  Database? _db;

  /// 初始化数据库（App 启动时调用一次）。
  Future<Database> init() async {
    if (_db != null) return _db!;
    final docDir = await getApplicationDocumentsDirectory();
    final dbPath = p.join(docDir.path, 'tms_driver.db');
    _db = await openDatabase(
      dbPath,
      version: 2,
      onCreate: _onCreate,
      onUpgrade: _onUpgrade,
    );
    return _db!;
  }

  /// Web 模式下 sqflite 不可用，所有操作返回安全默认值。
  bool get isInitialized => _db != null;

  /// 队列变更广播。
  ///
  /// 为什么需要它：待同步/失败计数原先用普通 FutureProvider 暴露，只在首次读取时算一次。
  /// 结果是司机离线签收入队后顶部横幅数字不变、同步成功后也不会归零，
  /// 看起来像「单据没进队列」或「传完了还挂着」，反而误导人。
  /// 所有增删改队列的方法都要发一次通知，让 UI 的 StreamProvider 重算。
  final _queueChangedController = StreamController<void>.broadcast();
  Stream<void> get onQueueChanged => _queueChangedController.stream;

  void _notifyQueueChanged() {
    if (!_queueChangedController.isClosed) _queueChangedController.add(null);
  }

  Database get db {
    if (_db == null) throw StateError('LocalDbService 未初始化，请先调用 init()');
    return _db!;
  }

  Future<void> _onCreate(Database db, int version) async {
    // 1. 缓存任务表：存储当天拉取的配送任务快照
    await db.execute('''
      CREATE TABLE cached_tasks (
        dispatch_id   TEXT PRIMARY KEY,
        dispatch_no   TEXT,
        status        TEXT,
        vehicle_plate TEXT,
        route_line    TEXT,
        driver_id     TEXT,
        driver_name   TEXT,
        task_json     TEXT NOT NULL,
        cached_at     TEXT NOT NULL,
        synced_at     TEXT
      )
    ''');

    // 2. 离线操作队列：网络恢复后按优先级 + FIFO 上传
    await db.execute('''
      CREATE TABLE pending_actions (
        id            INTEGER PRIMARY KEY AUTOINCREMENT,
        action_type   TEXT NOT NULL,
        action_key    TEXT,
        method        TEXT NOT NULL,
        path          TEXT NOT NULL,
        body_json     TEXT,
        file_path     TEXT,
        biz_type      TEXT,
        priority      INTEGER NOT NULL DEFAULT 5,
        status        TEXT NOT NULL DEFAULT 'PENDING',
        retry_count   INTEGER NOT NULL DEFAULT 0,
        max_retry     INTEGER NOT NULL DEFAULT 5,
        error_msg     TEXT,
        created_at    TEXT NOT NULL,
        updated_at    TEXT NOT NULL
      )
    ''');
    await db.execute(
        'CREATE INDEX idx_pending_status_priority ON pending_actions(status, priority, id)');

    // 3. GPS 轨迹缓存：定时采集，批量补传
    await db.execute('''
      CREATE TABLE gps_tracks (
        id          INTEGER PRIMARY KEY AUTOINCREMENT,
        driver_id   TEXT NOT NULL,
        dispatch_id TEXT,
        trip_id     TEXT,
        longitude   REAL NOT NULL,
        latitude    REAL NOT NULL,
        speed       REAL,
        heading     REAL,
        accuracy    REAL,
        loc_time    TEXT NOT NULL,
        synced      INTEGER NOT NULL DEFAULT 0,
        created_at  TEXT NOT NULL
      )
    ''');
    await db.execute('CREATE INDEX idx_gps_synced ON gps_tracks(synced, id)');

    // 4. 同步日志
    await db.execute('''
      CREATE TABLE sync_log (
        id          INTEGER PRIMARY KEY AUTOINCREMENT,
        action_type TEXT,
        action_key  TEXT,
        status      TEXT NOT NULL,
        error_msg   TEXT,
        created_at  TEXT NOT NULL
      )
    ''');

    // 5. 签收草稿
    await _createSignDrafts(db);
  }

  /// 版本升级：只做增量建表/加列，不能重建已有表（会丢掉未上传的离线队列）。
  Future<void> _onUpgrade(Database db, int oldVersion, int newVersion) async {
    if (oldVersion < 2) await _createSignDrafts(db);
  }

  /// 签收草稿表（v2）。
  ///
  /// 为什么要落本地：业务要求「单个签收不回传后台，结算后统一更新后台单据数据」。
  /// 司机可能签了 3 张单就退出页面去接电话，回来还得看到之前录的数量，
  /// 所以草稿不能只放内存，必须落库。
  ///
  /// 主键取 detail_id：一张调度明细对应一张单据，重复签收就是覆盖草稿。
  /// draft_json 存整包录入结果（items/拒收/签名/照片/备注），
  /// 结构与 /tms/app/sign 的入参保持一致，结算时可原样打包上传，
  /// 避免在两处维护两套字段映射。
  Future<void> _createSignDrafts(Database db) async {
    await db.execute('''
      CREATE TABLE IF NOT EXISTS sign_drafts (
        detail_id       TEXT PRIMARY KEY,
        dispatch_id     TEXT,
        customer_code   TEXT,
        source_bill_no  TEXT,
        bill_type       TEXT,
        sign_type       TEXT,
        signed_qty      REAL NOT NULL DEFAULT 0,
        reject_qty      REAL NOT NULL DEFAULT 0,
        sign_amount     REAL NOT NULL DEFAULT 0,
        draft_json      TEXT NOT NULL,
        settled         INTEGER NOT NULL DEFAULT 0,
        created_at      TEXT NOT NULL,
        updated_at      TEXT NOT NULL
      )
    ''');
    await db.execute(
        'CREATE INDEX IF NOT EXISTS idx_sign_draft_customer ON sign_drafts(customer_code, settled)');
  }

  // ==================== sign_drafts 签收草稿 ====================

  /// 保存/覆盖一条签收草稿。
  Future<void> saveSignDraft({
    required String detailId,
    required String dispatchId,
    required String customerCode,
    required String sourceBillNo,
    required String billType,
    required String signType,
    required double signedQty,
    required double rejectQty,
    required double signAmount,
    required Map<String, dynamic> draft,
  }) async {
    if (!isInitialized) return;
    final now = DateTime.now().toIso8601String();
    await db.insert(
      'sign_drafts',
      {
        'detail_id': detailId,
        'dispatch_id': dispatchId,
        'customer_code': customerCode,
        'source_bill_no': sourceBillNo,
        'bill_type': billType,
        'sign_type': signType,
        'signed_qty': signedQty,
        'reject_qty': rejectQty,
        'sign_amount': signAmount,
        'draft_json': jsonEncode(draft),
        'settled': 0,
        'created_at': now,
        'updated_at': now,
      },
      conflictAlgorithm: ConflictAlgorithm.replace,
    );
  }

  /// 读某配送点下全部未结算草稿（结算页取数用）。
  Future<List<Map<String, dynamic>>> getSignDrafts(String customerCode) async {
    if (!isInitialized) return const [];
    return db.query('sign_drafts',
        where: 'customer_code = ? AND settled = 0',
        whereArgs: [customerCode],
        orderBy: 'created_at');
  }

  /// 读单条草稿（签收页二次进入时回填用）。
  Future<Map<String, dynamic>?> getSignDraft(String detailId) async {
    if (!isInitialized) return null;
    final rows =
        await db.query('sign_drafts', where: 'detail_id = ?', whereArgs: [detailId], limit: 1);
    return rows.isEmpty ? null : rows.first;
  }

  /// 结算成功后清理已提交的草稿。
  ///
  /// 直接删而不是置 settled=1：草稿的唯一用途是「等结算」，
  /// 结算完成后后台已有正式签收流水，本地留着只会让下次进店误判成未签收。
  Future<void> deleteSignDrafts(List<String> detailIds) async {
    if (!isInitialized || detailIds.isEmpty) return;
    final marks = List.filled(detailIds.length, '?').join(',');
    await db.delete('sign_drafts', where: 'detail_id IN ($marks)', whereArgs: detailIds);
  }

  // ==================== pending_actions 队列操作 ====================

  /// 入队一条离线操作。
  /// actionType: LOADING_START / LOADING_CONFIRM / DEPART / SIGN / RETURN_SIGN / UPLOAD_PHOTO / GPS_REPORT 等
  /// priority: 1(装车/发车) 2(签收) 3(照片) 4(定位) 5(其他)
  Future<int> enqueueAction({
    required String actionType,
    String? actionKey,
    required String method,
    required String path,
    Map<String, dynamic>? body,
    String? filePath,
    String? bizType,
    int priority = 5,
    int maxRetry = 5,
  }) async {
    final now = DateTime.now().toIso8601String();
    final id = await db.insert('pending_actions', {
      'action_type': actionType,
      'action_key': actionKey,
      'method': method,
      'path': path,
      'body_json': body != null ? jsonEncode(body) : null,
      'file_path': filePath,
      'biz_type': bizType,
      'priority': priority,
      'status': 'PENDING',
      'retry_count': 0,
      'max_retry': maxRetry,
      'created_at': now,
      'updated_at': now,
    });
    _notifyQueueChanged();
    return id;
  }

  /// 取出待处理操作（按优先级升序 + FIFO）。
  Future<List<Map<String, dynamic>>> getPendingActions({int limit = 10}) async {
    return await db.query(
      'pending_actions',
      where: "status = 'PENDING'",
      orderBy: 'priority ASC, id ASC',
      limit: limit,
    );
  }

  /// 标记成功并删除。
  Future<void> markActionSuccess(int id) async {
    await db.delete('pending_actions', where: 'id = ?', whereArgs: [id]);
    _notifyQueueChanged();
  }

  /// 标记失败，增加重试计数；超过最大重试次数标记为 FAILED。
  Future<void> markActionFailed(int id, String error) async {
    final rows = await db.query('pending_actions',
        where: 'id = ?', whereArgs: [id], limit: 1);
    if (rows.isEmpty) return;
    final row = rows.first;
    int retryCount = (row['retry_count'] as int?) ?? 0;
    int maxRetry = (row['max_retry'] as int?) ?? 5;
    retryCount++;
    String status = retryCount >= maxRetry ? 'FAILED' : 'PENDING';
    await db.update(
      'pending_actions',
      {
        'retry_count': retryCount,
        'status': status,
        'error_msg': error,
        'updated_at': DateTime.now().toIso8601String(),
      },
      where: 'id = ?',
      whereArgs: [id],
    );
    _notifyQueueChanged();
  }

  /// 统计待处理数量（UI 提示用）。
  Future<int> pendingCount() async {
    final result = await db.rawQuery(
        "SELECT COUNT(*) as cnt FROM pending_actions WHERE status = 'PENDING'");
    return Sqflite.firstIntValue(result) ?? 0;
  }

  /// 统计失败数量。
  Future<int> failedCount() async {
    final result = await db.rawQuery(
        "SELECT COUNT(*) as cnt FROM pending_actions WHERE status = 'FAILED'");
    return Sqflite.firstIntValue(result) ?? 0;
  }

  /// 查询队列明细（待同步 + 已失败），供同步中心展示。
  ///
  /// 同时返回两种状态而不是只查 FAILED：司机需要看到「还有几条在排队」，
  /// 只列失败项会让他以为剩下的都传完了。
  Future<List<Map<String, dynamic>>> getQueuedActions({int limit = 100}) async {
    return await db.query(
      'pending_actions',
      where: "status IN ('PENDING', 'FAILED')",
      orderBy: "CASE status WHEN 'FAILED' THEN 0 ELSE 1 END, priority ASC, id ASC",
      limit: limit,
    );
  }

  /// 把 FAILED 记录重置回 PENDING，让下一轮同步重新捞取。
  ///
  /// 之所以必须有这个入口：markActionFailed 在重试超限后置为 FAILED，
  /// 而 getPendingActions 只查 PENDING，FAILED 记录再也不会被自动重试。
  /// 若不提供重置手段，一次网络抖动耗尽重试次数后，
  /// 这张单据就永久沉在本地库里，司机和后台都拿不到——必须让司机能手动救回。
  ///
  /// retry_count 一并归零：否则重置后第一次失败就又立刻超限，等于没重试。
  /// 传 id 为空则重置全部失败项。
  Future<int> resetFailedActions({int? id}) async {
    final n = await db.update(
      'pending_actions',
      {
        'status': 'PENDING',
        'retry_count': 0,
        'error_msg': null,
        'updated_at': DateTime.now().toIso8601String(),
      },
      where: id == null ? "status = 'FAILED'" : "id = ? AND status = 'FAILED'",
      whereArgs: id == null ? null : [id],
    );
    _notifyQueueChanged();
    return n;
  }

  /// 彻底删除一条队列记录（司机确认放弃该单据时使用）。
  Future<void> deleteAction(int id) async {
    await db.delete('pending_actions', where: 'id = ?', whereArgs: [id]);
    _notifyQueueChanged();
  }

  // ==================== cached_tasks 操作 ====================

  /// 缓存任务快照（登录后/刷新时调用）。
  Future<void> cacheTask(String dispatchId, Map<String, dynamic> taskData) async {
    final now = DateTime.now().toIso8601String();
    await db.insert('cached_tasks', {
      'dispatch_id': dispatchId,
      'dispatch_no': taskData['dispatchNo'] ?? '',
      'status': taskData['status'] ?? '',
      'vehicle_plate': taskData['vehiclePlate'] ?? '',
      'route_line': taskData['routeLine'] ?? '',
      'driver_id': taskData['driverId'] ?? '',
      'driver_name': taskData['driverName'] ?? '',
      'task_json': jsonEncode(taskData),
      'cached_at': now,
      'synced_at': now,
    }, conflictAlgorithm: ConflictAlgorithm.replace);
  }

  /// 批量缓存任务。
  Future<void> cacheTasks(List<Map<String, dynamic>> tasks) async {
    final batch = db.batch();
    for (final t in tasks) {
      final dispatchId = t['dispatchId']?.toString() ?? '';
      if (dispatchId.isEmpty) continue;
      final now = DateTime.now().toIso8601String();
      batch.insert('cached_tasks', {
        'dispatch_id': dispatchId,
        'dispatch_no': t['dispatchNo'] ?? '',
        'status': t['status'] ?? '',
        'vehicle_plate': t['vehiclePlate'] ?? '',
        'route_line': t['routeLine'] ?? '',
        'driver_id': t['driverId'] ?? '',
        'driver_name': t['driverName'] ?? '',
        'task_json': jsonEncode(t),
        'cached_at': now,
        'synced_at': now,
      }, conflictAlgorithm: ConflictAlgorithm.replace);
    }
    await batch.commit(noResult: true);
  }

  /// 读取所有缓存任务。
  Future<List<Map<String, dynamic>>> getCachedTasks() async {
    final rows = await db.query('cached_tasks', orderBy: 'dispatch_no ASC');
    return rows.map((r) {
      final json = r['task_json'] as String?;
      return json != null ? jsonDecode(json) as Map<String, dynamic> : <String, dynamic>{};
    }).toList();
  }

  /// 按 dispatchId 读取缓存任务。
  Future<Map<String, dynamic>?> getCachedTask(String dispatchId) async {
    final rows = await db.query('cached_tasks',
        where: 'dispatch_id = ?', whereArgs: [dispatchId], limit: 1);
    if (rows.isEmpty) return null;
    final json = rows.first['task_json'] as String?;
    return json != null ? jsonDecode(json) as Map<String, dynamic> : null;
  }

  /// 清理过期缓存（保留当天）。
  Future<int> cleanExpiredTasks() async {
    final today = DateTime.now();
    final cutoff = DateTime(today.year, today.month, today.day)
        .toIso8601String();
    return await db.delete('cached_tasks',
        where: 'cached_at < ?', whereArgs: [cutoff]);
  }

  // ==================== gps_tracks 操作 ====================

  /// 缓存一条 GPS 轨迹。
  Future<int> cacheGpsTrack({
    required String driverId,
    String? dispatchId,
    String? tripId,
    required double longitude,
    required double latitude,
    double? speed,
    double? heading,
    double? accuracy,
    required String locTime,
  }) async {
    return await db.insert('gps_tracks', {
      'driver_id': driverId,
      'dispatch_id': dispatchId,
      'trip_id': tripId,
      'longitude': longitude,
      'latitude': latitude,
      'speed': speed,
      'heading': heading,
      'accuracy': accuracy,
      'loc_time': locTime,
      'synced': 0,
      'created_at': DateTime.now().toIso8601String(),
    });
  }

  /// 取未同步的 GPS 轨迹（批量补传用）。
  Future<List<Map<String, dynamic>>> getUnsyncedGpsTracks({int limit = 100}) async {
    return await db.query('gps_tracks',
        where: 'synced = 0', orderBy: 'id ASC', limit: limit);
  }

  /// 标记 GPS 轨迹已同步（批量）。
  Future<void> markGpsSynced(List<int> ids) async {
    if (ids.isEmpty) return;
    final placeholders = List.filled(ids.length, '?').join(',');
    await db.rawUpdate(
        'UPDATE gps_tracks SET synced = 1 WHERE id IN ($placeholders)', ids);
  }

  /// 清理已同步的 GPS 轨迹（保留最近 7 天）。
  Future<int> cleanSyncedGpsTracks() async {
    final cutoff =
        DateTime.now().subtract(const Duration(days: 7)).toIso8601String();
    return await db.delete('gps_tracks',
        where: 'synced = 1 AND created_at < ?', whereArgs: [cutoff]);
  }

  /// 未同步 GPS 数量。
  Future<int> unsyncedGpsCount() async {
    final result =
        await db.rawQuery('SELECT COUNT(*) as cnt FROM gps_tracks WHERE synced = 0');
    return Sqflite.firstIntValue(result) ?? 0;
  }

  // ==================== sync_log 操作 ====================

  Future<void> logSync(String actionType, String? actionKey, String status,
      {String? error}) async {
    await db.insert('sync_log', {
      'action_type': actionType,
      'action_key': actionKey,
      'status': status,
      'error_msg': error,
      'created_at': DateTime.now().toIso8601String(),
    });
  }
}
