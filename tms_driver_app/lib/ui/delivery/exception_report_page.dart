import 'dart:io';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:geolocator/geolocator.dart';
import 'package:image_picker/image_picker.dart';
import '../../config/app_config.dart';
import '../../config/theme.dart';
import '../../providers/exception_provider.dart';
import '../../services/api_service.dart';
import '../../widgets/common.dart';
import '../../widgets/offline_banner.dart';

/// 通用异常上报页（P3-4）。
///
/// 承载「没有单据流程可走」的现场异常：车辆故障、交通事故、货物破损、
/// 门店关门、天气阻断、道路管控、其他。客户拒收与地址不符不在此处受理——
/// 那两类已有拒收单 / 改派返仓单承载，从异常上报走会绕过库存与应收处理。
///
/// 核心原则：**上报绝不能被阻断**。
///   - 坐标拿不到（地下车库、隧道、拒绝授权）照样能提交，靠位置描述兜底；
///   - 未关联行程也能提交（出车前检查发现故障时还没有任务）；
///   - 离线时整单入队，联网后自动补传，不让司机在事故现场跟表单较劲。
///
/// 流程：
///   1. 进页面并行拉取类型字典 + 获取当前 GPS
///   2. 选类型（紧急类型给红色强提示，并提示补齐车牌）
///   3. 填描述（必填）、位置描述、备注，按参数要求拍现场照
///   4. 提交 → /tms/app/exception/create（离线自动入队，优先级 1）
class ExceptionReportPage extends ConsumerStatefulWidget {
  final String dispatchId;
  final String detailId;
  final String receiptNo;
  final String customerName;
  final String vehicleNo;

  const ExceptionReportPage({
    super.key,
    this.dispatchId = '',
    this.detailId = '',
    this.receiptNo = '',
    this.customerName = '',
    this.vehicleNo = '',
  });

  @override
  ConsumerState<ExceptionReportPage> createState() =>
      _ExceptionReportPageState();
}

class _ExceptionReportPageState extends ConsumerState<ExceptionReportPage> {
  final _descCtrl = TextEditingController();
  final _addressCtrl = TextEditingController();
  final _remarkCtrl = TextEditingController();
  late final TextEditingController _vehicleCtrl;
  final List<XFile> _photos = [];

  String _type = '';

  double? _lng;
  double? _lat;
  double? _accuracy;
  String _locateError = '';
  bool _locating = false;
  bool _submitting = false;

  @override
  void initState() {
    super.initState();
    _vehicleCtrl = TextEditingController(text: widget.vehicleNo);
    WidgetsBinding.instance.addPostFrameCallback((_) => _locate());
  }

  @override
  void dispose() {
    _descCtrl.dispose();
    _addressCtrl.dispose();
    _remarkCtrl.dispose();
    _vehicleCtrl.dispose();
    super.dispose();
  }

  bool get _hasFix => _lng != null && _lat != null;

