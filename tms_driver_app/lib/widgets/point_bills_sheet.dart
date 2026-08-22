import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../config/theme.dart';
import '../models/delivery.dart';
import '../providers/delivery_provider.dart';

/// 配送点单据+商品清单弹窗（V77）。
///
/// 「查看清单」点配送点、「装车确认」点配送点卡片都复用它：
/// 调 /tms/app/loading/point-bills，按单据分组展示商品名称/规格/单位/数量。
///
/// 用 showModalBottomSheet 弹出，高度占屏 75%，可拖动下滑关闭。
class PointBillsSheet extends ConsumerWidget {
  final String dispatchId;
  final String detailId;
  final String? customerName;
  const PointBillsSheet({
    super.key,
    required this.dispatchId,
    required this.detailId,
    this.customerName,
  });

  static Future<void> show(
    BuildContext context, {
    required String dispatchId,
    required String detailId,
    String? customerName,
  }) {
    return showModalBottomSheet(
      context: context,
      isScrollControlled: true,
      backgroundColor: TmsTheme.bg,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(16)),
      ),
      builder: (_) => PointBillsSheet(
        dispatchId: dispatchId,
        detailId: detailId,
        customerName: customerName,
      ),
    );
  }

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final async = ref.watch(pointBillsProvider(
        PointBillsArgs(dispatchId: dispatchId, detailId: detailId)));
    final media = MediaQuery.of(context);
    return Padding(
      padding: EdgeInsets.only(bottom: media.viewInsets.bottom),
      child: SizedBox(
        height: media.size.height * 0.75,
        child: Column(children: [
          _handle(),
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 4, 16, 8),
            child: Row(children: [
              const Icon(Icons.receipt_long, size: 18, color: TmsTheme.accent),
              const SizedBox(width: 6),
              Expanded(
                child: Text(
                  customerName?.isNotEmpty == true
                      ? '单据清单 · $customerName'
                      : '单据清单',
                  style: const TextStyle(
                      fontSize: 15,
                      fontWeight: FontWeight.w700,
                      color: TmsTheme.ink),
                ),
              ),
              IconButton(
                icon: const Icon(Icons.close, size: 20),
                onPressed: () => Navigator.pop(context),
                visualDensity: VisualDensity.compact,
              ),
            ]),
          ),
          const Divider(height: 1, color: TmsTheme.rule),
          Expanded(
            child: async.when(
              loading: () =>
                  const Center(child: CircularProgressIndicator(strokeWidth: 2)),
              error: (e, _) => Center(
                child: Padding(
                  padding: const EdgeInsets.all(24),
                  child: Text('加载失败：$e',
                      style: const TextStyle(color: TmsTheme.muted)),
                ),
              ),
              data: (pb) {
                if (pb.bills.isEmpty) {
                  return const Center(
                    child: Text('该配送点暂无单据',
                        style: TextStyle(color: TmsTheme.muted)),
                  );
                }
                return ListView(
                  padding: const EdgeInsets.fromLTRB(12, 8, 12, 20),
                  children: [
                    for (final bill in pb.bills) _BillCard(bill: bill),
                    const SizedBox(height: 8),
                    Center(
                      child: Text(
                        '共 ${pb.bills.length} 张单 · ${_qty(pb.totalQty)} 件 · ${pb.skuCount} 种',
                        style: const TextStyle(
                            fontSize: 11, color: TmsTheme.muted),
                      ),
                    ),
                  ],
                );
              },
            ),
          ),
        ]),
      ),
    );
  }

  Widget _handle() => Center(
        child: Container(
          margin: const EdgeInsets.only(top: 8, bottom: 4),
          width: 36,
          height: 4,
          decoration: BoxDecoration(
            color: TmsTheme.rule,
            borderRadius: BorderRadius.circular(2),
          ),
        ),
      );
}

class _BillCard extends StatelessWidget {
  final PointBill bill;
  const _BillCard({required this.bill});

  @override
  Widget build(BuildContext context) {
    final isReturn = bill.isReturn;
    final color = isReturn ? TmsTheme.returnPurple : TmsTheme.accent;
    return Container(
      margin: const EdgeInsets.only(bottom: 10),
      decoration: BoxDecoration(
        color: Colors.white,
        borderRadius: BorderRadius.circular(10),
        border: Border.all(color: TmsTheme.rule),
      ),
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        // 单据标题行
        Container(
          padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
          decoration: BoxDecoration(
            color: color.withValues(alpha: 0.08),
            borderRadius:
                const BorderRadius.vertical(top: Radius.circular(9)),
          ),
          child: Row(children: [
            Icon(
              isReturn
                  ? Icons.assignment_return_outlined
                  : Icons.inventory_2_outlined,
              size: 15,
              color: color,
            ),
            const SizedBox(width: 6),
            Container(
              padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 1),
              decoration: BoxDecoration(
                color: color,
                borderRadius: BorderRadius.circular(4),
              ),
              child: Text(bill.billTypeText,
                  style: const TextStyle(
                      fontSize: 10,
                      color: Colors.white,
                      fontWeight: FontWeight.w700)),
            ),
            const SizedBox(width: 8),
            Expanded(
              child: Text(bill.sourceBillNo,
                  style: const TextStyle(
                      fontSize: 13,
                      fontWeight: FontWeight.w700,
                      color: TmsTheme.ink)),
            ),
            Text('${_qty(bill.qty)} 件 / ${bill.skuCount} 种',
                style: const TextStyle(fontSize: 11, color: TmsTheme.muted)),
          ]),
        ),
        // 商品表头
        const Padding(
          padding: EdgeInsets.fromLTRB(12, 6, 12, 2),
          child: Row(children: [
            Expanded(flex: 5, child: Text('商品', style: _headerStyle)),
            Expanded(flex: 4, child: Text('规格', style: _headerStyle)),
            Expanded(flex: 2, child: Text('单位', style: _headerStyle)),
            Expanded(
                flex: 2,
                child: Text('数量',
                    style: _headerStyle, textAlign: TextAlign.right)),
          ]),
        ),
        const Divider(height: 4, indent: 12, endIndent: 12, color: TmsTheme.rule),
        // 商品行
        for (final it in bill.items)
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 5),
            child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Expanded(
                flex: 5,
                child: Text(it.goodsName,
                    style: const TextStyle(
                        fontSize: 12,
                        color: TmsTheme.ink,
                        fontWeight: FontWeight.w600)),
              ),
              Expanded(
                flex: 4,
                child: Text(it.spec.isEmpty ? '—' : it.spec,
                    style: const TextStyle(fontSize: 11, color: TmsTheme.muted)),
              ),
              Expanded(
                flex: 2,
                child: Text(it.unitName.isEmpty ? '—' : it.unitName,
                    style: const TextStyle(fontSize: 11, color: TmsTheme.muted)),
              ),
              Expanded(
                flex: 2,
                child: Text(_qty(it.qty),
                    textAlign: TextAlign.right,
                    style: const TextStyle(
                        fontSize: 12,
                        color: TmsTheme.ink,
                        fontWeight: FontWeight.w700)),
              ),
            ]),
          ),
        const SizedBox(height: 4),
      ]),
    );
  }
}

const _headerStyle = TextStyle(fontSize: 10, color: TmsTheme.muted);

String _qty(num v) =>
    v == v.roundToDouble() ? v.toInt().toString() : v.toStringAsFixed(2);
