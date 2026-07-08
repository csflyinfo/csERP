<script setup>
/**
 * 销售出库单抽屉 —— 对称于 PurchaseInboundDrawer.vue。
 *
 * 两种入口：
 *   A. 出库列表「新建销售出库」：抽屉内下拉选择已审核未完成出库的销售订单
 *   B. 订单列表「生成出库单」：外部传入 sourceOrder = {orderId, orderNo}，自动预填
 *
 * 明细拆行 UI：
 *   - 默认每商品一行（qty = 订单剩余数量）
 *   - 单价预填订单单价、只读
 *   - 「拆分」按钮：把一行按 2 条 / 3 条 拆开，qty 平均分配
 *   - 每行支持从可选批次下拉选择（inv_batch_stock 中 qty>0 的批次）
 *   - 校验：Σ本次出库 ≤ 商品订单剩余数量
 */
import { ref, computed, watch } from 'vue'
import { post, get } from '../api/client.js'

const props = defineProps({
  visible: { type: Boolean, default: false },
  sourceOrder: { type: Object, default: null },
  // 编辑模式：{ outboundId, outboundNo } 或完整明细数据
  editData: { type: Object, default: null },
})
const emit = defineEmits(['close', 'save'])

const isEditMode = computed(() => !!(props.editData && (props.editData.outboundId || props.editData.outboundNo)))

const headerForm = ref({
  sourceOrder: '',
  customer: '',
  warehouse: '',
  billDate: new Date().toISOString().slice(0, 10),
  orderAmount: 0,
  outboundedAmount: 0,
  remark: '',
  salesman: '',
  territory: '',
  routeLine: '',
})

/** 明细行结构：
 *  { goodsCode, goodsName, unitName, orderQty, outboundedQty, remainQty,
 *    qty, price, taxRate, batchNo, productionDate, availableBatches, spec, barcode, smallUnitName, smallUnitQty, remark }
 */
const detailList = ref([])
const errors = ref({})
const availableOrders = ref([])
const loading = ref(false)

const totalQty = computed(() =>
  detailList.value.reduce((s, r) => s + Number(r.qty || 0), 0)
)
const totalAmount = computed(() =>
  detailList.value.reduce((s, r) => s + Number(r.qty || 0) * Number(r.price || 0), 0).toFixed(2)
)

watch(() => props.visible, async (val) => {
  if (!val) return
  errors.value = {}
  detailList.value = []
  headerForm.value = {
    sourceOrder: '', customer: '', warehouse: '',
    billDate: new Date().toISOString().slice(0, 10),
    orderAmount: 0, outboundedAmount: 0,
    remark: '', salesman: '', territory: '', routeLine: '',
  }
  if (props.editData?.outboundNo) {
    // 编辑模式：从后端加载已有出库单
    await loadFromExisting(props.editData)
  } else if (props.sourceOrder?.orderNo) {
    await loadFromOrder(props.sourceOrder.orderNo)
  } else {
    await loadAvailableOrders()
  }
})

async function loadAvailableOrders() {
  try {
    const data = await post('/sales/order/page', { pageNo: 1, pageSize: 500, filters: {} })
    availableOrders.value = (data.records || []).filter(r => {
      const st = r.status || r.STATUS
      const os = r.outboundStatus || r.outboundstatus || r.OUTBOUNDSTATUS
      return st === 'APPROVED' && os !== '已出库'
    })
  } catch (e) {
    availableOrders.value = []
  }
}

