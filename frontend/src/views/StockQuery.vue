<script setup>
/**
 * 库存查询 —— 双 TAB 页面。
 *
 * TAB 1「库存查询」：按 goods_code + warehouse 聚合（跨批次合计）
 * TAB 2「批次库存查询」：按批次维度展示，含批次号 / 生产日期 / 到期日期；支持锁定 / 取消锁定
 *
 * 交互：
 *   · 默认进入「库存查询」TAB，不自动查询，点「查询」按钮才请求
 *   · 筛选默认只显示第一行，「展开更多」显示所有条件
 *   · 多选下拉走点击展开的复选框面板（原生 select multiple 体验差）
 *   · 商品分类树形展示，选上级自动全选下级
 *   · 表格页脚显示数量/金额合计
 *   · 支持字段设置（勾选显示列，localStorage 持久化）
 */
import { computed, defineComponent, h, onMounted, onBeforeUnmount, ref, watch } from 'vue'
import { post } from '../api/client.js'
import { getDict } from '../utils/dictionary.js'
import { useColumnSettings } from '../composables/useColumnSettings.js'
import FieldSettingDialog from '../components/FieldSettingDialog.vue'

// 递归树节点组件（用于分类多选下拉的树形展示）
const TreeNode = defineComponent({
  name: 'TreeNode',
  props: {
    node: { type: Object, required: true },
    level: { type: Number, default: 0 },
    selected: { type: Array, required: true },
  },
  emits: ['toggle'],
  setup(props, { emit }) {
    return () => {
      const isChecked = props.selected.includes(props.node.code)
      const items = [
        h('label', { class: 'dd-item', style: { paddingLeft: (props.level * 16) + 'px' } }, [
          h('input', {
            type: 'checkbox',
            checked: isChecked,
            onChange: () => emit('toggle', props.node.code),
          }),
          h('span', { style: 'margin-left:4px' }, props.node.name),
        ]),
      ]
      if (props.node.children && props.node.children.length) {
        props.node.children.forEach(child => {
          items.push(h(TreeNode, {
            node: child,
            level: props.level + 1,
            selected: props.selected,
            onToggle: (code) => emit('toggle', code),
          }))
        })
      }
      return h('div', {}, items)
    }
  },
})

const tab = ref('balance')   // balance / batch

// ==================== 筛选状态 ====================
const filters = ref({
  keyword: '',
  warehouses: [],
  categories: [],
  brands: [],
  managers: [],
  suppliers: [],
  storageProperties: [],
  showZero: false,
  batchNo: '',
  productionDateFrom: '',
  productionDateTo: '',
})
const showMoreFilters = ref(false)

// ==================== 下拉选项 ====================
const warehouseOpts = ref([])          // [string]
const brandOpts = ref([])
const managerOpts = ref([])
const supplierOpts = ref([])
const storagePropertyOpts = ref([])
const categoryTree = ref([])           // [{ code, name, parentCode, children: [...] }]
const categoryChildrenMap = ref({})    // { code: [childCodes]（递归所有后代）}
const categoryNameByCode = ref({})     // { code: name }

async function loadOptions() {
  const params = { pageNo: 1, pageSize: 500, filters: {} }
  const [wh, cat, brand, emp, sup, storage] = await Promise.all([
    post('/base/warehouse/page', params).catch(() => ({ records: [] })),
    post('/base/category/page', params).catch(() => ({ records: [] })),
    post('/base/brand/page', params).catch(() => ({ records: [] })),
    post('/base/master/employee/page', params).catch(() => ({ records: [] })),
    post('/base/supplier/page', params).catch(() => ({ records: [] })),
    getDict('storage_property').catch(() => []),
  ])
  warehouseOpts.value = (wh.records || []).map(r => r.warehouseName).filter(Boolean)
  brandOpts.value = (brand.records || []).map(r => r.brandName).filter(Boolean)
  managerOpts.value = (emp.records || []).map(r => r.employeeName).filter(Boolean)
  supplierOpts.value = (sup.records || []).map(r => r.supplierName).filter(Boolean)
  const storageDict = Array.isArray(storage) ? storage : []
  storagePropertyOpts.value = storageDict.length
      ? storageDict.map(d => d.name || d.code)
      : ['常温', '冷藏', '冷冻', '恒温']
  buildCategoryTree(cat.records || [])
}
onMounted(loadOptions)

