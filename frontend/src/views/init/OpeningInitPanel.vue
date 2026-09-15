<script setup>
/**
 * 期初初始化通用暂存面板（PRD-34）
 * 应收/应付单面板使用；库存页在两个页签（按批次/按库位）各放一个。
 *
 * 职责：暂存行分页/过滤、导入、手工新增与编辑、删除、按批清空。
 * 建账状态卡、过账/反建账由父页面持有（库存两个页签共用一次过账）。
 */
import { ref, reactive, computed, watch, onMounted } from 'vue'
import { post } from '../../api/client.js'
import ImportDialog from '../../components/ImportDialog.vue'
import SearchSelect from '../../components/SearchSelect.vue'
import { IMPORT_PRESETS } from '../../importPresets.js'

const props = defineProps({
  /** 后端路由前缀，如 /init/ar */
  apiBase: { type: String, required: true },
  /** 权限码：{view, edit, delete, import} */
  perm: { type: Object, required: true },
  /** BATCH / BIN（库存），应收应付传空串 */
  mode: { type: String, default: '' },
  /** importPresets.js 的 preset key */
  presetKey: { type: String, required: true },
  /** 数据列：{f 字段, t 标题, w 宽度, num 右对齐} */
  columns: { type: Array, required: true },
  /** 编辑表单：{f,l,required,type,step,placeholder,hint}；type=select 用 options/dynamicOptions */
  formDef: { type: Array, required: true },
  /** 全局锁定（已建账/日结/GL 启用）：只读 */
  locked: { type: Boolean, default: false },
  /** 动态下拉选项，如库位按所选仓库级联：(field, form) => [{value,label}] */
  dynamicOptions: { type: Function, default: null },
  /** 字段变更回调（如切换仓库清空库位） */
  onFieldChange: { type: Function, default: null },
  /** 打开新增/编辑弹窗时回调（用于级联下拉预加载） */
  onFormSetup: { type: Function, default: null },
  /** 模块中文名（弹窗/提示） */
  entityLabel: { type: String, default: '期初' },
})

const emit = defineEmits(['changed'])

const rows = ref([])
const total = ref(0)
const pageNo = ref(1)
const pageSize = ref(20)
const loading = ref(false)
const feedback = ref('')
const statusFilter = ref('') // '' 全部 / VALID / ERROR
const keyword = ref('')

function show(msg, level = 'ok') {
  feedback.value = { level, msg }
  setTimeout(() => (feedback.value = ''), 4000)
}

async function load() {
  loading.value = true
  try {
    const body = {
      pageNo: pageNo.value,
      pageSize: pageSize.value,
      posted: props.locked ? 'Y' : 'N',
    }
    if (props.mode) body.initMode = props.mode
    if (statusFilter.value) body.lineStatus = statusFilter.value
    if (keyword.value.trim()) body.keyword = keyword.value.trim()
    const res = await post(props.apiBase + '/line/page', body)
    rows.value = res.records || []
    total.value = res.total || 0
  } catch (e) {
    show('加载失败：' + (e?.message || e), 'err')
  } finally {
    loading.value = false
  }
}

watch(() => props.locked, () => { pageNo.value = 1; load() })
onMounted(load)

function fmt(v, digits = 2) {
  const n = Number(v || 0)
  return n.toLocaleString('zh-CN', { minimumFractionDigits: digits, maximumFractionDigits: digits })
}
function cellText(r, f) {
  const v = r[f]
  if (v === null || v === undefined || v === '') return '—'
  const col = props.columns.find(c => c.f === f)
  if (col?.num) return fmt(v, col.digits ?? 2)
  return String(v)
}

// ==================== 手工行 ====================
const editor = reactive({ open: false, editingId: '', form: {} })

