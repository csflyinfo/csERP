<template>
  <div class="rpt-page">
    <div class="rpt-header">
      <div class="rpt-title">
        现金日记账
        <details class="rpt-help">
          <summary>取数说明</summary>
          <div class="rpt-help-pop">
            按资金账户逐笔登记的日记账：期初余额（期间首日前最近余额，回退账户档案余额）→ 逐笔流水
            （收入/支出、对方单位、收支项目、来源单号、经办人）→ 日小计 → 月/年合计，排序按账户+日期+时刻+流水号。
            系统独立滚算余额并与流水存量余额逐笔校验，差异超 1 分标红「MISMATCH」。
            红冲/取消审核流水为红字负行。默认期间自然月本月。金额按角色脱敏。
          </div>
        </details>
      </div>
      <div v-action-perms="actionHidden" class="rpt-ops">
        <button class="btn-plain" @click="exportCurrent">导出当前结果</button>
        <button class="btn-primary" @click="submitExport">异步导出</button>
      </div>
    </div>

    <ReportFilterBar v-model:start="start" v-model:end="end" initial-preset="thisMonth">
      <div class="ff">
        <label>资金账户</label>
        <input v-model="accountText" placeholder="多个账户用逗号分隔" @keyup.enter="onSearch" style="min-width:200px">
      </div>
      <div class="ff">
        <label>收支方向</label>
        <select v-model="filters.direction">
          <option value="">全部</option>
          <option value="IN">收入</option>
          <option value="OUT">支出</option>
        </select>
      </div>
      <div class="ff">
        <label>收支项目</label>
        <select v-model="filters.expenseItem">
          <option value="">全部</option>
          <option v-for="i in ITEMS" :key="i" :value="i">{{ i }}</option>
        </select>
      </div>
      <div class="ff"><label>对方单位</label><input v-model="filters.counterparty" @keyup.enter="onSearch"></div>
      <div class="ff"><label>来源单号</label><input v-model="filters.sourceBill" @keyup.enter="onSearch"></div>
      <template #actions>
        <button class="btn-primary" @click="onSearch">查询</button>
        <button class="btn-plain" @click="onReset">重置</button>
      </template>
    </ReportFilterBar>

    <DrillGridReport
      :columns="visibleColumns"
      :rows="rows"
      :summary="summary"
      :loading="loading"
      :total="total"
      :page-no="pageNo"
      :page-size="pageSize"
      :sort-field="sortField"
      :sort-order="sortOrder"
      :row-class-fn="rowClass"
      @sort="onSort"
    />
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import ReportFilterBar from '@/components/report/ReportFilterBar.vue'
import DrillGridReport from '@/components/report/DrillGridReport.vue'
import { useCenterReport } from '@/components/report/useCenterReport.js'
import { exportRowsXlsx, buildTreeRows } from '@/components/report/report-table.js'
import { useRbac } from '@/composables/useRbac.js'

const MODULE = 'fundJournalReport'
const CODE = 'fund_journal'
const STORE_KEY = 'rpt:fund_journal:state'
const ITEMS = ['收款', '付款', '冲销', '费用', '其他']
const { actionHidden, canViewColumn } = useRbac(MODULE)

const r = useCenterReport(CODE, { naturalMonth: true })
const { start, end, filters, pageNo, pageSize, sortField, sortOrder, rows, summary, total, loading } = r
const accountText = ref('')
pageSize.value = 100000

onMounted(() => {
  try {
    const saved = JSON.parse(localStorage.getItem(STORE_KEY) || '{}')
    Object.assign(filters.value, saved)
    accountText.value = saved.fundAccount || ''
  } catch { /* ignore */ }
  r.query().catch(() => {})
})

const ALL_COLUMNS = [
  { key: 'rowType', title: '行类型', width: 90 },
  { key: 'fundAccount', title: '资金账户', width: 150 },
  { key: 'bizDate', title: '日期', width: 100 },
  { key: 'ledgerNo', title: '单据/流水号', width: 160 },
  { key: 'sourceBill', title: '来源单号', width: 150 },
  { key: 'summary', title: '摘要', width: 180 },
  { key: 'counterpartyName', title: '对方单位', width: 170 },
  { key: 'expenseItem', title: '收支项目', width: 90 },
  { key: 'inAmount', title: '收入金额', num: true, money: true, width: 120 },
  { key: 'outAmount', title: '支出金额', num: true, money: true, width: 120 },
  { key: 'balance', title: '余额', num: true, money: true, width: 130 },
  { key: 'balanceCheck', title: '余额校验', width: 90 },
  { key: 'operatorName', title: '经办人', width: 90 },
]
const visibleColumns = computed(() => ALL_COLUMNS.filter(c => canViewColumn(MODULE, c.title)))

function rowClass(row) {
  if (row.balanceCheck === 'MISMATCH') return 'row-danger'
  if (['OPENING', 'DAY', 'MONTH', 'YEAR'].includes(row.rowType)) return 'row-subtotal'
  return ''
}

function onSearch() {
  pageNo.value = 1
  if (accountText.value.trim()) {
    filters.value.fundAccount = accountText.value.split(/[,，]/).map(s => s.trim()).filter(Boolean)
  } else {
    delete filters.value.fundAccount
  }
  localStorage.setItem(STORE_KEY, JSON.stringify({ ...filters.value, fundAccount: accountText.value }))
  r.query().catch(e => alert('查询失败：' + (e.message || '未知错误')))
}
function onReset() {
  filters.value = {}
  accountText.value = ''
  sortField.value = ''; sortOrder.value = ''
  localStorage.removeItem(STORE_KEY)
  r.resetDate()
  r.query().catch(e => alert('查询失败：' + (e.message || '未知错误')))
}
function onSort({ field, order }) {
  sortField.value = field
  sortOrder.value = order
  r.query().catch(() => {})
}
function filterText() {
  return `日期：${start.value}~${end.value}` + (accountText.value ? `；账户：${accountText.value}` : '')
}
function exportCurrent() {
  try {
    const nums = visibleColumns.value.filter(c => c.num).map(c => c.key)
    exportRowsXlsx({
      reportName: '现金日记账', filterText: filterText(),
      columns: visibleColumns.value, treeRows: buildTreeRows(rows.value, [], nums), summary: summary.value,
    })
  } catch (e) { alert('导出失败：' + (e.message || '未知错误')) }
}
async function submitExport() {
  try {
    const res = await r.exportAsync(filterText())
    alert(res.message + '（任务号 ' + res.taskNo + '）')
  } catch (e) { alert('导出失败：' + (e.message || '未知错误')) }
}
</script>

<style scoped>
@import './report-page.css';
.ff { display: flex; flex-direction: column; gap: 4px; font-size: 12px; color: #606266; }
.ff input, .ff select { height: 30px; border: 1px solid #dcdfe6; border-radius: 4px; padding: 0 8px; font-size: 13px; min-width: 120px; }
:deep(.row-danger) { background: #fff1f0 !important; color: #f5222d; font-weight: 600; }
:deep(.row-subtotal) { background: #fafafa; color: #606266; }
</style>
