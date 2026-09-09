<script setup>
/**
 * 总账 M9——凭证打印页（独立窗口，无菜单框架）。
 * 数据来自 /finance/gl/voucher/print-data（头 + 分录 + 合计 + 金额大写），
 * 加载后自动调起 window.print()；@media print 隐藏操作按钮。
 */
import { ref, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { post } from '../../api/client.js'

const route = useRoute()
const data = ref(null)
const error = ref('')

function money(v) {
  return Number(v || 0).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}
function dateStr(v) {
  return String(v || '').slice(0, 10)
}
function doPrint() { window.print() }
function doClose() { window.close() }

onMounted(async () => {
  const id = route.query.id
  if (!id) { error.value = '缺少凭证 id 参数'; return }
  try {
    data.value = await post('/finance/gl/voucher/print-data', { id })
    setTimeout(() => window.print(), 400)
  } catch (e) { error.value = '凭证加载失败：' + (e?.message || e) }
})
</script>

<template>
  <div class="print-page">
    <div v-if="error" class="err-box">{{ error }}</div>
    <template v-if="data">
      <div class="toolbar">
        <button class="btn primary" v-permission="'finance.gl.voucher.print'" @click="doPrint">打印</button>
        <button class="btn" @click="doClose">关闭</button>
      </div>

      <div class="voucher-sheet">
        <div v-if="data.companyName" class="company">{{ data.companyName }}</div>
        <h1 class="title">记 账 凭 证</h1>
        <div class="head-row">
          <span>日期：{{ dateStr(data.voucherDate) }}</span>
          <span class="vno">凭证号：{{ data.voucherNo || '（未审核）' }}</span>
          <span>附单据 {{ data.attachments || 0 }} 张</span>
        </div>

        <table class="voucher-table">
          <thead>
            <tr>
              <th class="col-summary">摘要</th>
              <th class="col-account">会计科目</th>
              <th class="col-aux">辅助核算</th>
              <th class="col-amount num">借方金额</th>
              <th class="col-amount num">贷方金额</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="e in data.entries" :key="e.lineNo">
              <td>{{ e.summary || data.summary || '' }}</td>
              <td><b>{{ e.accountCode }}</b> {{ e.accountName }}</td>
              <td class="aux">{{ e.auxText || '' }}</td>
              <td class="num">{{ Number(e.debitAmount) ? money(e.debitAmount) : '' }}</td>
              <td class="num">{{ Number(e.creditAmount) ? money(e.creditAmount) : '' }}</td>
            </tr>
            <tr class="total-row">
              <td colspan="3">合计（大写）：<b class="cn-amount">{{ data.amountCn }}</b></td>
              <td class="num"><b>{{ money(data.debitTotal) }}</b></td>
              <td class="num"><b>{{ money(data.creditTotal) }}</b></td>
            </tr>
          </tbody>
        </table>

        <div class="sign-row">
          <span>制单：{{ data.makerName || '' }}</span>
          <span>审核：{{ data.auditorName || '' }}</span>
          <span>记账：{{ data.posterName || '' }}</span>
          <span>出纳：</span>
        </div>
        <div class="foot-note" v-if="data.status !== '已过账'">
          ⚠ 当前凭证状态为「{{ data.status }}」，未过账凭证仅供核对，不作为正式入账依据。
        </div>
      </div>
    </template>
  </div>
</template>

<style scoped>
.print-page { max-width: 960px; margin: 0 auto; padding: 20px; background: #f5f5f5; font-family: 'Microsoft YaHei', sans-serif; }
.toolbar { display: flex; gap: 10px; justify-content: flex-end; margin-bottom: 14px; }
.btn { padding: 6px 18px; border: 1px solid #d9d9d9; border-radius: 6px; background: #fff; cursor: pointer; font-size: 13px; }
.btn.primary { background: #1677ff; color: #fff; border-color: #1677ff; }
.err-box { background: #fff1f0; border: 1px solid #ffa39e; color: #cf1322; padding: 16px; border-radius: 8px; }
.voucher-sheet { background: #fff; padding: 36px 44px; border-radius: 4px; box-shadow: 0 1px 6px rgba(0,0,0,.08); }
.company { text-align: center; font-size: 16px; font-weight: 600; color: #333; margin-bottom: 4px; }
.title { text-align: center; font-size: 24px; letter-spacing: 10px; margin: 4px 0 18px; }
.head-row { display: flex; justify-content: space-between; font-size: 14px; margin-bottom: 10px; color: #333; }
.vno { font-weight: 600; }
.voucher-table { width: 100%; border-collapse: collapse; font-size: 13px; }
.voucher-table th, .voucher-table td { border: 1px solid #333; padding: 7px 9px; vertical-align: top; }
.voucher-table th { background: #fafafa; font-weight: 600; text-align: center; }
.col-summary { width: 26%; }
.col-account { width: 24%; }
.col-aux { width: 18%; }
.col-amount { width: 16%; }
.num { text-align: right; font-variant-numeric: tabular-nums; white-space: nowrap; }
.aux { color: #666; font-size: 12px; }
.total-row td { font-size: 13px; }
.cn-amount { font-size: 15px; letter-spacing: 2px; }
.sign-row { display: flex; justify-content: space-around; margin-top: 28px; font-size: 13px; color: #333; }
.foot-note { margin-top: 18px; color: #d46b08; font-size: 12px; text-align: center; }
@media print {
  .print-page { background: #fff; padding: 0; max-width: none; }
  .toolbar { display: none; }
  .voucher-sheet { box-shadow: none; padding: 0; }
}
</style>
