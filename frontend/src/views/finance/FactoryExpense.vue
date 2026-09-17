<script setup>
/**
 * 厂家费用单列表（PRD-36 M3）。
 * JF：代垫（可关联客户费用单 FE）/ 其他两类；PENDING 可编辑/删除/审核，APPROVED 可反审核、可开红字。
 * 支持 Excel 导入（按供应商+日期+性质分组生成 PENDING 单）、明细导出、批量审核（左下角）。
 */
import { onMounted, ref } from 'vue'
import { post, downloadBlob, saveBlobFile } from '../../api/client.js'
import ImportDialog from '../../components/ImportDialog.vue'
import FactoryExpenseDrawer from '../../components/FactoryExpenseDrawer.vue'

const loading = ref(false)
const records = ref([])
const total = ref(0)
const pageNo = ref(1)
const pageSize = ref(20)
const feedback = ref(null)
const acting = ref(false)
const selected = ref(new Set())

// 查询条件（仅点「查询」生效，遵守全局查询约定）
const filters = ref({
  factoryExpenseNo: '',
  supplier: '',
  status: '',
  claimType: '',
  sourceMode: '',
  isRed: '',
  dateFrom: '',
  dateTo: '',
})

const drawer = ref(null)

// 导入弹窗
const importVisible = ref(false)
const importHeaders = ['供应商编码', '费用类型', '费用性质', '垫付客户编码', '关联客户费用单号',
  '费用日期', '金额', '厂家协议号/票据号', '贷方科目', '备注']
const importFieldMap = {
  供应商编码: 'supplierCode',
  费用类型: 'expenseTypeName',
  费用性质: 'claimTypeText',
  垫付客户编码: 'customerCode',
  关联客户费用单号: 'customerExpenseNo',
  费用日期: 'expenseDate',
  金额: 'amount',
  '厂家协议号/票据号': 'externalVoucherNo',
  贷方科目: 'glCreditSubject',
  备注: 'remark',
}

async function load() {
  loading.value = true
  feedback.value = null
  try {
    const res = await post('/finance/factory-expense/page', {
      pageNo: pageNo.value,
      pageSize: pageSize.value,
      ...filters.value,
    })
    records.value = res.records || []
    total.value = res.total || 0
    selected.value = new Set()
  } catch (e) {
    feedback.value = { level: 'error', msg: e?.message || '查询失败' }
  } finally {
    loading.value = false
  }
}

function doQuery() {
  pageNo.value = 1
  load()
}

function changePage(delta) {
  const next = pageNo.value + delta
  if (next < 1 || (next - 1) * pageSize.value >= total.value) return
  pageNo.value = next
  load()
}

function openCreate() {
  drawer.value.open('create', null)
}
function openView(row) {
  drawer.value.open('view', row)
}
function openEdit(row) {
  drawer.value.open('edit', row)
}

async function doAudit(row) {
  if (!window.confirm(`确认审核厂家费用单 ${row.factoryExpenseNo}？\n审核后立「其他应收款—厂家」费用债权。`)) return
  acting.value = true
  feedback.value = null
  try {
    await post('/finance/factory-expense/audit', { factoryExpenseId: row.factoryExpenseId })
    feedback.value = { level: 'ok', msg: '审核成功' }
    load()
  } catch (e) {
    feedback.value = { level: 'error', msg: e?.message || '审核失败' }
  } finally {
    acting.value = false
  }
}

async function doCancelAudit(row) {
  if (!window.confirm(`确认反审核厂家费用单 ${row.factoryExpenseNo}？\n费用债权将冲回，客户费用单关联同步释放。`)) return
  acting.value = true
  feedback.value = null
  try {
    await post('/finance/factory-expense/cancel-audit', { factoryExpenseId: row.factoryExpenseId })
    feedback.value = { level: 'ok', msg: '反审核成功' }
    load()
  } catch (e) {
    feedback.value = { level: 'error', msg: e?.message || '反审核失败' }
  } finally {
    acting.value = false
  }
}

async function doDelete(row) {
  if (!window.confirm(`确认删除厂家费用单 ${row.factoryExpenseNo}？仅待审核单据可删除。`)) return
  acting.value = true
  feedback.value = null
  try {
    await post('/finance/factory-expense/delete', { factoryExpenseId: row.factoryExpenseId })
    feedback.value = { level: 'ok', msg: '删除成功' }
    load()
  } catch (e) {
    feedback.value = { level: 'error', msg: e?.message || '删除失败' }
  } finally {
    acting.value = false
  }
}

