<script setup>
/**
 * 预付核销单 新建/编辑/查看 抽屉（PRD-36 M2）
 * 左：主单（供应商/经手人/核销日期/可用预付/合计/备注）；右：该供应商未结应付勾选 + 本次核销金额。
 * 保存只建 PENDING 单；「保存并审核」额外调用 audit，审核通过后核销才生效。
 * 自动生成单（AP_SETTLE/STATEMENT_SETTLE）不在此入口编辑，列表仅可查看。
 */
import { ref, computed } from 'vue'
import { post } from '../api/client.js'
import { todayStr } from '../utils/dateTime.js'

const emit = defineEmits(['saved'])

const visible = ref(false)
const mode = ref('create') // create | edit | view
const saving = ref(false)
const auditing = ref(false)
const loadingAp = ref(false)
const feedback = ref(null)

const suppliers = ref([])
const employees = ref([])
const prepayBalance = ref(0)

const head = ref({
  writeoffId: '',
  writeoffNo: '',
  supplierCode: '',
  supplierName: '',
  handler: '',
  writeoffDate: todayStr(),
  remark: '',
  status: '',
  businessSource: 'MANUAL',
})

// 未结应付候选行（含编辑时服务端明细中当前列表取不到的快照行）
const apRows = ref([])

const readonly = computed(() => mode.value === 'view')
const isEdit = computed(() => mode.value === 'edit')
const totalWriteoff = computed(() =>
  Math.round(apRows.value.reduce((s, r) => s + (Number(r.writeoffAmount) || 0), 0) * 100) / 100)
const selectedCount = computed(() => apRows.value.filter(r => Number(r.writeoffAmount) > 0).length)
const overBalance = computed(() => totalWriteoff.value > Number(prepayBalance.value || 0) + 0.001)
const selectedSupplier = computed(() =>
  suppliers.value.find(s => s.supplierCode === head.value.supplierCode) || null)

async function loadOptions() {
  try {
    const s = await post('/base/supplier/page', { pageNo: 1, pageSize: 500, filters: {} })
    suppliers.value = (s.records || []).filter(x => x.supplierCode)
  } catch (_) { suppliers.value = [] }
  try {
    const emp = await post('/base/master/employee/page', { pageNo: 1, pageSize: 200, filters: {} })
    employees.value = (emp.records || []).filter(x => x.employeeName || x.name)
  } catch (_) { employees.value = [] }
}

async function loadPrepayBalance() {
  prepayBalance.value = 0
  if (!head.value.supplierCode) return
  try {
    const r = await post('/finance/supplier-account/prepay-balance', {
      supplierCode: head.value.supplierCode,
      supplierName: head.value.supplierName,
    })
    if (r && r.supplierCode) head.value.supplierCode = r.supplierCode
    prepayBalance.value = Number(r.prepayBalance) || 0
  } catch (_) { prepayBalance.value = 0 }
}

