<script setup>
/**
 * 调拨出库单抽屉 —— 对已审核调拨申请单出仓，支持一商品多批次拆行。
 *
 * 交互：
 *   - 头部：来源调拨申请单（下拉，仅已审核未出库）→ from-apply 预填转出/转入仓
 *   - 明细：每行可选批次（转出仓 inv_batch_stock qty>0），「拆 2/拆 3」多批次出仓
 *   - 保存：POST /transfer/outbound/create | update（仅 PENDING）
 */
import { ref, computed, watch } from 'vue'
import { post, get } from '../api/client.js'

const props = defineProps({
  visible: { type: Boolean, default: false },
  mode: { type: String, default: 'add' }, // add / edit / view
  editData: { type: Object, default: null },
})
const emit = defineEmits(['close', 'save'])

const loading = ref(false)
const availableApplies = ref([])
const formErrors = ref({})

const header = ref({ applyNo: '', sourceWarehouse: '', targetWarehouse: '', billDate: new Date().toISOString().slice(0, 10), remark: '', outboundNo: '', status: '' })
const detailList = ref([])

const isView = computed(() => props.mode === 'view' || header.value.status === 'APPROVED')
const totalQty = computed(() => detailList.value.reduce((s, r) => s + (Number(r.qty) || 0), 0))
const totalCost = computed(() => detailList.value.reduce((s, r) => s + (Number(r.costAmount) || 0), 0).toFixed(2))

watch(() => props.visible, async (v) => {
  if (!v) return
  formErrors.value = {}
  header.value = { applyNo: '', sourceWarehouse: '', targetWarehouse: '', billDate: new Date().toISOString().slice(0, 10), remark: '', outboundNo: '', status: '' }
  detailList.value = []
  if (props.mode === 'edit' && props.editData) {
    await loadEdit(props.editData)
  } else {
    await loadAvailableApplies()
    if (props.mode === 'add' && props.editData?.applyNo) {
      // 从申请单列表「生成出库单」进入
      await loadFromApply(props.editData.applyNo)
    }
  }
})

async function loadAvailableApplies() {
  try {
    const data = await post('/transfer/apply/page', { pageNo: 1, pageSize: 500, filters: { status: 'APPROVED' } })
    availableApplies.value = (data.records || []).filter(a => a.status === 'APPROVED')
  } catch (_) { availableApplies.value = [] }
}

async function loadFromApply(applyNo) {
  if (!applyNo) return
  loading.value = true
  try {
    const data = await post('/transfer/outbound/from-apply', { applyNo })
    header.value.applyNo = data.applyNo || ''
    header.value.sourceWarehouse = data.sourceWarehouse || ''
    header.value.targetWarehouse = data.targetWarehouse || ''
    header.value.billDate = data.billDate || new Date().toISOString().slice(0, 10)
    const lines = (data.details || []).map(d => ({
      goodsCode: d.goodsCode, goodsName: d.goodsName, unitName: d.unitName || '',
      qty: Number(d.qty || 0), batchNo: '', productionDate: '', availableBatches: [], remark: '',
    }))
    detailList.value = lines
    await Promise.all(lines.map(l => loadBatches(l)))
  } catch (e) { formErrors.value = { header: e.message || '加载申请单失败' }; detailList.value = [] }
  finally { loading.value = false }
}

async function loadEdit(editData) {
  loading.value = true
  try {
    const raw = editData._raw || editData
    const data = await post('/transfer/outbound/detail', { outboundId: raw.outboundId || raw.c0 })
    header.value = {
      applyNo: data.sourceApplyNo || '', sourceWarehouse: data.sourceWarehouse || '',
      targetWarehouse: data.targetWarehouse || '', billDate: (data.billDate || '').toString().slice(0, 10),
      remark: data.remark || '', outboundNo: data.outboundNo || '', status: data.status || '',
    }
    const lines = (data.details || []).map(d => ({
      goodsCode: d.goodsCode, goodsName: d.goodsName, unitName: d.unitName || '',
      qty: Number(d.qty || 0), batchNo: d.batchNo || '',
      productionDate: '', costPrice: Number(d.costPrice || 0), costAmount: Number(d.costAmount || 0),
      availableBatches: [], remark: '',
    }))
    detailList.value = lines
    await Promise.all(lines.map(l => loadBatches(l)))
  } catch (e) { formErrors.value = { header: e.message || '加载出库单失败' }; detailList.value = [] }
  finally { loading.value = false }
}

async function loadBatches(row) {
  if (!row.goodsCode || !header.value.sourceWarehouse) return
  try {
    const list = await get(`/transfer/outbound/available-batches?goodsCode=${encodeURIComponent(row.goodsCode)}&warehouse=${encodeURIComponent(header.value.sourceWarehouse)}`)
    row.availableBatches = list || []
    // 已有 batchNo 时：从批次列表找对应的 productionDate 补齐；未指定时默认选第一批
    if (row.batchNo && row.availableBatches.length > 0) {
      const hit = row.availableBatches.find(b => b.batchNo === row.batchNo)
      if (hit) row.productionDate = hit.productionDate || ''
    } else if (!row.batchNo && row.availableBatches.length > 0) {
      row.batchNo = row.availableBatches[0].batchNo
      row.productionDate = row.availableBatches[0].productionDate || ''
    }
  } catch (_) { row.availableBatches = [] }
}

