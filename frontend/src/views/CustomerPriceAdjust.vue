<script setup>
import QueryBar from '../components/QueryBar.vue'
import ProTable from '../components/ProTable.vue'

const emit = defineEmits(['create'])

const columns = [
  { key: 'adjustNo', title: '单号' },
  { key: 'customer', title: '客户' },
  { key: 'billDate', title: '单据日期' },
  { key: 'effectiveMode', title: '生效方式' },
  { key: 'validRange', title: '有效范围' },
  { key: 'detailCount', title: '明细数', num: true },
  { key: 'creatorInfo', title: '制单信息' },
  { key: 'auditInfo', title: '审核信息' },
  { key: 'status', title: '状态' },
]

const rows = [
  { adjustNo: 'CPA202606140001', customer: 'C001 华联超市', billDate: '2026-06-14', effectiveMode: '定时生效 2026-06-15 08:00', validRange: '2026-06-15 ~ 2026-12-31', detailCount: 2, creatorInfo: '管理员 10:20', auditInfo: '待审核', status: '待审核' },
]
</script>

<template>
  <QueryBar :fields="['单号', '客户', '单据日期', '生效方式', '审核状态']" />
  <div style="text-align:right;margin-bottom:8px">
    <button class="btn primary" @click="emit('create')">新建调整单</button>
    <button class="btn">批量导入生成调整单</button>
  </div>
  <ProTable title="客户价格调整单" :columns="columns" :rows="rows" />
</template>
