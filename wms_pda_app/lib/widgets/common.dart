import 'package:flutter/material.dart';
import '../services/api_service.dart';
import '../theme/pda_theme.dart';

/// 顶部带图标的大扫码区。PDA 扫码枪以键盘输入方式工作，这里就是一个
/// 占满宽度的输入框 + 提交按钮，避免和普通表单混在一起。
class ScanZone extends StatelessWidget {
  final String title;
  final String hint;
  final IconData icon;
  final TextEditingController controller;
  final void Function(String value) onSubmit;
  final String? buttonLabel;

  const ScanZone({
    super.key,
    required this.title,
    required this.hint,
    required this.controller,
    required this.onSubmit,
    this.icon = Icons.qr_code_scanner,
    this.buttonLabel,
  });

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: PdaTheme.surface,
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: PdaTheme.border),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Row(children: [
            Icon(icon, color: PdaTheme.primary, size: 28),
            const SizedBox(width: 10),
            Text(title, style: PdaStyles.title),
          ]),
          const SizedBox(height: 12),
          Row(children: [
            Expanded(
              child: TextField(
                controller: controller,
                textInputAction: TextInputAction.done,
                style: const TextStyle(fontSize: 16, color: PdaTheme.textPrimary),
                decoration: InputDecoration(hintText: hint),
                onSubmitted: onSubmit,
              ),
            ),
            const SizedBox(width: 8),
            ElevatedButton(
              style: ElevatedButton.styleFrom(
                minimumSize: const Size(80, 48),
              ),
              onPressed: () {
                final v = controller.text.trim();
                if (v.isNotEmpty) onSubmit(v);
              },
              child: Text(buttonLabel ?? '确认'),
            ),
          ]),
        ],
      ),
    );
  }
}

/// 商品行：图标 + 名称 + 编码/库位 + 应/实数量 + 状态图标。
class ProductRow extends StatelessWidget {
  final String name;
  final String code;
  final String? qtyLabel;
  final String? qtyUnit;
  final Color? qtyColor;
  final IconData? statusIcon;
  final Color? statusColor;
  final VoidCallback? onTap;
  final Widget? trailing;
  final bool error;
  final bool dim;

  const ProductRow({
    super.key,
    required this.name,
    required this.code,
    this.qtyLabel,
    this.qtyUnit,
    this.qtyColor,
    this.statusIcon,
    this.statusColor,
    this.onTap,
    this.trailing,
    this.error = false,
    this.dim = false,
  });

  @override
  Widget build(BuildContext context) {
    final bg = error
        ? const Color(0x33FF5252)
        : (dim ? PdaTheme.surface2.withValues(alpha: 0.4) : PdaTheme.surface2);
    return InkWell(
      onTap: onTap,
      borderRadius: BorderRadius.circular(10),
      child: Container(
        margin: const EdgeInsets.only(bottom: 8),
        padding: const EdgeInsets.all(12),
        decoration: BoxDecoration(
          color: bg,
          borderRadius: BorderRadius.circular(10),
          border: Border.all(color: error ? PdaTheme.danger : PdaTheme.border),
        ),
        child: Row(children: [
          Container(
            width: 40,
            height: 40,
            alignment: Alignment.center,
            decoration: BoxDecoration(
              color: PdaTheme.surface3,
              borderRadius: BorderRadius.circular(8),
            ),
            child: const Text('📦', style: TextStyle(fontSize: 22)),
          ),
          const SizedBox(width: 10),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(name,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: PdaStyles.title),
                const SizedBox(height: 2),
                Text(code, style: PdaStyles.sub),
              ],
            ),
          ),
          if (trailing != null) trailing! else if (qtyLabel != null)
            Column(
              crossAxisAlignment: CrossAxisAlignment.end,
              children: [
                Text(qtyLabel!,
                    style: TextStyle(
                        fontSize: 18,
                        fontWeight: FontWeight.bold,
                        color: qtyColor ?? PdaTheme.textPrimary)),
                if (qtyUnit != null)
                  Text(qtyUnit!, style: PdaStyles.sub),
              ],
            ),
          if (statusIcon != null) ...[
            const SizedBox(width: 8),
            Icon(statusIcon, color: statusColor ?? PdaTheme.primary, size: 22),
          ],
        ]),
      ),
    );
  }
}

/// 进度条。
class PdaProgress extends StatelessWidget {
  final num current;
  final num total;
  final String? leftLabel;
  final String? rightLabel;
  const PdaProgress({
    super.key,
    required this.current,
    required this.total,
    this.leftLabel,
    this.rightLabel,
  });

  @override
  Widget build(BuildContext context) {
    final pct = total == 0 ? 0.0 : (current / total).clamp(0.0, 1.0);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        ClipRRect(
          borderRadius: BorderRadius.circular(6),
          child: LinearProgressIndicator(
            value: pct,
            minHeight: 10,
            backgroundColor: PdaTheme.surface3,
            valueColor: const AlwaysStoppedAnimation(PdaTheme.primary),
          ),
        ),
        const SizedBox(height: 6),
        Row(children: [
          Expanded(
              child: Text(leftLabel ?? '$current / $total',
                  style: PdaStyles.sub)),
          Text(rightLabel ?? '${(pct * 100).toStringAsFixed(0)}%',
              style: PdaStyles.sub),
        ]),
      ],
    );
  }
}

/// 信息提示条（info / warning / danger）。
class PdaAlert extends StatelessWidget {
  final String text;
  final Color color;
  final IconData icon;
  const PdaAlert({
    super.key,
    required this.text,
    this.color = PdaTheme.info,
    this.icon = Icons.info_outline,
  });