function onBatchChange(row) {
  const hits = (row.availableBatches || []).filter(b => b.batchNo === row.batchNo)
  row.productionDate = hits.length ? (hits[0].productionDate || '') : ''
}

function splitRow(index, count) {
  const row = detailList.value[index]
  if (!row || count < 2) return
  const total = Number(row.qty || 0)
  if (total <= 0) return alert('数量为 0，无法拆分')
  const per = Math.floor((total / count) * 10000) / 10000
  const rows = []
  for (let i = 0; i < count; i++) {
    const q = i === count - 1 ? Number((total - per * (count - 1)).toFixed(4)) : per
    rows.push({ ...row, qty: q, batchNo: row.batchNo, productionDate: row.productionDate })
  }
  detailList.value.splice(index, 1, ...rows)
}

function removeRow(index) {
  const row = detailList.value[index]
  if (!row) return
  if (detailList.value.filter(r => r.goodsCode === row.goodsCode).length <= 1) {
    alert('该商品仅剩一行，不能删除')
    return
  }
  detailList.value.splice(index, 1)
}

function validate() {
  const err = {}
  if (!header.value.applyNo) err.header = '请选择来源调拨申请单'
  if (detailList.value.filter(r => r.goodsCode).length === 0) err.details = '请至少一条出库明细'
  for (const r of detailList.value.filter(r => r.goodsCode)) {
    if (!Number(r.qty) || Number(r.qty) <= 0) { err.details = `商品 ${r.goodsName || r.goodsCode} 出库数量必须大于 0`; break }
    if (!r.batchNo) { err.details = `商品 ${r.goodsName || r.goodsCode} 请选择批次`; break }
  }
  formErrors.value = err
  return Object.keys(err).length === 0
}

async function save() {
  if (!validate()) { alert(Object.values(formErrors.value)[0]); return }
  loading.value = true
  try {
    const isEdit = props.mode === 'edit'
    const payload = {
      sourceApplyNo: header.value.applyNo,
      sourceWarehouse: header.value.sourceWarehouse,
      targetWarehouse: header.value.targetWarehouse,
      billDate: header.value.billDate,
      remark: header.value.remark,
      details: detailList.value.filter(r => r.goodsCode).map(r => ({
        goodsCode: r.goodsCode, goodsName: r.goodsName, unitName: r.unitName,
        qty: Number(r.qty), batchNo: r.batchNo, remark: r.remark || '',
      })),
    }
    if (isEdit) payload.outboundId = props.editData._raw?.outboundId || props.editData.c0
    await post(isEdit ? '/transfer/outbound/update' : '/transfer/outbound/create', payload)
    emit('save')
    emit('close')
  } catch (e) { alert('保存失败：' + (e.message || '未知错误')) }
  finally { loading.value = false }
}

function close() { emit('close') }
</script>

