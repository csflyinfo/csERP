/// 今日任务相关模型（对接 /tms/app/today-tasks）。
class TodaySummary {
  final int dispatchCount;
  final int totalStore;
  final num totalQty;
  final int returnTaskCount;
  TodaySummary({this.dispatchCount = 0, this.totalStore = 0, this.totalQty = 0, this.returnTaskCount = 0});
  factory TodaySummary.fromJson(Map<String, dynamic> j) => TodaySummary(
        dispatchCount: j['dispatchCount'] as int? ?? 0,
        totalStore: j['totalStore'] as int? ?? 0,
        totalQty: j['totalQty'] as num? ?? 0,
        returnTaskCount: j['returnTaskCount'] as int? ?? 0,
      );
}

/// 调度单（今日任务中的调度单头部）。
class Dispatch {
  final String dispatchId;
  final String dispatchNo;
  final String dispatchDate;
  final String routeLine;
  final String vehiclePlate;
  final String status;
  final num loadedQty;
  final num returnQty;
  final int storeCount;
  Dispatch({
    required this.dispatchId,
    required this.dispatchNo,
    this.dispatchDate = '',
    this.routeLine = '',
    this.vehiclePlate = '',
    this.status = '',
    this.loadedQty = 0,
    this.returnQty = 0,
    this.storeCount = 0,
  });
  factory Dispatch.fromJson(Map<String, dynamic> j) => Dispatch(
        dispatchId: j['dispatchId']?.toString() ?? '',
        dispatchNo: j['dispatchNo']?.toString() ?? '',
        dispatchDate: j['dispatchDate']?.toString() ?? '',
        routeLine: j['routeLine']?.toString() ?? '',
        vehiclePlate: j['vehiclePlate']?.toString() ?? '',
        status: j['status']?.toString() ?? '',
        loadedQty: j['loadedQty'] as num? ?? 0,
        returnQty: j['returnQty'] as num? ?? 0,
        storeCount: j['storeCount'] as int? ?? 0,
      );
}

/// 配送明细行（发货单 + 退货单取货任务混合）。
class DispatchDetail {
  final String detailId;
  final String dispatchId;
  final String billType; // RECEIPT / RETURN
  final String billTypeText; // 发货 / 取退
  final String sourceBillNo;
  final String customerCode;
  final String customerName;
  final String customerAddress;
  final num qty;
  final int skuCount;
  final int seqNo;
  final String status;
  final String dispatchNo;

  /// 门店档案坐标（来自 base_customer，后端 LEFT JOIN 透传，未维护时为 null）。
  final double? longitude;
  final double? latitude;

  /// 门店联系人（来自 base_customer.contact_name / mobile），供「联系客户」拨号。
  final String contactName;
  final String contactMobile;

  /// 到达打卡结果（未打卡时 arriveTime 为空串）。
  final String arriveTime;
  final double? arriveDistance;
  final bool gpsAbnormal;

  /// 结算方式短文案（预付 / 货到付款 / 账期），门店未维护结算方式时为空串。
  final String settlementText;

  /// 是否需要司机当场收款：仅货到付款为 true，取货任务恒为 false。
  final bool needCollect;

  /// 本次上门应收金额（发货单 deliver_amount，含税）；取货任务恒为 0。
  final num receivableAmount;

  DispatchDetail({
    required this.detailId,
    required this.dispatchId,
    this.billType = 'RECEIPT',
    this.billTypeText = '发货',
    this.sourceBillNo = '',
    this.customerCode = '',
    this.customerName = '',
    this.customerAddress = '',
    this.qty = 0,
    this.skuCount = 0,
    this.seqNo = 0,
    this.status = '',
    this.dispatchNo = '',
    this.longitude,
    this.latitude,
    this.contactName = '',
    this.contactMobile = '',
    this.arriveTime = '',
    this.arriveDistance,
    this.gpsAbnormal = false,
    this.settlementText = '',
    this.needCollect = false,
    this.receivableAmount = 0,
  });

