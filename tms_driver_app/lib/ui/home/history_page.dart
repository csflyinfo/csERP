import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../config/theme.dart';
import '../../models/task.dart';
import '../../providers/task_provider.dart';
import '../../widgets/common.dart';
import '../../widgets/offline_banner.dart';

/// 配送历史页：查询本人历史行程（默认近 30 天）。
///
/// 作为工作台底部 Tab 之一常驻，因此每次切回时不主动刷新，
/// 由用户下拉或切换筛选条件触发（避免频繁请求）。
class HistoryPage extends ConsumerStatefulWidget {
  const HistoryPage({super.key});

  @override
  ConsumerState<HistoryPage> createState() => _HistoryPageState();
}

class _HistoryPageState extends ConsumerState<HistoryPage> {
  /// 时间范围选项：近 N 天
  static const _dayOptions = <int, String>{7: '近7天', 30: '近30天', 90: '近90天'};

  /// 状态筛选选项
  static const _statusOptions = <String, String>{
    'ALL': '全部',
    'COMPLETED': '已完成',
    'DELIVERING': '配送中',
    'CANCELLED': '已取消',
  };

  int _days = 30;
  String _status = 'ALL';

  TripHistoryArgs get _args => TripHistoryArgs(status: _status, days: _days);

  @override
  Widget build(BuildContext context) {
    final async = ref.watch(tripHistoryProvider(_args));
    return Scaffold(
      backgroundColor: TmsTheme.bg,
      appBar: AppBar(title: const Text('配送历史')),
      body: Column(
        children: [
          const OfflineBanner(),
          _buildFilterBar(),
          Expanded(
            child: async.when(
              data: (page) => _buildList(page),
              loading: () => const Center(child: CircularProgressIndicator()),
              error: (e, _) => _buildError(e),
            ),
          ),
        ],
      ),
    );
  }