/** 由已审核原单开红字：服务端默认按未兑现额等比摊负金额生成 PENDING 红字单，随后进抽屉调整/审核。 */
async function doRed(row) {
  if (!window.confirm(`确认对厂家费用单 ${row.factoryExpenseNo} 开具红字单？\n将按该单剩余未兑现额 ${fmt(row.unsettledAmount)} 元生成待审核红字单。`)) return
  acting.value = true
  feedback.value = null
  try {
    const res = await post('/finance/factory-expense/red-create', { redSourceNo: row.factoryExpenseNo })
    feedback.value = { level: 'ok', msg: `红字单 ${res.factoryExpenseNo} 已生成，请确认明细后审核` }
    await load()
    drawer.value.open('edit', { factoryExpenseId: res.factoryExpenseId })
  } catch (e) {
    feedback.value = { level: 'error', msg: e?.message || '红字单创建失败' }
  } finally {
    acting.value = false
  }
}

// ==================== 勾选 / 批量审核（左下角，仅 PENDING 合格行） ====================

function toggleRow(row, checked) {
  const next = new Set(selected.value)
  if (checked) next.add(row.factoryExpenseId)
  else next.delete(row.factoryExpenseId)
  selected.value = next
}
const pendingSelected = ref(0)
function refreshPendingCount() {
  pendingSelected.value = records.value
    .filter(r => r.status === 'PENDING' && selected.value.has(r.factoryExpenseId)).length
}
function onCheck(row, e) {
  toggleRow(row, e.target.checked)
  refreshPendingCount()
}
const allChecked = ref(false)
function onCheckAll(e) {
  const next = new Set(selected.value)
  for (const r of records.value) {
    if (e.target.checked) next.add(r.factoryExpenseId)
    else next.delete(r.factoryExpenseId)
  }
  selected.value = next
  allChecked.value = e.target.checked
  refreshPendingCount()
}

async function batchAudit() {
  const rows = records.value.filter(r => r.status === 'PENDING' && selected.value.has(r.factoryExpenseId))
  if (!rows.length) return
  if (!window.confirm(`确认批量审核选中的 ${rows.length} 张待审核厂家费用单？`)) return
  acting.value = true
  const ok = []
  const fail = []
  for (const r of rows) {
    try {
      await post('/finance/factory-expense/audit', { factoryExpenseId: r.factoryExpenseId })
      ok.push(r.factoryExpenseNo)
    } catch (e) {
      fail.push(`${r.factoryExpenseNo}：${e?.message || '审核失败'}`)
    }
  }
  feedback.value = fail.length
    ? { level: 'warn', msg: `成功 ${ok.length} 张，失败 ${fail.length} 张：${fail.join('；')}` }
    : { level: 'ok', msg: `批量审核成功 ${ok.length} 张` }
  acting.value = false
  load()
}

// ==================== 导入 / 导出 ====================

function onImportRows(rows, meta) {
  importVisible.value = false
  acting.value = true
  feedback.value = null
  post('/finance/factory-expense/import', { rows, fileName: meta?.fileName || '厂家费用导入.xlsx' })
    .then(res => {
      feedback.value = res.failed > 0
        ? { level: 'warn', msg: `导入完成：有效 ${res.success} 行，失败 ${res.failed} 行，生成 ${res.billCount} 张待审核单（失败明细见导入列表）` }
        : { level: 'ok', msg: `导入成功：${res.success} 行，生成 ${res.billCount} 张待审核单` }
      load()
    })
    .catch(e => { feedback.value = { level: 'error', msg: e?.message || '导入失败' } })
    .finally(() => { acting.value = false })
}

async function doExport() {
  acting.value = true
  feedback.value = null
  try {
    const blob = await downloadBlob('/finance/factory-expense/export', { ...filters.value })
    saveBlobFile('厂家费用单明细.xlsx', blob)
  } catch (e) {
    feedback.value = { level: 'error', msg: e?.message || '导出失败' }
  } finally {
    acting.value = false
  }
}

function fmt(v) {
  return (Number(v || 0)).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}
function fmtDate(v) {
  return v ? String(v).substring(0, 10) : ''
}
function fmtTime(v) {
  return v ? String(v).replace('T', ' ').substring(0, 19) : ''
}

onMounted(load)
</script>

