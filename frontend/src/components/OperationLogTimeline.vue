<script setup>
/**
 * 单据操作记录时间线（PRD-31）——所有业务单据共用。
 *
 * <p>右侧抽屉，自绘竖线时间轴（不依赖 Element Plus）。打开时按 {bizType,bizNo} 拉
 * POST /operation-log/bill-timeline，按时间正序展示该单据全部操作。
 * 每条节点：时间 + 操作人 + 动作徽标 + 结果，可展开看改前/改后对比（复用 OperationLogDiff）。
 * 敏感字段（成本/采购价等）对非管理员由后端脱敏为 ***。
 */
import { ref, watch } from 'vue'
import { post } from '../api/client.js'
import OperationLogDiff from './OperationLogDiff.vue'

const props = defineProps({
  visible: { type: Boolean, default: false },
  title: { type: String, default: '操作记录' },
  bizType: { type: String, default: '' },
  bizNo: { type: String, default: '' },
  bizId: { type: String, default: '' },
})
const emit = defineEmits(['close'])

const loading = ref(false)
const rows = ref([])
const expanded = ref(new Set())
const error = ref('')

watch(() => props.visible, (v) => {
  if (v) load()
  else { expanded.value = new Set(); error.value = '' }
})

async function load() {
  if (!props.bizType || !props.bizNo) { rows.value = []; return }
  loading.value = true
  error.value = ''
  try {
    const data = await post('/operation-log/bill-timeline', {
      bizType: props.bizType, bizNo: props.bizNo, bizId: props.bizId || undefined,
    })
    rows.value = Array.isArray(data) ? data : []
  } catch (e) {
    error.value = e.message || '操作记录加载失败'
    rows.value = []
  } finally {
    loading.value = false
  }
}

function toggle(logId) {
  const s = new Set(expanded.value)
  if (s.has(logId)) s.delete(logId); else s.add(logId)
  expanded.value = s
}
function hasDiff(row) {
  const a = row.afterValue
  return a && (Array.isArray(a.main) || Array.isArray(a.lines))
}
function fmtTime(t) {
  if (!t) return ''
  return String(t).replace('T', ' ').slice(0, 19)
}
function costText(ms) {
  if (ms == null || ms === '') return ''
  const n = Number(ms)
  if (Number.isNaN(n)) return ''
  return n >= 1000 ? `${(n / 1000).toFixed(2)}s` : `${n}ms`
}
function closeDrawer() { emit('close') }
</script>

<template>
  <div v-show="visible" class="olt-mask" @click.self="closeDrawer">
    <div class="olt-box">
      <div class="olt-head">
        <b>{{ title }}</b>
        <span v-if="bizNo" class="olt-bizno">{{ bizNo }}</span>
        <div style="flex:1"></div>
        <button class="btn" @click="closeDrawer">关闭</button>
      </div>

      <div class="olt-body">
        <div v-if="loading" class="olt-state">加载中…</div>
        <div v-else-if="error" class="olt-state olt-err">{{ error }}</div>
        <div v-else-if="rows.length === 0" class="olt-state">该单据暂无操作记录</div>

        <ul v-else class="olt-timeline">
          <li v-for="row in rows" :key="row.logId" class="olt-item">
            <span class="olt-dot" :class="row.result === 'FAIL' ? 'dot-fail' : 'dot-ok'"></span>
            <div class="olt-card">
              <div class="olt-card-head">
                <span class="olt-action" :class="{ 'act-fail': row.result === 'FAIL' }">{{ row.actionName || row.action }}</span>
                <span v-if="row.sensitive === 'Y'" class="olt-sensitive">敏感</span>
                <span class="olt-operator">{{ row.operatorName || row.operatorAccount || '系统' }}</span>
                <span class="olt-time">{{ fmtTime(row.operateAt) }}</span>
                <div style="flex:1"></div>
                <button v-if="hasDiff(row)" class="link link-btn olt-toggle" @click="toggle(row.logId)">
                  {{ expanded.has(row.logId) ? '收起' : '改前改后' }}
                </button>
              </div>

              <div v-if="row.operationContent" class="olt-content">{{ row.operationContent }}</div>
              <div v-else-if="row.detail" class="olt-content">{{ row.detail }}</div>

              <div class="olt-meta">
                <span v-if="row.moduleName">{{ row.moduleName }}</span>
                <span v-if="costText(row.costTimeMs)">耗时 {{ costText(row.costTimeMs) }}</span>
                <span v-if="row.requestIp">IP {{ row.requestIp }}</span>
                <span v-if="row.result === 'FAIL'" class="olt-fail-reason">失败：{{ row.failReason || '—' }}</span>
              </div>

              <div v-if="expanded.has(row.logId) && hasDiff(row)" class="olt-diff">
                <OperationLogDiff
                  :after="row.afterValue"
                  :before="row.beforeValue"
                  :content="''"
                  :detail="''"
                />
              </div>
            </div>
          </li>
        </ul>
      </div>
    </div>
  </div>
