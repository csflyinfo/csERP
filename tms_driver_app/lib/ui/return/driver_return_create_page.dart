import 'dart:async';
import 'dart:io';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:geolocator/geolocator.dart';
import 'package:image_picker/image_picker.dart';
import '../../config/driver_perms.dart';
import '../../services/auth_service.dart';
import '../../services/photo_service.dart';
import '../../config/theme.dart';
import '../../models/driver_return.dart';
import '../../providers/driver_return_provider.dart';
import '../../providers/task_provider.dart';
import '../../services/api_service.dart';
import '../../services/param_service.dart';
import '../../widgets/common.dart';

/// 司机现场退货创建页面（对齐原型 Screen I）。
///
/// 流程：
///   1. 下拉选择客户（默认展示距当前位置最近的 10 个，可输入关键字模糊查）
///   2. 下拉选择收货仓库（只列实物仓）
///   3. 扫条码 / 关键字搜索添加退货商品，录入数量/价格/批次
///   4. 选择退货原因（破损/临期/错发/滞销/其他）
///   5. 拍现场照片（张数由参数 TMS_RETURN_PHOTO_COUNT 控制）
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
  final _warehouseCtrl = TextEditingController();
  final _remarkCtrl = TextEditingController();
  final _searchCtrl = TextEditingController();

  /// 选中的客户编码：不再在界面上展示「客户编号」，但建单仍必须带编码。
  String _customerCode = '';

  /// 已落定的搜索关键字（输入防抖后才更新，避免每敲一个字打一次接口）。
  String _searchKeyword = '';
  Timer? _searchDebounce;

  final List<ReturnGoodsItem> _items = [];
  final List<XFile> _photos = [];
  String _returnReason = '破损';
  bool _submitting = false;

  /// 现场退货照片张数下限：与退货回收共用 TMS_RETURN_PHOTO_COUNT
  /// （PRD-26 §3.2 该参数的消费方就是「退货回收/现场退货页」），0 表示不校验。
  int get _requirePhoto => ParamService.instance.current.returnPhotoCount;

  /// 可拍上限保底 6 张，参数更高时以参数为准。
  int get _maxPhoto => _requirePhoto > 6 ? _requirePhoto : 6;

  @override
  void initState() {
    super.initState();
    if (widget.customerName != null) _customerCtrl.text = widget.customerName!;
    if (widget.customerCode != null) _customerCode = widget.customerCode!;
    if (widget.warehouse != null) _warehouseCtrl.text = widget.warehouse!;
  }

  @override
  void dispose() {
    _searchDebounce?.cancel();
    _customerCtrl.dispose();
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
              _PickerTile(
                label: '客户名称',
                value: _customerCtrl.text,
                placeholder: '点击选择客户（默认最近的 10 个）',
                icon: Icons.arrow_drop_down,
                onTap: _pickCustomer,
              ),
              const SizedBox(height: 8),
              _PickerTile(
                label: '收货仓库',
                value: _warehouseCtrl.text,
                placeholder: '点击选择退货入哪个实物仓',
                icon: Icons.arrow_drop_down,
                onTap: _pickWarehouse,
              ),
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
                Expanded(
                  child: _SearchField(
                    ctrl: _searchCtrl,
                    placeholder: '扫码 / 输入名称·编码·条码',
                    onChanged: _onSearchChanged,
                    // PDA 扫码枪以键盘楔入方式把条码打进焦点框并带回车，
                    // onSubmitted 即「扫到一根条码」；手机无扫码枪时走右侧扫码按钮手动录入。
                    onSubmitted: (v) {
                      final code = v.trim();
                      if (code.isNotEmpty) _applyBarcode(code);
                    },
                  ),
                ),
                const SizedBox(width: 6),
                GestureDetector(
                  onTap: _scanBarcode,
                  child: Container(
                    padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 12),
                    decoration: BoxDecoration(color: TmsTheme.accent2, borderRadius: BorderRadius.circular(8)),
                    child: const Row(mainAxisSize: MainAxisSize.min, children: [
                      Icon(Icons.qr_code_scanner, size: 16, color: Colors.white),
                      SizedBox(width: 4),
                      Text('扫码', style: TextStyle(fontSize: 12, color: Colors.white, fontWeight: FontWeight.w700)),
                    ]),
                  ),
                ),
              ]),
              if (_searchKeyword.isNotEmpty)
                _GoodsSearchPanel(keyword: _searchKeyword, onPick: _pickGoods),
              const SizedBox(height: 8),
              ..._items.asMap().entries.map((e) => _ReturnItemRow(
                    item: e.value,
                    index: e.key + 1,
                    onChanged: () => setState(() {}),
                    onRemove: () => setState(() => _items.removeAt(e.key)),
                  )),
              if (_items.isEmpty)
                const Padding(padding: EdgeInsets.symmetric(vertical: 12), child: Center(child: Text('暂无退货商品，请扫码或搜索添加', style: TextStyle(fontSize: 12, color: TmsTheme.muted)))),
            ]),
          ),
          const SizedBox(height: 8),
          // 现场照片
          MCard(
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Row(children: [
                const Text('现场照片', style: TextStyle(fontSize: 14, fontWeight: FontWeight.w700, color: TmsTheme.ink)),
                const SizedBox(width: 6),
                Text(
                  _requirePhoto > 0
                      ? '（至少 $_requirePhoto 张，已拍 ${_photos.length} 张）'
                      : '（选填，已拍 ${_photos.length} 张）',
                  style: const TextStyle(fontSize: 11, color: TmsTheme.muted),
                ),
              ]),
              const SizedBox(height: 8),
              Wrap(spacing: 8, runSpacing: 8, children: [
                ..._photos.asMap().entries.map((e) => _PhotoTile(photo: e.value, index: e.key + 1, onDelete: () => setState(() => _photos.removeAt(e.key)))),
                if (_photos.length < _maxPhoto) _AddPhotoTile(onTap: _pickPhoto),
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
            // 功能码 + 参数双控：TMS_ONSITE_RETURN_ENABLED 关闭时现场退货入口本就不可达
            if (AuthService.hasPerm(DriverPerms.returnOnsite) &&
                ParamService.instance.current.onsiteReturnEnabled) ...[
              const SizedBox(width: 8),
              Expanded(child: TmsButton.primary(_submitting ? '提交中...' : '确认提交', onPressed: _submitting ? null : _submit)),
            ],
          ]),
          const SizedBox(height: 20),
        ],
      ),
    );
  }

  // ---------------------------------------------------------------- 选择器

  Future<void> _pickCustomer() async {
    final result = await showModalBottomSheet<CustomerSearchResult>(
      context: context,
      isScrollControlled: true,
      backgroundColor: Colors.transparent,
      builder: (_) => const _CustomerPickerSheet(),
    );
    if (result != null) {
      setState(() {
        _customerCtrl.text = result.customerName;
        _customerCode = result.customerCode;
      });
    }
  }

  Future<void> _pickWarehouse() async {
    final result = await showModalBottomSheet<WarehouseOption>(
      context: context,
      isScrollControlled: true,
      backgroundColor: Colors.transparent,
      builder: (_) => const _WarehousePickerSheet(),
    );
    if (result != null && result.warehouseName.isNotEmpty) {
      setState(() => _warehouseCtrl.text = result.warehouseName);
    }
  }

  // ---------------------------------------------------------------- 商品

  /// 输入防抖：停顿 350ms 才把关键字交给 provider 发请求。
  void _onSearchChanged(String v) {
    _searchDebounce?.cancel();
    _searchDebounce = Timer(const Duration(milliseconds: 350), () {
      if (mounted) setState(() => _searchKeyword = v.trim());
    });
  }

  /// 弹出条码录入框（PDA 扫码枪会把条码打进焦点框并回车）。
  Future<void> _scanBarcode() async {
    final code = await _promptBarcode();
    if (code == null || code.isEmpty) return;
    await _applyBarcode(code);
  }

  /// 条码查商品：精确命中条码/编码直接加入清单；否则降级为相似商品列表。
  Future<void> _applyBarcode(String code) async {
    try {
      final list = await ref.read(goodsSearchProvider(code).future);
      if (!mounted) return;
      final exact = list.where((g) => g.barcode == code || g.goodsCode == code).toList();
      if (exact.isNotEmpty) {
        _pickGoods(exact.first);
        _toast('✅ 已添加：${exact.first.goodsName}');
      } else if (list.isNotEmpty) {
        _searchCtrl.text = code;
        setState(() => _searchKeyword = code);
        _toast('条码未精确命中，已列出相似商品，请手动点选');
      } else {
        _toast('未找到该条码对应的商品');
      }
    } catch (e) {
      _toast('查询失败：${e.toString().replaceFirst("Exception: ", "")}');
    }
  }

  Future<String?> _promptBarcode() {
    return showDialog<String>(
      context: context,
      builder: (ctx) {
        final ctrl = TextEditingController();
        return AlertDialog(
          title: const Text('扫描/输入商品条码', style: TextStyle(fontSize: 15)),
          content: TextField(
            controller: ctrl,
            autofocus: true,
            textInputAction: TextInputAction.search,
            decoration: const InputDecoration(
              hintText: 'PDA 扫码枪对准条码，或手动输入后回车',
              prefixIcon: Icon(Icons.qr_code_scanner, size: 18),
            ),
            onSubmitted: (v) => Navigator.pop(ctx, v.trim()),
          ),
          actions: [
            TextButton(onPressed: () => Navigator.pop(ctx), child: const Text('取消')),
            TextButton(onPressed: () => Navigator.pop(ctx, ctrl.text.trim()), child: const Text('查询')),
          ],
        );
      },
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
    setState(() => _searchKeyword = '');
  }

  /// 拍摄现场退货照片。失败时给出可执行提示，避免静默无反应。
  Future<void> _pickPhoto() async {
    final result = await PhotoService.instance.capture();
    if (!mounted) return;
    if (result.isFailed) {
      _toast(result.error!);
      return;
    }
    if (result.isSuccess) {
      setState(() => _photos.add(result.file!));
      if (result.notice != null) _toast(result.notice!);
    }
  }

  Future<void> _submit() async {
    if (_customerCode.isEmpty || _customerCtrl.text.trim().isEmpty) {
      _toast('请选择客户');
      return;
    }
    if (_warehouseCtrl.text.trim().isEmpty) {
      _toast('请选择收货仓库');
      return;
    }
    if (_items.isEmpty) {
      _toast('请添加至少一个退货商品');
      return;
    }
    // 张数下限读参数（PRD-26 TMS_RETURN_PHOTO_COUNT），与退货回收签收同一口径
    if (_requirePhoto > 0 && _photos.length < _requirePhoto) {
      _toast('请至少拍摄 $_requirePhoto 张现场照片');
      return;
    }
    setState(() => _submitting = true);
    try {
      final photoUrlList = await ApiService.instance.uploadImagesOrDefer(
        _photos.map((p) => File(p.path)).toList(),
        bizType: 'RETURN',
      );
      final result = await ref.read(createReturnProvider(CreateReturnArgs(
        customerCode: _customerCode,
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
      // 离线入队时后端还没生成退货单号，也还没置成「司机已回收」，
      // 照常提示会显示空单号并谎报物流状态。
      if (result['_offline'] == true) {
        _toast('当前无网络，退货已暂存本地，联网后自动上传');
      } else {
        _toast('退货创建成功：$applyNo，物流状态=司机已回收');
      }
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

/// 下拉选择样式的字段（整行可点，右侧下拉箭头）。
class _PickerTile extends StatelessWidget {
  final String label;
  final String value;
  final String placeholder;
  final IconData icon;
  final VoidCallback onTap;
  const _PickerTile({required this.label, required this.value, required this.placeholder, required this.icon, required this.onTap});

  @override
  Widget build(BuildContext context) {
    return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      Text(label, style: const TextStyle(fontSize: 12, color: TmsTheme.muted, fontWeight: FontWeight.w600)),
      const SizedBox(height: 4),
      InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(8),
        child: Container(
          padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 13),
          decoration: BoxDecoration(
            color: Colors.white,
            borderRadius: BorderRadius.circular(8),
            border: Border.all(color: TmsTheme.rule, width: 1.5),
          ),
          child: Row(children: [
            Expanded(
              child: Text(
                value.isEmpty ? placeholder : value,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: TextStyle(fontSize: 13, color: value.isEmpty ? TmsTheme.muted : TmsTheme.ink),
              ),
            ),
            Icon(icon, size: 20, color: TmsTheme.muted),
          ]),
        ),
      ),
    ]);
  }
}

/// 带搜索图标的商品搜索框（样式与 _Field 对齐，多一个前缀图标和回车回调）。
class _SearchField extends StatelessWidget {
  final TextEditingController ctrl;
  final String placeholder;
  final ValueChanged<String> onChanged;
  final ValueChanged<String> onSubmitted;
  const _SearchField({required this.ctrl, required this.placeholder, required this.onChanged, required this.onSubmitted});

  @override
  Widget build(BuildContext context) {
    return TextField(
      controller: ctrl,
      onChanged: onChanged,
      onSubmitted: onSubmitted,
      textInputAction: TextInputAction.search,
      decoration: InputDecoration(
        hintText: placeholder,
        prefixIcon: const Icon(Icons.search, size: 18),
        filled: true,
        fillColor: Colors.white,
        isDense: true,
        contentPadding: const EdgeInsets.symmetric(horizontal: 12, vertical: 12),
        border: OutlineInputBorder(borderRadius: BorderRadius.circular(8), borderSide: const BorderSide(color: TmsTheme.rule, width: 1.5)),
        enabledBorder: OutlineInputBorder(borderRadius: BorderRadius.circular(8), borderSide: const BorderSide(color: TmsTheme.rule, width: 1.5)),
        focusedBorder: OutlineInputBorder(borderRadius: BorderRadius.circular(8), borderSide: const BorderSide(color: TmsTheme.accent2, width: 1.5)),
      ),
    );
  }
}

/// 客户选择弹层：打开先定位（静默失败不阻塞），无关键字展示最近 10 个客户。
class _CustomerPickerSheet extends ConsumerStatefulWidget {
  const _CustomerPickerSheet();

  @override
  ConsumerState<_CustomerPickerSheet> createState() => _CustomerPickerSheetState();
}

class _CustomerPickerSheetState extends ConsumerState<_CustomerPickerSheet> {
  final _ctrl = TextEditingController();
  String _keyword = '';
  Timer? _debounce;
  double? _lng;
  double? _lat;

  @override
  void initState() {
    super.initState();
    _locate();
  }

  @override
  void dispose() {
    _debounce?.cancel();
    _ctrl.dispose();
    super.dispose();
  }

  /// 取当前位置给后端算最近客户。权限被拒/无定位/超时都静默降级，
  /// 后端会按名称兜底返回，不能让司机卡在选择器上。
  Future<void> _locate() async {
    try {
      final enabled = await Geolocator.isLocationServiceEnabled();
      if (!enabled) return;
      var permission = await Geolocator.checkPermission();
      if (permission == LocationPermission.denied) {
        permission = await Geolocator.requestPermission();
      }
      if (permission == LocationPermission.denied || permission == LocationPermission.deniedForever) return;
      final pos = await Geolocator.getCurrentPosition(
        locationSettings: const LocationSettings(accuracy: LocationAccuracy.medium, timeLimit: Duration(seconds: 8)),
      );
      if (mounted) {
        setState(() {
          _lat = pos.latitude;
          _lng = pos.longitude;
        });
      }
    } catch (_) {
      // 定位失败按无坐标处理
    }
  }

  void _onChanged(String v) {
    _debounce?.cancel();
    _debounce = Timer(const Duration(milliseconds: 350), () {
      if (mounted) setState(() => _keyword = v.trim());
    });
  }

  @override
  Widget build(BuildContext context) {
    final async = ref.watch(returnCustomerSearchProvider(
      CustomerSearchArgs(keyword: _keyword, longitude: _lng, latitude: _lat),
    ));
    return Padding(
      padding: EdgeInsets.only(bottom: MediaQuery.of(context).viewInsets.bottom),
      child: Container(
        height: MediaQuery.of(context).size.height * 0.72,
        decoration: const BoxDecoration(color: TmsTheme.bg, borderRadius: BorderRadius.vertical(top: Radius.circular(16))),
        child: Column(children: [
          Container(
            padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
            decoration: const BoxDecoration(
              color: Colors.white,
              borderRadius: BorderRadius.vertical(top: Radius.circular(16)),
              border: Border(bottom: BorderSide(color: TmsTheme.rule)),
            ),
            child: Row(children: [
              const Expanded(child: Text('选择客户', style: TextStyle(fontSize: 15, fontWeight: FontWeight.w700, color: TmsTheme.ink))),
              GestureDetector(onTap: () => Navigator.pop(context), child: const Icon(Icons.close, size: 20, color: TmsTheme.muted)),
            ]),
          ),
          Padding(
            padding: const EdgeInsets.fromLTRB(12, 10, 12, 6),
            child: TextField(
              controller: _ctrl,
              autofocus: false,
              onChanged: _onChanged,
              decoration: InputDecoration(
                hintText: _keyword.isEmpty ? '输入客户名称/地址/电话模糊查询' : '输入关键字查询',
                prefixIcon: const Icon(Icons.search, size: 18),
                filled: true,
                fillColor: Colors.white,
                isDense: true,
                contentPadding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
                border: OutlineInputBorder(borderRadius: BorderRadius.circular(8), borderSide: const BorderSide(color: TmsTheme.rule, width: 1.5)),
                enabledBorder: OutlineInputBorder(borderRadius: BorderRadius.circular(8), borderSide: const BorderSide(color: TmsTheme.rule, width: 1.5)),
                focusedBorder: OutlineInputBorder(borderRadius: BorderRadius.circular(8), borderSide: const BorderSide(color: TmsTheme.accent2, width: 1.5)),
              ),
            ),
          ),
          Expanded(
            child: async.when(
              data: (list) {
                if (list.isEmpty) {
                  return const Center(child: Text('无匹配客户', style: TextStyle(fontSize: 12, color: TmsTheme.muted)));
                }
                return ListView.separated(
                  padding: const EdgeInsets.symmetric(horizontal: 12),
                  itemCount: list.length,
                  separatorBuilder: (_, __) => const Divider(height: 1, color: Color(0xFFF0F1F4)),
                  itemBuilder: (_, i) {
                    final c = list[i];
                    return InkWell(
                      onTap: () => Navigator.pop(context, c),
                      child: Padding(
                        padding: const EdgeInsets.symmetric(vertical: 10),
                        child: Row(children: [
                          Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                            Row(children: [
                              Flexible(
                                child: Text(c.customerName, style: const TextStyle(fontSize: 13, fontWeight: FontWeight.w700, color: TmsTheme.ink)),
                              ),
                              if (c.distanceKm != null) ...[
                                const SizedBox(width: 6),
                                Container(
                                  padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 1),
                                  decoration: BoxDecoration(color: const Color(0xFFFFF3E8), borderRadius: BorderRadius.circular(4)),
                                  child: Text('${c.distanceKm}km', style: const TextStyle(fontSize: 10, color: TmsTheme.accent2, fontWeight: FontWeight.w700)),
                                ),
                              ],
                            ]),
                            if (c.address.isNotEmpty) ...[
                              const SizedBox(height: 2),
                              Text(c.address, maxLines: 1, overflow: TextOverflow.ellipsis, style: const TextStyle(fontSize: 11, color: TmsTheme.muted)),
                            ],
                          ])),
                          const Icon(Icons.chevron_right, size: 18, color: TmsTheme.muted),
                        ]),
                      ),
                    );
                  },
                );
              },
              loading: () => const Center(child: SizedBox(width: 20, height: 20, child: CircularProgressIndicator(strokeWidth: 2))),
              error: (e, _) => Center(
                child: Padding(padding: const EdgeInsets.all(20), child: Text('客户查询失败：${e.toString().replaceFirst("Exception: ", "")}', style: const TextStyle(fontSize: 12, color: TmsTheme.bad))),
              ),
            ),
          ),
          if (_keyword.isEmpty)
            const Padding(
              padding: EdgeInsets.only(bottom: 10),
              child: Text('默认按当前位置由近到远展示，无坐标客户排在后面', style: TextStyle(fontSize: 10, color: TmsTheme.muted)),
            ),
        ]),
      ),
    );
  }
}

