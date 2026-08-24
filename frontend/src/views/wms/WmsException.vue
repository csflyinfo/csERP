<script setup>
/**
 * WMS V1.5 异常中心（V83 从 WmsZone.vue 拆出）
 */
import { ref, onMounted } from 'vue'
import { post } from '../../api/client.js'

const rows = ref([])
const loading = ref(false)
const feedback = ref('')
const exTypeText = { SHORT: '缺货', DIFF: '差异', DAMAGE: '破损', OTHER: '其他' }

function show(msg) { feedback.value = msg; setTimeout(() => (feedback.value = ''), 2500) }
async function load() {
  loading.value = true
  try { rows.value = (await post('/wms/exception/page', { pageNo: 1, pageSize: 500, filters: {} })).records || [] }
  catch (e) { show('加载失败：' + (e?.message || e)) }
  finally { loading.value = false }
}
onMounted(load)
</script>

<template>
  <div class="module-body">
    <div class="page-ops">
      <b>WMS 异常中心</b>
      <span class="tip">集中展示拣货/复核/装车环节上报的缺货、差异、破损等异常。</span>
      <div class="spacer"></div>
      <button class="btn" @click="load">刷新</button>
    </div>
    <div v-if="feedback" class="toast-inline err">{{ feedback }}</div>
    <div v-if="loading" class="tips-inline"><span>正在加载...</span></div>
    <div class="tablebox">
      <div class="scroll">
        <table class="data">
          <thead><tr><th>异常单号</th><th>波次</th><th>订单</th><th>商品</th><th>类型</th><th class="num">数量</th><th>状态</th><th>描述</th><th>上报人</th></tr></thead>
          <tbody>
            <tr v-for="e in rows" :key="e.exceptionId">
              <td>{{ e.exceptionNo }}</td><td>{{ e.waveNo || '—' }}</td><td>{{ e.sourceOrderNo || '—' }}</td>
              <td>{{ e.goodsCode || '—' }}</td><td>{{ exTypeText[e.exceptionType] || e.exceptionType }}</td>
              <td class="num">{{ e.qty }}</td>
              <td><span class="tag" :class="e.status==='RESOLVED'?'ok':'warn'">{{ e.status }}</span></td>
              <td>{{ e.description }}</td><td>{{ e.reporter }}</td>
            </tr>
            <tr v-if="!rows.length"><td colspan="9" class="empty">暂无异常</td></tr>
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
.scroll { overflow: auto; max-height: calc(100vh - 220px); }
table.data { width: 100%; border-collapse: collapse; font-size: 13px; }
table.data th, table.data td { padding: 8px 10px; border-bottom: 1px solid #f0f0f0; text-align: left; }
table.data th { background: #fafafa; font-weight: 600; color: #555; }
.num { text-align: right; }
.tag { display: inline-block; padding: 1px 7px; border-radius: 10px; font-size: 11px; background: #f0f0f0; }
.tag.ok { background: #f6ffed; color: #389e0d; }
.tag.warn { background: #fff1f0; color: #cf1322; }
.empty { text-align: center; color: #aaa; padding: 24px; }
.toast-inline.err { padding: 8px 12px; border-radius: 6px; margin: 10px 0; font-size: 13px; background: #fff1f0; border: 1px solid #ffa39e; color: #cf1322; }
</style>
