<script setup>
/**
 * 销售退货入库单抽屉 —— 只能从列表进入，不支持手工新建。
 *
 * 入库单由退货单审核后自动生成（status = PENDING），本抽屉用于：
 *   1. 选择每行入库仓库（正品入主仓、不良品入次品仓等）
 *   2. 填写生产日期 → 自动生成批次号（不选库内批次）
 *   3. 确认/修改实际入库数量（不可超过退货数量，可为 0）
 *   4. 审核入库 → 后端按库存成本单价计价、回库、回写入库数量
 *
 * 重要约定：
 *   · 退货单明细不含批次号和生产日期（在入库环节补填）
 *   · 批次号由生产日期自动生成（yyyyMMdd 格式，无前缀），可手动修改
 *   · 未填生产日期时批次号留空，库存支持空批次和空生产日期
 *   · 不选库内已有批次，退货入库创建新的批次记录
 *   · 不能删除明细行 —— 不入库的行将数量填 0 即可
 *   · 仓库在明细行级选择，不同商品可入不同仓库
 */
import { ref, computed, watch } from 'vue'
import { post, get } from '../api/client.js'

const props = defineProps({
  visible: { type: Boolean, default: false },
  /** 入库单 ID 或单号 */
  inboundId: { type: String, default: '' },
  /** 只读模式（已审核单据查看） */
  readonly: { type: Boolean, default: false },
})
const emit = defineEmits(['close', 'save'])

const head = ref({})
const detailList = ref([])
const errors = ref({})
const loading = ref(false)
/** applyDetailId → { qty, goodsCode, goodsName, unitName, spec } 申请明细行映射 */
const applyLines = ref({})
/** 仓库下拉选项 */
const warehouseOptions = ref([])

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

watch(() => [props.visible, props.inboundId], async ([val]) => {
  if (!val || !props.inboundId) return
  errors.value = {}
  await loadWarehouseOptions()
  await loadInbound(props.inboundId)
})

async function loadWarehouseOptions() {
  try {
    const wh = await post('/base/warehouse/page', { pageNo: 1, pageSize: 200, filters: {} })
    warehouseOptions.value = (wh.records || []).map(r => r.warehouseName).filter(Boolean)
  } catch (e) {
    warehouseOptions.value = []
  }
}

async function loadInbound(id) {
  loading.value = true
  try {
    const data = await get(`/sales/return-inbound/detail?id=${encodeURIComponent(id)}`)
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
      productionDate: String(d.productionDate || '').slice(0, 10),
      batchNo: d.batchNo || '',
      warehouse: d.warehouse || '',
      costPrice: Number(d.costPrice || 0),
      costAmount: Number(d.costAmount || 0),
      returnMode: d.returnMode || 'BY_BILL',
      sourceOutboundNo: d.sourceOutboundNo || '',
      sourceDetailId: d.sourceDetailId || '',
    }))
    await loadApplyLines(data.sourceApplyNo)
  } catch (e) {
    errors.value.header = e.message || '加载入库单失败'
    head.value = {}
    detailList.value = []
  } finally {
    loading.value = false
  }
}

/**
 * 拉来源退货单明细，按 detailId 建映射。
 * 入库上限必须按申请行取，不能按 goods_code 聚合 ——
 * 同商品多行申请（如 7+3 两行），聚合会让上限算错。
 */
async function loadApplyLines(applyNo) {
  applyLines.value = {}
  if (!applyNo) return
  try {
    const data = await get(`/sales/return-order/detail?id=${encodeURIComponent(applyNo)}`)
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
        sourceOutboundNo: d.sourceOutboundNo || '',
        sourceDetailId: d.sourceDetailId || '',
      }
    })
    applyLines.value = map
  } catch (e) { /* 上限拉取失败时仅依赖后端校验 */ }
}

/** 该行对应的申请行数量 */
function applyQtyOf(row) {
  return applyLines.value[row.applyDetailId]?.qty ?? null
}

/** 同一申请行在本单的入库合计（拆批次后多行共享同一 applyDetailId） */
function inboundSumOfApplyLine(applyDetailId) {
  if (!applyDetailId) return 0
  return detailList.value
    .filter(r => r.applyDetailId === applyDetailId)
    .reduce((s, r) => s + Number(r.qty || 0), 0)
}

