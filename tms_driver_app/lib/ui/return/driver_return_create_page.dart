import 'dart:convert';
import 'dart:io';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:image_picker/image_picker.dart';
import '../../config/theme.dart';
import '../../models/driver_return.dart';
import '../../providers/driver_return_provider.dart';
import '../../providers/task_provider.dart';
import '../../services/api_service.dart';
import '../../widgets/common.dart';

/// 司机现场退货创建页面（对齐原型 Screen I）。
///
/// 流程：
///   1. 选择客户（从今日配送任务的客户列表中选，或手动输入）
///   2. 选择仓库（默认司机所属仓库）
///   3. 搜索并添加退货商品，录入数量/价格/批次
///   4. 选择退货原因（破损/临期/错发/滞销/其他）
///   5. 拍现场照片（至少 1 张）
///   6. 「确认提交」→ /tms/app/return/create + upload-photo
class DriverReturnCreatePage extends ConsumerStatefulWidget {
  final String? customerCode;
  final String? customerName;
  final String? dispatchId;
  final String? tripId;
  final String? warehouse;

  const DriverReturnCreatePage({
    super.key,
    this.customerCode,
    this.customerName,
    this.dispatchId,
    this.tripId,
    this.warehouse,
  });

  @override
  ConsumerState<DriverReturnCreatePage> createState() => _DriverReturnCreatePageState();
}

class _DriverReturnCreatePageState extends ConsumerState<DriverReturnCreatePage> {
  final _customerCtrl = TextEditingController();
  final _customerCodeCtrl = TextEditingController();
  final _warehouseCtrl = TextEditingController();
  final _remarkCtrl = TextEditingController();
  final _searchCtrl = TextEditingController();
  final List<ReturnGoodsItem> _items = [];
  final List<XFile> _photos = [];
  String _returnReason = '破损';
  bool _submitting = false;

  @override
  void initState() {
    super.initState();
    if (widget.customerName != null) _customerCtrl.text = widget.customerName!;
    if (widget.customerCode != null) _customerCodeCtrl.text = widget.customerCode!;
    if (widget.warehouse != null) _warehouseCtrl.text = widget.warehouse!;
  }