function blankForm() {
  const f = {}
  for (const d of props.formDef) f[d.f] = ''
  return f
}
function openCreate() {
  editor.open = true
  editor.editingId = ''
  editor.form = blankForm()
  if (props.onFormSetup) props.onFormSetup(editor.form)
}
function openEdit(r) {
  editor.open = true
  editor.editingId = r.lineId
  const f = blankForm()
  for (const d of props.formDef) f[d.f] = r[d.f] ?? ''
  editor.form = f
  if (props.onFormSetup) props.onFormSetup(editor.form)
}
function fieldChanged(f) {
  // 级联清理
  if (props.onFieldChange) props.onFieldChange(f, editor.form)
}
function optionsOf(d) {
  if (d.options) return d.options
  if (props.dynamicOptions) return props.dynamicOptions(d.f, editor.form) || []
  return []
}

async function saveForm() {
  const missing = props.formDef.filter(d => d.required && `${editor.form[d.f] ?? ''}`.trim() === '')
  if (missing.length) { show('请填写必填项：' + missing.map(d => d.l).join('、'), 'err'); return }
  const body = { ...editor.form }
  if (props.mode) body.initMode = props.mode
  try {
    if (editor.editingId) {
      await post(props.apiBase + '/line/update', { lineId: editor.editingId, ...body })
      show('期初行已更新')
    } else {
      await post(props.apiBase + '/line/save', body)
      show('期初行已新增')
    }
    editor.open = false
    await load()
    emit('changed')
  } catch (e) { show('保存失败：' + (e?.message || e), 'err') }
}

async function removeRow(r) {
  if (!confirm(`确认删除该期初行？\n${r.goodsName || r.customerName || r.supplierName || ''} ${r.warehouseName || ''}`)) return
  try {
    await post(props.apiBase + '/line/delete', { lineId: r.lineId })
    show('已删除')
    await load()
    emit('changed')
  } catch (e) { show('删除失败：' + (e?.message || e), 'err') }
}

// ==================== 导入 / 清空 ====================
const importVisible = ref(false)
const preset = computed(() => IMPORT_PRESETS[props.presetKey])

async function handleImport(rowsList, meta = {}) {
  try {
    const extra = preset.value.extra ? preset.value.extra() : {}
    const res = await post(preset.value.endpoint, { ...extra, rows: rowsList, fileName: meta.fileName || '' })
    importVisible.value = false
    show(res?.message || `导入完成：成功 ${res?.inserted ?? 0} 条，失败 ${res?.failed ?? 0} 条`)
    await load()
    emit('changed')
  } catch (e) { show('导入失败：' + (e?.message || e), 'err') }
}

async function clearAll() {
  const tip = statusFilter.value === 'ERROR'
    ? '清空功能将删除【全部未建账暂存行】（不只是当前筛选的错误行），确认继续？'
    : '确认清空全部未建账暂存行？此操作不可恢复。'
  if (!confirm(tip)) return
  try {
    const res = await post(props.apiBase + '/clear', {})
    show(`已清空 ${res.deleted || 0} 行`)
    await load()
    emit('changed')
  } catch (e) { show('清空失败：' + (e?.message || e), 'err') }
}

function changePage(delta) {
  pageNo.value = Math.max(1, pageNo.value + delta)
  load()
}

defineExpose({ load })
</script>

