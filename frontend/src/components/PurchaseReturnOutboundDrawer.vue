<script setup>
/**
 * 采购退货出库单抽屉（WMS 出库指令）—— 只能从列表进入，不支持手工新建。
 *
 * 出库单由退货申请审核后自动生成（status = PENDING），本抽屉用于：
 *   1. 确认/修改实际出库数量（不可超过申请数量）与批次号
 *   2. 审核出库 → 后端扣减库存、按当前库存成本单价计价、自动生成采购退货单
 *
 * 成本说明：成本单价在审核时由后端按 inv_stock_balance.cost_price 取值回写，
 * 因此 PENDING 状态下成本列为空，审核后才有值。
 */
import { ref, computed, watch } from 'vue'
import { post, get } from '../api/client.js'

const props = defineProps({
  visible: { type: Boolean, default: false },
  /** 出库单 ID 或单号 */
  outboundId: { type: String, default: '' },
  /** 只读模式（已审核单据查看） */
  readonly: { type: Boolean, default: false },
})
const emit = defineEmits(['close', 'save'])

const head = ref({})
const detailList = ref([])
const errors = ref({})
const loading = ref(false)
/** applyDetailId → { qty, goodsCode, goodsName, unitName, spec } 申请明细行（一一对应） */
const applyLines = ref({})
/** 批次下拉缓存：key = `${goodsCode}|${warehouse}` */
const batchCache = ref({})
const batchEditingIndex = ref(-1)

const isApproved = computed(() => head.value.status === 'APPROVED')
const canEdit = computed(() => !props.readonly && !isApproved.value)

const totalQty = computed(() =>
  detailList.value.reduce((s, r) => s + Number(r.qty || 0), 0)
)
const totalAmount = computed(() =>
  detailList.value.reduce((s, r) => s + Number(r.qty || 0) * Number(r.price || 0), 0).toFixed(2)
)
const totalCostAmount = computed(() =>
  detailList.value.reduce((s, r) => s + Number(r.qty || 0) * Number(r.costPrice || 0), 0).toFixed(2)
)

watch(() => [props.visible, props.outboundId], async ([val]) => {
  if (!val || !props.outboundId) return
  errors.value = {}
  batchCache.value = {}
  batchEditingIndex.value = -1
  await loadOutbound(props.outboundId)
})

async function loadOutbound(id) {
  loading.value = true
  try {
    const data = await get(`/purchase/return-outbound/detail?id=${encodeURIComponent(id)}`)
    head.value = data
    detailList.value = (data.details || []).map(d => ({
      detailId: d.detailId,
      applyDetailId: d.applyDetailId || '',
      goodsCode: d.goodsCode,
      goodsName: d.goodsName,
      spec: d.spec || '',
      unitName: d.unitName,
      qty: Number(d.qty || 0),
      price: Number(d.price || 0),
      batchNo: d.batchNo || '',
      productionDate: String(d.productionDate || '').slice(0, 10),
      costPrice: Number(d.costPrice || 0),
      costAmount: Number(d.costAmount || 0),
      returnMode: d.returnMode || 'BY_BILL',
      sourceInboundNo: d.sourceInboundNo || '',
      sourceDetailId: d.sourceDetailId || '',
    }))
    // 拉来源申请单明细：按行建立映射（出库上限按申请行，不按商品聚合）
    await loadApplyLines(data.sourceApplyNo)
  } catch (e) {
    errors.value.header = e.message || '加载出库单失败'
    head.value = {}
    detailList.value = []
  } finally {
    loading.value = false
  }
}

/**
 * 拉来源申请单明细，按 detailId 建映射。
 * <p>关键：出库上限必须按<b>申请行</b>取，不能按 goods_code 聚合 ——
 * 同商品多批次时（申请 7+3 两行），聚合会让两行都显示 10，上限也算错。
 */
async function loadApplyLines(applyNo) {
  applyLines.value = {}
  if (!applyNo) return
  try {
    const data = await get(`/purchase/return-apply/detail?id=${encodeURIComponent(applyNo)}`)
    const map = {}
    ;(data.details || []).forEach(d => {
      map[d.detailId] = {
        qty: Number(d.qty || 0),
        goodsCode: d.goodsCode,
        goodsName: d.goodsName,
        spec: d.spec || '',
        unitName: d.unitName || '',
        price: Number(d.price || 0),
        costPrice: Number(d.costPrice || 0),
        returnMode: d.returnMode || 'BY_BILL',
        sourceInboundNo: d.sourceInboundNo || '',
        sourceDetailId: d.sourceDetailId || '',
        batchNo: d.batchNo || '',
      }
    })
    applyLines.value = map
  } catch (e) { /* 上限拉取失败时仅依赖后端校验 */ }
}

