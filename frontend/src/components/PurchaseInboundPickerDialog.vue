<script setup>
/**
 * 采购入库单商品选择窗口（【按单添加商品】入口）
 *
 * 左右表格结构：
 *   · 左表：该供应商已审核的采购入库单（入库单号、单据日期）
 *           - 可按单号 + 日期段查询，默认最近一年、按日期降序
 *           - 默认选中第一行并自动加载右表
 *   · 右表：左侧选中单据的入库明细，可多选
 *           - 字段：商品编号、商品名称、规格、单据单位、数量、单价、金额、生产日期、批次号、已退数量
 *           - 支持按商品编号/名称查询定位
 *
 * 交互约定：
 *   · 点【确认】把已选记录 emit 出去但**不关闭窗口**，可继续选其他单据累加退货
 *   · 点【关闭】才退出
 *   · 已退完（可退数量 = 0）的行禁止勾选
 */
import { ref, computed, watch } from 'vue'
import { get } from '../api/client.js'

const props = defineProps({
  visible: { type: Boolean, default: false },
  /** 主表选中的供应商名称 —— 只查该供应商的入库单 */
  supplierName: { type: String, default: '' },
})
const emit = defineEmits(['close', 'confirm'])

// ============ 左表：入库单列表 ============
const billList = ref([])
const billLoading = ref(false)
const selectedBillId = ref('')
const billQuery = ref({ inboundNo: '', dateFrom: '', dateTo: '' })

// ============ 右表：入库明细 ============
const detailList = ref([])
const detailLoading = ref(false)
const goodsKeyword = ref('')
const checkedIds = ref(new Set())
const errorMsg = ref('')

/** 累计已确认条数（跨单据累加，仅用于底部提示） */
const confirmedCount = ref(0)

const selectedBill = computed(() => billList.value.find(b => b.inboundId === selectedBillId.value) || null)
/** 可勾选行 = 可退数量 > 0 */
const selectableRows = computed(() => detailList.value.filter(r => Number(r.returnableQty || 0) > 0))
const allChecked = computed(() =>
  selectableRows.value.length > 0 && selectableRows.value.every(r => checkedIds.value.has(r.detailId))
)
const checkedCount = computed(() => checkedIds.value.size)

function oneYearAgo() {
  const d = new Date()
  d.setFullYear(d.getFullYear() - 1)
  return d.toISOString().slice(0, 10)
}
function today() {
  return new Date().toISOString().slice(0, 10)
}

// 打开窗口：重置状态并加载入库单
watch(() => props.visible, async (val) => {
  if (!val) return
  errorMsg.value = ''
  confirmedCount.value = 0
  checkedIds.value = new Set()
  detailList.value = []
  goodsKeyword.value = ''
  billQuery.value = { inboundNo: '', dateFrom: oneYearAgo(), dateTo: today() }
  await loadBills()
})

/** 加载左表入库单；加载后默认选中第一行 */
async function loadBills() {
  if (!props.supplierName) {
    errorMsg.value = '请先在主表选择供应商'
    billList.value = []
    return
  }
  billLoading.value = true
  errorMsg.value = ''
  try {
    const params = new URLSearchParams({
      supplierName: props.supplierName,
      dateFrom: billQuery.value.dateFrom || '',
      dateTo: billQuery.value.dateTo || '',
    })
    if (billQuery.value.inboundNo) params.set('inboundNo', billQuery.value.inboundNo)
    const rows = await get(`/purchase/return-apply/inbound-bills?${params.toString()}`)
    billList.value = Array.isArray(rows) ? rows : []
    // 默认选中第一行
    if (billList.value.length > 0) {
      await selectBill(billList.value[0].inboundId)
    } else {
      selectedBillId.value = ''
      detailList.value = []
    }
  } catch (e) {
    errorMsg.value = e.message || '加载入库单失败'
    billList.value = []
    detailList.value = []
  } finally {
    billLoading.value = false
  }
}

/** 选中左表某单据 → 加载右表明细（保留已勾选项，跨单据累加） */
async function selectBill(inboundId) {
  if (!inboundId) return
  selectedBillId.value = inboundId
  await loadDetails()
}