  Future<void> _locate() async {
    setState(() {
      _locating = true;
      _locateError = '';
    });
    try {
      if (!await Geolocator.isLocationServiceEnabled()) {
        setState(() => _locateError = '手机定位服务未开启，可填写位置描述后继续上报');
        return;
      }
      var permission = await Geolocator.checkPermission();
      if (permission == LocationPermission.denied) {
        permission = await Geolocator.requestPermission();
      }
      if (permission == LocationPermission.denied ||
          permission == LocationPermission.deniedForever) {
        setState(() => _locateError = '定位权限未授予，可填写位置描述后继续上报');
        return;
      }
      final p = await Geolocator.getCurrentPosition(
        locationSettings: const LocationSettings(
          accuracy: LocationAccuracy.high,
          timeLimit: Duration(seconds: 15),
        ),
      );
      setState(() {
        _lng = p.longitude;
        _lat = p.latitude;
        _accuracy = p.accuracy >= 0 ? p.accuracy : null;
      });
    } catch (e) {
      setState(() => _locateError = '定位失败：${_msg(e)}，可填写位置描述后继续上报');
    } finally {
      if (mounted) setState(() => _locating = false);
    }
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

  Future<void> _submit(ExceptionConfig cfg) async {
    if (_type.isEmpty) {
      _toast('请选择异常类型');
      return;
    }
    final desc = _descCtrl.text.trim();
    if (desc.isEmpty) {
      _toast('请填写异常描述，调度员靠这段话判断怎么处置');
      return;
    }
    // 照片是软要求：参数开启时提示，但离线或相机不可用时不能把上报卡死。
    // 漏报一起事故的代价远大于少一张照片。
    if (cfg.photoRequired && _photos.isEmpty) {
      final go = await _confirmNoPhoto();
      if (go != true) return;
    }

    setState(() => _submitting = true);
    try {
      List<String> photoUrls = const [];
      if (_photos.isNotEmpty) {
        photoUrls = await ApiService.instance.uploadImagesOrDefer(
          _photos.map((p) => File(p.path)).toList(),
          bizType: 'EXCEPTION',
        );
      }
      final typeName = _typeName(cfg, _type);
      final result =
          await ref.read(reportExceptionProvider(ReportExceptionArgs(
        exceptionType: _type,
        description: desc,
        title: typeName,
        dispatchId: widget.dispatchId,
        detailId: widget.detailId,
        receiptNo: widget.receiptNo,
        customerName: widget.customerName,
        vehicleNo: _vehicleCtrl.text.trim(),
        locationAddress: _addressCtrl.text.trim(),
        remark: _remarkCtrl.text.trim(),
        longitude: _lng,
        latitude: _lat,
        accuracy: _accuracy,
        photos: photoUrls,
        reportedAt: _nowText(),
      )).future);

      if (result['_offline'] == true) {
        _toast('当前无网络，异常已暂存本地，联网后优先上报');
      } else {
        final no = result['reportNo']?.toString() ?? '';
        _toast(no.isEmpty ? '异常上报成功' : '异常上报成功：$no');
      }
      ref.invalidate(exceptionListProvider);
      if (mounted) Navigator.pop(context, true);
    } catch (e) {
      _toast('上报失败：${_msg(e)}');
    } finally {
      if (mounted) setState(() => _submitting = false);
    }
  }

  Future<bool?> _confirmNoPhoto() {
    return showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('未拍现场照片'),
        content: const Text('当前配置建议上报时附现场照片。若现在无法拍照，也可以直接提交，事后再补充。'),
        actions: [
          TextButton(
              onPressed: () => Navigator.pop(ctx, false), child: const Text('去拍照')),
          TextButton(
              onPressed: () => Navigator.pop(ctx, true), child: const Text('直接提交')),
        ],
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final cfgAsync = ref.watch(exceptionConfigProvider);
    final cfg = cfgAsync.valueOrNull ?? const ExceptionConfig();
    return Scaffold(
      backgroundColor: TmsTheme.bg,
      appBar: AppBar(title: const Text('异常上报')),
      body: Column(children: [
        const OfflineBanner(),
        Expanded(child: _buildBody(cfg)),
      ]),
    );
  }

  Widget _buildBody(ExceptionConfig cfg) {
    final urgent = _type.isNotEmpty && cfg.isUrgent(_type);
    return ListView(
      padding: const EdgeInsets.all(14),
      children: [
        if (urgent)
          const Alert.danger('🚨 该类型为紧急异常，提交后调度员会第一时间收到，请确认车牌与位置准确')
        else
          const Alert.info('ℹ️ 客户拒收、地址不符请走「客户拒收」「改派返仓」单据，本页用于现场突发异常'),
        const SizedBox(height: 10),

        // 关联任务（有则展示，无则说明可独立上报）
        _buildContextCard(),
        const SizedBox(height: 12),

        // 异常类型
        MCard(
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            const Text('异常类型',
                style: TextStyle(
                    fontSize: 14, fontWeight: FontWeight.w700, color: TmsTheme.ink)),
            const SizedBox(height: 8),
            Wrap(
              spacing: 6,
              runSpacing: 6,
              children: cfg.types.map((t) {
                final on = _type == t.code;
                final hot = cfg.isUrgent(t.code);
                final activeColor = hot ? TmsTheme.bad : TmsTheme.accent;
                return GestureDetector(
                  onTap: () => setState(() => _type = t.code),
                  child: Container(
                    padding:
                        const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
                    decoration: BoxDecoration(
                      color: on ? activeColor : Colors.white,
                      borderRadius: BorderRadius.circular(8),
                      border: Border.all(
                          color: on ? activeColor : TmsTheme.rule, width: 1.5),
                    ),
                    child: Text(
                      t.name,
                      style: TextStyle(
                        fontSize: 12,
                        color: on ? Colors.white : TmsTheme.muted,
                        fontWeight: FontWeight.w600,
                      ),
                    ),
                  ),
                );
              }).toList(),
            ),
          ]),
        ),
        const SizedBox(height: 12),

        // 异常描述
        MCard(
          leftBar: urgent ? TmsTheme.bad : null,
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            const Text('异常描述（必填）',
                style: TextStyle(
                    fontSize: 14, fontWeight: FontWeight.w700, color: TmsTheme.ink)),
            const SizedBox(height: 8),
            TextField(
              controller: _descCtrl,
              maxLines: 4,
              maxLength: 500,
              decoration: const InputDecoration(
                hintText: '如：右后轮爆胎，需拖车救援 / 门店卷帘门未开，电话无人接听',
                border: OutlineInputBorder(),
                isDense: true,
                contentPadding: EdgeInsets.all(10),
              ),
              style: const TextStyle(fontSize: 13),
            ),
            const SizedBox(height: 4),
            TextField(
              controller: _vehicleCtrl,
              decoration: InputDecoration(
                labelText: urgent ? '车牌号（建议填写）' : '车牌号',
                hintText: '未填时由服务端按调度单补齐',
                border: const OutlineInputBorder(),
                isDense: true,
                contentPadding: const EdgeInsets.all(10),
              ),
              style: const TextStyle(fontSize: 13),
            ),
          ]),
        ),
        const SizedBox(height: 12),

