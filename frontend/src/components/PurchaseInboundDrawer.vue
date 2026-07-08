<script setup>
/**
 * 采购入库单抽屉 —— 支持两种入口：
 *   A. 入库列表「新建采购入库」：需要用户在头部先选择「引入采购订单」（下拉搜索订单号）
 *   B. 订单列表「生成入库单」：外部传入 sourceOrder = {orderId, orderNo}，自动预填
 *
 * 明细拆行 UI：
 *   - 默认每商品一行（received_qty = 订单剩余数量）
 *   - 单价预填订单单价、只读（决策 A：改价必须回订单改）
 *   - 「拆分」按钮：把一行按 2 条 / 3 条 / 手动 输入拆开，qty 手动分配
 *   - 生产日期变更 → 批次号自动同步为 yyyyMMdd（除非用户手动改过）
 *   - 校验：Σ实收 ≤ 商品订单剩余数量（前端预校验，后端二次校验）
 */
import { ref, computed, watch } from 'vue'
import { post, get } from '../api/client.js'

const props = defineProps({
  visible: { type: Boolean, default: false },
  /** 外部传入 { orderId, orderNo }；null 表示走「入库列表新建」入口 */
  sourceOrder: { type: Object, default: null },
})
const emit = defineEmits(['close', 'save'])

const headerForm = ref({
  sourceOrder: '',         // 订单号
  supplier: '',
  warehouse: '',
  billDate: new Date().toISOString().slice(0, 10),
  orderAmount: 0,          // 订单金额（只读，展示）
  inboundedAmount: 0,      // 已入库金额（只读，展示）
})

/** 明细行结构：
 *  { goodsCode, goodsName, spec, unitName, orderQty, inboundedQty, remainQty,
 *    receivedQty, price, batchNo, batchNoTouched, productionDate, expiryDate }
 */
const detailList = ref([])
const errors = ref({})   // { header: '...', details: '...' }
const availableOrders = ref([])   // 已审核未完成入库的采购订单列表（入口 A 下拉）
const loading = ref(false)

const totalQty = computed(() =>
  detailList.value.reduce((s, r) => s + Number(r.receivedQty || 0), 0)
)
const totalAmount = computed(() =>
  detailList.value.reduce((s, r) => s + Number(r.receivedQty || 0) * Number(r.price || 0), 0).toFixed(2)
)

function todayCompact() {
  return new Date().toISOString().slice(0, 10).replace(/-/g, '')
}

/** 抽屉打开：按 sourceOrder 情况初始化 */
watch(() => props.visible, async (val) => {
  if (!val) return
  errors.value = {}
  detailList.value = []
  headerForm.value = {
    sourceOrder: '',
    supplier: '',
    warehouse: '',
    billDate: new Date().toISOString().slice(0, 10),
    orderAmount: 0,
    inboundedAmount: 0,
  }
  if (props.sourceOrder?.orderNo) {
    // 入口 B：直接按订单号预填
    await loadFromOrder(props.sourceOrder.orderNo)
  } else {
    // 入口 A：拉取可选订单
    await loadAvailableOrders()
  }
})

/** 拉取已审核 + 未完全入库的采购订单，供入口 A 下拉选 */
async function loadAvailableOrders() {
  try {
    const data = await post('/purchase/order/page', { pageNo: 1, pageSize: 500, filters: {} })
    availableOrders.value = (data.records || []).filter(r => r.status === 'APPROVED' && r.inboundStatus !== '已入库')
  } catch (e) {
    availableOrders.value = []
  }
}

/** 按订单号加载头部 + 拆行明细（后端 /purchase/inbound/from-order） */
async function loadFromOrder(orderNo) {
  if (!orderNo) return
  loading.value = true
  try {
    const data = await get(`/purchase/inbound/from-order?orderNo=${encodeURIComponent(orderNo)}`)
    headerForm.value.sourceOrder = data.orderNo
    headerForm.value.supplier = data.supplier
    headerForm.value.warehouse = data.warehouse
    headerForm.value.orderAmount = Number(data.orderAmount || 0)
    headerForm.value.inboundedAmount = Number(data.inboundedAmount || 0)
    const defaultBatch = 'B' + todayCompact()
    detailList.value = (data.details || []).map(d => ({
      goodsCode: d.goodsCode,
      goodsName: d.goodsName,
      spec: d.spec,
      unitName: d.unitName,
      orderQty: Number(d.orderQty || 0),
      inboundedQty: Number(d.inboundedQty || 0),
      remainQty: Number(d.remainQty || 0),
      receivedQty: Number(d.remainQty || 0),   // 默认全部收
      price: Number(d.price || 0),             // 只读
      batchNo: defaultBatch,
      batchNoTouched: false,
      productionDate: '',
      expiryDate: '',
    }))
  } catch (e) {
    errors.value = { header: e.message || '加载订单明细失败' }
    detailList.value = []
  } finally {
    loading.value = false
  }
}

