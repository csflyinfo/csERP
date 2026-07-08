<script setup>
/**
 * 采购退货 —— 商品档案选择窗口（【添加商品】入口）
 *
 * 三个页签：
 *   · 历史采购（默认）：该供应商已审核入库单里出现过的商品，按最近入库时间倒序
 *   · 供应商商品：商品档案里关联该供应商（default_supplier）的商品
 *   · 全部商品：全部正常商品
 *
 * 交互约定：
 *   · 可多选，点【确认添加】emit 出去但**不关闭窗口**，可继续切页签/搜索累加
 *   · 点【关闭】才退出
 *   · 添加的商品退货方式固定 BY_GOODS（按品退货），数量/单位/价格/金额均可在明细里改
 */
import { ref, computed, watch } from 'vue'
import { get } from '../api/client.js'

const props = defineProps({
  visible: { type: Boolean, default: false },
  /** 主表选中的供应商名称 —— 历史采购 / 供应商商品 两个页签需要 */
  supplierName: { type: String, default: '' },
  /** 主表选中的仓库 —— 用于带出该仓库的成本单价与可用库存 */
  warehouse: { type: String, default: '' },
})
const emit = defineEmits(['close', 'confirm'])

const TABS = [
  { key: 'HISTORY', label: '历史采购' },
  { key: 'SUPPLIER', label: '供应商商品' },
  { key: 'ALL', label: '全部商品' },
]

const activeTab = ref('HISTORY')
const keyword = ref('')
const goodsList = ref([])
const loading = ref(false)
const errorMsg = ref('')
const checkedCodes = ref(new Set())
const confirmedCount = ref(0)

const allChecked = computed(() =>
  goodsList.value.length > 0 && goodsList.value.every(g => checkedCodes.value.has(g.goodsCode))
)
const checkedCount = computed(() => checkedCodes.value.size)

watch(() => props.visible, async (val) => {
  if (!val) return
  errorMsg.value = ''
  confirmedCount.value = 0
  checkedCodes.value = new Set()
  keyword.value = ''
  activeTab.value = 'HISTORY'
  await loadGoods()
})

async function switchTab(tab) {
  if (activeTab.value === tab) return
  activeTab.value = tab
  await loadGoods()
}

async function loadGoods() {
  loading.value = true
  errorMsg.value = ''
  try {
    const params = new URLSearchParams({ tab: activeTab.value })
    if (props.supplierName) params.set('supplierName', props.supplierName)
    if (props.warehouse) params.set('warehouse', props.warehouse)
    if (keyword.value.trim()) params.set('keyword', keyword.value.trim())
    const rows = await get(`/purchase/return-apply/goods-options?${params.toString()}`)
    goodsList.value = (Array.isArray(rows) ? rows : []).map(g => ({
      ...g,
      latestPurchasePrice: Number(g.latestPurchasePrice || 0),
      costPrice: Number(g.costPrice || 0),
      availableStock: Number(g.availableStock || 0),
    }))
  } catch (e) {
    errorMsg.value = e.message || '加载商品失败'
    goodsList.value = []
  } finally {
    loading.value = false
  }
}

function toggleRow(goodsCode, checked) {
  const next = new Set(checkedCodes.value)
  if (checked) next.add(goodsCode)
  else next.delete(goodsCode)
  checkedCodes.value = next
}

function toggleAll(checked) {
  const next = new Set(checkedCodes.value)
  goodsList.value.forEach(g => {
    if (checked) next.add(g.goodsCode)
    else next.delete(g.goodsCode)
  })
  checkedCodes.value = next
}

/**
 * 解析 unit_config，取小单位名（按品退货默认用小单位，明细里可切换）。
 * unitConfig 是 JSON 数组：索引 0=小 1=中 2=大。
 */
function parseSmallUnit(g) {
  const raw = g.unitConfig
  if (!raw) return g.baseUnit || ''
  let cfg
  try { cfg = typeof raw === 'string' ? JSON.parse(raw) : raw } catch (_) { return g.baseUnit || '' }
  if (!Array.isArray(cfg)) return g.baseUnit || ''
  const small = cfg.find(u => u && u.enabled !== false && u.unitName)
  return small?.unitName || g.baseUnit || ''
}

/**
 * 确认添加：转成退货明细行 emit 出去。
 *
 * @param close true = 【确定】添加后关闭；false = 【勾选添加】添加后保留窗口继续选
 */
