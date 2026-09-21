import 'package:flutter/material.dart';
import '../config/pda_perms.dart';
import '../services/auth_service.dart';
import '../theme/pda_theme.dart';

/// 全局扫码快捷入口（P0-3）：从首页 FAB 弹出，按登录 funcs 裁剪可见项。
///
/// 设计目标：
/// - 任何 Tab 都能一键弹出扫码面板，不切 Tab、不离开当前作业上下文；
/// - 入口与首页九宫格一一对应，但以"扫一码进一单"语义命名；
/// - 每项跳到对应业务页（暂不带预填参数，P1 阶段再改成扫码后直接进入明细态）；
/// - 没有任何扫码权限时显示空态提示，不阻塞首页。
Future<void> showGlobalScanSheet(BuildContext context) async {
  final auth = AuthService.instance;
  final all = <_ScanEntry>[
    _ScanEntry('扫收货单', Icons.download_rounded, '/receive', PdaPerm.receiveView),
    _ScanEntry('扫退货收货', Icons.assignment_return_rounded,
        '/receive-return', PdaPerm.returnView),
    _ScanEntry('扫其他入库', Icons.post_add_rounded, '/other-inbound',
        PdaPerm.otherView),
    _ScanEntry('扫上架任务', Icons.label_rounded, '/putaway',
        PdaPerm.putawayView),
    _ScanEntry('扫拣货单', Icons.outbox_rounded, '/pick', PdaPerm.pickView),
    _ScanEntry('扫复核任务', Icons.fact_check_rounded, '/check',
        PdaPerm.checkView),
    _ScanEntry('扫装车任务', Icons.local_shipping_rounded, '/load',
        PdaPerm.loadView),
    _ScanEntry('扫盘点任务', Icons.fact_check_outlined, '/stocktake',
        PdaPerm.takeView),
    _ScanEntry('扫移库', Icons.swap_horiz_rounded, '/move', PdaPerm.moveView),
    _ScanEntry('扫报损', Icons.report_problem_rounded, '/damage',
        PdaPerm.damageView),
    _ScanEntry('扫库位查库存', Icons.search_rounded, '/stock-query',
        PdaPerm.stockView),
    _ScanEntry('扫补货', Icons.bolt_rounded, '/replenish',
        PdaPerm.replenishView),
    _ScanEntry('异常上报', Icons.warning_amber_rounded, '/exception',
        PdaPerm.excView),
  ];
  final items = all.where((e) => auth.can(e.perm)).toList();

  await showModalBottomSheet(
    context: context,
    backgroundColor: PdaTheme.surface,
    shape: const RoundedRectangleBorder(
      borderRadius: BorderRadius.vertical(top: Radius.circular(16)),
    ),
    isScrollControlled: true,
    constraints: BoxConstraints(
      maxHeight: MediaQuery.of(context).size.height * 0.7,
    ),
    builder: (ctx) {
      return SafeArea(
        child: Padding(
          padding: const EdgeInsets.all(PdaSpacing.lg),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Row(children: [
                Container(
                  width: 36,
                  height: 36,
                  alignment: Alignment.center,
                  decoration: BoxDecoration(
                    color: PdaTheme.primary.withValues(alpha: 0.16),
                    borderRadius: BorderRadius.circular(10),
                  ),
                  child: const Icon(Icons.qr_code_scanner,
                      color: PdaTheme.primary, size: 22),
                ),
                const SizedBox(width: PdaSpacing.md),
                const Text('快捷扫码', style: PdaStyles.title),
                const Spacer(),
                IconButton(
                  tooltip: '关闭',
                  icon: const Icon(Icons.close_rounded,
                      color: PdaTheme.textSecondary),
                  onPressed: () => Navigator.pop(ctx),
                ),
              ]),
              const Divider(height: 16),
              if (items.isEmpty)
                const Padding(
                  padding: EdgeInsets.symmetric(vertical: 32),
                  child: Center(
                    child: Text('当前账号没有扫码入口权限',
                        style: PdaStyles.sub),
                  ),
                )
              else
                GridView.count(
                  shrinkWrap: true,
                  crossAxisCount: 3,
                  mainAxisSpacing: PdaSpacing.sm,
                  crossAxisSpacing: PdaSpacing.sm,
                  childAspectRatio: 1.0,
                  physics: const NeverScrollableScrollPhysics(),
                  children: [
                    for (final e in items)
                      _ScanTile(
                        entry: e,
                        onTap: () {
                          Navigator.pop(ctx);
                          Navigator.pushNamed(context, e.route);
                        },
                      ),
                  ],
                ),
              const SizedBox(height: PdaSpacing.sm),
              const Text(
                '点击任一入口跳转到对应作业页，进页后再扫单/扫商品',
                style: PdaStyles.sub,
                textAlign: TextAlign.center,
              ),
            ],
          ),
        ),
      );
    },
  );
}

class _ScanEntry {
  final String label;
  final IconData icon;
  final String route;
  final String perm;
  const _ScanEntry(this.label, this.icon, this.route, this.perm);
}

class _ScanTile extends StatelessWidget {
  final _ScanEntry entry;
  final VoidCallback onTap;
  const _ScanTile({required this.entry, required this.onTap});

  @override
  Widget build(BuildContext context) {
    return Material(
      color: Colors.transparent,
      child: InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(PdaSpacing.radius),
        child: Container(
          decoration: BoxDecoration(
            color: PdaTheme.surface2,
            borderRadius: BorderRadius.circular(PdaSpacing.radius),
            border: Border.all(color: PdaTheme.border),
          ),
          child: Column(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              Icon(entry.icon, color: PdaTheme.primary, size: 28),
              const SizedBox(height: PdaSpacing.sm),
              Text(
                entry.label,
                textAlign: TextAlign.center,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: const TextStyle(
                    fontSize: 12, color: PdaTheme.textPrimary),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