/** 入口 A：用户在下拉里换订单 */
function onOrderChange(orderNo) {
  if (!orderNo) return
  loadFromOrder(orderNo)
}

/** 拆分某行：把 index 处的一行拆成 count 行，qty 平均分配（尾数留在最后一行） */
function splitRow(index, count) {
  const row = detailList.value[index]
  if (!row || count < 2) return
  const totalQty = Number(row.receivedQty || 0)
  if (totalQty <= 0) return alert('数量为 0，无法拆分')
  const per = Math.floor((totalQty / count) * 10000) / 10000
  const rows = []
  for (let i = 0; i < count; i++) {
    const q = i === count - 1 ? Number((totalQty - per * (count - 1)).toFixed(4)) : per
    rows.push({
      ...row,
      receivedQty: q,
      // 每行独立批次号，尾号 -N
      batchNo: `${row.batchNo || 'B' + todayCompact()}-${i + 1}`,
      batchNoTouched: false,
      productionDate: '',
      expiryDate: '',
    })
  }
  detailList.value.splice(index, 1, ...rows)
}

/** 手动删除某行（只允许拆分后删除；单商品必须留一行） */
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

/** 生产日期变更 → 若用户没手动改过批次号，同步刷成 yyyyMMdd */
function onProductionDateChange(row) {
  if (row.batchNoTouched) return
  const pd = String(row.productionDate || '').replace(/-/g, '')
  if (pd) row.batchNo = 'B' + pd
}

function onBatchNoInput(row) {
  row.batchNoTouched = true
}

/** 校验并提交 */
async function saveInbound() {
  errors.value = {}
  if (!headerForm.value.sourceOrder) {
    errors.value.header = '请选择来源采购订单'
    return
  }
  if (detailList.value.length === 0) {
    errors.value.details = '请至少一条入库明细'
    return
  }
  // 前端预校验：Σ本次实收 by 商品 ≤ 该商品订单剩余
  const perGoods = {}
  for (const row of detailList.value) {
    if (Number(row.receivedQty || 0) <= 0) {
      errors.value.details = `商品 ${row.goodsName || row.goodsCode} 的实收数量必须大于 0`
      return
    }
    perGoods[row.goodsCode] = perGoods[row.goodsCode] || { remain: row.remainQty, sum: 0 }
    perGoods[row.goodsCode].sum += Number(row.receivedQty)
  }
  for (const [code, info] of Object.entries(perGoods)) {
    if (info.sum > info.remain + 1e-4) {
      errors.value.details = `商品 ${code} 本次实收 ${info.sum} 超过订单剩余 ${info.remain}`
      return
    }
  }

  const payload = {
    sourceOrder: headerForm.value.sourceOrder,
    supplier: headerForm.value.supplier,
    warehouse: headerForm.value.warehouse,
    billDate: headerForm.value.billDate,
    details: detailList.value.map(r => ({
      goodsCode: r.goodsCode,
      goodsName: r.goodsName,
      unitName: r.unitName,
      batchNo: r.batchNo,
      productionDate: r.productionDate || null,
      expiryDate: r.expiryDate || null,
      receivedQty: Number(r.receivedQty),
      price: Number(r.price),
    })),
  }

  try {
    const result = await post('/purchase/inbound/create', payload)
    emit('save', result)
    emit('close')
  } catch (e) {
    alert('保存失败：' + (e.message || '未知错误'))
  }
}

function closeDrawer() { emit('close') }
</script>