        // 位置
        _buildLocationCard(),
        const SizedBox(height: 12),

        // 现场照片
        MCard(
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Row(children: [
              const Text('现场照片',
                  style: TextStyle(
                      fontSize: 14, fontWeight: FontWeight.w700, color: TmsTheme.ink)),
              const SizedBox(width: 6),
              Text(
                cfg.photoRequired
                    ? '（建议至少 1 张，已拍 ${_photos.length} 张）'
                    : '（可选，已拍 ${_photos.length} 张）',
                style: const TextStyle(fontSize: 11, color: TmsTheme.muted),
              ),
            ]),
            const SizedBox(height: 8),
            Wrap(spacing: 8, runSpacing: 8, children: [
              ..._photos.asMap().entries.map((e) => _PhotoTile(
                    photo: e.value,
                    index: e.key + 1,
                    onDelete: () => setState(() => _photos.removeAt(e.key)),
                  )),
              if (_photos.length < 6) _AddPhotoTile(onTap: _pickPhoto),
            ]),
          ]),
        ),
        const SizedBox(height: 12),

        TextField(
          controller: _remarkCtrl,
          maxLines: 2,
          decoration: const InputDecoration(
            labelText: '备注',
            hintText: '可选，如已联系的对接人、预计恢复时间',
            filled: true,
            fillColor: Colors.white,
            border: OutlineInputBorder(),
            isDense: true,
            contentPadding: EdgeInsets.all(10),
          ),
          style: const TextStyle(fontSize: 13),
        ),
        const SizedBox(height: 16),

        Row(children: [
          Expanded(
            child: TmsButton.outline('取消',
                color: TmsTheme.muted,
                onPressed: _submitting ? null : () => Navigator.pop(context)),
          ),
          const SizedBox(width: 8),
          Expanded(
            child: urgent
                ? TmsButton.danger(_submitting ? '上报中...' : '紧急上报',
                    onPressed: _submitting ? null : () => _submit(cfg))
                : TmsButton.warn(_submitting ? '上报中...' : '提交异常上报',
                    onPressed: _submitting ? null : () => _submit(cfg)),
          ),
        ]),
        const SizedBox(height: 20),
      ],
    );
  }

  /// 关联任务卡。
  ///
  /// 无 detailId 时不隐藏而是显式说明「独立上报」——出车前检查发现故障、
  /// 路上被交警拦下都属于没有任务上下文的场景，司机需要确认这样也能提交。
  Widget _buildContextCard() {
    final linked = widget.detailId.isNotEmpty || widget.receiptNo.isNotEmpty;
    if (!linked) {
      return const MCard(
        leftBar: TmsTheme.muted,
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Row(children: [
            Icon(Icons.info_outline, size: 18, color: TmsTheme.muted),
            SizedBox(width: 6),
            Text('独立上报',
                style: TextStyle(
                    fontSize: 14, fontWeight: FontWeight.w700, color: TmsTheme.muted)),
          ]),
          SizedBox(height: 6),
          Text('本次上报未关联具体门店任务（如出车前检查、途中突发），可直接提交。',
              style: TextStyle(fontSize: 12, color: TmsTheme.muted)),
        ]),
      );
    }
    return MCard(
      leftBar: TmsTheme.accent,
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        const Text('关联任务',
            style: TextStyle(
                fontSize: 14, fontWeight: FontWeight.w700, color: TmsTheme.ink)),
        const SizedBox(height: 8),
        if (widget.receiptNo.isNotEmpty) MLine('发货单号', widget.receiptNo),
        if (widget.customerName.isNotEmpty) MLine('客户名称', widget.customerName),
      ]),
    );
  }

  Widget _buildLocationCard() {
    return MCard(
      leftBar: _hasFix ? TmsTheme.ok : TmsTheme.muted,
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Row(children: [
          const Icon(Icons.my_location, size: 18, color: TmsTheme.accent),
          const SizedBox(width: 6),
          const Expanded(
            child: Text('异常位置',
                style: TextStyle(
                    fontSize: 14, fontWeight: FontWeight.w700, color: TmsTheme.ink)),
          ),
          if (_locating)
            const SizedBox(
                width: 16, height: 16, child: CircularProgressIndicator(strokeWidth: 2))
          else
            GestureDetector(
              onTap: _locate,
              child: const Row(children: [
                Icon(Icons.refresh, size: 14, color: TmsTheme.accent),
                SizedBox(width: 2),
                Text('重新定位', style: TextStyle(fontSize: 12, color: TmsTheme.accent)),
              ]),
            ),
        ]),
        const SizedBox(height: 6),
        MLine(
          '经纬度',
          _hasFix
              ? '${_lng!.toStringAsFixed(6)}, ${_lat!.toStringAsFixed(6)}'
              : (_locating ? '定位中…' : '未获取'),
          valueColor: _hasFix ? null : TmsTheme.accent2,
        ),
        if (_accuracy != null) MLine('定位精度', '±${_accuracy!.toStringAsFixed(0)} 米'),
        if (_locateError.isNotEmpty) ...[
          const SizedBox(height: 4),
          Text(_locateError,
              style: const TextStyle(fontSize: 12, color: TmsTheme.accent2)),
        ],
        const SizedBox(height: 8),
        TextField(
          controller: _addressCtrl,
          decoration: InputDecoration(
            labelText: _hasFix ? '位置描述' : '位置描述（拿不到坐标时请务必填写）',
            hintText: '如：G60 沪昆高速 128 公里处 / XX 路 XX 号门店门口',
            border: const OutlineInputBorder(),
            isDense: true,
            contentPadding: const EdgeInsets.all(10),
          ),
          style: const TextStyle(fontSize: 13),
        ),
      ]),
    );
  }

  String _typeName(ExceptionConfig cfg, String code) {
    for (final t in cfg.types) {
      if (t.code == code) return t.name;
    }
    return '现场异常';
  }

  /// 后端 parseReportedAt 接受 `yyyy-MM-dd HH:mm:ss`。
  String _nowText() {
    final d = DateTime.now();
    String p(int v) => v.toString().padLeft(2, '0');
    return '${d.year}-${p(d.month)}-${p(d.day)} '
        '${p(d.hour)}:${p(d.minute)}:${p(d.second)}';
  }

  void _toast(String msg) {
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text(msg), duration: const Duration(seconds: 2)));
  }

  String _msg(Object e) => e.toString().replaceFirst('Exception: ', '');
}

