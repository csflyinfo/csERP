<script setup>
/**
 * WMS 库存查询：实物库存（wms_bin_stock），可选只看账实差异。
 */
import { ref, onMounted } from 'vue'
import { post } from '../../api/client.js'

const loading = ref(false)
const list = ref([])
const filters = ref({ warehouse: '总仓', keyword: '', discrepancyOnly: false })
const feedback = ref('')
function show(m) { feedback.value = m; setTimeout(() => (feedback.value = ''), 3000) }

async function load() {
  loading.value = true
  try {
    list.value = (await post('/wms/internal/stock-query', {
      pageNo: 1, pageSize: 500, filters: { ...filters.value }
    })).records || []
  } catch (e) { show(e?.message || '加载失败') }
  finally { loading.value = false }
}
onMounted(load)
</script>

<template>
  <div class="module-body">
    <div class="page-ops">
      <input v-model="filters.keyword" placeholder="商品编码/名称" />
      <label class="chk"><input type="checkbox" v-model="filters.discrepancyOnly" /> 只看账实差异</label>
      <button class="btn" @click="load">查询</button>
    </div>
    <div v-if="feedback" class="toast-inline">{{ feedback }}</div>
    <div class="tablebox">
      <table class="data">
        <thead><tr><th>库位</th><th>商品</th><th>批次</th><th class="num">本库位数量</th><th class="num">预占</th><th class="num">同品总实物</th></tr></thead>
        <tbody>
          <tr v-for="(r,i) in list" :key="i">
            <td class="bin">{{ r.binCode }}</td>
            <td>{{ r.goodsName }}<div class="sub">{{ r.goodsCode }}</div></td>
            <td>{{ r.batchNo || '—' }}</td>
            <td class="num">{{ r.binQty }}</td>
            <td class="num">{{ r.lockedQty }}</td>
            <td class="num">{{ r.physicalQty }}</td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>

<style scoped>
.page-ops { display: flex; gap: 8px; align-items: center; margin-bottom: 12px; }
.btn { height: 30px; padding: 0 14px; border: 1px solid #d9d9d9; background: #fff; border-radius: 4px; cursor: pointer; }
input { height: 30px; padding: 0 8px; border: 1px solid #d9d9d9; border-radius: 4px; }
.chk { display: inline-flex; align-items: center; gap: 4px; font-size: 13px; }
.tablebox { background: #fff; border: 1px solid #f0f0f0; border-radius: 6px; overflow: auto; max-height: calc(100vh - 200px); }
table.data { width: 100%; border-collapse: collapse; font-size: 13px; }
table.data th, table.data td { padding: 8px 10px; border-bottom: 1px solid #f0f0f0; text-align: left; }
table.data th { background: #fafafa; font-weight: 600; }
.num { text-align: right; }
.bin { font-family: monospace; color: #1677ff; }
.sub { color: #aaa; font-size: 11px; }
.toast-inline { background: #e6f4ff; border: 1px solid #91caff; padding: 6px 12px; border-radius: 4px; margin-bottom: 10px; }
</style>
