import 'dart:io';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:geolocator/geolocator.dart';
import 'package:image_picker/image_picker.dart';
import '../../services/photo_service.dart';
import '../../config/theme.dart';
import '../../providers/delivery_provider.dart';
import '../../services/api_service.dart';
import '../../widgets/common.dart';
import '../../widgets/offline_banner.dart';

/// 到达门店打卡页（P0-B3）。
///
/// 设计原则：**软提示、不强制**。
///   - 打卡不是签收的前置门禁，是否强制由系统参数 TMS_ARRIVE_REQUIRED 控制，
///     由用户在 ERP「系统参数」里自行开关；
///   - 门店未维护坐标、或手机拒绝定位授权时降级为「无围栏打卡」，只记时间；
///   - 距离与异常判定一律由服务端复算，本页展示的距离仅供司机预览。
///
/// 流程：
///   1. 进页面并行拉取打卡参数 + 获取当前 GPS
///   2. 本地预算与门店的直线距离，按 正常/偏差/异常 三档着色
///   3. 判定异常时要求填写原因（参数要求则同时要求现场照）
///   4. 提交 → /tms/app/arrive（离线自动入队，优先级 2）
class ArrivePage extends ConsumerStatefulWidget {
  final String dispatchId;
  final String detailId;
  final String customerName;
  final String customerAddress;

  /// 门店档案坐标（后端透传，未维护时为 null → 无围栏模式）。
  final double? storeLongitude;
  final double? storeLatitude;

  const ArrivePage({
    super.key,
    required this.dispatchId,
    required this.detailId,
    this.customerName = '',
    this.customerAddress = '',
    this.storeLongitude,
    this.storeLatitude,
  });

  @override
  ConsumerState<ArrivePage> createState() => _ArrivePageState();
}

class _ArrivePageState extends ConsumerState<ArrivePage> {
  final _reasonCtrl = TextEditingController();
  XFile? _photo;

