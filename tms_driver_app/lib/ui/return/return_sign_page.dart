import 'dart:convert';
import 'dart:io';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:image_picker/image_picker.dart';
import '../../config/theme.dart';
import '../../models/return_order.dart';
import '../../providers/task_provider.dart';
import '../../services/api_service.dart';
import '../../widgets/common.dart';

/// 退货回收处理（V1.2 闭环终点，对齐原型 Screen O）。
///
/// 流程：退货单信息 → 逐商品录入实收数量 → 回收拍照 → 客户确认签名 → 提交
/// 提交后回写 sales_return_apply.signed_qty + logistics_status=司机已回收。
class ReturnSignPage extends ConsumerStatefulWidget {
  final String applyNo;
  const ReturnSignPage({super.key, required this.applyNo});

  @override
  ConsumerState<ReturnSignPage> createState() => _ReturnSignPageState();
}

class _ReturnSignPageState extends ConsumerState<ReturnSignPage> {
  final _signerCtrl = TextEditingController();
  final _remarkCtrl = TextEditingController();
  final _sigKey = GlobalKey<SignaturePadState>();
  final List<num> _signedQties = [];
  final List<XFile> _photos = [];
  bool _submitting = false;

  @override
  void dispose() {
    _signerCtrl.dispose();
    _remarkCtrl.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final orderAsync = ref.watch(returnDetailProvider(widget.applyNo));
    return Scaffold(
      backgroundColor: TmsTheme.bg,
      appBar: AppBar(
        backgroundColor: TmsTheme.returnPurple,
        title: Text(widget.applyNo),
      ),
      body: orderAsync.when(
        data: (order) {
          // 初始化实收数量默认=退货数
          if (_signedQties.length != order.details.length) {
            _signedQties.clear();
            for (final it in order.details) {
              _signedQties.add(it.returnQty);
            }
          }
          return _buildBody(order);
        },
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (e, _) => Center(child: Padding(padding: const EdgeInsets.all(24), child: Text('加载失败：$e', style: const TextStyle(color: TmsTheme.muted)))),
      ),
    );
  }