// 构建分类树 + 递归子节点索引
function buildCategoryTree(records) {
  const nameByCode = {}
  const childMap = {}   // parentCode → [child record]
  records.forEach(r => {
    if (!r.categoryCode) return
    nameByCode[r.categoryCode] = r.categoryName
    const p = r.parentCode || ''
    ;(childMap[p] = childMap[p] || []).push(r)
  })
  const buildNode = (rec) => ({
    code: rec.categoryCode,
    name: rec.categoryName,
    parentCode: rec.parentCode || '',
    children: (childMap[rec.categoryCode] || []).map(buildNode),
  })
  const roots = (childMap[''] || []).map(buildNode)
  // 递归所有后代 code
  const descendants = {}
  const walk = (n) => {
    const list = []
    n.children.forEach(c => {
      list.push(c.code)
      list.push(...walk(c))
    })
    descendants[n.code] = list
    return list
  }
  roots.forEach(walk)
  categoryTree.value = roots
  categoryNameByCode.value = nameByCode
  categoryChildrenMap.value = descendants
}

// ==================== 查询结果 ====================
const loading = ref(false)
// 两个 TAB 独立缓存 records / summary / hasQueried，切换 TAB 不清空
const tabState = ref({
  balance: { records: [], summary: {}, hasQueried: false },
  batch: { records: [], summary: {}, hasQueried: false },
})
const records = computed(() => tabState.value[tab.value].records)
const summary = computed(() => tabState.value[tab.value].summary)
const hasQueried = computed(() => tabState.value[tab.value].hasQueried)

async function doQuery() {
  loading.value = true
  const activeTab = tab.value
  tabState.value[activeTab].hasQueried = true
  // 分类筛选：把选中的 code 都转成 name（后端按 categoryName 匹配）
  const catNames = filters.value.categories
      .map(c => categoryNameByCode.value[c])
      .filter(Boolean)
  const payload = {
    pageNo: 1,
    pageSize: 1000,
    filters: {
      keyword: filters.value.keyword.trim(),
      warehouses: filters.value.warehouses,
      categories: catNames,
      brands: filters.value.brands,
      managers: filters.value.managers,
      suppliers: filters.value.suppliers,
      storageProperties: filters.value.storageProperties,
      showZero: String(filters.value.showZero),
      batchNo: filters.value.batchNo.trim(),
      productionDateFrom: filters.value.productionDateFrom,
      productionDateTo: filters.value.productionDateTo,
    },
  }
  try {
    const url = activeTab === 'batch' ? '/inventory/batch/page' : '/inventory/balance/page'
    const res = await post(url, payload)
    tabState.value[activeTab].records = (res.records || []).map(deriveRow)
    tabState.value[activeTab].summary = res.summary || {}
  } catch (e) {
    alert('查询失败：' + (e.message || '未知错误'))
    tabState.value[activeTab].records = []
    tabState.value[activeTab].summary = {}
  } finally {
    loading.value = false
  }
}

function resetFilters() {
  filters.value = {
    keyword: '', warehouses: [], categories: [], brands: [], managers: [], suppliers: [], storageProperties: [],
    showZero: false, batchNo: '', productionDateFrom: '', productionDateTo: '',
  }
  // 只重置当前 TAB 的结果；筛选条件是共享的
  tabState.value[tab.value] = { records: [], summary: {}, hasQueried: false }
}

function switchTab(next) {
  if (tab.value === next) return
  tab.value = next
  // 数据 / 筛选条件均保留：tabState 里各 TAB 独立缓存结果，filters 是共享的
}

