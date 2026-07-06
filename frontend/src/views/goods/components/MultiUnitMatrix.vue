<script setup>
import { ref, watch, computed, onMounted } from 'vue'
import { post } from '../../../api/client.js'

const props = defineProps({
  modelValue: { type: Array, required: true },
  isWeighted: { type: Boolean, default: false },
})

const emit = defineEmits(['update:modelValue'])

// 系统设置的价格小数位数（默认2位，实际应从系统配置获取）
const priceDecimalPlaces = ref(2)

// 是否启用价格联动
const priceLinked = ref(true)

// 价格组配置（模拟从系统获取，启用了几个就显示几个）
const priceGroups = ref([
  { code: 'PG1', name: '批发价', enabled: true },
  { code: 'PG2', name: '零售价', enabled: true },
  { code: 'PG3', name: '会员价', enabled: true },
  { code: 'PG4', name: '大客户价', enabled: false },
])

// 获取启用的价格组
const enabledPriceGroups = computed(() => priceGroups.value.filter(pg => pg.enabled))

// 列显示顺序：小单位(index=0) → 大单位(index=2) → 中单位(index=1)
const columnOrder = [0, 2, 1]

// 属性字段配置（按要求的顺序）
const attributeFields = [
  { key: 'enabled', label: '是否启用', type: 'checkbox' },
  { key: 'unitName', label: '单位', type: 'select', required: true },
  { key: 'convertQty', label: '换算数量', type: 'number', required: true, decimals: 0 },
  { key: 'barcode', label: '条码', type: 'text' },
  { key: 'standardPrice', label: '标准售价', type: 'price', required: true },
  { key: 'minPrice', label: '最低价', type: 'price' },
  { key: 'suggestRetailPrice', label: '建议零售价', type: 'price' },
]

// 动态添加价格组字段
enabledPriceGroups.value.forEach(pg => {
  attributeFields.push({
    key: `priceGroup_${pg.code}`,
    label: pg.name,
    type: 'price',
    priceGroupCode: pg.code,
  })
})

// 添加剩余的属性字段
attributeFields.push(
  { key: 'weight', label: '重量(kg)', type: 'number', decimals: 3 },
  { key: 'volume', label: '体积(m³)', type: 'number', decimals: 6 },
  { key: 'isSaleUnit', label: '销售单位', type: 'checkbox' },
  { key: 'isPurchaseUnit', label: '采购单位', type: 'checkbox' },
)

// 单位选项
// 单位选项：从后端加载真实数据
const unitOptions = ref([])
async function loadUnits() {
  try {
    const data = await post('/base/unit/page', { pageNo: 1, pageSize: 500, filters: {} })
    unitOptions.value = (data.records || []).map(r => r.unitName).filter(Boolean)
  } catch (e) {
    console.warn('加载单位数据失败', e)
  }
}
onMounted(loadUnits)

