package com.erp.flow;

import com.erp.common.biz.BizState;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class BusinessFlowService {
    private final AtomicInteger serial = new AtomicInteger(1);
    private final Map<String, Map<String, Object>> stock = new LinkedHashMap<>();
    private final List<Map<String, Object>> stockLedger = new ArrayList<>();
    private final List<Map<String, Object>> arList = new ArrayList<>();
    private final List<Map<String, Object>> apList = new ArrayList<>();
    private final List<Map<String, Object>> fundLedger = new ArrayList<>();
    private final List<Map<String, Object>> customerPrices = new ArrayList<>();

    public Map<String, Object> runPurchaseCycle() {
        String purchaseOrderNo = no("PO");
        String inboundNo = no("PI");
        String receiptNo = no("PR");
        String apNo = no("AP");

        addStock("SP001", "农夫山泉500ml*24", "总仓", new BigDecimal("100"), new BigDecimal("31.20"), inboundNo);
        Map<String, Object> ap = map(
                "apNo", apNo,
                "supplier", "农夫山泉杭州经销",
                "sourceBill", receiptNo,
                "apAmount", "3500.00",
                "paidAmount", "0.00",
                "unpaidAmount", "3500.00",
                "status", BizState.UNVERIFIED
        );
        apList.add(ap);

        return map(
                "purchaseOrder", map("orderNo", purchaseOrderNo, "status", BizState.APPROVED, "effect", "形成采购在途"),
                "purchaseInbound", map("inboundNo", inboundNo, "status", BizState.APPROVED, "effect", "库存增加，生成库存流水，重算成本"),
                "purchaseReceipt", map("receiptNo", receiptNo, "status", BizState.APPROVED, "effect", "生成应付"),
                "ap", ap,
                "stock", stock.get("SP001@总仓")
        );
    }

    public Map<String, Object> runSalesCycle() {
        String orderNo = no("SO");
        String outboundNo = no("SOU");
        String receiptNo = no("SR");
        String arNo = no("AR");

        lockStock("SP001", "总仓", new BigDecimal("10"));
        outboundStock("SP001", "总仓", new BigDecimal("10"), outboundNo);
        Map<String, Object> ar = map(
                "arNo", arNo,
                "customer", "华联超市",
                "sourceBill", receiptNo,
                "arAmount", "350.00",
                "receivedAmount", "0.00",
                "unreceivedAmount", "350.00",
                "status", BizState.UNVERIFIED
        );
        arList.add(ar);

        return map(
                "salesOrder", map("orderNo", orderNo, "status", BizState.APPROVED, "effect", "锁定库存"),
                "salesOutbound", map("outboundNo", outboundNo, "status", BizState.APPROVED, "effect", "扣减库存，释放锁定，生成销售收货单"),
                "salesReceipt", map("receiptNo", receiptNo, "status", BizState.APPROVED, "effect", "生成应收"),
                "ar", ar,
                "stock", stock.get("SP001@总仓")
        );
    }

    public Map<String, Object> receiveAndVerifyAr() {
        if (arList.isEmpty()) {
            runSalesCycle();
        }
        Map<String, Object> ar = arList.get(arList.size() - 1);
        ar.put("receivedAmount", ar.get("arAmount"));
        ar.put("unreceivedAmount", "0.00");
        ar.put("status", BizState.VERIFIED);
        Map<String, Object> ledger = map(
                "ledgerNo", no("FUND"),
                "fundAccount", "工行基本户",
                "direction", "IN",
                "amount", ar.get("arAmount"),
                "sourceBill", ar.get("arNo"),
                "balanceAfter", "50650.00",
                "occurredAt", LocalDateTime.now().toString()
        );
        fundLedger.add(ledger);
        return map("ar", ar, "fundLedger", ledger);
    }

    public Map<String, Object> payAndVerifyAp() {
        if (apList.isEmpty()) {
            runPurchaseCycle();
        }
        Map<String, Object> ap = apList.get(apList.size() - 1);
        ap.put("paidAmount", ap.get("apAmount"));
        ap.put("unpaidAmount", "0.00");
        ap.put("status", BizState.VERIFIED);
        Map<String, Object> ledger = map(
                "ledgerNo", no("FUND"),
                "fundAccount", "工行基本户",
                "direction", "OUT",
                "amount", ap.get("apAmount"),
                "sourceBill", ap.get("apNo"),
                "balanceAfter", "46695.00",
                "occurredAt", LocalDateTime.now().toString()
        );
        fundLedger.add(ledger);
        return map("ap", ap, "fundLedger", ledger);
    }

    public Map<String, Object> auditCustomerPriceAdjust() {
        customerPrices.stream()
                .filter(item -> "C001".equals(item.get("customerCode")) && "SP001".equals(item.get("goodsCode")) && "EFFECTIVE".equals(item.get("status")))
                .forEach(item -> item.put("status", "STOPPED"));
        Map<String, Object> price = map(
                "priceId", "PRICE" + serial.getAndIncrement(),
                "adjustNo", no("CPA"),
                "customerCode", "C001",
                "customerName", "华联超市",
                "goodsCode", "SP001",
                "goodsName", "农夫山泉500ml*24",
                "originalPrice", "35.00",
                "currentPrice", "34.50",
                "effectiveMode", "IMMEDIATE",
                "validRange", "长期有效",
                "status", "EFFECTIVE"
        );
        customerPrices.add(price);
        return map("adjustStatus", BizState.APPROVED, "price", price, "effect", "最新调价生效，历史有效价自动停用");
    }

    public Map<String, Object> dashboard() {
        return map(
                "stock", new ArrayList<>(stock.values()),
                "stockLedger", stockLedger,
                "ar", arList,
                "ap", apList,
                "fundLedger", fundLedger,
                "customerPrices", customerPrices
        );
    }

    private void addStock(String goodsCode, String goodsName, String warehouse, BigDecimal qty, BigDecimal costPrice, String sourceBill) {
        String key = goodsCode + "@" + warehouse;
        Map<String, Object> row = stock.computeIfAbsent(key, k -> map(
                "goodsCode", goodsCode,
                "goodsName", goodsName,
                "warehouse", warehouse,
                "physicalQty", BigDecimal.ZERO,
                "lockedQty", BigDecimal.ZERO,
                "availableQty", BigDecimal.ZERO,
                "costPrice", costPrice,
                "stockAmount", BigDecimal.ZERO
        ));
        BigDecimal physical = ((BigDecimal) row.get("physicalQty")).add(qty);
        BigDecimal locked = (BigDecimal) row.get("lockedQty");
        row.put("physicalQty", physical);
        row.put("availableQty", physical.subtract(locked));
        row.put("costPrice", costPrice);
        row.put("stockAmount", physical.multiply(costPrice));
        stockLedger.add(map("ledgerNo", no("INV"), "sourceBill", sourceBill, "goodsCode", goodsCode, "direction", "IN", "qty", qty, "costPrice", costPrice));
    }

    private void lockStock(String goodsCode, String warehouse, BigDecimal qty) {
        Map<String, Object> row = stock.computeIfAbsent(goodsCode + "@" + warehouse, k -> map(
                "goodsCode", goodsCode,
                "goodsName", "农夫山泉500ml*24",
                "warehouse", warehouse,
                "physicalQty", new BigDecimal("100"),
                "lockedQty", BigDecimal.ZERO,
                "availableQty", new BigDecimal("100"),
                "costPrice", new BigDecimal("31.20"),
                "stockAmount", new BigDecimal("3120.00")
        ));
        BigDecimal physical = (BigDecimal) row.get("physicalQty");
        BigDecimal locked = ((BigDecimal) row.get("lockedQty")).add(qty);
        row.put("lockedQty", locked);
        row.put("availableQty", physical.subtract(locked));
    }

    private void outboundStock(String goodsCode, String warehouse, BigDecimal qty, String sourceBill) {
        Map<String, Object> row = stock.get(goodsCode + "@" + warehouse);
        if (row == null) {
            lockStock(goodsCode, warehouse, qty);
            row = stock.get(goodsCode + "@" + warehouse);
        }
        BigDecimal physical = ((BigDecimal) row.get("physicalQty")).subtract(qty);
        BigDecimal locked = ((BigDecimal) row.get("lockedQty")).subtract(qty);
        BigDecimal costPrice = (BigDecimal) row.get("costPrice");
        row.put("physicalQty", physical);
        row.put("lockedQty", locked.max(BigDecimal.ZERO));
        row.put("availableQty", physical.subtract((BigDecimal) row.get("lockedQty")));
        row.put("stockAmount", physical.multiply(costPrice));
        stockLedger.add(map("ledgerNo", no("INV"), "sourceBill", sourceBill, "goodsCode", goodsCode, "direction", "OUT", "qty", qty, "costPrice", costPrice));
    }

    private String no(String prefix) {
        return prefix + LocalDate.now().toString().replace("-", "") + String.format("%04d", serial.getAndIncrement());
    }

    private Map<String, Object> map(Object... values) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) {
            map.put(String.valueOf(values[i]), values[i + 1]);
        }
        return map;
    }
}
