<script setup>
import { ref, watch, computed, onMounted } from 'vue'
import { post, get } from '../../../api/client.js'

const props = defineProps({
  modelValue: { type: Array, required: true },
  isWeighted: { type: Boolean, default: false },
  visible: { type: Boolean, default: true },
  /** 当前商品编号 —— 编辑时用于拉价格组现价，新增时为 null */
  goodsCode: { type: String, default: '' },
  /** 是否只读（编辑商品时为 true，新建商品可编辑） */
  readonly: { type: Boolean, default: false },
  /** 当前商品名称（快速调价确认弹窗用） */
  goodsName: { type: String, default: '' },
})

const emit = defineEmits(['update:modelValue', 'quickAdjust'])

// 是否启用价格联动（仅对 standardPrice / minPrice / suggestRetailPrice 生效，
// 参考进价与价格组价格不参与联动）
const priceLinked = ref(true)

// ========== 动态价格组（从后端 /base/goods/price-groups 拉取）==========
const realPriceGroups = ref([])   // [{ priceGroupCode, priceGroupName, ...unitPrices }]
const priceGroupsLoading = ref(false)

/** 拉取已启用的价格组及其在当前商品的三级单位价格 */
async function loadPriceGroups() {
  priceGroupsLoading.value = true
  try {
    const params = props.goodsCode
      ? `?goodsCode=${encodeURIComponent(props.goodsCode)}`
      : ''
    const rows = await get(`/base/goods/price-groups${params}`)
    realPriceGroups.value = (Array.isArray(rows) ? rows : []).map(pg => ({
      priceGroupCode: pg.priceGroupCode,
      priceGroupName: pg.priceGroupName,
      sortOrder: pg.sortOrder,
      smallPrice: Number(pg.smallPrice || 0),
      middlePrice: Number(pg.middlePrice || 0),
      largePrice: Number(pg.largePrice || 0),
    }))
  } catch (e) {
    console.warn('加载价格组失败', e)
    realPriceGroups.value = []
  } finally {
    priceLoading.value = false
  }
}

onMounted(loadPriceGroups)
// 切换商品时重新拉取价格组价格
watch(() => props.goodsCode, () => { loadPriceGroups() })
// 抽屉打开/关闭时刷新（新增→编辑切换时 goodsCode 从空变有值）
watch(() => props.visible, (v) => { if (v) loadPriceGroups() })

// ========== 列顺序与属性字段 ==========
const columnOrder = [0, 2, 1]  // 小 → 大 → 中

// 属性字段配置（按展示顺序）
const attributeFields = [
  { key: 'enabled', label: '是否启用', type: 'checkbox' },
  { key: 'unitName', label: '单位', type: 'select', required: true },
  { key: 'convertQty', label: '换算数量', type: 'number', required: true, decimals: 0 },
  { key: 'barcode', label: '条码', type: 'text' },
  // 参考进价 —— 放在标准售价上方，不参与价格联动
  { key: 'purchasePrice', label: '参考进价', type: 'price', noLink: true },
  { key: 'standardPrice', label: '标准售价', type: 'price', required: true },
  { key: 'minPrice', label: '最低价', type: 'price' },
  { key: 'suggestRetailPrice', label: '建议零售价', type: 'price' },
]

// ========== 单位选项 ==========
const unitOptions = ref([])
async function loadUnits() {
  try {
    const data = await post('/base/unit/page', { pageNo: 1, pageSize: 500, filters: {} })
    unitOptions.value = (data.records || [])
      .filter(r => r.unitName && (r.status == null || r.status === 'NORMAL' || r.status === '正常'))
      .map(r => ({
        name: r.unitName,
        canMiddleUnit: !!r.canMiddleUnit,
        canLargeUnit: !!r.canLargeUnit,
      }))
  } catch (e) {
    console.warn('加载单位数据失败', e)
  }
}
onMounted(loadUnits)
watch(() => props.visible, (v) => { if (v) loadUnits() })

function optionsForCol(colIdx) {
  if (colIdx === 1) return unitOptions.value.filter(u => u.canMiddleUnit)
  if (colIdx === 2) return unitOptions.value.filter(u => u.canLargeUnit)
  return unitOptions.value
}