/// 收货仓库选择弹层：只列正常实物仓。
class _WarehousePickerSheet extends ConsumerWidget {
  const _WarehousePickerSheet();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final async = ref.watch(returnWarehouseListProvider);
    return Container(
      height: MediaQuery.of(context).size.height * 0.6,
      decoration: const BoxDecoration(color: TmsTheme.bg, borderRadius: BorderRadius.vertical(top: Radius.circular(16))),
      child: Column(children: [
        Container(
          padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
          decoration: const BoxDecoration(
            color: Colors.white,
            borderRadius: BorderRadius.vertical(top: Radius.circular(16)),
            border: Border(bottom: BorderSide(color: TmsTheme.rule)),
          ),
          child: Row(children: [
            const Expanded(child: Text('选择收货仓库（实物仓）', style: TextStyle(fontSize: 15, fontWeight: FontWeight.w700, color: TmsTheme.ink))),
            GestureDetector(onTap: () => Navigator.pop(context), child: const Icon(Icons.close, size: 20, color: TmsTheme.muted)),
          ]),
        ),
        Expanded(
          child: async.when(
            data: (list) {
              if (list.isEmpty) {
                return const Center(child: Text('没有可用的实物仓，请先在后台维护仓库档案', style: TextStyle(fontSize: 12, color: TmsTheme.muted)));
              }
              return ListView.separated(
                padding: const EdgeInsets.symmetric(horizontal: 12),
                itemCount: list.length,
                separatorBuilder: (_, __) => const Divider(height: 1, color: Color(0xFFF0F1F4)),
                itemBuilder: (_, i) {
                  final w = list[i];
                  return InkWell(
                    onTap: () => Navigator.pop(context, w),
                    child: Padding(
                      padding: const EdgeInsets.symmetric(vertical: 12),
                      child: Row(children: [
                        const Icon(Icons.warehouse, size: 18, color: TmsTheme.accent2),
                        const SizedBox(width: 8),
                        Expanded(child: Text(w.warehouseName, style: const TextStyle(fontSize: 13, fontWeight: FontWeight.w600, color: TmsTheme.ink))),
                        const Icon(Icons.chevron_right, size: 18, color: TmsTheme.muted),
                      ]),
                    ),
                  );
                },
              );
            },
            loading: () => const Center(child: SizedBox(width: 20, height: 20, child: CircularProgressIndicator(strokeWidth: 2))),
            error: (e, _) => Center(
              child: Padding(padding: const EdgeInsets.all(20), child: Text('仓库查询失败：${e.toString().replaceFirst("Exception: ", "")}', style: const TextStyle(fontSize: 12, color: TmsTheme.bad))),
            ),
          ),
        ),
      ]),
    );
  }
}