/** 编辑模式：从后端加载已有出库单 */
async function loadFromExisting(editData) {
  loading.value = true
  try {
    const key = editData.outboundNo || editData.outboundId
    const data = await get(`/sales/outbound/detail?outboundId=${encodeURIComponent(key)}`)
    headerForm.value.sourceOrder = data.sourceOrder || ''
    headerForm.value.customer = data.customer || ''
    headerForm.value.warehouse = data.warehouse || ''
    headerForm.value.billDate = data.billDate || new Date().toISOString().slice(0, 10)
    headerForm.value.orderAmount = Number(data.amount || 0)
    headerForm.value.remark = data.remark || ''
    headerForm.value.salesman = data.salesman || ''
    headerForm.value.territory = data.territory || ''
    headerForm.value.routeLine = data.routeLine || ''
    const lines = (data.details || []).map(d => ({
      goodsCode: d.goodsCode,
      goodsName: d.goodsName,
      unitName: d.unitName,
      orderQty: Number(d.qty || 0),
      outboundedQty: 0,
      remainQty: Number(d.qty || 0),
      qty: Number(d.qty || 0),
      price: Number(d.price || 0),
      taxRate: d.taxRate || '13%',
      batchNo: d.batchNo || '',
      productionDate: d.productionDate || '',
      spec: d.spec || '',
      barcode: d.barcode || '',
      smallUnitName: d.smallUnitName || '',
      smallUnitQty: Number(d.smallUnitQty || 0),
      remark: d.remark || '',
      availableBatches: [],
    }))
    detailList.value = lines
    await Promise.all(lines.map(l => loadBatches(l)))
  } catch (e) {
    errors.value = { header: e.message || '加载出库单失败' }
    detailList.value = []
  } finally {
    loading.value = false
  }
}

async function loadFromOrder(orderNo) {
  if (!orderNo) return
  loading.value = true
  try {
    const data = await get(`/sales/outbound/from-order?orderNo=${encodeURIComponent(orderNo)}`)
    headerForm.value.sourceOrder = data.orderNo
    headerForm.value.customer = data.customer
    headerForm.value.warehouse = data.warehouse
    headerForm.value.orderAmount = Number(data.orderAmount || 0)
    headerForm.value.outboundedAmount = Number(data.outboundedAmount || 0)
    const lines = (data.details || []).map(d => ({
      goodsCode: d.goodsCode,
      goodsName: d.goodsName,
      unitName: d.unitName,
      orderQty: Number(d.orderQty || 0),
      outboundedQty: Number(d.outboundedQty || 0),
      remainQty: Number(d.remainQty || 0),
      qty: Number(d.remainQty || 0),
      price: Number(d.price || 0),
      taxRate: d.taxRate || '13%',
      batchNo: '',
      productionDate: '',
      spec: d.spec || '',
      barcode: d.barcode || '',
      smallUnitName: d.smallUnitName || '',
      smallUnitQty: 0,
      remark: '',
      availableBatches: [],
    }))
    detailList.value = lines
    // 并行加载每行的可选批次
    await Promise.all(lines.map(l => loadBatches(l)))
  } catch (e) {
    errors.value = { header: e.message || '加载订单明细失败' }
    detailList.value = []
  } finally {
    loading.value = false
  }
}

async function loadBatches(row) {
  if (!row.goodsCode || !headerForm.value.warehouse) return
  try {
    const list = await get(
        `/sales/outbound/available-batches?goodsCode=${encodeURIComponent(row.goodsCode)}&warehouse=${encodeURIComponent(headerForm.value.warehouse)}`)
    row.availableBatches = list || []
    // 默认选第一批（最早生产日期），若未指定；同批次号自动带出生产日期
    if (!row.batchNo && row.availableBatches.length > 0) {
      row.batchNo = row.availableBatches[0].batchNo
      row.productionDate = row.availableBatches[0].productionDate || ''
    } else if (row.batchNo) {
      // 编辑模式已有 batchNo：从批次列表找对应 productionDate 补齐
      const hit = row.availableBatches.find(b => b.batchNo === row.batchNo)
      if (hit && !row.productionDate) row.productionDate = hit.productionDate || ''
    }
  } catch (e) {
    row.availableBatches = []
  }
}

/** 用户切换批次号时，自动带出对应的生产日期 */
function onBatchChange(row) {
  const hits = (row.availableBatches || []).filter(b => b.batchNo === row.batchNo)
  if (hits.length === 0) {
    row.productionDate = ''
    return
  }
  // 如果同批次号只有一条 production_date → 直接带出；多条 → 默认最老那个（列表已按 production_date ASC 排序，取第一条）
  const sorted = [...hits].sort((a, b) => String(a.productionDate || '').localeCompare(String(b.productionDate || '')))
  row.productionDate = sorted[0].productionDate || ''
}

/** 当前批次号对应的所有生产日期候选（同 batch_no 有多条时展示下拉） */
function productionDateOptions(row) {
  return (row.availableBatches || []).filter(b => b.batchNo === row.batchNo)
      .map(b => b.productionDate).filter(Boolean)
}