/** 该申请行还可入库多少（申请数量 − 已分配到各批次的合计） */
function remainOfApplyLine(applyDetailId) {
  const applyQty = applyLines.value[applyDetailId]?.qty
  if (applyQty == null) return null
  const v = applyQty - inboundSumOfApplyLine(applyDetailId)
  return Math.round(v * 10000) / 10000
}

/** 该行是否超限（申请行合计超申请数量 → 整组标红） */
function isRowExceeded(row) {
  const aq = applyQtyOf(row)
  if (aq == null) return false
  return inboundSumOfApplyLine(row.applyDetailId) > aq + 1e-6
}

/** 点击"暂无库存"时，用退货单的仓库回填默认值 */
function defaultWarehouse() {
  // 优先取该批明细里已有的仓库
  const existing = detailList.value.find(r => r.warehouse)
  if (existing) return existing.warehouse
  // 其次取退货单仓库（如果有的话）
  return head.value.warehouse || ''
}

// ============ 生产日期 → 自动生成批次号 ============
/**
 * 选择生产日期后，自动生成批次号 yyyyMMdd 格式（无前缀）。
 * 清空生产日期时，批次号也清空。
 * 批次号可手动修改。
 */
function onProductionDateChange(row) {
  if (row.batchNoManuallySet) return
  if (row.productionDate) {
    const d = row.productionDate.replace(/-/g, '')
    row.batchNo = d
  } else {
    row.batchNo = ''
  }
}
function onBatchNoInput(row) {
  row.batchNoManuallySet = true
}
function onBatchNoBlur(row) {
  // 如果用户清空了批次号且生产日期有值，重新自动生成
  if (!row.batchNo && row.productionDate && row.batchNoManuallySet) {
    row.batchNoManuallySet = false
    onProductionDateChange(row)
  }
}

// ============ 拆批次入库 ============
/**
 * 为某申请行新增一个入库批次行：复制该行的商品/单位/价格/成本等信息，
 * 数量默认取该申请行剩余可入库数量，生产日期和批次号留空由用户填写。
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
    productionDate: '',
    batchNo: '',
    batchNoManuallySet: false,
    warehouse: src.warehouse || defaultWarehouse(),
    costPrice: src.costPrice,
    costAmount: 0,
    returnMode: src.returnMode,
    sourceOutboundNo: src.sourceOutboundNo,
    sourceDetailId: src.sourceDetailId,
    qtyText: undefined,
  })
  errors.value.details = ''
}

// ============ 数量录入：纯手动输入 ============
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
    errors.value.details = '入库明细为空'
    return false
  }
  for (const row of detailList.value) {
    const label = row.goodsName || row.goodsCode
    if (Number(row.qty || 0) < 0) {
      errors.value.details = `商品 ${label} 的入库数量不能为负数`
      return false
    }
    // qty > 0 时必须填仓库、生产日期、批次号
    if (Number(row.qty || 0) > 0) {
      if (!row.warehouse) {
        errors.value.details = `商品 ${label} 未选择入库仓库`
        return false
      }
      if (!row.productionDate) {
        errors.value.details = `商品 ${label} 未填写生产日期`
        return false
      }
      if (!row.batchNo) {
        errors.value.details = `商品 ${label} 未填写批次号（选择生产日期后自动生成，也可手动修改）`
        return false
      }
    }
  }
  // 逐申请行校验：总入库数量不可大于申请数量
  const grouped = {}
  detailList.value.forEach(r => {
    if (!r.applyDetailId) return
    grouped[r.applyDetailId] = (grouped[r.applyDetailId] || 0) + Number(r.qty || 0)
  })
  for (const [adId, sum] of Object.entries(grouped)) {
    const line = applyLines.value[adId]
    if (!line) continue
    if (sum > line.qty + 1e-6) {
      errors.value.details = `商品 ${line.goodsName || line.goodsCode} 总入库数量 ${Math.round(sum * 10000) / 10000}`
        + ` 超过申请数量 ${line.qty}`
      return false
    }
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
    productionDate: r.productionDate || null,
    batchNo: r.batchNo || '',
    warehouse: r.warehouse || '',
    costPrice: Number(r.costPrice) || 0,
    returnMode: r.returnMode || 'BY_BILL',
    sourceOutboundNo: r.sourceOutboundNo || '',
    sourceDetailId: r.sourceDetailId || '',
  }))
}

/** 保存入库数量修改（不审核，允许添加生产日期和批次号） */
async function saveInbound() {
  if (!validate()) return
  try {
    const result = await post('/sales/return-inbound/update', {
      inboundId: head.value.inboundId,
      details: detailPayload(),
    })
    emit('save', result)
    await loadInbound(head.value.inboundId)
  } catch (e) {
    errors.value.header = '保存失败：' + (e.message || '未知错误')
  }
}

