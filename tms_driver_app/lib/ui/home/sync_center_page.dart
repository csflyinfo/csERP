import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../config/theme.dart';
import '../../services/connectivity_service.dart';
import '../../services/local_db_service.dart';
import '../../services/sync_service.dart';

/// 同步中心（离线队列可见性 + 手动重试）。
///
/// 为什么必须有这个页面：所有单据都支持离线入队后，队列变成了「司机已经干完、
/// 但公司还没收到」的数据。此前 APP 只在首页显示一个「N 条待同步」的数字，
/// 失败项完全不可见——而 markActionFailed 在重试超限后会置为 FAILED，
/// getPendingActions 只捞 PENDING，这条单据就再也不会被自动重试。
/// 没有这个入口，司机干的活会静默消失，且没人知道消失了什么。
class SyncCenterPage extends ConsumerWidget {
  const SyncCenterPage({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final online = ref.watch(isOnlineProvider).valueOrNull ?? true;
    final queued = ref.watch(queuedActionsProvider);
    final syncing =
        (ref.watch(syncStateProvider).valueOrNull ?? SyncState.idle) ==
            SyncState.syncing;

    return Scaffold(
      backgroundColor: TmsTheme.bg,
      appBar: AppBar(
        title: const Text('同步中心'),
        actions: [
          IconButton(
            tooltip: '刷新',
            icon: const Icon(Icons.refresh, size: 20),
            onPressed: () => _refresh(ref),
          ),
        ],
      ),
      body: Column(children: [
        _StatusHeader(online: online, syncing: syncing),
        Expanded(
          child: queued.when(
            loading: () =>
                const Center(child: CircularProgressIndicator(strokeWidth: 2)),
            error: (e, _) => Center(
              child: Padding(
                padding: const EdgeInsets.all(24),
                child: Text(
                  '队列读取失败：${e.toString().replaceFirst("Exception: ", "")}',
                  style: const TextStyle(fontSize: 12, color: TmsTheme.muted),
                ),
              ),
            ),
            data: (rows) => rows.isEmpty
                ? _empty()
                : RefreshIndicator(
                    onRefresh: () async => _refresh(ref),
                    child: ListView.separated(
                      padding: const EdgeInsets.all(14),
                      itemCount: rows.length,
                      separatorBuilder: (_, __) => const SizedBox(height: 10),
                      itemBuilder: (_, i) => _ActionCard(
                        row: rows[i],
                        onRetry: () => _retry(context, rows[i]),
                        onDiscard: () => _discard(context, rows[i]),
                      ),
                    ),
                  ),
          ),
        ),
        _BottomBar(
          online: online,
          syncing: syncing,
          onSyncAll: () => _syncAll(context),
        ),
      ]),
    );
  }

  Widget _empty() => const Center(
        child: Column(mainAxisAlignment: MainAxisAlignment.center, children: [
          Icon(Icons.cloud_done_outlined, size: 48, color: TmsTheme.ok),
          SizedBox(height: 12),
          Text('全部已同步', style: TextStyle(fontSize: 14, color: TmsTheme.ink)),
          SizedBox(height: 4),
          Text('没有待上传或失败的操作',
              style: TextStyle(fontSize: 12, color: TmsTheme.muted)),
        ]),
      );

  /// 手动刷新。
  ///
  /// 三个 Provider 已订阅队列变更、增删后会自动重算，正常情况下无需调用；
  /// 保留下拉/按钮刷新是为了兜底——若某处遗漏了变更通知，
  /// 司机至少还有手段把界面拉到最新，而不是对着过期数字干瞪眼。
  void _refresh(WidgetRef ref) {
    ref.invalidate(queuedActionsProvider);
    ref.invalidate(pendingCountProvider);
    ref.invalidate(failedCountProvider);
  }

  Future<void> _syncAll(BuildContext context) async {
    final messenger = ScaffoldMessenger.of(context);
    if (!ConnectivityService.instance.isOnline) {
      messenger.showSnackBar(
          const SnackBar(content: Text('当前无网络，请连上网络后再同步')));
      return;
    }
    // 一并把失败项重置回待同步：司机点「立即同步」的预期是「把所有没传的都传上去」，
    // 若只跑 PENDING，界面上那几条红色失败项点了没反应，会以为是 APP 坏了。
    final result = await SyncService.instance.retryFailed();
    if (!context.mounted) return;
    messenger.showSnackBar(SnackBar(
      content: Text(result.skippedAll
          ? '同步已在进行中或当前不可同步'
          : '同步完成：成功 ${result.success} 条，失败 ${result.failed} 条'),
    ));
  }

  Future<void> _retry(
      BuildContext context, Map<String, dynamic> row) async {
    final messenger = ScaffoldMessenger.of(context);
    if (!ConnectivityService.instance.isOnline) {
      messenger.showSnackBar(
          const SnackBar(content: Text('当前无网络，请连上网络后再重试')));
      return;
    }
    final result = await SyncService.instance.retryFailed(id: row['id'] as int);
    if (!context.mounted) return;
    messenger.showSnackBar(SnackBar(
      content: Text(result.success > 0 ? '重试成功' : '重试未成功，可稍后再试'),
    ));
  }

  /// 放弃某条队列记录。
  ///
  /// 二次确认且措辞写明后果：删掉意味着这单在公司系统里从未发生过，
  /// 司机得回去重做一遍。不做确认的话误触一次就是丢单。
  Future<void> _discard(
      BuildContext context, Map<String, dynamic> row) async {
    final messenger = ScaffoldMessenger.of(context);
    final ok = await showDialog<bool>(
      context: context,
      builder: (_) => AlertDialog(
        title: const Text('放弃这条记录？'),
        content: Text(
          '${_typeLabel(row['action_type'] as String?)}（${row['action_key'] ?? ''}）\n\n'
          '放弃后这条操作不会再上传，公司系统里等于从未发生，需要你重新操作一次。',
          style: const TextStyle(fontSize: 13),
        ),
        actions: [
          TextButton(
              onPressed: () => Navigator.pop(context, false),
              child: const Text('取消')),
          TextButton(
            onPressed: () => Navigator.pop(context, true),
            child: const Text('确认放弃', style: TextStyle(color: TmsTheme.bad)),
          ),
        ],
      ),
    );
    if (ok != true) return;
    await LocalDbService.instance.deleteAction(row['id'] as int);
    if (!context.mounted) return;
    messenger.showSnackBar(const SnackBar(content: Text('已放弃该记录')));
  }
}

/// 顶部状态区：网络 + 待同步/失败计数。
class _StatusHeader extends ConsumerWidget {
  const _StatusHeader({required this.online, required this.syncing});
  final bool online;
  final bool syncing;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final pending = ref.watch(pendingCountProvider).valueOrNull ?? 0;
    final failed = ref.watch(failedCountProvider).valueOrNull ?? 0;
    return Container(
      width: double.infinity,
      color: Colors.white,
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Row(children: [
          Icon(online ? Icons.cloud_queue : Icons.cloud_off,
              size: 18, color: online ? TmsTheme.ok : TmsTheme.bad),
          const SizedBox(width: 8),
          Text(
            syncing ? '正在同步...' : (online ? '网络正常' : '当前离线'),
            style: const TextStyle(
                fontSize: 14, fontWeight: FontWeight.w700, color: TmsTheme.ink),
          ),
        ]),
        const SizedBox(height: 12),
        Row(children: [
          _cell('待同步', '$pending', TmsTheme.accent2),
          _cell('已失败', '$failed', failed > 0 ? TmsTheme.bad : TmsTheme.muted),
        ]),
        if (failed > 0) ...[
          const SizedBox(height: 10),
          const Text(
            '失败的操作已达重试上限，不会自动重传，需要你手动点击重试。',
            style: TextStyle(fontSize: 11, color: TmsTheme.bad),
          ),
        ],
      ]),
    );
  }

  Widget _cell(String label, String value, Color color) => Expanded(
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Text(value,
              style: TextStyle(
                  fontSize: 20, fontWeight: FontWeight.w700, color: color)),
          const SizedBox(height: 2),
          Text(label,
              style: const TextStyle(fontSize: 11, color: TmsTheme.muted)),
        ]),
      );
}

