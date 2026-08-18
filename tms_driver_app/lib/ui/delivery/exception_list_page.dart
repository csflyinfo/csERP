import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../config/theme.dart';
import '../../providers/exception_provider.dart';
import '../../widgets/common.dart';
import '../../widgets/offline_banner.dart';

/// 我的异常上报记录（P3-4）。
///
/// 存在的意义不是「查历史」，而是让司机看到**上报之后有人管**：
/// 若上报完就没有任何反馈，司机很快会认定这是个走过场的功能而不再上报。
/// 因此这里重点展示处理状态与调度员填写的处理结论。
class ExceptionListPage extends ConsumerWidget {
  const ExceptionListPage({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final async = ref.watch(exceptionListProvider);
    return Scaffold(
      backgroundColor: TmsTheme.bg,
      appBar: AppBar(
        title: const Text('我的异常上报'),
        actions: [
          IconButton(
            icon: const Icon(Icons.refresh),
            onPressed: () => ref.invalidate(exceptionListProvider),
          ),
        ],
      ),
      body: Column(children: [
        const OfflineBanner(),
        Expanded(
          child: async.when(
            loading: () => const Center(child: CircularProgressIndicator()),
            error: (e, _) => _ErrorView(
              message: e.toString().replaceFirst('Exception: ', ''),
              onRetry: () => ref.invalidate(exceptionListProvider),
            ),
            data: (data) {
              final list = (data['list'] as List).cast<Map<String, dynamic>>();
              final pending = data['pendingCount'] ?? 0;
              if (list.isEmpty) {
                return const Center(
                  child: Padding(
                    padding: EdgeInsets.all(40),
                    child: Text('暂无异常上报记录',
                        style: TextStyle(color: TmsTheme.muted)),
                  ),
                );
              }
              return RefreshIndicator(
                onRefresh: () async => ref.invalidate(exceptionListProvider),
                child: ListView(
                  padding: const EdgeInsets.all(14),
                  children: [
                    if (pending is int && pending > 0)
                      Alert.warn('⏳ 有 $pending 条上报正在处理中，调度员处理后会在此更新结论'),
                    ...list.map(_ExceptionRow.new),
                    const SizedBox(height: 20),
                  ],
                ),
              );
            },
          ),
        ),
      ]),
    );
  }
}

class _ExceptionRow extends StatelessWidget {
  final Map<String, dynamic> row;
  const _ExceptionRow(this.row);

  @override
  Widget build(BuildContext context) {
    final status = _str(row['status']);
    final severity = _str(row['severity']);
    final urgent = severity == 'URGENT';
    final result = _str(row['handleResult']);
    return MCard(
      leftBar: _statusColor(status),
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Row(children: [
          Expanded(
            child: Text(
              _str(row['exceptionTypeName']).isEmpty
                  ? '现场异常'
                  : _str(row['exceptionTypeName']),
              style: const TextStyle(
                  fontSize: 14, fontWeight: FontWeight.w700, color: TmsTheme.ink),
            ),
          ),
          if (urgent) ...[const MTag.red('紧急'), const SizedBox(width: 4)],
          _statusTag(status),
        ]),
        const SizedBox(height: 4),
        Text(_str(row['reportNo']),
            style: const TextStyle(fontSize: 11, color: TmsTheme.muted)),
        const SizedBox(height: 6),
        Text(_str(row['description']),
            style: const TextStyle(fontSize: 13, color: TmsTheme.ink)),
        const SizedBox(height: 6),
        if (_str(row['customerName']).isNotEmpty)
          MLine('关联客户', _str(row['customerName'])),
        if (_str(row['locationAddress']).isNotEmpty)
          MLine('位置', _str(row['locationAddress'])),
        MLine('上报时间', _timeText(row['reportedAt'] ?? row['createTime'])),
        if (_str(row['handler']).isNotEmpty) MLine('处理人', _str(row['handler'])),
        // 处理结论是这个页面最有价值的信息，单独高亮而不是混在明细行里
        if (result.isNotEmpty) ...[
          const SizedBox(height: 6),
          Container(
            width: double.infinity,
            padding: const EdgeInsets.all(8),
            decoration: BoxDecoration(
              color: const Color(0xFFF3F4F6),
              borderRadius: BorderRadius.circular(8),
            ),
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              const Text('处理结论',
                  style: TextStyle(
                      fontSize: 11,
                      color: TmsTheme.muted,
                      fontWeight: FontWeight.w600)),
              const SizedBox(height: 2),
              Text(result,
                  style: const TextStyle(fontSize: 12, color: TmsTheme.ink)),
            ]),
          ),
        ],
      ]),
    );
  }

  static String _str(Object? v) => v == null ? '' : v.toString();

  static String _timeText(Object? v) {
    final s = _str(v);
    if (s.isEmpty) return '-';
    return s.length >= 16 ? s.substring(0, 16) : s;
  }

  static Color _statusColor(String status) => switch (status) {
        'CLOSED' => TmsTheme.ok,
        'HANDLING' => TmsTheme.accent,
        _ => TmsTheme.accent2,
      };

  static Widget _statusTag(String status) => switch (status) {
        'CLOSED' => const MTag.green('已关闭'),
        'HANDLING' => const MTag.blue('处理中'),
        _ => const MTag.orange('待处理'),
      };
}

class _ErrorView extends StatelessWidget {
  final String message;
  final VoidCallback onRetry;
  const _ErrorView({required this.message, required this.onRetry});

  @override
  Widget build(BuildContext context) {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(30),
        child: Column(mainAxisAlignment: MainAxisAlignment.center, children: [
          const Icon(Icons.cloud_off, size: 40, color: TmsTheme.muted),
          const SizedBox(height: 10),
          Text('加载失败：$message',
              textAlign: TextAlign.center,
              style: const TextStyle(fontSize: 13, color: TmsTheme.muted)),
          const SizedBox(height: 14),
          TmsButton.outline('重试', onPressed: onRetry),
        ]),
      ),
    );
  }
}