</template>

<style scoped>
.olt-mask {
  position: fixed; top: 48px; right: 0; bottom: 0; left: 299px;
  z-index: 900; display: flex; justify-content: flex-end; pointer-events: auto;
  background: rgba(15, 46, 88, 0.08); animation: fadeIn 0.2s ease;
}
.olt-box {
  /* 右侧小抽屉：约 1/4 屏宽（点击遮罩空白处关闭，靠 @click.self 触发） */
  flex: none; width: 25vw; min-width: 320px; max-width: 480px;
  background: #fff; display: flex; flex-direction: column; min-height: 0;
  border-left: 1px solid var(--line, #dcdfe6);
  box-shadow: -6px 0 24px rgba(15, 46, 88, 0.12);
  animation: slideIn 0.25s ease;
}
.olt-head {
  display: flex; align-items: center; gap: 10px; height: 46px; padding: 0 16px;
  border-bottom: 1px solid var(--line-soft, #ebeef5);
}
.olt-head b { font-size: 15px; }
.olt-bizno { color: #5d7896; font-size: 13px; }
.olt-body { flex: 1; overflow: auto; padding: 12px; background: #f5f7fa; }
.olt-state {
  padding: 48px; text-align: center; color: #909399; font-size: 13px;
  background: #fafbfc; border: 1px dashed var(--line, #dcdfe6); border-radius: 6px;
}
.olt-err { color: #d04848; }

.olt-timeline { list-style: none; margin: 0; padding: 0 0 0 8px; }
.olt-item { position: relative; padding: 0 0 16px 22px; }
.olt-item::before {
  content: ''; position: absolute; left: 5px; top: 12px; bottom: -2px;
  width: 2px; background: #e0e6ef;
}
.olt-item:last-child::before { display: none; }
.olt-dot {
  position: absolute; left: 0; top: 4px; width: 12px; height: 12px;
  border-radius: 50%; border: 2px solid #fff; box-shadow: 0 0 0 2px #d7deea;
}
.dot-ok { background: #3d8b5f; }
.dot-fail { background: #d04848; }

.olt-card {
  background: #fff; border: 1px solid var(--line-soft, #ebeef5);
  border-radius: 6px; padding: 10px 12px;
}
.olt-card-head { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.olt-action {
  display: inline-block; padding: 2px 10px; border-radius: 4px; font-size: 12px;
  font-weight: 700; background: #eaf2ff; color: #2b6cb0;
}
.olt-action.act-fail { background: #fde8e8; color: #d04848; }
.olt-sensitive {
  font-size: 11px; color: #b8860b; background: #fdf3e0; border-radius: 3px; padding: 1px 6px;
}
.olt-operator { font-weight: 600; color: #303133; font-size: 12px; }
.olt-time { color: #909399; font-size: 12px; }
.olt-toggle { margin-left: auto; font-size: 12px; }
.olt-content {
  margin-top: 6px; font-size: 12px; color: #4a5568; line-height: 1.6;
  background: #f8fafc; border-radius: 4px; padding: 6px 8px; word-break: break-all;
}
.olt-meta {
  margin-top: 6px; display: flex; gap: 14px; flex-wrap: wrap;
  color: #a0a4aa; font-size: 11px;
}
.olt-fail-reason { color: #d04848; }
.olt-diff { margin-top: 8px; border-top: 1px dashed var(--line, #dcdfe6); padding-top: 8px; }

@keyframes fadeIn { from { opacity: 0; } to { opacity: 1; } }
@keyframes slideIn { from { transform: translateX(60px); opacity: 0.6; } to { transform: translateX(0); opacity: 1; } }
@media (max-width: 900px) { .olt-mask { left: 0; } }
</style>