async function loadDetails() {
  if (!selectedBillId.value) return
  detailLoading.value = true
  try {
    const params = new URLSearchParams({ inboundId: selectedBillId.value })
    if (goodsKeyword.value.trim()) params.set('goodsKeyword', goodsKeyword.value.trim())
    const data = await get(`/purchase/return-apply/inbound-detail?${params.toString()}`)
    detailList.value = (data.details || []).map(d => ({
      ...d,
      qty: Number(d.qty || 0),
      price: Number(d.price || 0),
      amount: Number(d.amount || 0),
      returnedQty: Number(d.returnedQty || 0),
      returnableQty: Number(d.returnableQty || 0),
      costPrice: Number(d.costPrice || 0),
      availableStock: Number(d.availableStock || 0),
      productionDate: String(d.productionDate || '').slice(0, 10),
      sourceInboundNo: d.sourceInboundNo || data.inboundNo,
      warehouse: data.warehouse,
    }))
  } catch (e) {
    errorMsg.value = e.message || '加载入库明细失败'
    detailList.value = []
  } finally {
    detailLoading.value = false
  }
}

/** 商品关键字查询：重新拉当前单据明细 */
function searchGoods() {
  loadDetails()
}

function toggleRow(detailId, checked) {
  const next = new Set(checkedIds.value)
  if (checked) next.add(detailId)
  else next.delete(detailId)
  checkedIds.value = next
}

function toggleAll(checked) {
  const next = new Set(checkedIds.value)
  selectableRows.value.forEach(r => {
    if (checked) next.add(r.detailId)
    else next.delete(r.detailId)
  })
  checkedIds.value = next
}

/**
 * 确认添加：把已勾选明细转成退货明细行 emit 出去。
 *
 * @param close true = 【确定】添加后关闭窗口；false = 【勾选添加】添加后保留窗口，可切换其他单据继续添加
 * @returns 是否成功添加（无勾选时返回 false，【确定】据此决定不关窗）
 */
function confirmSelection(close = false) {
  const rows = detailList.value.filter(r => checkedIds.value.has(r.detailId))
  if (rows.length === 0) {
    errorMsg.value = '请先勾选要退货的商品'
    return false
  }
  const payload = rows.map(r => ({
    returnMode: 'BY_BILL',
    goodsCode: r.goodsCode,
    goodsName: r.goodsName,
    spec: r.spec || '',
    unitName: r.unitName || '',
    qty: r.returnableQty,          // 默认全退可退数量
    price: r.price,
    batchNo: r.batchNo || '',
    productionDate: r.productionDate || '',
    sourceInboundNo: r.sourceInboundNo,
    sourceDetailId: r.detailId,
    returnableQty: r.returnableQty,
    costPrice: r.costPrice,
    availableStock: r.availableStock,
    warehouse: r.warehouse || '',
  }))
  emit('confirm', payload)
  confirmedCount.value += payload.length
  // 清空本单勾选，方便继续选其他单据
  checkedIds.value = new Set()
  errorMsg.value = ''
  if (close) emit('close')
  return true
}

/** 【确定】：添加并关闭；若一条都没勾选则提示且不关窗 */
function confirmAndClose() {
  confirmSelection(true)
}

function closeDialog() {
  emit('close')
}
</script>