/** 该行对应的申请行数量 */
function applyQtyOf(row) {
  return applyLines.value[row.applyDetailId]?.qty ?? null
}

/** 同一申请行在本单的出库合计（拆批次后多行共享同一 applyDetailId） */
function outboundSumOfApplyLine(applyDetailId) {
  if (!applyDetailId) return 0
  return detailList.value
    .filter(r => r.applyDetailId === applyDetailId)
    .reduce((s, r) => s + Number(r.qty || 0), 0)
}

/** 该申请行还可出库多少（申请数量 − 已分配到各批次的合计） */
function remainOfApplyLine(applyDetailId) {
  const applyQty = applyLines.value[applyDetailId]?.qty
  if (applyQty == null) return null
  const v = applyQty - outboundSumOfApplyLine(applyDetailId)
  return Math.round(v * 10000) / 10000
}

/** 该行是否超限（申请行合计超申请数量 → 整组标红） */
function isRowExceeded(row) {
  const applyQty = applyQtyOf(row)
  if (applyQty == null) return false
  return outboundSumOfApplyLine(row.applyDetailId) > applyQty + 1e-6
}

// ============ 拆批次出库 ============
/**
 * 为某申请行新增一个出库批次行：复制该行的商品/单位/价格/成本等信息，
 * 数量默认取该申请行剩余可出库数量，批次留空由用户选。
 */
function addBatchRow(index) {
  const src = detailList.value[index]
  if (!src) return
  const remain = remainOfApplyLine(src.applyDetailId)
  if (remain != null && remain <= 0) {
    errors.value.details = `商品 ${src.goodsName || src.goodsCode} 已按申请数量分配完，无剩余可拆分`
    return
  }
  detailList.value.splice(index + 1, 0, {
    detailId: '',                     // 新行无 ID，后端全量替换时会新建
    applyDetailId: src.applyDetailId,
    goodsCode: src.goodsCode,
    goodsName: src.goodsName,
    spec: src.spec,
    unitName: src.unitName,
    qty: remain != null ? remain : 0,
    price: src.price,
    batchNo: '',
    productionDate: '',
    costPrice: src.costPrice,
    costAmount: 0,
    returnMode: src.returnMode,
    sourceInboundNo: src.sourceInboundNo,
    sourceDetailId: src.sourceDetailId,
  })
  errors.value.details = ''
}

/** 删除出库行：同一申请行至少保留一行 */
function removeRow(index) {
  const row = detailList.value[index]
  if (!row) return
  const sameLine = detailList.value.filter(r => r.applyDetailId === row.applyDetailId)
  if (sameLine.length <= 1) {
    errors.value.details = '该申请行仅剩一条出库明细，不能删除（如需不退请回退货申请单修改）'
    return
  }
  detailList.value.splice(index, 1)
  if (batchEditingIndex.value === index) batchEditingIndex.value = -1
  errors.value.details = ''
}

// ============ 批次选择 ============
function batchKey(goodsCode) {
  return `${goodsCode}|${head.value.warehouse || ''}`
}

async function openBatchPicker(index) {
  const row = detailList.value[index]
  if (!row) return
  batchEditingIndex.value = batchEditingIndex.value === index ? -1 : index
  if (batchEditingIndex.value !== index) return
  const key = batchKey(row.goodsCode)
  if (batchCache.value[key]) return
  try {
    const rows = await get(
      `/purchase/return-apply/batch-options?goodsCode=${encodeURIComponent(row.goodsCode)}`
      + `&warehouse=${encodeURIComponent(head.value.warehouse || '')}`
    )
    batchCache.value = {
      ...batchCache.value,
      [key]: (Array.isArray(rows) ? rows : []).map(b => ({
        batchNo: b.batchNo,
        productionDate: String(b.productionDate || '').slice(0, 10),
        availableQty: Number(b.availableQty || 0),
        costPrice: Number(b.costPrice || 0),
      })),
    }
  } catch (e) {
    batchCache.value = { ...batchCache.value, [key]: [] }
    errors.value.details = e.message || '加载批次失败'
  }
}

function batchOptionsFor(row) {
  return batchCache.value[batchKey(row.goodsCode)] || []
}

