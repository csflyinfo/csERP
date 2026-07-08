<script setup>
/**
 * 调拨入库单抽屉 —— 出库审核后自动生成；正常单只可改数量（不可超调出数量），
 * 差异退回单明细只读。成本 = 调出时成本。
 *
 * 交互：
 *   - 正常单：明细行 qty 可编辑（≤ outQty），保存调数量
 *   - 差异退回单：全只读，只能审核（入调出仓）
 *   - 审核后：自动入仓 + 数量不足自动生成差异退回单
 */
import { ref, computed, watch } from 'vue'
import { post } from '../api/client.js'

const props = defineProps({
  visible: { type: Boolean, default: false },
  mode: { type: String, default: 'view' }, // view only (auto-generated, no manual create)
  editData: { type: Object, default: null },
})
const emit = defineEmits(['close', 'save'])

const loading = ref(false)
const formErrors = ref({})

const header = ref({
  inboundId: '', inboundNo: '', inboundType: '',
  sourceOutboundNo: '', sourceWarehouse: '', targetWarehouse: '',
  billDate: '', qty: 0, costAmount: 0, status: '', remark: '',
})
const detailList = ref([])

const isView = computed(() => header.value.status === 'APPROVED')
const isDiffReturn = computed(() => header.value.inboundType === '调拨入库差异退回')
const isEditable = computed(() => header.value.status === 'PENDING' && !isDiffReturn.value)

const totalQty = computed(() => detailList.value.reduce((s, r) => s + (Number(r.qty) || 0), 0))
const totalCost = computed(() => detailList.value.reduce((s, r) => s + (Number(r.costAmount) || 0), 0))

watch(() => props.visible, async (v) => {
  if (!v) return
  formErrors.value = {}
  header.value = { inboundId: '', inboundNo: '', inboundType: '', sourceOutboundNo: '', sourceWarehouse: '', targetWarehouse: '', billDate: '', qty: 0, costAmount: 0, status: '', remark: '' }
  detailList.value = []
  if (props.editData) await loadDetail(props.editData)
})

async function loadDetail(editData) {
  loading.value = true
  try {
    const raw = editData._raw || editData
    const key = raw.inboundId || raw.inboundNo || raw.c0
    const data = await post('/transfer/inbound/detail', { inboundId: key })
    header.value = {
      inboundId: data.inboundId || '', inboundNo: data.inboundNo || '',
      inboundType: data.inboundType || '正常', sourceOutboundNo: data.sourceOutboundNo || '',
      sourceWarehouse: data.sourceWarehouse || '', targetWarehouse: data.targetWarehouse || '',
      billDate: (data.billDate || '').toString().slice(0, 10),
      qty: Number(data.qty || 0), costAmount: Number(data.costAmount || 0),
      status: data.status || '', remark: data.remark || '',
    }
    detailList.value = (data.details || []).map(d => ({
      detailId: d.detailId, goodsCode: d.goodsCode, goodsName: d.goodsName,
      unitName: d.unitName || '', outQty: Number(d.outQty || 0),
      qty: Number(d.qty || 0), batchNo: d.batchNo || '',
      costPrice: Number(d.costPrice || 0), costAmount: Number(d.costAmount || 0),
    }))
  } catch (e) { formErrors.value = { header: e.message || '加载入库单失败' } }
  finally { loading.value = false }
}

/** 修改数量后重新计算行成本 */
function onQtyChange(row) {
  if (Number(row.qty) > Number(row.outQty)) {
    row.qty = row.outQty
    alert('入库数量不能超过调出数量 ' + row.outQty)
  }
  row.costAmount = Number((Number(row.qty || 0) * Number(row.costPrice || 0)).toFixed(2))
}

async function saveQty() {
  if (!isEditable.value) { alert('当前状态不可修改数量'); return }
  const diff = detailList.value.filter(r => r.goodsCode && !r.detailId)
  if (diff.length > 0) { alert('明细行缺少 ID，无法保存'); return }
  loading.value = true
  try {
    await post('/transfer/inbound/update', {
      inboundId: header.value.inboundId,
      details: detailList.value.map(r => ({
        detailId: r.detailId, qty: Number(r.qty || 0),
      })),
    })
    emit('save')
    emit('close')
  } catch (e) { alert('保存数量失败：' + (e.message || '未知错误')) }
  finally { loading.value = false }
}

function close() { emit('close') }
</script>