// ========== 数据初始化 ==========
function ensureUnitStructure(unit, index) {
  const defaults = {
    unitType: ['小单位', '中单位', '大单位'][index],
    unitName: '',
    barcode: '',
    convertQty: index === 0 ? 1 : (index === 1 ? 12 : 24),
    purchasePrice: 0,
    standardPrice: 0,
    minPrice: 0,
    suggestRetailPrice: 0,
    weight: 0,
    volume: 0,
    minOrderQty: 0,
    isSaleUnit: true,
    isPurchaseUnit: true,
    enabled: index === 0,
  }
  return { ...defaults, ...unit }
}

// ========== 业务逻辑 ==========
function handleEnableChange(unitIndex, enabled) {
  if (unitIndex === 0) return
  const units = [...props.modelValue]
  if (unitIndex === 1 && enabled && !units[2]?.enabled) {
    alert('启用中单位前，请先启用大单位')
    return
  }
  units[unitIndex].enabled = enabled
  if (enabled && priceLinked.value && units[0]?.standardPrice > 0) {
    const baseUnit = units[0]
    const ratio = units[unitIndex].convertQty / baseUnit.convertQty
    units[unitIndex].standardPrice = Number((baseUnit.standardPrice * ratio).toFixed(2))
  }
  emit('update:modelValue', units)
}

/**
 * 价格联动逻辑 —— 仅对 standardPrice / minPrice / suggestRetailPrice 生效。
 * 参考进价(purchasePrice)和价格组价格不参与联动。
 */
const LINKED_FIELDS = ['standardPrice', 'minPrice', 'suggestRetailPrice']

function handlePriceChange(unitIndex, field, value) {
  const units = [...props.modelValue]
  units[unitIndex][field] = Number(value) || 0

  if (priceLinked.value && LINKED_FIELDS.includes(field)) {
    const baseUnitIndex = units.findIndex(u => u.enabled)
    if (baseUnitIndex === -1) { emit('update:modelValue', units); return }
    const baseUnit = units[baseUnitIndex]
    const baseRatio = baseUnit.convertQty
    units.forEach((unit, idx) => {
      if (idx !== baseUnitIndex && unit.enabled) {
        unit[field] = Number((baseUnit[field] * unit.convertQty / baseRatio).toFixed(2))
      }
    })
  }

  emit('update:modelValue', units)
}

function handleConvertQtyChange(unitIndex, value) {
  const units = [...props.modelValue]
  units[unitIndex].convertQty = Number(value) || 1
  if (priceLinked.value) {
    const baseUnitIndex = units.findIndex(u => u.enabled)
    if (baseUnitIndex !== -1) {
      const baseUnit = units[baseUnitIndex]
      LINKED_FIELDS.forEach(field => {
        if (baseUnit[field] > 0) {
          units.forEach((unit, idx) => {
            if (idx !== baseUnitIndex && unit.enabled) {
              unit[field] = Number((baseUnit[field] * unit.convertQty / baseUnit.convertQty).toFixed(2))
            }
          })
        }
      })
    }
  }
  emit('update:modelValue', units)
}

// ========== 价格组值（存入单位数据，key = `pg_<priceGroupCode>`）==========
/** 读：从单位对象取价格组价格（兼容存量：可能还未初始化） */
function getPriceGroupValue(unit, pgCode) {
  return unit[`pg_${pgCode}`] ?? 0
}
/** 价格组价格设为只读 —— 不提供修改入口 */
</script>

