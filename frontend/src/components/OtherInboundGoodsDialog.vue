<script setup>
/**
 * 其他入库单专用商品选择对话框
 *
 * 形态参照 CustomerPriceGoodsDialog（本项目既有的表格多选选择器）：
 *  - 表格多选，勾选后不关闭窗口，点「确定」一次性批量返回
 *  - 支持按商品编号 / 名称 / 条码 / 品牌 / 分类筛选
 *  - 表头全选只作用于当前页
 *  - 分页：100 / 500 / 1000 条/页
 *
 * 与 CustomerPriceGoodsDialog 的差异：
 *  - 展示入库关心的列：单位 / 标准售价 / 当前库存 / 成本单价（而非三级单位标价）
 *  - 已在明细中的商品**标记但不禁用** —— 其他入库允许同商品不同批次分行录入
 *
 * 本组件不发请求：商品列表（含成本/库存）由父组件加载后通过 goodsList 传入，
 * 避免换仓库时各处重复拉数。
 */
import { ref, computed, watch } from 'vue'

const props = defineProps({
  visible: { type: Boolean, default: false },
  /** 商品列表，父组件已按当前仓库补好 costPrice / availableStock */
  goodsList: { type: Array, default: () => [] },
  /** 已在明细中的商品编码，仅作标记（不禁用，允许同商品不同批次） */
  existingCodes: { type: Array, default: () => [] },
  /** 当前仓库名称，用于标题提示成本/库存的口径 */
  warehouse: { type: String, default: '' },
})

const emit = defineEmits(['confirm', 'close'])

// ==================== 筛选 ====================
const filters = ref(emptyFilters())
const pageNo = ref(1)
const pageSize = ref(100)
const selected = ref(new Set())

function emptyFilters() {
  return { goodsCode: '', goodsName: '', barcode: '', brandName: '', categoryName: '' }
}

watch(() => props.visible, (v) => {
  if (v) {
    filters.value = emptyFilters()
    pageNo.value = 1
    selected.value = new Set()
  }
})

/** 只保留正常状态商品，再按筛选条件过滤（全部为「包含」匹配，大小写不敏感） */
const filteredGoods = computed(() => {
  const f = filters.value
  const hit = (val, q) => !q || String(val || '').toLowerCase().includes(q.trim().toLowerCase())
  return props.goodsList.filter(g => {
    const st = String(g.status || 'NORMAL').toUpperCase()
    if (st === 'STOPPED') return false
    return hit(g.goodsCode, f.goodsCode)
      && hit(g.goodsName, f.goodsName)
      && hit(g.barcode, f.barcode)
      && hit(g.brandName, f.brandName)
      && hit(g.categoryName, f.categoryName)
  })
})

const total = computed(() => filteredGoods.value.length)
const totalPages = computed(() => Math.max(1, Math.ceil(total.value / pageSize.value)))

const pageGoods = computed(() => {
  const start = (pageNo.value - 1) * pageSize.value
  return filteredGoods.value.slice(start, start + pageSize.value)
})

// 筛选变化后回到第 1 页，避免停在超出范围的页码上看到空列表
watch([filters, pageSize], () => { pageNo.value = 1 }, { deep: true })
watch(totalPages, (tp) => { if (pageNo.value > tp) pageNo.value = tp })

function resetFilters() {
  filters.value = emptyFilters()
  pageNo.value = 1
}

// ==================== 选择 ====================
function isExisting(code) {
  return props.existingCodes.includes(code)
}

function toggle(code) {
  if (selected.value.has(code)) selected.value.delete(code)
  else selected.value.add(code)
  // Set 是引用类型，重新赋值才能触发视图更新
  selected.value = new Set(selected.value)
}

const pageCodes = computed(() => pageGoods.value.map(g => g.goodsCode))

/** 表头全选框：当前页全部已勾选时为选中态 */
const allPageChecked = computed(() =>
  pageCodes.value.length > 0 && pageCodes.value.every(c => selected.value.has(c))
)

function toggleAllPage(checked) {
  const next = new Set(selected.value)
  for (const c of pageCodes.value) {
    if (checked) next.add(c)
    else next.delete(c)
  }
  selected.value = next
}