  double? _lng;
  double? _lat;
  double? _accuracy;
  String _locateError = '';
  bool _locating = false;
  bool _submitting = false;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) => _locate());
  }

  @override
  void dispose() {
    _reasonCtrl.dispose();
    super.dispose();
  }

  bool get _hasStoreGeo =>
      widget.storeLongitude != null && widget.storeLatitude != null;

  bool get _hasFix => _lng != null && _lat != null;

  /// 是否具备围栏比对条件：门店坐标与本机定位都齐全。
  bool get _geoEnabled => _hasStoreGeo && _hasFix;

  Future<void> _locate() async {
    setState(() {
      _locating = true;
      _locateError = '';
    });
    try {
      if (!await Geolocator.isLocationServiceEnabled()) {
        setState(() => _locateError = '手机定位服务未开启，可继续无围栏打卡');
        return;
      }
      var permission = await Geolocator.checkPermission();
      if (permission == LocationPermission.denied) {
        permission = await Geolocator.requestPermission();
      }
      if (permission == LocationPermission.denied ||
          permission == LocationPermission.deniedForever) {
        setState(() => _locateError = '定位权限未授予，可继续无围栏打卡');
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
      setState(() => _locateError = '定位失败：${_msg(e)}，可继续无围栏打卡');
    } finally {
      if (mounted) setState(() => _locating = false);
    }
  }

  /// 本地预览距离（米）。与服务端同用 Haversine，结果一致。
  double? get _distance {
    if (!_geoEnabled) return null;
    return Geolocator.distanceBetween(
        _lat!, _lng!, widget.storeLatitude!, widget.storeLongitude!);
  }

  /// 拍摄到店打卡照片。失败时给出可执行提示，避免静默无反应。
  Future<void> _pickPhoto() async {
    final result = await PhotoService.instance.capture();
    if (!mounted) return;
    if (result.isFailed) {
      _toast(result.error!);
      return;
    }
    if (result.isSuccess) {
      setState(() => _photo = result.file);
      if (result.notice != null) _toast(result.notice!);
    }
  }

  Future<void> _submit(ArriveConfig cfg) async {
    final distance = _distance;
    final abnormal = distance != null && distance > cfg.warnRadius;
    final reason = _reasonCtrl.text.trim();
    if (abnormal) {
      if (reason.isEmpty) {
        _toast('定位偏差过大，请填写异常原因');
        return;
      }
      if (cfg.photoRequired && _photo == null) {
        _toast('定位异常打卡需上传现场照片');
        return;
      }
    }

    setState(() => _submitting = true);
    try {
      String photoUrl = '';
      if (_photo != null) {
        final r = await ApiService.instance
            .enqueueOrUpload(file: File(_photo!.path), bizType: 'SIGN', actionType: 'ARRIVE_PHOTO', actionKey: widget.detailId);
        photoUrl = r['url']?.toString() ?? '';
      }
      final result = await ref.read(arriveProvider(ArriveArgs(
        dispatchId: widget.dispatchId,
        detailId: widget.detailId,
        longitude: _lng,
        latitude: _lat,
        accuracy: _accuracy,
        abnormalReason: reason,
        photoUrl: photoUrl,
      )).future);

      if (result['_offline'] == true) {
        _toast('已离线暂存，联网后自动上报打卡');
      } else {
        _toast(result['message']?.toString() ?? '打卡成功');
      }
      if (mounted) Navigator.pop(context, true);
    } catch (e) {
      _toast('打卡失败：${_msg(e)}');
    } finally {
      if (mounted) setState(() => _submitting = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final cfgAsync = ref.watch(arriveConfigProvider);
    final cfg = cfgAsync.valueOrNull ?? const ArriveConfig();
    return Scaffold(
      backgroundColor: TmsTheme.bg,
      appBar: AppBar(title: const Text('到店打卡')),
      body: Column(children: [
        const OfflineBanner(),
        Expanded(child: _buildBody(cfg)),
      ]),
    );
  }

  Widget _buildBody(ArriveConfig cfg) {
    final distance = _distance;
    final abnormal = distance != null && distance > cfg.warnRadius;

    return ListView(
      padding: const EdgeInsets.all(14),
      children: [
        if (cfg.arriveRequired)
          const Alert.warn('⚠️ 当前配置要求签收前必须完成到店打卡')
        else
          const Alert.info('ℹ️ 打卡用于记录到店时间与位置，不影响签收流程'),
        const SizedBox(height: 10),

        // 门店信息
        MCard(
          leftBar: TmsTheme.accent,
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Row(children: [
              const Icon(Icons.store, size: 18, color: TmsTheme.accent),
              const SizedBox(width: 6),
              Expanded(
                child: Text(
                  widget.customerName.isEmpty ? '门店' : widget.customerName,
                  style: const TextStyle(
                      fontSize: 15, fontWeight: FontWeight.w700, color: TmsTheme.ink),
                ),
              ),
            ]),
            const SizedBox(height: 4),
            if (widget.customerAddress.isNotEmpty)
              MLine('地址', widget.customerAddress),
            MLine(
              '门店坐标',
              _hasStoreGeo
                  ? '${widget.storeLongitude!.toStringAsFixed(6)}, ${widget.storeLatitude!.toStringAsFixed(6)}'
                  : '未维护',
              valueColor: _hasStoreGeo ? null : TmsTheme.accent2,
            ),
          ]),
        ),
        const SizedBox(height: 12),

        // 当前定位
        MCard(
          leftBar: _hasFix ? TmsTheme.ok : TmsTheme.muted,
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Row(children: [
              const Icon(Icons.my_location, size: 18, color: TmsTheme.accent),
              const SizedBox(width: 6),
              const Expanded(
                child: Text('当前定位',
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
                    Text('重新定位',
                        style: TextStyle(fontSize: 12, color: TmsTheme.accent)),
                  ]),
                ),
            ]),
            const SizedBox(height: 6),
            MLine(
              '经纬度',
              _hasFix
                  ? '${_lng!.toStringAsFixed(6)}, ${_lat!.toStringAsFixed(6)}'
                  : (_locating ? '定位中…' : '未获取'),
            ),
            if (_accuracy != null)
              MLine('定位精度', '±${_accuracy!.toStringAsFixed(0)} 米'),
            if (_locateError.isNotEmpty) ...[
              const SizedBox(height: 6),
              Text(_locateError,
                  style: const TextStyle(fontSize: 12, color: TmsTheme.accent2)),
            ],
          ]),
        ),
        const SizedBox(height: 12),

        // 围栏比对结果
        _buildGeoResult(cfg, distance, abnormal),

        // 异常原因 / 现场照
        if (abnormal) ...[
          const SizedBox(height: 12),
          _buildAbnormalForm(cfg),
        ],

        const SizedBox(height: 18),
        _submitting
            ? const Center(child: CircularProgressIndicator())
            : TmsButton.primary(
                _hasFix ? '确认到店打卡' : '无定位打卡（仅记时间）',
                onPressed: () => _submit(cfg),
              ),
        const SizedBox(height: 8),
        TmsButton.outline('稍后再打卡',
            onPressed: _submitting ? null : () => Navigator.pop(context, false)),
        const SizedBox(height: 20),
      ],
    );
  }

  /// 围栏比对结果卡：三档着色（正常 / 偏差 / 异常），无围栏时给降级说明。
  Widget _buildGeoResult(ArriveConfig cfg, double? distance, bool abnormal) {
    if (!_geoEnabled) {
      return MCard(
        leftBar: TmsTheme.muted,
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          const Row(children: [
            Icon(Icons.gps_off, size: 18, color: TmsTheme.muted),
            SizedBox(width: 6),
            Text('未启用围栏校验',
                style: TextStyle(
                    fontSize: 14, fontWeight: FontWeight.w700, color: TmsTheme.muted)),
          ]),
          const SizedBox(height: 6),
          Text(
            _hasStoreGeo
                ? '未取到本机定位，本次打卡只记录到达时间。'
                : '该门店未维护坐标，本次打卡只记录到达时间。可在签收页「修改门店定位」补录。',
            style: const TextStyle(fontSize: 12, color: TmsTheme.muted),
          ),
        ]),
      );
    }

    final d = distance!;
    final normal = d <= cfg.normalRadius;
    final color = normal
        ? TmsTheme.ok
        : (abnormal ? TmsTheme.bad : TmsTheme.accent2);
    final label = normal ? '正常范围' : (abnormal ? 'GPS 异常' : '存在偏差');

    return MCard(
      leftBar: color,
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Row(children: [
          Icon(normal ? Icons.check_circle : Icons.warning_amber,
              size: 18, color: color),
          const SizedBox(width: 6),
          Expanded(
            child: Text('距门店 ${d.toStringAsFixed(0)} 米',
                style: TextStyle(
                    fontSize: 15, fontWeight: FontWeight.w700, color: color)),
          ),
          if (normal)
            MTag.green(label)
          else if (abnormal)
            MTag.red(label)
          else
            MTag.orange(label),
        ]),
        const SizedBox(height: 6),
        Text(
          '正常范围 ≤ ${cfg.normalRadius.toStringAsFixed(0)} 米 · '
          '超过 ${cfg.warnRadius.toStringAsFixed(0)} 米判定异常',
          style: const TextStyle(fontSize: 11, color: TmsTheme.muted),
        ),
      ]),
    );
  }

  Widget _buildAbnormalForm(ArriveConfig cfg) {
    return MCard(
      leftBar: TmsTheme.bad,
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        const Text('定位异常说明（必填）',
            style: TextStyle(
                fontSize: 14, fontWeight: FontWeight.w700, color: TmsTheme.bad)),
        const SizedBox(height: 8),
        TextField(
          controller: _reasonCtrl,
          maxLines: 3,
          decoration: const InputDecoration(
            hintText: '如：门店实际搬迁 / 只能停在路口 / 定位漂移',
            border: OutlineInputBorder(),
            isDense: true,
            contentPadding: EdgeInsets.all(10),
          ),
          style: const TextStyle(fontSize: 13),
        ),
        if (cfg.photoRequired) ...[
          const SizedBox(height: 10),
          Row(children: [
            const Expanded(
              child: Text('现场照片（必传）',
                  style: TextStyle(
                      fontSize: 13, fontWeight: FontWeight.w700, color: TmsTheme.ink)),
            ),
            TmsButton.outline(_photo == null ? '拍照' : '重拍', onPressed: _pickPhoto),
          ]),
          if (_photo != null)
            Padding(
              padding: const EdgeInsets.only(top: 8),
              child: Text('已选：${_photo!.name}',
                  style: const TextStyle(fontSize: 12, color: TmsTheme.ok)),
            ),
        ],
      ]),
    );
  }

  void _toast(String msg) {
    if (!mounted) return;
    ScaffoldMessenger.of(context)
        .showSnackBar(SnackBar(content: Text(msg), duration: const Duration(seconds: 2)));
  }

  String _msg(Object e) => e.toString().replaceFirst('Exception: ', '');
}