/** 拉取该供应商未结应付（按到期日升序，空到期日排最后，与服务端 FIFO 口径一致）。 */
async function loadApCandidates(snapshotLines) {
  loadingAp.value = true
  try {
    const keyword = head.value.supplierName || head.value.supplierCode
    const res = await post('/finance/ap/page', {
      pageNo: 1, pageSize: 200,
      filters: { supplier: keyword, status: '未核销' },
    })
    const live = (res.records || []).filter(r => Number(r.unpaidAmount) > 0)
    const byNo = new Map(live.map(r => [r.apNo, r]))
    const merged = live.map(r => ({
      apNo: r.apNo,
      sourceBill: r.sourceBill || '',
      apAmount: Number(r.apAmount) || 0,
      paidAmount: Number(r.paidAmount) || 0,
      unpaidAmount: Number(r.unpaidAmount) || 0,
      dueDate: r.dueDate ? String(r.dueDate).substring(0, 10) : '',
      writeoffAmount: 0,
      missing: false,
    }))
    // 编辑/查看：服务端明细行可能因制单后被别处结算而不在未结列表中，补快照行并由服务端最终校验
    for (const d of snapshotLines || []) {
      if (!byNo.has(d.apNo)) {
        merged.push({
          apNo: d.apNo,
          sourceBill: d.sourceBill || '',
          apAmount: Number(d.apAmount) || 0,
          paidAmount: Math.max(0, (Number(d.apAmount) || 0) - (Number(d.unsettledBefore) || 0)),
          unpaidAmount: Number(d.unsettledBefore) || 0,
          dueDate: d.dueDate ? String(d.dueDate).substring(0, 10) : '',
          writeoffAmount: Number(d.writeoffAmount) || 0,
          missing: true,
        })
      } else {
        const row = merged.find(r => r.apNo === d.apNo)
        if (row) row.writeoffAmount = Number(d.writeoffAmount) || 0
      }
    }
    merged.sort((a, b) => {
      if (!a.dueDate) return 1
      if (!b.dueDate) return -1
      return a.dueDate < b.dueDate ? -1 : a.dueDate > b.dueDate ? 1 : 0
    })
    apRows.value = merged
  } catch (e) {
    feedback.value = e?.message || '未结应付加载失败'
    apRows.value = []
  } finally {
    loadingAp.value = false
  }
}

async function open(modeName, row) {
  feedback.value = null
  mode.value = modeName || 'create'
  apRows.value = []
  prepayBalance.value = 0
  head.value = {
    writeoffId: '', writeoffNo: '', supplierCode: '', supplierName: '',
    handler: '', writeoffDate: todayStr(), remark: '',
    status: '', businessSource: 'MANUAL',
  }
  await loadOptions()
  if (mode.value === 'create') {
    visible.value = true
    return
  }
  // edit / view：拉详情
  try {
    const detail = await post('/finance/prepay-writeoff/detail', { writeoffId: row.writeoffId })
    head.value = {
      writeoffId: detail.writeoffId,
      writeoffNo: detail.writeoffNo,
      supplierCode: detail.supplierCode || '',
      supplierName: detail.supplierName || '',
      handler: detail.handler || '',
      writeoffDate: detail.writeoffDate ? String(detail.writeoffDate).substring(0, 10) : todayStr(),
      remark: detail.remark || '',
      status: detail.status || '',
      businessSource: detail.businessSource || 'MANUAL',
    }
    visible.value = true
    await loadPrepayBalance()
    await loadApCandidates(detail.details || [])
  } catch (e) {
    feedback.value = e?.message || '单据加载失败'
    visible.value = true
  }
}

function onSupplierChange() {
  const s = selectedSupplier.value
  head.value.supplierName = s ? s.supplierName : ''
  apRows.value = []
  feedback.value = null
  if (s) {
    loadPrepayBalance()
    loadApCandidates([])
  } else {
    prepayBalance.value = 0
  }
}

function onCheckRow(r, checked) {
  r.writeoffAmount = checked
    ? Math.min(Number(r.unpaidAmount) || 0, Math.max(0, Number(prepayBalance.value) - totalWriteoff.value + (Number(r.writeoffAmount) || 0)))
    : 0
}

function clampRow(r) {
  let v = Number(r.writeoffAmount)
  if (isNaN(v) || v < 0) v = 0
  const cap = Number(r.unpaidAmount) || 0
  if (v > cap) v = cap
  r.writeoffAmount = Math.round(v * 100) / 100
}

function close() { visible.value = false }

function buildPayload() {
  const lines = apRows.value
    .filter(r => Number(r.writeoffAmount) > 0)
    .map(r => ({ apNo: r.apNo, writeoffAmount: Math.round(Number(r.writeoffAmount) * 100) / 100 }))
  return {
    writeoffId: head.value.writeoffId || undefined,
    supplierCode: head.value.supplierCode,
    supplierName: head.value.supplierName,
    handler: head.value.handler,
    writeoffDate: head.value.writeoffDate,
    remark: head.value.remark,
    details: lines,
  }
}

