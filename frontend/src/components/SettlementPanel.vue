<script setup>
// 账期设置面板（按 docs/账期管理-产品说明.md V1.4 规范实现）
// 通过 v-model 双向绑定一个对象：
//   {
//     settlementType: 'PREPAY' | 'COD' | 'TERM',
//     termType:       'FIXED' | 'WEEKLY' | 'SEMI_MONTH' | 'MONTHLY',
//     termDays:       Number,   // 账期天数（固定/周结/半月结） or 月结A模式的天数
//     cutoffDay:      '1'..'31',
//     paymentMode:    'A' | 'B',
//     termMonths:     Number,
//     paymentDay:     '1'..'31',
//   }
import { computed } from 'vue'

const props = defineProps({
  modelValue: { type: Object, required: true },
  readonly: { type: Boolean, default: false },
  // 用于「货到付款」文案区分：customer=销售出库/客户签收日；supplier=采购入库/入库签收日
  role: { type: String, default: 'customer' }, // 'customer' | 'supplier'
})
const emit = defineEmits(['update:modelValue'])

function patch(payload) {
  emit('update:modelValue', { ...props.modelValue, ...payload })
}

// 选择账期类型时联动：MONTHLY 默认付款模式 = A
function onTermTypeChange(v) {
  const next = { termType: v }
  if (v === 'MONTHLY' && !props.modelValue.paymentMode) {
    next.paymentMode = 'A'
  }
  patch(next)
}

const isTerm = computed(() => props.modelValue?.settlementType === 'TERM')
const isMonthly = computed(() => isTerm.value && props.modelValue?.termType === 'MONTHLY')
const isMonthlyB = computed(() => isMonthly.value && props.modelValue?.paymentMode === 'B')

// 摘要文案
const summary = computed(() => {
  const m = props.modelValue || {}
  if (m.settlementType === 'PREPAY') return '预付'
  if (m.settlementType === 'COD') return '货到付款'
  if (m.settlementType === 'TERM') {
    switch (m.termType) {
      case 'FIXED': return `账期 · 固定 ${m.termDays ?? 0} 天`
      case 'WEEKLY': return `账期 · 周结 ${m.termDays ?? 0} 天`
      case 'SEMI_MONTH': return `账期 · 半月结 ${m.termDays ?? 0} 天`
      case 'MONTHLY': {
        if (m.paymentMode === 'B') return `账期 · 月结 截账 ${m.cutoffDay || '?'} 日 + ${m.termMonths ?? 0} 月第 ${m.paymentDay || '?'} 日`
        return `账期 · 月结 截账 ${m.cutoffDay || '?'} 日 + ${m.termDays ?? 0} 天`
      }
      default: return '账期'
    }
  }
  return ''
})

// 规则说明文案（实时提示当前配置的计算规则）
const ruleHint = computed(() => {
  const m = props.modelValue || {}
  if (m.settlementType === 'PREPAY') return '结算日 = 订单审核日'
  if (m.settlementType === 'COD') {
    return props.role === 'supplier' ? '采购入库以入库签收日结算' : '销售出库以客户签收日结算'
  }
  if (m.settlementType === 'TERM') {
    switch (m.termType) {
      case 'FIXED': return `结算日 = 单据完成日 + ${m.termDays ?? 0} 天`
      case 'WEEKLY': return `按自然周聚合，结算日 = 本周一 + ${m.termDays ?? 0} 天`
      case 'SEMI_MONTH': return `1-15 日 / 16-月末 各一周期，结算日 = 周期结束次日 + ${m.termDays ?? 0} 天`
      case 'MONTHLY': {
        if (m.paymentMode === 'B') return `结算日 = 截账日 + ${m.termMonths ?? 0} 个月，再取该月第 ${m.paymentDay || '?'} 天（31 = 月末）`
        return `结算日 = 截账日 + ${m.termDays ?? 0} 天`
      }
      default: return '请选择账期类型'
    }
  }
  return ''
})
</script>