<template>
  <div v-if="visible" class="ib-mask">
    <div class="ib-box">
      <div class="ib-head">
        <b>调拨入库单{{ isDiffReturn ? ' — 差异退回' : '' }}</b>
        <span style="margin-left:10px;color:#606266;font-size:12px">{{ header.inboundNo }}</span>
        <span class="badge" :class="{ ok: header.inboundType === '调拨入库差异退回', wait: header.inboundType === '正常' }" style="margin-left:8px">{{ header.inboundType }}</span>
        <span class="badge" :class="header.status === 'APPROVED' ? 'ok' : 'wait'" style="margin-left:4px">{{ { PENDING: '待审核', APPROVED: '已审核' }[header.status] || header.status }}</span>
        <div style="flex:1"></div>
        <button class="btn" @click="close">关闭</button>
        <button v-if="isEditable" class="btn primary" @click="saveQty" :disabled="loading">保存数量</button>
      </div>
      <div class="ib-body">
        <div class="card" style="padding:10px 14px">
          <div v-if="formErrors.header" style="color:#f56c6c;font-size:12px;margin-bottom:6px">{{ formErrors.header }}</div>
          <div class="grid4">
            <div class="field"><label>来源出库单</label><input readonly :value="header.sourceOutboundNo" /></div>
            <div class="field"><label>入库类型</label><input readonly :value="header.inboundType" /></div>
            <div class="field"><label>调出仓</label><input readonly :value="header.sourceWarehouse" /></div>
            <div class="field"><label>调入仓</label><input readonly :value="header.targetWarehouse" /></div>
            <div v-if="header.remark" class="field field-full"><label>备注</label><input readonly :value="header.remark" style="width:100%" /></div>
          </div>
        </div>
        <div class="card detail-card">
          <div style="display:flex;align-items:center;gap:8px;margin-bottom:6px">
            <b style="font-size:13px">入库明细</b>
            <span v-if="isDiffReturn" style="font-size:11px;color:#e6a23c">⚠ 差异退回单明细不可修改</span>
            <div style="flex:1"></div>
            <span style="font-size:12px;color:#606266">合计数量：<b>{{ totalQty }}</b>&nbsp;&nbsp;成本合计：<b>¥ {{ totalCost.toFixed(2) }}</b></span>
          </div>
          <div v-if="loading" style="text-align:center;padding:30px;color:#909399">加载中...</div>
          <div v-else class="detail-scroll">
            <table>
              <thead><tr><th style="width:36px">#</th><th>商品编号</th><th>商品名称</th><th>单位</th><th style="width:90px">调出数量</th><th style="width:110px">入库数量</th><th>批次</th><th style="width:110px">成本单价</th><th style="width:100px">成本金额</th></tr></thead>
              <tbody>
                <tr v-if="detailList.length === 0"><td colspan="9" style="text-align:center;color:#909399;padding:20px">暂无明细</td></tr>
                <tr v-for="(row, i) in detailList" :key="i">
                  <td>{{ i + 1 }}</td>
                  <td>{{ row.goodsCode }}</td>
                  <td>{{ row.goodsName }}</td>
                  <td>{{ row.unitName }}</td>
                  <td style="text-align:right">{{ Number(row.outQty).toFixed(2) }}</td>
                  <td>
                    <template v-if="!isEditable">{{ Number(row.qty).toFixed(2) }}</template>
                    <input v-else type="number" min="0" step="0.0001" :max="row.outQty" v-model.number="row.qty" @change="onQtyChange(row)" style="width:100%;height:24px;text-align:right" />
                  </td>
                  <td>{{ row.batchNo || '-' }}</td>
                  <td style="text-align:right">{{ Number(row.costPrice).toFixed(6) }}</td>
                  <td style="text-align:right;font-weight:600">{{ Number(row.costAmount).toFixed(2) }}</td>
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
.ib-mask { position: fixed; top: 48px; right: 0; bottom: 0; left: 299px; z-index: 900; display: flex; pointer-events: none; }
.ib-box { flex: 1; background: #fff; display: flex; flex-direction: column; min-width: 0; border-left: 1px solid #e5e7eb; box-shadow: -6px 0 24px rgba(15,46,88,.12); pointer-events: auto; }
.ib-head { display: flex; align-items: center; gap: 10px; height: 46px; padding: 0 16px; border-bottom: 1px solid #e5e7eb; flex-shrink: 0; }
.ib-head b { font-size: 15px; }
.ib-body { flex: 1; overflow: auto; padding: 12px 16px; display: flex; flex-direction: column; gap: 12px; background: #f5f7fa; }
.card { background: #fff; border: 1px solid #e5e7eb; border-radius: 6px; }
.grid4 { display: grid; grid-template-columns: 1fr 1fr; gap: 8px 16px; }
.grid4 .field { display: grid; grid-template-columns: 80px 1fr; align-items: center; gap: 4px; }
.grid4 .field label { font-size: 12px; color: #606266; text-align: right; font-weight: 600; white-space: nowrap; }
.grid4 .field input { height: 28px; padding: 0 6px; border: 1px solid #dcdfe6; border-radius: 4px; font-size: 12px; }
.field-full { grid-column: 1 / -1; }
.detail-card { flex: 1; display: flex; flex-direction: column; min-height: 220px; padding: 10px 14px; }
.detail-scroll { flex: 1; overflow: auto; min-height: 0; }
.detail-scroll table { width: 100%; border-collapse: collapse; font-size: 12px; }
.detail-scroll th { background: #f5f7fa; padding: 6px 8px; text-align: left; font-weight: 600; border-bottom: 1px solid #e5e7eb; }
.detail-scroll td { padding: 4px 8px; border-bottom: 1px solid #f0f0f0; }
.badge { font-size: 11px; padding: 1px 8px; border-radius: 10px; }
.badge.ok { background: #f0f9eb; color: #67c23a; }
.badge.wait { background: #fdf6ec; color: #e6a23c; }
@media (max-width: 900px) { .ib-mask { left: 0; } }
</style>
