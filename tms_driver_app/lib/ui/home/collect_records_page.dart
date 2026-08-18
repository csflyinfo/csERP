import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../config/theme.dart';
import '../../models/task.dart';
import '../../providers/task_provider.dart';
import '../../widgets/common.dart';
import '../../widgets/offline_banner.dart';

/// 收款记录页：司机本人代收货款的逐笔流水（默认近 30 天）。
///
/// 存在意义是交账对账：「我的」页统计卡只给一个累计总数，
/// 一旦跟调度对不上，司机没有任何手段定位差额出在哪家门店。
/// 这里按门店级签收记录逐笔列出，并把现金与电子收款分开汇总
/// —— 现金要点钞交财务，电子收款只需核对流水，两者交割方式不同。
class CollectRecordsPage extends ConsumerStatefulWidget {
  const CollectRecordsPage({super.key});

  @override
  ConsumerState<CollectRecordsPage> createState() => _CollectRecordsPageState();
}

class _CollectRecordsPageState extends ConsumerState<CollectRecordsPage> {
  static const _dayOptions = <int, String>{7: '近7天', 30: '近30天', 90: '近90天'};

  /// 收款方式选项与签收页写入 pay_method 的中文值严格一致（现金/微信/支付宝/赊账），
  /// 不做额外枚举映射，否则筛选条件对不上库里的值。
  /// 刻意不放「赊账」：赊账的 collect_amount 恒为 0，会被接口的「只看真实收款」条件过滤掉，
  /// 留着只会得到一个永远为空的筛选项。
  static const _payOptions = <String, String>{
    'ALL': '全部方式',
    '现金': '现金',
    '微信': '微信',
    '支付宝': '支付宝',
  };

  int _days = 30;
  String _payMethod = 'ALL';

  CollectRecordArgs get _args => CollectRecordArgs(days: _days, payMethod: _payMethod);

  @override
  Widget build(BuildContext context) {
    final async = ref.watch(collectRecordsProvider(_args));
    return Scaffold(
      backgroundColor: TmsTheme.bg,
      appBar: AppBar(title: const Text('收款记录')),
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

  Widget _buildFilterBar() {
    return Container(
      color: Colors.white,
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          _chipRow(_dayOptions.entries.map((e) => _chip(
                e.value,
                selected: _days == e.key,
                onTap: () => setState(() => _days = e.key),
              ))),
          const SizedBox(height: 6),
          _chipRow(_payOptions.entries.map((e) => _chip(
                e.value,
                selected: _payMethod == e.key,
                onTap: () => setState(() => _payMethod = e.key),
              ))),
        ],
      ),
    );
  }

  Widget _chipRow(Iterable<Widget> children) => SingleChildScrollView(
        scrollDirection: Axis.horizontal,
        child: Row(children: children.toList()),
      );

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

  Widget _buildList(CollectRecordPage page) {
    return RefreshIndicator(
      onRefresh: () async => ref.invalidate(collectRecordsProvider(_args)),
      child: ListView(
        padding: const EdgeInsets.all(12),
        children: [
          _buildSummary(page.summary),
          if (page.records.isEmpty)
            const Padding(
              padding: EdgeInsets.only(top: 60),
              child: Center(
                child: Text('所选条件下暂无收款记录',
                    style: TextStyle(fontSize: 13, color: TmsTheme.muted)),
              ),
            )
          else
            ...page.records.map(_buildCard),
          if (page.records.isNotEmpty) ...[
            const SizedBox(height: 8),
            const Center(
              child: Text('仅显示实际收到款的记录，预付/账期门店不在此列',
                  style: TextStyle(fontSize: 11, color: TmsTheme.muted)),
            ),
            const SizedBox(height: 12),
          ],
        ],
      ),
    );
  }

  /// 汇总条：总额之外单列现金，因为现金是司机身上真实揣着的钱，
  /// 交账时必须点得清；电子收款已到公司账户，风险性质完全不同。
  Widget _buildSummary(CollectSummary s) {
    return MCard(
      child: Row(
        children: [
          _statCell('${s.recordCount}', '笔数'),
          _divider(),
          _statCell('¥${_num(s.amountSum)}', '收款合计', color: TmsTheme.accent2),
          _divider(),
          _statCell('¥${_num(s.cashSum)}', '其中现金', color: TmsTheme.bad),
          _divider(),
          _statCell('¥${_num(s.onlineSum)}', '电子收款', color: TmsTheme.ok),
        ],
      ),
    );
  }

  Widget _statCell(String value, String label, {Color color = TmsTheme.ink}) {
    return Expanded(
      child: Column(
        children: [
          Text(value,
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: TextStyle(fontSize: 15, fontWeight: FontWeight.w800, color: color)),
          const SizedBox(height: 2),
          Text(label, style: const TextStyle(fontSize: 11, color: TmsTheme.muted)),
        ],
      ),
    );
  }

  Widget _divider() => Container(width: 1, height: 28, color: TmsTheme.rule);

  Widget _buildCard(CollectRecord r) {
    return MCard(
      leftBar: r.isApproved ? TmsTheme.ok : (r.isRejected ? TmsTheme.bad : TmsTheme.accent2),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Expanded(
                child: Text(
                  r.customerName.isEmpty ? r.sourceBillNo : r.customerName,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: const TextStyle(fontSize: 14, fontWeight: FontWeight.w700, color: TmsTheme.ink),
                ),
              ),
              const SizedBox(width: 8),
              Text('¥${_num(r.collectAmount)}',
                  style: const TextStyle(fontSize: 16, fontWeight: FontWeight.w800, color: TmsTheme.accent2)),
            ],
          ),
          const SizedBox(height: 4),
          Row(
            children: [
              Expanded(
                child: Text(
                  '${r.signTimeShort} · ${r.billTypeText}${r.payMethod.isEmpty ? '' : ' · ${r.payMethod}'}',
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: const TextStyle(fontSize: 11, color: TmsTheme.muted),
                ),
              ),
              _verifyTag(r),
            ],
          ),
          const SizedBox(height: 6),
          MLine('单据号', r.sourceBillNo),
          if (r.dispatchNo.isNotEmpty) MLine('调度单', r.dispatchNo),
          if (r.signTypeText.isNotEmpty) MLine('签收方式', r.signTypeText),
          if (r.customerSigner.isNotEmpty) MLine('客户签收人', r.customerSigner),
        ],
      ),
    );
  }

  /// 核销状态标签：待核销代表这笔钱还没跟调度交割完，
  /// 司机看到它就知道账还没清，不能当成已完成。
  Widget _verifyTag(CollectRecord r) {
    final text = r.verifiedText.isEmpty ? '待核销' : r.verifiedText;
    if (r.isApproved) return MTag.green(text);
    if (r.isRejected) return MTag.red(text);
    return MTag.orange(text);
  }

  Widget _buildError(Object e) {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(24),
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Text('加载失败：${e.toString().replaceFirst("Exception: ", "")}',
                textAlign: TextAlign.center,
                style: const TextStyle(fontSize: 13, color: TmsTheme.muted)),
            const SizedBox(height: 12),
            SizedBox(
              width: 140,
              child: TmsButton.outline('重试',
                  onPressed: () => ref.invalidate(collectRecordsProvider(_args))),
            ),
          ],
        ),
      ),
    );
  }

  String _num(num v) => v == v.roundToDouble() ? v.toInt().toString() : v.toStringAsFixed(2);
}
