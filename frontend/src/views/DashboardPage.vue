<script setup>
defineProps({
  dashboardCards: { type: Array, required: true },
  dashboardLoading: { type: Boolean, default: false },
  dashboardError: { type: String, default: '' },
  recentLogs: { type: Array, default: () => [] },
  flowResult: { type: Object, default: null },
})

const emit = defineEmits(['refresh-summary', 'refresh-logs', 'run-core-flow'])
</script>

<template>
  <section>
    <div class="page-ops">
      <button class="btn" @click="emit('refresh-summary')">刷新经营指标</button>
      <button class="btn primary" @click="emit('run-core-flow')">核心闭环自测</button>
      <span v-if="dashboardLoading" class="muted">正在加载经营概览...</span>
      <span v-else-if="dashboardError" class="muted">{{ dashboardError }}</span>
    </div>
    <div class="cards">
      <div v-for="item in dashboardCards" :key="item.label" class="card">
        <div>{{ item.label }}</div>
        <div class="value">{{ item.value }}</div>
      </div>
    </div>
    <div class="tablebox" style="margin-bottom:8px">
      <div class="toolbar"><b>最近操作动态</b><div class="spacer"></div><button class="btn" @click="emit('refresh-logs')">刷新动态</button></div>
      <div style="padding:12px;display:grid;grid-template-columns:repeat(3,1fr);gap:8px">
        <div v-for="log in recentLogs" :key="log.bizNo + log.action + log.operateAt" class="card">
          <b>{{ log.action }} · {{ log.moduleCode }}</b>
          <p>{{ log.detail || log.bizNo }}</p>
          <span class="muted">{{ log.operatorName }} {{ log.operateAt }}</span>
        </div>
        <div v-if="!recentLogs.length" class="card"><b>暂无操作动态</b><p>导入、导出、系统配置等操作会显示在这里。</p></div>
      </div>
    </div>
    <div v-if="flowResult" class="tablebox">
      <div class="toolbar"><b>V1.0核心闭环自测结果</b></div>
      <div style="padding:12px;display:grid;grid-template-columns:repeat(5,1fr);gap:8px">
        <div class="card"><b>采购闭环</b><p>{{ flowResult.purchaseCycle.purchaseInbound.effect }}</p></div>
        <div class="card"><b>销售闭环</b><p>{{ flowResult.salesCycle.salesOutbound.effect }}</p></div>
        <div class="card"><b>应收核销</b><p>{{ flowResult.arReceipt.ar.status }}</p></div>
        <div class="card"><b>应付核销</b><p>{{ flowResult.apPayment.ap.status }}</p></div>
        <div class="card"><b>客户价格</b><p>{{ flowResult.customerPrice.effect }}</p></div>
      </div>
    </div>
  </section>
</template>
