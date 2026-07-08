<script setup>
/**
 * 调拨申请单抽屉 —— 申请从转出仓向转入仓调拨商品。
 *
 * 交互：
 *   - 头部：转出仓 / 转入仓（下拉）、申请日期、备注
 *   - 明细：内联商品选择（InlineGoodsPicker）+ 数量 + 备注，支持多商品
 *   - 保存：POST /transfer/apply/create | update（仅 PENDING 可编辑）
 *   - 查看模式（已审核）：全只读
 */
import { ref, computed, watch } from 'vue'
import { post } from '../api/client.js'
import InlineGoodsPicker from './InlineGoodsPicker.vue'

const props = defineProps({
  visible: { type: Boolean, default: false },
  mode: { type: String, default: 'add' }, // add / edit / view
  editData: { type: Object, default: null },
})
const emit = defineEmits(['close', 'save'])

const loading = ref(false)
const warehouseList = ref([])
const allGoods = ref([])
const formErrors = ref({})

const header = ref({ sourceWarehouse: '', targetWarehouse: '', applyDate: new Date().toISOString().slice(0, 10), remark: '', applyNo: '', status: '' })
const detailList = ref([])

const isView = computed(() => props.mode === 'view' || header.value.status === 'APPROVED')
const totalQty = computed(() => detailList.value.reduce((s, r) => s + (Number(r.qty) || 0), 0))

function makeEmptyRow() {
  return { goodsCode: '', goodsName: '', unitName: '', qty: null, remark: '', goodsSearch: '' }
}

watch(() => props.visible, async (v) => {
  if (!v) return
  formErrors.value = {}
  header.value = { sourceWarehouse: '', targetWarehouse: '', applyDate: new Date().toISOString().slice(0, 10), remark: '', applyNo: '', status: '' }
  detailList.value = [makeEmptyRow()]
  await loadBase()
  if (props.mode !== 'add' && props.editData) await loadEdit(props.editData)
})

async function loadBase() {
  try {
    const w = await post('/base/warehouse/page', { pageNo: 1, pageSize: 500, filters: {} })
    warehouseList.value = (w.records || []).filter(x => x.warehouseName || x.name)
  } catch (_) { warehouseList.value = [] }
  try {
    const g = await post('/base/goods/page', { pageNo: 1, pageSize: 2000, filters: {} })
    allGoods.value = (g.records || []).filter(x => String(x.status || '').toUpperCase() !== 'STOPPED')
  } catch (_) { allGoods.value = [] }
}

async function loadEdit(editData) {
  loading.value = true
  try {
    const raw = editData._raw || editData
    const data = await post('/transfer/apply/detail', { applyId: raw.applyId || raw.c0 })
    header.value = {
      sourceWarehouse: data.sourceWarehouse || '', targetWarehouse: data.targetWarehouse || '',
      applyDate: (data.applyDate || '').toString().slice(0, 10),
      remark: data.remark || '', applyNo: data.applyNo || '', status: data.status || '',
    }
    detailList.value = (data.details || []).map(d => ({
      goodsCode: d.goodsCode, goodsName: d.goodsName, unitName: d.unitName || '',
      qty: Number(d.qty || 0), outQty: Number(d.outQty || 0), inQty: Number(d.inQty || 0),
      remark: d.remark || '', goodsSearch: d.goodsCode,
    }))
  } catch (e) { formErrors.value = { header: e.message || '加载申请单失败' } }
  finally { loading.value = false }
}

// ===== 商品选择 =====
function onGoodsSelect(row, g) {
  row.goodsCode = g.goodsCode
  row.goodsName = g.goodsName || ''
  row.unitName = g.baseUnit || g.unitName || ''
  row.goodsSearch = g.goodsCode
  if (!row.qty) row.qty = 1
}

function addRow() { detailList.value.push(makeEmptyRow()) }
function removeRow(i) {
  if (detailList.value.length <= 1) { alert('至少保留一行'); return }
  detailList.value.splice(i, 1)
}

function validate() {
  const err = {}
  if (!header.value.sourceWarehouse) err.sourceWh = '请选择转出仓库'
  if (!header.value.targetWarehouse) err.targetWh = '请选择转入仓库'
  if (header.value.sourceWarehouse && header.value.sourceWarehouse === header.value.targetWarehouse) err.sameWh = '转出仓与转入仓不能相同'
  if (detailList.value.filter(r => r.goodsCode).length === 0) err.details = '请至少添加一条商品明细'
  if (detailList.value.some(r => r.goodsCode && (!Number(r.qty) || Number(r.qty) <= 0))) err.details = '商品数量必须大于 0'
  formErrors.value = err
  return Object.keys(err).length === 0
}

async function save() {
  if (!validate()) { alert(Object.values(formErrors.value)[0]); return }
  loading.value = true
  try {
    const isEdit = props.mode === 'edit'
    const payload = {
      sourceWarehouse: header.value.sourceWarehouse,
      targetWarehouse: header.value.targetWarehouse,
      applyDate: header.value.applyDate,
      remark: header.value.remark,
      details: detailList.value.filter(r => r.goodsCode).map(r => ({
        goodsCode: r.goodsCode, goodsName: r.goodsName, unitName: r.unitName,
        qty: Number(r.qty), remark: r.remark || '',
      })),
    }
    if (isEdit) payload.applyId = props.editData._raw?.applyId || props.editData.c0
    await post(isEdit ? '/transfer/apply/update' : '/transfer/apply/create', payload)
    emit('save')
    emit('close')
  } catch (e) { alert('保存失败：' + (e.message || '未知错误')) }
  finally { loading.value = false }
}