// 确保单位数据结构完整
function ensureUnitStructure(unit, index) {
  const defaults = {
    unitType: ['小单位', '中单位', '大单位'][index],
    unitName: '',
    barcode: '',
    convertQty: index === 0 ? 1 : (index === 1 ? 12 : 24),
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

// 处理启用状态变化 - 业务逻辑
function handleEnableChange(unitIndex, enabled) {
  // 小单位默认启用，不可修改
  if (unitIndex === 0) return

  const units = [...props.modelValue]

  // 启用中单位必须先启用大单位
  if (unitIndex === 1 && enabled && !units[2]?.enabled) {
    alert('启用中单位前，请先启用大单位')
    return
  }

  units[unitIndex].enabled = enabled

  // 启用单位时，如果启用了价格联动，则根据小单位价格自动计算
  if (enabled && priceLinked.value && units[0]?.standardPrice > 0) {
    const baseUnit = units[0]
    const ratio = units[unitIndex].convertQty / baseUnit.convertQty
    units[unitIndex].standardPrice = Number((baseUnit.standardPrice * ratio).toFixed(priceDecimalPlaces.value))
  }

  emit('update:modelValue', units)
}

// 处理价格变化 - 价格联动逻辑
function handlePriceChange(unitIndex, field, value) {
  if (!priceLinked.value) return

  const units = [...props.modelValue]
  units[unitIndex][field] = Number(value) || 0

  // 找到基准单位（已启用的最小单位）
  const baseUnitIndex = units.findIndex(u => u.enabled)
  if (baseUnitIndex === -1) return

  const baseUnit = units[baseUnitIndex]
  const baseRatio = baseUnit.convertQty

  // 根据基准单位价格和换算比例，自动计算其他单位价格
  units.forEach((unit, idx) => {
    if (idx !== baseUnitIndex && unit.enabled) {
      const ratio = unit.convertQty / baseRatio
      unit[field] = Number((baseUnit[field] * ratio).toFixed(priceDecimalPlaces.value))
    }
  })

  emit('update:modelValue', units)
}

// 处理换算数量变化 - 价格联动
function handleConvertQtyChange(unitIndex, value) {
  const units = [...props.modelValue]
  units[unitIndex].convertQty = Number(value) || 1

  // 如果启用了价格联动，重新计算所有价格
  if (priceLinked.value) {
    // 找到基准单位
    const baseUnitIndex = units.findIndex(u => u.enabled)
    if (baseUnitIndex !== -1) {
      const baseUnit = units[baseUnitIndex]
      const priceFields = ['standardPrice', 'minPrice', 'suggestRetailPrice']
      // 添加价格组字段
      enabledPriceGroups.value.forEach(pg => {
        priceFields.push(`priceGroup_${pg.code}`)
      })

      priceFields.forEach(field => {
        if (baseUnit[field] > 0) {
          units.forEach((unit, idx) => {
            if (idx !== baseUnitIndex && unit.enabled) {
              const ratio = unit.convertQty / baseUnit.convertQty
              unit[field] = Number((baseUnit[field] * ratio).toFixed(priceDecimalPlaces.value))
            }
          })
        }
      })
    }
  }

  emit('update:modelValue', units)
}

// 获取价格组字段值
function getPriceGroupValue(unit, pgCode) {
  return unit[`priceGroup_${pgCode}`] || 0
}

// 设置价格组字段值
function setPriceGroupValue(unitIndex, pgCode, value) {
  handlePriceChange(unitIndex, `priceGroup_${pgCode}`, value)
}
</script>

<template>
  <!-- 外层容器控制总宽度：4列 × 200px = 800px -->
  <div class="multi-unit-matrix" style="width: 800px; min-width: 800px; max-width: 800px;">
    <!-- 顶部操作栏 -->
    <div class="header-bar">
      <label class="link-checkbox">
        <input type="checkbox" v-model="priceLinked" />
        <span>启用价格联动</span>
      </label>
      <span class="hint">启用后，修改任意单位价格或换算数量，其他单位价格将自动按比例换算</span>
    </div>

    <!-- 多单位二维表 -->
    <table class="matrix-table">
      <thead>
        <tr>
          <th class="attr-col">属性</th>
          <!-- 列顺序：小单位(index=0) → 大单位(index=2) → 中单位(index=1) -->
          <th v-for="colIdx in columnOrder" :key="colIdx" class="unit-col">
            {{ modelValue[colIdx].unitType || ['小单位', '中单位', '大单位'][colIdx] }}
            <span v-if="colIdx === 0" class="badge">(基本单位)</span>
          </th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="field in attributeFields" :key="field.key">
          <td class="attr-label">{{ field.label }}</td>
          <!-- 列顺序：小单位(index=0) → 大单位(index=2) → 中单位(index=1) -->
          <td v-for="colIdx in columnOrder" :key="colIdx" class="input-cell">
            <template v-for="unit in [modelValue[colIdx]]" :key="'u' + colIdx">
              <!-- 是否启用 -->
              <template v-if="field.key === 'enabled'">
                <input
                  type="checkbox"
                  :checked="unit.enabled"
                  :disabled="colIdx === 0"
                  @change="handleEnableChange(colIdx, $event.target.checked)"
                />
              </template>

              <!-- 单位名称选择 -->
              <template v-else-if="field.key === 'unitName'">
                <select v-model="unit.unitName" :disabled="!unit.enabled" class="unit-select">
                  <option value="">请选择</option>
                  <option v-for="opt in unitOptions" :key="opt" :value="opt">{{ opt }}</option>
                </select>
                <span v-if="field.required && unit.enabled && !unit.unitName" class="required-mark">*</span>
              </template>

              <!-- 换算数量 -->
              <template v-else-if="field.key === 'convertQty'">
                <input
                  type="number"
                  v-model.number="unit.convertQty"
                  :disabled="!unit.enabled || colIdx === 0"
                  :placeholder="colIdx === 0 ? '1' : '0'"
                  step="1"
                  min="1"
                  @change="handleConvertQtyChange(colIdx, unit.convertQty)"
                />
                <span v-if="field.required && unit.enabled && colIdx !== 0 && (!unit.convertQty || unit.convertQty <= 0)" class="required-mark">*</span>
              </template>

              <!-- 条码 -->
              <template v-else-if="field.key === 'barcode'">
                <input type="text" v-model="unit.barcode" :disabled="!unit.enabled" placeholder="输入条码" />
              </template>

              <!-- 价格类型字段 -->
              <template v-else-if="field.type === 'price'">
                <input
                  type="number"
                  :value="field.priceGroupCode ? getPriceGroupValue(unit, field.priceGroupCode) : unit[field.key]"
                  :disabled="!unit.enabled"
                  placeholder="0.00"
                  :step="`0.${'0'.repeat(priceDecimalPlaces - 1)}1`"
                  @input="field.priceGroupCode
                    ? setPriceGroupValue(colIdx, field.priceGroupCode, $event.target.value)
                    : handlePriceChange(colIdx, field.key, $event.target.value)"
                />
                <span v-if="field.required && unit.enabled && (!unit[field.key] || unit[field.key] <= 0)" class="required-mark">*</span>
              </template>

              <!-- 重量 -->
              <template v-else-if="field.key === 'weight'">
                <input type="number" v-model.number="unit.weight" :disabled="!unit.enabled" placeholder="0.000" step="0.001" />
              </template>

              <!-- 体积 -->
              <template v-else-if="field.key === 'volume'">
                <input type="number" v-model.number="unit.volume" :disabled="!unit.enabled" placeholder="0.000000" step="0.000001" />
              </template>

              <!-- 销售单位 / 采购单位 -->
              <template v-else-if="field.key === 'isSaleUnit' || field.key === 'isPurchaseUnit'">
                <input type="checkbox" v-model="unit[field.key]" :disabled="!unit.enabled" />
              </template>

              <!-- 其他数字类型 -->
              <template v-else-if="field.type === 'number'">
                <input
                  type="number"
                  v-model.number="unit[field.key]"
                  :disabled="!unit.enabled"
                  placeholder="0"
                  :step="field.decimals === 0 ? 1 : `0.${'0'.repeat(field.decimals - 1)}1`"
                />
              </template>

              <!-- 禁用状态显示 -->
              <span v-if="!unit.enabled" class="disabled-overlay"></span>
            </template>
          </td>
        </tr>
      </tbody>
    </table>

    <!-- 必填提示 -->
    <div class="required-hint">
      <span class="required-mark">*</span> 表示启用该单位时为必填项
    </div>
  </div>
</template>

<style>
/* 移除scoped，确保样式强制生效 */
.multi-unit-matrix {
  width: 800px !important; /* 4列 × 200px = 800px */
  min-width: 800px !important;
  max-width: 800px !important;
  margin: 0 auto;
}

.header-bar {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 16px;
  background: #f9fafb;
  border-bottom: 1px solid #e5e7eb;
  margin-bottom: 12px;
  border-radius: 4px;
}

.link-checkbox {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  font-weight: 500;
  color: #606266;
  cursor: pointer;
}

.hint {
  font-size: 11px;
  color: #909399;
}

/* 矩阵表格 */
.matrix-table {
  width: 800px !important; /* 4列 × 200px = 800px */
  min-width: 800px !important;
  max-width: 800px !important;
  border-collapse: collapse;
  font-size: 12px;
  table-layout: fixed; /* 强制表格布局固定 */
}

.matrix-table th {
  background: #f5f7fa;
  padding: 10px 8px;
  text-align: center;
  font-weight: 600;
  color: #303133;
  border: 1px solid #ebeef5;
  width: 200px !important;
  min-width: 200px !important;
  max-width: 200px !important;
}

.matrix-table td {
  border: 1px solid #ebeef5;
  padding: 6px 8px;
  position: relative;
  height: 36px;
  width: 200px !important;
  min-width: 200px !important;
  max-width: 200px !important;
}

.matrix-table td {
  border: 1px solid #ebeef5;
  padding: 6px 8px;
  position: relative;
  height: 36px;
}

.attr-label {
  background: #f5f7fa;
  font-weight: 600; /* 加粗 */
  color: #303133; /* 颜色加深，从#606266改为#303133 */
  text-align: center;
  border-right: 1px solid #ebeef5;
}

.input-cell {
  text-align: center;
  background: #fff;
}

.input-cell input[type="text"],
.input-cell input[type="number"],
.input-cell select {
  width: 100%;
  height: 28px;
  padding: 0 6px;
  border: 1px solid #dcdfe6;
  border-radius: 3px;
  font-size: 11px;
  color: #606266; /* 输入值颜色稍浅，与字段名区分 */
  font-weight: 400;
  text-align: center;
  transition: all 0.2s;
  box-sizing: border-box;
  background: #fff;
  min-width: 0;
}

.input-cell input:focus,
.input-cell select:focus {
  outline: none;
  border-color: #409eff;
  box-shadow: 0 0 0 2px rgba(64, 158, 255, 0.1);
}

.input-cell input:disabled,
.input-cell select:disabled {
  background: #f5f7fa;
  cursor: not-allowed;
  color: #c0c4cc;
}

.input-cell input[type="checkbox"] {
  width: 16px;
  height: 16px;
  cursor: pointer;
}

.input-cell input[type="checkbox"]:disabled {
  cursor: not-allowed;
}

.unit-select {
  min-width: 80px;
}

.badge {
  display: inline-block;
  padding: 1px 6px;
  background: #ecf5ff;
  color: #409eff;
  border-radius: 2px;
  font-size: 10px;
  margin-left: 4px;
  font-weight: normal;
}

.required-mark {
  color: #f56c6c;
  position: absolute;
  right: 2px;
  top: 50%;
  transform: translateY(-50%);
  font-size: 14px;
  font-weight: bold;
}

.disabled-overlay {
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background: rgba(245, 247, 250, 0.5);
  pointer-events: none;
}

.required-hint {
  margin-top: 12px;
  font-size: 11px;
  color: #909399;
  text-align: right;
}
</style>
