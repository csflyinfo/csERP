import 'dart:io';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:image_picker/image_picker.dart';
import '../../config/app_config.dart';
import '../../config/theme.dart';
import '../../models/reschedule_reject.dart';
import '../../providers/reschedule_reject_provider.dart';
import '../../providers/task_provider.dart';
import '../../services/api_service.dart';
import '../../widgets/common.dart';

/// 改派返仓创建页面（P3-2）。
///
/// 触发场景：客户不在 / 地址错误 / 联系不上 / 客户要求改期 → 货物随车返仓 → 仓库验收 → 回调度池重新派送
/// 核心原则：不反审核出库单，不生成入库单，库存不变
///
/// 流程：
///   1. 展示当前发货单信息（来源：调度明细）
///   2. 选择改派原因
///   3. 选择期望改送日期（默认次日）
///   4. 录入明细（默认从发货单拉取全部 SKU 及数量）
///   5. 拍现场照片（至少 1 张，留证用）
///   6. 「确认提交」→ /tms/app/reschedule-return/create + upload-photo
class RescheduleReturnPage extends ConsumerStatefulWidget {
  final String dispatchId;
  final String detailId;
  final String receiptNo;
  final String customerName;
  final String customerAddress;
  final num totalQty;

  const RescheduleReturnPage({
    super.key,
    required this.dispatchId,
    required this.detailId,
    required this.receiptNo,
    this.customerName = '',
    this.customerAddress = '',
    this.totalQty = 0,
  });

  @override
  ConsumerState<RescheduleReturnPage> createState() => _RescheduleReturnPageState();
}

class _RescheduleReturnPageState extends ConsumerState<RescheduleReturnPage> {
  final _remarkCtrl = TextEditingController();
  final _reasonDetailCtrl = TextEditingController();
  final List<XFile> _photos = [];
  String _reason = RescheduleReason.customerAbsent;
  DateTime _rescheduleDate = DateTime.now().add(const Duration(days: 1));
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
      appBar: AppBar(title: const Text('改派返仓')),
      body: ListView(
        padding: const EdgeInsets.all(14),
        children: [
          Alert.warn('🔄 货物随车返仓，仓库验收后回调度池重新派送。不反审核出库单、不动库存。'),
          const SizedBox(height: 8),
          // 发货单信息
          MCard(
            leftBar: TmsTheme.accent2,
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
          // 改派原因
          MCard(
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              const Text('改派原因', style: TextStyle(fontSize: 14, fontWeight: FontWeight.w700, color: TmsTheme.ink)),
              const SizedBox(height: 8),
              Wrap(spacing: 6, runSpacing: 6, children: RescheduleReason.labels.keys.map((r) {
                final on = _reason == r;
                return GestureDetector(
                  onTap: () => setState(() => _reason = r),
                  child: Container(
                    padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
                    decoration: BoxDecoration(
                      color: on ? TmsTheme.accent2 : Colors.white,
                      borderRadius: BorderRadius.circular(8),
                      border: Border.all(color: on ? TmsTheme.accent2 : TmsTheme.rule, width: 1.5),
                    ),
                    child: Text(RescheduleReason.label(r),
                        style: TextStyle(fontSize: 12, color: on ? Colors.white : TmsTheme.muted, fontWeight: FontWeight.w600)),
                  ),
                );
              }).toList()),
              const SizedBox(height: 8),
              TextField(
                controller: _reasonDetailCtrl,
                maxLines: 2,
                decoration: InputDecoration(
                  hintText: '可选，描述具体情况（如：客户出差 3 天后回来）',
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
          // 期望改送日期
          MCard(
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              const Text('期望改送日期', style: TextStyle(fontSize: 14, fontWeight: FontWeight.w700, color: TmsTheme.ink)),
              const SizedBox(height: 8),
              GestureDetector(
                onTap: _pickDate,
                child: Container(
                  padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 12),
                  decoration: BoxDecoration(
                    color: Colors.white,
                    borderRadius: BorderRadius.circular(8),
                    border: Border.all(color: TmsTheme.rule, width: 1.5),
                  ),
                  child: Row(children: [
                    const Icon(Icons.calendar_today, size: 16, color: TmsTheme.accent),
                    const SizedBox(width: 8),
                    Text(_fmtDate(_rescheduleDate), style: const TextStyle(fontSize: 13, color: TmsTheme.ink, fontWeight: FontWeight.w600)),
                    const Spacer(),
                    const Text('次日默认', style: TextStyle(fontSize: 11, color: TmsTheme.muted)),
                  ]),
                ),
              ),
            ]),
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
            Expanded(child: TmsButton.warn(_submitting ? '提交中...' : '确认改派返仓', onPressed: _submitting ? null : _submit)),
          ]),
          const SizedBox(height: 20),
        ],
      ),
    );
  }

  Future<void> _pickDate() async {
    final picked = await showDatePicker(
      context: context,
      initialDate: _rescheduleDate,
      firstDate: DateTime.now().add(const Duration(days: 1)),
      lastDate: DateTime.now().add(const Duration(days: 30)),
    );
    if (picked != null) setState(() => _rescheduleDate = picked);
  }

  Future<void> _pickPhoto() async {
    final picker = ImagePicker();
    final photo = await picker.pickImage(
      source: ImageSource.camera,
      maxWidth: AppConfig.photoMaxEdge.toDouble(),
      maxHeight: AppConfig.photoMaxEdge.toDouble(),
      imageQuality: AppConfig.photoQuality,
    );
    if (photo != null) setState(() => _photos.add(photo));
  }

  Future<void> _submit() async {
    if (_photos.isEmpty) {
      _toast('请至少拍摄 1 张现场照片留证');
      return;
    }
    setState(() => _submitting = true);
    try {
      final photoUrlList = await ApiService.instance.uploadImagesOrDefer(
        _photos.map((p) => File(p.path)).toList(),
        bizType: 'RESCHEDULE',
      );
      final result = await ref.read(createRescheduleReturnProvider(CreateRescheduleReturnArgs(
        dispatchId: widget.dispatchId,
        detailId: widget.detailId,
        receiptNo: widget.receiptNo,
        reason: _reason,
        reasonDetail: _reasonDetailCtrl.text.trim(),
        rescheduleDate: _fmtDate(_rescheduleDate),
        remark: _remarkCtrl.text.trim(),
        items: const [], // 后端未传 items 时默认从发货单拉取全部 SKU
        photos: photoUrlList,
      )).future);
      final returnNo = result['returnNo']?.toString() ?? '';
      final count = result['rescheduleCount'] ?? 1;
      // 离线入队时后端还没生成单号，若照常提示会显示「生成成功：（第 1 次改派）」，
      // 空单号加成功语会让司机以为单据已在系统里，回公司找不到又要重录一遍。
      if (result['_offline'] == true) {
        _toast('当前无网络，改派返仓已暂存本地，联网后自动上传');
      } else {
        _toast('改派返仓单生成成功：$returnNo（第 $count 次改派）');
      }
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

  String _fmtDate(DateTime d) => '${d.year}-${d.month.toString().padLeft(2, '0')}-${d.day.toString().padLeft(2, '0')}';
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