  Widget _buildBody(ReturnOrder order) {
    final totalReturn = order.details.fold<num>(0, (s, it) => s + it.returnQty);
    final totalSigned = _signedQties.fold<num>(0, (s, v) => s + v);
    final diff = totalSigned - totalReturn;
    return ListView(
      padding: const EdgeInsets.all(14),
      children: [
        Alert.info('📦 退货回收任务 · 退货单 ${order.applyNo}'),
        const SizedBox(height: 8),
        // 退货单信息卡
        MCard(
          leftBar: TmsTheme.returnPurple,
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Row(mainAxisAlignment: MainAxisAlignment.spaceBetween, children: [
              Text('退货单：${order.applyNo}', style: const TextStyle(fontSize: 14, fontWeight: FontWeight.w700, color: TmsTheme.ink)),
              const MTag.purple('待回收'),
            ]),
            const SizedBox(height: 4),
            MLine('客户', order.customerName),
            MLine('仓库', order.warehouse),
            MLine('退货日期', order.billDate),
            MLine('退货原因', order.returnReason.isEmpty ? '—' : order.returnReason),
            MLine('当前物流状态', order.logisticsStatus),
          ]),
        ),
        const SizedBox(height: 8),
        // 退货商品 · 录入实收数量
        MCard(
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            const Text('📦 退货商品 · 录入实收数量', style: TextStyle(fontSize: 14, fontWeight: FontWeight.w700, color: TmsTheme.ink)),
            const Text('（实收可小于退货数，差异自动标记待处理）', style: TextStyle(fontSize: 11, color: TmsTheme.muted)),
            const SizedBox(height: 8),
            ...order.details.asMap().entries.map((e) {
              final i = e.key;
              final it = e.value;
              return _ReturnItemRow(
                item: it,
                signedQty: _signedQties[i],
                onChanged: (v) => setState(() => _signedQties[i] = v),
              );
            }),
            const SizedBox(height: 8),
            Container(
              padding: const EdgeInsets.all(10),
              decoration: BoxDecoration(color: TmsTheme.bg, borderRadius: BorderRadius.circular(8)),
              child: Row(mainAxisAlignment: MainAxisAlignment.spaceBetween, children: [
                Text('合计：退货 $totalReturn 件', style: const TextStyle(fontSize: 12, color: TmsTheme.muted)),
                Text('实收 $totalSigned 件', style: const TextStyle(fontSize: 12, color: TmsTheme.ok, fontWeight: FontWeight.w700)),
                Text('差异 $diff 件', style: TextStyle(fontSize: 12, color: diff < 0 ? TmsTheme.bad : TmsTheme.muted, fontWeight: FontWeight.w700)),
              ]),
            ),
          ]),
        ),
        const SizedBox(height: 8),
        // 回收拍照
        MCard(
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Row(children: [
              const Text('📸 退货实物照片', style: TextStyle(fontSize: 14, fontWeight: FontWeight.w700, color: TmsTheme.ink)),
              const SizedBox(width: 6),
              Text('（至少 2 张，已拍 ${_photos.length} 张）', style: const TextStyle(fontSize: 11, color: TmsTheme.muted)),
            ]),
            const SizedBox(height: 8),
            Wrap(
              spacing: 8,
              runSpacing: 8,
              children: [
                ..._photos.asMap().entries.map((e) => _PhotoTile(
                      photo: e.value,
                      index: e.key + 1,
                      onDelete: () => setState(() => _photos.removeAt(e.key)),
                    )),
                if (_photos.length < 6)
                  _AddPhotoTile(onTap: _pickPhoto),
              ],
            ),
          ]),
        ),
        const SizedBox(height: 8),
        // 客户确认签名
        MCard(
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            const Text('✍️ 客户确认签名', style: TextStyle(fontSize: 14, fontWeight: FontWeight.w700, color: TmsTheme.ink)),
            const SizedBox(height: 6),
            _Field('签收人姓名', _signerCtrl, placeholder: '请输入签收人姓名'),
            const SizedBox(height: 6),
            SignaturePad(
              key: _sigKey,
              height: 120,
              placeholder: '客户确认退货退款',
            ),
            const SizedBox(height: 4),
            Align(
              alignment: Alignment.centerRight,
              child: TextButton.icon(
                onPressed: () => _sigKey.currentState?.clear(),
                icon: const Icon(Icons.clear, size: 14),
                label: const Text('清除签名', style: TextStyle(fontSize: 11)),
                style: TextButton.styleFrom(
                  padding: const EdgeInsets.symmetric(horizontal: 8),
                  minimumSize: Size.zero,
                  tapTargetSize: MaterialTapTargetSize.shrinkWrap,
                ),
              ),
            ),
          ]),
        ),
        const SizedBox(height: 8),
        _Field('备注说明', _remarkCtrl, placeholder: '可选，如：少件/包装破损情况'),
        const SizedBox(height: 16),
        Row(children: [
          Expanded(child: TmsButton.outline('稍后处理', color: TmsTheme.muted, onPressed: () => Navigator.pop(context))),
          const SizedBox(width: 8),
          Expanded(child: TmsButton.purple(_submitting ? '提交中...' : '确认回收', onPressed: _submitting ? null : () => _submit(order))),
        ]),
        const SizedBox(height: 20),
      ],
    );
  }

  Future<void> _pickPhoto() async {
    final picker = ImagePicker();
    final photo = await picker.pickImage(source: ImageSource.camera, imageQuality: 70);
    if (photo != null) {
      setState(() => _photos.add(photo));
    }
  }

  Future<void> _submit(ReturnOrder order) async {
    if (_signerCtrl.text.trim().isEmpty) {
      _toast('请输入签收人姓名');
      return;
    }
    if (_photos.length < 2) {
      _toast('请至少拍摄 2 张退货实物照片');
      return;
    }
    if (_sigKey.currentState?.isEmpty ?? true) {
      _toast('请完成客户签名');
      return;
    }
    setState(() => _submitting = true);
    try {
      // 导出签名并上传获得 URL
      String? signatureUrl;
      final signatureB64 = await _sigKey.currentState?.exportAsBase64Png();
      if (signatureB64 != null && signatureB64.isNotEmpty) {
        final sigBytes = base64Decode(signatureB64);
        final sigFile = File('${Directory.systemTemp.path}/sig_${DateTime.now().millisecondsSinceEpoch}.png');
        await sigFile.writeAsBytes(sigBytes);
        try {
          final sigUpResult = await ApiService.instance.uploadImage(sigFile, bizType: 'SIGNATURE');
          signatureUrl = sigUpResult['url'] as String?;
        } finally {
          if (await sigFile.exists()) await sigFile.delete();
        }
      }
      // 照片上传获得 URL
      final photoUrlList = <String>[];
      for (final p in _photos) {
        final upResult = await ApiService.instance.uploadImage(File(p.path), bizType: 'RETURN');
        photoUrlList.add(upResult['url'] as String);
      }

      final items = <Map<String, dynamic>>[];
      for (var i = 0; i < order.details.length; i++) {
        items.add({
          'detailId': order.details[i].detailId,
          'goodsCode': order.details[i].goodsCode,
          'signedQty': _signedQties[i],
        });
      }
      await ref.read(returnSignProvider(ReturnSignArgs(
        applyNo: order.applyNo,
        items: items,
        customerSigner: _signerCtrl.text.trim(),
        remark: _remarkCtrl.text.trim(),
        photos: photoUrlList,
        signature: signatureUrl,
      )).future);
      // 刷新今日任务
      ref.invalidate(todayTasksProvider);
      if (mounted) {
        _toast('回收成功，物流状态 → 司机已回收');
        Navigator.pop(context, true);
      }
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

/// 退货明细行：商品信息 + 实收数量录入。
class _ReturnItemRow extends StatelessWidget {
  final ReturnItem item;
  final num signedQty;
  final ValueChanged<num> onChanged;
  const _ReturnItemRow({required this.item, required this.signedQty, required this.onChanged});

  @override
  Widget build(BuildContext context) {
    final diff = signedQty - item.returnQty;
    return Container(
      padding: const EdgeInsets.symmetric(vertical: 8),
      decoration: const BoxDecoration(border: Border(bottom: BorderSide(color: Color(0xFFF0F1F4)))),
      child: Row(children: [
        Expanded(
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text(item.goodsName, style: const TextStyle(fontSize: 13, fontWeight: FontWeight.w600, color: TmsTheme.ink)),
            if (item.spec.isNotEmpty)
              Text('${item.spec} · ${item.unitName}', style: const TextStyle(fontSize: 11, color: TmsTheme.muted)),
            const SizedBox(height: 2),
            Text('应退 ${item.returnQty} 件', style: const TextStyle(fontSize: 11, color: TmsTheme.muted)),
          ]),
        ),
        SizedBox(
          width: 90,
          child: Column(crossAxisAlignment: CrossAxisAlignment.end, children: [
            const Text('实收', style: TextStyle(fontSize: 10, color: TmsTheme.muted)),
            TextField(
              keyboardType: const TextInputType.numberWithOptions(decimal: true),
              textAlign: TextAlign.center,
              controller: TextEditingController(text: signedQty.toString()),
              decoration: InputDecoration(
                isDense: true,
                contentPadding: const EdgeInsets.symmetric(horizontal: 8, vertical: 8),
                border: OutlineInputBorder(borderRadius: BorderRadius.circular(8), borderSide: const BorderSide(color: TmsTheme.rule, width: 1.5)),
                enabledBorder: OutlineInputBorder(borderRadius: BorderRadius.circular(8), borderSide: const BorderSide(color: TmsTheme.rule, width: 1.5)),
              ),
              onChanged: (v) {
                final n = num.tryParse(v) ?? 0;
                onChanged(n < 0 ? 0 : n);
              },
            ),
          ]),
        ),
        const SizedBox(width: 8),
        SizedBox(
          width: 44,
          child: Text(
            diff == 0 ? '一致' : '${diff > 0 ? "+" : ""}$diff',
            textAlign: TextAlign.center,
            style: TextStyle(fontSize: 11, fontWeight: FontWeight.w700, color: diff == 0 ? TmsTheme.ok : (diff < 0 ? TmsTheme.bad : TmsTheme.accent2)),
          ),
        ),
      ]),
    );
  }
}

