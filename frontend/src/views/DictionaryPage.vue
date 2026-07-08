<script setup>
import { ref, computed, onMounted } from 'vue'
import { get, post } from '../api/client.js'
import { invalidateDict } from '../utils/dictionary.js'
import ImportDialog from '../components/ImportDialog.vue'
import * as XLSX from 'xlsx'

// ============ state ============
const types = ref([])          // 左侧字典类型列表
const values = ref([])         // 右侧字典值列表
const selectedTypeId = ref('')
const typeSearch = ref('')
const feedback = ref('')

const selectedType = computed(() => types.value.find(t => t.id === selectedTypeId.value))
const filteredTypes = computed(() => {
  const q = typeSearch.value.trim().toLowerCase()
  if (!q) return types.value
  return types.value.filter(t =>
    String(t.dictType || '').toLowerCase().includes(q)
    || String(t.dictTypeName || '').toLowerCase().includes(q)
  )
})

function show(msg) { feedback.value = msg; setTimeout(() => feedback.value = '', 2000) }

// ============ 类型 CRUD ============
async function loadTypes(keepSelected = false) {
  try {
    const data = await post('/base/dictionary/type/page', { pageNo: 1, pageSize: 500, filters: {} })
    types.value = data?.records || []
    if (!keepSelected && !selectedTypeId.value && types.value.length) {
      selectedTypeId.value = types.value[0].id
      await loadValues()
    }
  } catch (e) {
    types.value = []
  }
}

async function pickType(t) {
  selectedTypeId.value = t.id
  await loadValues()
}

// 字典类型系统预设：仅查看，不支持新建/编辑/删除

// ============ 值 CRUD ============
async function loadValues() {
  if (!selectedType.value) { values.value = []; return }
  try {
    const data = await post('/base/dictionary/value/page', { dictType: selectedType.value.dictType })
    values.value = data?.records || []
  } catch (e) {
    values.value = []
  }
}

const valueDialog = ref(null)
function nextDictCode() {
  // 字典值编码从 0 开始按顺序号生成，找出当前最大数字编码 + 1
  let maxN = -1
  for (const v of values.value) {
    const code = String(v.dictCode || '').trim()
    if (/^\d+$/.test(code)) {
      const n = parseInt(code, 10)
      if (n > maxN) maxN = n
    }
  }
  return String(maxN + 1)
}
function openValueAdd() {
  if (!selectedType.value) { alert('请先选择左侧的字典类型'); return }
  valueDialog.value = { mode: 'add', form: { id: '', dictType: selectedType.value.dictType, dictCode: nextDictCode(), dictName: '', sortOrder: (values.value.length + 1) * 10, remark: '', status: 'NORMAL' } }
}
function openValueEdit(v) {
  valueDialog.value = { mode: 'edit', form: { ...v, isSystem: !!v.isSystem } }
}
async function saveValue() {
  const f = valueDialog.value.form
  if (!f.dictCode || !f.dictName) { alert('编码和名称必填'); return }
  try {
    await post('/base/dictionary/value/save', f)
    show('字典值已保存')
    valueDialog.value = null
    invalidateDict(f.dictType)
    await loadValues()
  } catch (e) {
    alert('保存失败：' + (e.message || e))
  }
}
async function deleteValue(v) {
  if (v.isSystem) { alert('系统预置字典值不允许删除，请改为停用'); return }
  if (!confirm(`确认删除字典值「${v.dictName}」？`)) return
  try {
    await post('/base/dictionary/value/delete', { id: v.id })
    show('已删除')
    invalidateDict(selectedType.value.dictType)
    await loadValues()
  } catch (e) {
    alert('删除失败：' + (e.message || e))
  }
}
async function toggleValueStatus(v) {
  const next = v.status === 'NORMAL' ? 'STOPPED' : 'NORMAL'
  try {
    await post('/base/dictionary/value/stop', { id: v.id, status: next })
    show(next === 'NORMAL' ? '已启用' : '已停用')
    invalidateDict(selectedType.value.dictType)
    await loadValues()
  } catch (e) {
    alert('操作失败：' + (e.message || e))
  }
}

