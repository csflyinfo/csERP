import 'dart:async';
import 'package:connectivity_plus/connectivity_plus.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

/// 网络连接状态服务（P6 离线能力）。
///
/// 使用 connectivity_plus 监听网络变化：
/// - ConnectivityResult.none：完全离线
/// - ConnectivityResult.wifi / mobile / ethernet：在线
///
/// 全局状态通过 isOnlineProvider 暴露给 UI 和同步服务。
class ConnectivityService {
  ConnectivityService._();
  static final ConnectivityService instance = ConnectivityService._();

  final Connectivity _connectivity = Connectivity();
  StreamSubscription<ConnectivityResult>? _subscription;
  bool _isOnline = true;
  final _controller = StreamController<bool>.broadcast();

  /// 当前是否在线。
  bool get isOnline => _isOnline;

  /// 在线状态变化流。
  Stream<bool> get onOnlineChanged => _controller.stream;

  /// 初始化并开始监听（App 启动时调用）。
  Future<void> init() async {
    final result = await _connectivity.checkConnectivity();
    _updateOnline(result);
    _subscription = _connectivity.onConnectivityChanged.listen(_updateOnline);
  }

  void _updateOnline(ConnectivityResult result) {
    final wasOnline = _isOnline;
    _isOnline = result != ConnectivityResult.none;
    if (wasOnline != _isOnline) {
      _controller.add(_isOnline);
    }
  }

  /// 释放资源。
  void dispose() {
    _subscription?.cancel();
    _controller.close();
  }
}

/// 全局在线状态 Provider。
final isOnlineProvider = StreamProvider<bool>((ref) {
  return ConnectivityService.instance.onOnlineChanged;
});

/// 当前在线状态（同步读取）。
final currentOnlineProvider = Provider<bool>((ref) {
  return ConnectivityService.instance.isOnline;
});