/// 单条队列记录卡片。
class _ActionCard extends StatelessWidget {
  const _ActionCard({
    required this.row,
    required this.onRetry,
    required this.onDiscard,
  });

  final Map<String, dynamic> row;
  final VoidCallback onRetry;
  final VoidCallback onDiscard;

  @override
  Widget build(BuildContext context) {
    final isFailed = (row['status'] as String?) == 'FAILED';
    final retryCount = (row['retry_count'] as int?) ?? 0;
    final maxRetry = (row['max_retry'] as int?) ?? 5;
    final error = (row['error_msg'] as String?) ?? '';
    return Container(
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: Colors.white,
        borderRadius: BorderRadius.circular(12),
        border: isFailed
            ? Border.all(color: TmsTheme.bad.withValues(alpha: 0.35))
            : null,
      ),
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Row(children: [
          Expanded(
            child: Text(
              _typeLabel(row['action_type'] as String?),
              style: const TextStyle(
                  fontSize: 14, fontWeight: FontWeight.w700, color: TmsTheme.ink),
            ),
          ),
          Container(
            padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
            decoration: BoxDecoration(
              color: (isFailed ? TmsTheme.bad : TmsTheme.accent2)
                  .withValues(alpha: 0.12),
              borderRadius: BorderRadius.circular(4),
            ),
            child: Text(
              isFailed ? '已失败' : '待同步',
              style: TextStyle(
                fontSize: 11,
                fontWeight: FontWeight.w600,
                color: isFailed ? TmsTheme.bad : TmsTheme.accent2,
              ),
            ),
          ),
        ]),
        const SizedBox(height: 6),
        Text(
          '单据：${(row['action_key'] as String?)?.isNotEmpty == true ? row['action_key'] : "—"}',
          style: const TextStyle(fontSize: 12, color: TmsTheme.muted),
        ),
        Text(
          '产生时间：${_timeText(row['created_at'] as String?)}',
          style: const TextStyle(fontSize: 12, color: TmsTheme.muted),
        ),
        if (retryCount > 0)
          Text(
            '已重试：$retryCount / $maxRetry 次',
            style: const TextStyle(fontSize: 12, color: TmsTheme.muted),
          ),
        // 展示原始错误而不是笼统的「同步失败」：司机需要据此判断
        // 是自己该换个地方重试，还是得打电话找调度/IT。
        if (isFailed && error.isNotEmpty) ...[
          const SizedBox(height: 6),
          Container(
            width: double.infinity,
            padding: const EdgeInsets.all(8),
            decoration: BoxDecoration(
              color: TmsTheme.bg,
              borderRadius: BorderRadius.circular(6),
            ),
            child: Text(
              error.length > 200 ? '${error.substring(0, 200)}...' : error,
              style: const TextStyle(fontSize: 11, color: TmsTheme.bad),
            ),
          ),
        ],
        if (isFailed) ...[
          const SizedBox(height: 10),
          Row(children: [
            Expanded(
              child: OutlinedButton.icon(
                onPressed: onRetry,
                icon: const Icon(Icons.refresh, size: 16),
                label: const Text('重试'),
                style: OutlinedButton.styleFrom(
                  foregroundColor: TmsTheme.accent,
                  side: const BorderSide(color: TmsTheme.accent),
                  padding: const EdgeInsets.symmetric(vertical: 8),
                ),
              ),
            ),
            const SizedBox(width: 10),
            Expanded(
              child: OutlinedButton.icon(
                onPressed: onDiscard,
                icon: const Icon(Icons.delete_outline, size: 16),
                label: const Text('放弃'),
                style: OutlinedButton.styleFrom(
                  foregroundColor: TmsTheme.muted,
                  side: const BorderSide(color: TmsTheme.rule),
                  padding: const EdgeInsets.symmetric(vertical: 8),
                ),
              ),
            ),
          ]),
        ],
      ]),
    );
  }
}