// ============ 导入 / 导出 ============
const importDialogState = ref({ visible: false })
function openImport() {
  if (!selectedType.value) { alert('请先选择左侧的字典类型'); return }
  importDialogState.value = {
    visible: true,
    title: `导入字典值 - ${selectedType.value.dictTypeName}`,
    templateName: `字典值导入模板_${selectedType.value.dictType}`,
    templateHeaders: ['字典编码', '字典名称', '排序', '备注'],
    fieldMap: {
      '字典编码': 'dictCode',
      '字典名称': 'dictName',
      '排序': 'sortOrder',
      '备注': 'remark',
    },
    requiredKey: 'dictCode',
  }
}
async function handleImportRows(rows) {
  if (!selectedType.value) return
  try {
    const res = await post('/base/dictionary/import', { dictType: selectedType.value.dictType, rows })
    const inserted = res?.inserted ?? 0
    const skipped = res?.skipped ?? 0
    show(`导入完成：新增 ${inserted} 条${skipped ? `，跳过 ${skipped} 条` : ''}`)
    invalidateDict(selectedType.value.dictType)
    await loadValues()
  } catch (e) {
    alert('导入失败：' + (e.message || e))
  }
}

function exportValues() {
  if (!selectedType.value || !values.value.length) { show('无数据可导出'); return }
  const aoa = [['字典编码', '字典名称', '排序', '备注', '状态']]
  values.value.forEach(v => aoa.push([v.dictCode, v.dictName, v.sortOrder, v.remark || '', v.status === 'STOPPED' ? '停用' : '正常']))
  const ws = XLSX.utils.aoa_to_sheet(aoa)
  const wb = XLSX.utils.book_new()
  XLSX.utils.book_append_sheet(wb, ws, '字典值')
  const filename = `字典_${selectedType.value.dictType}_${new Date().toISOString().slice(0, 10)}.xlsx`
  XLSX.writeFile(wb, filename)
  show('导出成功')
}

onMounted(loadTypes)
</script>

