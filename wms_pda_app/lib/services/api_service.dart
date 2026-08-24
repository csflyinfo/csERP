import 'package:dio/dio.dart';
import '../config/app_config.dart';

/// 统一 dio 封装：Bearer 注入、{code,message,data} 解包、错误归一化。
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
        final t = _token;
        if (t.isNotEmpty) options.headers['Authorization'] = 'Bearer $t';
        handler.next(options);
      },
      onResponse: (response, handler) {
        final body = response.data;
        if (body is Map<String, dynamic>) {
          final code = body['code']?.toString() ?? '';
          if (code != '0' && code != '200') {
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

  void setToken(String t) => _token = t;
  String get token => _token;
  void clearToken() => _token = '';
  void onUnauthorized(void Function() cb) => _onUnauthorized = cb;

  /// 运行时改完服务地址后回写到已建好的 dio 单例。
  void applyBaseUrl() {
    dio.options.baseUrl = AppConfig.apiBase;
  }

  Future<dynamic> get(String path, {Map<String, dynamic>? query}) async {
    final r = await dio.get(path, queryParameters: query);
    return r.data;
  }

  Future<dynamic> post(String path, {Object? body}) async {
    final r = await dio.post(path, data: body);
    return r.data;
  }

  /// 把 DioException 翻译成中文提示，避免 PDA 屏幕冒 RawStackTrace。
  static String friendlyError(Object e) {
    if (e is DioException) {
      switch (e.type) {
        case DioExceptionType.connectionTimeout:
        case DioExceptionType.sendTimeout:
        case DioExceptionType.receiveTimeout:
          return '连接超时，请确认服务器地址与网络';
        case DioExceptionType.connectionError:
          return '无法连接服务器：${e.message}';
        case DioExceptionType.badResponse:
          final msg = e.response?.data is Map
              ? (e.response!.data['message']?.toString() ?? '服务端异常')
              : 'HTTP ${e.response?.statusCode}';
          return msg;
        case DioExceptionType.badCertificate:
          return '证书校验失败';
        case DioExceptionType.cancel:
          return '请求已取消';
        case DioExceptionType.unknown:
          return e.message ?? '网络异常';
        default:
          return e.message ?? '网络异常';
      }
    }
    return e.toString();
  }
}