function close() { emit('close') }
</script>

<template>
  <div v-if="visible" class="apply-mask">
    <div class="apply-box">
      <div class="apply-head">
        <b>{{ isView ? '查看调拨申请单' : (props.mode === 'edit' ? '编辑调拨申请单' : '新建调拨申请单') }}</b>
        <span v-if="header.applyNo" style="margin-left:10px;color:#606266;font-size:12px">{{ header.applyNo }}</span>
        <span v-if="header.status" class="badge" :class="header.status === 'APPROVED' ? 'ok' : 'wait'" style="margin-left:8px">{{ { PENDING: '待审核', APPROVED: '已审核' }[header.status] || header.status }}</span>
        <div style="flex:1"></div>
        <button class="btn" @click="close">关闭</button>
        <button v-if="!isView" class="btn primary" @click="save" :disabled="loading">保存</button>
      </div>
      <div class="apply-body">
        <div class="card" style="padding:10px 14px">
          <div v-if="formErrors.header" style="color:#f56c6c;font-size:12px;margin-bottom:6px">{{ formErrors.header }}</div>
          <div class="grid4">
            <div class="field"><label>转出仓库 <span class="req">*</span></label>
              <select v-model="header.sourceWarehouse" :disabled="isView"><option value="">请选择</option>
                <option v-for="w in warehouseList" :key="w.warehouseCode || w.code" :value="w.warehouseName || w.name">{{ w.warehouseName || w.name }}</option>
              </select>
            </div>
            <div class="field"><label>转入仓库 <span class="req">*</span></label>
              <select v-model="header.targetWarehouse" :disabled="isView"><option value="">请选择</option>
                <option v-for="w in warehouseList" :key="w.warehouseCode || w.code" :value="w.warehouseName || w.name">{{ w.warehouseName || w.name }}</option>
              </select>
            </div>
            <div class="field"><label>申请日期</label><input type="date" v-model="header.applyDate" :disabled="isView" /></div>
            <div class="field field-full"><label>备注</label><input v-model="header.remark" :disabled="isView" placeholder="选填" style="width:100%" /></div>
          </div>
        </div>
        <div class="card detail-card">
          <div style="display:flex;align-items:center;gap:8px;margin-bottom:6px">
            <b style="font-size:13px">申请明细</b>
            <span v-if="formErrors.details" style="color:#f56c6c;font-size:12px">{{ formErrors.details }}</span>
            <div style="flex:1"></div>
            <span style="font-size:12px;color:#606266">合计数量：<b>{{ totalQty }}</b></span>
            <button v-if="!isView" class="btn primary" style="height:24px;font-size:11px;padding:0 8px" @click="addRow">+ 添加商品</button>
          </div>
          <div class="detail-scroll">
            <table>
              <thead><tr><th style="width:36px">#</th><th style="min-width:200px">商品 <span v-if="!isView" class="req">*</span></th><th>单位</th><th style="width:90px">申请数量 <span v-if="!isView" class="req">*</span></th><th style="width:80px">调出数量</th><th style="width:80px">调入数量</th><th style="min-width:100px">备注</th><th v-if="!isView" style="width:50px">操作</th></tr></thead>
              <tbody>
                <tr v-if="detailList.length === 0"><td colspan="8" style="text-align:center;color:#909399;padding:20px">暂无明细</td></tr>
                <tr v-for="(row, i) in detailList" :key="i">
                  <td>{{ i + 1 }}</td>
                  <td>
                    <template v-if="isView">{{ row.goodsCode }} {{ row.goodsName }}</template>
                    <InlineGoodsPicker v-else v-model="row.goodsSearch" :goods-list="allGoods" :existing-codes="detailList.filter((_, x) => x !== i).map(r => r.goodsCode).filter(Boolean)" @select="g => onGoodsSelect(row, g)" />
                  </td>
                  <td>{{ row.unitName }}</td>
                  <td>
                    <template v-if="isView">{{ row.qty }}</template>
                    <input v-else type="number" min="0" step="0.0001" v-model.number="row.qty" style="width:100%;height:24px;text-align:right" />
                  </td>
                  <td style="text-align:right">{{ Number(row.outQty || 0).toFixed(2) }}</td>
                  <td style="text-align:right">{{ Number(row.inQty || 0).toFixed(2) }}</td>
                  <td><input v-model="row.remark" :disabled="isView" placeholder="选填" style="width:100%;height:24px" /></td>
                  <td v-if="!isView"><button class="link link-btn danger-link" @click="removeRow(i)">删除</button></td>
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
.apply-mask { position: fixed; top: 48px; right: 0; bottom: 0; left: 299px; z-index: 900; display: flex; pointer-events: none; }
.apply-box { flex: 1; background: #fff; display: flex; flex-direction: column; min-width: 0; border-left: 1px solid #e5e7eb; box-shadow: -6px 0 24px rgba(15,46,88,.12); pointer-events: auto; }
.apply-head { display: flex; align-items: center; gap: 10px; height: 46px; padding: 0 16px; border-bottom: 1px solid #e5e7eb; flex-shrink: 0; }
.apply-head b { font-size: 15px; }
.apply-body { flex: 1; overflow: auto; padding: 12px 16px; display: flex; flex-direction: column; gap: 12px; background: #f5f7fa; }
.card { background: #fff; border: 1px solid #e5e7eb; border-radius: 6px; }
.grid4 { display: grid; grid-template-columns: 1fr 1fr; gap: 8px 16px; }
.grid4 .field { display: grid; grid-template-columns: 78px 1fr; align-items: center; gap: 4px; }
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
@media (max-width: 900px) { .apply-mask { left: 0; } }
</style>