function onOrderChange(orderNo) {
  if (!orderNo) return
  loadFromOrder(orderNo)
}

function splitRow(index, count) {
  const row = detailList.value[index]
  if (!row || count < 2) return
  const totalRowQty = Number(row.qty || 0)
  if (totalRowQty <= 0) return alert('数量为 0，无法拆分')
  const per = Math.floor((totalRowQty / count) * 10000) / 10000
  const rows = []
  for (let i = 0; i < count; i++) {
    const q = i === count - 1 ? Number((totalRowQty - per * (count - 1)).toFixed(4)) : per
    rows.push({
      ...row,
      qty: q,
      batchNo: row.batchNo,   // 每行独立选批次，默认继承
    })
  }
  detailList.value.splice(index, 1, ...rows)
}

function removeRow(index) {
  const row = detailList.value[index]
  if (!row) return
  const sameGoodsRows = detailList.value.filter(r => r.goodsCode === row.goodsCode)
  if (sameGoodsRows.length <= 1) {
    alert('该商品仅剩一行，不能删除')
    return
  }
  detailList.value.splice(index, 1)
}

async function saveOutbound() {
  errors.value = {}
  if (!headerForm.value.sourceOrder) {
    errors.value.header = '请选择来源销售订单'
    return
  }
  if (detailList.value.length === 0) {
    errors.value.details = '请至少一条出库明细'
    return
  }
  // 预校验：Σ本次出库 ≤ Σ订单剩余（按 goods_code 聚合）
  // 同商品可能在订单里有多条明细（如正常品 + 赠品），前端拆行也会出现多行同 goodsCode。
  // 因此：每个 goodsCode 的 remain 只从"原始订单剩余"取一次总和（避免拆行时重复累加）。
  const perGoods = {}
  const orderRemainByGoods = {}
  // 从原始订单行推 remain：同 goodsCode 只累加一次（去重 detail line）
  const seenRemainKeys = new Set()
  for (const row of detailList.value) {
    if (Number(row.qty || 0) <= 0) {
      errors.value.details = `商品 ${row.goodsName || row.goodsCode} 的出库数量必须大于 0`
      return
    }
    if (!row.batchNo) {
      errors.value.details = `商品 ${row.goodsName || row.goodsCode} 请选择批次`
      return
    }
    // 用「goodsCode + orderQty + remainQty」作为原始订单行指纹，去重
    const rowKey = `${row.goodsCode}|${row.orderQty}|${row.remainQty}`
    if (!seenRemainKeys.has(rowKey)) {
      seenRemainKeys.add(rowKey)
      orderRemainByGoods[row.goodsCode] = (orderRemainByGoods[row.goodsCode] || 0) + Number(row.remainQty || 0)
    }
    perGoods[row.goodsCode] = (perGoods[row.goodsCode] || 0) + Number(row.qty)
  }
  for (const [code, sum] of Object.entries(perGoods)) {
    const remain = orderRemainByGoods[code] || 0
    if (sum > remain + 1e-4) {
      errors.value.details = `商品 ${code} 本次出库 ${sum} 超过订单剩余 ${remain}`
      return
    }
  }

  const payload = {
    sourceOrder: headerForm.value.sourceOrder,
    customer: headerForm.value.customer,
    warehouse: headerForm.value.warehouse,
    billDate: headerForm.value.billDate,
    remark: headerForm.value.remark,
    salesman: headerForm.value.salesman,
    territory: headerForm.value.territory,
    routeLine: headerForm.value.routeLine,
    details: detailList.value.map(r => ({
      goodsCode: r.goodsCode,
      goodsName: r.goodsName,
      unitName: r.unitName,
      qty: Number(r.qty),
      price: Number(r.price),
      batchNo: r.batchNo,
      productionDate: r.productionDate || null,
      remark: r.remark || '',
    })),
  }

  try {
    const url = isEditMode.value ? '/sales/outbound/update' : '/sales/outbound/create'
    if (isEditMode.value) {
      payload.outboundId = props.editData.outboundId || props.editData.outboundNo
    }
    const result = await post(url, payload)
    emit('save', result)
    emit('close')
  } catch (e) {
    alert('保存失败：' + (e.message || '未知错误'))
  }
}