/** 审核入库 → 回库存 + 生成销售退货单 */
async function auditInbound() {
  if (!validate()) return
  if (!confirm(`确认审核入库单【${head.value.inboundNo}】？\n\n审核后将：\n· 按当前库存成本单价计价\n· 退货商品回库并写入库存流水\n· 回写入库数量到退货单\n\n此操作不可直接撤销。`)) return
  try {
    if (canEdit.value) {
      await post('/sales/return-inbound/update', {
        inboundId: head.value.inboundId,
        details: detailPayload(),
      })
    }
    const result = await post('/sales/return-inbound/audit', { bizId: head.value.inboundId })
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
        <b>销售退货入库单 {{ head.inboundNo || '' }}</b>
        <span v-if="isApproved" class="badge ok">已审核</span>
        <span v-else class="badge wait">待审核</span>
        <div style="flex:1"></div>
        <div class="actions">
          <button class="btn" @click="closeDrawer">关闭</button>
          <button v-if="canEdit" class="btn" @click="saveInbound">保存</button>
          <button v-if="canEdit" class="btn primary" @click="auditInbound">审核入库</button>
        </div>
      </div>

      <div class="return-drawer-body">
        <div v-if="errors.header" class="err-banner">{{ errors.header }}</div>

        <!-- 头部（只读，无仓库字段） -->
        <div class="card" style="padding:12px">
          <div style="font-weight:900;margin-bottom:10px;color:var(--primary)">单据信息</div>
          <div class="grid4">
            <div class="field">
              <label>入库单号</label>
              <input readonly :value="head.inboundNo || ''" />
            </div>
            <div class="field">
              <label>来源退货单</label>
              <input readonly :value="head.sourceApplyNo || ''" />
            </div>
            <div class="field">
              <label>客户</label>
              <input readonly :value="head.customerName || ''" />
            </div>
            <div class="field">
              <label>入库日期</label>
              <input readonly :value="String(head.billDate || '').slice(0, 10)" />
            </div>
            <div class="field">
              <label>是否已更新库存</label>
              <input readonly :value="head.stockUpdated ? '是' : '否'" />
            </div>
            <div class="field" v-if="head.remark">
              <label>备注</label>
              <input readonly :value="head.remark || ''" />
            </div>
          </div>
        </div>

        <!-- 明细 -->
        <div class="card detail-card">
          <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:8px">
            <div style="font-weight:900;color:var(--primary)">入库明细</div>
            <div style="font-size:12px;color:#5d7896">
              {{ canEdit
                 ? '依次选择仓库 → 填写生产日期（自动生成批次号）→ 录入数量；点击【拆批次】将同一申请行拆分多批次入库；不入库的行数量填 0'
                 : '已审核单据，明细只读' }}
            </div>
          </div>
          <div v-if="errors.details" style="color:var(--danger);font-size:12px;margin-bottom:6px">{{ errors.details }}</div>
          <div v-if="loading" class="empty-detail">加载中...</div>
          <div v-else-if="detailList.length === 0" class="empty-detail">暂无入库明细</div>
          <div v-else class="detail-scroll">
            <table>
              <thead>
                <tr>
                  <th style="width:40px">#</th>
                  <th style="width:78px">退货方式</th>
                  <th style="min-width:110px">商品编号</th>
                  <th style="min-width:140px">商品名称</th>
                  <th style="min-width:80px">规格</th>
                  <th style="width:56px">单位</th>
                  <th style="width:100px">仓库 <span v-if="canEdit" class="req">*</span></th>
                  <th style="width:80px">申请数量</th>
                  <th style="width:100px">入库数量</th>
                  <th style="width:72px">未分配</th>
                  <th style="width:105px">生产日期 <span v-if="canEdit" class="req">*</span></th>
                  <th style="min-width:130px">批次号 <span v-if="canEdit" class="req">*</span></th>
                  <th style="width:105px">单价</th>
                  <th style="width:100px">金额</th>
                  <th style="width:98px">成本单价</th>
                  <th style="width:88px">成本金额</th>
                  <th style="min-width:115px">源单号</th>
                  <th v-if="canEdit" style="width:72px">操作</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="(row, index) in detailList" :key="row.detailId || index"
                    :class="{ 'zero-qty': Number(row.qty || 0) === 0 }">
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
                  <!-- 仓库：行级下拉选择 -->
                  <td>
                    <select v-if="canEdit" v-model="row.warehouse"
                            style="width:100%;height:24px;font-size:12px;padding:0 4px">
                      <option value="">--请选择--</option>
                      <option v-for="w in warehouseOptions" :key="w" :value="w">{{ w }}</option>
                    </select>
                    <span v-else>{{ row.warehouse || '-' }}</span>
                  </td>
                  <td style="text-align:right;color:var(--primary);font-weight:700">
                    {{ applyQtyOf(row) ?? '-' }}
                  </td>
                  <td>
                    <input v-if="canEdit" type="text" inputmode="decimal"
                           :value="qtyDisplay(row)"
                           :class="{ 'qty-error': isRowExceeded(row) }"
                           :title="isRowExceeded(row) ? `该申请行总入库 ${inboundSumOfApplyLine(row.applyDetailId)} 已超申请数量 ${applyQtyOf(row)}` : ''"
                           @input="onQtyInput(row, $event.target.value)"
                           @blur="onQtyBlur(row)"
                           style="width:100%;height:24px;text-align:right" />
                    <span v-else style="display:block;text-align:right">{{ row.qty }}</span>
                  </td>
                  <td style="text-align:right"
                      :class="{ 'remain-warn': (remainOfApplyLine(row.applyDetailId) ?? 0) < 0 }">
                    {{ remainOfApplyLine(row.applyDetailId) ?? '-' }}
                  </td>
                  <!-- 生产日期：选择后自动生成批次号 -->
                  <td>
                    <input v-if="canEdit" type="date"
                           v-model="row.productionDate"
                           @change="onProductionDateChange(row)"
                           style="width:100%;height:24px;font-size:12px;padding:0 4px" />
                    <span v-else>{{ row.productionDate || '-' }}</span>
                  </td>
                  <!-- 批次号：自动生成（日期YYYYMMDD），可手动改 -->
                  <td>
                    <input v-if="canEdit" type="text"
                           v-model="row.batchNo"
                           @input="onBatchNoInput(row)"
                           @blur="onBatchNoBlur(row)"
                           :placeholder="canEdit ? '选生产日期自动生成' : ''"
                           style="width:100%;height:24px;font-size:12px;padding:0 6px" />
                    <span v-else>{{ row.batchNo || '-' }}</span>
                  </td>
                  <td style="text-align:right">{{ Number(row.price).toFixed(4) }}</td>
                  <td style="text-align:right;font-weight:700">
                    {{ (Number(row.qty || 0) * Number(row.price || 0)).toFixed(2) }}
                  </td>
                  <td style="text-align:right">
                    {{ Number(row.costPrice || 0) > 0 ? Number(row.costPrice).toFixed(6) : '审核后计算' }}
                  </td>
                  <td style="text-align:right;font-weight:700">
                    {{ (Number(row.qty || 0) * Number(row.costPrice || 0)).toFixed(2) }}
                  </td>
                  <td>{{ row.sourceOutboundNo || '-' }}</td>
                  <td v-if="canEdit">
                    <button class="link link-btn" title="为该申请行新增一个入库批次（支持多生产日期入库）"
                            @click="addBatchRow(index)">拆批次</button>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>

        <div class="summary">
          <span>合计入库数量：<b>{{ totalQty }}</b></span>
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

.tag {
  display: inline-block; padding: 1px 6px;
  border-radius: 3px; font-size: 11px; font-weight: 700; white-space: nowrap;
}
.tag.by-bill { background: #ecf5ff; color: #409eff; border: 1px solid #d9ecff; }
.tag.by-goods { background: #fdf6ec; color: #e6a23c; border: 1px solid #faecd8; }

.qty-error { border-color: #f56c6c !important; background: #fef0f0; }
.remain-warn { color: #f56c6c; font-weight: 700; }

/* 零入库行灰化 */
.zero-qty { opacity: 0.45; }
.zero-qty:hover { opacity: 0.7; }

@keyframes fadeIn { from { opacity: 0; } to { opacity: 1; } }
@keyframes slideIn { from { transform: translateX(60px); opacity: 0.6 } to { transform: translateX(0); opacity: 1 } }
@media (max-width: 900px) {
  .return-drawer-mask { left: 0; }
}
</style>