function validate(payload) {
  if (!head.value.supplierCode) return '请选择供应商'
  if (!head.value.handler) return '请选择经手人'
  if (!head.value.writeoffDate) return '请填写核销日期'
  if (!payload.details.length) return '请至少勾选一行未结应付并填写核销金额'
  if (overBalance.value) {
    return `核销合计 ${totalWriteoff.value.toFixed(2)} 元超过供应商预付余额 ${Number(prepayBalance.value || 0).toFixed(2)} 元`
  }
  return ''
}

async function save(thenAudit) {
  const payload = buildPayload()
  const err = validate(payload)
  if (err) { feedback.value = err; return }
  const verb = isEdit.value ? '修改' : '新建'
  if (!window.confirm(
    `确认${verb}预付核销单？供应商：${head.value.supplierName}，核销合计 ￥${totalWriteoff.value.toFixed(2)}`
    + (thenAudit ? '\n保存后将立即审核，预付冲应付即刻生效。' : ''))) return
  saving.value = true
  feedback.value = null
  try {
    let res
    if (isEdit.value) {
      res = await post('/finance/prepay-writeoff/update', payload)
    } else {
      res = await post('/finance/prepay-writeoff/create', payload)
    }
    const writeoffId = res.writeoffId
    if (thenAudit) {
      auditing.value = true
      await post('/finance/prepay-writeoff/audit', { writeoffId })
    }
    feedback.value = { ok: true, msg: thenAudit ? '保存并审核成功' : '保存成功' }
    emit('saved')
    close()
  } catch (e) {
    feedback.value = e?.message || '保存失败'
  } finally {
    saving.value = false
    auditing.value = false
  }
}

function fmt(v) {
  return (Number(v || 0)).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}
function statusText(s) {
  return { PENDING: '待审核', APPROVED: '已审核', CANCELLED: '已作废' }[s] || s
}
function sourceText(s) {
  return { MANUAL: '手工录入', AP_SETTLE: '应付结算生成', STATEMENT_SETTLE: '对账单结算生成' }[s] || '手工录入'
}

defineExpose({ open })
</script>