function closeDrawer() { emit('close') }
</script>

<template>
  <div v-show="visible" class="outbound-drawer-mask">
    <div class="outbound-drawer-box">
      <div class="outbound-drawer-head">
        <b>{{ isEditMode ? '编辑销售出库单' : (props.sourceOrder ? '生成销售出库单' : '新建销售出库单') }}</b>
        <div style="flex:1"></div>
        <div class="actions">
          <button class="btn" @click="closeDrawer">取消</button>
          <button class="btn primary" @click="saveOutbound">保存</button>
        </div>
      </div>

      <div class="outbound-drawer-body">
        <div class="card" style="padding:12px">
          <div style="font-weight:900;margin-bottom:10px;color:var(--primary)">单据信息</div>
          <div v-if="errors.header" style="color:var(--danger);font-size:12px;margin-bottom:6px">{{ errors.header }}</div>
          <div class="grid4">
            <div class="field">
              <label>来源销售订单 <span class="req">*</span></label>
              <select v-if="!props.sourceOrder && !isEditMode" v-model="headerForm.sourceOrder" @change="onOrderChange($event.target.value)">
                <option value="">请选择</option>
                <option v-for="o in availableOrders" :key="o.orderNo || o.orderno" :value="o.orderNo || o.orderno">
                  {{ o.orderNo || o.orderno }} - {{ o.customerName || o.customername }}（订单额 ¥{{ o.amount }}）
                </option>
              </select>
              <input v-else readonly :value="headerForm.sourceOrder" />
            </div>
            <div class="field">
              <label>客户</label>
              <input readonly :value="headerForm.customer" />
            </div>
            <div class="field">
              <label>业务员</label>
              <input readonly :value="headerForm.salesman" />
            </div>
            <div class="field">
              <label>线路</label>
              <input readonly :value="headerForm.routeLine" />
            </div>
            <div class="field">
              <label>片区</label>
              <input readonly :value="headerForm.territory" />
            </div>
            <div class="field">
              <label>仓库</label>
              <input readonly :value="headerForm.warehouse" />
            </div>
            <div class="field">
              <label>单据日期</label>
              <input type="date" v-model="headerForm.billDate" />
            </div>
            <div class="field">
              <label>订单金额</label>
              <input readonly :value="'¥ ' + Number(headerForm.orderAmount).toFixed(2)" />
            </div>
            <div class="field field-full">
              <label>备注</label>
              <input v-model="headerForm.remark" placeholder="选填" style="width:100%" />
            </div>
          </div>
        </div>

        <div class="card detail-card">
          <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:8px">
            <div style="font-weight:900;color:var(--primary)">出库明细</div>
            <div style="font-size:12px;color:#5d7896">提示：单价随订单单价只读；批次下拉展示 qty>0 的可用批次；选批次自动带出生产日期</div>
          </div>
          <div v-if="errors.details" style="color:var(--danger);font-size:12px;margin-bottom:6px">{{ errors.details }}</div>
          <div v-if="loading" class="empty-detail">加载中...</div>
          <div v-else-if="detailList.length === 0" class="empty-detail">请先选择来源销售订单</div>
          <div v-else class="detail-scroll">
            <table>
              <thead>
                <tr>
                  <th style="width:40px">#</th>
                  <th style="min-width:110px">商品编号</th>
                  <th style="min-width:150px">商品名称</th>
                  <th style="min-width:100px">规格</th>
                  <th style="min-width:110px">条码</th>
                  <th style="width:70px">单据单位</th>
                  <th style="width:80px">订单数量</th>
                  <th style="width:80px">已出库</th>
                  <th style="width:80px">剩余</th>
                  <th style="width:100px">单据数量 <span class="req">*</span></th>
                  <th style="width:70px">小单位</th>
                  <th style="width:90px">小单位数</th>
                  <th style="width:80px">单价</th>
                  <th style="width:90px">金额</th>
                  <th style="min-width:180px">批次 <span class="req">*</span></th>
                  <th style="min-width:130px">生产日期</th>
                  <th style="min-width:120px">备注</th>
                  <th style="width:160px">操作</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="(row, index) in detailList" :key="index">
                  <td>{{ index + 1 }}</td>
                  <td>{{ row.goodsCode }}</td>
                  <td>{{ row.goodsName }}</td>
                  <td>{{ row.spec }}</td>
                  <td>{{ row.barcode }}</td>
                  <td>{{ row.unitName }}</td>
                  <td style="text-align:right">{{ row.orderQty }}</td>
                  <td style="text-align:right">{{ row.outboundedQty }}</td>
                  <td style="text-align:right;font-weight:700;color:var(--primary)">{{ row.remainQty }}</td>
                  <td>
                    <input type="number" step="0.0001" min="0" v-model.number="row.qty"
                      style="width:100%;height:24px;text-align:right" />
                  </td>
                  <td>{{ row.smallUnitName }}</td>
                  <td style="text-align:right">{{ row.smallUnitQty }}</td>
                  <td style="text-align:right">{{ Number(row.price).toFixed(4) }}</td>
                  <td style="text-align:right;font-weight:700">
                    {{ (Number(row.qty || 0) * Number(row.price || 0)).toFixed(2) }}
                  </td>
                  <td>
                    <select v-model="row.batchNo" @change="onBatchChange(row)" style="width:100%;height:24px">
                      <option value="">请选择批次</option>
                      <option v-for="b in row.availableBatches" :key="b.batchNo + '_' + b.productionDate" :value="b.batchNo">
                        {{ b.batchNo }}（可用 {{ b.qty }}）
                      </option>
                    </select>
                  </td>
                  <td>
                    <!-- 同批次号有多条生产日期时展示下拉，否则只读展示 -->
                    <select v-if="productionDateOptions(row).length > 1" v-model="row.productionDate" style="width:100%;height:24px">
                      <option v-for="d in productionDateOptions(row)" :key="d" :value="d">{{ d }}</option>
                    </select>
                    <input v-else readonly :value="row.productionDate" style="width:100%;height:24px" />
                  </td>
                  <td>
                    <input v-model="row.remark" placeholder="选填" style="width:100%;height:24px" />
                  </td>
                  <td>
                    <button class="link link-btn" @click="splitRow(index, 2)">拆 2</button>
                    <button class="link link-btn" @click="splitRow(index, 3)">拆 3</button>
                    <button class="link link-btn danger-link" @click="removeRow(index)">删除</button>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>

        <div class="summary">
          <span>合计数量：<b>{{ totalQty }}</b></span>
          <span>合计金额：<b>¥ {{ totalAmount }}</b></span>
          <span>行数：<b>{{ detailList.length }}</b></span>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.outbound-drawer-mask {
  position: fixed;
  top: 48px; right: 0; bottom: 0; left: 299px;
  z-index: 900;
  display: flex;
  pointer-events: none;
  animation: fadeIn 0.2s ease;
}
.outbound-drawer-box {
  flex: 1;
  background: #fff;
  display: flex; flex-direction: column;
  min-width: 0;
  border-left: 1px solid var(--line);
  box-shadow: -6px 0 24px rgba(15, 46, 88, 0.12);
  pointer-events: auto;
  animation: slideIn 0.25s ease;
}
.outbound-drawer-head {
  display: flex; align-items: center; gap: 10px;
  height: 46px; padding: 0 16px;
  border-bottom: 1px solid var(--line-soft);
}
.outbound-drawer-head b { font-size: 15px; }
.outbound-drawer-body {
  flex: 1; overflow: auto;
  padding: 12px 16px;
  display: flex; flex-direction: column; gap: 12px;
  background: #f5f7fa;
}
.detail-card { flex: 1; display: flex; flex-direction: column; min-height: 260px; padding: 12px; }
.detail-scroll { flex: 1; overflow: auto; min-height: 0; }
.empty-detail { padding: 40px; text-align: center; color: #909399; font-size: 13px; background: #fafbfc; border: 1px dashed #e5e7eb; border-radius: 8px; }
.field .req { color: #f56c6c; }
@keyframes fadeIn { from { opacity: 0; } to { opacity: 1; } }
@keyframes slideIn { from { transform: translateX(60px); opacity: 0.6 } to { transform: translateX(0); opacity: 1 } }
@media (max-width: 900px) {
  .outbound-drawer-mask { left: 0; }
}
</style>