  factory DispatchDetail.fromJson(Map<String, dynamic> j) => DispatchDetail(
        detailId: j['detailId']?.toString() ?? '',
        dispatchId: j['dispatchId']?.toString() ?? '',
        billType: j['billType']?.toString() ?? 'RECEIPT',
        billTypeText: j['billTypeText']?.toString() ?? '发货',
        sourceBillNo: j['sourceBillNo']?.toString() ?? '',
        customerCode: j['customerCode']?.toString() ?? '',
        customerName: j['customerName']?.toString() ?? '',
        customerAddress: j['customerAddress']?.toString() ?? '',
        qty: j['qty'] as num? ?? 0,
        skuCount: j['skuCount'] as int? ?? 0,
        seqNo: j['seqNo'] as int? ?? 0,
        status: j['status']?.toString() ?? '',
        dispatchNo: j['dispatchNo']?.toString() ?? '',
        longitude: _toDouble(j['longitude']),
        latitude: _toDouble(j['latitude']),
        contactName: j['contactName']?.toString() ?? '',
        contactMobile: j['contactMobile']?.toString() ?? '',
        arriveTime: (j['arriveTime']?.toString() ?? '').replaceFirst('T', ' '),
        arriveDistance: _toDouble(j['arriveDistance']),
        gpsAbnormal: j['gpsAbnormal']?.toString() == 'Y',
        settlementText: j['settlementText']?.toString() ?? '',
        needCollect: j['needCollect'] == true,
        receivableAmount: j['receivableAmount'] as num? ?? 0,
      );

  /// 后端 DECIMAL 字段可能序列化为 num 或字符串，统一容错转换。
  static double? _toDouble(Object? v) {
    if (v == null) return null;
    if (v is num) return v.toDouble();
    return double.tryParse(v.toString());
  }

  bool get isReturn => billType == 'RETURN';

  /// 门店与本次定位都可用时才具备围栏比对条件。
  bool get hasGeo => longitude != null && latitude != null;

  /// 门店电话是否可拨；为空时「联系客户」按钮应置灰。
  bool get hasPhone => contactMobile.trim().isNotEmpty;

  /// 是否已到达打卡。
  bool get hasArrived => arriveTime.isNotEmpty;
}

/// 今日任务聚合。
class TodayTasks {
  final List<Dispatch> dispatches;
  final List<DispatchDetail> details;
  final TodaySummary summary;
  TodayTasks({this.dispatches = const [], this.details = const [], required this.summary});

  factory TodayTasks.fromJson(Map<String, dynamic> j) => TodayTasks(
        dispatches: (j['dispatches'] as List? ?? []).map((e) => Dispatch.fromJson(e as Map<String, dynamic>)).toList(),
        details: (j['details'] as List? ?? []).map((e) => DispatchDetail.fromJson(e as Map<String, dynamic>)).toList(),
        summary: TodaySummary.fromJson(j['summary'] as Map<String, dynamic>? ?? {}),
      );
}

/// 历史配送任务行（对接 /tms/app/trip/history）。
class TripHistory {
  final String tripId;
  final String tripNo;
  final String dispatchId;
  final String dispatchNo;
  final String vehiclePlate;
  final String routeLine;
  final String territory;
  final String tripDate;
  final String status;
  final String statusText;
  final int totalStore;
  final int deliveredStore;
  final num totalQty;
  final num deliveredQty;
  final num collectedAmount;
  final int progress;
  final String departTime;
  final String completeTime;

  TripHistory({
    required this.tripId,
    required this.tripNo,
    this.dispatchId = '',
    this.dispatchNo = '',
    this.vehiclePlate = '',
    this.routeLine = '',
    this.territory = '',
    this.tripDate = '',
    this.status = '',
    this.statusText = '',
    this.totalStore = 0,
    this.deliveredStore = 0,
    this.totalQty = 0,
    this.deliveredQty = 0,
    this.collectedAmount = 0,
    this.progress = 0,
    this.departTime = '',
    this.completeTime = '',
  });

