import 'dart:convert';
import 'dart:io';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:image_picker/image_picker.dart';
import '../../config/theme.dart';
import '../../models/reschedule_reject.dart';
import '../../providers/reschedule_reject_provider.dart';
import '../../providers/task_provider.dart';
import '../../services/api_service.dart';
import '../../widgets/common.dart';

/// 客户拒收单创建页面（P3-2）。
///
/// 触发场景：客户全部 / 部分拒收 → 货物随车返仓 → 仓库收货生成拒收入库单（JSRK）→ 审核后库存增加、撤销应收
/// 核心原则：仓库收货时把拒收数量回写到 sales_receipt_detail.reject_qty，复用现有 JSRK 流程
///
/// 流程：
///   1. 展示当前发货单信息（来源：调度明细）
///   2. 选择拒收原因
///   3. 默认全部拒收（拒收数量 = 配送数量），可逐行调整
///   4. 拍现场照片（至少 1 张，留证用）
///   5. 「确认提交」→ /tms/app/customer-reject/create + upload-photo
class CustomerRejectPage extends ConsumerStatefulWidget {
  final String dispatchId;
  final String detailId;
  final String receiptNo;
  final String customerName;
  final String customerAddress;
  final num totalQty;

  const CustomerRejectPage({
    super.key,
    required this.dispatchId,
    required this.detailId,
    required this.receiptNo,
    this.customerName = '',
    this.customerAddress = '',
    this.totalQty = 0,
  });

  @override
  ConsumerState<CustomerRejectPage> createState() => _CustomerRejectPageState();
}

class _CustomerRejectPageState extends ConsumerState<CustomerRejectPage> {
  final _remarkCtrl = TextEditingController();
  final _reasonDetailCtrl = TextEditingController();
  final List<XFile> _photos = [];
  String _rejectReason = CustomerRejectReason.customerReject;
  bool _allReject = true;
  bool _submitting = false;