<template>
  <div class="multi-unit-matrix">
    <!-- 顶部操作栏 -->
    <div class="header-bar">
      <label class="link-checkbox">
        <input type="checkbox" v-model="priceLinked" />
        <span>启用价格联动</span>
      </label>
      <span class="hint">启用后，修改任意单位价格或换算数量，其他单位<b>标准售价/最低价/建议零售价</b>将自动按比例换算。<b>参考进价与价格组价格不联动。</b></span>
    </div>

    <!-- 多单位二维表 -->
    <table class="matrix-table">
      <thead>
        <tr>
          <th class="attr-col">属性</th>
          <th v-for="colIdx in columnOrder" :key="colIdx" class="unit-col">
            {{ modelValue[colIdx]?.unitType || ['小单位', '中单位', '大单位'][colIdx] }}
            <span v-if="colIdx === 0" class="badge">(基本单位)</span>
          </th>
        </tr>
      </thead>
      <tbody>
        <!-- 固定属性行 -->
        <tr v-for="field in attributeFields" :key="field.key">
          <td class="attr-label">{{ field.label }}</td>
          <td v-for="colIdx in columnOrder" :key="colIdx" class="input-cell">
            <template v-for="unit in [modelValue[colIdx]]" :key="'u' + colIdx">
              <!-- 是否启用 -->
              <template v-if="field.key === 'enabled'">
                <input type="checkbox" :checked="unit.enabled"
                       :disabled="colIdx === 0"
                       @change="handleEnableChange(colIdx, $event.target.checked)" />
              </template>
              <!-- 单位名称 -->
              <template v-else-if="field.key === 'unitName'">
                <select v-model="unit.unitName" :disabled="!unit.enabled" class="unit-select">
                  <option value="">请选择</option>
                  <option v-for="opt in optionsForCol(colIdx)" :key="opt.name" :value="opt.name">{{ opt.name }}</option>
                </select>
              </template>
              <!-- 换算数量 -->
              <template v-else-if="field.key === 'convertQty'">
                <input type="number" v-model.number="unit.convertQty"
                       :disabled="!unit.enabled || colIdx === 0"
                       placeholder="1" step="1" min="1"
                       @change="handleConvertQtyChange(colIdx, unit.convertQty)" />
              </template>
              <!-- 条码 -->
              <template v-else-if="field.key === 'barcode'">
                <input type="text" v-model="unit.barcode" :disabled="!unit.enabled" placeholder="输入条码" />
              </template>
              <!-- 价格类型（非价格组）—— 编辑时只读，只能通过调价单调整 -->
              <template v-else-if="field.type === 'price' && !field.noLink">
                <input type="number" :value="unit[field.key]"
                       :disabled="!unit.enabled || props.readonly"
                       :title="props.readonly && unit.enabled ? '编辑时不可修改「' + field.label + '」，请使用右侧【快速调价】按钮通过调价单调整' : ''"
                       placeholder="0.00" step="0.01"
                       class="price-input"
                       :class="{ 'readonly-price': props.readonly && unit.enabled }"
                       @input="handlePriceChange(colIdx, field.key, $event.target.value)" />
              </template>
              <!-- 参考进价：不参与联动，纯手动录入；编辑时同样只读 -->
              <template v-else-if="field.type === 'price' && field.noLink">
                <input type="number" v-model.number="unit.purchasePrice"
                       :disabled="!unit.enabled || props.readonly"
                       :title="props.readonly && unit.enabled ? '编辑时不可修改「参考进价」，请使用右侧【快速调价】按钮通过调价单调整' : '参考进价不参与价格联动，需手动逐单位录入'"
                       placeholder="0.00" step="0.01"
                       class="price-input"
                       :class="{ 'readonly-price': props.readonly && unit.enabled }" />
              </template>
              <!-- 重量 -->
              <template v-else-if="field.key === 'weight'">
                <input type="number" v-model.number="unit.weight" :disabled="!unit.enabled" placeholder="0.000" step="0.001" />
              </template>
              <!-- 体积 -->
              <template v-else-if="field.key === 'volume'">
                <input type="number" v-model.number="unit.volume" :disabled="!unit.enabled" placeholder="0.000000" step="0.000001" />
              </template>
              <!-- 销售/采购单位 -->
              <template v-else-if="field.key === 'isSaleUnit' || field.key === 'isPurchaseUnit'">
                <input type="checkbox" v-model="unit[field.key]" :disabled="!unit.enabled" />
              </template>
            </template>
          </td>
        </tr>

        <!-- 价格组行（只读，动态渲染） -->
        <tr v-for="pg in realPriceGroups" :key="'pg-' + pg.priceGroupCode" class="pg-row">
          <td class="attr-label pg-name">
            {{ pg.priceGroupName }}
          </td>
          <td v-for="colIdx in columnOrder" :key="'pgv-' + colIdx" class="input-cell">
            <input type="number"
                   :value="colIdx === 0 ? pg.smallPrice : colIdx === 2 ? pg.largePrice : pg.middlePrice"
                   disabled readonly
                   class="pg-readonly"
                   :title="`价格组「${pg.priceGroupName}」由调价单更新，此处不可编辑`" />
          </td>
        </tr>

        <!-- 销售/采购单位行 -->
        <tr v-for="field in [{key:'isSaleUnit',label:'销售单位'},{key:'isPurchaseUnit',label:'采购单位'}]" :key="field.key">
          <td class="attr-label">{{ field.label }}</td>
          <td v-for="colIdx in columnOrder" :key="colIdx" class="input-cell">
            <input type="checkbox" v-model="modelValue[colIdx][field.key]" :disabled="!modelValue[colIdx].enabled" />
          </td>
        </tr>
      </tbody>
    </table>

    <!-- 必填提示 + 快速调价按钮 -->
    <div class="bottom-bar">
      <span class="required-hint">
        <span class="required-mark">*</span> 表示启用该单位时为必填项
      </span>
      <div style="flex:1"></div>
      <button v-if="props.readonly && props.goodsCode" class="btn primary quick-adjust-btn"
              title="通过调价单调整标准售价、参考进价、最低价、建议零售价及价格组价格"
              @click="emit('quickAdjust')">
        快速调价
      </button>
      <span v-if="!props.readonly" class="hint-new">新建时可编辑所有价格；保存后如需调整，请通过调价单</span>
    </div>
  </div>