function confirmSelection(close = false) {
  const rows = goodsList.value.filter(g => checkedCodes.value.has(g.goodsCode))
  if (rows.length === 0) {
    errorMsg.value = '请先勾选要退货的商品'
    return false
  }
  const payload = rows.map(g => ({
    returnMode: 'BY_GOODS',
    goodsCode: g.goodsCode,
    goodsName: g.goodsName,
    spec: g.spec || '',
    unitName: parseSmallUnit(g),
    unitConfig: g.unitConfig || '',
    qty: 0,                                                   // 按品退货数量由用户填
    price: g.latestPurchasePrice || g.costPrice || 0,          // 参考进价兜底成本价
    batchNo: '',
    productionDate: '',
    sourceInboundNo: '',
    sourceDetailId: '',
    returnableQty: 0,
    costPrice: g.costPrice,
    availableStock: g.availableStock,
  }))
  emit('confirm', payload)
  confirmedCount.value += payload.length
  checkedCodes.value = new Set()
  errorMsg.value = ''
  if (close) emit('close')
  return true
}

/** 【确定】：添加并关闭 */
function confirmAndClose() {
  confirmSelection(true)
}

function closeDialog() {
  emit('close')
}
</script>

<template>
  <div v-if="visible" class="gpd-mask" @click.self="closeDialog">
    <div class="gpd-box">
      <div class="gpd-head">
        <b>添加商品 —— 按品退货</b>
        <span class="gpd-sup-tag">供应商：{{ supplierName || '未选择' }}</span>
        <span class="gpd-sup-tag">仓库：{{ warehouse || '未选择' }}</span>
        <div style="flex:1"></div>
        <button class="btn" @click="closeDialog">关闭</button>
        <button class="btn" @click="confirmSelection(false)">
          勾选添加<span v-if="checkedCount">（{{ checkedCount }}）</span>
        </button>
        <button class="btn primary" @click="confirmAndClose">确定</button>
      </div>

      <div class="gpd-tabs">
        <button
          v-for="t in TABS"
          :key="t.key"
          class="gpd-tab"
          :class="{ active: activeTab === t.key }"
          @click="switchTab(t.key)"
        >{{ t.label }}</button>
        <div style="flex:1"></div>
        <div class="gpd-search">
          <input v-model="keyword" placeholder="商品编号 / 名称 / 条码，回车查询" @keydown.enter="loadGoods" />
          <button class="btn" @click="loadGoods">查询</button>
        </div>
      </div>

      <div v-if="errorMsg" class="gpd-err">{{ errorMsg }}</div>

      <div class="gpd-body">
        <div v-if="loading" class="gpd-empty">加载中...</div>
        <div v-else-if="goodsList.length === 0" class="gpd-empty">
          {{ activeTab === 'HISTORY' ? '该供应商暂无历史采购商品'
             : activeTab === 'SUPPLIER' ? '该供应商暂无关联商品（商品档案「默认供应商」未指向该供应商）'
             : '无匹配商品' }}
        </div>
        <table v-else>
          <thead>
            <tr>
              <th style="width:36px">
                <input type="checkbox" :checked="allChecked" @change="toggleAll($event.target.checked)" />
              </th>
              <th style="min-width:110px">商品编号</th>
              <th style="min-width:170px">商品名称</th>
              <th style="min-width:100px">规格</th>
              <th style="width:80px">基本单位</th>
              <th style="width:95px">参考进价</th>
              <th style="width:100px">成本单价</th>
              <th style="width:90px">可用库存</th>
              <th v-if="activeTab === 'HISTORY'" style="width:110px">最近入库日期</th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="g in goodsList"
              :key="g.goodsCode"
              :class="{ checked: checkedCodes.has(g.goodsCode) }"
            >
              <td>
                <input
                  type="checkbox"
                  :checked="checkedCodes.has(g.goodsCode)"
                  @change="toggleRow(g.goodsCode, $event.target.checked)"
                />
              </td>
              <td>{{ g.goodsCode }}</td>
              <td>{{ g.goodsName }}</td>
              <td>{{ g.spec || '-' }}</td>
              <td>{{ g.baseUnit || '-' }}</td>
              <td class="num">{{ g.latestPurchasePrice.toFixed(4) }}</td>
              <td class="num">{{ g.costPrice.toFixed(6) }}</td>
              <td class="num" :class="{ zero: g.availableStock <= 0 }">{{ g.availableStock }}</td>
              <td v-if="activeTab === 'HISTORY'">{{ String(g.lastInboundDate || '').slice(0, 10) || '-' }}</td>
            </tr>
          </tbody>
        </table>
      </div>

      <div class="gpd-foot">
        <span>已勾选：<b>{{ checkedCount }}</b> 条</span>
        <span v-if="confirmedCount > 0" class="ok">本次已添加：<b>{{ confirmedCount }}</b> 条</span>
        <div style="flex:1"></div>
        <span class="tip">提示：【勾选添加】添加后保留窗口可继续选；按品退货的数量、单位、单价、金额与批次号在明细中修改。</span>
      </div>
    </div>
  </div>