<template>
  <div v-show="visible" class="inbound-drawer-mask">
    <div class="inbound-drawer-box">
      <div class="inbound-drawer-head">
        <b>{{ props.sourceOrder ? '生成采购入库单' : '新建采购入库单' }}</b>
        <div style="flex:1"></div>
        <div class="actions">
          <button class="btn" @click="closeDrawer">取消</button>
          <button class="btn primary" @click="saveInbound">保存</button>
        </div>
      </div>

      <div class="inbound-drawer-body">
        <!-- 头部 -->
        <div class="card" style="padding:12px">
          <div style="font-weight:900;margin-bottom:10px;color:var(--primary)">单据信息</div>
          <div v-if="errors.header" style="color:var(--danger);font-size:12px;margin-bottom:6px">{{ errors.header }}</div>
          <div class="grid4">
            <div class="field">
              <label>来源采购订单 <span class="req">*</span></label>
              <!-- 入口 A：下拉选择；入口 B：只读展示 -->
              <select v-if="!props.sourceOrder" v-model="headerForm.sourceOrder" @change="onOrderChange($event.target.value)">
                <option value="">请选择</option>
                <option v-for="o in availableOrders" :key="o.orderNo" :value="o.orderNo">
                  {{ o.orderNo }} - {{ o.supplierName }}（订单额 ¥{{ o.amount }}）
                </option>
              </select>
              <input v-else readonly :value="headerForm.sourceOrder" />
            </div>
            <div class="field">
              <label>供应商</label>
              <input readonly :value="headerForm.supplier" />
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
            <div class="field">
              <label>已入库金额</label>
              <input readonly :value="'¥ ' + Number(headerForm.inboundedAmount).toFixed(2)" />
            </div>
          </div>
        </div>

        <!-- 明细 -->
        <div class="card detail-card">
          <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:8px">
            <div style="font-weight:900;color:var(--primary)">入库明细（每商品默认一行 · 可按批次拆分）</div>
            <div style="font-size:12px;color:#5d7896">提示：单价随订单单价只读；如需改价请回采购订单修改</div>
          </div>
          <div v-if="errors.details" style="color:var(--danger);font-size:12px;margin-bottom:6px">{{ errors.details }}</div>
          <div v-if="loading" class="empty-detail">加载中...</div>
          <div v-else-if="detailList.length === 0" class="empty-detail">请先选择来源采购订单</div>
          <div v-else class="detail-scroll">
            <table>
              <thead>
                <tr>
                  <th style="width:40px">#</th>
                  <th style="min-width:120px">商品编号</th>
                  <th style="min-width:160px">商品名称</th>
                  <th>规格</th>
                  <th style="width:60px">单位</th>
                  <th style="width:80px">订单数量</th>
                  <th style="width:80px">已入库</th>
                  <th style="width:80px">剩余</th>
                  <th style="width:100px">实收数量 <span class="req">*</span></th>
                  <th style="width:80px">单价</th>
                  <th style="width:90px">金额</th>
                  <th style="min-width:120px">批次号</th>
                  <th style="min-width:120px">生产日期</th>
                  <th style="min-width:120px">到期日期</th>
                  <th style="width:160px">操作</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="(row, index) in detailList" :key="index">
                  <td>{{ index + 1 }}</td>
                  <td>{{ row.goodsCode }}</td>
                  <td>{{ row.goodsName }}</td>
                  <td>{{ row.spec }}</td>
                  <td>{{ row.unitName }}</td>
                  <td style="text-align:right">{{ row.orderQty }}</td>
                  <td style="text-align:right">{{ row.inboundedQty }}</td>
                  <td style="text-align:right;font-weight:700;color:var(--primary)">{{ row.remainQty }}</td>
                  <td>
                    <input
                      type="number"
                      step="0.0001"
                      min="0"
                      v-model.number="row.receivedQty"
                      style="width:100%;height:24px;text-align:right"
                    />
                  </td>
                  <td style="text-align:right">{{ Number(row.price).toFixed(4) }}</td>
                  <td style="text-align:right;font-weight:700">
                    {{ (Number(row.receivedQty || 0) * Number(row.price || 0)).toFixed(2) }}
                  </td>
                  <td>
                    <input
                      v-model="row.batchNo"
                      @input="onBatchNoInput(row)"
                      style="width:100%;height:24px"
                    />
                  </td>
                  <td>
                    <input
                      type="date"
                      v-model="row.productionDate"
                      @change="onProductionDateChange(row)"
                      style="width:100%;height:24px"
                    />
                  </td>
                  <td>
                    <input type="date" v-model="row.expiryDate" style="width:100%;height:24px" />
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
.inbound-drawer-mask {
  position: fixed;
  top: 48px; right: 0; bottom: 0; left: 299px;
  z-index: 900;
  display: flex;
  pointer-events: none;
  animation: fadeIn 0.2s ease;
}
.inbound-drawer-box {
  flex: 1;
  background: #fff;
  display: flex; flex-direction: column;
  min-width: 0;
  border-left: 1px solid var(--line);
  box-shadow: -6px 0 24px rgba(15, 46, 88, 0.12);
  pointer-events: auto;
  animation: slideIn 0.25s ease;
}
.inbound-drawer-head {
  display: flex; align-items: center; gap: 10px;
  height: 46px; padding: 0 16px;
  border-bottom: 1px solid var(--line-soft);
}
.inbound-drawer-head b { font-size: 15px; }
.inbound-drawer-body {
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
  .inbound-drawer-mask { left: 0; }
}
</style>
