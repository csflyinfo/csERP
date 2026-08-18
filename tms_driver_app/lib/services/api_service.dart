import 'dart:io';
import 'package:dio/dio.dart';
import 'package:flutter/foundation.dart';
import '../config/app_config.dart';
import 'connectivity_service.dart';
import 'local_db_service.dart';

/// dio 封装：统一注入 Bearer Token、超时、错误归一化。
///
/// 响应体约定：{ code:'0', message, data }，code===0 视为成功，返回 data。
///
/// 离线能力（P6）：
/// - enqueueOrPost(): 离线时自动入队 pending_actions，在线时直接发送
/// - enqueueOrUpload(): 离线时将文件路径入队，在线时上传
/// - 网络恢复后 SyncService 自动消费队列
class ApiService {
  ApiService._();
  static final ApiService instance = ApiService._();

  late final Dio dio = _build();

  Dio _build() {
    final d = Dio(BaseOptions(
      baseUrl: AppConfig.apiBase,
      connectTimeout: AppConfig.connectTimeout,
      receiveTimeout: AppConfig.receiveTimeout,
      headers: {'Content-Type': 'application/json'},
    ));
    d.interceptors.add(InterceptorsWrapper(
      onRequest: (options, handler) {
        final token = _token;
        if (token.isNotEmpty) {
          options.headers['Authorization'] = 'Bearer $token';
        }
        handler.next(options);
      },
      onResponse: (response, handler) {
        // 业务层归一化：code !== '0' 抛错，成功返回 data
        final body = response.data;
        if (body is Map<String, dynamic>) {
          final code = body['code']?.toString() ?? '';
          if (code != '0') {
            handler.reject(DioException(
              requestOptions: response.requestOptions,
              message: body['message']?.toString() ?? '请求失败',
              response: response,
            ));
            return;
          }
          response.data = body['data'];
        }
        handler.next(response);
      },
      onError: (e, handler) {
        if (e.response?.statusCode == 401) {
          _token = '';
          _onUnauthorized?.call();
        }
        handler.next(e);
      },
    ));
    return d;
  }

  String _token = '';
  void Function()? _onUnauthorized;

  void setToken(String token) => _token = token;
  String get token => _token;
  void clearToken() => _token = '';
  void setUnauthorizedHandler(void Function() cb) => _onUnauthorized = cb;

  /// POST 请求，返回 data 字段。
  Future<dynamic> post(String path, {Map<String, dynamic>? body}) async {
    final resp = await dio.post(path, data: body);
    return resp.data;
  }

  /// 上传图片（multipart），返回 {url, objectKey}。
  /// bizType: SIGN/RETURN/STORE/SETTLEMENT/SIGNATURE/REJECT/RESCHEDULE
  Future<Map<String, dynamic>> uploadImage(File imageFile, {String bizType = 'SIGN'}) async {
    final formData = FormData.fromMap({
      'file': await MultipartFile.fromFile(imageFile.path),
      'bizType': bizType,
    });
    final resp = await dio.post('/tms/app/upload/image', data: formData);
    return resp.data as Map<String, dynamic>;
  }

  // ==================== 离线感知方法（P6） ====================

  /// 离线感知 POST：在线时直接发送，离线时自动入队。
  ///
  /// 参数：
  /// - actionType: 操作类型（LOADING_START/SIGN/DEPART 等，用于日志和 UI 展示）
  /// - actionKey: 操作唯一标识（如 dispatchId/signId），用于去重
  /// - priority: 优先级 1(装车/发车) 2(签收) 3(照片) 4(定位) 5(其他)
  /// - onlineResult: 在线发送成功后的回调，返回需要缓存的数据
  ///
  /// 返回：
  /// - 在线成功：返回后端响应数据
  /// - 离线入队：返回 {'_offline': true, 'queued': true}
  Future<dynamic> enqueueOrPost({
    required String actionType,
    String? actionKey,
    required String path,
    Map<String, dynamic>? body,
    int priority = 5,
  }) async {
    if (ConnectivityService.instance.isOnline) {
      try {
        return await post(path, body: body);
      } catch (e) {
        // 在线但发送失败（如服务器超时），也入队稍后重试
        if (e is DioException && _isNetworkError(e)) {
          await _enqueue(actionType, actionKey, 'POST', path, body, null, null, priority);
          return {'_offline': true, 'queued': true};
        }
        rethrow;
      }
    } else {
      // 离线：入队
      await _enqueue(actionType, actionKey, 'POST', path, body, null, null, priority);
      return {'_offline': true, 'queued': true};
    }
  }

  /// 离线感知文件上传：在线时直接上传，离线时将文件路径入队。
  ///
  /// 返回：
  /// - 在线成功：返回 {url, objectKey}
  /// - 离线入队：返回 {'_offline': true, 'queued': true}（URL 暂不可用，同步后由队列处理）
  Future<Map<String, dynamic>> enqueueOrUpload({
    required String actionType,
    String? actionKey,
    required File file,
    String bizType = 'SIGN',
    int priority = 3,
  }) async {
    if (ConnectivityService.instance.isOnline) {
      try {
        return await uploadImage(file, bizType: bizType);
      } catch (e) {
        if (e is DioException && _isNetworkError(e)) {
          await _enqueue(actionType, actionKey, 'UPLOAD', '/tms/app/upload/image',
              null, file.path, bizType, priority);
          return {'_offline': true, 'queued': true};
        }
        rethrow;
      }
    } else {
      await _enqueue(actionType, actionKey, 'UPLOAD', '/tms/app/upload/image',
          null, file.path, bizType, priority);
      return {'_offline': true, 'queued': true};
    }
  }

