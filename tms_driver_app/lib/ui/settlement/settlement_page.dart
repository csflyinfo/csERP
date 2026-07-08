import 'dart:convert';
import 'dart:io';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:image_picker/image_picker.dart';
import '../../config/theme.dart';
import '../../models/settlement.dart';
import '../../providers/settlement_provider.dart';
import '../../providers/task_provider.dart';
import '../../services/api_service.dart';
import '../../widgets/common.dart';

/// 交账结算页面（P3-3）。
///
/// 流程：
///   1. 展示本日配送汇总（应收/实收现金/线上收款/退货/应交回）
///   2. 输入实际交回金额 → 自动计算差异
///   3. 拍结算照片（现金清点/收款截图/POS签购单）
///   4. 电子签名确认
///   5. 提交 → ERP 端财务审核
class SettlementPage extends ConsumerStatefulWidget {
  const SettlementPage({super.key});

  @override
  ConsumerState<SettlementPage> createState() => _SettlementPageState();
}

class _SettlementPageState extends ConsumerState<SettlementPage> {
  final _actualSubmitCtrl = TextEditingController();
  final _diffReasonCtrl = TextEditingController();
  final _remarkCtrl = TextEditingController();
  final _signatureKey = GlobalKey<SignaturePadState>();
  final List<XFile> _photos = [];
  bool _submitting = false;
  num _submitAmount = 0;

  @override
  void dispose() {
    _actualSubmitCtrl.dispose();
    _diffReasonCtrl.dispose();
    _remarkCtrl.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final async = ref.watch(settlementSummaryProvider);
    return Scaffold(
      backgroundColor: TmsTheme.bg,
      appBar: AppBar(title: const Text('交账结算')),
      body: async.when(
        data: (summary) => summary.alreadySettled ? _buildAlreadySettled(summary) : _buildForm(summary),
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (e, _) => Center(
          child: Padding(
            padding: const EdgeInsets.all(24),
            child: Text('加载失败：$e', style: const TextStyle(color: TmsTheme.muted)),
          ),
        ),
      ),
    );
  }

  /// 已交账状态。
  Widget _buildAlreadySettled(SettlementSummary s) {
    final statusText = s.status == 'APPROVED' ? '已审核' : s.status == 'DISPUTED' ? '差异争议' : '待审核';
    final statusColor = s.status == 'APPROVED' ? TmsTheme.ok : s.status == 'DISPUTED' ? TmsTheme.bad : TmsTheme.accent2;
    return ListView(
      padding: const EdgeInsets.all(40),
      children: [
        Icon(Icons.check_circle, size: 56, color: statusColor),
        const SizedBox(height: 16),
        const Text('今日已交账', textAlign: TextAlign.center, style: TextStyle(fontSize: 16, fontWeight: FontWeight.w700, color: TmsTheme.ink)),
        const SizedBox(height: 8),
        Text('交账单号：${s.settlementNo}', textAlign: TextAlign.center, style: const TextStyle(fontSize: 13, color: TmsTheme.muted)),
        const SizedBox(height: 4),
        Text('状态：$statusText', textAlign: TextAlign.center, style: TextStyle(fontSize: 13, color: statusColor, fontWeight: FontWeight.w600)),
      ],
    );
  }

