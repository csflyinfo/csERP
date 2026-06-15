<script setup>
import QueryBar from '../components/QueryBar.vue'
import ProTable from '../components/ProTable.vue'

const columns = [
  { key: 'adjustNo', title: '单号' },
  { key: 'customer', title: '客户编号/名称' },
  { key: 'goodsCode', title: '商品编号' },
  { key: 'goodsName', title: '名称' },
  { key: 'baseUnit', title: '基本单位' },
  { key: 'spec', title: '规格' },
  { key: 'barcode', title: '条码' },
  { key: 'originalPrice', title: '原价', num: true },
  { key: 'currentPrice', title: '现价', num: true },
  { key: 'latestPurchasePrice', title: '最新进价', num: true },
  { key: 'costPrice', title: '成本价', num: true },
  { key: 'effectiveStatus', title: '生效状态' },
  { key: 'action', title: '操作' },
]

const rows = [
  { adjustNo: 'CPA202606140001', customer: 'C001 华联超市', goodsCode: 'SP001', goodsName: '农夫山泉500ml*24', baseUnit: '瓶', spec: '500ml*24', barcode: '6941410749551', originalPrice: '35.00', currentPrice: '34.50', latestPurchasePrice: '31.20', costPrice: '30.80', effectiveStatus: '生效中', action: '停用' },
]
</script>

<template>
  <QueryBar :fields="['门店/客户', '商品', '生效状态', '单号', '有效日期']" />
  <div style="text-align:right;margin-bottom:8px">
    <button class="btn danger">停用所选价格</button>
    <button class="btn">导出</button>
  </div>
  <ProTable title="客户价格明细" :columns="columns" :rows="rows">
    <template #effectiveStatus="{ row }"><span class="badge ok">{{ row.effectiveStatus }}</span></template>
    <template #action="{ row }"><span class="link">{{ row.action }}</span></template>
  </ProTable>
</template>
