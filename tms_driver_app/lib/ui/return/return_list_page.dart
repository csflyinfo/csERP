import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../config/theme.dart';
import '../../providers/task_provider.dart';
import '../../widgets/common.dart';
import 'return_sign_page.dart';

/// 退货回收列表（对齐原型 退货回收 Tab + Screen O 入口）。
///
/// 数据来源：今日任务中 bill_type=RETURN 的取货任务。
class ReturnListPage extends ConsumerStatefulWidget {
  final String? initialApplyNo; // 从首页点击进入时直接跳详情
  const ReturnListPage({super.key, this.initialApplyNo});

  @override
  ConsumerState<ReturnListPage> createState() => _ReturnListPageState();
}

class _ReturnListPageState extends ConsumerState<ReturnListPage> {
  @override
  void initState() {
    super.initState();
    if (widget.initialApplyNo != null) {
      WidgetsBinding.instance.addPostFrameCallback((_) {
        Navigator.push(context, MaterialPageRoute(
          builder: (_) => ReturnSignPage(applyNo: widget.initialApplyNo!),
        ));
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final tasksAsync = ref.watch(todayTasksProvider);
    return Scaffold(
      backgroundColor: TmsTheme.bg,
      appBar: AppBar(
        backgroundColor: TmsTheme.returnPurple,
        title: const Text('退货回收'),
      ),
      body: tasksAsync.when(
            data: (tasks) {
              final returns = tasks.details.where((d) => d.isReturn).toList();
              final pending = returns.where((d) => d.status == 'PENDING').toList();
              final done = returns.where((d) => d.status != 'PENDING').toList();
              final totalQty = pending.fold<num>(0, (s, d) => s + d.qty);
              if (returns.isEmpty) {
                return const Center(child: Padding(padding: EdgeInsets.all(32), child: Text('暂无退货回收任务', style: TextStyle(color: TmsTheme.muted))));
              }
              return ListView(
                padding: const EdgeInsets.all(14),
                children: [
                  // 统计三宫格
                  Row(children: [
                    _stat('${pending.length}', '待回收', TmsTheme.accent2, const Color(0xFFFFF7ED)),
                    const SizedBox(width: 8),
                    _stat('${done.length}', '已回收', TmsTheme.ok, const Color(0xFFDCFCE7)),
                    const SizedBox(width: 8),
                    _stat('$totalQty', '待回收件数', TmsTheme.returnPurple, const Color(0xFFEDE9FE)),
                  ]),
                  const SizedBox(height: 12),
                  if (pending.isNotEmpty) ...[
                    const Padding(padding: EdgeInsets.symmetric(vertical: 6), child: Text('待回收（物流状态：已调度）', style: TextStyle(fontSize: 12, color: TmsTheme.muted))),
                    ...pending.map((d) => MCard(
                          leftBar: TmsTheme.returnPurple,
                          onTap: () => Navigator.push(context, MaterialPageRoute(builder: (_) => ReturnSignPage(applyNo: d.sourceBillNo))),
                          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                            Row(mainAxisAlignment: MainAxisAlignment.spaceBetween, children: [
                              Expanded(child: Text(d.customerName, style: const TextStyle(fontSize: 14, fontWeight: FontWeight.w700, color: TmsTheme.ink))),
                              const MTag.purple('待回收'),
                            ]),
                            const SizedBox(height: 4),
                            Text('${d.sourceBillNo} · ${d.customerAddress}', style: const TextStyle(fontSize: 12, color: TmsTheme.muted)),
                            const SizedBox(height: 2),
                            Text('退货 ${d.qty} 件 · 调度单 ${d.dispatchNo}', style: const TextStyle(fontSize: 12, color: TmsTheme.muted)),
                          ]),
                        )),
                  ],
                  if (done.isNotEmpty) ...[
                    const Padding(padding: EdgeInsets.symmetric(vertical: 6), child: Text('已回收（物流状态：司机已回收）', style: TextStyle(fontSize: 12, color: TmsTheme.muted))),
                    ...done.map((d) => MCard(
                          leftBar: TmsTheme.ok,
                          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                            Row(mainAxisAlignment: MainAxisAlignment.spaceBetween, children: [
                              Expanded(child: Text(d.customerName, style: const TextStyle(fontSize: 14, fontWeight: FontWeight.w700, color: TmsTheme.ink))),
                              const MTag.green('已回收'),
                            ]),
                            const SizedBox(height: 4),
                            Text('${d.sourceBillNo} · ${d.customerAddress}', style: const TextStyle(fontSize: 12, color: TmsTheme.muted)),
                          ]),
                        )),
                  ],
                ],
              );
            },
            loading: () => const Center(child: CircularProgressIndicator()),
            error: (e, _) => Center(child: Text('加载失败：$e', style: const TextStyle(color: TmsTheme.muted))),
          ),
    );
  }

  Widget _stat(String num, String label, Color numColor, Color bg) =>
      Expanded(child: Container(
        padding: const EdgeInsets.symmetric(vertical: 12),
        decoration: BoxDecoration(color: bg, borderRadius: BorderRadius.circular(12)),
        child: Column(children: [
          Text(num, style: TextStyle(fontSize: 22, fontWeight: FontWeight.w700, color: numColor)),
          const SizedBox(height: 2),
          Text(label, style: const TextStyle(fontSize: 11, color: TmsTheme.muted)),
        ]),
      ));
}