<template>
  <div class="module-body">
    <div class="page-ops">
      <button class="btn primary" @click="doQuery">查询</button>
      <button class="btn" v-permission="'fin.factory_expense.add'" @click="openCreate">新建费用单</button>
      <button class="btn" v-permission="'fin.factory_expense.import'" @click="importVisible = true">导入</button>
      <button class="btn" v-permission="'fin.factory_expense.export'" :disabled="acting" @click="doExport">导出</button>
      <div class="spacer"></div>
    </div>

    <div class="query-panel">
      <div class="query-row">
        <label>费用单号
          <input v-model="filters.factoryExpenseNo" class="inp" placeholder="厂家费用单号"
                 @keyup.enter="doQuery" />
        </label>
        <label>供应商
          <input v-model="filters.supplier" class="inp" placeholder="供应商编号或名称"
                 @keyup.enter="doQuery" />
        </label>
        <label>状态
          <select v-model="filters.status" class="sel">
            <option value="">全部</option>
            <option value="PENDING">待审核</option>
            <option value="APPROVED">已审核</option>
          </select>
        </label>
        <label>费用性质
          <select v-model="filters.claimType" class="sel">
            <option value="">全部</option>
            <option value="ADVANCE">代垫</option>
            <option value="OTHER">其他</option>
          </select>
        </label>
        <label>来源方式
          <select v-model="filters.sourceMode" class="sel">
            <option value="">全部</option>
            <option value="MANUAL">手工录入</option>
            <option value="CUSTOMER_EXPENSE">客户费用单关联</option>
            <option value="IMPORT">Excel 导入</option>
          </select>
        </label>
        <label>单据
          <select v-model="filters.isRed" class="sel">
            <option value="">全部</option>
            <option value="N">普通单</option>
            <option value="Y">红字单</option>
          </select>
        </label>
        <label>费用日期
          <input type="date" v-model="filters.dateFrom" class="sel" />
          <span class="dash">至</span>
          <input type="date" v-model="filters.dateTo" class="sel" />
        </label>
      </div>
    </div>

    <div v-if="feedback" class="toast-inline" :class="feedback.level">{{ feedback.msg }}</div>

    <div class="table-wrap">
      <table class="data">
        <thead>
          <tr>
            <th style="width:36px"><input type="checkbox" :checked="allChecked" @change="onCheckAll" /></th>
            <th style="width:50px">序号</th>
            <th style="width:150px">费用单号</th>
            <th style="width:100px">供应商编号</th>
            <th>供应商名称</th>
            <th style="width:90px">费用日期</th>
            <th style="width:60px">性质</th>
            <th style="width:100px">来源方式</th>
            <th style="width:110px">费用金额</th>
            <th style="width:100px">已兑现</th>
            <th style="width:100px">未兑现</th>
            <th style="width:80px">兑现状态</th>
            <th style="width:70px">单据状态</th>
            <th style="width:90px">业务来源</th>
            <th style="width:130px">协议号/票据号</th>
            <th style="width:70px">经手人</th>
            <th style="width:140px">审核时间</th>
            <th style="width:230px">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-if="loading"><td colspan="18" class="empty">加载中…</td></tr>
          <tr v-else-if="!records.length"><td colspan="18" class="empty">暂无数据</td></tr>
          <tr v-for="(r, i) in records" :key="r.factoryExpenseId" :class="{ redrow: r.isRed === 'Y' }">
            <td class="c"><input type="checkbox" :checked="selected.has(r.factoryExpenseId)"
                                @change="onCheck(r, $event)" :disabled="r.status !== 'PENDING'" /></td>
            <td class="c">{{ (pageNo - 1) * pageSize + i + 1 }}</td>
            <td class="bill">
              {{ r.factoryExpenseNo }}
              <span v-if="r.isRed === 'Y'" class="red-flag">红</span>
            </td>
            <td>{{ r.supplierCode }}</td>
            <td>{{ r.supplierName }}</td>
            <td>{{ fmtDate(r.expenseDate) }}</td>
            <td class="c">{{ r.claimTypeText }}</td>
            <td>{{ r.sourceModeText }}</td>
            <td class="num strong" :class="{ neg: r.isRed === 'Y' }">{{ fmt(r.totalAmount) }}</td>
            <td class="num">{{ fmt(r.settledAmount) }}</td>
            <td class="num">{{ fmt(r.unsettledAmount) }}</td>
            <td class="c">{{ r.settleStatusText }}</td>
            <td class="c"><span class="tag" :class="r.status">{{ r.statusText }}</span></td>
            <td>{{ r.businessSourceText }}</td>
            <td>{{ r.externalVoucherNo || '—' }}</td>
            <td>{{ r.handler || '—' }}</td>
            <td>{{ fmtTime(r.auditTime) || '—' }}</td>
            <td class="ops">
              <a class="lk" @click="openView(r)">查看</a>
              <template v-if="r.status === 'PENDING'">
                <a class="lk" v-permission="'fin.factory_expense.edit'" @click="openEdit(r)">编辑</a>
                <a class="lk danger" v-permission="'fin.factory_expense.delete'" @click="doDelete(r)">删除</a>
                <a class="lk primary" v-permission="'fin.factory_expense.audit'" @click="doAudit(r)">审核</a>
              </template>
              <template v-else>
                <a class="lk warn" v-permission="'fin.factory_expense.unaudit'" @click="doCancelAudit(r)">反审核</a>
                <a v-if="r.isRed !== 'Y'" class="lk danger" v-permission="'fin.factory_expense.add'"
                   @click="doRed(r)">红字</a>
              </template>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <div class="pager">
      <button class="btn sm" :disabled="pageNo <= 1 || acting" @click="changePage(-1)">上一页</button>
      <span class="page-no">{{ pageNo }}</span>
      <button class="btn sm" :disabled="pageNo * pageSize >= total || acting" @click="changePage(1)">下一页</button>
      <span class="total-text">共 {{ total }} 条</span>
    </div>

    <!-- 左下角批量审核：仅勾选行中存在 PENDING 时出现 -->
    <div v-if="pendingSelected > 0" class="batch-bar">
      <span>已选 {{ pendingSelected }} 张待审核单</span>
      <button class="btn primary sm" :disabled="acting" @click="batchAudit">批量审核</button>
    </div>

    <ImportDialog v-if="importVisible"
                  :visible="importVisible"
                  title="厂家费用单导入"
                  :template-headers="importHeaders"
                  template-name="厂家费用单_导入模板"
                  :field-map="importFieldMap"
                  required-key="supplierCode"
                  template-url="/finance/factory-expense/import-template"
                  @close="importVisible = false"
                  @import="onImportRows" />

    <FactoryExpenseDrawer ref="drawer" @saved="load" />
  </div>