/** 同商品同单位同批次不可重复 */
function isBatchTaken(index, batchNo) {
  const row = detailList.value[index]
  if (!row || !batchNo) return false
  return detailList.value.some((r, i) =>
    i !== index && r.goodsCode === row.goodsCode
    && (r.unitName || '') === (row.unitName || '')
    && (r.batchNo || '') === batchNo
  )
}

/** 选中批次 → 带出生产日期（可用库存仅作提示，不覆盖数量） */
function pickBatch(index, batch) {
  const row = detailList.value[index]
  if (!row) return
  if (isBatchTaken(index, batch.batchNo)) {
    errors.value.details = `批次 ${batch.batchNo} 已被本单其他行使用，同商品同单位同批次不可重复`
    return
  }
  row.batchNo = batch.batchNo
  row.productionDate = batch.productionDate
  row.batchAvailable = batch.availableQty
  batchEditingIndex.value = -1
  errors.value.details = ''
}

// ============ 数量录入：纯手动输入，无加减控件 ============
/** 只保留数字与一个小数点，最多 4 位小数；保留输入中的原始形态（如 "10."） */
function onQtyInput(row, raw) {
  let s = String(raw ?? '').replace(/[^\d.]/g, '')
  const dot = s.indexOf('.')
  if (dot >= 0) {
    s = s.slice(0, dot) + '.' + s.slice(dot + 1).replace(/\./g, '').slice(0, 4)
  }
  row.qtyText = s
  const v = Number(s)
  row.qty = Number.isFinite(v) ? v : 0
}
function onQtyBlur(row) {
  delete row.qtyText
}
function qtyDisplay(row) {
  return row.qtyText !== undefined ? row.qtyText : (row.qty ?? '')
}

function validate() {
  errors.value = {}
  if (detailList.value.length === 0) {
    errors.value.details = '出库明细为空'
    return false
  }
  for (const row of detailList.value) {
    const label = row.goodsName || row.goodsCode
    if (Number(row.qty || 0) <= 0) {
      errors.value.details = `商品 ${label} 的出库数量必须大于 0`
      return false
    }
    if (!row.batchNo) {
      errors.value.details = `商品 ${label} 未选择出库批次`
      return false
    }
  }
  // 逐申请行校验：总出库数量不可大于申请数量
  const grouped = {}
  detailList.value.forEach(r => {
    if (!r.applyDetailId) return
    grouped[r.applyDetailId] = (grouped[r.applyDetailId] || 0) + Number(r.qty || 0)
  })
  for (const [adId, sum] of Object.entries(grouped)) {
    const line = applyLines.value[adId]
    if (!line) continue
    if (sum > line.qty + 1e-6) {
      errors.value.details = `商品 ${line.goodsName || line.goodsCode} 总出库数量 ${Math.round(sum * 10000) / 10000}`
        + ` 超过申请数量 ${line.qty}`
      return false
    }
  }
  // 同商品同单位同批次不可重复
  const seen = new Map()
  for (let i = 0; i < detailList.value.length; i++) {
    const r = detailList.value[i]
    const key = `${r.goodsCode}|${r.unitName || ''}|${r.batchNo}`
    if (seen.has(key)) {
      errors.value.details = `第 ${seen.get(key) + 1} 行与第 ${i + 1} 行重复：`
        + `${r.goodsName || r.goodsCode} · ${r.unitName} · 批次 ${r.batchNo}，请合并数量`
      return false
    }
    seen.set(key, i)
  }
  return true
}

/** 明细提交载荷（保存与审核共用） */
function detailPayload() {
  return detailList.value.map(r => ({
    detailId: r.detailId || '',
    applyDetailId: r.applyDetailId || '',
    goodsCode: r.goodsCode,
    goodsName: r.goodsName,
    spec: r.spec || '',
    unitName: r.unitName,
    qty: Number(r.qty),
    price: Number(r.price),
    batchNo: r.batchNo || '',
    productionDate: r.productionDate || null,
    costPrice: Number(r.costPrice) || 0,
    returnMode: r.returnMode || 'BY_BILL',
    sourceInboundNo: r.sourceInboundNo || '',
    sourceDetailId: r.sourceDetailId || '',
  }))
}

/** 保存出库数量修改（不审核） */
async function saveOutbound() {
  if (!validate()) return
  try {
    const result = await post('/purchase/return-outbound/update', {
      outboundId: head.value.outboundId,
      details: detailPayload(),
    })
    emit('save', result)
    await loadOutbound(head.value.outboundId)
  } catch (e) {
    errors.value.header = '保存失败：' + (e.message || '未知错误')
  }
}

