import 'dart:convert';
import 'dart:io';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:image_picker/image_picker.dart';
import '../../config/theme.dart';
import '../../models/delivery.dart';
import '../../providers/delivery_provider.dart';
import '../../providers/task_provider.dart';
import '../../services/api_service.dart';
import '../../widgets/common.dart';
import '../store/store_location_page.dart';

/// 配送签收页面（对齐原型 Screen G）。
///
/// 流程：
///   1. 进入页面拉取签收 SKU 明细（按 detailId）
///   2. 逐商品录入实收数量（默认全收，可改小）
///   3. 录入收款金额（COD 货到付款场景）
///   4. 拍现场照片（至少 1 张）
///   5. 客户签名
///   6. 「确认签收」提交 → /tms/app/sign + /tms/app/sign/upload-photo
class DeliverySignPage extends ConsumerStatefulWidget {
  final String detailId;
  const DeliverySignPage({super.key, required this.detailId});

  @override
  ConsumerState<DeliverySignPage> createState() => _DeliverySignPageState();
}

class _DeliverySignPageState extends ConsumerState<DeliverySignPage> {
  final _signerCtrl = TextEditingController();
  final _remarkCtrl = TextEditingController();
  final _collectCtrl = TextEditingController();
  final _sigKey = GlobalKey<SignaturePadState>();
  final List<SignItem> _items = [];
  final List<XFile> _photos = [];
  String _payMethod = '现金';
  bool _submitting = false;

