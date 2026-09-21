import 'package:flutter/material.dart';
import '../theme/pda_theme.dart';

/// 统一功能卡片数据（方案 V1.1 优化项四）。
class FunctionCardData {
  /// 功能标识。
  final String id;

  /// 显示名称。
  final String title;

  /// emoji 或文字图标。
  final String glyph;

  /// 主题色。
  final Color color;

  /// 待办角标；null 或 0 不显示。
  final int? badge;

  /// 点击回调。
  final VoidCallback onTap;

  const FunctionCardData({
    required this.id,
    required this.title,
    required this.glyph,
    required this.color,
    this.badge,
    required this.onTap,
  });
}

/// 统一功能网格（方案 V1.1 优化项四）。
///
/// 规则：
/// - 固定 [columns] = 4 列；
/// - 卡片为正方形（由网格自动约束）；
/// - 卡片间距固定 12；
/// - [fillPlaceholders] 为真时，末行不足 4 个用透明占位补齐，
///   保证各模块视觉对齐；
/// - 本身不滚动（shrinkWrap），交给外层滚动容器。
class FunctionCardGrid extends StatelessWidget {
  /// 模块标题。
  final String? sectionTitle;

  /// 卡片数据。
  final List<FunctionCardData> cards;

  /// 列数（固定 4）。
  final int columns;

  /// 卡片间距。
  final double spacing;

  /// 不足一行时是否补透明占位。
  final bool fillPlaceholders;

  const FunctionCardGrid({
    super.key,
    this.sectionTitle,
    required this.cards,
    this.columns = 4,
    this.spacing = PdaSpacing.sm,
    this.fillPlaceholders = true,
  });

  @override
  Widget build(BuildContext context) {
    final total = cards.length;
    final placeholderCount = fillPlaceholders
        ? ((columns - (total % columns)) % columns)
        : 0;
    final children = <Widget>[
      for (final c in cards)
        _FunctionCard(data: c),
      for (var i = 0; i < placeholderCount; i++) const _Placeholder(),
    ];
    final grid = GridView.count(
      crossAxisCount: columns,
      shrinkWrap: true,
      physics: const NeverScrollableScrollPhysics(),
      mainAxisSpacing: spacing,
      crossAxisSpacing: spacing,
      childAspectRatio: 1,
      children: children,
    );
    if (sectionTitle == null) return grid;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        _SectionTitle(sectionTitle!),
        SizedBox(height: spacing),
        grid,
      ],
    );
  }
}

class _SectionTitle extends StatelessWidget {
  final String text;
  const _SectionTitle(this.text);

  @override
  Widget build(BuildContext context) {
    return Row(children: [
      Container(
        width: 3,
        height: 16,
        decoration: BoxDecoration(
          color: PdaTheme.primary,
          borderRadius: BorderRadius.circular(2),
        ),
      ),
      const SizedBox(width: 8),
      Text(text,
          style: const TextStyle(
              fontSize: 15,
              fontWeight: FontWeight.w600,
              color: PdaTheme.textPrimary)),
    ]);
  }
}

/// 单个功能卡片：正方形、图标 + 名称、右上角角标。
class _FunctionCard extends StatelessWidget {
  final FunctionCardData data;
  const _FunctionCard({required this.data});

  @override
  Widget build(BuildContext context) {
    final hasBadge = (data.badge ?? 0) > 0;
    return Material(
      color: Colors.transparent,
      child: InkWell(
        onTap: data.onTap,
        borderRadius: BorderRadius.circular(PdaSpacing.radius),
        child: Stack(
          clipBehavior: Clip.none,
          children: [
            Container(
              decoration: BoxDecoration(
                color: PdaTheme.surface,
                borderRadius: BorderRadius.circular(PdaSpacing.radius),
                border: Border.all(color: PdaTheme.border),
              ),
              child: Column(
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  Container(
                    width: 44,
                    height: 44,
                    alignment: Alignment.center,
                    decoration: BoxDecoration(
                      color: data.color.withValues(alpha: 0.16),
                      borderRadius: BorderRadius.circular(12),
                    ),
                    child: Text(data.glyph,
                        style: const TextStyle(fontSize: 24, height: 1.1)),
                  ),
                  const SizedBox(height: 6),
                  Padding(
                    padding: const EdgeInsets.symmetric(horizontal: 2),
                    child: Text(
                      data.title,
                      textAlign: TextAlign.center,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(
                          fontSize: 12,
                          color: PdaTheme.textPrimary,
                          fontWeight: FontWeight.w600),
                    ),
                  ),
                ],
              ),
            ),
            if (hasBadge)
              Positioned(
                top: -4,
                right: -2,
                child: Container(
                  constraints: const BoxConstraints(minWidth: 20),
                  padding: const EdgeInsets.symmetric(
                      horizontal: 5, vertical: 2),
                  decoration: BoxDecoration(
                    color: PdaTheme.danger,
                    borderRadius: BorderRadius.circular(10),
                    border: Border.all(color: PdaTheme.bg, width: 2),
                  ),
                  child: Text('${data.badge}',
                      textAlign: TextAlign.center,
                      style: const TextStyle(
                          color: Colors.white,
                          fontSize: 10,
                          fontWeight: FontWeight.bold,
                          height: 1.1)),
                ),
              ),
          ],
        ),
      ),
    );
  }
}

/// 透明占位块：保持网格对齐，不响应点击。
class _Placeholder extends StatelessWidget {
  const _Placeholder();

  @override
  Widget build(BuildContext context) {
    return const SizedBox.expand();
  }
}