/** 审核出库 → 扣库存 + 生成采购退货单 */
async function auditOutbound() {
  if (!validate()) return
  if (!confirm(`确认审核出库单【${head.value.outboundNo}】？\n\n审核后将：\n· 按当前库存成本单价计价\n· 扣减库存并写入库存流水\n· 自动生成采购退货单\n\n此操作不可直接撤销。`)) return
  try {
    // 先保存数量修改，再审核，避免用户改了数量没保存就审核
    if (canEdit.value) {
      await post('/purchase/return-outbound/update', {
        outboundId: head.value.outboundId,
        details: detailPayload(),
      })
    }
    const result = await post('/purchase/return-outbound/audit', { bizId: head.value.outboundId })
    emit('save', result)
    emit('close')
  } catch (e) {
    errors.value.header = '审核失败：' + (e.message || '未知错误')
  }
}

function closeDrawer() { emit('close') }
</script>

<template>
  <div v-show="visible" class="return-drawer-mask">
    <div class="return-drawer-box">
      <div class="return-drawer-head">
        <b>采购退货出库单 {{ head.outboundNo || '' }}</b>
        <span v-if="isApproved" class="badge ok">已审核</span>
        <span v-else class="badge wait">待审核</span>
        <div style="flex:1"></div>
        <div class="actions">
          <button class="btn" @click="closeDrawer">关闭</button>
          <button v-if="canEdit" class="btn" @click="saveOutbound">保存</button>
          <button v-if="canEdit" class="btn primary" @click="auditOutbound">审核出库</button>
        </div>
      </div>

      <div class="return-drawer-body">
        <div v-if="errors.header" class="err-banner">{{ errors.header }}</div>

        <!-- 头部（只读） -->
        <div class="card" style="padding:12px">
          <div style="font-weight:900;margin-bottom:10px;color:var(--primary)">单据信息</div>
          <div class="grid4">
            <div class="field">
              <label>出库单号</label>
              <input readonly :value="head.outboundNo || ''" />
            </div>
            <div class="field">
              <label>来源退货申请</label>
              <input readonly :value="head.sourceApplyNo || ''" />
            </div>
            <div class="field">
              <label>供应商</label>
              <input readonly :value="head.supplierName || ''" />
            </div>
            <div class="field">
              <label>仓库</label>
              <input readonly :value="head.warehouse || ''" />
            </div>
            <div class="field">
              <label>出库日期</label>
              <input readonly :value="String(head.billDate || '').slice(0, 10)" />
            </div>
            <div class="field">
              <label>是否已更新库存</label>
              <input readonly :value="head.stockUpdated ? '是' : '否'" />
            </div>
            <div class="field">
              <label>是否已生成退货单</label>
              <input readonly :value="head.returnGenerated ? '是' : '否'" />
            </div>
            <div class="field">
              <label>备注</label>
              <input readonly :value="head.remark || ''" />
            </div>
          </div>
        </div>

        <!-- 明细 -->
        <div class="card detail-card">
          <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:8px">
            <div style="font-weight:900;color:var(--primary)">出库明细</div>
            <div style="font-size:12px;color:#5d7896">
              {{ canEdit ? '同一申请行总出库数量不可超过申请数量；批次库存不够时点【拆批次】分多个批次出库' : '已审核单据，明细只读' }}
            </div>
          </div>
          <div v-if="errors.details" style="color:var(--danger);font-size:12px;margin-bottom:6px">{{ errors.details }}</div>
          <div v-if="loading" class="empty-detail">加载中...</div>
          <div v-else-if="detailList.length === 0" class="empty-detail">暂无出库明细</div>
          <div v-else class="detail-scroll">
            <table>
              <thead>
                <tr>
                  <th style="width:40px">#</th>
                  <th style="width:78px">退货方式</th>
                  <th style="min-width:110px">商品编号</th>
                  <th style="min-width:150px">商品名称</th>
                  <th style="min-width:90px">规格</th>
                  <th style="width:60px">单位</th>
                  <th style="width:80px">申请数量</th>
                  <th style="width:100px">出库数量 <span v-if="canEdit" class="req">*</span></th>
                  <th style="width:78px">未分配</th>
                  <th style="width:110px">单价</th>
                  <th style="width:110px">金额</th>
                  <th style="min-width:130px">批次号 <span v-if="canEdit" class="req">*</span></th>
                  <th style="min-width:100px">生产日期</th>
                  <th style="width:100px">成本单价</th>
                  <th style="width:90px">成本金额</th>
                  <th style="min-width:120px">源单号</th>
                  <th v-if="canEdit" style="width:96px">操作</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="(row, index) in detailList" :key="row.detailId || index">
                  <td>{{ index + 1 }}</td>
                  <td>
                    <span class="tag" :class="row.returnMode === 'BY_GOODS' ? 'by-goods' : 'by-bill'">
                      {{ row.returnMode === 'BY_GOODS' ? '按品退货' : '按单退货' }}
                    </span>
                  </td>
                  <td>{{ row.goodsCode }}</td>
                  <td>{{ row.goodsName }}</td>
                  <td>{{ row.spec || '-' }}</td>
                  <td>{{ row.unitName }}</td>
                  <!-- 申请数量：按申请行取（一一对应），非按商品聚合 -->
                  <td style="text-align:right;color:var(--primary);font-weight:700">
                    {{ applyQtyOf(row) ?? '-' }}
                  </td>
                  <td>
                    <input v-if="canEdit" type="text" inputmode="decimal"
                           :value="qtyDisplay(row)"
                           :class="{ 'qty-error': isRowExceeded(row) }"
                           :title="isRowExceeded(row) ? `该申请行总出库 ${outboundSumOfApplyLine(row.applyDetailId)} 已超申请数量 ${applyQtyOf(row)}` : ''"
                           @input="onQtyInput(row, $event.target.value)"
                           @blur="onQtyBlur(row)"
                           style="width:100%;height:24px;text-align:right" />
                    <span v-else style="display:block;text-align:right">{{ row.qty }}</span>
                  </td>
                  <!-- 未分配：该申请行还剩多少没分配到批次（拆批次时看这个） -->
                  <td style="text-align:right"
                      :class="{ 'remain-warn': (remainOfApplyLine(row.applyDetailId) ?? 0) < 0 }">
                    {{ remainOfApplyLine(row.applyDetailId) ?? '-' }}
                  </td>
                  <td style="text-align:right">{{ Number(row.price).toFixed(4) }}</td>
                  <td style="text-align:right;font-weight:700">
                    {{ (Number(row.qty || 0) * Number(row.price || 0)).toFixed(2) }}
                  </td>
                  <!-- 批次号：可改可新增（点击展开仓库内有库存批次） -->
                  <td class="batch-cell">
                    <template v-if="canEdit">
                      <button class="batch-btn" :class="{ empty: !row.batchNo }" @click="openBatchPicker(index)">
                        {{ row.batchNo || '选择批次' }}
                      </button>
                      <div v-if="batchEditingIndex === index" class="batch-dropdown">
                        <div v-if="batchOptionsFor(row).length === 0" class="batch-empty">
                          该商品在【{{ head.warehouse || '未知仓库' }}】无可用批次库存
                        </div>
                        <div v-for="b in batchOptionsFor(row)" :key="b.batchNo"
                             class="batch-opt" :class="{ taken: isBatchTaken(index, b.batchNo) }"
                             :title="isBatchTaken(index, b.batchNo) ? '该批次已被本单其他行使用' : ''"
                             @click="pickBatch(index, b)">
                          <span class="bn">{{ b.batchNo }}</span>
                          <span class="pd">{{ b.productionDate || '-' }}</span>
                          <span class="aq">
                            <template v-if="isBatchTaken(index, b.batchNo)">已占用</template>
                            <template v-else>可用 {{ b.availableQty }}</template>
                          </span>
                        </div>
                      </div>
                    </template>
                    <span v-else>{{ row.batchNo || '-' }}</span>
                  </td>
                  <!-- 生产日期：随批次自动带出，只读 -->
                  <td>{{ row.productionDate || '-' }}</td>
                  <td style="text-align:right">
                    {{ Number(row.costPrice || 0) > 0 ? Number(row.costPrice).toFixed(6) : '审核后计算' }}
                  </td>
                  <td style="text-align:right;font-weight:700">
                    {{ (Number(row.qty || 0) * Number(row.costPrice || 0)).toFixed(2) }}
                  </td>
                  <td>{{ row.sourceInboundNo || '-' }}</td>
                  <td v-if="canEdit">
                    <button class="link link-btn" title="为该申请行新增一个出库批次（拆批次出库）"
                            @click="addBatchRow(index)">拆批次</button>
                    <button class="link link-btn danger-link" @click="removeRow(index)">删除</button>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>

        <div class="summary">
          <span>合计出库数量：<b>{{ totalQty }}</b></span>
          <span>合计退货金额：<b>¥ {{ totalAmount }}</b></span>
          <span v-if="isApproved">合计成本金额：<b>¥ {{ totalCostAmount }}</b></span>
          <span>行数：<b>{{ detailList.length }}</b></span>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.return-drawer-mask {
  position: fixed;
  top: 48px; right: 0; bottom: 0; left: 299px;
  z-index: 900;
  display: flex;
  pointer-events: none;
  animation: fadeIn 0.2s ease;
}
.return-drawer-box {
  flex: 1;
  background: #fff;
  display: flex; flex-direction: column;
  min-width: 0;
  border-left: 1px solid var(--line);
  box-shadow: -6px 0 24px rgba(15, 46, 88, 0.12);
  pointer-events: auto;
  animation: slideIn 0.25s ease;
}
.return-drawer-head {
  display: flex; align-items: center; gap: 10px;
  height: 46px; padding: 0 16px;
  border-bottom: 1px solid var(--line-soft);
}
.return-drawer-head b { font-size: 15px; }
.return-drawer-body {
  flex: 1; overflow: auto;
  padding: 12px 16px;
  display: flex; flex-direction: column; gap: 12px;
  background: #f5f7fa;
}
.detail-card { flex: 1; display: flex; flex-direction: column; min-height: 260px; padding: 12px; }
.detail-scroll { flex: 1; overflow: auto; min-height: 0; }
.empty-detail { padding: 40px; text-align: center; color: #909399; font-size: 13px; background: #fafbfc; border: 1px dashed #e5e7eb; border-radius: 8px; }
.err-banner { padding: 8px 12px; background: #fef0f0; border: 1px solid #fde2e2; border-radius: 6px; color: var(--danger); font-size: 12px; }
.field .req { color: #f56c6c; }

/* 退货方式标签 */
.tag {
  display: inline-block; padding: 1px 6px;
  border-radius: 3px; font-size: 11px; font-weight: 700; white-space: nowrap;
}
.tag.by-bill { background: #ecf5ff; color: #409eff; border: 1px solid #d9ecff; }
.tag.by-goods { background: #fdf6ec; color: #e6a23c; border: 1px solid #faecd8; }

/* 数量超申请数量标红 */
.qty-error { border-color: #f56c6c !important; background: #fef0f0; }
.remain-warn { color: #f56c6c; font-weight: 700; }

/* 批次下拉（与申请单抽屉一致） */
.batch-cell { position: relative; }
.batch-btn {
  width: 100%; height: 24px; padding: 0 6px;
  border: 1px solid #dcdfe6; border-radius: 3px;
  background: #fff; cursor: pointer;
  font-size: 12px; text-align: left;
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
}
.batch-btn:hover { border-color: #409eff; }
.batch-btn.empty { color: #c0c4cc; }
.batch-dropdown {
  position: absolute; top: 100%; left: 0; z-index: 30;
  min-width: 240px;
  background: #fff; border: 1px solid #dcdfe6; border-radius: 4px;
  box-shadow: 0 6px 20px rgba(0, 0, 0, 0.12);
  max-height: 220px; overflow-y: auto;
}
.batch-opt {
  display: grid; grid-template-columns: 1fr 90px 76px;
  gap: 6px; padding: 6px 8px;
  cursor: pointer; font-size: 12px;
  border-bottom: 1px solid #f0f2f5;
}
.batch-opt:last-child { border-bottom: none; }
.batch-opt:hover { background: #ecf5ff; }
.batch-opt.taken { color: #c0c4cc; background: #fafafa; cursor: not-allowed; }
.batch-opt.taken:hover { background: #fafafa; }
.batch-opt .bn { font-family: var(--font-mono); }
.batch-opt .pd { color: #666; }
.batch-opt .aq { color: #67c23a; text-align: right; }
.batch-empty { padding: 12px; text-align: center; color: #909399; font-size: 12px; }
@keyframes fadeIn { from { opacity: 0; } to { opacity: 1; } }
@keyframes slideIn { from { transform: translateX(60px); opacity: 0.6 } to { transform: translateX(0); opacity: 1 } }
@media (max-width: 900px) {
  .return-drawer-mask { left: 0; }
}
</style>