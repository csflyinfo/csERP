// 今日任务相关模型（对接 /tms/app/today-tasks）。

/// 件数格式化：后端 qty 是 DECIMAL(18,4)，解析成 num 后整数会渲染成「2.0 件」。
/// 整数去掉小数位，非整数保留两位，避免司机看到「2.0 件」这类不自然的观感。
String fmtQty(num v) =>
    v == v.roundToDouble() ? v.toInt().toString() : v.toStringAsFixed(2);

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
  final String statusText;
  final num loadedQty;
  final num returnQty;
  final int storeCount;

  /// 是否为追加单：非空 parentDispatchId 表示这张单是挂到某趟在途车上的追加任务。
  ///
  /// 首页要把它和普通新单区分开：司机看到「追加」标记才知道这批货是加塞进来的，
  /// 装车时要留意车厢里已经压了原来的货。
  final bool appended;

  /// 首页卡片实时统计（由 /home/overview 的 fillDispatchCardStat 补齐）。
  ///
  /// storeCount 在 /home/overview 里会被 fillDispatchCardStat 按明细现算后覆写，
  /// 所以这里不再单开 cardStoreCount：同一个 key 拆两个字段只会让人以为有两套数。
  /// /today-tasks 走的是主表快照，字段名相同、口径略旧，卡片只在首页用，够用。
  final int receiptCount;
  final int returnCount;
  final num totalQty;
  final num collectAmount;

  Dispatch({
    required this.dispatchId,
    required this.dispatchNo,
    this.dispatchDate = '',
    this.routeLine = '',
    this.vehiclePlate = '',
    this.status = '',
    this.statusText = '',
    this.loadedQty = 0,
    this.returnQty = 0,
    this.storeCount = 0,
    this.appended = false,
    this.receiptCount = 0,
    this.returnCount = 0,
    this.totalQty = 0,
    this.collectAmount = 0,
  });
  factory Dispatch.fromJson(Map<String, dynamic> j) => Dispatch(
        dispatchId: j['dispatchId']?.toString() ?? '',
        dispatchNo: j['dispatchNo']?.toString() ?? '',
        dispatchDate: j['dispatchDate']?.toString() ?? '',
        routeLine: j['routeLine']?.toString() ?? '',
        vehiclePlate: j['vehiclePlate']?.toString() ?? '',
        status: j['status']?.toString() ?? '',
        statusText: j['statusText']?.toString() ?? '',
        loadedQty: j['loadedQty'] as num? ?? 0,
        returnQty: j['returnQty'] as num? ?? 0,
        storeCount: j['storeCount'] as int? ?? 0,
        appended: j['appended'] == true,
        receiptCount: j['receiptCount'] as int? ?? 0,
        returnCount: j['returnCount'] as int? ?? 0,
        totalQty: j['totalQty'] as num? ?? 0,
        collectAmount: j['collectAmount'] as num? ?? 0,
      );

  /// 是否还没接单：只有 ASSIGNED 才显示「接单」按钮。
  bool get canAccept => status == 'ASSIGNED';

  /// 是否已接单待装车/待发车：卡片要一直留在首页直到发车。
  bool get beforeDepart => status == 'ASSIGNED' || status == 'ACCEPTED' || status == 'LOADED';

  /// 卡片副标题：日期 · 线路 · 车牌，缺失项自动跳过不留空占位。
  String get subtitle => [
        if (dispatchDate.isNotEmpty) dispatchDate.length >= 10 ? dispatchDate.substring(0, 10) : dispatchDate,
        if (routeLine.isNotEmpty) routeLine,
        if (vehiclePlate.isNotEmpty) vehiclePlate,
      ].join(' · ');
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

  /// 件数展示文本（整数不带小数位）。
  String get qtyText => fmtQty(qty);
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

