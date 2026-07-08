<script setup>
/**
 * 客户价格调整专用商品选择对话框
 *
 * 与 GoodsAddDialog 不同：
 *  - 表格多选模式，勾选后不关闭窗口
 *  - 展示三级单位（小/中/大）标价
 *  - 支持按商品编号、名称、条码、品牌、分类、存储属性筛选
 *  - 分页：100/500/1000/2000 条/页
 *  - 只展示正常状态商品，已添加的不可重复勾选
 */
import { ref, computed, watch } from 'vue'
import { post } from '../api/client.js'

const props = defineProps({
  visible: { type: Boolean, default: false },
  /** 已添加的商品编码集合，防止重复添加 */
  existingCodes: { type: Array, default: () => [] },
})

const emit = defineEmits(['confirm', 'close'])

// ==================== 数据加载 ====================
const loading = ref(false)
const allGoods = ref([])   // 全部正常状态商品

async function loadGoods() {
  if (allGoods.value.length) return
  loading.value = true
  try {
    // 一次性加载全部正常商品（后端 keyword 为空时返回全部，但可能受 PageHelper 限制）
    // 先试加载 2000 条，不够再追加
    const data = await post('/base/goods/page', { pageNo: 1, pageSize: 2000, filters: {} })
    allGoods.value = (data.records || []).filter(g => {
      const st = (g.status || '').toUpperCase()
      return st === 'NORMAL' || st === '正常'
    })
  } catch (_) {
    allGoods.value = []
  } finally {
    loading.value = false
  }
}

watch(() => props.visible, (v) => {
  if (v) {
    loadGoods()
    resetFilters()
  }
})

// ==================== 筛选条件 ====================
const filters = ref({
  goodsCode: '',
  goodsName: '',
  barcode: '',
  brandName: '',
  categoryName: '',
  storageProperty: '',
})

function resetFilters() {
  filters.value = { goodsCode: '', goodsName: '', barcode: '', brandName: '', categoryName: '', storageProperty: '' }
  currentPage.value = 1
}

// ==================== 客户端筛选 ====================
function matchesFilter(g) {
  const f = filters.value
  const fields = [
    { key: 'goodsCode', val: f.goodsCode },
    { key: 'goodsName', val: f.goodsName },
    { key: 'barcode', val: f.barcode },
    { key: 'brandName', val: f.brandName },
    { key: 'categoryName', val: f.categoryName },
    { key: 'storageProperty', val: f.storageProperty },
  ]
  return fields.every(({ key, val }) => {
    if (!val || !val.trim()) return true
    const v = String(g[key] || '').toLowerCase()
    return v.includes(String(val).trim().toLowerCase())
  })
}

const filteredGoods = computed(() => allGoods.value.filter(matchesFilter))

// ==================== 勾选 ====================
const selected = ref(new Set())

function isExisting(code) {
  return props.existingCodes.includes(code)
}

function toggle(code) {
  if (isExisting(code)) return
  if (selected.value.has(code)) selected.value.delete(code)
  else selected.value.add(code)
}

/** 当前页可勾选（未添加过）的商品编码 */
const selectablePageCodes = computed(() =>
  pagedGoods.value.map(g => g.goodsCode).filter(code => !isExisting(code))
)

/** 表头全选框状态：当前页所有可选商品都已勾选 */
const allPageSelected = computed(() =>
  selectablePageCodes.value.length > 0
  && selectablePageCodes.value.every(code => selected.value.has(code))
)

/** 表头全选 / 取消全选，只影响当前页且跳过已添加的商品 */
function toggleSelectAllOnPage(checked) {
  selectablePageCodes.value.forEach(code => {
    if (checked) selected.value.add(code)
    else selected.value.delete(code)
  })
}

// ==================== 分页 ====================
const pageSize = ref(100)
const currentPage = ref(1)
const pageSizeOptions = [100, 500, 1000, 2000]

const totalFiltered = computed(() => filteredGoods.value.length)
const totalPages = computed(() => Math.max(1, Math.ceil(totalFiltered.value / pageSize.value)))
const pagedGoods = computed(() => {
  const start = (currentPage.value - 1) * pageSize.value
  return filteredGoods.value.slice(start, start + pageSize.value)
})

// 筛选条件 / 每页条数变化时回到第一页。
// 必须 deep：v-model 改的是 filters.value 的属性，对象引用不变，浅监听不会触发。
watch([filters, pageSize], () => {
  currentPage.value = 1
}, { deep: true })

// 筛选后总页数变少时，避免停留在空白页
watch(totalPages, (tp) => {
  if (currentPage.value > tp) currentPage.value = tp
})

function setPage(p) {
  if (p >= 1 && p <= totalPages.value) currentPage.value = p
}