  @override
  void dispose() {
    _customerCtrl.dispose();
    _customerCodeCtrl.dispose();
    _warehouseCtrl.dispose();
    _remarkCtrl.dispose();
    _searchCtrl.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: TmsTheme.bg,
      appBar: AppBar(title: const Text('现场退货')),
      body: ListView(
        padding: const EdgeInsets.all(14),
        children: [
          const Alert.info('🔄 现场退货回收 · 随车返仓后仓库验收入账'),
          const SizedBox(height: 8),
          // 客户信息卡
          MCard(
            leftBar: TmsTheme.accent2,
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              const Text('客户信息', style: TextStyle(fontSize: 14, fontWeight: FontWeight.w700, color: TmsTheme.ink)),
              const SizedBox(height: 8),
              _Field('客户名称', _customerCtrl, placeholder: '请输入客户名称'),
              const SizedBox(height: 8),
              _Field('客户编码', _customerCodeCtrl, placeholder: '请输入客户编码'),
              const SizedBox(height: 8),
              _Field('收货仓库', _warehouseCtrl, placeholder: '退货入哪个仓库'),
            ]),
          ),
          const SizedBox(height: 8),
          // 退货原因
          MCard(
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              const Text('退货原因', style: TextStyle(fontSize: 14, fontWeight: FontWeight.w700, color: TmsTheme.ink)),
              const SizedBox(height: 8),
              Wrap(spacing: 6, runSpacing: 6, children: ['破损', '临期', '错发', '滞销', '质量问题', '其他'].map((r) {
                final on = _returnReason == r;
                return GestureDetector(
                  onTap: () => setState(() => _returnReason = r),
                  child: Container(
                    padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
                    decoration: BoxDecoration(
                      color: on ? TmsTheme.accent2 : Colors.white,
                      borderRadius: BorderRadius.circular(8),
                      border: Border.all(color: on ? TmsTheme.accent2 : TmsTheme.rule, width: 1.5),
                    ),
                    child: Text(r, style: TextStyle(fontSize: 12, color: on ? Colors.white : TmsTheme.muted, fontWeight: FontWeight.w600)),
                  ),
                );
              }).toList()),
            ]),
          ),
          const SizedBox(height: 8),
          // 商品搜索 + 添加
          MCard(
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              const Text('退货商品', style: TextStyle(fontSize: 14, fontWeight: FontWeight.w700, color: TmsTheme.ink)),
              const SizedBox(height: 6),
              Row(children: [
                Expanded(child: _Field('搜索商品（编码/名称/条码）', _searchCtrl, placeholder: '输入关键字搜索', onChanged: (_) => setState(() {}))),
                const SizedBox(width: 6),
                GestureDetector(
                  onTap: _searchCtrl.text.isNotEmpty ? () => ref.invalidate(goodsSearchProvider(_searchCtrl.text.trim())) : null,
                  child: Container(
                    padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 12),
                    decoration: BoxDecoration(color: TmsTheme.accent2, borderRadius: BorderRadius.circular(8)),
                    child: const Text('搜索', style: TextStyle(fontSize: 12, color: Colors.white, fontWeight: FontWeight.w700)),
                  ),
                ),
              ]),
              if (_searchCtrl.text.isNotEmpty)
                _GoodsSearchPanel(keyword: _searchCtrl.text.trim(), onPick: _pickGoods),
              const SizedBox(height: 8),
              ..._items.asMap().entries.map((e) => _ReturnItemRow(
                    item: e.value,
                    index: e.key + 1,
                    onChanged: () => setState(() {}),
                    onRemove: () => setState(() => _items.removeAt(e.key)),
                  )),
              if (_items.isEmpty)
                const Padding(padding: EdgeInsets.symmetric(vertical: 12), child: Center(child: Text('暂无退货商品，请搜索添加', style: TextStyle(fontSize: 12, color: TmsTheme.muted)))),
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
          _Field('备注说明', _remarkCtrl, placeholder: '可选，如：退货详细情况'),
          const SizedBox(height: 8),
          // 合计
          if (_items.isNotEmpty)
            Container(
              padding: const EdgeInsets.all(10),
              decoration: BoxDecoration(color: const Color(0xFFFCE7D6), borderRadius: BorderRadius.circular(8)),
              child: Row(mainAxisAlignment: MainAxisAlignment.spaceBetween, children: [
                Text('合计：${_items.length} 个商品', style: const TextStyle(fontSize: 12, color: TmsTheme.ink, fontWeight: FontWeight.w600)),
                Text('${_items.fold<num>(0, (s, it) => s + it.qty)} 件', style: const TextStyle(fontSize: 12, color: TmsTheme.accent2, fontWeight: FontWeight.w700)),
                Text('¥ ${_items.fold<num>(0, (s, it) => s + it.amount).toStringAsFixed(2)}', style: const TextStyle(fontSize: 12, color: TmsTheme.accent2, fontWeight: FontWeight.w700)),
              ]),
            ),
          const SizedBox(height: 16),
          Row(children: [
            Expanded(child: TmsButton.outline('取消', color: TmsTheme.muted, onPressed: () => Navigator.pop(context))),
            const SizedBox(width: 8),
            Expanded(child: TmsButton.primary(_submitting ? '提交中...' : '确认提交', onPressed: _submitting ? null : _submit)),
          ]),
          const SizedBox(height: 20),
        ],
      ),
    );
  }

  void _pickGoods(GoodsSearchResult g) {
    // 检查是否已添加
    final existing = _items.indexWhere((it) => it.goodsCode == g.goodsCode);
    if (existing >= 0) {
      setState(() => _items[existing].qty += 1);
    } else {
      setState(() {
        _items.add(ReturnGoodsItem(
          goodsCode: g.goodsCode,
          goodsName: g.goodsName,
          spec: g.spec,
          unitName: g.unitName,
          qty: 1,
          price: g.price,
        ));
      });
    }
    _searchCtrl.clear();
    setState(() {});
  }

  Future<void> _pickPhoto() async {
    final picker = ImagePicker();
    final photo = await picker.pickImage(source: ImageSource.camera, imageQuality: 70);
    if (photo != null) setState(() => _photos.add(photo));
  }

  Future<void> _submit() async {
    if (_customerCtrl.text.trim().isEmpty || _customerCodeCtrl.text.trim().isEmpty) {
      _toast('请填写客户信息');
      return;
    }
    if (_warehouseCtrl.text.trim().isEmpty) {
      _toast('请填写收货仓库');
      return;
    }
    if (_items.isEmpty) {
      _toast('请添加至少一个退货商品');
      return;
    }
    if (_photos.isEmpty) {
      _toast('请至少拍摄 1 张现场照片');
      return;
    }
    setState(() => _submitting = true);
    try {
      final photoUrlList = <String>[];
      for (final p in _photos) {
        final upResult = await ApiService.instance.uploadImage(File(p.path), bizType: 'RETURN');
        photoUrlList.add(upResult['url'] as String);
      }
      final result = await ref.read(createReturnProvider(CreateReturnArgs(
        customerCode: _customerCodeCtrl.text.trim(),
        customerName: _customerCtrl.text.trim(),
        warehouse: _warehouseCtrl.text.trim(),
        returnReason: _returnReason,
        remark: _remarkCtrl.text.trim(),
        dispatchId: widget.dispatchId,
        tripId: widget.tripId,
        items: _items,
        photos: photoUrlList,
      )).future);
      final applyNo = result['applyNo']?.toString() ?? '';
      _toast('退货创建成功：$applyNo，物流状态=司机已回收');
      ref.invalidate(returnTaskListProvider);
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

/// 商品搜索结果面板。
class _GoodsSearchPanel extends ConsumerWidget {
  final String keyword;
  final void Function(GoodsSearchResult) onPick;
  const _GoodsSearchPanel({required this.keyword, required this.onPick});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final async = ref.watch(goodsSearchProvider(keyword));
    return async.when(
      data: (list) {
        if (list.isEmpty) {
          return const Padding(padding: EdgeInsets.symmetric(vertical: 8), child: Text('无匹配商品', style: TextStyle(fontSize: 11, color: TmsTheme.muted)));
        }
        return Container(
          margin: const EdgeInsets.only(top: 4),
          constraints: const BoxConstraints(maxHeight: 200),
          decoration: BoxDecoration(color: Colors.white, borderRadius: BorderRadius.circular(8), border: Border.all(color: TmsTheme.rule)),
          child: ListView.builder(
            shrinkWrap: true,
            itemCount: list.length,
            itemBuilder: (_, i) {
              final g = list[i];
              return InkWell(
                onTap: () => onPick(g),
                child: Container(
                  padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 8),
                  decoration: const BoxDecoration(border: Border(bottom: BorderSide(color: Color(0xFFF0F1F4)))),
                  child: Row(children: [
                    Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                      Text(g.goodsName, style: const TextStyle(fontSize: 12, fontWeight: FontWeight.w600, color: TmsTheme.ink)),
                      Text('${g.goodsCode} · ${g.spec} · ${g.unitName}', style: const TextStyle(fontSize: 10, color: TmsTheme.muted)),
                    ])),
                    Text('¥${g.price}', style: const TextStyle(fontSize: 11, color: TmsTheme.accent2, fontWeight: FontWeight.w700)),
                    const SizedBox(width: 4),
                    const Icon(Icons.add_circle, size: 16, color: TmsTheme.accent2),
                  ]),
                ),
              );
            },
          ),
        );
      },
      loading: () => const Padding(padding: EdgeInsets.symmetric(vertical: 8), child: Center(child: SizedBox(width: 14, height: 14, child: CircularProgressIndicator(strokeWidth: 2)))),
      error: (e, _) => Padding(padding: const EdgeInsets.symmetric(vertical: 8), child: Text('搜索失败：$e', style: const TextStyle(fontSize: 11, color: TmsTheme.bad))),
    );
  }
}