// ==================== 派生列 ====================
function deriveRow(r) {
  let largeConvertQty = 1
  try {
    const cfg = typeof r.unitConfig === 'string' ? JSON.parse(r.unitConfig) : r.unitConfig
    if (Array.isArray(cfg)) {
      const large = cfg.find(u => u.unitType === '大单位' && u.enabled !== false)
      if (large && Number(large.convertQty) > 0) largeConvertQty = Number(large.convertQty)
    }
  } catch (e) { /* ignore */ }
  const phys = Number(r.physicalQty) || 0
  const locked = Number(r.lockedQty) || 0
  const frozen = Number(r.frozenQty) || 0
  const avail = Number(r.availableQty) || 0
  const cost = Number(r.costPrice) || 0
  return {
    ...r,
    physicalQtyPieces: largeConvertQty > 1 ? (phys / largeConvertQty).toFixed(2) : String(phys),
    lockedQtyPieces: largeConvertQty > 1 ? (locked / largeConvertQty).toFixed(2) : String(locked),
    frozenQtyPieces: largeConvertQty > 1 ? (frozen / largeConvertQty).toFixed(2) : String(frozen),
    availableQtyPieces: largeConvertQty > 1 ? (avail / largeConvertQty).toFixed(2) : String(avail),
    availableStockAmount: (avail * cost).toFixed(2),
  }
}

// ==================== 锁定 / 取消锁定 弹窗 ====================
const lockDialog = ref(null)

function openLockDialog(row, mode) {
  const available = Number(row.availableQty || 0)
  const locked = Number(row.lockedQty || 0)
  lockDialog.value = {
    row,
    mode,
    qty: mode === 'unlock' ? locked : (available > 0 ? Math.min(1, available) : 0),
    maxQty: mode === 'unlock' ? locked : available,
  }
}
async function confirmLock() {
  const d = lockDialog.value
  if (!d) return
  const qty = Number(d.qty)
  if (!qty || qty <= 0) return alert('数量必须大于 0')
  if (qty > d.maxQty) return alert(`数量不能超过 ${d.maxQty}`)
  if (!d.row.batchStockId) return alert('批次记录缺失 batchStockId，请刷新查询')
  const url = d.mode === 'unlock' ? '/inventory/batch/unlock' : '/inventory/batch/lock'
  try {
    const res = await post(url, { batchStockId: d.row.batchStockId, qty })
    alert(res.effect || '操作成功')
    lockDialog.value = null
    doQuery()
  } catch (e) {
    alert('操作失败：' + (e.message || '未知错误'))
  }
}

// ==================== 表格列 ====================
const balanceColumns = [
  { key: 'goodsCode', title: '商品编号', width: 120 },
  { key: 'goodsName', title: '商品名称', width: 160 },
  { key: 'spec', title: '规格', width: 120 },
  { key: 'categoryName', title: '商品分类', width: 100 },
  { key: 'barcode', title: '条码', width: 120 },
  { key: 'baseUnit', title: '基本单位', width: 70 },
  { key: 'warehouse', title: '仓库', width: 100 },
  { key: 'costPrice', title: '成本单价', width: 90, align: 'right', num: true },
  { key: 'physicalQty', title: '实物库存', width: 90, align: 'right', num: true, sum: 'physicalQtySum' },
  { key: 'stockAmount', title: '库存金额', width: 100, align: 'right', num: true, sum: 'stockAmountSum' },
  { key: 'lockedQty', title: '锁定库存', width: 90, align: 'right', num: true, sum: 'lockedQtySum' },
  { key: 'frozenQty', title: '冻结库存', width: 90, align: 'right', num: true },
  { key: 'availableQty', title: '可用库存', width: 90, align: 'right', num: true, sum: 'availableQtySum' },
  { key: 'availableStockAmount', title: '可用库存金额', width: 110, align: 'right', num: true, sum: 'availableStockAmountSum' },
  { key: 'physicalQtyPieces', title: '实物件数', width: 90, align: 'right' },
  { key: 'lockedQtyPieces', title: '锁定件数', width: 90, align: 'right' },
  { key: 'frozenQtyPieces', title: '冻结件数', width: 90, align: 'right' },
  { key: 'availableQtyPieces', title: '可用件数', width: 90, align: 'right' },
  { key: 'purchaseOnWay', title: '采购在途', width: 90, align: 'right', num: true },
  { key: 'defaultSupplier', title: '主供应商', width: 120 },
  { key: 'brandName', title: '品牌', width: 90 },
  { key: 'storageProperty', title: '存储属性', width: 80 },
  { key: 'goodsManager', title: '采购负责人', width: 100 },
  { key: 'lastInoutTime', title: '最近出入库时间', width: 150 },
]
const batchColumns = [
  ...balanceColumns.slice(0, 7),
  { key: 'batchNo', title: '批次号', width: 130 },
  { key: 'productionDate', title: '生产日期', width: 110 },
  { key: 'expiryDate', title: '到期日期', width: 110 },
  ...balanceColumns.slice(7),
  { key: 'action', title: '操作', width: 160, action: true },
]
const allColumns = computed(() => tab.value === 'batch' ? batchColumns : balanceColumns)