<template>
  <div v-if="visible" class="xw-mask" @click.self="close">
    <div class="xw-modal">
      <div class="xw-h">
        {{ readonly ? '查看预付核销单' : (isEdit ? '编辑预付核销单' : '新建预付核销单') }}
        <span v-if="head.writeoffNo" class="no-tag">{{ head.writeoffNo }}</span>
        <span class="x" @click="close">×</span>
      </div>

      <div class="xw-b">
        <div v-if="feedback" class="toast-inline" :class="feedback.ok ? 'ok' : 'error'">
          {{ feedback.msg || feedback }}
        </div>
        <div class="xw-grid">
          <!-- 左：主单 -->
          <div class="master">
            <div class="fi">
              <label>供应商 <span class="req">*</span></label>
              <select v-model="head.supplierCode" :disabled="readonly" @change="onSupplierChange">
                <option value="">请选择供应商</option>
                <option v-for="s in suppliers" :key="s.supplierCode" :value="s.supplierCode">
                  {{ s.supplierCode }} {{ s.supplierName }}
                </option>
              </select>
            </div>
            <div class="fi">
              <label>经手人 <span class="req">*</span></label>
              <select v-model="head.handler" :disabled="readonly">
                <option value="">请选择</option>
                <option v-for="e in employees" :key="e.employeeCode || e.code" :value="e.employeeName || e.name">
                  {{ e.employeeName || e.name }}
                </option>
              </select>
            </div>
            <div class="fi">
              <label>核销日期 <span class="req">*</span></label>
              <input type="date" v-model="head.writeoffDate" :disabled="readonly" />
            </div>
            <div class="balance-panel">
              <div class="bp-line">供应商 ERP 预付余额：<b :class="{ neg: overBalance }">￥{{ fmt(prepayBalance) }}</b></div>
              <div class="bp-line">本次核销合计：<b class="money" :class="{ neg: overBalance }">￥{{ fmt(totalWriteoff) }}</b></div>
              <div class="bp-line">已选应付：<b>{{ selectedCount }}</b> 行</div>
              <div v-if="overBalance" class="bp-warn">核销合计超过预付余额，保存将被拒绝</div>
            </div>
            <div class="fi">
              <label>备注</label>
              <textarea v-model="head.remark" rows="3" :disabled="readonly"
                        placeholder="选填，最多 200 字" maxlength="200"></textarea>
            </div>
            <div v-if="readonly" class="fi">
              <label>单据状态 / 来源</label>
              <div class="ro-text">
                <span class="tag" :class="head.status">{{ statusText(head.status) }}</span>
                {{ sourceText(head.businessSource) }}
              </div>
            </div>
          </div>

          <!-- 右：未结应付勾选 -->
          <div class="detail-side">
            <div class="detail-head">
              <span>未结应付（按到期日升序，勾选后填写本次核销金额）</span>
              <span v-if="loadingAp" class="loading-tip">加载中…</span>
            </div>
            <div class="detail-scroll">
              <table class="ap-table">
                <thead>
                  <tr>
                    <th style="width:36px"></th>
                    <th style="width:140px">来源单号</th>
                    <th style="width:130px">应付单号</th>
                    <th style="width:90px;text-align:right">应付金额</th>
                    <th style="width:90px;text-align:right">已付</th>
                    <th style="width:90px;text-align:right">未付</th>
                    <th style="width:95px">到期日</th>
                    <th style="width:110px;text-align:right">本次核销</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-if="!head.supplierCode"><td colspan="8" class="empty">请先选择供应商</td></tr>
                  <tr v-else-if="loadingAp"><td colspan="8" class="empty">加载中…</td></tr>
                  <tr v-else-if="!apRows.length"><td colspan="8" class="empty">该供应商没有未结算应付</td></tr>
                  <tr v-for="r in apRows" :key="r.apNo" :class="{ missing: r.missing }">
                    <td class="c">
                      <input type="checkbox" :disabled="readonly"
                             :checked="Number(r.writeoffAmount) > 0"
                             @change="onCheckRow(r, $event.target.checked)" />
                    </td>
                    <td>{{ r.sourceBill || '—' }}</td>
                    <td>{{ r.apNo }}</td>
                    <td class="num">{{ fmt(r.apAmount) }}</td>
                    <td class="num">{{ fmt(r.paidAmount) }}</td>
                    <td class="num strong">{{ fmt(r.unpaidAmount) }}</td>
                    <td>{{ r.dueDate || '—' }}</td>
                    <td>
                      <input type="number" min="0" step="0.01" class="amt"
                             v-model.number="r.writeoffAmount" :disabled="readonly"
                             @change="clampRow(r)" />
                    </td>
                  </tr>
                </tbody>
              </table>
            </div>
            <div v-if="apRows.some(r => r.missing)" class="missing-tip">
              标红行是制单时的快照：该应付当前已不在未结列表中，保存时服务端会按真值重新校验。
            </div>
          </div>
        </div>
      </div>

      <div class="xw-f">
        <button class="btn" @click="close">关闭</button>
        <template v-if="!readonly">
          <button class="btn" :disabled="saving || auditing" @click="save(false)">
            {{ saving ? '保存中…' : '保存' }}
          </button>
          <button class="btn primary" :disabled="saving || auditing" @click="save(true)">
            {{ auditing ? '审核中…' : '保存并审核' }}
          </button>
        </template>
      </div>
    </div>
  </div>
</template>