<template>
  <div class="settlement-panel">
    <!-- 结算方式 -->
    <div class="line">
      <label class="lbl">结算方式 <span class="req">*</span></label>
      <div class="radio-row">
        <label class="radio">
          <input type="radio" value="PREPAY" :checked="modelValue.settlementType === 'PREPAY'"
                 :disabled="readonly" @change="patch({ settlementType: 'PREPAY' })" />
          <span>预付</span>
        </label>
        <label class="radio">
          <input type="radio" value="COD" :checked="modelValue.settlementType === 'COD'"
                 :disabled="readonly" @change="patch({ settlementType: 'COD' })" />
          <span>货到付款</span>
        </label>
        <label class="radio">
          <input type="radio" value="TERM" :checked="modelValue.settlementType === 'TERM'"
                 :disabled="readonly" @change="patch({ settlementType: 'TERM' })" />
          <span>账期</span>
        </label>
      </div>
    </div>

    <!-- 账期配置 -->
    <template v-if="isTerm">
      <div class="line">
        <label class="lbl">账期类型 <span class="req">*</span></label>
        <select :value="modelValue.termType || ''" :disabled="readonly"
                @change="onTermTypeChange($event.target.value)">
          <option value="">请选择</option>
          <option value="FIXED">固定账期天数</option>
          <option value="WEEKLY">周结</option>
          <option value="SEMI_MONTH">半月结</option>
          <option value="MONTHLY">月结</option>
        </select>
      </div>

      <!-- 固定 / 周结 / 半月结：只需要账期天数 -->
      <div v-if="modelValue.termType && modelValue.termType !== 'MONTHLY'" class="line">
        <label class="lbl">账期天数 <span class="req">*</span></label>
        <input type="number" min="0" max="365" step="1"
               :value="modelValue.termDays ?? 0" :readonly="readonly"
               @input="patch({ termDays: Math.max(0, Math.min(365, parseInt($event.target.value) || 0)) })" />
        <span class="suffix">天（0-365）</span>
      </div>

      <!-- 月结：截账日 + 付款模式 -->
      <template v-if="isMonthly">
        <div class="line">
          <label class="lbl">截账日 <span class="req">*</span></label>
          <input type="number" min="1" max="31" step="1"
                 :value="modelValue.cutoffDay || ''" :readonly="readonly"
                 @input="patch({ cutoffDay: String(Math.max(1, Math.min(31, parseInt($event.target.value) || 1))) })"
                 style="width:80px" />
          <span class="suffix">日（1-31，31 表示月末）</span>
        </div>
        <div class="line">
          <label class="lbl">付款模式 <span class="req">*</span></label>
          <div class="radio-row">
            <label class="radio">
              <input type="radio" value="A" :checked="modelValue.paymentMode === 'A'"
                     :disabled="readonly" @change="patch({ paymentMode: 'A' })" />
              <span>A. 截账日后 N 天付款</span>
            </label>
            <label class="radio">
              <input type="radio" value="B" :checked="modelValue.paymentMode === 'B'"
                     :disabled="readonly" @change="patch({ paymentMode: 'B' })" />
              <span>B. 截账日后 N 个月第 M 天付款</span>
            </label>
          </div>
        </div>
        <!-- 模式 A -->
        <div v-if="modelValue.paymentMode === 'A'" class="line">
          <label class="lbl">账期天数 N <span class="req">*</span></label>
          <input type="number" min="0" max="365" step="1"
                 :value="modelValue.termDays ?? 0" :readonly="readonly"
                 @input="patch({ termDays: Math.max(0, Math.min(365, parseInt($event.target.value) || 0)) })" />
          <span class="suffix">天（0-365）</span>
        </div>
        <!-- 模式 B -->
        <template v-if="isMonthlyB">
          <div class="line">
            <label class="lbl">账期月数 N <span class="req">*</span></label>
            <input type="number" min="0" step="1"
                   :value="modelValue.termMonths ?? 0" :readonly="readonly"
                   @input="patch({ termMonths: Math.max(0, parseInt($event.target.value) || 0) })"
                   style="width:80px" />
            <span class="suffix">月</span>
          </div>
          <div class="line">
            <label class="lbl">付款日 M <span class="req">*</span></label>
            <input type="number" min="1" max="31" step="1"
                   :value="modelValue.paymentDay || ''" :readonly="readonly"
                   @input="patch({ paymentDay: String(Math.max(1, Math.min(31, parseInt($event.target.value) || 1))) })"
                   style="width:80px" />
            <span class="suffix">日（31 = 月末）</span>
          </div>
        </template>
      </template>
    </template>

    <!-- 规则说明 + 摘要 -->
    <div class="rule-hint">
      <div class="hint-line"><b>摘要：</b>{{ summary || '—' }}</div>
      <div class="hint-line"><b>规则：</b>{{ ruleHint || '—' }}</div>
    </div>
  </div>
</template>

<style scoped>
.settlement-panel {
  border: 1px solid var(--line);
  border-radius: 6px;
  padding: 12px 16px;
  background: #fafbfc;
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.line {
  display: flex;
  align-items: center;
  gap: 12px;
  font-size: 12px;
}
.lbl {
  min-width: 96px;
  font-weight: 600;
  color: #303133;
  text-align: right;
}
.radio-row {
  display: flex;
  gap: 16px;
  align-items: center;
  flex-wrap: wrap;
}
.radio {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  cursor: pointer;
}
.radio input {
  cursor: pointer;
  width: 13px;
  height: 13px;
  margin: 0;
  accent-color: var(--primary);
  flex-shrink: 0;
}
.radio input:disabled {
  cursor: not-allowed;
}
.radio span {
  font-size: 12px;
  color: #303133;
  line-height: 1;
}
.settlement-panel input[type=number],
.settlement-panel select {
  height: 28px;
  border: 1px solid var(--line);
  border-radius: 4px;
  padding: 0 8px;
  font-size: 12px;
  outline: none;
  width: 120px;
}
.settlement-panel select {
  width: 180px;
}
.suffix {
  color: #909399;
  font-size: 11px;
}
.req {
  color: #f56c6c;
}
.rule-hint {
  margin-top: 4px;
  padding: 8px 10px;
  background: #eaf4ff;
  border-left: 3px solid var(--primary);
  border-radius: 4px;
  color: #12385f;
  font-size: 12px;
}
.hint-line {
  line-height: 1.6;
}
.hint-line b {
  color: #303133;
}
</style>