</template>

<style>
/* === 与原来完全一致的样式（只新增 .pg-row / .pg-readonly / .pg-name） === */
.multi-unit-matrix {
  width: 800px !important; min-width: 800px !important; max-width: 800px !important;
  margin: 0 auto;
}
.header-bar {
  display: flex; align-items: center; gap: 12px;
  padding: 10px 16px;
  background: #f9fafb; border-bottom: 1px solid #e5e7eb;
  margin-bottom: 12px; border-radius: 4px;
}
.link-checkbox {
  display: flex; align-items: center; gap: 6px;
  font-size: 12px; font-weight: 500; color: #606266; cursor: pointer;
}
.hint { font-size: 11px; color: #909399; }
.hint b { color: #e6a23c; }

.matrix-table {
  width: 800px !important; min-width: 800px !important; max-width: 800px !important;
  border-collapse: collapse; font-size: 12px; table-layout: fixed;
}
.matrix-table th {
  background: #f5f7fa; padding: 10px 8px; text-align: center;
  font-weight: 600; color: #303133; border: 1px solid #ebeef5;
  width: 200px !important; min-width: 200px !important; max-width: 200px !important;
}
.matrix-table td {
  border: 1px solid #ebeef5; padding: 6px 8px; position: relative;
  height: 36px; width: 200px !important; min-width: 200px !important; max-width: 200px !important;
}
.attr-label {
  background: #f5f7fa; font-weight: 600; color: #303133;
  text-align: center; border-right: 1px solid #ebeef5;
}
/* 价格组行背景微蓝，区别于固定行 */
.pg-row { background: #f0f5ff; }
.pg-row .attr-label { background: #e9eff7; color: #409eff; }
.pg-readonly {
  background: #f5f7fa !important;
  cursor: not-allowed !important;
  color: #606266 !important;
}

.input-cell { text-align: center; background: #fff; }
.input-cell input[type="text"],
.input-cell input[type="number"],
.input-cell select {
  width: 100%; height: 28px; padding: 0 6px;
  border: 1px solid #dcdfe6; border-radius: 3px;
  font-size: 11px; color: #606266; font-weight: 400;
  text-align: center; transition: all 0.2s;
  box-sizing: border-box; background: #fff; min-width: 0;
}
.input-cell input:focus, .input-cell select:focus {
  outline: none; border-color: #409eff; box-shadow: 0 0 0 2px rgba(64, 158, 255, 0.1);
}
.input-cell input:disabled, .input-cell select:disabled {
  background: #f5f7fa; cursor: not-allowed; color: #c0c4cc;
}
.input-cell input[type="checkbox"] { width: 16px; height: 16px; cursor: pointer; }
.input-cell input[type="checkbox"]:disabled { cursor: not-allowed; }
.unit-select { min-width: 80px; }
.badge {
  display: inline-block; padding: 1px 6px;
  background: #ecf5ff; color: #409eff; border-radius: 2px;
  font-size: 10px; margin-left: 4px; font-weight: normal;
}
.required-mark {
  color: #f56c6c; position: absolute; right: 2px; top: 50%;
  transform: translateY(-50%); font-size: 14px; font-weight: bold;
}
.required-hint { margin-top: 0; font-size: 11px; color: #909399; }

/* 编辑时价格只读样式 */
.price-input.readonly-price {
  background: #f5f7fa !important;
  color: #909399 !important;
  cursor: not-allowed !important;
}
.price-input.readonly-price:focus {
  border-color: #dcdfe6 !important;
  box-shadow: none !important;
}

/* 底部操作栏 */
.bottom-bar {
  display: flex; align-items: center; gap: 12px;
  margin-top: 12px;
}
.quick-adjust-btn {
  font-size: 12px; padding: 4px 14px;
}
.hint-new { font-size: 11px; color: #67c23a; }
</style>