  /// 交账表单。
  Widget _buildForm(SettlementSummary s) {
    _submitAmount = s.submitAmount;
    // 初始化实际交回金额为应交回金额
    if (_actualSubmitCtrl.text.isEmpty) {
      _actualSubmitCtrl.text = s.submitAmount.toStringAsFixed(2);
    }

    return ListView(
      padding: const EdgeInsets.all(14),
      children: [
        // 汇总卡片
        MCard(
          leftBar: TmsTheme.accent,
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Row(children: [
              const Icon(Icons.summarize, size: 18, color: TmsTheme.accent),
              const SizedBox(width: 6),
              const Text('本日配送汇总', style: TextStyle(fontSize: 14, fontWeight: FontWeight.w700, color: TmsTheme.ink)),
              const Spacer(),
              Text('${s.signedStores}/${s.totalStores} 门店', style: const TextStyle(fontSize: 12, color: TmsTheme.muted)),
            ]),
            const SizedBox(height: 12),
            _amountRow('应收总金额', s.totalAmount, TmsTheme.ink),
            _amountRow('实收现金', s.cashAmount, TmsTheme.ok),
            _amountRow('线上收款', s.onlineAmount, TmsTheme.accent),
            if (s.returnAmount > 0) ...[
              _amountRow('退货金额', s.returnAmount, TmsTheme.bad),
              _amountRow('退货件数', s.returnQty, TmsTheme.bad, isQty: true),
            ],
            const Divider(height: 20),
            _amountRow('应交回现金', s.submitAmount, TmsTheme.accent2, bold: true),
          ]),
        ),
        const SizedBox(height: 14),

        // 实际交回金额
        MCard(
          leftBar: TmsTheme.accent2,
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            const Text('实际交回金额', style: TextStyle(fontSize: 13, fontWeight: FontWeight.w700, color: TmsTheme.ink)),
            const SizedBox(height: 8),
            TextField(
              controller: _actualSubmitCtrl,
              keyboardType: const TextInputType.numberWithOptions(decimal: true),
              inputFormatters: [FilteringTextInputFormatter.allow(RegExp(r'^\d*\.?\d{0,2}'))],
              decoration: const InputDecoration(
                prefixText: '¥ ',
                border: OutlineInputBorder(),
                contentPadding: EdgeInsets.symmetric(horizontal: 12, vertical: 10),
              ),
              style: const TextStyle(fontSize: 18, fontWeight: FontWeight.w700, color: TmsTheme.accent2),
              onChanged: (_) => setState(() {}),
            ),
            const SizedBox(height: 8),
            _buildDiffDisplay(),
          ]),
        ),
        const SizedBox(height: 14),

        // 结算照片
        MCard(
          leftBar: TmsTheme.accent,
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Row(children: [
              const Text('结算照片', style: TextStyle(fontSize: 13, fontWeight: FontWeight.w700, color: TmsTheme.ink)),
              const Spacer(),
              Text('${_photos.length}/6 张', style: const TextStyle(fontSize: 11, color: TmsTheme.muted)),
            ]),
            const SizedBox(height: 4),
            const Text('现金清点 / 收款截图 / POS签购单', style: TextStyle(fontSize: 11, color: TmsTheme.muted)),
            const SizedBox(height: 8),
            _buildPhotoGrid(),
          ]),
        ),
        const SizedBox(height: 14),

        // 电子签名
        MCard(
          leftBar: TmsTheme.accent,
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Row(children: [
              const Text('电子签名', style: TextStyle(fontSize: 13, fontWeight: FontWeight.w700, color: TmsTheme.ink)),
              const Spacer(),
              TextButton(
                onPressed: () => _signatureKey.currentState?.clear(),
                child: const Text('清除', style: TextStyle(fontSize: 12, color: TmsTheme.muted)),
              ),
            ]),
            const SizedBox(height: 4),
            const Text('司机签名确认交账金额无误', style: TextStyle(fontSize: 11, color: TmsTheme.muted)),
            const SizedBox(height: 8),
            SignaturePad(
              key: _signatureKey,
              height: 120,
              penColor: TmsTheme.ink,
            ),
          ]),
        ),
        const SizedBox(height: 14),

        // 差异原因（有差异时显示）
        if (_calcDiff() != 0) ...[
          MCard(
            leftBar: TmsTheme.bad,
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              const Text('差异说明', style: TextStyle(fontSize: 13, fontWeight: FontWeight.w700, color: TmsTheme.bad)),
              const SizedBox(height: 8),
              TextField(
                controller: _diffReasonCtrl,
                maxLines: 2,
                decoration: const InputDecoration(
                  hintText: '请说明差异原因（如找零误差、垫付等）',
                  border: OutlineInputBorder(),
                  contentPadding: EdgeInsets.symmetric(horizontal: 12, vertical: 10),
                ),
                style: const TextStyle(fontSize: 13),
              ),
            ]),
          ),
          const SizedBox(height: 14),
        ],

        // 备注
        MCard(
          leftBar: TmsTheme.muted,
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            const Text('备注', style: TextStyle(fontSize: 13, fontWeight: FontWeight.w700, color: TmsTheme.ink)),
            const SizedBox(height: 8),
            TextField(
              controller: _remarkCtrl,
              maxLines: 2,
              decoration: const InputDecoration(
                hintText: '选填',
                border: OutlineInputBorder(),
                contentPadding: EdgeInsets.symmetric(horizontal: 12, vertical: 10),
              ),
              style: const TextStyle(fontSize: 13),
            ),
          ]),
        ),
        const SizedBox(height: 14),

        // 提交按钮
        TmsButton.primary(
          _submitting ? '提交中...' : '确认交账',
          onPressed: _submitting ? null : _submit,
        ),
        const SizedBox(height: 20),
      ],
    );
  }

  /// 金额行。
  Widget _amountRow(String label, num value, Color color, {bool bold = false, bool isQty = false}) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 3),
      child: Row(mainAxisAlignment: MainAxisAlignment.spaceBetween, children: [
        Text(label, style: TextStyle(fontSize: 13, color: TmsTheme.muted, fontWeight: bold ? FontWeight.w600 : FontWeight.w400)),
        Text(
          isQty ? '$value 件' : '¥ ${value.toStringAsFixed(2)}',
          style: TextStyle(fontSize: 14, color: color, fontWeight: bold ? FontWeight.w700 : FontWeight.w600),
        ),
      ]),
    );
  }

  /// 差异显示。
  Widget _buildDiffDisplay() {
    final diff = _calcDiff();
    if (diff == 0) {
      return const Row(children: [
        Icon(Icons.check_circle, size: 14, color: TmsTheme.ok),
        SizedBox(width: 4),
        Text('金额一致，无差异', style: TextStyle(fontSize: 12, color: TmsTheme.ok)),
      ]);
    }
    final isShort = diff < 0;
    return Row(children: [
      Icon(isShort ? Icons.warning : Icons.add_circle, size: 14, color: isShort ? TmsTheme.bad : TmsTheme.ok),
      const SizedBox(width: 4),
      Text(
        '${isShort ? "短款" : "长款"} ¥ ${diff.abs().toStringAsFixed(2)}',
        style: TextStyle(fontSize: 12, color: isShort ? TmsTheme.bad : TmsTheme.ok, fontWeight: FontWeight.w600),
      ),
    ]);
  }

  /// 计算差异。
  num _calcDiff() {
    final actual = num.tryParse(_actualSubmitCtrl.text) ?? 0;
    return actual - _submitAmount;
  }

  /// 照片网格。
  Widget _buildPhotoGrid() {
    return Wrap(
      spacing: 8,
      runSpacing: 8,
      children: [
        ..._photos.asMap().entries.map((e) => _photoThumb(e.key, e.value)),
        if (_photos.length < 6) _addPhotoButton(),
      ],
    );
  }

  Widget _photoThumb(int index, XFile file) {
    return Stack(children: [
      ClipRRect(
        borderRadius: BorderRadius.circular(6),
        child: Image.file(File(file.path), width: 72, height: 72, fit: BoxFit.cover),
      ),
      Positioned(
        right: 0, top: 0,
        child: GestureDetector(
          onTap: () => setState(() => _photos.removeAt(index)),
          child: Container(
            padding: const EdgeInsets.all(2),
            decoration: const BoxDecoration(color: Colors.black54, shape: BoxShape.circle),
            child: const Icon(Icons.close, size: 12, color: Colors.white),
          ),
        ),
      ),
    ]);
  }

  Widget _addPhotoButton() {
    return GestureDetector(
      onTap: _pickPhoto,
      child: Container(
        width: 72, height: 72,
        decoration: BoxDecoration(
          border: Border.all(color: TmsTheme.rule),
          borderRadius: BorderRadius.circular(6),
        ),
        child: const Column(mainAxisAlignment: MainAxisAlignment.center, children: [
          Icon(Icons.camera_alt, size: 20, color: TmsTheme.muted),
          SizedBox(height: 2),
          Text('拍照', style: TextStyle(fontSize: 10, color: TmsTheme.muted)),
        ]),
      ),
    );
  }

  Future<void> _pickPhoto() async {
    final picker = ImagePicker();
    final photo = await picker.pickImage(source: ImageSource.camera, imageQuality: 70);
    if (photo != null) setState(() => _photos.add(photo));
  }

  Future<void> _submit() async {
    // 校验
    final actual = num.tryParse(_actualSubmitCtrl.text);
    if (actual == null) {
      _toast('请输入实际交回金额');
      return;
    }
    if (_photos.isEmpty) {
      _toast('请至少拍摄 1 张结算照片');
      return;
    }
    final signatureB64 = await _signatureKey.currentState?.exportAsBase64Png();
    if (signatureB64 == null || signatureB64.isEmpty) {
      _toast('请完成电子签名');
      return;
    }

    setState(() => _submitting = true);
    try {
      // 上传签名图（base64 → 临时文件 → 上传获得 URL）
      final sigBytes = base64Decode(signatureB64);
      final tempDir = await Directory.systemTemp.createTemp('sig');
      final sigFile = File('${tempDir.path}/signature.png');
      await sigFile.writeAsBytes(sigBytes);
      final sigResult = await ApiService.instance.uploadImage(sigFile, bizType: 'SIGNATURE');
      final signatureUrl = sigResult['url'] as String;

      // 上传结算照片（XFile → File → 上传获得 URL）
      final photoList = <Map<String, dynamic>>[];
      for (final p in _photos) {
        final upResult = await ApiService.instance.uploadImage(File(p.path), bizType: 'SETTLEMENT');
        photoList.add({'url': upResult['url'], 'photoType': 'CASH'});
      }

      final result = await ref.read(settlementSubmitProvider(SettlementSubmitArgs(
        actualSubmit: actual,
        diffReason: _diffReasonCtrl.text.trim(),
        signatureImg: signatureUrl,
        remark: _remarkCtrl.text.trim(),
        photos: photoList,
      )).future);

      final no = result['settlementNo']?.toString() ?? '';
      final diff = result['diffAmount'] as num? ?? 0;
      String msg = '交账提交成功：$no';
      if (diff != 0) msg += '（差异 ¥ ${diff.toStringAsFixed(2)}）';
      _toast(msg);
      ref.invalidate(todayTasksProvider);
      ref.invalidate(settlementSummaryProvider);
      if (mounted) Navigator.pop(context, true);
    } catch (e) {
      _toast('提交失败：${e.toString().replaceFirst("Exception: ", "")}');
    } finally {
      if (mounted) setState(() => _submitting = false);
    }
  }

  void _toast(String msg) {
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(msg), behavior: SnackBarBehavior.floating));
  }
}
