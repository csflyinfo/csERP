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
      );

  bool get isReturn => billType == 'RETURN';
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
