<script setup>
/**
 * WMS V1.5 库位库存（V83 从 WmsZone.vue 拆出）
 * 展示 wms_bin_stock 实物位置账；金额/成本以「库存查询」为准（账实分离）。
 */
import { ref, onMounted } from 'vue'
import { post } from '../../api/client.js'

const rows = ref([])
const loading = ref(false)
const feedback = ref('')

function show(msg) { feedback.value = msg; setTimeout(() => (feedback.value = ''), 2500) }
async function load() {
  loading.value = true
  try { rows.value = (await post('/wms/bin-stock/page', { pageNo: 1, pageSize: 500, filters: {} })).records || [] }
  catch (e) { show('加载失败：' + (e?.message || e)) }
  finally { loading.value = false }
}
onMounted(load)
</script>

<template>
  <div class="module-body">
    <div class="page-ops">
      <b>库位库存</b>
      <span class="tip">库位库存为实物位置账；财务金额/成本以「库存查询」为准（账实分离）。</span>
      <div class="spacer"></div>
      <button class="btn" @click="load">刷新</button>
    </div>
    <div v-if="feedback" class="toast-inline err">{{ feedback }}</div>
    <div v-if="loading" class="tips-inline"><span>正在加载...</span></div>
    <div class="tablebox">
      <div class="toolbar"><b>库位库存（实物位置账）</b></div>
      <div class="scroll">
        <table class="data">
          <thead><tr><th>库位</th><th>商品</th><th>批次</th><th>仓库</th><th>容器</th><th class="num">数量</th><th class="num">预占</th></tr></thead>
          <tbody>
            <tr v-for="s in rows" :key="s.binStockId">
              <td class="bin">{{ s.binCode }}</td>
              <td>{{ s.goodsName }}<div class="sub">{{ s.goodsCode }}</div></td>
              <td>{{ s.batchNo || '—' }}</td><td>{{ s.warehouse }}</td><td>{{ s.containerCode || '—' }}</td>
              <td class="num">{{ s.qty }}</td><td class="num">{{ s.lockedQty }}</td>
            </tr>
            <tr v-if="!rows.length"><td colspan="7" class="empty">暂无库位库存（上架/入库后产生）</td></tr>
          </tbody>
        </table>
      </div>
    </div>
  </div>
</template>

<style scoped>
.tip { color: #874d00; background: #fffbe6; border: 1px solid #ffe58f; padding: 4px 10px; border-radius: 6px; font-size: 12px; margin-left: 10px; }
.spacer { flex: 1; }
.tablebox { background: #fff; border: 1px solid #f0f0f0; border-radius: 8px; overflow: hidden; margin-top: 10px; }
.toolbar { padding: 10px 12px; border-bottom: 1px solid #f0f0f0; background: #fafafa; }
.scroll { overflow: auto; max-height: calc(100vh - 220px); }
table.data { width: 100%; border-collapse: collapse; font-size: 13px; }
table.data th, table.data td { padding: 8px 10px; border-bottom: 1px solid #f0f0f0; text-align: left; }
table.data th { background: #fafafa; font-weight: 600; color: #555; }
.num { text-align: right; }
.bin { font-family: monospace; color: #1677ff; }
.sub { color: #aaa; font-size: 11px; }
.empty { text-align: center; color: #aaa; padding: 24px; }
.toast-inline.err { padding: 8px 12px; border-radius: 6px; margin: 10px 0; font-size: 13px; background: #fff1f0; border: 1px solid #ffa39e; color: #cf1322; }
</style>
