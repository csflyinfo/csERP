<script setup>
import { computed } from 'vue'

const props = defineProps({
  modelValue: { type: Object, required: true },
})

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
          <option value="">请选择默认供应商</option>
          <option value="可口可乐经销">可口可乐经销</option>
          <option value="百草味供应商">百草味供应商</option>
          <option value="统一企业">统一企业</option>
        </select>
      </div>
      <div class="field">
        <label>默认仓库</label>
        <select v-model="modelValue.defaultWarehouse">
          <option value="">请选择</option>
          <option value="总仓">总仓</option>
          <option value="东区仓">东区仓</option>
          <option value="西区仓">西区仓</option>
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
    </div>

    <!-- 第二行 -->
    <div class="row">
      <div class="field">
        <label>保质期(天)</label>
        <input type="number" v-model.number="modelValue.shelfLifeDays" placeholder="0" step="1" />
      </div>
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
    </div>

    <!-- 第三行 -->
    <div class="row">
      <div class="field">
        <label>采购起订量</label>
        <input
          type="number"
          v-model.number="modelValue.minOrderQty"
          :placeholder="modelValue.isWeighted ? '0.000' : '0'"
          :step="qtyStep"
        />
      </div>
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
  font-weight: 600; /* 字段名称加粗 */
  color: #303133;  /* 字段名称颜色加深 */
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
  color: #606266;  /* 输入值颜色稍浅，与字段名区分 */
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