/// 已拍照片缩略图（带删除按钮）。
class _PhotoTile extends StatelessWidget {
  final XFile photo;
  final int index;
  final VoidCallback onDelete;
  const _PhotoTile({required this.photo, required this.index, required this.onDelete});

  @override
  Widget build(BuildContext context) {
    return SizedBox(
      width: 80,
      height: 80,
      child: Stack(
        children: [
          ClipRRect(
            borderRadius: BorderRadius.circular(10),
            child: Image.file(File(photo.path), width: 80, height: 80, fit: BoxFit.cover),
          ),
          Positioned(
            top: 2,
            right: 2,
            child: GestureDetector(
              onTap: onDelete,
              child: Container(
                padding: const EdgeInsets.all(2),
                decoration: const BoxDecoration(color: Color(0xCC000000), shape: BoxShape.circle),
                child: const Icon(Icons.close, size: 12, color: Colors.white),
              ),
            ),
          ),
          Positioned(
            bottom: 2,
            left: 2,
            child: Container(
              padding: const EdgeInsets.symmetric(horizontal: 4, vertical: 1),
              decoration: BoxDecoration(color: const Color(0x88000000), borderRadius: BorderRadius.circular(4)),
              child: Text('$index', style: const TextStyle(fontSize: 9, color: Colors.white, fontWeight: FontWeight.w600)),
            ),
          ),
        ],
      ),
    );
  }
}

