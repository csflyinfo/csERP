<script setup>
/**
 * 拒收入库单抽屉 —— 只能从列表进入，不支持手工新建。
 *
 * 单据由发货单「确认签收」时登记的拒收商品自动生成（status = PENDING），本抽屉用于：
 *   1. 核对来源信息（销售订单号 / 发货单号 / 出库单号 / 司机 / 客户）
 *   2. 确认或改小本次入库数量（不可超过拒收数量，填 0 表示该行不入库）
 *   3. 必要时调整入库仓库、批次号、生产日期
 *   4. 审核入库 → 后端按【原出库成本单价】回库、写入库流水、重算移动加权平均成本
 *
 * 重要约定：
 *   · 批次号与生产日期由后端从原出库单带出；同一商品原出库拆了多个批次时取生产日期最新的那一批
 *   · 成本单价 = 原出库单该商品的成本单价，本抽屉只读，改数量不影响单价
 *     —— 成本是「商品 + 仓库」维度的移动加权平均，与批次无关：同一单据同一商品各批次成本单价都一样，
 *        所以上面「取最新生产日期那批」只决定批次号/生产日期，不影响成本
 *     —— 区别于销售退货入库/其他入库的是时点：本单用出库时点的成本快照，那两个取审核时点的当前均价
 *   · 不能新增/删除明细行 —— 明细由拒收登记决定，不入库的行数量填 0
 *   · 批次号留空时后端按生产日期 yyyyMMdd 生成；生产日期也为空则批次留空
 */
import { ref, computed, watch } from 'vue'
import { post, get } from '../api/client.js'

const props = defineProps({
  visible: { type: Boolean, default: false },
  /** 拒收入库单 ID 或单号 */
  inboundId: { type: String, default: '' },
  /** 只读模式（已审核单据查看） */
  readonly: { type: Boolean, default: false },
})
const emit = defineEmits(['close', 'save'])

const head = ref({})
const detailList = ref([])
const errors = ref({})
const loading = ref(false)
/** 仓库下拉选项 */
const warehouseOptions = ref([])

const isApproved = computed(() => head.value.status === 'APPROVED')
const canEdit = computed(() => !props.readonly && !isApproved.value)

const totalRejectQty = computed(() =>
  detailList.value.reduce((s, r) => s + Number(r.rejectQty || 0), 0)
)
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
    const data = await get(`/sales/reject-inbound/detail?id=${encodeURIComponent(id)}`)
    head.value = data
    detailList.value = (data.details || []).map(d => ({
      detailId: d.detailId,
      goodsCode: d.goodsCode,
      goodsName: d.goodsName,
      spec: d.spec || '',
      unitName: d.unitName || '',
      warehouse: d.warehouse || '',
      rejectQty: Number(d.rejectQty || 0),
      qty: Number(d.qty || 0),
      batchNo: d.batchNo || '',
      productionDate: String(d.productionDate || '').slice(0, 10),
      price: Number(d.price || 0),
      costPrice: Number(d.costPrice || 0),
      rejectReason: d.rejectReason || '',
      remark: d.remark || '',
      batchNoManuallySet: false,
    }))
  } catch (e) {
    errors.value.header = e.message || '加载拒收入库单失败'
    head.value = {}
    detailList.value = []
  } finally {
    loading.value = false
  }
}

// ============ 生产日期 → 自动生成批次号 ============
/**
 * 改生产日期后自动重算批次号 yyyyMMdd（无前缀）；手工改过批次号的行不再自动覆盖。
 * 与销售退货入库、其他入库保持同一套全局批次号生成规则。
 */
