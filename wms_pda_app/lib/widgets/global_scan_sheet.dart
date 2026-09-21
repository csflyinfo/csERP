import 'package:flutter/material.dart';
import '../config/pda_perms.dart';
import '../services/api_service.dart';
import '../services/auth_service.dart';
import '../services/wms_app_service.dart';
import '../theme/pda_theme.dart';

/// 全局扫码面板（P0-3 + V1.1 智能路由）：从首页 FAB / 硬件扫码键弹出。
///
/// 两部分：
/// - 顶部「智能识别」：扫码枪扫入任意条码 → 后端在当前仓反查业务对象 →
///   自动路由到对应作业页（库位/容器/入库/波次/盘点/移库/补货/报损/商品）；
/// - 下方「手动选择」：按登录 funcs 裁剪的快捷入口，识别不出或想手动选时兜底。
Future<void> showGlobalScanSheet(BuildContext context) async {
  await showModalBottomSheet(
    context: context,
    backgroundColor: PdaTheme.surface,
    shape: const RoundedRectangleBorder(
      borderRadius: BorderRadius.vertical(top: Radius.circular(16)),
    ),
    isScrollControlled: true,
    constraints: BoxConstraints(
      maxHeight: MediaQuery.of(context).size.height * 0.82,
    ),
    builder: (ctx) => const _ScanSheetBody(),
  );
}

class _ScanSheetBody extends StatefulWidget {
  const _ScanSheetBody();
  @override
  State<_ScanSheetBody> createState() => _ScanSheetBodyState();
}

class _ScanSheetBodyState extends State<_ScanSheetBody> {
  final auth = AuthService.instance;
  final _codeCtrl = TextEditingController();
  bool _busy = false;

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

  @override
  void dispose() {
    _codeCtrl.dispose();
    super.dispose();
  }

  List<_ScanEntry> get items =>
      all.where((e) => auth.can(e.perm)).toList();

  /// 智能识别：提交条码 → 后端反查 → 按 route 跳转。
  Future<void> _identify() async {
    final code = _codeCtrl.text.trim();
    if (code.isEmpty || _busy) return;
    setState(() => _busy = true);
    try {
      final r = await WmsAppService.instance.barcodeIdentify(code);
      if (!mounted) return;
      final type = (r['type'] ?? '').toString();
      final route = (r['route'] ?? '').toString();
      if (type == 'unknown' || route.isEmpty) {
        ScaffoldMessenger.of(context).showSnackBar(SnackBar(
          content: const Text('未识别该条码，请从下方手动选择作业入口'),
          backgroundColor: PdaTheme.warning,
          behavior: SnackBarBehavior.floating,
        ));
        _codeCtrl.clear();
        return;
      }
      final params = r['params'];
      final args = params is Map
          ? Map<String, dynamic>.from(params)
          : <String, dynamic>{};
      Navigator.pop(context);
      Navigator.pushNamed(context, route, arguments: args);
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(SnackBar(
          content: Text(ApiService.friendlyError(e)),
          backgroundColor: PdaTheme.danger,
          behavior: SnackBarBehavior.floating,
        ));
      }
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final list = items;
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
              const Text('扫码作业', style: PdaStyles.title),
              const Spacer(),
              IconButton(
                tooltip: '关闭',
                icon: const Icon(Icons.close_rounded,
                    color: PdaTheme.textSecondary),
                onPressed: () => Navigator.pop(context),
              ),
            ]),
            const Divider(height: 12),

            // 智能识别输入区
            Container(
              padding: const EdgeInsets.all(PdaSpacing.md),
              decoration: BoxDecoration(
                color: PdaTheme.surface2,
                borderRadius: BorderRadius.circular(PdaSpacing.radius),
                border: Border.all(color: PdaTheme.primary.withValues(alpha: 0.4)),
              ),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  const Row(children: [
                    Icon(Icons.auto_awesome, size: 16, color: PdaTheme.primary),
                    SizedBox(width: 6),
                    Text('扫入条码，自动识别去向',
                        style: TextStyle(
                            fontSize: 13,
                            color: PdaTheme.primary,
                            fontWeight: FontWeight.w600)),
                  ]),
                  const SizedBox(height: 10),
                  Row(children: [
                    Expanded(
                      child: TextField(
                        controller: _codeCtrl,
                        autofocus: true,
                        textInputAction: TextInputAction.done,
                        style: const TextStyle(
                            fontSize: 16, color: PdaTheme.textPrimary),
                        decoration: const InputDecoration(
                          hintText: '扫码枪扫入 / 手动输入单号、库位、商品条码',
                        ),
                        onSubmitted: (_) => _identify(),
                      ),
                    ),
                    const SizedBox(width: 8),
                    ElevatedButton(
                      style: ElevatedButton.styleFrom(
                        minimumSize: const Size(88, 48),
                      ),
                      onPressed: _busy ? null : _identify,
                      child: _busy
                          ? const SizedBox(
                              width: 20,
                              height: 20,
                              child: CircularProgressIndicator(
                                  strokeWidth: 2, color: Colors.white),
                            )
                          : const Text('识别'),
                    ),
                  ]),
                ],
              ),
            ),
            const SizedBox(height: PdaSpacing.md),
            const Row(children: [
              Icon(Icons.apps, size: 16, color: PdaTheme.textSecondary),
              SizedBox(width: 6),
              Text('手动选择作业',
                  style: TextStyle(
                      fontSize: 13, color: PdaTheme.textSecondary)),
            ]),
            const SizedBox(height: PdaSpacing.sm),
            Flexible(
              child: list.isEmpty
                  ? const Padding(
                      padding: EdgeInsets.symmetric(vertical: 24),
                      child: Center(
                        child: Text('当前账号没有作业入口权限',
                            style: PdaStyles.sub),
                      ),
                    )
                  : GridView.count(
                      shrinkWrap: true,
                      crossAxisCount: 3,
                      mainAxisSpacing: PdaSpacing.sm,
                      crossAxisSpacing: PdaSpacing.sm,
                      childAspectRatio: 1.0,
                      children: [
                        for (final e in list)
                          _ScanTile(
                            entry: e,
                            onTap: () {
                              Navigator.pop(context);
                              Navigator.pushNamed(context, e.route);
                            },
                          ),
                      ],
                    ),
            ),
          ],
        ),
      ),
    );
  }
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
