import 'dart:convert';
import 'dart:ui' as ui;
import 'package:flutter/material.dart';
import '../config/theme.dart';

/// 通用卡片（对齐原型 .mcard）。
class MCard extends StatelessWidget {
  final Widget child;
  final EdgeInsets? padding;
  final Color? leftBar;
  final bool selected;
  final VoidCallback? onTap;
  const MCard({super.key, required this.child, this.padding, this.leftBar, this.selected = false, this.onTap});

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      onTap: onTap,
      child: Container(
        width: double.infinity,
        margin: const EdgeInsets.only(bottom: 8),
        padding: padding ?? const EdgeInsets.all(12),
        decoration: BoxDecoration(
          color: Colors.white,
          borderRadius: BorderRadius.circular(12),
          border: selected ? Border.all(color: TmsTheme.accent, width: 2) : null,
          boxShadow: const [BoxShadow(color: Color(0x10000000), blurRadius: 3, offset: Offset(0, 1))],
        ),
        child: leftBar != null
            ? IntrinsicHeight(child: Row(children: [Container(width: 3, color: leftBar, margin: const EdgeInsets.only(right: 10)), Expanded(child: child)]))
            : child,
      ),
    );
  }
}

/// 状态标签（对齐原型 .mtag）。
class MTag extends StatelessWidget {
  final String text;
  final Color color;
  final Color bg;
  const MTag.blue(this.text, {super.key}) : color = TmsTheme.accent, bg = const Color(0xFFDBEAFE);
  const MTag.orange(this.text, {super.key}) : color = TmsTheme.accent2, bg = const Color(0xFFFFF7ED);
  const MTag.green(this.text, {super.key}) : color = TmsTheme.ok, bg = const Color(0xFFDCFCE7);
  const MTag.red(this.text, {super.key}) : color = TmsTheme.bad, bg = const Color(0xFFFEE2E2);
  const MTag.gray(this.text, {super.key}) : color = TmsTheme.muted, bg = const Color(0xFFF3F4F6);
  const MTag.purple(this.text, {super.key}) : color = TmsTheme.returnPurple, bg = const Color(0xFFEDE9FE);
  const MTag.custom(this.text, {super.key, required this.color, required this.bg});

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
      decoration: BoxDecoration(color: bg, borderRadius: BorderRadius.circular(10)),
      child: Text(text, style: TextStyle(fontSize: 10, color: color, fontWeight: FontWeight.w700)),
    );
  }
}

/// 信息行（对齐原型 .mline）。
class MLine extends StatelessWidget {
  final String label;
  final String value;
  final Color? valueColor;
  const MLine(this.label, this.value, {super.key, this.valueColor});
  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 5),
      child: Row(
        mainAxisAlignment: MainAxisAlignment.spaceBetween,
        children: [
          Text(label, style: const TextStyle(fontSize: 13, color: TmsTheme.muted)),
          Flexible(child: Text(value, style: TextStyle(fontSize: 13, color: valueColor ?? TmsTheme.ink, fontWeight: FontWeight.w600), textAlign: TextAlign.right)),
        ],
      ),
    );
  }
}

/// 提示条（对齐原型 .alert）。
class Alert extends StatelessWidget {
  final String text;
  final Color color;
  final Color bg;
  const Alert.info(this.text, {super.key}) : color = TmsTheme.accent, bg = const Color(0xFFDBEAFE);
  const Alert.warn(this.text, {super.key}) : color = TmsTheme.accent2, bg = const Color(0xFFFFF7ED);
  const Alert.ok(this.text, {super.key}) : color = TmsTheme.ok, bg = const Color(0xFFDCFCE7);
  const Alert.danger(this.text, {super.key}) : color = TmsTheme.bad, bg = const Color(0xFFFEE2E2);

  @override
  Widget build(BuildContext context) {
    return Container(
      width: double.infinity,
      margin: const EdgeInsets.symmetric(vertical: 4),
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
      decoration: BoxDecoration(color: bg, borderRadius: BorderRadius.circular(8)),
      child: Text(text, style: TextStyle(fontSize: 11, color: color)),
    );
  }
}

/// 主按钮（对齐原型 .btn.primary）。
class TmsButton extends StatelessWidget {
  final String text;
  final VoidCallback? onPressed;
  final Color? color;
  final Color? textColor;
  final bool outline;
  const TmsButton.primary(this.text, {super.key, this.onPressed}) : color = TmsTheme.accent, textColor = Colors.white, outline = false;
  const TmsButton.warn(this.text, {super.key, this.onPressed}) : color = TmsTheme.accent2, textColor = Colors.white, outline = false;
  const TmsButton.danger(this.text, {super.key, this.onPressed}) : color = TmsTheme.bad, textColor = Colors.white, outline = false;
  const TmsButton.purple(this.text, {super.key, this.onPressed}) : color = TmsTheme.returnPurple, textColor = Colors.white, outline = false;
  const TmsButton.outline(this.text, {super.key, this.onPressed, this.color}) : textColor = color ?? TmsTheme.accent, outline = true;