<template>
  <div v-if="visible" class="ipd-mask" @click.self="closeDialog">
    <div class="ipd-box">
      <div class="ipd-head">
        <b>按单添加商品 —— 选择采购入库单</b>
        <span class="ipd-sup-tag">供应商：{{ supplierName || '未选择' }}</span>
        <div style="flex:1"></div>
        <button class="btn" @click="closeDialog">关闭</button>
        <button class="btn" @click="confirmSelection(false)">
          勾选添加<span v-if="checkedCount">（{{ checkedCount }}）</span>
        </button>
        <button class="btn primary" @click="confirmAndClose">确定</button>
      </div>

      <div v-if="errorMsg" class="ipd-err">{{ errorMsg }}</div>

      <div class="ipd-body">
        <!-- 左：入库单列表 -->
        <div class="ipd-left">
          <div class="ipd-panel-title">已审核入库单</div>
          <div class="ipd-filter">
            <input v-model="billQuery.inboundNo" placeholder="入库单号" @keydown.enter="loadBills" />
            <div class="ipd-date-row">
              <input type="date" v-model="billQuery.dateFrom" />
              <span class="sep">~</span>
              <input type="date" v-model="billQuery.dateTo" />
            </div>
            <button class="btn" @click="loadBills">查询</button>
          </div>
          <div class="ipd-table-wrap">
            <div v-if="billLoading" class="ipd-empty">加载中...</div>
            <div v-else-if="billList.length === 0" class="ipd-empty">无符合条件的已审核入库单</div>
            <table v-else>
              <thead>
                <tr>
                  <th>入库单号</th>
                  <th style="width:100px">单据日期</th>
                </tr>
              </thead>
              <tbody>
                <tr
                  v-for="b in billList"
                  :key="b.inboundId"
                  class="ipd-bill-row"
                  :class="{ active: b.inboundId === selectedBillId }"
                  @click="selectBill(b.inboundId)"
                >
                  <td>{{ b.inboundNo }}</td>
                  <td>{{ String(b.billDate || '').slice(0, 10) }}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>

        <!-- 右：入库明细 -->
        <div class="ipd-right">
          <div class="ipd-panel-title">
            入库明细
            <span v-if="selectedBill" class="ipd-sub">{{ selectedBill.inboundNo }} · {{ selectedBill.warehouse }}</span>
          </div>
          <div class="ipd-filter">
            <input v-model="goodsKeyword" placeholder="商品编号 / 名称，回车查询" @keydown.enter="searchGoods" />
            <button class="btn" @click="searchGoods">查询</button>
          </div>
          <div class="ipd-table-wrap">
            <div v-if="detailLoading" class="ipd-empty">加载中...</div>
            <div v-else-if="!selectedBillId" class="ipd-empty">请先在左侧选择入库单</div>
            <div v-else-if="detailList.length === 0" class="ipd-empty">该单据无匹配明细</div>
            <table v-else>
              <thead>
                <tr>
                  <th style="width:36px">
                    <input type="checkbox" :checked="allChecked" :disabled="selectableRows.length === 0"
                           @change="toggleAll($event.target.checked)" />
                  </th>
                  <th style="min-width:100px">商品编号</th>
                  <th style="min-width:150px">商品名称</th>
                  <th style="min-width:90px">规格</th>
                  <th style="width:70px">单据单位</th>
                  <th style="width:70px">数量</th>
                  <th style="width:80px">单价</th>
                  <th style="width:85px">金额</th>
                  <th style="width:100px">生产日期</th>
                  <th style="min-width:100px">批次号</th>
                  <th style="width:80px">已退数量</th>
                </tr>
              </thead>
              <tbody>
                <tr
                  v-for="r in detailList"
                  :key="r.detailId"
                  :class="{ disabled: Number(r.returnableQty) <= 0, checked: checkedIds.has(r.detailId) }"
                >
                  <td>
                    <input
                      type="checkbox"
                      :checked="checkedIds.has(r.detailId)"
                      :disabled="Number(r.returnableQty) <= 0"
                      :title="Number(r.returnableQty) <= 0 ? '该行已退完，无可退数量' : ''"
                      @change="toggleRow(r.detailId, $event.target.checked)"
                    />
                  </td>
                  <td>{{ r.goodsCode }}</td>
                  <td>{{ r.goodsName }}</td>
                  <td>{{ r.spec || '-' }}</td>
                  <td>{{ r.unitName }}</td>
                  <td class="num">{{ r.qty }}</td>
                  <td class="num">{{ r.price.toFixed(4) }}</td>
                  <td class="num">{{ r.amount.toFixed(2) }}</td>
                  <td>{{ r.productionDate || '-' }}</td>
                  <td>{{ r.batchNo || '-' }}</td>
                  <td class="num" :class="{ warn: Number(r.returnedQty) > 0 }">{{ r.returnedQty }}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>
      </div>

      <div class="ipd-foot">
        <span>本单已勾选：<b>{{ checkedCount }}</b> 条</span>
        <span v-if="confirmedCount > 0" class="ok">本次已添加：<b>{{ confirmedCount }}</b> 条</span>
        <div style="flex:1"></div>
        <span class="tip">提示：【勾选添加】添加后保留窗口，可切换其他单据继续添加；【确定】添加并关闭窗口。</span>
      </div>
    </div>
  </div>