// ==================== 三级单位解析（复用 PriceAdjustDrawer 的 parseUnitProfile 逻辑）====================
function parseUnitProfile(g) {
  const out = {
    smallUnit: g.baseUnit || '',
    smallStandardPrice: null,
    mediumUnit: '',
    mediumStandardPrice: null,
    mediumEnabled: false,
    largeUnit: '',
    largeStandardPrice: null,
    largeEnabled: false,
  }
  if (!g) return out

  // 小单位标价兜底
  if (g.standardPrice != null) {
    const sp = Number(g.standardPrice)
    out.smallStandardPrice = Number.isFinite(sp) ? sp : null
  }

  try {
    const cfg = typeof g.unitConfig === 'string' ? JSON.parse(g.unitConfig) : g.unitConfig
    if (!Array.isArray(cfg)) return out

    const keys = [
      ['smallUnit', 'smallStandardPrice', 'smallEnabled'],
      ['mediumUnit', 'mediumStandardPrice', 'mediumEnabled'],
      ['largeUnit', 'largeStandardPrice', 'largeEnabled'],
    ]
    cfg.slice(0, 3).forEach((u, i) => {
      if (!u || typeof u !== 'object') return
      const [uk, pk, ek] = keys[i]
      if (u.unitName) out[uk] = String(u.unitName)
      out[ek] = u.enabled !== false // 缺省视为启用
      const sp = Number(u.standardPrice)
      if (Number.isFinite(sp)) out[pk] = sp
    })
  } catch (_) { /* ignore */ }

  // 小单位始终启用
  out.smallEnabled = true
  return out
}

// 为表格行预计算单位 profile
const goodsWithProfile = computed(() =>
  pagedGoods.value.map(g => ({ ...g, _profile: parseUnitProfile(g) }))
)

/**
 * 标价显示：单位未启用时显示「—」。
 * 停用单位在商品档案里 standardPrice 常为 0，直接显示 0.00 会被误读成真实售价。
 */
function fmtStandardPrice(v, enabled = true) {
  if (!enabled || v == null) return '—'
  return Number(v).toFixed(2)
}

// ==================== 确定 / 关闭 ====================
function confirm() {
  const selectedRows = allGoods.value
    .filter(g => selected.value.has(g.goodsCode))
    .map(g => ({ ...g, _profile: parseUnitProfile(g) }))
  emit('confirm', selectedRows)
  selected.value = new Set()
}

function close() {
  selected.value = new Set()
  emit('close')
}
</script>