</template>

<style scoped>
.gpd-mask {
  position: fixed; inset: 0;
  background: rgba(15, 35, 60, 0.35);
  z-index: 1300;
  display: flex; justify-content: center; align-items: center;
}
.gpd-box {
  width: 940px; max-width: 96vw;
  height: 620px; max-height: 92vh;
  background: #fff; border-radius: 10px;
  box-shadow: 0 20px 60px rgba(0, 0, 0, 0.2);
  display: flex; flex-direction: column;
}
.gpd-head {
  display: flex; align-items: center; gap: 10px;
  padding: 10px 14px;
  border-bottom: 1px solid #e5e7eb;
  flex-shrink: 0;
}
.gpd-head b { font-size: 14px; }
.gpd-sup { color: #666; font-size: 12px; }
/* 供应商 / 仓库标签：顶部醒目展示当前上下文 */
.gpd-sup-tag {
  padding: 3px 10px;
  background: #ecf5ff;
  border: 1px solid #d9ecff;
  border-radius: 12px;
  color: #409eff;
  font-size: 12px; font-weight: 700;
  white-space: nowrap;
}
.gpd-tabs {
  display: flex; align-items: center; gap: 4px;
  padding: 8px 14px 0;
  border-bottom: 1px solid #e5e7eb;
  flex-shrink: 0;
}
.gpd-tab {
  padding: 6px 14px;
  border: 1px solid transparent; border-bottom: none;
  background: transparent; cursor: pointer;
  font-size: 13px; color: #606266;
  border-radius: 6px 6px 0 0;
}
.gpd-tab:hover { color: #409eff; }
.gpd-tab.active {
  background: #fff;
  border-color: #e5e7eb;
  color: #409eff; font-weight: 700;
  margin-bottom: -1px;
}
.gpd-search { display: flex; align-items: center; gap: 6px; padding-bottom: 6px; }
.gpd-search input {
  height: 28px; width: 250px; padding: 0 8px;
  border: 1px solid #dcdfe6; border-radius: 4px; font-size: 12px;
}
.gpd-err {
  margin: 8px 14px 0;
  padding: 6px 10px;
  background: #fef0f0; border: 1px solid #fde2e2; border-radius: 4px;
  color: #f56c6c; font-size: 12px;
  flex-shrink: 0;
}
.gpd-body {
  flex: 1; min-height: 0; overflow: auto;
  margin: 10px 14px;
  border: 1px solid #e5e7eb; border-radius: 6px;
}
.gpd-body table { width: 100%; border-collapse: collapse; font-size: 12px; }
.gpd-body th {
  position: sticky; top: 0; z-index: 1;
  background: #f5f7fa; color: #303133; font-weight: 700;
  padding: 7px 8px; text-align: left; white-space: nowrap;
  border-bottom: 1px solid #e5e7eb;
}
.gpd-body td { padding: 6px 8px; border-bottom: 1px solid #f0f2f5; white-space: nowrap; }
.gpd-body td.num { text-align: right; font-variant-numeric: tabular-nums; }
.gpd-body td.num.zero { color: #c0c4cc; }
.gpd-body tr.checked { background: #f0f9eb; }
.gpd-empty { padding: 50px; text-align: center; color: #909399; font-size: 12px; }
.gpd-foot {
  display: flex; align-items: center; gap: 14px;
  padding: 8px 14px;
  border-top: 1px solid #e5e7eb;
  font-size: 12px; color: #606266;
  flex-shrink: 0;
}
.gpd-foot .ok { color: #67c23a; }
.gpd-foot .tip { color: #909399; }
</style>