import 'package:flutter/material.dart';
import '../theme/pda_theme.dart';

/// 不可逆操作二次确认弹窗（方案 V1.1 优化项三）。
///
/// 弹窗内显示操作摘要（标题 + 若干键值明细），用户点「确认」返回 true。
/// 用于删除、报损、取消、撤销等不可轻易回退的操作。
class ConfirmDialog {
  ConfirmDialog._();

  /// 弹出确认框。
  ///
  /// - [title]：操作标题，如「确认报损」；
  /// - [summary]：摘要行（键值对），逐行展示单号/商品/数量等；
  /// - [confirmText]：确认按钮文案；
  /// - [danger]：是否危险操作（红色确认按钮）。
  ///
  /// 返回 true 表示用户确认；其余（取消/点外部/返回）为 false。
  static Future<bool> show(
    BuildContext context, {
    required String title,
    List<({String k, String v})> summary = const [],
    String confirmText = '确认',
    String cancelText = '取消',
    bool danger = true,
  }) async {
    final result = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        backgroundColor: PdaTheme.surface,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(PdaSpacing.radius),
        ),
        title: Row(children: [
          Icon(
            danger ? Icons.error_outline_rounded : Icons.help_outline_rounded,
            color: danger ? PdaTheme.danger : PdaTheme.primary,
            size: 22,
          ),
          const SizedBox(width: 8),
          Expanded(child: Text(title, style: PdaStyles.title)),
        ]),
        content: summary.isEmpty
            ? null
            : Column(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  for (final s in summary)
                    Padding(
                      padding: const EdgeInsets.only(bottom: 6),
                      child: Row(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          SizedBox(
                            width: 72,
                            child: Text(s.k,
                                style: PdaStyles.sub),
                          ),
                          Expanded(
                            child: Text(s.v,
                                style: const TextStyle(
                                    fontSize: 14,
                                    color: PdaTheme.textPrimary)),
                          ),
                        ],
                      ),
                    ),
                ],
              ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx, false),
            child: Text(cancelText,
                style:
                    const TextStyle(color: PdaTheme.textSecondary)),
          ),
          ElevatedButton(
            style: ElevatedButton.styleFrom(
              backgroundColor:
                  danger ? PdaTheme.danger : PdaTheme.primary,
            ),
            onPressed: () => Navigator.pop(ctx, true),
            child: Text(confirmText),
          ),
        ],
      ),
    );
    return result ?? false;
  }
}