  /// 筛选栏：时间范围 + 状态，切换即换 provider 参数触发新请求。
  Widget _buildFilterBar() {
    return Container(
      color: Colors.white,
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          _chipRow(
            _dayOptions.entries.map((e) => _chip(
                  e.value,
                  selected: _days == e.key,
                  onTap: () => setState(() => _days = e.key),
                )),
          ),
          const SizedBox(height: 6),
          _chipRow(
            _statusOptions.entries.map((e) => _chip(
                  e.value,
                  selected: _status == e.key,
                  onTap: () => setState(() => _status = e.key),
                )),
          ),
        ],
      ),
    );
  }

  Widget _chipRow(Iterable<Widget> children) {
    return SingleChildScrollView(
      scrollDirection: Axis.horizontal,
      child: Row(children: children.toList()),
    );
  }

  Widget _chip(String text, {required bool selected, required VoidCallback onTap}) {
    return Padding(
      padding: const EdgeInsets.only(right: 8),
      child: GestureDetector(
        onTap: onTap,
        child: Container(
          padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 6),
          decoration: BoxDecoration(
            color: selected ? TmsTheme.accent : const Color(0xFFF3F4F6),
            borderRadius: BorderRadius.circular(16),
          ),
          child: Text(
            text,
            style: TextStyle(
              fontSize: 12,
              color: selected ? Colors.white : TmsTheme.muted,
              fontWeight: FontWeight.w600,
            ),
          ),
        ),
      ),
    );
  }

  Widget _buildList(TripHistoryPage page) {
    return RefreshIndicator(
      onRefresh: () async => ref.invalidate(tripHistoryProvider(_args)),
      child: ListView(
        padding: const EdgeInsets.all(12),
        children: [
          _buildSummary(page.summary),
          if (page.records.isEmpty)
            const Padding(
              padding: EdgeInsets.only(top: 60),
              child: Center(
                child: Text(
                  '所选条件下暂无配送记录',
                  style: TextStyle(fontSize: 13, color: TmsTheme.muted),
                ),
              ),
            )
          else
            ...page.records.map(_buildTripCard),
        ],
      ),
    );
  }

  /// 汇总条：门店数/件数取「已完成」口径，与后端 delivered_* 字段一致。
  Widget _buildSummary(TripHistorySummary s) {
    return MCard(
      child: Row(
        children: [
          _statCell('${s.tripCount}', '行程数'),
          _divider(),
          _statCell('${s.storeSum}', '完成门店'),
          _divider(),
          _statCell(_num(s.qtySum), '配送件数'),
          _divider(),
          _statCell('¥${_num(s.amountSum)}', '代收金额'),
        ],
      ),
    );
  }

  Widget _statCell(String value, String label) {
    return Expanded(
      child: Column(
        children: [
          Text(value,
              style: const TextStyle(fontSize: 16, fontWeight: FontWeight.w800, color: TmsTheme.ink)),
          const SizedBox(height: 2),
          Text(label, style: const TextStyle(fontSize: 11, color: TmsTheme.muted)),
        ],
      ),
    );
  }

  Widget _divider() => Container(width: 1, height: 28, color: TmsTheme.rule);

  Widget _buildTripCard(TripHistory t) {
    return MCard(
      leftBar: _statusColor(t.status),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Expanded(
                child: Text(
                  t.tripDate.isEmpty ? t.tripNo : '${t.tripDate} · ${t.routeLine}',
                  style: const TextStyle(fontSize: 14, fontWeight: FontWeight.w700, color: TmsTheme.ink),
                ),
              ),
              _statusTag(t),
            ],
          ),
          const SizedBox(height: 4),
          Text(
            '${t.tripNo}${t.dispatchNo.isEmpty ? '' : ' / ${t.dispatchNo}'}',
            style: const TextStyle(fontSize: 11, color: TmsTheme.muted),
          ),
          const SizedBox(height: 6),
          _progressBar(t),
          MLine('门店进度', '${t.deliveredStore} / ${t.totalStore} 家（${t.progress}%）'),
          MLine('配送件数', '${_num(t.deliveredQty)} / ${_num(t.totalQty)} 件'),
          if (t.collectedAmount > 0)
            MLine('代收金额', '¥${_num(t.collectedAmount)}', valueColor: TmsTheme.accent2),
          if (t.vehiclePlate.isNotEmpty) MLine('车辆', t.vehiclePlate),
          if (t.departTime.isNotEmpty) MLine('发车时间', _time(t.departTime)),
          if (t.completeTime.isNotEmpty) MLine('完成时间', _time(t.completeTime)),
        ],
      ),
    );
  }

  /// 进度条：直观呈现门店完成比例。
  Widget _progressBar(TripHistory t) {
    final ratio = (t.progress / 100).clamp(0.0, 1.0);
    return Padding(
      padding: const EdgeInsets.only(bottom: 2),
      child: ClipRRect(
        borderRadius: BorderRadius.circular(4),
        child: LinearProgressIndicator(
          value: ratio,
          minHeight: 6,
          backgroundColor: const Color(0xFFF3F4F6),
          valueColor: AlwaysStoppedAnimation(_statusColor(t.status)),
        ),
      ),
    );
  }

  Widget _statusTag(TripHistory t) {
    final text = t.statusText.isEmpty ? t.status : t.statusText;
    return switch (t.status) {
      'COMPLETED' => MTag.green(text),
      'DELIVERING' => MTag.orange(text),
      'DEPARTED' => MTag.blue(text),
      'CANCELLED' => MTag.red(text),
      _ => MTag.gray(text),
    };
  }

  Color _statusColor(String status) => switch (status) {
        'COMPLETED' => TmsTheme.ok,
        'DELIVERING' => TmsTheme.accent2,
        'DEPARTED' => TmsTheme.accent,
        'CANCELLED' => TmsTheme.bad,
        _ => TmsTheme.muted,
      };

  Widget _buildError(Object e) {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(24),
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Text('加载失败：$e',
                textAlign: TextAlign.center,
                style: const TextStyle(fontSize: 13, color: TmsTheme.muted)),
            const SizedBox(height: 12),
            SizedBox(
              width: 140,
              child: TmsButton.outline('重试',
                  onPressed: () => ref.invalidate(tripHistoryProvider(_args))),
            ),
          ],
        ),
      ),
    );
  }

  /// 数值格式化：整数不显示小数位，避免「12.0 件」这类观感问题。
  String _num(num v) => v == v.roundToDouble() ? v.toInt().toString() : v.toStringAsFixed(2);

  /// 时间截断到分钟（后端返回 yyyy-MM-dd HH:mm:ss[.SSS]）。
  String _time(String v) {
    if (v.length >= 16) return v.substring(0, 16).replaceFirst('T', ' ');
    return v;
  }
}
