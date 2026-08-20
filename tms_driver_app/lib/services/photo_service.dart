import 'package:flutter/services.dart';
import 'package:image_picker/image_picker.dart';

import '../config/app_config.dart';

/// 照片来源。
enum PhotoSource {
  /// 现场直拍，正常的留证方式。
  camera,

  /// 相机不可用时从相册选取的降级结果。
  ///
  /// 单独区分是为了让界面能明示来源：这类照片不具备「现场直拍」的取证强度，
  /// 不能和直拍照片在 UI 上混为一谈。
  gallery,
}

/// 拍照结果。
///
/// 刻意区分「用户主动取消」与「拍照失败」两种空结果：
///   - 取消是正常操作，再弹提示反而是打扰；
///   - 失败必须让司机看见原因，否则就是点了没反应。
class PhotoResult {
  /// 拍摄成功的文件；取消或失败时为 null。
  final XFile? file;

  /// 失败原因（面向司机的中文文案）；成功或用户取消时为 null。
  final String? error;

  /// 照片实际来源。失败或取消时无意义，固定为 [PhotoSource.camera]。
  final PhotoSource source;

  const PhotoResult._(this.file, this.error, this.source);

  const PhotoResult.success(XFile file, {PhotoSource source = PhotoSource.camera})
      : this._(file, null, source);

  /// 用户按返回键放弃拍照，不算异常。
  const PhotoResult.cancelled() : this._(null, null, PhotoSource.camera);

  const PhotoResult.failed(String error) : this._(null, error, PhotoSource.camera);

  bool get isSuccess => file != null;

  /// 需要向司机提示的失败。取消不在此列。
  bool get isFailed => error != null;

  /// 本张是相册降级选取，界面应据此给出提示。
  bool get isFallback => isSuccess && source == PhotoSource.gallery;

  /// 成功但需要向司机说明的附加信息；无需说明时为 null。
  ///
  /// 文案由服务统一给出而非各页面自拟：降级是全局行为，9 个拍照页若各写一遍
  /// 提示，既重复又容易漏（最初就只加了 2 处），口径还会逐渐漂移。
  String? get notice => isFallback ? '设备无相机，已从相册选取（仅调试环境允许）' : null;
}

/// 拍照服务：全端唯一的相机入口。
///
/// 抽出此服务的原因是一次线上问题：司机点「拍照」毫无反应。
/// 根因不在权限，而在调用约定——
///   `image_picker` 的 `ImageSource.camera` 并不自己驱动摄像头，而是发
///   `ACTION_IMAGE_CAPTURE` 隐式 Intent 交给系统相机代拍。当设备上没有
///   任何相机应用（部分模拟器镜像、精简 ROM）时，插件内部捕获
///   `ActivityNotFoundException` 后调用 `finishWithError`，Dart 侧收到的是
///   **抛出的 PlatformException**，而非返回 null。
///   原先各页面只写 `if (photo != null)` 且没有 try/catch，异常成了未捕获的
///   异步错误，只进日志不进界面，于是表现为「点击无反应」。
///
/// 因此这里统一收敛五件事：
///   1. 捕获 PlatformException 并翻译成司机能看懂的中文原因；
///   2. 区分「取消」与「失败」，避免误报打扰；
///   3. 调起相机前先经原生通道探测可用性（详见 [capture]）——真机实测发现，
///      部分模拟器上该 Intent 解析不到任何 Activity 时系统**不抛异常**，只
///      静默返回取消，仅靠捕获异常判断相机缺失会失效；
///   4. 相机不可用时按配置降级到相册，保证流程能走完；
///   5. 集中照片压缩参数，杜绝各页面参数漂移导致上传体积不一致。
class PhotoService {
  PhotoService._();
  static final PhotoService instance = PhotoService._();

  final ImagePicker _picker = ImagePicker();

  /// 原生能力探测通道，与 `MainActivity` 约定。
  static const MethodChannel _device = MethodChannel('com.erp.tms/device');

  /// 「设备无相机应用」的统一错误对象。
  ///
  /// 主动探测出的相机缺失与插件抛出的同类异常，走同一套文案与降级逻辑，
  /// 避免两条路径的提示口径分叉。
  static final PlatformException _noCameraError =
      PlatformException(code: 'no_available_camera');

  /// 相机应用探测结果缓存。
  ///
  /// 设备装没装相机应用在一次运行内不会变，而拍照页有 9 处、每页可连拍多张，
  /// 每次都过一次 Platform Channel 属于无谓开销。
  bool? _hasCameraApp;

  /// 设备上是否存在可响应拍照请求的相机应用。
  ///
  /// 探测失败时返回 true（按「有相机」处理）：宁可照常走直拍、让真实错误暴露，
  /// 也不能因为探测本身出问题就把所有设备都降级到相册。
  Future<bool> _cameraAvailable() async {
    if (_hasCameraApp != null) return _hasCameraApp!;
    try {
      _hasCameraApp = await _device.invokeMethod<bool>('hasCameraApp') ?? true;
    } catch (_) {
      _hasCameraApp = true;
    }
    return _hasCameraApp!;
  }

