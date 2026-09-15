<script setup>
/**
 * 期初初始化页面外壳（PRD-34）：状态卡、锁定提示、期初建账、反建账。
 * 暂存行面板（一个或两个页签）通过默认插槽传入。
 */
import { ref, computed, onMounted } from 'vue'
import { post } from '../../api/client.js'

const props = defineProps({
  apiBase: { type: String, required: true },
  permPost: { type: String, required: true },
  permReverse: { type: String, required: true },
  entityLabel: { type: String, default: '期初' },
  /** 建账确认文案 */
  postConfirm: { type: String, default: '' },
})

const status = ref({})
const feedback = ref('')
const reverseOpen = ref(false)
const reverseReason = ref('')
const acting = ref(false)

const locked = computed(() => !!(status.value.posted || status.value.dayCloseLocked || status.value.glInitialized))

function show(msg, level = 'ok') {
  feedback.value = { level, msg }
  setTimeout(() => (feedback.value = ''), 4000)
}

async function loadStatus() {
  try {
    status.value = await post(props.apiBase + '/status')
  } catch (e) {
    show('状态加载失败：' + (e?.message || e), 'err')
  }
}

async function doPost() {
  const msg = props.postConfirm || '确认执行期初建账？建账后数据写入正式账表并锁定，首次业务日结前可反建账。'
  if (!confirm(msg)) return
  acting.value = true
  try {
    const r = await post(props.apiBase + '/post')
    show(`建账成功，批号 ${r.postNo}，共 ${r.lineCount} 行`)
    await loadStatus()
  } catch (e) {
    show('建账失败：' + (e?.message || e), 'err')
  } finally {
    acting.value = false
  }
}

function openReverse() {
  reverseReason.value = ''
  reverseOpen.value = true
}
async function doReverse() {
  if (!reverseReason.value.trim()) { show('请填写反建账原因', 'err'); return }
  acting.value = true
  try {
    await post(props.apiBase + '/reverse', { reason: reverseReason.value.trim() })
    reverseOpen.value = false
    show('已反建账，暂存行恢复可编辑')
    await loadStatus()
  } catch (e) {
    show('反建账失败：' + (e?.message || e), 'err')
  } finally {
    acting.value = false
  }
}

function fmtDateTime(v) {
  if (!v) return '—'
  return String(v).replace('T', ' ').slice(0, 16)
}

onMounted(loadStatus)
defineExpose({ loadStatus })
</script>

<template>
  <div class="module-body">
    <div class="page-ops">
      <b>{{ entityLabel }}期初初始化</b>
      <span class="state-tag" :class="status.posted ? 'on' : 'off'">
        {{ status.posted ? `已建账 · 批号 ${status.postNo || ''}` : '未建账（暂存核对中）' }}
      </span>
      <div class="spacer"></div>
      <button class="btn" @click="loadStatus">刷新</button>
      <button v-if="!status.posted" class="btn primary" v-permission="permPost"
              :disabled="!status.canPost || acting" @click="doPost">期初建账</button>
      <button v-if="status.canReverse" class="btn danger-outline" v-permission="permReverse"
              :disabled="acting" @click="openReverse">反建账</button>
    </div>

    <div v-if="feedback" class="toast-inline" :class="feedback.level">{{ feedback.msg }}</div>

    <div v-if="status.dayCloseLocked" class="banner warn">
      🔒 系统已存在业务日结记录，{{ entityLabel }}期初数据已锁定，不能修改、导入或反建账。
    </div>
    <div v-else-if="status.glInitialized" class="banner warn">
      🔒 总账已启用，业务期初已锁定；如需调整请通过总账凭证处理。
    </div>
    <div v-else-if="status.posted" class="banner ok">
      ✓ 已于 {{ fmtDateTime(status.postAt) }} 由 {{ status.postByName || '—' }} 建账（批号 {{ status.postNo }}），
      共 {{ status.postLineCount }} 行；首次业务日结之前如发现错误，可反建账后重新导入。
    </div>
    <div v-else class="banner info">
      建议顺序：先建应收/应付与库存业务期初 → 总账一键引入业务期初 → 启用总账 → 首次业务日结。建账后上述操作将全部锁定。
    </div>

    <!-- 合计/警示信息，由具体页面定制 -->
    <slot name="summary" :status="status"></slot>

    <!-- 暂存面板（应收/应付一个；库存两个页签） -->
    <slot :status="status" :locked="locked" :reload="loadStatus"></slot>

    <!-- 反建账原因弹窗 -->
    <div v-if="reverseOpen" class="modal-mask" @click.self="reverseOpen = false">
      <div class="modal">
        <div class="modal-h">反建账 — {{ entityLabel }}期初<span class="x" @click="reverseOpen = false">×</span></div>
        <div class="modal-b">
          <p class="tip">反建账将删除批号 <b>{{ status.postNo }}</b> 写入的全部正式账数据，暂存行恢复为可编辑。
            已发生后续业务（核销/出入库流水）时反建账会被拒绝。</p>
          <label class="reason-label">反建账原因（必填，留痕）
            <textarea v-model="reverseReason" rows="4" placeholder="如：期初金额导错，重新导入"></textarea>
          </label>
        </div>
        <div class="modal-f">
          <button class="btn" @click="reverseOpen = false">取消</button>
          <button class="btn danger" :disabled="acting" @click="doReverse">确认反建账</button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.spacer { flex: 1; }
.state-tag { font-size: 12px; padding: 3px 12px; border-radius: 12px; margin-left: 10px; }
.state-tag.on { background: #f6ffed; color: #389e0d; border: 1px solid #b7eb8f; }
.state-tag.off { background: #fff7e6; color: #d46b08; border: 1px solid #ffd591; }
.banner { padding: 9px 14px; border-radius: 8px; font-size: 13px; margin-top: 10px; }
.banner.warn { background: #fffbe6; border: 1px solid #ffe58f; color: #ad6800; }
.banner.ok { background: #f6ffed; border: 1px solid #b7eb8f; color: #389e0d; }
.banner.info { background: #e6f4ff; border: 1px solid #91caff; color: #0958d9; }
.toast-inline { padding: 8px 12px; border-radius: 6px; margin-top: 10px; font-size: 13px; background: #f6ffed; border: 1px solid #b7eb8f; color: #389e0d; }
.toast-inline.err { background: #fff1f0; border-color: #ffa39e; color: #cf1322; }
.btn.danger-outline { color: #cf1322; border: 1px solid #ffa39e; }
.btn.danger { background: #cf1322; color: #fff; border-color: #cf1322; }
.modal-mask { position: fixed; inset: 0; background: rgba(0,0,0,.4); display: flex; align-items: center; justify-content: center; z-index: 100; }
.modal { background: #fff; border-radius: 10px; width: 520px; max-width: 94vw; }
.modal-h { padding: 14px 18px; font-weight: 700; border-bottom: 1px solid #f0f0f0; display: flex; justify-content: space-between; }
.modal-h .x { cursor: pointer; color: #999; }
.modal-b { padding: 16px 18px; }
.tip { font-size: 12px; color: #999; margin: 0 0 12px; }
.reason-label { display: flex; flex-direction: column; gap: 6px; font-size: 13px; color: #555; }
.reason-label textarea { padding: 8px 10px; border: 1px solid #d9d9d9; border-radius: 6px; font-family: inherit; resize: vertical; }
.modal-f { padding: 12px 18px; border-top: 1px solid #f0f0f0; text-align: right; }
.modal-f .btn { margin-left: 8px; }
</style>
