<template>
  <!-- 销售域报表公共筛选（#10/#11/#14/#16 共用；键名与后端
       AbstractSalesSummaryDefinition.appendCommonDwsFilters 严格一致）。
       只改值不触发查询——查询统一由「查询」按钮触发（全局查询约定）。 -->
  <div class="ff"><label>客户</label><input v-model="filters.customer" placeholder="编号/名称" @keyup.enter="$emit('search')"></div>
  <div class="ff"><label>客户等级</label><input v-model="filters.customerLevel" @keyup.enter="$emit('search')"></div>
  <div class="ff"><label>业务员</label><input v-model="filters.salesman" @keyup.enter="$emit('search')"></div>
  <div class="ff"><label>区域</label><input v-model="filters.territory" @keyup.enter="$emit('search')"></div>
  <div class="ff"><label>路线</label><input v-model="filters.routeLine" @keyup.enter="$emit('search')"></div>
  <div class="ff">
    <label>仓库</label>
    <select v-model="filters.warehouse">
      <option value="">全部仓库</option>
      <option v-for="w in warehouses" :key="w" :value="w">{{ w }}</option>
    </select>
  </div>
  <div class="ff"><label>商品</label><input v-model="filters.goods" placeholder="编号/名称/条码" @keyup.enter="$emit('search')"></div>
  <div class="ff"><label>商品分类</label><input v-model="filters.categoryName" @keyup.enter="$emit('search')"></div>
  <div class="ff"><label>品牌</label><input v-model="filters.brandName" @keyup.enter="$emit('search')"></div>
  <div class="ff">
    <label>存储属性</label>
    <select v-model="filters.storageProperty">
      <option value="">全部</option>
      <option value="常温">常温</option>
      <option value="冷藏">冷藏</option>
      <option value="冷冻">冷冻</option>
      <option value="恒温">恒温</option>
    </select>
  </div>
</template>

<script setup>
/**
 * 销售报表公共筛选条片段。
 * @param filters useCenterReport 的 filters ref.value（响应式对象，直接改其键）
 * @param warehouses 仓库名称数组（loadWarehouses）
 */
defineProps({
  filters: { type: Object, required: true },
  warehouses: { type: Array, default: () => [] },
})
defineEmits(['search'])
</script>

<style scoped>
/* 片段根节点是多个 .ff（fragment），父页面 scoped 样式无法穿透到内部节点，
   必须在本组件内引入统一筛选条样式。 */
@import '../../views/report/report-page.css';
</style>