// ==================== 字段设置（使用通用 composable） ====================
const {
  pendingSettings, visibleColumns, dialogColumnList,
  dialogOpen: fieldDialogOpen, draggingKey,
  openDialog: openFieldDialog, closeDialog: closeFieldDialog,
  saveSettings: saveColumnSettings, resetSettings: resetColumnSettings,
  moveInDialog,
  onHeaderDragStart, onHeaderDragOver, onHeaderDrop,
  startResize, cellStyle,
} = useColumnSettings({
  storageKey: () => `erp-field-setting-v2:stockQuery:${tab.value}`,
  allColumns,
})

// ==================== 格式化 ====================
function fmtCell(row, col) {
  const v = row[col.key]
  if (v == null || v === '') return ''
  if (col.num) {
    const n = Number(v)
    return Number.isFinite(n) ? n.toFixed(2) : String(v)
  }
  return String(v)
}
function fmtSummary(col) {
  if (!col.sum) return ''
  const v = summary.value[col.sum]
  if (v == null) return ''
  const n = Number(v)
  return Number.isFinite(n) ? n.toFixed(2) : String(v)
}

// cellStyle 由 useColumnSettings composable 提供（见文件顶部）

// ==================== 多选下拉面板（自定义组件替代原生 select multiple） ====================
const openDropdown = ref('')   // 当前打开的下拉 key，为空表示关闭

function toggleDropdown(key) {
  openDropdown.value = openDropdown.value === key ? '' : key
}
function closeDropdown() { openDropdown.value = '' }
function onOutsideClick(e) {
  if (openDropdown.value && !e.target.closest('.dd-wrap')) closeDropdown()
}
onMounted(() => document.addEventListener('click', onOutsideClick))
onBeforeUnmount(() => document.removeEventListener('click', onOutsideClick))

function toggleOption(list, val) {
  const idx = list.indexOf(val)
  if (idx >= 0) list.splice(idx, 1)
  else list.push(val)
}
function selectionLabel(list, placeholder) {
  if (!list || list.length === 0) return placeholder
  if (list.length === 1) return list[0]
  return `已选 ${list.length} 项`
}

// 分类树多选：选上级自动全选下级；取消上级自动取消下级
function toggleCategory(code) {
  const list = filters.value.categories
  const idx = list.indexOf(code)
  const descendants = categoryChildrenMap.value[code] || []
  if (idx >= 0) {
    // 取消勾选：自身 + 所有后代都从选中列表移除
    filters.value.categories = list.filter(c => c !== code && !descendants.includes(c))
  } else {
    // 勾选：自身 + 所有后代加入
    const toAdd = [code, ...descendants]
    filters.value.categories = Array.from(new Set([...list, ...toAdd]))
  }
}
function selectionCategoryLabel() {
  const list = filters.value.categories
  if (list.length === 0) return '全部分类'
  const names = list.map(c => categoryNameByCode.value[c]).filter(Boolean)
  if (names.length === 1) return names[0]
  return `已选 ${names.length} 项`
}
</script>