<template>
  <div class="init-panel">
    <div v-if="feedback" class="toast-inline" :class="feedback.level">{{ feedback.msg }}</div>

    <div class="toolbar">
      <input v-model="keyword" class="kw" placeholder="编码/名称/批号/库位关键字" @keyup.enter="() => { pageNo = 1; load() }" />
      <button class="btn sm" @click="() => { pageNo = 1; load() }">查询</button>
              <select v-if="!locked" v-model="statusFilter" class="sel" @change="() => { pageNo = 1; load() }">
        <option value="">全部状态</option>
        <option value="VALID">有效</option>
        <option value="ERROR">错误</option>
      </select>
      <div class="spacer"></div>
      <template v-if="!locked">
        <button class="btn sm" v-permission="perm.import" @click="importVisible = true">导入</button>
        <button class="btn sm" v-permission="perm.edit" @click="openCreate">新增行</button>
        <button class="btn sm danger-outline" v-permission="perm.delete" @click="clearAll">清空暂存</button>
      </template>
      <button class="btn sm" @click="load">刷新</button>
    </div>

    <div class="table-wrap">
      <table class="data">
        <thead>
          <tr>
            <th v-for="c in columns" :key="c.f" :style="c.w ? { width: c.w } : null"
                :class="{ num: c.num }">{{ c.t }}</th>
            <th style="width:70px">状态</th>
            <th v-if="!locked" style="width:170px">失败原因</th>
            <th style="width:130px">导入批次</th>
            <th v-if="!locked" style="width:90px">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-if="loading"><td :colspan="columns.length + (locked ? 2 : 4)" class="muted center">加载中…</td></tr>
          <tr v-else-if="!rows.length"><td :colspan="columns.length + (locked ? 2 : 4)" class="muted center">
            {{ locked ? '该流程无期初行' : '暂无暂存行，请导入 Excel 或手工新增' }}
          </td></tr>
          <template v-else>
            <tr v-for="r in rows" :key="r.lineId">
              <td v-for="c in columns" :key="c.f" :class="{ num: c.num }"
                  :title="cellText(r, c.f)">{{ cellText(r, c.f) }}</td>
              <td>
                <span class="line-tag" :class="r.lineStatus === 'ERROR' ? 'err' : 'ok'">
                  {{ r.lineStatus === 'ERROR' ? '错误' : '有效' }}
                </span>
              </td>
              <td v-if="!locked" class="err-msg" :title="r.errorMsg || ''">{{ r.errorMsg || '—' }}</td>
              <td class="muted small">{{ r.importBatchNo || '—' }}</td>
              <td v-if="!locked">
                <a class="link" v-permission="perm.edit" @click="openEdit(r)">编辑</a>
                <a class="link danger" v-permission="perm.delete" @click="removeRow(r)">删除</a>
              </td>
            </tr>
          </template>
        </tbody>
      </table>
    </div>

    <div class="pager">
      <span class="muted small">共 {{ total }} 行</span>
      <div class="spacer"></div>
      <button class="btn sm" :disabled="pageNo <= 1" @click="changePage(-1)">上一页</button>
      <span class="page-no">{{ pageNo }}</span>
      <button class="btn sm" :disabled="pageNo * pageSize >= total" @click="changePage(1)">下一页</button>
    </div>

    <!-- 导入弹窗 -->
    <ImportDialog
      :visible="importVisible"
      :title="preset.title"
      :template-headers="preset.templateHeaders"
      :template-name="preset.templateName"
      :field-map="preset.fieldMap"
      :required-key="preset.requiredKey"
      :template-url="preset.templateUrl"
      @close="importVisible = false"
      @import="handleImport"
    />

    <!-- 手工新增/编辑弹窗 -->
    <div v-if="editor.open" class="modal-mask" @click.self="editor.open = false">
      <div class="modal w720">
        <div class="modal-h">
          {{ editor.editingId ? '编辑' : '新增' }}{{ entityLabel }}期初行
          <span class="x" @click="editor.open = false">×</span>
        </div>
        <div class="modal-b">
          <div class="form-grid">
            <label v-for="d in formDef" :key="d.f" class="field"
                   :class="{ wide: d.type === 'textarea' }">
              <span class="flabel"><i v-if="d.required" class="req">*</i>{{ d.l }}</span>
              <SearchSelect v-if="d.type === 'select'"
                            :model-value="editor.form[d.f]"
                            :options="optionsOf(d)"
                            :placeholder="d.placeholder || ('选择' + d.l)"
                            @update:model-value="v => { editor.form[d.f] = v; fieldChanged(d.f) }"
                            clearable />
              <textarea v-else-if="d.type === 'textarea'" v-model="editor.form[d.f]" rows="2"
                        :placeholder="d.placeholder || ''"></textarea>
              <input v-else :type="d.type === 'number' ? 'number' : (d.type === 'date' ? 'date' : 'text')"
                     :step="d.step || (d.type === 'number' ? '0.01' : null)"
                     v-model="editor.form[d.f]"
                     :placeholder="d.placeholder || ''" />
              <span v-if="d.hint" class="hint">{{ d.hint }}</span>
            </label>
          </div>
        </div>
        <div class="modal-f">
          <button class="btn" @click="editor.open = false">取消</button>
          <button class="btn primary" v-permission="perm.edit" @click="saveForm">保存</button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.init-panel { display: flex; flex-direction: column; gap: 8px; }
