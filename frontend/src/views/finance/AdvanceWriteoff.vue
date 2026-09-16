<script setup>
/**
 * 预收核销单列表（PRD-35 M3）。
 * 手工单（MANUAL）：待审核可编辑/删除/审核，已审核可反审核。
 * 结算自动单（AR_SETTLE/STATEMENT_SETTLE）：只可查看；冲回入口是对应收款单反审核（级联作废）。
 */
import { onMounted, ref } from 'vue'
import { post } from '../../api/client.js'
import AdvanceWriteoffDrawer from '../../components/AdvanceWriteoffDrawer.vue'

const loading = ref(false)
const records = ref([])
const total = ref(0)
const pageNo = ref(1)
const pageSize = ref(20)
const feedback = ref(null)
const acting = ref(false)

// 查询条件（仅点「查询」生效，遵守全局查询约定）
const filters = ref({
  writeoffNo: '',
  customer: '',
  status: '',
  businessSource: '',
  dateFrom: '',
  dateTo: '',
})

const drawer = ref(null)

async function load() {
  loading.value = true
  feedback.value = null
  try {
    const res = await post('/finance/advance-writeoff/page', {
      pageNo: pageNo.value,
      pageSize: pageSize.value,
      writeoffNo: filters.value.writeoffNo,
      customer: filters.value.customer,
      status: filters.value.status,
      businessSource: filters.value.businessSource,
      dateFrom: filters.value.dateFrom,
      dateTo: filters.value.dateTo,
    })
    records.value = res.records || []
    total.value = res.total || 0
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
  if (!window.confirm(`确认审核预收核销单 ${row.writeoffNo}？\n审核后预收冲应收即刻生效。`)) return
  acting.value = true
  feedback.value = null
  try {
    await post('/finance/advance-writeoff/audit', { writeoffId: row.writeoffId })
    feedback.value = { level: 'ok', msg: '审核成功' }
    load()
  } catch (e) {
    feedback.value = { level: 'error', msg: e?.message || '审核失败' }
  } finally {
    acting.value = false
  }
}

async function doCancelAudit(row) {
  if (!window.confirm(`确认反审核预收核销单 ${row.writeoffNo}？\n核销将全部冲回，预收余额与应收金额恢复。`)) return
  acting.value = true
  feedback.value = null
  try {
    await post('/finance/advance-writeoff/cancel-audit', { writeoffId: row.writeoffId })
    feedback.value = { level: 'ok', msg: '反审核成功' }
    load()
  } catch (e) {
    feedback.value = { level: 'error', msg: e?.message || '反审核失败' }
  } finally {
    acting.value = false
  }
}

async function doDelete(row) {
  if (!window.confirm(`确认删除预收核销单 ${row.writeoffNo}？仅待审核单据可删除。`)) return
  acting.value = true
  feedback.value = null
  try {
    await post('/finance/advance-writeoff/delete', { writeoffId: row.writeoffId })
    feedback.value = { level: 'ok', msg: '删除成功' }
    load()
  } catch (e) {
    feedback.value = { level: 'error', msg: e?.message || '删除失败' }
  } finally {
    acting.value = false
  }
}

function isManual(row) {
  return row.businessSource === 'MANUAL'
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
      <button class="btn" v-permission="'fin.advance_writeoff.add'" @click="openCreate">新建核销单</button>
      <div class="spacer"></div>
    </div>

    <div class="query-panel">
      <div class="query-row">
        <label>核销单号
          <input v-model="filters.writeoffNo" class="inp" placeholder="核销单号"
                 @keyup.enter="doQuery" />
        </label>
        <label>客户
          <input v-model="filters.customer" class="inp" placeholder="客户编号或名称"
                 @keyup.enter="doQuery" />
        </label>
        <label>状态
          <select v-model="filters.status" class="sel">
            <option value="">全部</option>
            <option value="PENDING">待审核</option>
            <option value="APPROVED">已审核</option>
            <option value="CANCELLED">已作废</option>
          </select>
        </label>
        <label>业务来源
          <select v-model="filters.businessSource" class="sel">
            <option value="">全部</option>
            <option value="MANUAL">手工录入</option>
            <option value="AR_SETTLE">应收结算</option>
            <option value="STATEMENT_SETTLE">对账单结算</option>
          </select>
        </label>
        <label>核销日期
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
            <th style="width:50px">序号</th>
            <th style="width:150px">核销单号</th>
            <th style="width:110px">客户编号</th>
            <th>客户名称</th>
            <th style="width:80px">经手人</th>
            <th style="width:95px">核销日期</th>
            <th style="width:110px">核销金额</th>
            <th style="width:80px">状态</th>
            <th style="width:90px">业务来源</th>
            <th style="width:150px">来源单号</th>
            <th>备注</th>
            <th style="width:80px">制单人</th>
            <th style="width:140px">制单时间</th>
            <th style="width:80px">审核人</th>
            <th style="width:140px">审核时间</th>
            <th style="width:200px">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-if="loading"><td colspan="16" class="empty">加载中…</td></tr>
          <tr v-else-if="!records.length"><td colspan="16" class="empty">暂无数据</td></tr>
          <tr v-for="(r, i) in records" :key="r.writeoffId">
            <td class="c">{{ (pageNo - 1) * pageSize + i + 1 }}</td>
            <td class="bill">{{ r.writeoffNo }}</td>
            <td>{{ r.customerCode }}</td>
            <td>{{ r.customerName }}</td>
            <td>{{ r.handler }}</td>
            <td>{{ fmtDate(r.writeoffDate) }}</td>
            <td class="num strong">{{ fmt(r.totalAmount) }}</td>
            <td class="c"><span class="tag" :class="r.status">{{ r.statusText }}</span></td>
            <td>{{ r.businessSourceText }}</td>
            <td class="bill">{{ r.sourceBillNo || '—' }}</td>
            <td class="remark">{{ r.remark || '' }}</td>
            <td>{{ r.creatorName }}</td>
            <td>{{ fmtTime(r.createTime) }}</td>
            <td>{{ r.auditorName || '—' }}</td>
            <td>{{ fmtTime(r.auditTime) || '—' }}</td>
            <td class="ops">
              <a class="lk" @click="openView(r)">查看</a>
              <template v-if="isManual(r) && r.status === 'PENDING'">
                <a class="lk" v-permission="'fin.advance_writeoff.edit'" @click="openEdit(r)">编辑</a>
                <a class="lk danger" v-permission="'fin.advance_writeoff.delete'"
                   @click="doDelete(r)">删除</a>
                <a class="lk primary" v-permission="'fin.advance_writeoff.audit'"
                   @click="doAudit(r)">审核</a>
              </template>
              <a v-else-if="isManual(r) && r.status === 'APPROVED'" class="lk warn"
                 v-permission="'fin.advance_writeoff.unaudit'" @click="doCancelAudit(r)">反审核</a>
              <span v-else-if="!isManual(r)" class="auto-tip">结算生成</span>
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

    <AdvanceWriteoffDrawer ref="drawer" @saved="load" />
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
.bill { color: #1f6fdd; }
.remark { white-space: normal; min-width: 120px; color: #606266; }
.empty { text-align: center; color: #909399; padding: 30px 0 !important; }
.tag { padding: 1px 7px; border-radius: 3px; font-size: 12px; }
.tag.PENDING { background: #f4f4f5; color: #909399; }
.tag.APPROVED { background: #f0f9eb; color: #2f8f46; }
.tag.CANCELLED { background: #fef0f0; color: #c45656; }
.ops { display: flex; gap: 8px; align-items: center; }
.lk { color: #1f6fdd; cursor: pointer; font-size: 12.5px; }
.lk.warn { color: #b88230; }
.lk.danger { color: #c45656; }
.auto-tip { color: #c0c4cc; font-size: 12px; }
.pager { display: flex; align-items: center; gap: 8px; margin-top: 10px; }
.total-text { color: #909399; font-size: 12px; margin-left: 8px; }
.toast-inline.warn { color: #b88230; }
.toast-inline.ok { color: #2f8f46; }
.toast-inline.error { color: #c45656; }
</style>