  factory TripHistory.fromJson(Map<String, dynamic> j) => TripHistory(
        tripId: j['tripId']?.toString() ?? '',
        tripNo: j['tripNo']?.toString() ?? '',
        dispatchId: j['dispatchId']?.toString() ?? '',
        dispatchNo: j['dispatchNo']?.toString() ?? '',
        vehiclePlate: j['vehiclePlate']?.toString() ?? '',
        routeLine: j['routeLine']?.toString() ?? '',
        territory: j['territory']?.toString() ?? '',
        tripDate: (j['tripDate']?.toString() ?? '').split(' ').first,
        status: j['status']?.toString() ?? '',
        statusText: j['statusText']?.toString() ?? '',
        totalStore: (j['totalStore'] as num?)?.toInt() ?? 0,
        deliveredStore: (j['deliveredStore'] as num?)?.toInt() ?? 0,
        totalQty: j['totalQty'] as num? ?? 0,
        deliveredQty: j['deliveredQty'] as num? ?? 0,
        collectedAmount: j['collectedAmount'] as num? ?? 0,
        progress: (j['progress'] as num?)?.toInt() ?? 0,
        departTime: j['departTime']?.toString() ?? '',
        completeTime: j['completeTime']?.toString() ?? '',
      );

  bool get isCompleted => status == 'COMPLETED';
}

/// 历史任务汇总（全量统计，非当前页）。
class TripHistorySummary {
  final int tripCount;
  final int storeSum;
  final num qtySum;
  final num amountSum;
  TripHistorySummary({this.tripCount = 0, this.storeSum = 0, this.qtySum = 0, this.amountSum = 0});
  factory TripHistorySummary.fromJson(Map<String, dynamic> j) => TripHistorySummary(
        tripCount: (j['tripCount'] as num?)?.toInt() ?? 0,
        storeSum: (j['storeSum'] as num?)?.toInt() ?? 0,
        qtySum: j['qtySum'] as num? ?? 0,
        amountSum: j['amountSum'] as num? ?? 0,
      );
}

/// 一笔收款流水（对应门店级签收记录 tms_sign_record）。
///
/// 粒度是「一次上门一条」而非车次级汇总：司机跟调度对账时，
/// 差额必须能定位到具体哪家门店，车次汇总数对不上时无法追查。
class CollectRecord {
  final String signId;
  final String dispatchNo;
  final String sourceBillNo;
  final String customerName;
  final String billTypeText;
  final String signTypeText;
  final num collectAmount;
  final String payMethod;
  final String signTime;
  final String customerSigner;
  final String verified;
  final String verifiedText;

  const CollectRecord({
    this.signId = '',
    this.dispatchNo = '',
    this.sourceBillNo = '',
    this.customerName = '',
    this.billTypeText = '',
    this.signTypeText = '',
    this.collectAmount = 0,
    this.payMethod = '',
    this.signTime = '',
    this.customerSigner = '',
    this.verified = '',
    this.verifiedText = '',
  });

  factory CollectRecord.fromJson(Map<String, dynamic> j) => CollectRecord(
        signId: j['signId']?.toString() ?? '',
        dispatchNo: j['dispatchNo']?.toString() ?? '',
        sourceBillNo: j['sourceBillNo']?.toString() ?? '',
        customerName: j['customerName']?.toString() ?? '',
        billTypeText: j['billTypeText']?.toString() ?? '',
        signTypeText: j['signTypeText']?.toString() ?? '',
        collectAmount: j['collectAmount'] as num? ?? 0,
        payMethod: j['payMethod']?.toString() ?? '',
        signTime: j['signTime']?.toString() ?? '',
        customerSigner: j['customerSigner']?.toString() ?? '',
        verified: j['verified']?.toString() ?? '',
        verifiedText: j['verifiedText']?.toString() ?? '',
      );

  /// 已核销的钱不能再改，UI 用它决定是否显示为「已交割」灰色态
  bool get isApproved => verified == 'APPROVED';
  bool get isRejected => verified == 'REJECTED';

  /// 「2026-08-17 14:30」——秒对司机对账无意义，去掉减少一行挤压
  String get signTimeShort {
    if (signTime.length >= 16) return signTime.substring(0, 16);
    return signTime;
  }
}

