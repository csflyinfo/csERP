<script setup>
/**
 * 业务日结 RJ 单打印页（PRD-33，独立窗口，无菜单框架）。
 * 数据来自 /finance/day-close/ticket（日结头 + 当日关键合计 + 应收/应付/资金/商品定版台账 + 资金备注），
 * 加载后自动调起 window.print()；@media print 隐藏操作按钮。
 */
import { ref, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { dayCloseTicket } from '../../api/day-close.js'

const route = useRoute()
const data = ref(null)
const error = ref('')

/** 金额两位小数。 */
function money(v) {
  return Number(v || 0).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}
function day(v) { return v == null ? '' : String(v).slice(0, 10) }
function time(v) { return v == null ? '' : String(v).slice(0, 19) }
function yn(v) { return v === 'Y' ? '平衡' : '不平' }
function doPrint() { window.print() }
function doClose() { window.close() }

onMounted(async () => {
  const date = route.query.date
  if (!date) { error.value = '缺少 date 参数'; return }
  try {
    data.value = await dayCloseTicket(date)
    setTimeout(() => window.print(), 400)
  } catch (e) { error.value = '日结单加载失败：' + (e?.message || e) }
})
</script>

<template>
  <div class="print-page">
    <div v-if="error" class="err-box">{{ error }}</div>
    <template v-if="data">
      <div class="toolbar">
        <button class="btn primary" @click="doPrint">打印</button>
        <button class="btn" @click="doClose">关闭</button>
      </div>

      <div class="rj-sheet">
        <h1 class="title">业 务 日 结 单</h1>
        <div class="head-row">
          <span>日结单号：<b>{{ data.closeNo }}</b></span>
          <span>封单日期：<b>{{ day(data.closeDate) }}</b></span>
          <span>日结人：{{ data.closeName }}</span>
          <span>日结时间：{{ time(data.closeTime) }}</span>
        </div>
        <div class="head-row check-row">
          <span>DWS 对账：<b :class="data.dwsBalanced === 'Y' ? 'ok' : 'bad'">{{ yn(data.dwsBalanced) }}</b></span>
          <span>四项滚存勾稽：<b :class="data.tieBalanced === 'Y' ? 'ok' : 'bad'">{{ yn(data.tieBalanced) }}</b></span>
          <span>资金档案：{{ data.fundBalanced === 'Y' ? '一致' : '有差异（见备注）' }}</span>
          <span>跨阶挂账单：{{ data.pendingBillCount ?? 0 }} 张（未纳入本单）</span>
        </div>

        <div class="sec-title">一、当日关键合计（金额含税）</div>
        <table class="rj-table">
          <thead>
            <tr>
              <th>签收金额</th><th>收款核销</th><th>采购入库</th>
              <th>付款</th><th>入库成本</th><th>出库成本</th>
            </tr>
          </thead>
          <tbody>
            <tr>
              <td class="num">{{ money(data.salesSignedAmount) }}</td>
              <td class="num">{{ money(data.receiptAmount) }}</td>
              <td class="num">{{ money(data.purchaseAmount) }}</td>
              <td class="num">{{ money(data.paymentAmount) }}</td>
              <td class="num">{{ money(data.stockInAmount) }}</td>
              <td class="num">{{ money(data.stockOutAmount) }}</td>
            </tr>
          </tbody>
        </table>

        <div class="sec-title">二、客户应收定版台账（按客户汇总）</div>
        <table class="rj-table">
          <thead>
            <tr>
              <th>客户编码</th><th>客户名称</th><th class="num">应收总额</th>
              <th class="num">已收</th><th class="num">未收余额</th>
              <th class="num">预收余额</th><th class="num">预收(重分类)</th><th class="num">逾期</th><th class="num">单据数</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(r, i) in data.arDaily" :key="'ar' + i">
              <td>{{ r.customerCode }}</td><td>{{ r.customerName }}</td>
              <td class="num">{{ money(r.arAmount) }}</td><td class="num">{{ money(r.receivedAmount) }}</td>
              <td class="num">{{ money(r.unreceivedAmount) }}</td><td class="num">{{ money(r.advanceAccountBalance) }}</td>
              <td class="num">{{ money(r.advanceAmount) }}</td>
              <td class="num">{{ money(r.overdueAmount) }}</td><td class="num">{{ r.billCount }}</td>
            </tr>
            <tr v-if="!data.arDaily?.length"><td colspan="9" class="empty">无数据</td></tr>
          </tbody>
        </table>

        <div class="sec-title">三、供应商应付定版台账（按供应商汇总）</div>
        <table class="rj-table">
          <thead>
            <tr>
              <th>供应商编码</th><th>供应商名称</th><th class="num">应付总额</th>
              <th class="num">已付</th><th class="num">未付余额</th>
              <th class="num">预付(2202重分类)</th><th class="num">预付余额(1123账户)</th>
              <th class="num">费用余额(1221)</th>
              <th class="num">逾期</th><th class="num">单据数</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(r, i) in data.apDaily" :key="'ap' + i">
              <td>{{ r.supplierCode }}</td><td>{{ r.supplierName }}</td>
              <td class="num">{{ money(r.apAmount) }}</td><td class="num">{{ money(r.paidAmount) }}</td>
              <td class="num">{{ money(r.unpaidAmount) }}</td><td class="num">{{ money(r.prepaidAmount) }}</td>
              <td class="num">{{ money(r.prepayAccountBalance) }}</td><td class="num">{{ money(r.expenseAccountBalance) }}</td>
              <td class="num">{{ money(r.overdueAmount) }}</td><td class="num">{{ r.billCount }}</td>
            </tr>
            <tr v-if="!data.apDaily?.length"><td colspan="10" class="empty">无数据</td></tr>
          </tbody>
        </table>
        <div class="table-note">
          注：「预付(2202重分类)」为该供应商应付单负余额的报表重分类额；「预付余额(1123账户)」为供应商账户预付余额；
          资产负债表预付账款=两者之和，分列不要求相等。
        </div>

        <div class="sec-title">四、资金账户日余额（现金账户附实盘数）</div>
        <table class="rj-table">
          <thead>
            <tr>
              <th>账户编码</th><th>账户名称</th><th class="num">上日余额</th>
              <th class="num">当日收入</th><th class="num">当日支出</th>
              <th class="num">当日余额</th><th class="num">现金实盘数</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(r, i) in data.fundDaily" :key="'f' + i">
              <td>{{ r.fundAccountCode }}</td><td>{{ r.fundAccountName }}</td>
              <td class="num">{{ money(r.openBalance) }}</td><td class="num">{{ money(r.inAmount) }}</td>
              <td class="num">{{ money(r.outAmount) }}</td><td class="num">{{ money(r.closeBalance) }}</td>
              <td class="num">{{ r.cashCount == null ? '—' : money(r.cashCount) }}</td>
            </tr>
            <tr v-if="!data.fundDaily?.length"><td colspan="7" class="empty">无数据</td></tr>
          </tbody>
        </table>

        <div class="sec-title">五、商品收发存定版（按商品+仓库，数量为基本单位）</div>
        <div v-if="data.goodsSummary" class="goods-sum">
          <span>商品/仓行：<b>{{ data.goodsSummary.rowCount }}</b></span>
          <span>收入数量：<b>{{ money(data.goodsSummary.inQty) }}</b></span>
          <span>收入金额：<b>{{ money(data.goodsSummary.inAmount) }}</b></span>
          <span>成本调整：<b>{{ money(data.goodsSummary.adjustAmount) }}</b></span>
          <span>发出数量：<b>{{ money(data.goodsSummary.outQty) }}</b></span>
          <span>发出金额：<b>{{ money(data.goodsSummary.outAmount) }}</b></span>
          <span>签收净额：<b>{{ money(data.goodsSummary.signedAmount) }}</b></span>
          <span>毛利：<b>{{ money(data.goodsSummary.grossProfit) }}</b></span>
          <span>期末金额：<b>{{ money(data.goodsSummary.endingAmount) }}</b></span>
          <span v-if="data.goodsSummary.negativeCount > 0" class="bad">负库存行：{{ data.goodsSummary.negativeCount }}</span>
        </div>
        <table class="rj-table goods-table">
          <thead>
            <tr>
              <th>商品编码</th><th>商品名称</th><th>规格</th><th>仓库</th>
              <th class="num">期初数量</th><th class="num">收入数量</th><th class="num">收入金额</th>
              <th class="num">成本调整</th><th class="num">发出数量</th><th class="num">发出金额</th>
              <th class="num">签收净额</th><th class="num">毛利</th>
              <th class="num">期末数量</th><th class="num">期末金额</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(r, i) in data.goodsDaily" :key="'g' + i" :class="{ neg: r.negativeFlag === 'Y' }">
              <td>{{ r.goodsCode }}</td><td>{{ r.goodsName }}</td><td>{{ r.spec }}</td><td>{{ r.warehouse }}</td>
              <td class="num">{{ money(r.openingQty) }}</td>
              <td class="num">{{ money(r.inQty) }}</td><td class="num">{{ money(r.inAmount) }}</td>
              <td class="num">{{ money(r.adjustAmount) }}</td>
              <td class="num">{{ money(r.outQty) }}</td><td class="num">{{ money(r.outAmount) }}</td>
              <td class="num">{{ money(r.signedAmount) }}</td><td class="num">{{ money(r.grossProfit) }}</td>
              <td class="num">{{ money(r.endingQty) }}</td><td class="num">{{ money(r.endingAmount) }}</td>
            </tr>
            <tr v-if="!data.goodsDaily?.length"><td colspan="14" class="empty">无数据</td></tr>
          </tbody>
        </table>

        <div v-if="data.fundRemark" class="remark-box">
          <b>资金情况说明：</b>{{ data.fundRemark }}
        </div>

        <div class="sign-row">
          <span>日结：{{ data.closeName }}</span>
          <span>复核：</span>
          <span>出纳：</span>
          <span>日期：{{ day(data.closeDate) }}</span>
        </div>
        <div class="foot-note">
          本单为按日封单定版凭证：{{ day(data.closeDate) }} 及之前已生效业务单据已冻结，
          反日结须经角色权限授权并全程留痕；未审核单据不参与封单。
        </div>
      </div>
    </template>
  </div>
</template>

<style scoped>
.print-page { max-width: 1000px; margin: 0 auto; padding: 20px; background: #f5f5f5; }
.toolbar { display: flex; gap: 10px; justify-content: flex-end; margin-bottom: 14px; }
.btn { padding: 6px 18px; border: 1px solid #d9d9d9; border-radius: 6px; background: #fff; cursor: pointer; font-size: 13px; }
.btn.primary { background: #1d6fd1; color: #fff; border-color: #1d6fd1; }
.err-box { background: #fff1f0; border: 1px solid #ffa39e; color: #cf1322; padding: 16px; border-radius: 8px; }
.rj-sheet { background: #fff; padding: 32px 40px; border-radius: 4px; box-shadow: 0 1px 6px rgba(0,0,0,.08); }
.title { text-align: center; font-size: 22px; letter-spacing: 8px; margin: 4px 0 16px; }
.head-row { display: flex; justify-content: space-between; font-size: 13px; color: #333; margin-bottom: 8px; flex-wrap: wrap; gap: 4px; }
.check-row { background: #fafbfc; padding: 6px 10px; border-radius: 4px; }
.ok { color: #1a9e54; }
.bad { color: #d33; }
.sec-title { font-size: 14px; font-weight: 700; margin: 16px 0 6px; }
.rj-table { width: 100%; border-collapse: collapse; font-size: 12px; }
.rj-table th, .rj-table td { border: 1px solid #888; padding: 5px 7px; }
.rj-table th { background: #f5f5f5; font-weight: 600; text-align: center; }
.num { text-align: right; font-variant-numeric: tabular-nums; white-space: nowrap; }
.empty { text-align: center; color: #999; padding: 10px; }
.remark-box { margin-top: 14px; border: 1px solid #d0d0d0; padding: 8px 10px; font-size: 13px; border-radius: 4px; }
.goods-sum { display: flex; flex-wrap: wrap; gap: 6px 18px; background: #fafbfc; border: 1px solid #e3e3e3; border-radius: 4px; padding: 6px 10px; font-size: 12px; margin-bottom: 6px; }
.goods-table { font-size: 11px; }
.goods-table tr.neg td { color: #d33; }
.sign-row { display: flex; justify-content: space-around; margin-top: 30px; font-size: 13px; color: #333; }
.foot-note { margin-top: 16px; color: #666; font-size: 11px; text-align: center; line-height: 1.7; }
.table-note { color: #888; font-size: 11px; line-height: 1.6; margin: 4px 0 0; }
@media print {
  .print-page { background: #fff; padding: 0; max-width: none; }
  .toolbar { display: none; }
  .rj-sheet { box-shadow: none; padding: 0; }
}
</style>