/// 已拍照片缩略图。
class _PhotoTile extends StatelessWidget {
  final XFile photo;
  final int index;
  final VoidCallback onDelete;
  const _PhotoTile(
      {required this.photo, required this.index, required this.onDelete});

  @override
  Widget build(BuildContext context) {
    return SizedBox(
      width: 80,
      height: 80,
      child: Stack(children: [
        ClipRRect(
            borderRadius: BorderRadius.circular(10),
            child: Image.file(File(photo.path),
                width: 80, height: 80, fit: BoxFit.cover)),
        Positioned(
            top: 2,
            right: 2,
            child: GestureDetector(
              onTap: onDelete,
              child: Container(
                  padding: const EdgeInsets.all(2),
                  decoration: const BoxDecoration(
                      color: Color(0xCC000000), shape: BoxShape.circle),
                  child: const Icon(Icons.close, size: 12, color: Colors.white)),
            )),
        Positioned(
            bottom: 2,
            left: 2,
            child: Container(
              padding: const EdgeInsets.symmetric(horizontal: 4, vertical: 1),
              decoration: BoxDecoration(
                  color: const Color(0x88000000),
                  borderRadius: BorderRadius.circular(4)),
              child: Text('$index',
                  style: const TextStyle(
                      fontSize: 9, color: Colors.white, fontWeight: FontWeight.w600)),
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
        decoration: BoxDecoration(
            color: const Color(0xFFF9FAFB),
            borderRadius: BorderRadius.circular(10),
            border: Border.all(color: TmsTheme.rule, width: 1.5)),
        child: const Column(mainAxisAlignment: MainAxisAlignment.center, children: [
          Icon(Icons.camera_alt_outlined, size: 22, color: TmsTheme.muted),
          SizedBox(height: 2),
          Text('拍照', style: TextStyle(fontSize: 10, color: TmsTheme.muted)),
        ]),
      ),
    );
  }
}