  /// 判断是否为网络错误（可入队重试）。
  bool _isNetworkError(DioException e) {
    return e.type == DioExceptionType.connectionTimeout ||
        e.type == DioExceptionType.sendTimeout ||
        e.type == DioExceptionType.receiveTimeout ||
        e.type == DioExceptionType.connectionError ||
        e.type == DioExceptionType.unknown;
  }

  /// 判断字符串是否已是可直接入库的远端 URL。
  ///
  /// 离线延后上传的媒体在 body 里暂存的是本地绝对路径（Android 形如
  /// /data/user/0/.../cache/xxx.jpg），与 http(s) URL 天然可区分，
  /// SyncService 靠这个判据决定「这条要不要先补传」。
  static bool isRemoteUrl(String s) =>
      s.startsWith('http://') || s.startsWith('https://');

  /// 上传图片，离线时不抛异常而是返回本地路径占位。
  ///
  /// 为什么不能直接用 uploadImage：单据页的流程是「先传照片拿 URL、再提交主单」，
  /// uploadImage 在离线时会直接抛异常，被页面 catch 成「提交失败」，
  /// 结果主单的 enqueueOrPost 根本执行不到——离线入队形同虚设。
  ///
  /// 为什么不用 enqueueOrUpload：它把上传单独入队，同步成功后返回的 URL 无处可去，
  /// 主单里照片字段仍是空的，照片等于丢了。
  ///
  /// 这里改成返回本地路径，由主单 body 一起带入队列，
  /// SyncService 重放前会先把本地路径补传成真 URL 再提交，照片与主单不会脱钩。
  /// 返回值统一含 url 字段：在线是远端 URL，离线是本地绝对路径。
  Future<Map<String, dynamic>> uploadImageOrDefer(
    File imageFile, {
    String bizType = 'SIGN',
  }) async {
    if (ConnectivityService.instance.isOnline) {
      try {
        return await uploadImage(imageFile, bizType: bizType);
      } catch (e) {
        if (e is DioException && _isNetworkError(e)) {
          debugPrint('[Offline] 图片延后上传: ${imageFile.path}');
          return {'url': imageFile.path, '_deferred': true, 'bizType': bizType};
        }
        rethrow;
      }
    }
    debugPrint('[Offline] 图片延后上传: ${imageFile.path}');
    return {'url': imageFile.path, '_deferred': true, 'bizType': bizType};
  }

  /// 批量上传留证照片，有限并发 + 保序返回 URL 列表。
  ///
  /// 替代各单据页原先的 `for (p in _photos) await upload(p)` 串行写法：
  /// 串行时总耗时是各张之和，弱网下 4 张就能让「提交」按钮转好几十秒，
  /// 司机会误以为卡死而反复点击或强杀 APP。
  ///
  /// 并发数受 AppConfig.photoUploadConcurrency 限制而非全量并发：
  /// 现场多是弱网，连接开太多只会互相抢带宽、拉高单张超时概率。
  ///
  /// 返回列表严格与入参同序：照片顺序就是司机的拍摄顺序（先整体后细节），
  /// 用 Future.wait 的完成顺序会打乱它，所以按下标写回固定长度的数组。
  ///
  /// 任一张彻底失败（非网络原因）都会抛出，由页面提示重试；
  /// 网络原因失败则由 uploadImageOrDefer 降级为本地路径占位，不会中断提交。
  Future<List<String>> uploadImagesOrDefer(
    List<File> files, {
    String bizType = 'SIGN',
  }) async {
    if (files.isEmpty) return const [];
    final urls = List<String?>.filled(files.length, null);
    const concurrency = AppConfig.photoUploadConcurrency;
    for (var start = 0; start < files.length; start += concurrency) {
      final end = (start + concurrency).clamp(0, files.length);
      await Future.wait([
        for (var i = start; i < end; i++)
          uploadImageOrDefer(files[i], bizType: bizType)
              .then((res) => urls[i] = res['url'] as String),
      ]);
    }
    return urls.cast<String>();
  }

  /// 入队操作。
  Future<void> _enqueue(
    String actionType,
    String? actionKey,
    String method,
    String path,
    Map<String, dynamic>? body,
    String? filePath,
    String? bizType,
    int priority,
  ) async {
    try {
      await LocalDbService.instance.enqueueAction(
        actionType: actionType,
        actionKey: actionKey,
        method: method,
        path: path,
        body: body,
        filePath: filePath,
        bizType: bizType,
        priority: priority,
      );
      debugPrint('[Offline] 操作已入队: $actionType ($actionKey)');
    } catch (e) {
      debugPrint('[Offline] 入队失败: $e');
    }
  }
}