  /// 采集一张留证照片。
  ///
  /// 优先 [ImageSource.camera]，且正常情况下不提供相册入口：签收、退货、异常等
  /// 场景的照片是履约凭证，允许从相册选图等于允许事后补图，会破坏取证价值。
  ///
  /// 唯一例外是**相机确实不可用**（设备无相机应用）。此时若仍然直接失败，
  /// 签收全流程会被硬堵死，模拟器上根本无法联调。故在
  /// [AppConfig.allowGalleryFallback] 开启时降级到相册，并把来源标记为
  /// [PhotoSource.gallery] 交由界面明示，不静默混入直拍照片。
  ///
  /// 注意：权限被拒**不降级**。那是用户到设置里开权限就能解决的问题，
  /// 降级只会掩盖真实原因，让司机永远不知道该去开权限。
  Future<PhotoResult> capture() async {
    if (!await _cameraAvailable()) {
      // 相机应用缺失时系统只会静默返回取消，不抛异常。若照常发起直拍，
      // 界面会退化成「点了没反应」，因此这里必须提前分流：
      // 允许降级就走相册，不允许也要明确报错，不能沉默。
      if (AppConfig.allowGalleryFallback) {
        return _captureFromGallery(_noCameraError);
      }
      return PhotoResult.failed(_describe(_noCameraError));
    }
    try {
      final photo = await _pick(ImageSource.camera);
      return photo == null ? const PhotoResult.cancelled() : PhotoResult.success(photo);
    } on PlatformException catch (e) {
      if (_isCameraMissing(e) && AppConfig.allowGalleryFallback) {
        return _captureFromGallery(e);
      }
      return PhotoResult.failed(_describe(e));
    } catch (e) {
      // 兜底：插件在个别机型上可能抛出非 PlatformException（如临时文件写入失败）。
      // 宁可给一句笼统提示，也不能让界面继续保持沉默。
      return PhotoResult.failed('拍照失败，请重试（$e）');
    }
  }

  /// 相机不可用后的相册降级。
  ///
  /// 相册再失败时的报错口径按「哪条信息更可执行」来选：
  ///   - 相册权限被拒 → 报相册错误。这是用户到设置里开权限就能解决的，
  ///     若此时报「没有相机应用」，会把人引去装相机，越走越偏；
  ///   - 其他原因（如连相册应用也没有）→ 报相机的原始错误，
  ///     因为根因确实是「这台设备拍不了照」。
  Future<PhotoResult> _captureFromGallery(PlatformException cameraError) async {
    try {
      final photo = await _pick(ImageSource.gallery);
      return photo == null
          ? const PhotoResult.cancelled()
          : PhotoResult.success(photo, source: PhotoSource.gallery);
    } on PlatformException catch (e) {
      final actionable = e.code == 'photo_access_denied';
      return PhotoResult.failed(_describe(actionable ? e : cameraError));
    } catch (_) {
      return PhotoResult.failed(_describe(cameraError));
    }
  }

  Future<XFile?> _pick(ImageSource source) {
    return _picker.pickImage(
      source: source,
      maxWidth: AppConfig.photoMaxEdge.toDouble(),
      maxHeight: AppConfig.photoMaxEdge.toDouble(),
      imageQuality: AppConfig.photoQuality,
    );
  }

  /// 判断是否属于「设备没有可用相机应用」。
  ///
  /// 不同 image_picker 版本对该场景的错误码不完全一致，
  /// 故除 `no_available_camera` 外再按消息文本兜底匹配。
  bool _isCameraMissing(PlatformException e) {
    if (e.code == 'no_available_camera') return true;
    final msg = (e.message ?? '').toLowerCase();
    return msg.contains('no cameras available') || msg.contains('no camera');
  }

  /// 把插件错误码翻译成可执行的中文提示。
  ///
  /// 文案原则：告诉司机「下一步该做什么」，而不是只说「失败了」。
  String _describe(PlatformException e) {
    switch (e.code) {
      case 'no_available_camera':
        return '设备上没有可用的相机应用，无法拍照。请安装系统相机后重试';
      case 'camera_access_denied':
        return '相机权限被拒绝。请到「设置 - 应用 - 智速达 - 权限」中开启相机';
      case 'photo_access_denied':
        return '相册权限被拒绝。请到「设置 - 应用 - 智速达 - 权限」中开启存储/照片';
      case 'already_active':
        return '上一次拍照尚未结束，请稍候再试';
      case 'invalid_image':
        return '照片格式异常，请重新拍摄';
      default:
        if (_isCameraMissing(e)) {
          return '设备上没有可用的相机应用，无法拍照。请安装系统相机后重试';
        }
        return '拍照失败：${e.message ?? e.code}';
    }
  }
}
