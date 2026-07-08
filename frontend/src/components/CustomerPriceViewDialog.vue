<script setup>
/**
 * 客户价格调整单 —— 详情查看窗口（只读）
 *
 * 双击列表记录弹出。展示单据头（客户/生效方式/有效期/制单/审核信息）
 * 与三级单位明细，字段口径与编辑页保持一致。
 */
import { ref, watch } from 'vue'
import { get } from '../api/client.js'

const props = defineProps({
  visible: { type: Boolean, default: false },
  /** 要查看的调整单 ID */
  adjustId: { type: String, default: '' },
})

const emit = defineEmits(['close'])

const loading = ref(false)
const error = ref('')
const head = ref(null)
const details = ref([])

async function loadDetail() {
  if (!props.adjustId) return
  loading.value = true
  error.value = ''
  head.value = null
  details.value = []
  try {
    const data = await get(`/base/customer-price-adjust/detail?adjustId=${encodeURIComponent(props.adjustId)}`)
    head.value = data || null
    details.value = data?.details || []
  } catch (e) {
    error.value = e?.message || '加载失败'
  } finally {
    loading.value = false
  }
}

watch(() => props.visible, (v) => { if (v) loadDetail() })

/** 价格显示：未启用单位或空值统一显示「—」 */
function fmtPrice(v, enabled = true) {
  if (!enabled || v == null || v === '') return '—'
  return Number(v).toFixed(2)
}

/** 中/大单位启用状态：后端返回布尔或 0/1，统一归一 */
function isEnabled(v) {
  return v === true || v === 1 || v === '1' || v === 'TRUE' || v === 'true'
}

function close() { emit('close') }
</script>

<template>
  <div v-if="visible" class="cpv-overlay" @click.self="close">
    <div class="cpv-box">
      <div class="cpv-head">
        <b>
          客户价格调整单详情
          <span v-if="head?.adjustNo" class="cpv-no">{{ head.adjustNo }}</span>
          <span v-if="head?.statusText" class="badge" :class="head.statusText === '已审核' ? 'ok' : 'wait'">
            {{ head.statusText }}
          </span>
        </b>
        <div class="cpv-actions">
          <button class="btn" @click="close">关闭</button>
        </div>
      </div>

      <div class="cpv-body">
        <div v-if="loading" class="cpv-tip">加载中…</div>
        <div v-else-if="error" class="cpv-tip cpv-error">{{ error }}</div>
        <template v-else-if="head">
          <!-- 单据头 -->
          <div class="cpv-grid">
            <div class="cpv-field"><label>调整单号</label><span>{{ head.adjustNo || '-' }}</span></div>
            <div class="cpv-field"><label>客户</label><span>{{ head.customer || '-' }}</span></div>
            <div class="cpv-field"><label>单据日期</label><span>{{ head.billDateText || '-' }}</span></div>
            <div class="cpv-field"><label>生效方式</label><span>{{ head.effectiveMode === 'IMMEDIATE' ? '立即生效' : '定时生效' }}</span></div>
            <div class="cpv-field"><label>有效期</label><span>{{ head.validRangeText || '长期有效' }}</span></div>
            <div class="cpv-field"><label>商品数</label><span>{{ details.length }}</span></div>
            <div class="cpv-field"><label>制单人</label><span>{{ head.creatorNameText || '-' }}</span></div>
            <div class="cpv-field"><label>制单时间</label><span>{{ head.createTimeText || '-' }}</span></div>
            <div class="cpv-field"><label>审核人</label><span>{{ head.auditorNameText || '-' }}</span></div>
            <div class="cpv-field"><label>审核时间</label><span>{{ head.auditTimeText || '-' }}</span></div>
            <div class="cpv-field cpv-full"><label>备注</label><span>{{ head.remark || '-' }}</span></div>
          </div>

          <!-- 明细 -->
          <h4 class="cpv-sub">调价商品明细（{{ details.length }}）</h4>
          <div class="cpv-scroll">
            <table>
              <thead>
                <tr>
                  <th style="width:40px">#</th>
                  <th>商品编号</th>
                  <th>商品名称</th>
                  <th>规格</th>
                  <th>小单位</th>
                  <th class="num">小单位标价</th>
                  <th class="num">小单位现价</th>
                  <th>大单位</th>
                  <th class="num">大单位标价</th>
                  <th class="num">大单位现价</th>
                  <th>中单位</th>
                  <th class="num">中单位标价</th>
                  <th class="num">中单位现价</th>
                  <th>品牌</th>
                  <th>商品分类</th>
                  <th>存储属性</th>
                </tr>
              </thead>
              <tbody>
                <tr v-if="details.length === 0">
                  <td colspan="16" class="cpv-tip">无明细</td>
                </tr>
                <tr v-for="(row, i) in details" :key="i">
                  <td>{{ i + 1 }}</td>
                  <td>{{ row.goodsCode }}</td>
                  <td>{{ row.goodsName }}</td>
                  <td>{{ row.spec || '-' }}</td>

                  <td>{{ row.smallUnit || row.baseUnit || '-' }}</td>
                  <td class="num">{{ fmtPrice(row.smallStandardPrice) }}</td>
                  <td class="num strong">{{ fmtPrice(row.smallCurrentPrice) }}</td>

                  <td :class="{ off: !isEnabled(row.largeUnitEnabled) }">{{ row.largeUnit || '—' }}</td>
                  <td class="num" :class="{ off: !isEnabled(row.largeUnitEnabled) }">{{ fmtPrice(row.largeStandardPrice, isEnabled(row.largeUnitEnabled)) }}</td>
                  <td class="num strong" :class="{ off: !isEnabled(row.largeUnitEnabled) }">{{ fmtPrice(row.largeCurrentPrice, isEnabled(row.largeUnitEnabled)) }}</td>

                  <td :class="{ off: !isEnabled(row.mediumUnitEnabled) }">{{ row.mediumUnit || '—' }}</td>
                  <td class="num" :class="{ off: !isEnabled(row.mediumUnitEnabled) }">{{ fmtPrice(row.mediumStandardPrice, isEnabled(row.mediumUnitEnabled)) }}</td>
                  <td class="num strong" :class="{ off: !isEnabled(row.mediumUnitEnabled) }">{{ fmtPrice(row.mediumCurrentPrice, isEnabled(row.mediumUnitEnabled)) }}</td>

                  <td>{{ row.brandName || '-' }}</td>
                  <td>{{ row.categoryName || '-' }}</td>
                  <td>{{ row.storageProperty || '-' }}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </template>
      </div>
    </div>
  </div>