  @override
  void dispose() {
    _signerCtrl.dispose();
    _remarkCtrl.dispose();
    _collectCtrl.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final async = ref.watch(signItemsProvider(widget.detailId));
    return Scaffold(
      backgroundColor: TmsTheme.bg,
      appBar: AppBar(title: const Text('配送签收')),
      body: async.when(
        data: (d) {
          if (_items.length != d.items.length) {
            _items.clear();
            for (final it in d.items) {
              _items.add(SignItem(
                goodsCode: it.goodsCode,
                goodsName: it.goodsName,
                unitName: it.unitName,
                requiredQty: it.requiredQty,
                signedQty: it.requiredQty, // 默认全收
                rejectQty: 0,
              ));
            }
            // 默认收款金额=应收金额
            if (_collectCtrl.text.isEmpty && d.amount > 0) {
              _collectCtrl.text = d.amount.toString();
            }
          }
          return _buildBody(d);
        },
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

  Widget _buildBody(SignDetail d) {
    final totalRequired = _items.fold<num>(0, (s, it) => s + it.requiredQty);
    final totalSigned = _items.fold<num>(0, (s, it) => s + it.signedQty);
    final totalReject = _items.fold<num>(0, (s, it) => s + it.rejectQty);
    final diff = totalSigned - totalRequired;

    return ListView(
      padding: const EdgeInsets.all(14),
      children: [
        Alert.info('📦 配送签收 · 发货单 ${d.sourceBillNo}'),
        const SizedBox(height: 8),
        // 发货单信息卡
        MCard(
          leftBar: TmsTheme.accent,
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Row(mainAxisAlignment: MainAxisAlignment.spaceBetween, children: [
              Expanded(child: Text('发货单：${d.sourceBillNo}', style: const TextStyle(fontSize: 14, fontWeight: FontWeight.w700, color: TmsTheme.ink))),
              const MTag.blue('待签收'),
            ]),
            const SizedBox(height: 4),
            MLine('客户', d.customerName),
            MLine('地址', d.customerAddress),
            MLine('应收金额', '¥ ${d.amount.toStringAsFixed(2)}', valueColor: TmsTheme.accent2),
            const SizedBox(height: 6),
            Align(
              alignment: Alignment.centerRight,
              child: TextButton.icon(
                onPressed: () {
                  Navigator.push(context, MaterialPageRoute(
                    builder: (_) => StoreLocationPage(
                      customerCode: d.customerCode,
                      customerName: d.customerName,
                      dispatchId: d.dispatchId,
                    ),
                  ));
                },
                icon: const Icon(Icons.location_on, size: 14),
                label: const Text('修改定位', style: TextStyle(fontSize: 12)),
              ),
            ),
          ]),
        ),
        const SizedBox(height: 8),
        // 商品清单 · 录入实收数量
        MCard(
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            const Text('📦 商品清单 · 录入实收数量', style: TextStyle(fontSize: 14, fontWeight: FontWeight.w700, color: TmsTheme.ink)),
            const Text('（实收可小于应发，差异自动标记部分签收）', style: TextStyle(fontSize: 11, color: TmsTheme.muted)),
            const SizedBox(height: 8),
            ..._items.asMap().entries.map((e) {
              final i = e.key;
              final it = e.value;
              return _SignItemRow(
                item: it,
                onChanged: (signed, reject) => setState(() {
                  _items[i].signedQty = signed;
                  _items[i].rejectQty = reject;
                  _updateCollectAmount(d);
                }),
              );
            }),
            const SizedBox(height: 8),
            Container(
              padding: const EdgeInsets.all(10),
              decoration: BoxDecoration(color: TmsTheme.bg, borderRadius: BorderRadius.circular(8)),
              child: Row(mainAxisAlignment: MainAxisAlignment.spaceBetween, children: [
                Text('合计：应发 $totalRequired 件', style: const TextStyle(fontSize: 12, color: TmsTheme.muted)),
                Text('实收 $totalSigned 件', style: const TextStyle(fontSize: 12, color: TmsTheme.ok, fontWeight: FontWeight.w700)),
                Text('拒收 $totalReject 件', style: const TextStyle(fontSize: 12, color: TmsTheme.bad, fontWeight: FontWeight.w700)),
              ]),
            ),
            const SizedBox(height: 4),
            if (diff == 0)
              const Alert.ok('✅ 全部一致，可直接签收')
            else if (diff < 0)
              Alert.warn('⚠️ 实收少于应发 $diff 件，将标记为部分签收')
            else
              Alert.warn('⚠️ 实收多于应发 $diff 件，请核对'),
          ]),
        ),
        const SizedBox(height: 8),
        // 收款信息（COD）
        MCard(
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            const Text('💰 货到付款（COD）', style: TextStyle(fontSize: 14, fontWeight: FontWeight.w700, color: TmsTheme.ink)),
            const SizedBox(height: 8),
            _Field('收款金额（¥）', _collectCtrl, placeholder: '0 表示无收款'),
            const SizedBox(height: 8),
            const Text('收款方式', style: TextStyle(fontSize: 12, color: TmsTheme.muted, fontWeight: FontWeight.w600)),
            const SizedBox(height: 4),
            Wrap(spacing: 6, children: ['现金', '微信', '支付宝', '赊账'].map((m) {
              final on = _payMethod == m;
              return GestureDetector(
                onTap: () => setState(() => _payMethod = m),
                child: Container(
                  padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
                  decoration: BoxDecoration(
                    color: on ? TmsTheme.accent : Colors.white,
                    borderRadius: BorderRadius.circular(8),
                    border: Border.all(color: on ? TmsTheme.accent : TmsTheme.rule, width: 1.5),
                  ),
                  child: Text(m, style: TextStyle(fontSize: 12, color: on ? Colors.white : TmsTheme.muted, fontWeight: FontWeight.w600)),
                ),
              );
            }).toList()),
          ]),
        ),
        const SizedBox(height: 8),
        // 现场照片
        MCard(
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Row(children: [
              const Text('📸 现场照片', style: TextStyle(fontSize: 14, fontWeight: FontWeight.w700, color: TmsTheme.ink)),
              const SizedBox(width: 6),
              Text('（至少 1 张，已拍 ${_photos.length} 张）', style: const TextStyle(fontSize: 11, color: TmsTheme.muted)),
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
                if (_photos.length < 6) _AddPhotoTile(onTap: _pickPhoto),
              ],
            ),
          ]),
        ),
        const SizedBox(height: 8),
        // 客户签名
        MCard(
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            const Text('✍️ 客户确认签名（可选）', style: TextStyle(fontSize: 14, fontWeight: FontWeight.w700, color: TmsTheme.ink)),
            const SizedBox(height: 6),
            _Field('签收人姓名（可选）', _signerCtrl, placeholder: '请输入签收人姓名'),
            const SizedBox(height: 6),
            SignaturePad(key: _sigKey, height: 120, placeholder: '客户确认签收（可选）'),
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
          Expanded(child: TmsButton.primary(_submitting ? '提交中...' : '确认签收', onPressed: _submitting ? null : () => _submit(d))),
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

  /// 根据实收数量按比例更新收款金额。
  void _updateCollectAmount(SignDetail d) {
    final totalRequired = _items.fold<num>(0, (s, it) => s + it.requiredQty);
    if (totalRequired <= 0 || d.amount <= 0) return;
    final totalSigned = _items.fold<num>(0, (s, it) => s + it.signedQty);
    final collectAmount = d.amount * totalSigned / totalRequired;
    _collectCtrl.text = collectAmount.toStringAsFixed(2);
  }

  Future<void> _submit(SignDetail d) async {
    if (_photos.isEmpty) {
      _toast('请至少拍摄 1 张现场照片');
      return;
    }
    setState(() => _submitting = true);
    try {
      // 上传签名图（可选，有签名才上传）
      final signatureB64 = await _sigKey.currentState?.exportAsBase64Png();
      String? signatureUrl;
      if (signatureB64 != null && signatureB64.isNotEmpty) {
        final sigBytes = base64Decode(signatureB64);
        final tempDir = await Directory.systemTemp.createTemp('sig');
        final sigFile = File('${tempDir.path}/signature.png');
        await sigFile.writeAsBytes(sigBytes);
        final sigResult = await ApiService.instance.uploadImage(sigFile, bizType: 'SIGNATURE');
        signatureUrl = sigResult['url'] as String;
      }

      // 上传所有现场照片（XFile → File → 上传获得 URL）
      final photoUrls = <String>[];
      for (final p in _photos) {
        final upResult = await ApiService.instance.uploadImage(File(p.path), bizType: 'SIGN');
        photoUrls.add(upResult['url'] as String);
      }

      final items = <Map<String, dynamic>>[];
      for (final it in _items) {
        items.add({
          'goodsCode': it.goodsCode,
          'signedQty': it.signedQty,
          'rejectQty': it.rejectQty,
        });
      }

      final result = await ref.read(signSubmitProvider(SignSubmitArgs(
        dispatchId: d.dispatchId,
        detailId: d.detailId,
        sourceBillNo: d.sourceBillNo,
        items: items,
        collectAmount: num.tryParse(_collectCtrl.text.trim()) ?? 0,
        payMethod: _payMethod,
        customerSigner: _signerCtrl.text.trim(),
        remark: _remarkCtrl.text.trim(),
        photoUrls: photoUrls,
        signatureUrl: signatureUrl,
      )).future);

      final signType = result['signType']?.toString() ?? '';
      final allCompleted = result['allCompleted'] == true;
      String msg = '签收成功：${_signTypeText(signType)}';
      if (allCompleted) msg += '，本调度单已全部签收完成';
      ref.invalidate(todayTasksProvider);
      ref.invalidate(signItemsProvider(widget.detailId));
      if (mounted) {
        _toast(msg);
        Navigator.pop(context, true);
      }
    } catch (e) {
      _toast('提交失败：${e.toString().replaceFirst("Exception: ", "")}');
    } finally {
      if (mounted) setState(() => _submitting = false);
    }
  }

  String _signTypeText(String t) {
    switch (t) {
      case 'NORMAL':
        return '正常签收';
      case 'PARTIAL':
        return '部分签收';
      case 'REJECT':
        return '全部拒收';
      default:
        return t;
    }
  }

  void _toast(String msg) {
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(msg), behavior: SnackBarBehavior.floating));
  }
}