  @override
  void dispose() {
    _remarkCtrl.dispose();
    _reasonDetailCtrl.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: TmsTheme.bg,
      appBar: AppBar(title: const Text('客户拒收单')),
      body: ListView(
        padding: const EdgeInsets.all(14),
        children: [
          Alert.danger('⚠️ 客户拒收 → 货物返仓 → 仓库收货后生成拒收入库单，审核后库存增加、撤销对应应收'),
          const SizedBox(height: 8),
          // 发货单信息
          MCard(
            leftBar: TmsTheme.bad,
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              const Text('发货单信息', style: TextStyle(fontSize: 14, fontWeight: FontWeight.w700, color: TmsTheme.ink)),
              const SizedBox(height: 8),
              MLine('发货单号', widget.receiptNo),
              MLine('客户名称', widget.customerName.isEmpty ? '-' : widget.customerName),
              MLine('客户地址', widget.customerAddress.isEmpty ? '-' : widget.customerAddress),
              MLine('配送数量', '${widget.totalQty} 件'),
            ]),
          ),
          const SizedBox(height: 8),
          // 拒收原因
          MCard(
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              const Text('拒收原因', style: TextStyle(fontSize: 14, fontWeight: FontWeight.w700, color: TmsTheme.ink)),
              const SizedBox(height: 8),
              Wrap(spacing: 6, runSpacing: 6, children: CustomerRejectReason.labels.keys.map((r) {
                final on = _rejectReason == r;
                return GestureDetector(
                  onTap: () => setState(() => _rejectReason = r),
                  child: Container(
                    padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
                    decoration: BoxDecoration(
                      color: on ? TmsTheme.bad : Colors.white,
                      borderRadius: BorderRadius.circular(8),
                      border: Border.all(color: on ? TmsTheme.bad : TmsTheme.rule, width: 1.5),
                    ),
                    child: Text(CustomerRejectReason.label(r),
                        style: TextStyle(fontSize: 12, color: on ? Colors.white : TmsTheme.muted, fontWeight: FontWeight.w600)),
                  ),
                );
              }).toList()),
              const SizedBox(height: 8),
              TextField(
                controller: _reasonDetailCtrl,
                maxLines: 2,
                decoration: InputDecoration(
                  hintText: '可选，描述具体拒收情况',
                  filled: true,
                  fillColor: Colors.white,
                  contentPadding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
                  border: OutlineInputBorder(borderRadius: BorderRadius.circular(8), borderSide: const BorderSide(color: TmsTheme.rule, width: 1.5)),
                  enabledBorder: OutlineInputBorder(borderRadius: BorderRadius.circular(8), borderSide: const BorderSide(color: TmsTheme.rule, width: 1.5)),
                ),
              ),
            ]),
          ),
          const SizedBox(height: 8),
          // 拒收范围
          MCard(
            child: Row(mainAxisAlignment: MainAxisAlignment.spaceBetween, children: [
              const Text('拒收范围', style: TextStyle(fontSize: 14, fontWeight: FontWeight.w700, color: TmsTheme.ink)),
              Row(children: [
                _radio('全部拒收', _allReject, () => setState(() => _allReject = true)),
                const SizedBox(width: 12),
                _radio('部分拒收', !_allReject, () => setState(() => _allReject = false)),
              ]),
            ]),
          ),
          if (!_allReject)
            const Padding(
              padding: EdgeInsets.symmetric(vertical: 4, horizontal: 4),
              child: Text('部分拒收请在「配送签收」页面录入每行实收/拒收数量后正常签收，本页面仅用于全拒收场景。',
                  style: TextStyle(fontSize: 11, color: TmsTheme.muted)),
            ),
          const SizedBox(height: 8),
          // 现场照片
          MCard(
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Row(children: [
                const Text('现场照片', style: TextStyle(fontSize: 14, fontWeight: FontWeight.w700, color: TmsTheme.ink)),
                const SizedBox(width: 6),
                Text('（至少 1 张，已拍 ${_photos.length} 张）', style: const TextStyle(fontSize: 11, color: TmsTheme.muted)),
              ]),
              const SizedBox(height: 8),
              Wrap(spacing: 8, runSpacing: 8, children: [
                ..._photos.asMap().entries.map((e) => _PhotoTile(photo: e.value, index: e.key + 1, onDelete: () => setState(() => _photos.removeAt(e.key)))),
                if (_photos.length < 6) _AddPhotoTile(onTap: _pickPhoto),
              ]),
            ]),
          ),
          const SizedBox(height: 8),
          TextField(
            controller: _remarkCtrl,
            maxLines: 2,
            decoration: InputDecoration(
              labelText: '备注',
              hintText: '可选',
              filled: true,
              fillColor: Colors.white,
              contentPadding: const EdgeInsets.symmetric(horizontal: 12, vertical: 12),
              border: OutlineInputBorder(borderRadius: BorderRadius.circular(8), borderSide: const BorderSide(color: TmsTheme.rule, width: 1.5)),
              enabledBorder: OutlineInputBorder(borderRadius: BorderRadius.circular(8), borderSide: const BorderSide(color: TmsTheme.rule, width: 1.5)),
            ),
          ),
          const SizedBox(height: 16),
          Row(children: [
            Expanded(child: TmsButton.outline('取消', color: TmsTheme.muted, onPressed: () => Navigator.pop(context))),
            const SizedBox(width: 8),
            Expanded(child: TmsButton.danger(_submitting ? '提交中...' : '确认客户拒收', onPressed: _submitting ? null : _submit)),
          ]),
          const SizedBox(height: 20),
        ],
      ),
    );
  }

  Widget _radio(String label, bool on, VoidCallback onTap) {
    return GestureDetector(
      onTap: onTap,
      child: Row(children: [
        Icon(on ? Icons.radio_button_checked : Icons.radio_button_unchecked, size: 16, color: on ? TmsTheme.bad : TmsTheme.muted),
        const SizedBox(width: 4),
        Text(label, style: TextStyle(fontSize: 12, color: on ? TmsTheme.ink : TmsTheme.muted, fontWeight: on ? FontWeight.w600 : FontWeight.normal)),
      ]),
    );
  }

  Future<void> _pickPhoto() async {
    final picker = ImagePicker();
    final photo = await picker.pickImage(source: ImageSource.camera, imageQuality: 70);
    if (photo != null) setState(() => _photos.add(photo));
  }

  Future<void> _submit() async {
    if (_photos.isEmpty) {
      _toast('请至少拍摄 1 张现场照片留证');
      return;
    }
    if (!_allReject) {
      _toast('部分拒收请前往「配送签收」页面操作');
      return;
    }
    setState(() => _submitting = true);
    try {
      final photoUrlList = <String>[];
      for (final p in _photos) {
        final upResult = await ApiService.instance.uploadImage(File(p.path), bizType: 'REJECT');
        photoUrlList.add(upResult['url'] as String);
      }
      final result = await ref.read(createCustomerRejectProvider(CreateCustomerRejectArgs(
        dispatchId: widget.dispatchId,
        detailId: widget.detailId,
        receiptNo: widget.receiptNo,
        rejectReason: _rejectReason,
        reasonDetail: _reasonDetailCtrl.text.trim(),
        remark: _remarkCtrl.text.trim(),
        items: const [], // 后端未传 items 时默认从发货单拉取全部 SKU 作为全拒收
        photos: photoUrlList,
      )).future);
      final rejectNo = result['rejectNo']?.toString() ?? '';
      final totalQty = result['totalQty'] ?? 0;
      _toast('客户拒收单生成成功：$rejectNo（共 $totalQty 件）');
      ref.invalidate(todayTasksProvider);
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

/// 已拍照片缩略图。
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
      child: Stack(children: [
        ClipRRect(borderRadius: BorderRadius.circular(10), child: Image.file(File(photo.path), width: 80, height: 80, fit: BoxFit.cover)),
        Positioned(top: 2, right: 2, child: GestureDetector(
          onTap: onDelete,
          child: Container(padding: const EdgeInsets.all(2), decoration: const BoxDecoration(color: Color(0xCC000000), shape: BoxShape.circle), child: const Icon(Icons.close, size: 12, color: Colors.white)),
        )),
        Positioned(bottom: 2, left: 2, child: Container(
          padding: const EdgeInsets.symmetric(horizontal: 4, vertical: 1),
          decoration: BoxDecoration(color: const Color(0x88000000), borderRadius: BorderRadius.circular(4)),
          child: Text('$index', style: const TextStyle(fontSize: 9, color: Colors.white, fontWeight: FontWeight.w600)),
        )),
      ]),
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
        decoration: BoxDecoration(color: const Color(0xFFF9FAFB), borderRadius: BorderRadius.circular(10), border: Border.all(color: TmsTheme.rule, width: 1.5)),
        child: const Column(mainAxisAlignment: MainAxisAlignment.center, children: [
          Icon(Icons.camera_alt_outlined, size: 22, color: TmsTheme.muted),
          SizedBox(height: 2),
          Text('拍照', style: TextStyle(fontSize: 10, color: TmsTheme.muted)),
        ]),
      ),
    );
  }
}