.spacer { flex: 1; }
.toolbar { display: flex; align-items: center; gap: 8px; }
.kw { width: 220px; padding: 5px 10px; border: 1px solid #d9d9d9; border-radius: 6px; font-size: 13px; }
.sel { height: 30px; border: 1px solid #d9d9d9; border-radius: 6px; font-size: 13px; padding: 0 6px; }
.btn.sm { padding: 4px 12px; font-size: 13px; }
.btn.danger-outline { color: #cf1322; border-color: #ffa39e; }
.table-wrap { background: #fff; border: 1px solid #f0f0f0; border-radius: 8px; overflow: auto; }
.data { width: 100%; border-collapse: collapse; font-size: 13px; }
.data th, .data td { padding: 6px 10px; border-bottom: 1px solid #f0f0f0; text-align: left; white-space: nowrap; }
.data th { background: #fafafa; font-weight: 600; color: #555; position: sticky; top: 0; }
.data td.num, .data th.num { text-align: right; }
.data td.err-msg { color: #cf1322; max-width: 240px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.muted { color: #999; }
.small { font-size: 12px; }
.center { text-align: center; padding: 24px 0; }
.line-tag { display: inline-block; padding: 1px 10px; border-radius: 10px; font-size: 12px; }
.line-tag.ok { background: #f6ffed; color: #389e0d; border: 1px solid #b7eb8f; }
.line-tag.err { background: #fff1f0; color: #cf1322; border: 1px solid #ffa39e; }
.link { color: #1677ff; cursor: pointer; font-size: 12px; margin-right: 10px; }
.link.danger { color: #cf1322; }
.pager { display: flex; align-items: center; gap: 8px; }
.page-no { font-size: 13px; }
.toast-inline { padding: 8px 12px; border-radius: 6px; font-size: 13px; background: #f6ffed; border: 1px solid #b7eb8f; color: #389e0d; }
.toast-inline.err { background: #fff1f0; border-color: #ffa39e; color: #cf1322; }
.modal-mask { position: fixed; inset: 0; background: rgba(0,0,0,.4); display: flex; align-items: center; justify-content: center; z-index: 100; }
.modal { background: #fff; border-radius: 10px; width: 560px; max-width: 94vw; max-height: 90vh; overflow: auto; }
.modal.w720 { width: 760px; }
.modal-h { padding: 14px 18px; font-weight: 700; border-bottom: 1px solid #f0f0f0; display: flex; justify-content: space-between; position: sticky; top: 0; background: #fff; z-index: 1; }
.modal-h .x { cursor: pointer; color: #999; }
.modal-b { padding: 16px 18px; }
.modal-f { padding: 12px 18px; border-top: 1px solid #f0f0f0; text-align: right; }
.modal-f .btn { margin-left: 8px; }
.form-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 12px 16px; }
.field { display: flex; flex-direction: column; gap: 4px; font-size: 13px; }
.field.wide { grid-column: 1 / -1; }
.flabel { color: #555; }
.req { color: #cf1322; margin-right: 2px; font-style: normal; }
.hint { color: #999; font-size: 12px; }
.field input, .field textarea, .field :deep(.search-select) { padding: 5px 10px; border: 1px solid #d9d9d9; border-radius: 6px; font-size: 13px; font-family: inherit; }
.field textarea { resize: vertical; }
</style>