/// 首页概览（对接 /tms/app/home/overview）。
///
/// 与 TodayTasks 分开而不是复用：首页只要几个汇总数字和一条「下一站」，
/// TodayTasks 会把几十到上百行明细全部带回来，首屏没必要付这个代价。
class HomeOverview {
  /// 未完成调度单总数。
  final int dispatchCount;

  /// 未发车调度单（status 属于 ASSIGNED/ACCEPTED/LOADED），首页逐张出卡片。
  ///
  /// 为什么不继续用 pendingAccept：接单只是这张单的第一步，接完还要装车、发车。
  /// 若卡片只在 ASSIGNED 时显示，司机点完「接单」卡片就消失，后面找不到装车入口。
  /// 发车（DEPARTED）后这张单从本列表移出，转由【配送中】页按门店呈现。
  final List<Dispatch> pendingDispatches;

  /// 待接单调度单（status=ASSIGNED），是 pendingDispatches 的子集。
  ///
  /// 保留仅为兼容：后端两个字段都返回，新版 UI 一律读 pendingDispatches，
  /// 判断「能否接单」用 Dispatch.canAccept，不要再按列表归属去推状态。
  final List<Dispatch> pendingAccept;

  /// 当前作业调度单（最早一张已接单及之后的单），首页装车/发车按钮针对它。
  final String currentDispatchId;
  final String currentStatus;
  final String currentStatusText;

  /// 门店级任务量。
  final int totalStore;
  final int doneStore;
  final int pendingStore;

  /// 今日交账。
  final bool settledToday;
  final num cashAmount;
  final num returnAmount;
  final num submitAmount;

  /// 下一站门店（全部送完或未发车时为 null）。
  final HomeNextStore? nextStore;

  HomeOverview({
    this.dispatchCount = 0,
    this.pendingDispatches = const [],
    this.pendingAccept = const [],
    this.currentDispatchId = '',
    this.currentStatus = '',
    this.currentStatusText = '',
    this.totalStore = 0,
    this.doneStore = 0,
    this.pendingStore = 0,
    this.settledToday = false,
    this.cashAmount = 0,
    this.returnAmount = 0,
    this.submitAmount = 0,
    this.nextStore,
  });

  factory HomeOverview.fromJson(Map<String, dynamic> j) {
    final store = j['storeStat'] as Map<String, dynamic>? ?? {};
    final st = j['settlement'] as Map<String, dynamic>? ?? {};
    final next = j['nextStore'] as Map<String, dynamic>?;
    List<Dispatch> parse(String key) => (j[key] as List? ?? [])
        .map((e) => Dispatch.fromJson(e as Map<String, dynamic>))
        .toList();
    final pending = parse('pendingDispatches');
    return HomeOverview(
      dispatchCount: j['dispatchCount'] as int? ?? 0,
      // 老后端只有 pendingAccept 时退回它，新版 APP 装到旧服务上不至于首页空白
      pendingDispatches: pending.isNotEmpty ? pending : parse('pendingAccept'),
      pendingAccept: parse('pendingAccept'),
      currentDispatchId: j['currentDispatchId']?.toString() ?? '',
      currentStatus: j['currentStatus']?.toString() ?? '',
      currentStatusText: j['currentStatusText']?.toString() ?? '',
      totalStore: store['totalStore'] as int? ?? 0,
      doneStore: store['doneStore'] as int? ?? 0,
      pendingStore: store['pendingStore'] as int? ?? 0,
      settledToday: st['settledToday'] == true,
      cashAmount: st['cashAmount'] as num? ?? 0,
      returnAmount: st['returnAmount'] as num? ?? 0,
      submitAmount: st['submitAmount'] as num? ?? 0,
      nextStore: next == null ? null : HomeNextStore.fromJson(next),
    );
  }

  /// 是否可以开始装车：当前单停在已接单。
  bool get canLoad => currentStatus == 'ACCEPTED' || currentStatus == 'LOADED';

  /// 是否已在配送途中。
  bool get onRoad => currentStatus == 'DEPARTED' || currentStatus == 'DELIVERING';
}