</template>

<style scoped>
.cpv-overlay {
  position: fixed; inset: 0; z-index: 2000;
  background: rgba(0,0,0,.45);
  display: flex; align-items: center; justify-content: center;
}
.cpv-box {
  background: #fff; border-radius: 8px;
  width: min(1200px, 96vw); max-height: 90vh;
  display: flex; flex-direction: column;
  box-shadow: 0 8px 32px rgba(0,0,0,.18);
}
.cpv-head {
  display: flex; align-items: center; padding: 14px 20px;
  border-bottom: 1px solid #e5e7eb;
}
.cpv-head b { font-size: 15px; color: #303133; display: flex; align-items: center; gap: 8px; }
.cpv-no { font-weight: 500; font-size: 12px; color: #606266; }
.cpv-actions { margin-left: auto; }
.cpv-body { flex: 1; overflow: auto; min-height: 0; padding: 14px 20px; }

.cpv-grid {
  display: grid; grid-template-columns: repeat(4, 1fr);
  gap: 8px 16px; margin-bottom: 6px;
}
.cpv-field { display: flex; align-items: baseline; gap: 8px; font-size: 12px; }
.cpv-field label { color: #909399; min-width: 60px; text-align: right; flex-shrink: 0; }
.cpv-field span { color: #303133; }
.cpv-field.cpv-full { grid-column: 1 / -1; }

.cpv-sub { margin: 16px 0 8px; font-size: 13px; color: #303133; }
.cpv-scroll { overflow-x: auto; }
.cpv-scroll table { width: 100%; border-collapse: collapse; font-size: 12px; }
.cpv-scroll th {
  background: #f5f7fa; padding: 8px 6px; text-align: left; font-weight: 600;
  color: #303133; border-bottom: 1px solid #e5e7eb; white-space: nowrap;
}
.cpv-scroll td { padding: 5px 6px; border-bottom: 1px solid #f0f0f0; white-space: nowrap; }
.cpv-scroll .num { text-align: right; font-variant-numeric: tabular-nums; }
.cpv-scroll .strong { font-weight: 600; }
.cpv-scroll .off { color: #c0c4cc; background: #f8fafc; }

.cpv-tip { text-align: center; color: #909399; padding: 30px; }
.cpv-error { color: #dc2626; }
</style>