/// 底部「立即同步」条。
class _BottomBar extends StatelessWidget {
  const _BottomBar({
    required this.online,
    required this.syncing,
    required this.onSyncAll,
  });

  final bool online;
  final bool syncing;
  final Future<void> Function() onSyncAll;

  @override
  Widget build(BuildContext context) {
    return SafeArea(
      top: false,
      child: Container(
        padding: const EdgeInsets.all(14),
        color: Colors.white,
        child: ElevatedButton.icon(
          // 离线时禁用而不是点了才提示：按钮可点却必然失败是种无意义的期待落空。
          onPressed: (!online || syncing) ? null : onSyncAll,
          icon: syncing
              ? const SizedBox(
                  width: 14,
                  height: 14,
                  child: CircularProgressIndicator(
                      strokeWidth: 2, color: Colors.white),
                )
              : const Icon(Icons.sync, size: 18),
          label: Text(syncing
              ? '同步中...'
              : (online ? '立即同步全部' : '离线中，无法同步')),
          style: ElevatedButton.styleFrom(
            backgroundColor: TmsTheme.accent,
            foregroundColor: Colors.white,
            elevation: 0,
            padding: const EdgeInsets.symmetric(vertical: 13),
            shape: RoundedRectangleBorder(
                borderRadius: BorderRadius.circular(10)),
          ),
        ),
      ),
    );
  }
}

/// action_type → 司机看得懂的中文名。
///
/// 直接显示 SIGN、RESCHEDULE_RETURN 这类内部标识对司机毫无意义，
/// 他需要认出「这是给哪家店的什么操作」才能判断要不要重试或放弃。
String _typeLabel(String? type) {
  switch (type) {
    case 'SIGN':
      return '发货签收';
    case 'RETURN_SIGN':
      return '退货回收签收';
    case 'CUSTOMER_REJECT':
      return '客户拒收登记';
    case 'RESCHEDULE_RETURN':
      return '改派返仓登记';
    case 'DRIVER_RETURN':
      return '现场退货登记';
    case 'LOADING_SCAN':
      return '装车扫码';
    case 'LOADING_START':
      return '开始装车';
    case 'LOADING_CONFIRM':
      return '装车完成确认';
    case 'STORE_LOCATION':
      return '门店定位纠偏';
    case 'ARRIVE':
      return '到店打卡';
    case 'PHOTO_UPLOAD':
      return '照片上传';
    default:
      return type?.isNotEmpty == true ? type! : '未知操作';
  }
}

String _timeText(String? iso) {
  if (iso == null || iso.isEmpty) return '—';
  final s = iso.replaceFirst('T', ' ');
  return s.length >= 16 ? s.substring(0, 16) : s;
}