/// 首页「下一站」门店。
class HomeNextStore {
  final String dispatchId;
  final String detailId;
  final String customerCode;
  final String customerName;
  final String customerAddress;
  final int seqNo;
  final String contactName;
  final String contactMobile;
  final double? longitude;
  final double? latitude;

  /// 该门店名下待办单数（同店多单时提示司机一次带齐）。
  final int billCount;

  HomeNextStore({
    this.dispatchId = '',
    this.detailId = '',
    this.customerCode = '',
    this.customerName = '',
    this.customerAddress = '',
    this.seqNo = 0,
    this.contactName = '',
    this.contactMobile = '',
    this.longitude,
    this.latitude,
    this.billCount = 0,
  });

  factory HomeNextStore.fromJson(Map<String, dynamic> j) => HomeNextStore(
        dispatchId: j['dispatchId']?.toString() ?? '',
        detailId: j['detailId']?.toString() ?? '',
        customerCode: j['customerCode']?.toString() ?? '',
        customerName: j['customerName']?.toString() ?? '',
        customerAddress: j['customerAddress']?.toString() ?? '',
        seqNo: j['seqNo'] as int? ?? 0,
        contactName: j['contactName']?.toString() ?? '',
        contactMobile: j['contactMobile']?.toString() ?? '',
        longitude: DispatchDetail._toDouble(j['longitude']),
        latitude: DispatchDetail._toDouble(j['latitude']),
        billCount: (j['billCount'] as num? ?? 0).toInt(),
      );

  bool get hasGeo => longitude != null && latitude != null;
  bool get hasPhone => contactMobile.trim().isNotEmpty;
}

/// 配送中门店行（对接 /tms/app/delivering/stores）。
///
/// 与 DispatchDetail 的区别：那是「单据」，这是「门店」。
/// 同一门店可能有多张单，配送中列表按门店展示才和司机实际跑店动作一致，
/// 单据明细留给配送点详情页。
class DeliveringStore {
  final String dispatchId;
  final String dispatchNo;
  final String vehiclePlate;
  final String customerCode;
  final String customerName;
  final String customerAddress;
  final String contactName;
  final String contactMobile;
  final double? longitude;
  final double? latitude;
  final String settlementText;
  final int seqNo;

  /// 列表展示序号：由后端按最终可见顺序从 1 连续编号。
  ///
  /// 不能直接用 seqNo 展示——它是「每张调度单内」的排序值，跨调度单按门店
  /// 合并后会重号（两家店都可能是 10），且过滤掉已完成门店后还会断号。
  final int orderNo;

  /// 到达打卡时间（该门店任一单据已打卡即有值）。
  final String arriveTime;

  /// 到达打卡的落点单据：后端给出的该店首张未签收单，供「到达」按钮直接调用。
  final String arriveDetailId;

  final int billCount;
  final int returnCount;
  final num totalQty;
  final num totalAmount;
  final int pendingCount;
  final bool hasReturn;
  final bool needCollect;

  /// DONE（名下单据全部处理完） / PENDING。
  final String storeStatus;

  DeliveringStore({
    this.dispatchId = '',
    this.dispatchNo = '',
    this.vehiclePlate = '',
    this.customerCode = '',
    this.customerName = '',
    this.customerAddress = '',
    this.contactName = '',
    this.contactMobile = '',
    this.longitude,
    this.latitude,
    this.settlementText = '',
    this.seqNo = 0,
    this.orderNo = 0,
    this.arriveTime = '',
    this.arriveDetailId = '',
    this.billCount = 0,
    this.returnCount = 0,
    this.totalQty = 0,
    this.totalAmount = 0,
    this.pendingCount = 0,
    this.hasReturn = false,
    this.needCollect = false,
    this.storeStatus = 'PENDING',
  });