</template>

<style scoped>
.query-panel {
  background: var(--panel-bg, #fff);
  border: 1px solid var(--border-color, #e4e7ed);
  border-radius: 4px;
  padding: 12px 16px;
  margin-bottom: 10px;
}
.query-row { display: flex; flex-wrap: wrap; gap: 18px; align-items: center; }
.query-row label { display: flex; align-items: center; gap: 6px; font-size: 13px; color: #606266; white-space: nowrap; }
.dash { color: #909399; }
.sel, .inp {
  height: 28px; border: 1px solid #dcdfe6; border-radius: 4px;
  padding: 0 8px; font-size: 13px; min-width: 120px;
}
.table-wrap {
  background: var(--panel-bg, #fff);
  border: 1px solid var(--border-color, #e4e7ed);
  border-radius: 4px;
  overflow: auto;
}
table.data { width: 100%; border-collapse: collapse; font-size: 13px; }
table.data th, table.data td { border-bottom: 1px solid #ebeef5; padding: 8px 10px; text-align: left; white-space: nowrap; }
table.data th { background: #f5f7fa; color: #303133; font-weight: 600; text-align: center; }
table.data td.c { text-align: center; }
table.data td.num { text-align: right; font-variant-numeric: tabular-nums; }
.strong { font-weight: 600; color: #1f6fdd; }
.neg { color: #c45656; }
.bill { color: #1f6fdd; }
tr.redrow { background: #fffafa; }
.red-flag { background: #c45656; color: #fff; border-radius: 2px; padding: 0 4px; font-size: 11px; margin-left: 4px; }
.empty { text-align: center; color: #909399; padding: 30px 0 !important; }
.tag { padding: 1px 7px; border-radius: 3px; font-size: 12px; }
.tag.PENDING { background: #f4f4f5; color: #909399; }
.tag.APPROVED { background: #f0f9eb; color: #2f8f46; }
.ops { display: flex; gap: 8px; align-items: center; }
.lk { color: #1f6fdd; cursor: pointer; font-size: 12.5px; }
.lk.warn { color: #b88230; }
.lk.danger { color: #c45656; }
.pager { display: flex; align-items: center; gap: 8px; margin-top: 10px; }
.total-text { color: #909399; font-size: 12px; margin-left: 8px; }
.toast-inline { font-size: 13px; margin-bottom: 8px; padding: 6px 10px; border-radius: 4px; }
.toast-inline.warn { color: #b88230; background: #fdf6ec; }
.toast-inline.ok { color: #2f8f46; background: #f0f9eb; }
.toast-inner.error, .toast-inline.error { color: #c45656; background: #fef0f0; }
.batch-bar {
  position: fixed; left: 24px; bottom: 24px; z-index: 1500;
  background: #303133; color: #fff; border-radius: 6px;
  padding: 10px 14px; display: flex; align-items: center; gap: 12px; font-size: 13px;
  box-shadow: 0 6px 20px rgba(0,0,0,.25);
}
</style>