<template>
  <div class="dict-page">
    <!-- 左：字典类型 -->
    <aside class="dict-left">
      <div class="dict-head">
        <b>字典类型</b>
        <span class="muted" style="font-size:11px">系统预设，仅可查看</span>
      </div>
      <input class="search" v-model="typeSearch" placeholder="搜索编码/名称" />
      <div class="type-list">
        <div v-if="!filteredTypes.length" class="empty">暂无字典类型</div>
        <div
          v-for="t in filteredTypes"
          :key="t.id"
          class="type-item"
          :class="{ active: selectedTypeId === t.id }"
          @click="pickType(t)"
        >
          <div class="type-line">
            <span class="type-name">{{ t.dictTypeName }}</span>
            <span class="tag-sys">系统</span>
          </div>
          <div class="type-code">{{ t.dictType }}</div>
        </div>
      </div>
    </aside>

    <!-- 右：字典值 -->
    <section class="dict-right">
      <div class="dict-head">
        <div>
          <b>字典值</b>
          <span v-if="selectedType" class="muted" style="margin-left:8px">
            {{ selectedType.dictTypeName }}（{{ selectedType.dictType }}）
          </span>
        </div>
        <div class="actions" v-if="selectedType">
          <button class="btn" @click="openImport">导入</button>
          <button class="btn" @click="exportValues" :disabled="!values.length">导出</button>
          <button class="btn primary" @click="openValueAdd">+ 新建</button>
        </div>
      </div>

      <div v-if="!selectedType" class="empty-panel">请从左侧选择或新建字典类型</div>
      <div v-else class="value-table-wrap">
        <table class="value-table">
          <thead>
            <tr>
              <th style="width:36px">#</th>
              <th>字典编码</th>
              <th>字典名称</th>
              <th style="width:70px">排序</th>
              <th style="width:70px">系统</th>
              <th>备注</th>
              <th style="width:80px">状态</th>
              <th style="width:150px">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-if="!values.length">
              <td colspan="8" class="empty">暂无字典值，点击右上角【+ 新建】</td>
            </tr>
            <tr v-for="(v, idx) in values" :key="v.id">
              <td class="c">{{ idx + 1 }}</td>
              <td>{{ v.dictCode }}</td>
              <td>{{ v.dictName }}</td>
              <td class="c">{{ v.sortOrder }}</td>
              <td class="c">{{ v.isSystem ? '是' : '否' }}</td>
              <td>{{ v.remark }}</td>
              <td class="c">
                <span :class="v.status === 'STOPPED' ? 'badge-off' : 'badge-on'">
                  {{ v.status === 'STOPPED' ? '停用' : '正常' }}
                </span>
              </td>
              <td class="c">
                <button class="link-btn" @click="openValueEdit(v)">编辑</button>
                <button class="link-btn" @click="toggleValueStatus(v)">
                  {{ v.status === 'NORMAL' ? '停用' : '启用' }}
                </button>
                <button v-if="!v.isSystem" class="link-btn danger-link" @click="deleteValue(v)">删除</button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>

    <!-- 字典类型系统预设，无编辑弹窗 -->

    <!-- 字典值 弹窗 -->
    <div v-if="valueDialog" class="mask" @click.self="valueDialog = null">
      <div class="dialog">
        <div class="dialog-head">
          <b>{{ valueDialog.mode === 'add' ? '新建字典值' : '编辑字典值' }}</b>
          <button class="link-btn" @click="valueDialog = null">×</button>
        </div>
        <div class="dialog-body">
          <div class="field">
            <label>字典编码 <span class="req">*</span></label>
            <input v-model="valueDialog.form.dictCode" readonly title="字典值编码由系统自动生成，从 0 开始按顺序累加" />
          </div>
          <div class="field">
            <label>字典名称 <span class="req">*</span></label>
            <input v-model="valueDialog.form.dictName" />
          </div>
          <div class="field">
            <label>排序</label>
            <input v-model.number="valueDialog.form.sortOrder" type="number" />
          </div>
          <div class="field">
            <label>状态</label>
            <select v-model="valueDialog.form.status">
              <option value="NORMAL">正常</option>
              <option value="STOPPED">停用</option>
            </select>
          </div>
          <div class="field">
            <label>备注</label>
            <textarea v-model="valueDialog.form.remark" rows="2"></textarea>
          </div>
        </div>
        <div class="dialog-foot">
          <button class="btn" @click="valueDialog = null">取消</button>
          <button class="btn primary" @click="saveValue">保存</button>
        </div>
      </div>
    </div>

    <!-- 导入 -->
    <ImportDialog
      v-if="importDialogState.visible"
      :visible="importDialogState.visible"
      :title="importDialogState.title"
      :template-name="importDialogState.templateName"
      :template-headers="importDialogState.templateHeaders"
      :field-map="importDialogState.fieldMap"
      :required-key="importDialogState.requiredKey"
      @close="importDialogState.visible = false"
      @confirm="handleImportRows"
    />

    <div v-if="feedback" class="toast">{{ feedback }}</div>
  </div>
</template>

