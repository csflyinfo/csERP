/// 司机模型。
class Driver {
  final String token;
  final String driverId;
  final String driverCode;
  final String driverName;
  final String mobile;
  final String roleCode;

  Driver({
    required this.token,
    required this.driverId,
    required this.driverCode,
    required this.driverName,
    required this.mobile,
    required this.roleCode,
  });

  factory Driver.fromJson(Map<String, dynamic> j) => Driver(
        token: j['token']?.toString() ?? '',
        driverId: j['driverId']?.toString() ?? '',
        driverCode: j['driverCode']?.toString() ?? '',
        driverName: j['driverName']?.toString() ?? '',
        mobile: j['mobile']?.toString() ?? '',
        roleCode: j['roleCode']?.toString() ?? 'DRIVER',
      );
}
