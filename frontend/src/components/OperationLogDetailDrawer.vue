<script setup>
/**
 * 管理端「操作日志详情」抽屉（PRD-31，/system 仅 ADMIN）。
 *
 * <p>从日志列表点「详情」打开，GET /system/operation-log/detail/{logId} 拉全字段（含解析后的
 * before/after）。头部展示操作元信息，主体用 {@link OperationLogDiff} 渲染改前改后对比；
 * 无 diff（新增/删除/查看等）时回落展示 operation_content / detail 文本。
 */
import { ref, watch } from 'vue'
import { get } from '../api/client.js'
import OperationLogDiff from './OperationLogDiff.vue'

const props = defineProps({
  visible: { type: Boolean, default: false },
  logId: { type: String, default: '' },
})
const emit = defineEmits(['close'])

const loading = ref(false)
const row = ref(null)
const error = ref('')

watch(() => props.visible, (v) => {
  if (v) load()
  else { row.value = null; error.value = '' }
})

async function load() {
  if (!props.logId) return
  loading.value = true
  error.value = ''
  try {
    row.value = await get(`/system/operation-log/detail/${encodeURIComponent(props.logId)}`)
  } catch (e) {
    error.value = e.message || '详情加载失败'
    row.value = null
  } finally {
    loading.value = false
  }
}

function fmtTime(t) { return t ? String(t).replace('T', ' ').slice(0, 19) : '' }
function costText(ms) {
  if (ms == null || ms === '') return ''
  const n = Number(ms)
  if (Number.isNaN(n)) return ''
  return n >= 1000 ? `${(n / 1000).toFixed(2)}s` : `${n}ms`
}
function closeDrawer() { emit('close') }
</script>

<template>
  <div v-show="visible" class="old-mask" @click.self="closeDrawer">
    <div class="old-box">
      <div class="old-head">
        <b>操作日志详情</b>
        <div style="flex:1"></div>
        <button class="btn" @click="closeDrawer">关闭</button>
      </div>

      <div class="old-body">
        <div v-if="loading" class="old-state">加载中…</div>
        <div v-else-if="error" class="old-state old-err">{{ error }}</div>
        <div v-else-if="!row" class="old-state">无数据</div>

        <template v-else>
          <div class="card">
            <div class="section-title">操作信息</div>
            <div class="meta-grid">
              <div class="meta-cell"><label>操作时间</label><span>{{ fmtTime(row.operateAt) }}</span></div>
              <div class="meta-cell"><label>操作人</label><span>{{ row.operatorName || '—' }}</span></div>
              <div class="meta-cell"><label>账号</label><span>{{ row.operatorAccount || '—' }}</span></div>
              <div class="meta-cell"><label>模块</label><span>{{ row.moduleName || row.moduleCode || '—' }}</span></div>
              <div class="meta-cell"><label>动作</label><span>{{ row.actionName || row.action || '—' }}</span></div>
              <div class="meta-cell"><label>结果</label>
                <span :class="row.result === 'FAIL' ? 'txt-fail' : 'txt-ok'">
                  {{ row.result === 'FAIL' ? '失败' : '成功' }}
                </span>
              </div>
              <div class="meta-cell"><label>业务号</label><span>{{ row.bizNo || '—' }}</span></div>
              <div class="meta-cell"><label>业务类型</label><span>{{ row.bizType || '—' }}</span></div>
              <div class="meta-cell"><label>IP</label><span>{{ row.requestIp || '—' }}</span></div>
              <div class="meta-cell"><label>方法</label><span>{{ row.requestMethod || '—' }}</span></div>
              <div class="meta-cell"><label>耗时</label><span>{{ costText(row.costTimeMs) || '—' }}</span></div>
              <div class="meta-cell"><label>敏感</label>
                <span :class="row.sensitive === 'Y' ? 'txt-warn' : ''">{{ row.sensitive === 'Y' ? '是' : '否' }}</span>
              </div>
              <div class="meta-cell meta-wide"><label>请求URL</label><span>{{ row.requestUrl || '—' }}</span></div>
              <div v-if="row.failReason" class="meta-cell meta-wide"><label>失败原因</label>
                <span class="txt-fail">{{ row.failReason }}</span></div>
            </div>
          </div>

          <div class="card">
            <div class="section-title">改前 / 改后对比</div>
            <OperationLogDiff
              :after="row.afterValue"
              :before="row.beforeValue"
              :content="row.operationContent || ''"
              :detail="row.detail || ''"
            />
          </div>
        </template>
      </div>
    </div>
  </div>
</template>

<style scoped>
.old-mask {
  position: fixed; top: 48px; right: 0; bottom: 0; left: 299px;
  z-index: 920; display: flex; pointer-events: auto;
  background: rgba(15, 46, 88, 0.12); animation: fadeIn 0.2s ease;
}
.old-box {
  flex: 1; background: #fff; display: flex; flex-direction: column; min-width: 0;
  border-left: 1px solid var(--line, #dcdfe6);
  box-shadow: -6px 0 24px rgba(15, 46, 88, 0.16);
  animation: slideIn 0.25s ease;
}
.old-head {
  display: flex; align-items: center; gap: 10px; height: 46px; padding: 0 16px;
  border-bottom: 1px solid var(--line-soft, #ebeef5);
}
.old-head b { font-size: 15px; }
.old-body {
  flex: 1; overflow: auto; padding: 12px 16px;
  display: flex; flex-direction: column; gap: 12px; background: #f5f7fa;
}
.old-state {
  padding: 48px; text-align: center; color: #909399; font-size: 13px;
  background: #fafbfc; border: 1px dashed var(--line, #dcdfe6); border-radius: 6px;
}
.old-err { color: #d04848; }
.card {
  background: #fff; border: 1px solid var(--line-soft, #ebeef5);
  border-radius: 6px; padding: 12px 14px;
}
.section-title {
  font-weight: 800; color: var(--primary, #2b6cb0); font-size: 13px;
  margin-bottom: 10px; padding-bottom: 6px; border-bottom: 1px solid var(--line-soft, #ebeef5);
}
.meta-grid {
  display: grid; grid-template-columns: repeat(3, 1fr); gap: 10px 20px;
}
.meta-cell { display: flex; align-items: center; min-width: 0; font-size: 12px; gap: 8px; }
.meta-wide { grid-column: 1 / -1; }
.meta-cell label { width: 64px; color: #909399; text-align: right; flex-shrink: 0; }
.meta-cell span {
  color: #303133; font-weight: 500; overflow: hidden; text-overflow: ellipsis;
  white-space: nowrap; word-break: break-all;
}
.txt-ok { color: #2f9e58; }
.txt-fail { color: #d04848; }
.txt-warn { color: #b8860b; font-weight: 700; }
@keyframes fadeIn { from { opacity: 0; } to { opacity: 1; } }
@keyframes slideIn { from { transform: translateX(60px); opacity: 0.6; } to { transform: translateX(0); opacity: 1; } }
@media (max-width: 900px) {
  .old-mask { left: 0; }
  .meta-grid { grid-template-columns: repeat(2, 1fr); }
}
</style>