/// 拍照添加按钮。
class _AddPhotoTile extends StatelessWidget {
  final VoidCallback onTap;
  const _AddPhotoTile({required this.onTap});

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      onTap: onTap,
      child: Container(
        width: 80,
        height: 80,
        decoration: BoxDecoration(
          color: const Color(0xFFF9FAFB),
          borderRadius: BorderRadius.circular(10),
          border: Border.all(color: TmsTheme.rule, width: 1.5, style: BorderStyle.solid),
        ),
        child: const Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Icon(Icons.camera_alt_outlined, size: 22, color: TmsTheme.muted),
            SizedBox(height: 2),
            Text('拍照', style: TextStyle(fontSize: 10, color: TmsTheme.muted)),
          ],
        ),
      ),
    );
  }
}

class _Field extends StatelessWidget {
  final String label;
  final TextEditingController ctrl;
  final String placeholder;
  const _Field(this.label, this.ctrl, {this.placeholder = ''});
  @override
  Widget build(BuildContext context) {
    return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      Text(label, style: const TextStyle(fontSize: 12, color: TmsTheme.muted, fontWeight: FontWeight.w600)),
      const SizedBox(height: 4),
      TextField(
        controller: ctrl,
        decoration: InputDecoration(
          hintText: placeholder,
          filled: true,
          fillColor: Colors.white,
          contentPadding: const EdgeInsets.symmetric(horizontal: 12, vertical: 12),
          border: OutlineInputBorder(borderRadius: BorderRadius.circular(8), borderSide: const BorderSide(color: TmsTheme.rule, width: 1.5)),
          enabledBorder: OutlineInputBorder(borderRadius: BorderRadius.circular(8), borderSide: const BorderSide(color: TmsTheme.rule, width: 1.5)),
          focusedBorder: OutlineInputBorder(borderRadius: BorderRadius.circular(8), borderSide: const BorderSide(color: TmsTheme.accent, width: 1.5)),
        ),
      ),
    ]);
  }
}
