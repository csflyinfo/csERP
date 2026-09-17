<script setup>
/**
 * 厂家费用兑现单列表（PRD-36 M3）。
 * 一单一方式：CASH 现金结算 / OFFSET 冲应付（账扣）/ OTHER 其他核销（1123/1405/5601、5602/5711）。
 * PENDING 可编辑/删除/审核（支持左下角批量审核），APPROVED 可反审核；支持扣款通知单打印与导出。
 */
import { onMounted, ref } from 'vue'
import { post, downloadBlob, saveBlobFile } from '../../api/client.js'
import FactorySettleDrawer from '../../components/FactorySettleDrawer.vue'

const loading = ref(false)
const records = ref([])
const total = ref(0)
const pageNo = ref(1)
const pageSize = ref(20)
const feedback = ref(null)
const selected = ref(new Set())

const filters = ref({
  settleNo: '',
  supplier: '',
  status: '',
  settleType: '',
  dateFrom: '',
  dateTo: '',
})

const drawer = ref(null)

// 扣款通知单打印
const printVisible = ref(false)
const printData = ref(null)
const printLoading = ref(false)

async function load() {
  loading.value = true
  feedback.value = null
  try {
    const res = await post('/finance/factory-settle/page', {
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

function openCreate() { drawer.value.open('create', null) }
function openView(row) { drawer.value.open('view', row) }
function openEdit(row) { drawer.value.open('edit', row) }

async function callAction(path, id, okMsg, errMsg, confirmMsg) {
  if (confirmMsg && !window.confirm(confirmMsg)) return
  feedback.value = null
  try {
    await post(path, id)
    feedback.value = { level: 'ok', msg: okMsg }
    load()
  } catch (e) {
    feedback.value = { level: 'error', msg: e?.message || errMsg }
  }
}

function doAudit(row) {
  return callAction('/finance/factory-settle/audit', { settleId: row.settleId }, '审核成功', '审核失败',
    `确认审核兑现单 ${row.settleNo}？\n审核后费用兑现即刻生效。`)
}
function doCancelAudit(row) {
  return callAction('/finance/factory-settle/cancel-audit', { settleId: row.settleId }, '反审核成功', '反审核失败',
    `确认反审核兑现单 ${row.settleNo}？\n资金/应付/费用余额将全部按真值冲回。`)
}
function doDelete(row) {
  return callAction('/finance/factory-settle/delete', { settleId: row.settleId }, '删除成功', '删除失败',
    `确认删除兑现单 ${row.settleNo}？仅待审核单据可删除。`)
}

// ==================== 勾选 / 批量审核 ====================

const pendingSelected = ref(0)
function onCheck(row, e) {
  const next = new Set(selected.value)
  if (e.target.checked) next.add(row.settleId)
  else next.delete(row.settleId)
  selected.value = next
  pendingSelected.value = records.value
    .filter(r => r.status === 'PENDING' && next.has(r.settleId)).length
}
const allChecked = ref(false)
function onCheckAll(e) {
  const next = new Set(selected.value)
  for (const r of records.value) {
    if (e.target.checked) next.add(r.settleId)
    else next.delete(r.settleId)
  }
  selected.value = next
  allChecked.value = e.target.checked
  pendingSelected.value = records.value
    .filter(r => r.status === 'PENDING' && next.has(r.settleId)).length
}

async function batchAudit() {
  const rows = records.value.filter(r => r.status === 'PENDING' && selected.value.has(r.settleId))
  if (!rows.length) return
  if (!window.confirm(`确认批量审核选中的 ${rows.length} 张待审核兑现单？`)) return
  const ok = []
  const fail = []
  for (const r of rows) {
    try {
      await post('/finance/factory-settle/audit', { settleId: r.settleId })
      ok.push(r.settleNo)
    } catch (e) {
      fail.push(`${r.settleNo}：${e?.message || '审核失败'}`)
    }
  }
  feedback.value = fail.length
    ? { level: 'warn', msg: `成功 ${ok.length} 张，失败 ${fail.length} 张：${fail.join('；')}` }
    : { level: 'ok', msg: `批量审核成功 ${ok.length} 张` }
  load()
}

// ==================== 打印 / 导出扣款通知单 ====================

async function openPrint(row) {
  printLoading.value = true
  printData.value = null
  printVisible.value = true
  try {
    printData.value = await post('/finance/factory-settle/print-data', { settleId: row.settleId })
  } catch (e) {
    feedback.value = { level: 'error', msg: e?.message || '打印数据加载失败' }
    printVisible.value = false
  } finally {
    printLoading.value = false
  }
}

function doPrint() {
  window.print()
}

async function doExportNotice(row) {
  feedback.value = null
  try {
    const blob = await downloadBlob('/finance/factory-settle/export-notice', { settleId: row.settleId })
    saveBlobFile(`扣款通知单_${row.settleNo}.xlsx`, blob)
  } catch (e) {
    feedback.value = { level: 'error', msg: e?.message || '导出失败' }
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
    <div class="page-ops no-print">
      <button class="btn primary" @click="doQuery">查询</button>
      <button class="btn" v-permission="'fin.factory_settle.add'" @click="openCreate">新建兑现单</button>
      <div class="spacer"></div>
    </div>

    <div class="query-panel no-print">
      <div class="query-row">
        <label>兑现单号
          <input v-model="filters.settleNo" class="inp" placeholder="兑现单号" @keyup.enter="doQuery" />
        </label>
        <label>供应商
          <input v-model="filters.supplier" class="inp" placeholder="编号或名称" @keyup.enter="doQuery" />
        </label>
        <label>状态
          <select v-model="filters.status" class="sel">
            <option value="">全部</option>
            <option value="PENDING">待审核</option>
            <option value="APPROVED">已审核</option>
          </select>
        </label>
        <label>兑现方式
          <select v-model="filters.settleType" class="sel">
            <option value="">全部</option>
            <option value="CASH">现金结算</option>
            <option value="OFFSET">冲应付</option>
            <option value="OTHER">其他核销</option>
          </select>
        </label>
        <label>兑现日期
          <input type="date" v-model="filters.dateFrom" class="sel" />
          <span class="dash">至</span>
          <input type="date" v-model="filters.dateTo" class="sel" />
        </label>
      </div>
    </div>

    <div v-if="feedback" class="toast-inline no-print" :class="feedback.level">{{ feedback.msg }}</div>

    <div class="table-wrap no-print">
      <table class="data">
        <thead>
          <tr>
            <th style="width:36px"><input type="checkbox" :checked="allChecked" @change="onCheckAll" /></th>
            <th style="width:50px">序号</th>
            <th style="width:150px">兑现单号</th>
            <th style="width:100px">供应商编号</th>
            <th>供应商名称</th>
            <th style="width:90px">兑现日期</th>
            <th style="width:80px">方式</th>
            <th style="width:110px">兑现金额</th>
            <th style="width:170px">对方科目/关联单</th>
            <th style="width:70px">状态</th>
            <th style="width:70px">经手人</th>
            <th>备注</th>
            <th style="width:140px">审核时间</th>
            <th style="width:250px">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-if="loading"><td colspan="14" class="empty">加载中…</td></tr>
          <tr v-else-if="!records.length"><td colspan="14" class="empty">暂无数据</td></tr>
          <tr v-for="(r, i) in records" :key="r.settleId">
            <td class="c"><input type="checkbox" :checked="selected.has(r.settleId)"
                                @change="onCheck(r, $event)" :disabled="r.status !== 'PENDING'" /></td>
            <td class="c">{{ (pageNo - 1) * pageSize + i + 1 }}</td>
            <td class="bill">{{ r.settleNo }}</td>
            <td>{{ r.supplierCode }}</td>
            <td>{{ r.supplierName }}</td>
            <td>{{ fmtDate(r.settleDate) }}</td>
            <td class="c">{{ r.settleTypeText }}</td>
            <td class="num strong">{{ fmt(r.totalAmount) }}</td>
            <td>
              <template v-if="r.settleType === 'OTHER'">
                {{ r.contraSubjectCode }} {{ r.contraSubjectName }}
                <span v-if="r.relatedBillNo" class="sub">/ {{ r.relatedBillNo }}</span>
              </template>
              <span v-else class="sub">{{ r.relatedBillNo || '—' }}</span>
            </td>
            <td class="c"><span class="tag" :class="r.status">{{ r.statusText }}</span></td>
            <td>{{ r.handler || '—' }}</td>
            <td class="remark">{{ r.remark || '' }}</td>
            <td>{{ fmtTime(r.auditTime) || '—' }}</td>
            <td class="ops">
              <a class="lk" @click="openView(r)">查看</a>
              <template v-if="r.status === 'PENDING'">
                <a class="lk" v-permission="'fin.factory_settle.edit'" @click="openEdit(r)">编辑</a>
                <a class="lk danger" v-permission="'fin.factory_settle.delete'" @click="doDelete(r)">删除</a>
                <a class="lk primary" v-permission="'fin.factory_settle.audit'" @click="doAudit(r)">审核</a>
              </template>
              <a v-else class="lk warn" v-permission="'fin.factory_settle.unaudit'" @click="doCancelAudit(r)">反审核</a>
              <a class="lk" v-permission="'fin.factory_settle.print'" @click="openPrint(r)">打印</a>
              <a class="lk" v-permission="'fin.factory_settle.print'" @click="doExportNotice(r)">导出通知单</a>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <div class="pager no-print">
      <button class="btn sm" :disabled="pageNo <= 1" @click="changePage(-1)">上一页</button>
      <span class="page-no">{{ pageNo }}</span>
      <button class="btn sm" :disabled="pageNo * pageSize >= total" @click="changePage(1)">下一页</button>
      <span class="total-text">共 {{ total }} 条</span>
    </div>

    <div v-if="pendingSelected > 0" class="batch-bar no-print">
      <span>已选 {{ pendingSelected }} 张待审核兑现单</span>
      <button class="btn primary sm" @click="batchAudit">批量审核</button>
    </div>

    <!-- 扣款通知单打印视图 -->
    <div v-if="printVisible" class="print-mask no-print-mask" @click.self="printVisible = false">
      <div class="print-modal">
        <div class="print-toolbar no-print">
          <b>扣款通知单预览</b>
          <div>
            <button class="btn primary sm" :disabled="printLoading" @click="doPrint">打印</button>
            <button class="btn sm" @click="printVisible = false">关闭</button>
          </div>
        </div>
        <div v-if="printLoading" class="print-loading">加载中…</div>
        <div v-else-if="printData" class="print-sheet">
          <h2 class="pt-title">扣款通知单</h2>
          <table class="pt-head">
            <tr><td class="k">单据编号</td><td>{{ printData.settleNo }}</td>
                <td class="k">兑现日期</td><td>{{ fmtDate(printData.settleDate) }}</td></tr>
            <tr><td class="k">供应商</td><td>{{ printData.supplierName }}（{{ printData.supplierCode }}）</td>
                <td class="k">兑现方式</td><td>{{ printData.settleTypeText }}</td></tr>
            <tr v-if="printData.settleType === 'OTHER'">
                <td class="k">对方科目</td><td>{{ printData.contraSubjectCode }} {{ printData.contraSubjectName }}</td>
                <td class="k">关联单据</td><td>{{ printData.relatedBillNo || '—' }}</td></tr>
            <tr><td class="k">经手人</td><td>{{ printData.handler || '—' }}</td>
                <td class="k">金额合计</td><td class="strong">￥{{ fmt(printData.totalAmount) }}</td></tr>
          </table>

          <h3>一、厂家费用明细</h3>
          <table class="pt-table">
            <thead>
              <tr><th>序号</th><th>厂家费用单号</th><th>费用日期</th><th>费用性质</th>
                  <th class="r">费用金额</th><th class="r">本次兑现</th></tr>
            </thead>
            <tbody>
              <tr v-for="(d, i) in printData.jfDetails" :key="i">
                <td class="c">{{ i + 1 }}</td>
                <td>{{ d.factoryExpenseNo }}</td>
                <td>{{ fmtDate(d.expenseDate) }}</td>
                <td>{{ d.claimType === 'OTHER' ? '其他' : '代垫' }}</td>
                <td class="r">{{ fmt(d.expenseAmount) }}</td>
                <td class="r strong">{{ fmt(d.settleAmount) }}</td>
              </tr>
            </tbody>
          </table>

          <h3 v-if="printData.settleType === 'CASH'">二、资金到账</h3>
          <table v-if="printData.settleType === 'CASH'" class="pt-table">
            <thead><tr><th>序号</th><th>资金账户</th><th class="r">到账金额</th><th>备注</th></tr></thead>
            <tbody>
              <tr v-for="(d, i) in printData.fundDetails" :key="i">
                <td class="c">{{ i + 1 }}</td><td>{{ d.fundAccount }}</td>
                <td class="r strong">{{ fmt(d.amount) }}</td><td>{{ d.remark || '' }}</td>
              </tr>
            </tbody>
          </table>

          <h3 v-else-if="printData.settleType === 'OFFSET'">二、冲应付明细</h3>
          <table v-if="printData.settleType === 'OFFSET'" class="pt-table">
            <thead><tr><th>序号</th><th>应付单号</th><th>来源单据</th><th>到期日</th>
                       <th class="r">应付金额</th><th class="r">本次冲销</th></tr></thead>
            <tbody>
              <tr v-for="(d, i) in printData.apDetails" :key="i">
                <td class="c">{{ i + 1 }}</td><td>{{ d.apNo }}</td><td>{{ d.sourceBill || '—' }}</td>
                <td>{{ fmtDate(d.dueDate) || '—' }}</td>
                <td class="r">{{ fmt(d.apAmount) }}</td><td class="r strong">{{ fmt(d.offsetAmount) }}</td>
              </tr>
            </tbody>
          </table>

          <p v-if="printData.remark" class="pt-remark">备注：{{ printData.remark }}</p>
          <p class="pt-sign">厂家确认（签字/盖章）：________________&nbsp;&nbsp;&nbsp;&nbsp;日期：________________</p>
        </div>
      </div>
    </div>

    <FactorySettleDrawer ref="drawer" @saved="load" />
  </div>
</template>

<style scoped>
.query-panel { background: var(--panel-bg, #fff); border: 1px solid var(--border-color, #e4e7ed);
  border-radius: 4px; padding: 12px 16px; margin-bottom: 10px; }
.query-row { display: flex; flex-wrap: wrap; gap: 18px; align-items: center; }
.query-row label { display: flex; align-items: center; gap: 6px; font-size: 13px; color: #606266; white-space: nowrap; }
.dash { color: #909399; }
.sel, .inp { height: 28px; border: 1px solid #dcdfe6; border-radius: 4px; padding: 0 8px; font-size: 13px; min-width: 120px; }
.table-wrap { background: var(--panel-bg, #fff); border: 1px solid var(--border-color, #e4e7ed);
  border-radius: 4px; overflow: auto; }
table.data { width: 100%; border-collapse: collapse; font-size: 13px; }
table.data th, table.data td { border-bottom: 1px solid #ebeef5; padding: 8px 10px; text-align: left; white-space: nowrap; }
table.data th { background: #f5f7fa; color: #303133; font-weight: 600; text-align: center; }
table.data td.c { text-align: center; }
table.data td.num { text-align: right; font-variant-numeric: tabular-nums; }
.strong { font-weight: 600; color: #1f6fdd; }
.bill { color: #1f6fdd; }
.remark { white-space: normal; min-width: 120px; color: #606266; }
.sub { color: #909399; font-size: 12px; }
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
.toast-inline.error { color: #c45656; background: #fef0f0; }
.batch-bar { position: fixed; left: 24px; bottom: 24px; z-index: 1500; background: #303133; color: #fff;
  border-radius: 6px; padding: 10px 14px; display: flex; align-items: center; gap: 12px; font-size: 13px;
  box-shadow: 0 6px 20px rgba(0,0,0,.25); }

/* 打印通知单 */
.print-mask { position: fixed; inset: 0; background: rgba(0,0,0,.45); z-index: 2100;
  display: flex; align-items: center; justify-content: center; }
.print-modal { width: 900px; max-width: 96vw; max-height: 92vh; background: #fff; border-radius: 6px;
  display: flex; flex-direction: column; }
.print-toolbar { padding: 10px 16px; border-bottom: 1px solid #ebeef5; display: flex;
  justify-content: space-between; align-items: center; }
.print-loading { padding: 60px 0; text-align: center; color: #909399; }
.print-sheet { padding: 30px 40px; overflow: auto; }
.pt-title { text-align: center; margin: 0 0 20px; }
.pt-head { width: 100%; border-collapse: collapse; margin-bottom: 16px; }
.pt-head td { border: 1px solid #909399; padding: 6px 10px; font-size: 13px; }
.pt-head td.k { background: #f5f7fa; width: 90px; font-weight: 600; white-space: nowrap; }
h3 { font-size: 14px; margin: 16px 0 8px; }
.pt-table { width: 100%; border-collapse: collapse; font-size: 13px; }
.pt-table th, .pt-table td { border: 1px solid #909399; padding: 5px 10px; }
.pt-table th { background: #f5f7fa; font-weight: 600; }
.pt-table .c { text-align: center; }
.pt-table .r { text-align: right; font-variant-numeric: tabular-nums; }
.pt-remark { font-size: 13px; margin-top: 16px; }
.pt-sign { font-size: 13px; margin-top: 40px; }
@media print {
  body * { visibility: hidden; }
  .print-sheet, .print-sheet * { visibility: visible; }
  .print-sheet { position: absolute; inset: 0; padding: 20px 30px; }
  .no-print-mask { background: #fff; position: static; }
}
</style>