<template>
  <div v-if="visible" class="ob-mask">
    <div class="ob-box">
      <div class="ob-head">
        <b>{{ isView ? '查看调拨出库单' : (props.mode === 'edit' ? '编辑调拨出库单' : (props.editData?.applyNo ? '生成调拨出库单' : '新建调拨出库单')) }}</b>
        <span v-if="header.outboundNo" style="margin-left:10px;color:#606266;font-size:12px">{{ header.outboundNo }}</span>
        <span v-if="header.status" class="badge" :class="header.status === 'APPROVED' ? 'ok' : 'wait'" style="margin-left:8px">{{ { PENDING: '待审核', APPROVED: '已审核' }[header.status] || header.status }}</span>
        <div style="flex:1"></div>
        <button class="btn" @click="close">关闭</button>
        <button v-if="!isView" class="btn primary" @click="save" :disabled="loading">保存</button>
      </div>
      <div class="ob-body">
        <div class="card" style="padding:10px 14px">
          <div v-if="formErrors.header" style="color:#f56c6c;font-size:12px;margin-bottom:6px">{{ formErrors.header }}</div>
          <div class="grid4">
            <div class="field"><label>来源申请单 <span class="req">*</span></label>
              <template v-if="!props.editData?.applyNo && !isView && props.mode !== 'edit'">
                <select v-model="header.applyNo" @change="loadFromApply($event.target.value)"><option value="">请选择</option>
                  <option v-for="a in availableApplies" :key="a.applyNo" :value="a.applyNo">{{ a.applyNo }}（{{ a.sourceWarehouse }} → {{ a.targetWarehouse }}，数量 {{ a.qty }}）</option>
                </select>
              </template>
              <input v-else readonly :value="header.applyNo" />
            </div>
            <div class="field"><label>转出仓库</label><input readonly :value="header.sourceWarehouse" /></div>
            <div class="field"><label>转入仓库</label><input readonly :value="header.targetWarehouse" /></div>
            <div class="field"><label>出库日期</label><input type="date" v-model="header.billDate" :disabled="isView" /></div>
            <div class="field field-full"><label>备注</label><input v-model="header.remark" :disabled="isView" placeholder="选填" style="width:100%" /></div>
          </div>
        </div>
        <div class="card detail-card">
          <div style="display:flex;align-items:center;gap:8px;margin-bottom:6px">
            <b style="font-size:13px">出库明细</b>
            <span v-if="formErrors.details" style="color:#f56c6c;font-size:12px">{{ formErrors.details }}</span>
            <div style="flex:1"></div>
            <span style="font-size:12px;color:#606266">合计数量：<b>{{ totalQty }}</b>&nbsp;&nbsp;成本合计：<b style="color:#409eff">¥ {{ totalCost }}</b></span>
          </div>
          <div v-if="loading" style="text-align:center;padding:30px;color:#909399">加载中...</div>
          <div v-else-if="detailList.length === 0" style="text-align:center;padding:30px;color:#909399">请先选择来源调拨申请单</div>
          <div v-else class="detail-scroll">
            <table>
              <thead><tr><th style="width:36px">#</th><th>商品编号</th><th>商品名称</th><th>单位</th><th style="width:100px">出库数量 <span class="req">*</span></th><th style="min-width:170px">批次 <span class="req">*</span></th><th style="min-width:110px">生产日期</th><th style="width:100px">成本单价</th><th style="width:100px">成本金额</th><th v-if="!isView" style="width:120px">操作</th></tr></thead>
              <tbody>
                <tr v-for="(row, i) in detailList" :key="i">
                  <td>{{ i + 1 }}</td>
                  <td>{{ row.goodsCode }}</td>
                  <td>{{ row.goodsName }}</td>
                  <td>{{ row.unitName }}</td>
                  <td>
                    <template v-if="isView">{{ row.qty }}</template>
                    <input v-else type="number" min="0" step="0.0001" v-model.number="row.qty" style="width:100%;height:24px;text-align:right" />
                  </td>
                  <td>
                    <template v-if="isView">{{ row.batchNo }}</template>
                    <select v-else v-model="row.batchNo" @change="onBatchChange(row)" style="width:100%;height:24px">
                      <option value="">请选择批次</option>
                      <option v-for="b in row.availableBatches" :key="b.batchNo" :value="b.batchNo">{{ b.batchNo }}（可用 {{ b.qty }}）</option>
                    </select>
                  </td>
                  <td><input readonly :value="row.productionDate" style="width:100%;height:24px" /></td>
                  <td style="text-align:right">{{ Number(row.costPrice || 0).toFixed(6) }}</td>
                  <td style="text-align:right;font-weight:600">{{ Number(row.costAmount || 0).toFixed(2) }}</td>
                  <td v-if="!isView">
                    <button class="link link-btn" @click="splitRow(i, 2)">拆 2</button>
                    <button class="link link-btn" @click="splitRow(i, 3)">拆 3</button>
                    <button class="link link-btn danger-link" @click="removeRow(i)">删除</button>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.ob-mask { position: fixed; top: 48px; right: 0; bottom: 0; left: 299px; z-index: 900; display: flex; pointer-events: none; }
.ob-box { flex: 1; background: #fff; display: flex; flex-direction: column; min-width: 0; border-left: 1px solid #e5e7eb; box-shadow: -6px 0 24px rgba(15,46,88,.12); pointer-events: auto; }
.ob-head { display: flex; align-items: center; gap: 10px; height: 46px; padding: 0 16px; border-bottom: 1px solid #e5e7eb; flex-shrink: 0; }
.ob-head b { font-size: 15px; }
.ob-body { flex: 1; overflow: auto; padding: 12px 16px; display: flex; flex-direction: column; gap: 12px; background: #f5f7fa; }
.card { background: #fff; border: 1px solid #e5e7eb; border-radius: 6px; }
.grid4 { display: grid; grid-template-columns: 1fr 1fr; gap: 8px 16px; }
.grid4 .field { display: grid; grid-template-columns: 96px 1fr; align-items: center; gap: 4px; }
.grid4 .field label { font-size: 12px; color: #606266; text-align: right; font-weight: 600; white-space: nowrap; }
.grid4 .field input, .grid4 .field select { height: 28px; padding: 0 6px; border: 1px solid #dcdfe6; border-radius: 4px; font-size: 12px; }
.field-full { grid-column: 1 / -1; }
.req { color: #f56c6c; }
.detail-card { flex: 1; display: flex; flex-direction: column; min-height: 220px; padding: 10px 14px; }
.detail-scroll { flex: 1; overflow: auto; min-height: 0; }
.detail-scroll table { width: 100%; border-collapse: collapse; font-size: 12px; }
.detail-scroll th { background: #f5f7fa; padding: 6px 8px; text-align: left; font-weight: 600; border-bottom: 1px solid #e5e7eb; }
.detail-scroll td { padding: 4px 8px; border-bottom: 1px solid #f0f0f0; }
.badge { font-size: 11px; padding: 1px 8px; border-radius: 10px; }
.badge.ok { background: #f0f9eb; color: #67c23a; }
.badge.wait { background: #fdf6ec; color: #e6a23c; }
@media (max-width: 900px) { .ob-mask { left: 0; } }
</style>