function onProductionDateChange(row) {
  if (row.batchNoManuallySet) return
  row.batchNo = row.productionDate ? row.productionDate.replace(/-/g, '') : ''
}
function onBatchNoInput(row) {
  row.batchNoManuallySet = true
}
function onBatchNoBlur(row) {
  // 用户清空批次号且生产日期有值 → 恢复自动生成
  if (!row.batchNo && row.productionDate && row.batchNoManuallySet) {
    row.batchNoManuallySet = false
    onProductionDateChange(row)
  }
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
/** 该行入库数量是否超过拒收数量 */
function isRowExceeded(row) {
  return Number(row.qty || 0) > Number(row.rejectQty || 0) + 1e-6
}

function validate() {
  errors.value = {}
  if (detailList.value.length === 0) {
    errors.value.details = '入库明细为空'
    return false
  }
  for (const row of detailList.value) {
    const label = row.goodsName || row.goodsCode
    const qty = Number(row.qty || 0)
    if (qty < 0) {
      errors.value.details = `商品 ${label} 的入库数量不能为负数`
      return false
    }
    if (isRowExceeded(row)) {
      errors.value.details = `商品 ${label} 的入库数量 ${qty} 超过拒收数量 ${row.rejectQty}`
      return false
    }
    if (qty > 0 && !row.warehouse) {
      errors.value.details = `商品 ${label} 未选择入库仓库`
      return false
    }
  }
  if (totalQty.value <= 0) {
    errors.value.details = '所有明细的入库数量都是 0，无法审核'
    return false
  }
  return true
}

/** 明细提交载荷（成本单价不提交 —— 由后端从原出库快照保护） */
function detailPayload() {
  return detailList.value.map(r => ({
    detailId: r.detailId,
    qty: Number(r.qty),
    warehouse: r.warehouse || '',
    batchNo: r.batchNo || '',
    productionDate: r.productionDate || null,
    remark: r.remark || '',
  }))
}

/** 保存修改（不审核） */
async function saveInbound() {
  if (!validate()) return
  try {
    const result = await post('/sales/reject-inbound/update', {
      inboundId: head.value.inboundId,
      warehouse: head.value.warehouse || '',
      remark: head.value.remark || '',
      details: detailPayload(),
    })
    emit('save', result)
    await loadInbound(head.value.inboundId)
  } catch (e) {
    errors.value.header = '保存失败：' + (e.message || '未知错误')
  }
}

/** 审核入库 → 按原出库成本单价回库 + 写入库流水 + 重算成本 */
async function auditInbound() {
  if (!validate()) return
  if (!confirm(`确认审核拒收入库单【${head.value.inboundNo}】？\n\n审核后将：\n· 按【原出库成本单价】计价（不是当前库存成本均价）\n· 拒收商品回库并写入库存流水\n· 重算移动加权平均成本\n\n如需撤销请走反审核。`)) return
  try {
    if (canEdit.value) {
      await post('/sales/reject-inbound/update', {
        inboundId: head.value.inboundId,
        warehouse: head.value.warehouse || '',
        remark: head.value.remark || '',
        details: detailPayload(),
      })
    }
    const result = await post('/sales/reject-inbound/audit', { bizId: head.value.inboundId })
    emit('save', result)
    emit('close')
  } catch (e) {
    errors.value.header = '审核失败：' + (e.message || '未知错误')
  }
}

/** 反审核 → 扣回库存 */
async function reverseAudit() {
  if (!confirm(`确认反审核拒收入库单【${head.value.inboundNo}】？\n\n将扣回本单已入库的库存。若相关批次已被后续单据出库导致库存不足，反审核会被拒绝。`)) return
  try {
    const result = await post('/sales/reject-inbound/reverse-audit', { bizId: head.value.inboundId })
    emit('save', result)
    await loadInbound(head.value.inboundId)
  } catch (e) {
    errors.value.header = '反审核失败：' + (e.message || '未知错误')
  }
}

function closeDrawer() { emit('close') }
</script>

<template>
  <div v-show="visible" class="reject-drawer-mask">
    <div class="reject-drawer-box">
      <div class="reject-drawer-head">
        <b>拒收入库单 {{ head.inboundNo || '' }}</b>
        <span v-if="isApproved" class="badge ok">已审核</span>
        <span v-else class="badge wait">待审核</span>
        <div style="flex:1"></div>
        <div class="actions">
          <button class="btn" @click="closeDrawer">关闭</button>
          <button v-if="canEdit" class="btn" @click="saveInbound">保存</button>
          <button v-if="canEdit" class="btn primary" @click="auditInbound">审核入库</button>
          <button v-if="isApproved && !readonly" class="btn" @click="reverseAudit">反审核</button>
        </div>
      </div>

      <div class="reject-drawer-body">
        <div v-if="errors.header" class="err-banner">{{ errors.header }}</div>

        <!-- 来源信息（全部只读快照） -->
        <div class="card" style="padding:12px">
          <div style="font-weight:900;margin-bottom:10px;color:var(--primary)">来源信息</div>
          <div class="grid4">
            <div class="field">
              <label>入库单号</label>
              <input readonly :value="head.inboundNo || ''" />
            </div>
            <div class="field">
              <label>销售订单号</label>
              <input readonly :value="head.sourceOrderNo || ''" />
            </div>
            <div class="field">
              <label>发货单号</label>
              <input readonly :value="head.sourceReceiptNo || ''" />
            </div>
            <div class="field">
              <label>出库单号</label>
              <input readonly :value="head.sourceOutboundNo || ''" />
            </div>
            <div class="field">
              <label>客户</label>
              <input readonly :value="head.customerName || ''" />
            </div>
            <div class="field">
              <label>司机</label>
              <input readonly :value="head.driver || ''" />
            </div>
            <div class="field">
              <label>线路</label>
              <input readonly :value="head.routeLine || ''" />
            </div>
            <div class="field">
              <label>业务员</label>
              <input readonly :value="head.salesman || ''" />
            </div>
            <div class="field">
              <label>入库日期</label>
              <input readonly :value="String(head.billDate || '').slice(0, 10)" />
            </div>
            <div class="field">
              <label>是否已更新库存</label>
              <input readonly :value="head.stockUpdated ? '是' : '否'" />
            </div>
            <div class="field" style="grid-column:span 2">
              <label>备注</label>
              <input v-if="canEdit" v-model="head.remark" placeholder="可填写拒收处理说明" />
              <input v-else readonly :value="head.remark || ''" />
            </div>
          </div>
        </div>

        <!-- 明细 -->
        <div class="card detail-card">
          <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:8px">
            <div style="font-weight:900;color:var(--primary)">拒收入库明细</div>
            <div style="font-size:12px;color:#5d7896">
              {{ canEdit
                 ? '批次号与生产日期已从原出库单带出（同商品多批次时取生产日期最新的一批）；成本单价取原出库单该商品的成本，与批次无关（系统按商品+仓库加权平均计价），不可修改；不入库的行数量填 0'
                 : '已审核单据，明细只读' }}
            </div>
          </div>
          <div v-if="errors.details" style="color:var(--danger);font-size:12px;margin-bottom:6px">{{ errors.details }}</div>
          <div v-if="loading" class="empty-detail">加载中...</div>
          <div v-else-if="detailList.length === 0" class="empty-detail">暂无拒收入库明细</div>
          <div v-else class="detail-scroll">
            <table>
              <thead>
                <tr>
                  <th style="width:40px">#</th>
                  <th style="min-width:110px">商品编号</th>
                  <th style="min-width:140px">商品名称</th>
                  <th style="min-width:80px">规格</th>
                  <th style="width:56px">单位</th>
                  <th style="width:100px">仓库 <span v-if="canEdit" class="req">*</span></th>
                  <th style="width:80px">拒收数量</th>
                  <th style="width:100px">本次入库数量</th>
                  <th style="width:105px">生产日期</th>
                  <th style="min-width:130px">批次号</th>
                  <th style="width:105px">单价</th>
                  <th style="width:100px">金额</th>
                  <th style="width:98px">成本单价</th>
                  <th style="width:88px">成本金额</th>
                  <th style="min-width:140px">拒收原因</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="(row, index) in detailList" :key="row.detailId || index"
                    :class="{ 'zero-qty': Number(row.qty || 0) === 0 }">
                  <td>{{ index + 1 }}</td>
                  <td>{{ row.goodsCode }}</td>
                  <td>{{ row.goodsName }}</td>
                  <td>{{ row.spec || '-' }}</td>
                  <td>{{ row.unitName || '-' }}</td>
                  <td>
                    <select v-if="canEdit" v-model="row.warehouse"
                            style="width:100%;height:24px;font-size:12px;padding:0 4px">
                      <option value="">--请选择--</option>
                      <option v-for="w in warehouseOptions" :key="w" :value="w">{{ w }}</option>
                    </select>
                    <span v-else>{{ row.warehouse || '-' }}</span>
                  </td>
                  <td style="text-align:right;color:var(--primary);font-weight:700">{{ row.rejectQty }}</td>
                  <td>
                    <input v-if="canEdit" type="text" inputmode="decimal"
                           :value="qtyDisplay(row)"
                           :class="{ 'qty-error': isRowExceeded(row) }"
                           :title="isRowExceeded(row) ? `入库数量不可超过拒收数量 ${row.rejectQty}` : ''"
                           @input="onQtyInput(row, $event.target.value)"
                           @blur="onQtyBlur(row)"
                           style="width:100%;height:24px;text-align:right" />
                    <span v-else style="display:block;text-align:right">{{ row.qty }}</span>
                  </td>
                  <td>
                    <input v-if="canEdit" type="date"
                           v-model="row.productionDate"
                           @change="onProductionDateChange(row)"
                           style="width:100%;height:24px;font-size:12px;padding:0 4px" />
                    <span v-else>{{ row.productionDate || '-' }}</span>
                  </td>
                  <td>
                    <input v-if="canEdit" type="text"
                           v-model="row.batchNo"
                           @input="onBatchNoInput(row)"
                           @blur="onBatchNoBlur(row)"
                           placeholder="原出库批次"
                           style="width:100%;height:24px;font-size:12px;padding:0 6px" />
                    <span v-else>{{ row.batchNo || '-' }}</span>
                  </td>
                  <td style="text-align:right">{{ Number(row.price).toFixed(4) }}</td>
                  <td style="text-align:right;font-weight:700">
                    {{ (Number(row.qty || 0) * Number(row.price || 0)).toFixed(2) }}
                  </td>
                  <!-- 成本单价：原出库成本，只读 -->
                  <td style="text-align:right" title="取原出库单该商品的成本单价（与批次无关，系统按商品+仓库移动加权平均计价），不可修改">
                    {{ Number(row.costPrice || 0) > 0 ? Number(row.costPrice).toFixed(6) : '-' }}
                  </td>
                  <td style="text-align:right;font-weight:700">
                    {{ (Number(row.qty || 0) * Number(row.costPrice || 0)).toFixed(2) }}
                  </td>
                  <td :title="row.rejectReason">{{ row.rejectReason || '-' }}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>

        <div class="summary">
          <span>合计拒收数量：<b>{{ totalRejectQty }}</b></span>
          <span>合计入库数量：<b>{{ totalQty }}</b></span>
          <span>合计拒收金额：<b>¥ {{ totalAmount }}</b></span>
          <span>合计成本金额：<b>¥ {{ totalCostAmount }}</b></span>
          <span>行数：<b>{{ detailList.length }}</b></span>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.reject-drawer-mask {
  position: fixed;
  top: 48px; right: 0; bottom: 0; left: 299px;
  z-index: 900;
  display: flex;
  pointer-events: none;
  animation: fadeIn 0.2s ease;
}
.reject-drawer-box {
  flex: 1;
  background: #fff;
  display: flex; flex-direction: column;
  min-width: 0;
  border-left: 1px solid var(--line);
  box-shadow: -6px 0 24px rgba(15, 46, 88, 0.12);
  pointer-events: auto;
  animation: slideIn 0.25s ease;
}
.reject-drawer-head {
  display: flex; align-items: center; gap: 10px;
  height: 46px; padding: 0 16px;
  border-bottom: 1px solid var(--line-soft);
}
.reject-drawer-head b { font-size: 15px; }
.reject-drawer-body {
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

.qty-error { border-color: #f56c6c !important; background: #fef0f0; }

/* 零入库行灰化 */
.zero-qty { opacity: 0.45; }
.zero-qty:hover { opacity: 0.7; }

@keyframes fadeIn { from { opacity: 0; } to { opacity: 1; } }
@keyframes slideIn { from { transform: translateX(60px); opacity: 0.6 } to { transform: translateX(0); opacity: 1 } }
@media (max-width: 900px) {
  .reject-drawer-mask { left: 0; }
}
</style>