/// 退货商品行。
class _ReturnItemRow extends StatelessWidget {
  final ReturnGoodsItem item;
  final int index;
  final VoidCallback onChanged;
  final VoidCallback onRemove;
  const _ReturnItemRow({required this.item, required this.index, required this.onChanged, required this.onRemove});

  @override
  Widget build(BuildContext context) {
    return Container(
      margin: const EdgeInsets.only(bottom: 6),
      padding: const EdgeInsets.all(8),
      decoration: BoxDecoration(color: const Color(0xFFF9FAFB), borderRadius: BorderRadius.circular(8), border: Border.all(color: TmsTheme.rule)),
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Row(children: [
          Expanded(child: Text('$index. ${item.goodsName}', style: const TextStyle(fontSize: 12, fontWeight: FontWeight.w700, color: TmsTheme.ink))),
          GestureDetector(onTap: onRemove, child: const Icon(Icons.close, size: 14, color: TmsTheme.bad)),
        ]),
        const SizedBox(height: 2),
        Text('${item.goodsCode} · ${item.spec} · ${item.unitName}', style: const TextStyle(fontSize: 10, color: TmsTheme.muted)),
        const SizedBox(height: 6),
        Row(children: [
          const Text('数量', style: TextStyle(fontSize: 11, color: TmsTheme.muted)),
          const SizedBox(width: 4),
          SizedBox(
            width: 60,
            child: TextField(
              keyboardType: const TextInputType.numberWithOptions(decimal: true),
              textAlign: TextAlign.center,
              controller: TextEditingController(text: item.qty.toString()),
              decoration: InputDecoration(
                isDense: true,
                contentPadding: const EdgeInsets.symmetric(horizontal: 4, vertical: 6),
                border: OutlineInputBorder(borderRadius: BorderRadius.circular(6), borderSide: const BorderSide(color: TmsTheme.rule, width: 1.5)),
                enabledBorder: OutlineInputBorder(borderRadius: BorderRadius.circular(6), borderSide: const BorderSide(color: TmsTheme.rule, width: 1.5)),
              ),
              onChanged: (v) {
                item.qty = num.tryParse(v) ?? 0;
                onChanged();
              },
            ),
          ),
          const SizedBox(width: 8),
          const Text('单价', style: TextStyle(fontSize: 11, color: TmsTheme.muted)),
          const SizedBox(width: 4),
          SizedBox(
            width: 70,
            child: TextField(
              keyboardType: const TextInputType.numberWithOptions(decimal: true),
              textAlign: TextAlign.center,
              controller: TextEditingController(text: item.price.toString()),
              decoration: InputDecoration(
                isDense: true,
                contentPadding: const EdgeInsets.symmetric(horizontal: 4, vertical: 6),
                border: OutlineInputBorder(borderRadius: BorderRadius.circular(6), borderSide: const BorderSide(color: TmsTheme.rule, width: 1.5)),
                enabledBorder: OutlineInputBorder(borderRadius: BorderRadius.circular(6), borderSide: const BorderSide(color: TmsTheme.rule, width: 1.5)),
              ),
              onChanged: (v) {
                item.price = num.tryParse(v) ?? 0;
                onChanged();
              },
            ),
          ),
          const SizedBox(width: 8),
          Expanded(child: Text('小计 ¥${item.amount.toStringAsFixed(2)}', style: const TextStyle(fontSize: 11, color: TmsTheme.accent2, fontWeight: FontWeight.w700), textAlign: TextAlign.right)),
        ]),
      ]),
    );
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