<style scoped>
.dict-page {
  display: grid;
  grid-template-columns: 320px 1fr;
  gap: 8px;
  height: 100%;
  min-height: 0;
}
.dict-left,
.dict-right {
  background: #fff;
  border: 1px solid var(--line);
  border-radius: 8px;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.dict-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 12px;
  border-bottom: 1px solid var(--line);
  background: #f8fafc;
  font-size: 13px;
}
.dict-head .btn {
  height: 26px;
  padding: 0 10px;
  font-size: 12px;
}
.search {
  margin: 8px 12px 4px;
  padding: 4px 8px;
  border: 1px solid var(--line);
  border-radius: 4px;
  font-size: 12px;
  height: 28px;
}
.muted {
  color: #909399;
  font-size: 12px;
}
.type-list {
  flex: 1;
  overflow-y: auto;
  padding: 4px 8px 12px;
}
.type-item {
  padding: 8px 10px;
  border-radius: 6px;
  cursor: pointer;
  border: 1px solid transparent;
  margin-bottom: 4px;
}
.type-item:hover {
  background: #f0f4fa;
}
.type-item.active {
  background: #eaf4ff;
  border-color: #b8d5f8;
}
.type-line {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  color: #303133;
  font-weight: 600;
}
.type-code {
  font-size: 11px;
  color: #909399;
  margin-top: 2px;
  font-family: var(--font-mono);
  font-variant-numeric: tabular-nums;
}
.type-actions {
  margin-top: 4px;
  display: flex;
  gap: 8px;
  font-size: 11px;
}
.tag-sys {
  background: #e6f4ff;
  color: #1677ff;
  border: 1px solid #91caff;
  border-radius: 4px;
  padding: 0 4px;
  font-size: 10px;
  font-weight: normal;
}

.value-table-wrap {
  flex: 1;
  overflow: auto;
}
.value-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 12px;
}
.value-table th,
.value-table td {
  border-bottom: 1px solid #eef3f8;
  padding: 6px 10px;
  text-align: left;
}
.value-table th {
  background: #f7fbff;
  color: #24415f;
  font-weight: 600;
  position: sticky;
  top: 0;
}
.value-table td.c { text-align: center; }
.value-table .empty {
  text-align: center;
  color: #909399;
  padding: 24px;
}

.badge-on {
  background: #f0f9eb;
  color: #67c23a;
  border-radius: 4px;
  padding: 2px 6px;
  font-size: 11px;
}
.badge-off {
  background: #fef0f0;
  color: #f56c6c;
  border-radius: 4px;
  padding: 2px 6px;
  font-size: 11px;
}

.empty-panel,
.empty {
  color: #909399;
  padding: 40px;
  text-align: center;
  font-size: 12px;
}

.link-btn {
  border: 0;
  background: transparent;
  color: var(--primary);
  cursor: pointer;
  font-size: 12px;
  margin: 0 4px;
}
.link-btn.danger-link { color: #f56c6c; }

/* 弹窗 */
.mask {
  position: fixed;
  inset: 0;
  background: rgba(15, 35, 60, 0.28);
  display: grid;
  place-items: center;
  z-index: 200;
}
.dialog {
  width: 480px;
  background: #fff;
  border-radius: 8px;
  overflow: hidden;
  box-shadow: 0 20px 60px rgba(0, 0, 0, 0.2);
}
.dialog-head {
  padding: 10px 16px;
  border-bottom: 1px solid var(--line);
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.dialog-body {
  padding: 16px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.dialog-body .field {
  display: grid;
  grid-template-columns: 90px 1fr;
  gap: 4px 12px;
  align-items: center;
}
.dialog-body .field label {
  font-size: 12px;
  color: #606266;
  text-align: right;
}
.dialog-body .field input,
.dialog-body .field select,
.dialog-body .field textarea {
  padding: 4px 8px;
  border: 1px solid var(--line);
  border-radius: 4px;
  font-size: 12px;
  outline: none;
}
.dialog-body .field textarea {
  resize: vertical;
  min-height: 44px;
}
.dialog-body .field .req { color: #f56c6c; }
.dialog-foot {
  padding: 10px 16px;
  border-top: 1px solid var(--line);
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}
.dialog-foot .btn {
  height: 28px;
  padding: 0 14px;
}

.toast {
  position: fixed;
  right: 18px;
  bottom: 18px;
  background: #12385f;
  color: #fff;
  border-radius: 6px;
  padding: 10px 16px;
  font-size: 12px;
  z-index: 300;
}
</style>