/** 单行「添加」：立即返回该商品，窗口保持打开，方便连续挑几个 */
function quickAdd(g) {
  emit('confirm', [g])
}

function confirm() {
  const rows = props.goodsList.filter(g => selected.value.has(g.goodsCode))
  if (rows.length === 0) return
  emit('confirm', rows)
  selected.value = new Set()
  emit('close')
}

function cancel() {
  selected.value = new Set()
  emit('close')
}

function num(v, digits = 2) {
  return Number(v || 0).toFixed(digits)
}
</script>

<template>
  <div v-if="visible" class="oigd-mask" @click.self="cancel">
    <div class="oigd-box">
      <div class="oigd-head">
        <b>添加入库商品</b>
        <span class="oigd-sub">已选 {{ selected.size }} 个<template v-if="warehouse"> · 成本/库存取自「{{ warehouse }}」</template></span>
        <div style="flex:1"></div>
        <button class="btn" @click="cancel">取消</button>
        <button class="btn primary" :disabled="selected.size === 0" @click="confirm">
          确定添加 ({{ selected.size }})
        </button>
      </div>

      <!-- 筛选条 -->
      <div class="oigd-filters">
        <div class="fitem"><label>商品编号</label><input v-model="filters.goodsCode" placeholder="模糊匹配" /></div>
        <div class="fitem"><label>商品名称</label><input v-model="filters.goodsName" placeholder="模糊匹配" /></div>
        <div class="fitem"><label>条码</label><input v-model="filters.barcode" placeholder="模糊匹配" /></div>
        <div class="fitem"><label>品牌</label><input v-model="filters.brandName" placeholder="模糊匹配" /></div>
        <div class="fitem"><label>分类</label><input v-model="filters.categoryName" placeholder="模糊匹配" /></div>
        <button class="btn sm" @click="resetFilters">重置</button>
      </div>

      <!-- 商品表格 -->
      <div class="oigd-body">
        <table>
          <thead>
            <tr>
              <th style="width:36px">
                <input type="checkbox" :checked="allPageChecked" @change="toggleAllPage($event.target.checked)" />
              </th>
              <th style="width:110px">商品编号</th>
              <th style="min-width:170px">商品名称</th>
              <th style="width:110px">规格</th>
              <th style="width:60px">单位</th>
              <th style="width:110px">品牌</th>
              <th style="width:110px">分类</th>
              <th style="width:90px" class="r">标准售价</th>
              <th style="width:90px" class="r">当前库存</th>
              <th style="width:100px" class="r">成本单价</th>
              <th style="width:60px">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-if="pageGoods.length === 0">
              <td colspan="11" class="empty">没有符合条件的商品</td>
            </tr>
            <tr v-for="g in pageGoods" :key="g.goodsCode"
              :class="{ added: isExisting(g.goodsCode), sel: selected.has(g.goodsCode) }"
              @click="toggle(g.goodsCode)">
              <td @click.stop>
                <input type="checkbox" :checked="selected.has(g.goodsCode)" @change="toggle(g.goodsCode)" />
              </td>
              <td class="mono">{{ g.goodsCode }}</td>
              <td>
                {{ g.goodsName }}
                <span v-if="isExisting(g.goodsCode)" class="tag-added">已添加</span>
              </td>
              <td>{{ g.spec || '-' }}</td>
              <td>{{ g.baseUnit || '-' }}</td>
              <td>{{ g.brandName || '-' }}</td>
              <td>{{ g.categoryName || '-' }}</td>
              <td class="mono r">{{ num(g.standardPrice, 4) }}</td>
              <td class="mono r">{{ num(g.availableStock, 2) }}</td>
              <td class="mono r">{{ num(g.costPrice, 6) }}</td>
              <td @click.stop>
                <button class="link" @click="quickAdd(g)">添加</button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <!-- 分页 -->
      <div class="oigd-foot">
        <span class="tip">点行可勾选 · 行内「添加」立即加入且不关窗 · 已添加的商品仍可再选（同商品不同批次分行）</span>
        <div style="flex:1"></div>
        <span>共 {{ total }} 条</span>
        <select v-model.number="pageSize">
          <option :value="100">100 条/页</option>
          <option :value="500">500 条/页</option>
          <option :value="1000">1000 条/页</option>
        </select>
        <button class="btn sm" :disabled="pageNo <= 1" @click="pageNo--">上一页</button>
        <span>{{ pageNo }} / {{ totalPages }}</span>
        <button class="btn sm" :disabled="pageNo >= totalPages" @click="pageNo++">下一页</button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.oigd-mask {
  position: fixed; inset: 0;
  background: rgba(15, 35, 60, 0.35);
  z-index: 1200;
  display: flex; justify-content: center; align-items: center;
}
.oigd-box {
  width: 1080px; max-width: 96vw; height: 660px; max-height: 90vh;
  background: #fff; border-radius: 10px;
  box-shadow: 0 20px 60px rgba(0, 0, 0, 0.2);
  display: flex; flex-direction: column;
}
.oigd-head {
  display: flex; align-items: center; gap: 10px;
  padding: 10px 14px; border-bottom: 1px solid #e5e7eb; flex-shrink: 0;
}
.oigd-head b { font-size: 14px; }
.oigd-sub { color: #909399; font-size: 12px; }

.oigd-filters {
  display: flex; align-items: center; gap: 10px; flex-wrap: wrap;
  padding: 10px 14px; background: #fafbfc; border-bottom: 1px solid #e5e7eb; flex-shrink: 0;
}
.fitem { display: flex; align-items: center; gap: 5px; }
.fitem label { font-size: 12px; color: #666; white-space: nowrap; }
.fitem input {
  width: 120px; height: 26px; padding: 0 6px;
  border: 1px solid #dcdfe6; border-radius: 4px; font-size: 12px;
}
.fitem input:focus { outline: none; border-color: #409eff; }

.oigd-body { flex: 1; overflow: auto; min-height: 0; }
.oigd-body table { width: 100%; border-collapse: collapse; font-size: 12px; }
.oigd-body th {
  position: sticky; top: 0; z-index: 1;
  background: #f5f7fa; padding: 7px 6px; text-align: left;
  font-weight: 600; color: #303133; border-bottom: 1px solid #e5e7eb; white-space: nowrap;
}
.oigd-body td { padding: 5px 6px; border-bottom: 1px solid #f5f5f5; cursor: pointer; }
.oigd-body tr:hover td { background: #f5f9ff; }
.oigd-body tr.sel td { background: #ecf5ff; }
.oigd-body tr.added td { color: #8a94a0; }
.oigd-body .mono { font-family: var(--font-mono); font-variant-numeric: tabular-nums; }
.oigd-body .r { text-align: right; }
.oigd-body .empty { text-align: center; color: #bbb; padding: 50px 0; cursor: default; }
.tag-added {
  margin-left: 6px; padding: 0 5px; border-radius: 3px;
  background: #f4f4f5; color: #909399; font-size: 11px;
}

.oigd-foot {
  display: flex; align-items: center; gap: 8px;
  padding: 8px 14px; border-top: 1px solid #e5e7eb; flex-shrink: 0;
  font-size: 12px; color: #606266;
}
.oigd-foot .tip { color: #a8b3c0; }
.oigd-foot select {
  height: 26px; border: 1px solid #dcdfe6; border-radius: 4px; font-size: 12px;
}

.btn {
  padding: 4px 14px; border: 1px solid #d9d9d9; border-radius: 4px;
  background: #fff; cursor: pointer; font-size: 13px;
}
.btn:hover:not(:disabled) { border-color: #409eff; color: #409eff; }
.btn.primary { background: #409eff; color: #fff; border-color: #409eff; }
.btn.primary:hover:not(:disabled) { background: #3a8ee6; }
.btn:disabled { opacity: .5; cursor: not-allowed; }
.btn.sm { padding: 2px 10px; font-size: 12px; }
.link { background: none; border: none; cursor: pointer; font-size: 12px; color: #409eff; padding: 0; }
.link:hover { text-decoration: underline; }
</style>