  factory DeliveringStore.fromJson(Map<String, dynamic> j) => DeliveringStore(
        dispatchId: j['dispatchId']?.toString() ?? '',
        dispatchNo: j['dispatchNo']?.toString() ?? '',
        vehiclePlate: j['vehiclePlate']?.toString() ?? '',
        customerCode: j['customerCode']?.toString() ?? '',
        customerName: j['customerName']?.toString() ?? '',
        customerAddress: j['customerAddress']?.toString() ?? '',
        contactName: j['contactName']?.toString() ?? '',
        contactMobile: j['contactMobile']?.toString() ?? '',
        longitude: DispatchDetail._toDouble(j['longitude']),
        latitude: DispatchDetail._toDouble(j['latitude']),
        settlementText: j['settlementText']?.toString() ?? '',
        seqNo: (j['seqNo'] as num? ?? 0).toInt(),
        orderNo: (j['orderNo'] as num? ?? 0).toInt(),
        arriveTime: (j['arriveTime']?.toString() ?? '').replaceFirst('T', ' '),
        arriveDetailId: j['arriveDetailId']?.toString() ?? '',
        billCount: (j['billCount'] as num? ?? 0).toInt(),
        returnCount: (j['returnCount'] as num? ?? 0).toInt(),
        totalQty: j['totalQty'] as num? ?? 0,
        totalAmount: j['totalAmount'] as num? ?? 0,
        pendingCount: (j['pendingCount'] as num? ?? 0).toInt(),
        hasReturn: j['hasReturn'] == true,
        needCollect: j['needCollect'] == true,
        storeStatus: j['storeStatus']?.toString() ?? 'PENDING',
      );

  bool get hasGeo => longitude != null && latitude != null;
  bool get hasPhone => contactMobile.trim().isNotEmpty;
  bool get hasArrived => arriveTime.isNotEmpty;
  bool get done => storeStatus == 'DONE';

  /// 件数展示文本（整数不带小数位）。
  String get qtyText => fmtQty(totalQty);
}

/// 配送中门店列表 + 汇总。
class DeliveringStores {
  final List<DeliveringStore> stores;
  final int storeCount;
  final int pendingStore;
  final int doneStore;
  final num totalAmount;

  /// 未完成单据数（与列表里能点开的单据数一致）。
  final int billCount;

  /// 本趟车的单据总数（含已签收），只用于进度类展示。
  ///
  /// 与 billCount 分成两个字段是因为二者分母不同：billCount 回答「还要送几单」，
  /// totalBillCount 回答「今天一共几单」。合成一个字段的话，
  /// 顶部统计要么在签收后越送越少显得任务缩水，要么永远不动看不出进度。
  final int totalBillCount;

  DeliveringStores({
    this.stores = const [],
    this.storeCount = 0,
    this.pendingStore = 0,
    this.doneStore = 0,
    this.totalAmount = 0,
    this.billCount = 0,
    this.totalBillCount = 0,
  });

  factory DeliveringStores.fromJson(Map<String, dynamic> j) {
    final s = j['summary'] as Map<String, dynamic>? ?? {};
    return DeliveringStores(
      stores: (j['stores'] as List? ?? [])
          .map((e) => DeliveringStore.fromJson(e as Map<String, dynamic>))
          .toList(),
      storeCount: (s['storeCount'] as num? ?? 0).toInt(),
      pendingStore: (s['pendingStore'] as num? ?? 0).toInt(),
      doneStore: (s['doneStore'] as num? ?? 0).toInt(),
      totalAmount: s['totalAmount'] as num? ?? 0,
      billCount: (s['billCount'] as num? ?? 0).toInt(),
      totalBillCount: (s['totalBillCount'] as num? ?? 0).toInt(),
    );
  }
}

/// 配送点详情：某门店在某调度单下的全部单据（对接 /tms/app/delivering/store-bills）。
///
/// bills 直接复用 DispatchDetail：后端已保证该接口的行字段与 /today-tasks
/// 的 details 完全一致，再造一个近乎同构的模型只会让两边字段慢慢漂移。
class StoreBills {
  final List<DispatchDetail> bills;