  factory PdaAlert.info(String t) =>
      PdaAlert(text: t, color: PdaTheme.info, icon: Icons.info_outline);
  factory PdaAlert.warning(String t) =>
      PdaAlert(text: t, color: PdaTheme.warning, icon: Icons.warning_amber);
  factory PdaAlert.danger(String t) =>
      PdaAlert(text: t, color: PdaTheme.danger, icon: Icons.error_outline);

  @override
  Widget build(BuildContext context) {
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.10),
        borderRadius: BorderRadius.circular(10),
        border: Border.all(color: color.withValues(alpha: 0.5)),
      ),
      child: Row(children: [
        Icon(icon, color: color, size: 20),
        const SizedBox(width: 8),
        Expanded(child: Text(text, style: TextStyle(fontSize: 13, color: color))),
      ]),
    );
  }
}

/// 推荐库位卡。
class LocationCard extends StatelessWidget {
  final String binCode;
  final String path;
  final String? tag;
  final IconData icon;
  final Color? tagColor;
  const LocationCard({
    super.key,
    required this.binCode,
    required this.path,
    this.tag,
    this.icon = Icons.location_on,
    this.tagColor,
  });

  @override
  Widget build(BuildContext context) {
    return Container(
      margin: const EdgeInsets.symmetric(vertical: 8),
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: PdaTheme.surface,
        borderRadius: BorderRadius.circular(10),
        border: Border.all(color: PdaTheme.border),
      ),
      child: Row(children: [
        Icon(icon, color: PdaTheme.primary, size: 28),
        const SizedBox(width: 10),
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(binCode, style: PdaStyles.title),
              const SizedBox(height: 2),
              Text(path, style: PdaStyles.sub),
            ],
          ),
        ),
        if (tag != null)
          Container(
            padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
            decoration: BoxDecoration(
              color: (tagColor ?? PdaTheme.primary).withValues(alpha: 0.15),
              borderRadius: BorderRadius.circular(6),
            ),
            child: Text(tag!,
                style: TextStyle(
                    fontSize: 12,
                    color: tagColor ?? PdaTheme.primary,
                    fontWeight: FontWeight.w600)),
          ),
      ]),
    );
  }
}

/// 页面容器，带可滚动的 body + 底部固定按钮条。
class PdaScaffold extends StatelessWidget {
  final String title;
  final Widget body;
  final List<Widget>? actions;
  final List<Widget>? bottomButtons;
  final Future<void> Function()? onRefresh;
  final Widget? floatingActionButton;
  const PdaScaffold({
    super.key,
    required this.title,
    required this.body,
    this.actions,
    this.bottomButtons,
    this.onRefresh,
    this.floatingActionButton,
  });

  @override
  Widget build(BuildContext context) {
    Widget content = Padding(
      padding: const EdgeInsets.all(12),
      child: body,
    );
    if (onRefresh != null) {
      content = RefreshIndicator(
        onRefresh: onRefresh!,
        color: PdaTheme.primary,
        backgroundColor: PdaTheme.surface,
        child: ListView(children: [content]),
      );
    } else {
      content = SingleChildScrollView(child: content);
    }
    return Scaffold(
      appBar: AppBar(
        title: Text(title),
        actions: actions,
      ),
      body: SafeArea(child: content),
      floatingActionButton: floatingActionButton,
      bottomNavigationBar: bottomButtons == null
          ? null
          : Container(
              padding: const EdgeInsets.all(12),
              decoration: const BoxDecoration(
                color: PdaTheme.surface,
                border: Border(top: BorderSide(color: PdaTheme.border)),
              ),
              child: Row(
                  children: bottomButtons!
                      .map((e) => Expanded(
                          child: Padding(
                            padding: const EdgeInsets.symmetric(horizontal: 4),
                            child: e,
                          )))
                      .toList()),
            ),
    );
  }
}

/// 全局 toast。
void toast(BuildContext context, String msg, {bool error = false}) {
  ScaffoldMessenger.of(context).showSnackBar(SnackBar(
    content: Text(msg),
    backgroundColor: error ? PdaTheme.danger : PdaTheme.surface3,
    behavior: SnackBarBehavior.floating,
    duration: Duration(seconds: error ? 3 : 2),
  ));
}

/// 异步动作包装：loading + 错误提示 + 成功 toast。
Future<T?> runWithBusy<T>(
  BuildContext context,
  Future<T> Function() action, {
  String? successMsg,
  String? busyMsg,
}) async {
  showDialog(
    context: context,
    barrierDismissible: false,
    builder: (_) => Center(
      child: Container(
        padding: const EdgeInsets.all(20),
        decoration: BoxDecoration(
          color: PdaTheme.surface,
          borderRadius: BorderRadius.circular(10),
        ),
        child: const CircularProgressIndicator(color: PdaTheme.primary),
      ),
    ),
  );
  try {
    final r = await action();
    if (context.mounted) Navigator.of(context, rootNavigator: true).pop();
    if (successMsg != null && context.mounted) toast(context, successMsg);
    return r;
  } catch (e) {
    if (context.mounted) Navigator.of(context, rootNavigator: true).pop();
    if (context.mounted) {
      toast(context, ApiService.friendlyError(e), error: true);
    }
    return null;
  }
}

/// Map 取值容错：key 可能是 camelCase 也可能因为大写别名有差异。
String pickStr(Map<String, dynamic> m, List<String> keys, [String dft = '']) {
  for (final k in keys) {
    final v = m[k];
    if (v != null && v.toString().trim().isNotEmpty) return v.toString();
  }
  return dft;
}

num pickNum(Map<String, dynamic> m, List<String> keys, [num dft = 0]) {
  for (final k in keys) {
    final v = m[k];
    if (v == null) continue;
    if (v is num) return v;
    return num.tryParse(v.toString()) ?? dft;
  }
  return dft;
}