<template>
  <div class="stock-query">
    <!-- TAB 切换 -->
    <div class="tabs">
      <div class="tab" :class="{ active: tab === 'balance' }" @click="switchTab('balance')">库存查询</div>
      <div class="tab" :class="{ active: tab === 'batch' }" @click="switchTab('batch')">批次库存查询</div>
    </div>

    <!-- 筛选区 -->
    <div class="filter-card">
      <div class="filter-row">
        <div class="filter-field">
          <label>商品</label>
          <input v-model="filters.keyword" placeholder="商品编号 / 名称 / 条码 模糊" style="width:260px" />
        </div>

        <!-- 仓库多选下拉 -->
        <div class="filter-field">
          <label>仓库</label>
          <div class="dd-wrap" @click.stop>
            <div class="dd-input" @click="toggleDropdown('warehouses')">
              <span>{{ selectionLabel(filters.warehouses, '全部仓库') }}</span>
              <span class="dd-arrow">▾</span>
            </div>
            <div v-if="openDropdown === 'warehouses'" class="dd-panel">
              <label v-for="o in warehouseOpts" :key="o" class="dd-item">
                <input type="checkbox" :checked="filters.warehouses.includes(o)" @change="toggleOption(filters.warehouses, o)" />
                {{ o }}
              </label>
              <div v-if="warehouseOpts.length === 0" class="dd-empty">暂无数据</div>
            </div>
          </div>
        </div>

        <!-- 分类树形下拉 -->
        <div class="filter-field">
          <label>分类</label>
          <div class="dd-wrap" @click.stop>
            <div class="dd-input" @click="toggleDropdown('categories')">
              <span>{{ selectionCategoryLabel() }}</span>
              <span class="dd-arrow">▾</span>
            </div>
            <div v-if="openDropdown === 'categories'" class="dd-panel dd-tree">
              <template v-if="categoryTree.length === 0">
                <div class="dd-empty">暂无分类</div>
              </template>
              <template v-else>
                <div v-for="root in categoryTree" :key="root.code">
                  <TreeNode :node="root" :level="0" :selected="filters.categories" @toggle="toggleCategory" />
                </div>
              </template>
            </div>
          </div>
        </div>

        <div class="filter-actions">
          <button class="btn primary" @click="doQuery">查询</button>
          <button class="btn" @click="resetFilters">重置</button>
          <button class="btn" @click="showMoreFilters = !showMoreFilters">
            {{ showMoreFilters ? '收起 ▲' : '展开更多 ▼' }}
          </button>
          <button class="btn" @click="openFieldDialog">字段设置</button>
        </div>
      </div>

      <div v-if="showMoreFilters" class="filter-row-more">
        <div class="filter-field">
          <label>品牌</label>
          <div class="dd-wrap" @click.stop>
            <div class="dd-input" @click="toggleDropdown('brands')">
              <span>{{ selectionLabel(filters.brands, '全部品牌') }}</span>
              <span class="dd-arrow">▾</span>
            </div>
            <div v-if="openDropdown === 'brands'" class="dd-panel">
              <label v-for="o in brandOpts" :key="o" class="dd-item">
                <input type="checkbox" :checked="filters.brands.includes(o)" @change="toggleOption(filters.brands, o)" />
                {{ o }}
              </label>
              <div v-if="brandOpts.length === 0" class="dd-empty">暂无数据</div>
            </div>
          </div>
        </div>

        <div class="filter-field">
          <label>商品负责人</label>
          <div class="dd-wrap" @click.stop>
            <div class="dd-input" @click="toggleDropdown('managers')">
              <span>{{ selectionLabel(filters.managers, '全部负责人') }}</span>
              <span class="dd-arrow">▾</span>
            </div>
            <div v-if="openDropdown === 'managers'" class="dd-panel">
              <label v-for="o in managerOpts" :key="o" class="dd-item">
                <input type="checkbox" :checked="filters.managers.includes(o)" @change="toggleOption(filters.managers, o)" />
                {{ o }}
              </label>
              <div v-if="managerOpts.length === 0" class="dd-empty">暂无数据</div>
            </div>
          </div>
        </div>

        <div class="filter-field">
          <label>主供应商</label>
          <select v-model="filters.suppliers[0]" style="width:180px;height:32px"
                  @change="filters.suppliers = filters.suppliers[0] ? [filters.suppliers[0]] : []">
            <option value="">全部</option>
            <option v-for="o in supplierOpts" :key="o" :value="o">{{ o }}</option>
          </select>
        </div>

        <div class="filter-field">
          <label>存储属性</label>
          <div class="dd-wrap" @click.stop>
            <div class="dd-input" @click="toggleDropdown('storageProperties')">
              <span>{{ selectionLabel(filters.storageProperties, '全部') }}</span>
              <span class="dd-arrow">▾</span>
            </div>
            <div v-if="openDropdown === 'storageProperties'" class="dd-panel">
              <label v-for="o in storagePropertyOpts" :key="o" class="dd-item">
                <input type="checkbox" :checked="filters.storageProperties.includes(o)" @change="toggleOption(filters.storageProperties, o)" />
                {{ o }}
              </label>
            </div>
          </div>
        </div>

        <div v-if="tab === 'batch'" class="filter-field">
          <label>批次号</label>
          <input v-model="filters.batchNo" placeholder="批次号模糊" style="width:180px" />
        </div>
        <div v-if="tab === 'batch'" class="filter-field">
          <label>生产日期</label>
          <input type="date" v-model="filters.productionDateFrom" style="width:130px" />
          <span style="margin:0 4px">~</span>
          <input type="date" v-model="filters.productionDateTo" style="width:130px" />
        </div>

        <div class="filter-field">
          <label><input type="checkbox" v-model="filters.showZero" /> 显示为 0 的库存</label>
        </div>
      </div>
    </div>

    <!-- 表格 -->
    <div class="table-card">
      <div v-if="!hasQueried" class="empty-hint">请设置查询条件后点击「查询」显示结果</div>
      <div v-else-if="loading" class="empty-hint">加载中...</div>
      <div v-else-if="records.length === 0" class="empty-hint">未查询到记录</div>
      <div v-else class="table-scroll">
        <table>
          <thead>
            <tr>
              <th v-for="col in visibleColumns" :key="col.key"
                  :class="{ 'th-fixed': col.fixed, 'th-fixed-last': col.isLastFixed, 'th-dragging': draggingKey === col.key }"
                  :style="cellStyle(col, 'th')"
                  :draggable="!col.fixed && !col.action"
                  @dragstart="onHeaderDragStart($event, col)"
                  @dragover="onHeaderDragOver"
                  @drop="onHeaderDrop($event, col)">
                <span class="th-title">{{ col.title }}</span>
                <span v-if="col.fixed" class="th-flag" title="已固定">📌</span>
                <span class="th-resize" @mousedown="startResize($event, col)"></span>
              </th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(row, ri) in records" :key="ri">
              <td v-for="col in visibleColumns" :key="col.key"
                  :class="{ 'num': col.num, 'td-fixed': col.fixed, 'td-fixed-last': col.isLastFixed }"
                  :style="cellStyle(col, 'td')">
                <template v-if="col.action">
                  <button v-if="Number(row.availableQty) > 0" class="link link-btn" @click="openLockDialog(row, 'lock')">锁定</button>
                  <button v-if="Number(row.lockedQty) > 0" class="link link-btn danger-link" @click="openLockDialog(row, 'unlock')">取消锁定</button>
                </template>
                <template v-else>{{ fmtCell(row, col) }}</template>
              </td>
            </tr>
          </tbody>
          <tfoot>
            <tr class="summary-row">
              <td v-for="(col, ci) in visibleColumns" :key="col.key"
                  :class="{ 'td-fixed': col.fixed, 'td-fixed-last': col.isLastFixed }"
                  :style="cellStyle(col, 'tf')">
                <b v-if="ci === 0">合计</b>
                <template v-else-if="col.sum">{{ fmtSummary(col) }}</template>
              </td>
            </tr>
          </tfoot>
        </table>
      </div>
      <div v-if="hasQueried && records.length > 0" class="count-hint">共 {{ records.length }} 条</div>
    </div>

    <!-- 锁定 / 取消锁定 弹窗 -->
    <div v-if="lockDialog" class="dialog-mask" @click.self="lockDialog = null">
      <div class="dialog-box">
        <div class="dialog-head">
          <b>{{ lockDialog.mode === 'lock' ? '锁定批次库存' : '取消批次锁定' }}</b>
          <button class="btn" @click="lockDialog = null">×</button>
        </div>
        <div class="dialog-body">
          <div class="field"><label>批次</label><span>{{ lockDialog.row.batchNo }}（{{ lockDialog.row.goodsCode }} / {{ lockDialog.row.warehouse }}）</span></div>
          <div class="field"><label>{{ lockDialog.mode === 'lock' ? '可用数量' : '已锁数量' }}</label><span>{{ lockDialog.maxQty }}</span></div>
          <div class="field">
            <label>{{ lockDialog.mode === 'lock' ? '锁定数量' : '取消锁定数量' }}</label>
            <input type="number" v-model.number="lockDialog.qty" :min="0.0001" :max="lockDialog.maxQty" step="0.0001" style="width:200px" />
          </div>
        </div>
        <div class="dialog-foot">
          <button class="btn" @click="lockDialog = null">取消</button>
          <button class="btn primary" @click="confirmLock">确认</button>
        </div>
      </div>
    </div>

    <!-- 字段设置弹窗（通用组件） -->
    <FieldSettingDialog v-if="fieldDialogOpen"
      :title="`字段设置 —— ${tab === 'batch' ? '批次库存查询' : '库存查询'}`"
      :pending-settings="pendingSettings"
      :dialog-column-list="dialogColumnList"
      @save="saveColumnSettings"
      @reset="resetColumnSettings"
      @close="closeFieldDialog"
      @move="moveInDialog" />
  </div>