  @override
  Widget build(BuildContext context) {
    return SizedBox(
      width: double.infinity,
      child: ElevatedButton(
        onPressed: onPressed,
        style: ElevatedButton.styleFrom(
          backgroundColor: outline ? Colors.white : color,
          foregroundColor: textColor,
          elevation: 0,
          padding: const EdgeInsets.symmetric(vertical: 11),
          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(10), side: outline ? BorderSide(color: color ?? TmsTheme.accent, width: 1.5) : BorderSide.none),
        ),
        child: Text(text, style: const TextStyle(fontSize: 14, fontWeight: FontWeight.w700)),
      ),
    );
  }
}

/// 手写签名板（对齐原型 .sigbox canvas 签名）。
///
/// 用法：
/// ```dart
/// final _sigKey = GlobalKey<SignaturePadState>();
/// SignaturePad(key: _sigKey, height: 120)
/// // 清除：_sigKey.currentState?.clear();
/// // 导出 base64 PNG：final b64 = await _sigKey.currentState?.exportAsBase64Png();
/// ```
class SignaturePad extends StatefulWidget {
  final double height;
  final Color penColor;
  final double penWidth;
  final String placeholder;
  final Color? backgroundColor;

  const SignaturePad({
    super.key,
    this.height = 100,
    this.penColor = const Color(0xFF1A1C23),
    this.penWidth = 2.5,
    this.placeholder = '✍️ 请在此区域手写签名',
    this.backgroundColor,
  });

  @override
  State<SignaturePad> createState() => SignaturePadState();
}

class SignaturePadState extends State<SignaturePad> {
  final List<List<Offset>> _strokes = [];
  List<Offset> _currentStroke = [];
  Size _canvasSize = Size.zero;

  bool get isEmpty => _strokes.isEmpty;

  void clear() {
    setState(_strokes.clear);
  }

  /// 导出为 base64 PNG（透明背景，不含 data: 前缀）。空签名返回 null。
  Future<String?> exportAsBase64Png() async {
    if (isEmpty || _canvasSize == Size.zero) return null;
    final recorder = ui.PictureRecorder();
    final canvas = ui.Canvas(recorder);
    final painter = _SignaturePainter(
      strokes: _strokes,
      penColor: widget.penColor,
      penWidth: widget.penWidth,
    );
    painter.paint(canvas, _canvasSize);
    final picture = recorder.endRecording();
    final image = await picture.toImage(
      _canvasSize.width.round(),
      _canvasSize.height.round(),
    );
    final byteData = await image.toByteData(format: ui.ImageByteFormat.png);
    if (byteData == null) return null;
    return base64Encode(byteData.buffer.asUint8List());
  }

  @override
  Widget build(BuildContext context) {
    return SizedBox(
      height: widget.height,
      child: LayoutBuilder(builder: (context, constraints) {
        _canvasSize = Size(constraints.maxWidth, widget.height);
        return GestureDetector(
          onPanStart: (d) {
            setState(() {
              _currentStroke = [d.localPosition];
              _strokes.add(_currentStroke);
            });
          },
          onPanUpdate: (d) {
            setState(() => _currentStroke.add(d.localPosition));
          },
          onPanEnd: (_) {
            _currentStroke = [];
          },
          child: Container(
            decoration: BoxDecoration(
              border: Border.all(color: TmsTheme.rule, width: 1.5),
              borderRadius: BorderRadius.circular(8),
              color: widget.backgroundColor ?? const Color(0xFFFAFBFC),
            ),
            child: CustomPaint(
              painter: _SignaturePainter(
                strokes: _strokes,
                penColor: widget.penColor,
                penWidth: widget.penWidth,
              ),
              child: isEmpty
                  ? Center(
                      child: Text(
                        widget.placeholder,
                        style: const TextStyle(fontSize: 12, color: Color(0xFFA0A7B2)),
                      ),
                    )
                  : null,
            ),
          ),
        );
      }),
    );
  }
}

class _SignaturePainter extends CustomPainter {
  final List<List<Offset>> strokes;
  final Color penColor;
  final double penWidth;

  _SignaturePainter({
    required this.strokes,
    required this.penColor,
    required this.penWidth,
  });

  @override
  void paint(Canvas canvas, Size size) {
    final paint = Paint()
      ..color = penColor
      ..strokeWidth = penWidth
      ..strokeCap = StrokeCap.round
      ..strokeJoin = StrokeJoin.round
      ..style = PaintingStyle.stroke;

    for (final stroke in strokes) {
      if (stroke.length < 2) {
        if (stroke.isNotEmpty) {
          canvas.drawPoints(
            ui.PointMode.points,
            [stroke[0]],
            paint..strokeWidth = penWidth * 1.5,
          );
        }
        continue;
      }
      final path = Path()..moveTo(stroke[0].dx, stroke[0].dy);
      for (var i = 1; i < stroke.length; i++) {
        path.lineTo(stroke[i].dx, stroke[i].dy);
      }
      canvas.drawPath(path, paint);
    }
  }

  @override
  bool shouldRepaint(covariant _SignaturePainter old) => true;
}