  /// 门店抬头（后端从首行提取，列表为空时各字段为空串）。
  final String customerCode;
  final String customerName;
  final String customerAddress;
  final String contactName;
  final String contactMobile;
  final String dispatchNo;

  final int billCount;
  final int returnCount;
  final int pendingCount;
  final num totalQty;
  final num totalAmount;

  StoreBills({
    this.bills = const [],
    this.customerCode = '',
    this.customerName = '',
    this.customerAddress = '',
    this.contactName = '',
    this.contactMobile = '',
    this.dispatchNo = '',
    this.billCount = 0,
    this.returnCount = 0,
    this.pendingCount = 0,
    this.totalQty = 0,
    this.totalAmount = 0,
  });

  factory StoreBills.fromJson(Map<String, dynamic> j) {
    final s = j['summary'] as Map<String, dynamic>? ?? {};
    final st = j['store'] as Map<String, dynamic>? ?? {};
    return StoreBills(
      bills: (j['bills'] as List? ?? [])
          .map((e) => DispatchDetail.fromJson(e as Map<String, dynamic>))
          .toList(),
      customerCode: st['customerCode']?.toString() ?? '',
      customerName: st['customerName']?.toString() ?? '',
      customerAddress: st['customerAddress']?.toString() ?? '',
      contactName: st['contactName']?.toString() ?? '',
      contactMobile: st['contactMobile']?.toString() ?? '',
      dispatchNo: st['dispatchNo']?.toString() ?? '',
      billCount: (s['billCount'] as num? ?? 0).toInt(),
      returnCount: (s['returnCount'] as num? ?? 0).toInt(),
      pendingCount: (s['pendingCount'] as num? ?? 0).toInt(),
      totalQty: s['totalQty'] as num? ?? 0,
      totalAmount: s['totalAmount'] as num? ?? 0,
    );
  }

  /// 未签收的发货单（改派返仓只针对发货单：退货单是往回收货，没有「返仓改派」语义）。
  List<DispatchDetail> get reschedulable =>
      bills.where((b) => !b.isReturn && b.status == 'PENDING').toList();

  /// 门店坐标取首行（同店各行的坐标来自同一条 base_customer 记录）。
  double? get longitude => bills.isEmpty ? null : bills.first.longitude;
  double? get latitude => bills.isEmpty ? null : bills.first.latitude;

  bool get hasPhone => contactMobile.trim().isNotEmpty;
  bool get hasGeo => longitude != null && latitude != null;

  /// 件数展示文本（整数不带小数位）。
  String get qtyText => fmtQty(totalQty);

  /// 该门店是否已到达打卡（任一行有打卡时间即算）。
  bool get hasArrived => bills.any((b) => b.hasArrived);

  /// 打卡落点行：优先取已打卡的那条，否则取首张未签收单。
  String get arriveDetailId {
    for (final b in bills) {
      if (b.hasArrived) return b.detailId;
    }
    for (final b in bills) {
      if (b.status == 'PENDING') return b.detailId;
    }
    return bills.isEmpty ? '' : bills.first.detailId;
  }

  /// 门店级动作（定位修改、现场退货等）该挂到哪张调度单上。
  ///
  /// 跨单合并后同一家店的单据可能分属原单与追加单，页面不能再持有
  /// 单一 dispatchId。这里取「下一张要送的单」所属调度单：留痕落在
  /// 司机当前实际在处理的那趟车上，比落在已签完的旧单上更有追溯意义。
  String get primaryDispatchId {
    for (final b in bills) {
      if (b.status == 'PENDING') return b.dispatchId;
    }
    return bills.isEmpty ? '' : bills.first.dispatchId;
  }

  /// 按单据行反查其所属调度单，供 /arrive 等以 detailId 为主键的接口传参。
  String dispatchIdOf(String detailId) {
    for (final b in bills) {
      if (b.detailId == detailId) return b.dispatchId;
    }
    return primaryDispatchId;
  }
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