<template>
  <div v-if="visible" class="cpd-overlay" @click.self="close">
    <div class="cpd-box">
      <div class="cpd-head">
        <b>添加调价商品（已选 {{ selected.size }} 个）</b>
        <div class="cpd-actions">
          <button class="btn" @click="close">关闭</button>
          <button class="btn primary" :disabled="selected.size === 0" @click="confirm">确定</button>
        </div>
      </div>

      <!-- 筛选区 -->
      <div class="cpd-filters">
        <div class="filter-item">
          <label>商品编号</label>
          <input v-model="filters.goodsCode" placeholder="模糊匹配" />
        </div>
        <div class="filter-item">
          <label>商品名称</label>
          <input v-model="filters.goodsName" placeholder="模糊匹配" />
        </div>
        <div class="filter-item">
          <label>条码</label>
          <input v-model="filters.barcode" placeholder="模糊匹配" />
        </div>
        <div class="filter-item">
          <label>品牌</label>
          <input v-model="filters.brandName" placeholder="模糊匹配" />
        </div>
        <div class="filter-item">
          <label>商品分类</label>
          <input v-model="filters.categoryName" placeholder="模糊匹配" />
        </div>
        <div class="filter-item">
          <label>存储属性</label>
          <input v-model="filters.storageProperty" placeholder="模糊匹配" />
        </div>
        <div class="filter-item">
          <label>&nbsp;</label>
          <button class="btn" @click="resetFilters">重置</button>
        </div>
      </div>

      <!-- 表格 -->
      <div class="cpd-table-wrapper">
        <div v-if="loading" class="cpd-empty">加载中…</div>
        <table v-else>
          <thead>
            <tr>
              <th style="width:36px">
                <input type="checkbox"
                  :checked="allPageSelected"
                  @change="toggleSelectAllOnPage($event.target.checked)" />
              </th>
              <th>商品编号</th>
              <th>商品名称</th>
              <th>规格</th>
              <th>条码</th>
              <th>商品分类</th>
              <th>品牌</th>
              <th>小单位</th>
              <th class="num">小单位标价</th>
              <th>大单位</th>
              <th class="num">大单位标价</th>
              <th>中单位</th>
              <th class="num">中单位标价</th>
              <th>存储属性</th>
            </tr>
          </thead>
          <tbody>
            <tr v-if="goodsWithProfile.length === 0">
              <td colspan="14" class="cpd-empty">无匹配商品</td>
            </tr>
            <tr v-for="g in goodsWithProfile" :key="g.goodsCode"
              :class="{ 'cpd-disabled': isExisting(g.goodsCode) }"
              @click="toggle(g.goodsCode)">
              <td>
                <input type="checkbox"
                  :checked="selected.has(g.goodsCode) || isExisting(g.goodsCode)"
                  :disabled="isExisting(g.goodsCode)"
                  @click.stop="toggle(g.goodsCode)" />
              </td>
              <td>{{ g.goodsCode }}</td>
              <td>{{ g.goodsName }}</td>
              <td>{{ g.spec || '-' }}</td>
              <td>{{ g.barcode || '-' }}</td>
              <td>{{ g.categoryName || '-' }}</td>
              <td>{{ g.brandName || '-' }}</td>
              <td>{{ g._profile.smallUnit || g.baseUnit || '-' }}</td>
              <td class="num">{{ fmtStandardPrice(g._profile.smallStandardPrice) }}</td>
              <!-- 列顺序为 小 → 大 → 中（与需求指定的字段顺序一致），
                   因此这里先绑 large 再绑 medium，不能按 profile 的字段声明顺序写 -->
              <td :class="{ 'cpd-off': !g._profile.largeEnabled }">{{ g._profile.largeUnit || '—' }}</td>
              <td class="num" :class="{ 'cpd-off': !g._profile.largeEnabled }">{{ fmtStandardPrice(g._profile.largeStandardPrice, g._profile.largeEnabled) }}</td>
              <td :class="{ 'cpd-off': !g._profile.mediumEnabled }">{{ g._profile.mediumUnit || '—' }}</td>
              <td class="num" :class="{ 'cpd-off': !g._profile.mediumEnabled }">{{ fmtStandardPrice(g._profile.mediumStandardPrice, g._profile.mediumEnabled) }}</td>
              <td>{{ g.storageProperty || '-' }}</td>
            </tr>
          </tbody>
        </table>
      </div>

      <!-- 底部分页 -->
      <div class="cpd-footer">
        <span class="cpd-total">共 {{ totalFiltered }} 条</span>
        <div class="cpd-pager">
          <label>每页</label>
          <select :value="pageSize" @change="pageSize = Number($event.target.value)">
            <option v-for="n in pageSizeOptions" :key="n" :value="n">{{ n }}</option>
          </select>
          <button class="btn btn-sm" :disabled="currentPage <= 1" @click="setPage(currentPage - 1)">上一页</button>
          <span class="cpd-page">{{ currentPage }} / {{ totalPages }}</span>
          <button class="btn btn-sm" :disabled="currentPage >= totalPages" @click="setPage(currentPage + 1)">下一页</button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.cpd-overlay {
  position: fixed; inset: 0; z-index: 2000;
  background: rgba(0,0,0,.45);
  display: flex; align-items: center; justify-content: center;
}
.cpd-box {
  background: #fff; border-radius: 8px;
  width: min(1200px, 96vw); max-height: 90vh;
  display: flex; flex-direction: column;
  box-shadow: 0 8px 32px rgba(0,0,0,.18);
}
.cpd-head {
  display: flex; align-items: center; padding: 14px 20px;
  border-bottom: 1px solid #e5e7eb;
}
.cpd-head b { font-size: 15px; color: #303133; }
.cpd-actions { margin-left: auto; display: flex; gap: 8px; }

/* 筛选区 */
.cpd-filters {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 8px 16px;
  padding: 12px 20px;
  border-bottom: 1px solid #f0f0f0;
}
.filter-item { display: flex; align-items: center; gap: 6px; }
.filter-item label {
  font-size: 12px; color: #606266; white-space: nowrap; min-width: 52px; text-align: right;
}
.filter-item input {
  flex: 1; height: 28px; padding: 0 8px; border: 1px solid #dcdfe6; border-radius: 4px; font-size: 12px;
}

/* 表格 */
.cpd-table-wrapper {
  flex: 1; overflow: auto; min-height: 0; padding: 0 20px;
}
.cpd-table-wrapper table {
  width: 100%; border-collapse: collapse; font-size: 12px;
}
.cpd-table-wrapper th {
  background: #f5f7fa; padding: 8px 6px; text-align: left;
  font-weight: 600; color: #303133; border-bottom: 1px solid #e5e7eb;
  position: sticky; top: 0; z-index: 1;
}
.cpd-table-wrapper td {
  padding: 5px 6px; border-bottom: 1px solid #f0f0f0; cursor: pointer;
}
.cpd-table-wrapper .num { text-align: right; font-variant-numeric: tabular-nums; }
.cpd-table-wrapper tr:hover { background: #f5f7fa; }
.cpd-table-wrapper tr.cpd-disabled { background: #fafafa; color: #c0c4cc; cursor: not-allowed; }
.cpd-table-wrapper tr.cpd-disabled:hover { background: #fafafa; }
.cpd-off { color: #c0c4cc; }
.cpd-empty { text-align: center; color: #909399; padding: 40px; }

/* 分页 */
.cpd-footer {
  display: flex; align-items: center; justify-content: space-between;
  padding: 10px 20px; border-top: 1px solid #e5e7eb; font-size: 12px;
}
.cpd-total { color: #606266; }
.cpd-pager { display: flex; align-items: center; gap: 8px; }
.cpd-pager select { height: 28px; padding: 0 6px; border: 1px solid #dcdfe6; border-radius: 4px; font-size: 12px; }
.cpd-page { color: #303133; min-width: 60px; text-align: center; }
.btn-sm { padding: 2px 10px; font-size: 12px; height: 28px; }
</style>