<style scoped>
.xw-mask {
  position: fixed; inset: 0;
  background: rgba(0,0,0,.45);
  z-index: 2000;
  display: flex; align-items: center; justify-content: center;
}
.xw-modal {
  width: 96vw; max-width: 1280px; height: 86vh;
  background: #fff; border-radius: 6px;
  display: flex; flex-direction: column;
  box-shadow: 0 8px 30px rgba(0,0,0,.2);
}
.xw-h {
  padding: 12px 18px; font-size: 15px; font-weight: 600;
  border-bottom: 1px solid #ebeef5;
  display: flex; align-items: center; gap: 10px;
}
.xw-h .no-tag { font-size: 12px; color: #1f6fdd; font-weight: 400; }
.xw-h .x { margin-left: auto; cursor: pointer; font-size: 20px; color: #909399; }
.xw-b { padding: 12px 18px; display: flex; flex-direction: column; flex: 1; min-height: 0; }
.toast-inline { font-size: 13px; margin-bottom: 8px; padding: 6px 10px; border-radius: 4px; }
.toast-inline.error { color: #c45656; background: #fef0f0; }
.toast-inline.ok { color: #2f8f46; background: #f0f9eb; }
.xw-grid { display: grid; grid-template-columns: 320px 1fr; gap: 14px; flex: 1; min-height: 0; }
.master { display: flex; flex-direction: column; gap: 12px; border-right: 1px solid #ebeef5; padding-right: 14px; }
.fi { display: flex; flex-direction: column; gap: 4px; }
.fi label { font-size: 12px; color: #606266; font-weight: 600; }
.fi select, .fi input, .fi textarea {
  border: 1px solid #dcdfe6; border-radius: 4px; padding: 5px 8px; font-size: 13px;
  font-family: inherit;
}
.fi select, .fi input { height: 30px; }
.req { color: #f56c6c; }
.balance-panel {
  background: #f5f7fa; border-radius: 4px; padding: 10px 12px;
  display: flex; flex-direction: column; gap: 6px; font-size: 13px; color: #303133;
}
.bp-line b { font-variant-numeric: tabular-nums; }
.bp-line .money { color: #1f6fdd; font-size: 15px; }
.bp-line .neg { color: #c45656; }
.bp-warn { color: #c45656; font-size: 12px; }
.ro-text { font-size: 13px; display: flex; align-items: center; gap: 8px; }
.tag { padding: 1px 8px; border-radius: 3px; font-size: 12px; }
.tag.PENDING { background: #f4f4f5; color: #909399; }
.tag.APPROVED { background: #f0f9eb; color: #2f8f46; }
.tag.CANCELLED { background: #fef0f0; color: #c45656; }
.detail-side { display: flex; flex-direction: column; min-height: 0; }
.detail-head {
  font-size: 13px; font-weight: 600; color: #303133;
  margin-bottom: 8px; display: flex; justify-content: space-between; align-items: center;
}
.loading-tip { font-weight: 400; color: #909399; font-size: 12px; }
.detail-scroll { flex: 1; overflow: auto; border: 1px solid #ebeef5; border-radius: 4px; }
.ap-table { width: 100%; border-collapse: collapse; font-size: 12.5px; }
.ap-table th, .ap-table td { border-bottom: 1px solid #f0f2f5; padding: 6px 8px; white-space: nowrap; }
.ap-table th { background: #f5f7fa; font-weight: 600; text-align: left; position: sticky; top: 0; z-index: 1; }
.ap-table td.num { text-align: right; font-variant-numeric: tabular-nums; }
.ap-table td.c { text-align: center; }
.ap-table .strong { font-weight: 600; color: #1f6fdd; }
.ap-table .empty { text-align: center; color: #909399; padding: 30px 0; }
.ap-table tr.missing td { background: #fff7f6; }
.amt { width: 100px; height: 26px; border: 1px solid #dcdfe6; border-radius: 4px; padding: 0 6px;
  text-align: right; font-size: 12.5px; }
.missing-tip { color: #b88230; font-size: 12px; margin-top: 6px; }
.xw-f {
  padding: 10px 18px; border-top: 1px solid #ebeef5;
  display: flex; justify-content: flex-end; gap: 8px;
}
</style>
