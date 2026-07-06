<script setup>
import { computed, ref, onMounted } from 'vue'
import { post } from '../../../api/client.js'

const props = defineProps({
  modelValue: { type: Object, required: true },
})

// 从后端加载真实下拉数据
const supplierOptions = ref([])

async function loadOptions() {
  const params = { pageNo: 1, pageSize: 500, filters: {} }
  try {
    const sup = await post('/base/supplier/page', params).catch(() => ({ records: [] }))
    supplierOptions.value = (sup.records || []).map(r => r.supplierName).filter(Boolean)
  } catch (e) {
    console.warn('加载下拉数据失败', e)
  }
}

onMounted(loadOptions)

// 计算小数位步长 - 称重品3位小数，非称重品整数
const qtyStep = computed(() => props.modelValue.isWeighted ? 0.001 : 1)

// 库存上下限步长
const stockLimitStep = computed(() => props.modelValue.isWeighted ? 0.001 : 1)
</script>

<template>
  <div class="stock-purchase-form">
    <!-- 第一行 -->
    <div class="row">
      <div class="field">
        <label>默认供应商</label>
        <select v-model="modelValue.defaultSupplier">
          <option value="">{{ supplierOptions.length ? '请选择' : '请先在【供应商资料】维护' }}</option>
          <option v-for="opt in supplierOptions" :key="opt" :value="opt">{{ opt }}</option>
        </select>
      </div>
      <div class="field">
        <label>存储属性</label>
        <select v-model="modelValue.storageProperty">
          <option value="常温">常温</option>
          <option value="冷藏">冷藏</option>
          <option value="冷冻">冷冻</option>
          <option value="恒温">恒温</option>
        </select>
      </div>
      <div class="field">
        <label>产地</label>
        <input type="text" v-model="modelValue.origin" placeholder="如: 杭州" />
      </div>
      <div class="field">
        <label>保质期(天)</label>
        <input type="number" v-model.number="modelValue.shelfLifeDays" placeholder="0" step="1" />
      </div>
    </div>

    <!-- 第二行 -->
    <div class="row">
      <div class="field">
        <label>临期预警天数</label>
        <input type="number" v-model.number="modelValue.warningDays" placeholder="0" step="1" />
      </div>
      <div class="field">
        <label>库存上限</label>
        <input
          type="number"
          v-model.number="modelValue.stockUpperLimit"
          :placeholder="modelValue.isWeighted ? '0.000' : '0'"
          :step="stockLimitStep"
        />
      </div>
      <div class="field">
        <label>库存下限</label>
        <input
          type="number"
          v-model.number="modelValue.stockLowerLimit"
          :placeholder="modelValue.isWeighted ? '0.000' : '0'"
          :step="stockLimitStep"
        />
      </div>
      <div class="field">
        <label>采购起订量</label>
        <input
          type="number"
          v-model.number="modelValue.minOrderQty"
          :placeholder="modelValue.isWeighted ? '0.000' : '0'"
          :step="qtyStep"
        />
      </div>
    </div>

    <!-- 第三行 -->
    <div class="row">
      <div class="field">
        <label>单托盘大单位数量</label>
        <input type="number" v-model.number="modelValue.palletQty" placeholder="0" step="1" />
      </div>
      <div class="field">
        <label>堆码层数</label>
        <input type="number" v-model.number="modelValue.stackLayers" placeholder="0" step="1" />
      </div>
    </div>

    <div class="hint-row">
      <span v-if="modelValue.isWeighted" class="hint">称重商品：库存上下限、采购起订量支持最多3位小数</span>
      <span v-else class="hint">非称重商品：库存上下限、采购起订量仅支持整数</span>
    </div>
  </div>
</template>

<style scoped>
.stock-purchase-form {
  max-width: 100%;
  margin: 0 auto;
}

.row {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 12px 16px;
  margin-bottom: 12px;
}

.field {
  display: flex;
  flex-direction: row;
  align-items: center;
  gap: 8px;
}

.field label {
  font-size: 12px;
  font-weight: 600;
  color: #303133;
  min-width: 90px;
  text-align: right;
  flex-shrink: 0;
  line-height: 32px;
}

.field input,
.field select {
  flex: 1;
  height: 32px;
  padding: 0 10px;
  border: 1px solid #dcdfe6;
  border-radius: 4px;
  font-size: 12px;
  color: #606266;
  font-weight: 400;
  transition: all 0.2s;
  box-sizing: border-box;
  min-width: 0;
  outline: none;
  background: #fff;
}

.field input:focus,
.field select:focus {
  border-color: #409eff;
  box-shadow: 0 0 0 2px rgba(64, 158, 255, 0.1);
}

.field select {
  cursor: pointer;
}

.hint-row {
  margin-top: 8px;
  padding: 8px 12px;
  background: #f0f9ff;
  border-radius: 4px;
  border-left: 3px solid #409eff;
}

.hint {
  font-size: 11px;
  color: #409eff;
}
</style>