</template>

<!-- TreeNode 组件在 <script setup> 顶部定义，供分类树多选下拉使用 -->

<style scoped>
.stock-query { padding: 12px 16px; background: #f5f7fa; min-height: 100%; display: flex; flex-direction: column; gap: 12px; }
.tabs { display: flex; gap: 4px; border-bottom: 2px solid #e5e7eb; }
.tab { padding: 10px 20px; cursor: pointer; font-weight: 600; color: #606266; border-bottom: 2px solid transparent; margin-bottom: -2px; }
.tab.active { color: #409eff; border-bottom-color: #409eff; }

.filter-card { background: #fff; border: 1px solid #e5e7eb; border-radius: 6px; padding: 12px 16px; }
.filter-row, .filter-row-more { display: flex; flex-wrap: wrap; gap: 12px 20px; align-items: center; }
.filter-row-more { margin-top: 12px; padding-top: 12px; border-top: 1px dashed #f0f0f0; }
.filter-field { display: flex; align-items: center; gap: 6px; position: relative; }
.filter-field > label { font-size: 12px; color: #303133; font-weight: 600; }
.filter-field input:not([type=checkbox]), .filter-field select { height: 32px; padding: 0 8px; border: 1px solid #dcdfe6; border-radius: 4px; font-size: 12px; box-sizing: border-box; }
.filter-actions { display: flex; gap: 8px; margin-left: auto; }

/* 多选下拉 */
.dd-wrap { position: relative; }
.dd-input { min-width: 180px; height: 32px; padding: 0 8px; border: 1px solid #dcdfe6; border-radius: 4px; font-size: 12px; display: flex; align-items: center; justify-content: space-between; cursor: pointer; background: #fff; box-sizing: border-box; user-select: none; }
.dd-input:hover { border-color: #409eff; }
.dd-arrow { color: #909399; font-size: 10px; }
.dd-panel { position: absolute; top: 100%; left: 0; margin-top: 2px; min-width: 200px; max-height: 300px; overflow: auto; background: #fff; border: 1px solid #dcdfe6; border-radius: 4px; box-shadow: 0 6px 16px rgba(15, 46, 88, 0.12); z-index: 100; padding: 4px 0; }
.dd-tree { min-width: 260px; }
.dd-item { display: flex; align-items: center; gap: 6px; padding: 6px 12px; font-size: 12px; cursor: pointer; }
.dd-item:hover { background: #f5f7fa; }
.dd-item input[type=checkbox] { margin: 0; }
.dd-empty { padding: 12px; color: #909399; font-size: 12px; text-align: center; }

/* 表格 */
.table-card { background: #fff; border: 1px solid #e5e7eb; border-radius: 6px; flex: 1; display: flex; flex-direction: column; min-height: 300px; }
.empty-hint { padding: 60px; text-align: center; color: #909399; font-size: 13px; }
.table-scroll { flex: 1; overflow: auto; }
.stock-query table { width: 100%; border-collapse: collapse; font-size: 12px; }
.stock-query th { background: #f5f7fa; padding: 8px 10px; text-align: left; font-weight: 600; color: #303133; border-bottom: 1px solid #e5e7eb; position: sticky; top: 0; z-index: 2; white-space: nowrap; user-select: none; }
.stock-query td { padding: 8px 10px; border-bottom: 1px solid #f0f0f0; color: #606266; white-space: nowrap; }
.stock-query td.num { font-variant-numeric: tabular-nums; }
.summary-row { background: #fafbfc; font-weight: 700; }
.summary-row td { border-top: 2px solid #e5e7eb; color: #409eff; position: sticky; bottom: 0; z-index: 1; background: #fafbfc; }
.count-hint { padding: 8px 16px; font-size: 12px; color: #909399; border-top: 1px solid #f0f0f0; background: #fafbfc; }

/* 弹窗 */
.dialog-mask { position: fixed; top: 0; left: 0; right: 0; bottom: 0; background: rgba(0, 0, 0, 0.4); z-index: 1000; display: flex; align-items: center; justify-content: center; }
.dialog-box { background: #fff; border-radius: 8px; width: 480px; max-width: 90vw; overflow: hidden; display: flex; flex-direction: column; max-height: 80vh; }
.dialog-head { display: flex; align-items: center; justify-content: space-between; padding: 12px 16px; border-bottom: 1px solid #e5e7eb; }
.dialog-body { padding: 16px; display: flex; flex-direction: column; gap: 12px; overflow: auto; }
.dialog-body .field { display: flex; gap: 12px; align-items: center; }
.dialog-body .field > label { width: 100px; text-align: right; font-size: 12px; color: #303133; font-weight: 600; }
.dialog-foot { padding: 12px 16px; border-top: 1px solid #e5e7eb; display: flex; justify-content: flex-end; gap: 8px; }

/* 字段设置多列复选 */
/* 字段设置弹窗样式已迁移到 FieldSettingDialog.vue */

/* 表头拖拽 + 列宽 —— .stock-query th 通用样式已在前面定义 */
.th-title { pointer-events: none; }
.th-flag { margin-left: 4px; font-size: 10px; }
.th-dragging { opacity: 0.4; }
.th-resize { position: absolute; right: 0; top: 0; bottom: 0; width: 4px; cursor: col-resize; background: transparent; }
.th-resize:hover { background: #409eff; }

/* 固定列吸附左侧 —— sticky 定位由 cellStyle() 内联应用，此处只加边框/阴影视觉强调 */
.th-fixed, .td-fixed { border-right: 1px solid #f0f0f0; }
.th-fixed-last, .td-fixed-last { box-shadow: 2px 0 6px -2px rgba(0, 0, 0, 0.12); }
/* 悬停行时固定列同步高亮：sticky 元素有自己 background，需要覆盖 */
.stock-query tbody tr:hover .td-fixed { background: #f5f7fa !important; }
</style>