/// 收款记录汇总（全量，非当前页）。
///
/// 现金与电子收款分开：司机交账时现金要点钞交财务，
/// 电子收款只需核对流水，混在一个总数里没法交割。
class CollectSummary {
  final int recordCount;
  final num amountSum;
  final num cashSum;
  final num onlineSum;
  const CollectSummary({this.recordCount = 0, this.amountSum = 0, this.cashSum = 0, this.onlineSum = 0});
  factory CollectSummary.fromJson(Map<String, dynamic> j) => CollectSummary(
        recordCount: (j['recordCount'] as num?)?.toInt() ?? 0,
        amountSum: j['amountSum'] as num? ?? 0,
        cashSum: j['cashSum'] as num? ?? 0,
        onlineSum: j['onlineSum'] as num? ?? 0,
      );
}

/// 收款记录分页结果。
class CollectRecordPage {
  final List<CollectRecord> records;
  final int total;
  final CollectSummary summary;
  const CollectRecordPage({this.records = const [], this.total = 0, this.summary = const CollectSummary()});
  factory CollectRecordPage.fromJson(Map<String, dynamic> j) => CollectRecordPage(
        records: (j['records'] as List? ?? [])
            .map((e) => CollectRecord.fromJson(e as Map<String, dynamic>))
            .toList(),
        total: (j['total'] as num?)?.toInt() ?? 0,
        summary: CollectSummary.fromJson(j['summary'] as Map<String, dynamic>? ?? {}),
      );
}

/// 历史任务分页结果。
class TripHistoryPage {
  final List<TripHistory> records;
  final int total;
  final TripHistorySummary summary;
  TripHistoryPage({this.records = const [], this.total = 0, required this.summary});

  factory TripHistoryPage.fromJson(Map<String, dynamic> j) => TripHistoryPage(
        records: (j['records'] as List? ?? [])
            .map((e) => TripHistory.fromJson(e as Map<String, dynamic>))
            .toList(),
        total: (j['total'] as num?)?.toInt() ?? 0,
        summary: TripHistorySummary.fromJson(j['summary'] as Map<String, dynamic>? ?? {}),
      );
}

/// 司机绩效统计（对应 /tms/app/driver/stats）。
///
/// signRate / gpsNormalRate 允许为 null：后端在分母为 0 时返回 null，
/// 表示「无数据」而非「0%」，UI 需展示为「—」，避免新司机被显示成 0 分。
class DriverStats {
  final int tripCount;
  final int totalStore;
  final int signedStore;
  final int rejectStore;
  final double signedQty;
  final double collectAmount;
  final int arriveStore;
  final double? signRate;
  final double? gpsNormalRate;

  const DriverStats({
    this.tripCount = 0,
    this.totalStore = 0,
    this.signedStore = 0,
    this.rejectStore = 0,
    this.signedQty = 0,
    this.collectAmount = 0,
    this.arriveStore = 0,
    this.signRate,
    this.gpsNormalRate,
  });

  factory DriverStats.fromJson(Map<String, dynamic> j) => DriverStats(
        tripCount: (j['tripCount'] as num?)?.toInt() ?? 0,
        totalStore: (j['totalStore'] as num?)?.toInt() ?? 0,
        signedStore: (j['signedStore'] as num?)?.toInt() ?? 0,
        rejectStore: (j['rejectStore'] as num?)?.toInt() ?? 0,
        signedQty: (j['signedQty'] as num?)?.toDouble() ?? 0,
        collectAmount: (j['collectAmount'] as num?)?.toDouble() ?? 0,
        arriveStore: (j['arriveStore'] as num?)?.toInt() ?? 0,
        signRate: (j['signRate'] as num?)?.toDouble(),
        gpsNormalRate: (j['gpsNormalRate'] as num?)?.toDouble(),
      );

  /// 百分比文本，无数据时返回「—」而不是「0%」。
  static String pct(double? v) => v == null ? '—' : '${v.toStringAsFixed(1)}%';
}