</template>

<style scoped>
.ipd-mask {
  position: fixed; inset: 0;
  background: rgba(15, 35, 60, 0.35);
  z-index: 1300;
  display: flex; justify-content: center; align-items: center;
}
.ipd-box {
  width: 1180px; max-width: 96vw;
  height: 660px; max-height: 92vh;
  background: #fff; border-radius: 10px;
  box-shadow: 0 20px 60px rgba(0, 0, 0, 0.2);
  display: flex; flex-direction: column;
}
.ipd-head {
  display: flex; align-items: center; gap: 10px;
  padding: 10px 14px;
  border-bottom: 1px solid #e5e7eb;
  flex-shrink: 0;
}
.ipd-head b { font-size: 14px; }
.ipd-sup { color: #666; font-size: 12px; }
/* 供应商标签：顶部醒目展示当前所选供应商 */
.ipd-sup-tag {
  padding: 3px 10px;
  background: #ecf5ff;
  border: 1px solid #d9ecff;
  border-radius: 12px;
  color: #409eff;
  font-size: 12px; font-weight: 700;
  white-space: nowrap;
}
.ipd-err {
  margin: 8px 14px 0;
  padding: 6px 10px;
  background: #fef0f0; border: 1px solid #fde2e2; border-radius: 4px;
  color: #f56c6c; font-size: 12px;
  flex-shrink: 0;
}
.ipd-body {
  flex: 1; min-height: 0;
  display: flex; gap: 10px;
  padding: 10px 14px;
}
.ipd-left { width: 38%; min-width: 0; display: flex; flex-direction: column; }
.ipd-right { flex: 1; min-width: 0; display: flex; flex-direction: column; }
.ipd-panel-title {
  font-weight: 700; font-size: 13px; color: var(--primary, #409eff);
  padding-bottom: 6px;
  display: flex; align-items: center; gap: 8px;
}
.ipd-sub { font-weight: 400; font-size: 12px; color: #909399; }
.ipd-filter {
  display: flex; align-items: center; gap: 6px;
  padding-bottom: 6px; flex-wrap: wrap;
}
.ipd-filter input {
  height: 28px; padding: 0 8px;
  border: 1px solid #dcdfe6; border-radius: 4px;
  font-size: 12px; min-width: 0; flex: 1;
}
.ipd-filter input[type="date"] { flex: none; width: 130px; }
.ipd-date-row { display: flex; align-items: center; gap: 4px; }
.ipd-date-row .sep { color: #909399; font-size: 12px; }
.ipd-table-wrap {
  flex: 1; min-height: 0; overflow: auto;
  border: 1px solid #e5e7eb; border-radius: 6px;
}
.ipd-table-wrap table { width: 100%; border-collapse: collapse; font-size: 12px; }
.ipd-table-wrap th {
  position: sticky; top: 0; z-index: 1;
  background: #f5f7fa; color: #303133; font-weight: 700;
  padding: 7px 8px; text-align: left; white-space: nowrap;
  border-bottom: 1px solid #e5e7eb;
}
.ipd-table-wrap td {
  padding: 6px 8px; border-bottom: 1px solid #f0f2f5;
  white-space: nowrap;
}
.ipd-table-wrap td.num { text-align: right; font-variant-numeric: tabular-nums; }
.ipd-table-wrap td.num.warn { color: #e6a23c; font-weight: 700; }
.ipd-bill-row { cursor: pointer; }
.ipd-bill-row:hover { background: #f5f7fa; }
.ipd-bill-row.active { background: #ecf5ff; font-weight: 700; }
.ipd-table-wrap tr.disabled { color: #c0c4cc; background: #fafafa; }
.ipd-table-wrap tr.checked { background: #f0f9eb; }
.ipd-empty { padding: 40px; text-align: center; color: #909399; font-size: 12px; }
.ipd-foot {
  display: flex; align-items: center; gap: 14px;
  padding: 8px 14px;
  border-top: 1px solid #e5e7eb;
  font-size: 12px; color: #606266;
  flex-shrink: 0;
}
.ipd-foot .ok { color: #67c23a; }
.ipd-foot .tip { color: #909399; }
</style>