/// 签收明细行：商品信息 + 实收/拒收数量录入。
class _SignItemRow extends StatelessWidget {
  final SignItem item;
  final void Function(num signed, num reject) onChanged;
  const _SignItemRow({required this.item, required this.onChanged});

  @override
  Widget build(BuildContext context) {
    final diff = item.signedQty - item.requiredQty;
    return Container(
      padding: const EdgeInsets.symmetric(vertical: 8),
      decoration: const BoxDecoration(border: Border(bottom: BorderSide(color: Color(0xFFF0F1F4)))),
      child: Row(children: [
        Expanded(
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text(item.goodsName, style: const TextStyle(fontSize: 13, fontWeight: FontWeight.w600, color: TmsTheme.ink)),
            if (item.unitName.isNotEmpty)
              Text(item.unitName, style: const TextStyle(fontSize: 11, color: TmsTheme.muted)),
            const SizedBox(height: 2),
            Text('应发 ${item.requiredQty} 件', style: const TextStyle(fontSize: 11, color: TmsTheme.muted)),
          ]),
        ),
        SizedBox(
          width: 70,
          child: Column(crossAxisAlignment: CrossAxisAlignment.end, children: [
            const Text('实收', style: TextStyle(fontSize: 10, color: TmsTheme.muted)),
            TextField(
              keyboardType: const TextInputType.numberWithOptions(decimal: true),
              textAlign: TextAlign.center,
              controller: TextEditingController(text: item.signedQty.toString()),
              decoration: InputDecoration(
                isDense: true,
                contentPadding: const EdgeInsets.symmetric(horizontal: 6, vertical: 8),
                border: OutlineInputBorder(borderRadius: BorderRadius.circular(8), borderSide: const BorderSide(color: TmsTheme.rule, width: 1.5)),
                enabledBorder: OutlineInputBorder(borderRadius: BorderRadius.circular(8), borderSide: const BorderSide(color: TmsTheme.rule, width: 1.5)),
              ),
              onChanged: (v) {
                final n = num.tryParse(v) ?? 0;
                final signed = n < 0 ? 0 : n;
                // 拒收 = max(0, 应发 - 实收)，实收可大于应发时拒收为 0
                final reject = (item.requiredQty - signed).clamp(0, item.requiredQty);
                onChanged(signed, reject);
              },
            ),
          ]),
        ),
        const SizedBox(width: 6),
        SizedBox(
          width: 70,
          child: Column(crossAxisAlignment: CrossAxisAlignment.end, children: [
            const Text('拒收', style: TextStyle(fontSize: 10, color: TmsTheme.muted)),
            TextField(
              keyboardType: const TextInputType.numberWithOptions(decimal: true),
              textAlign: TextAlign.center,
              controller: TextEditingController(text: item.rejectQty.toString()),
              decoration: InputDecoration(
                isDense: true,
                contentPadding: const EdgeInsets.symmetric(horizontal: 6, vertical: 8),
                border: OutlineInputBorder(borderRadius: BorderRadius.circular(8), borderSide: const BorderSide(color: TmsTheme.rule, width: 1.5)),
                enabledBorder: OutlineInputBorder(borderRadius: BorderRadius.circular(8), borderSide: const BorderSide(color: TmsTheme.rule, width: 1.5)),
              ),
              onChanged: (v) {
                final n = num.tryParse(v) ?? 0;
                final reject = (n < 0 ? 0 : n).clamp(0, item.requiredQty);
                // 实收 = 应发 - 拒收
                onChanged(item.requiredQty - reject, reject);
              },
            ),
          ]),
        ),
        const SizedBox(width: 6),
        SizedBox(
          width: 36,
          child: Text(
            diff == 0 ? '一致' : '${diff > 0 ? "+" : ""}$diff',
            textAlign: TextAlign.center,
            style: TextStyle(fontSize: 10, fontWeight: FontWeight.w700, color: diff == 0 ? TmsTheme.ok : (diff < 0 ? TmsTheme.bad : TmsTheme.accent2)),
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
        keyboardType: label.contains('金额') ? const TextInputType.numberWithOptions(decimal: true) : TextInputType.text,
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