/// 商品搜索结果面板：下拉行展示商品名称/规格/单位。
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
              final subtitle = [g.spec, g.unitName].where((s) => s.isNotEmpty).join(' · ');
              return InkWell(
                onTap: () => onPick(g),
                child: Container(
                  padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 8),
                  decoration: const BoxDecoration(border: Border(bottom: BorderSide(color: Color(0xFFF0F1F4)))),
                  child: Row(children: [
                    Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                      Text(g.goodsName, style: const TextStyle(fontSize: 12, fontWeight: FontWeight.w600, color: TmsTheme.ink)),
                      if (subtitle.isNotEmpty) ...[
                        const SizedBox(height: 2),
                        Text(subtitle, style: const TextStyle(fontSize: 10, color: TmsTheme.muted)),
                      ],
                    ])),
                    const Icon(Icons.add_circle, size: 18, color: TmsTheme.accent2),
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
        Text([item.goodsCode, item.spec, item.unitName].where((s) => s.isNotEmpty).join(' · '), style: const TextStyle(fontSize: 10, color: TmsTheme.muted)),
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
          focusedBorder: OutlineInputBorder(borderRadius: BorderRadius.circular(8), borderSide: const BorderSide(color: TmsTheme.accent2, width: 1.5)),
        ),
      ),
    ]);
  }
}