class _Field extends StatelessWidget {
  final String label;
  final TextEditingController ctrl;
  final String placeholder;
  final ValueChanged<String>? onChanged;
  const _Field(this.label, this.ctrl, {this.placeholder = '', this.onChanged});
  @override
  Widget build(BuildContext context) {
    return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      Text(label, style: const TextStyle(fontSize: 12, color: TmsTheme.muted, fontWeight: FontWeight.w600)),
      const SizedBox(height: 4),
      TextField(
        controller: ctrl,
        onChanged: onChanged,
        decoration: InputDecoration(
          hintText: placeholder,
          filled: true,
          fillColor: Colors.white,
          contentPadding: const EdgeInsets.symmetric(horizontal: 12, vertical: 12),
          border: OutlineInputBorder(borderRadius: BorderRadius.circular(8), borderSide: const BorderSide(color: TmsTheme.rule, width: 1.5)),
          enabledBorder: OutlineInputBorder(borderRadius: BorderRadius.circular(8), borderSide: const BorderSide(color: TmsTheme.rule, width: 1.5)),
          focusedBorder: OutlineInputBorder(borderRadius: BorderRadius.circular(8), borderSide: const BorderSide(color: TmsTheme.accent2, width: 1.5)),
        ),
      ),
    ]);
  }
}
