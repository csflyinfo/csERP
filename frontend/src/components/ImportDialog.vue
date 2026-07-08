<script setup>
import { ref, computed, watch } from 'vue'
import * as XLSX from 'xlsx'

/**
 * 通用 Excel 导入弹窗
 * - 支持 xls / xlsx / csv
 * - 拖入或点击选择文件
 * - 「下载模板」按模板 headers 生成 xlsx
 * - 解析后按表头做字段映射并发出 'import' 事件（携带 rows 数组）
 */
const props = defineProps({
  visible: { type: Boolean, default: false },
  title: { type: String, default: '导入数据' },
  /** 模板表头列名数组（首行） */
  templateHeaders: { type: Array, required: true },
  /** 模板文件名（不含扩展名） */
  templateName: { type: String, default: '导入模板' },
  /**
   * 字段映射：表头列名 → 记录键
   * 例如 { '户名': 'accountName', '开户银行': 'bankName' }
   * 未映射的列忽略；缺失列跳过；至少 requiredKey 必须有值才计入一行
   */
  fieldMap: { type: Object, required: true },
  /** 必须有值才视为有效行 */
  requiredKey: { type: String, default: '' },
})

const emit = defineEmits(['close', 'import'])

const dragging = ref(false)
const selectedFile = ref(null)
const parseError = ref('')
const previewRows = ref([]) // 已解析成对象的行

// 预览表头：按模板列顺序显示，仅取出被 fieldMap 覆盖的列
const previewHeaders = computed(() => {
  const map = props.fieldMap || {}
  return (props.templateHeaders || []).filter(h => Object.prototype.hasOwnProperty.call(map, h))
})

watch(() => props.visible, (v) => {
  if (v) {
    dragging.value = false
    selectedFile.value = null
    parseError.value = ''
    previewRows.value = []
  }
})

function onFileChange(e) {
  const f = e.target.files?.[0]
  if (f) handleFile(f)
  e.target.value = ''
}
function onDrop(e) {
  e.preventDefault()
  dragging.value = false
  const f = e.dataTransfer.files?.[0]
  if (f) handleFile(f)
}
function onDragOver(e) { e.preventDefault(); dragging.value = true }
function onDragLeave() { dragging.value = false }

async function handleFile(file) {
  parseError.value = ''
  selectedFile.value = file
  const nameOk = /\.(xls|xlsx|csv)$/i.test(file.name)
  if (!nameOk) {
    parseError.value = '仅支持 xls / xlsx / csv 文件'
    selectedFile.value = null
    return
  }
  try {
    const buf = await file.arrayBuffer()
    const wb = XLSX.read(buf, { type: 'array' })
    const ws = wb.Sheets[wb.SheetNames[0]]
    if (!ws) { parseError.value = '工作簿为空'; return }
    const arr = XLSX.utils.sheet_to_json(ws, { header: 1, defval: '' })
    if (!arr.length) { parseError.value = '文件无数据'; return }
    const header = arr[0].map(h => String(h ?? '').trim())
    const rows = arr.slice(1)
    // 建立列 index 表
    const colIdx = {}
    for (const [label, key] of Object.entries(props.fieldMap)) {
      const idx = header.findIndex(h => h === label || h.includes(label))
      if (idx >= 0) colIdx[key] = idx
    }
    const out = []
    for (const r of rows) {
      if (!Array.isArray(r) || r.every(v => v === '' || v == null)) continue
      const rec = {}
      for (const key of Object.keys(colIdx)) {
        const v = r[colIdx[key]]
        rec[key] = v == null ? '' : String(v).trim()
      }
      if (props.requiredKey && !rec[props.requiredKey]) continue
      out.push(rec)
    }
    if (!out.length) {
      parseError.value = '未解析到有效行，请检查表头是否与模板一致'
    }
    previewRows.value = out
  } catch (e) {
    parseError.value = '解析失败：' + (e.message || e)
  }
}

function downloadTemplate() {
  const ws = XLSX.utils.aoa_to_sheet([props.templateHeaders])
  // 简单列宽
  ws['!cols'] = props.templateHeaders.map(() => ({ wch: 18 }))
  const wb = XLSX.utils.book_new()
  XLSX.utils.book_append_sheet(wb, ws, 'Sheet1')
  XLSX.writeFile(wb, `${props.templateName}.xlsx`)
}

function confirm() {
  if (!previewRows.value.length) {
    parseError.value = '请先选择或拖入有效的 Excel/CSV 文件'
    return
  }
  emit('import', previewRows.value)
}

function close() {
  emit('close')
}
</script>

<template>
  <div v-if="visible" class="import-mask" @click.self="close">
    <div class="import-box">
      <div class="import-head">
        <b>{{ title }}</b>
        <button class="close-btn" type="button" @click="close">×</button>
      </div>
      <div class="import-body">
        <div class="tpl-row">
          <span>请先下载模板，按照表头填入数据后再上传：</span>
          <button class="btn" type="button" @click="downloadTemplate">下载模板（.xlsx）</button>
        </div>

        <div class="dropzone" :class="{ dragging }"
             @dragover="onDragOver" @dragleave="onDragLeave" @drop="onDrop">
          <div v-if="!selectedFile" class="dz-empty">
            <div class="dz-icon">⬆</div>
            <div class="dz-title">拖入文件到此处，或</div>
            <label class="btn primary">
              选择文件
              <input type="file" accept=".xls,.xlsx,.csv" style="display:none" @change="onFileChange" />
            </label>
            <div class="dz-hint">支持 .xls / .xlsx / .csv</div>
          </div>
          <div v-else class="dz-file">
            <div class="dz-fname">📄 {{ selectedFile.name }}</div>
            <div class="dz-meta">已解析 {{ previewRows.length }} 条有效行</div>
            <label class="btn">
              重新选择
              <input type="file" accept=".xls,.xlsx,.csv" style="display:none" @change="onFileChange" />
            </label>
          </div>
        </div>

        <div v-if="parseError" class="err">{{ parseError }}</div>

        <div v-if="previewRows.length" class="preview">
          <div class="preview-title">预览（前 5 行）：</div>
          <div class="preview-scroll">
            <table class="preview-grid">
              <thead>
                <tr>
                  <th style="width:36px">#</th>
                  <th v-for="header in previewHeaders" :key="header">{{ header }}</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="(r, i) in previewRows.slice(0, 5)" :key="i">
                  <td class="c">{{ i + 1 }}</td>
                  <td v-for="header in previewHeaders" :key="header">{{ r[fieldMap[header]] }}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>
      </div>
      <div class="import-foot">
        <button class="btn" type="button" @click="close">取消</button>
        <button class="btn primary" type="button" :disabled="!previewRows.length" @click="confirm">
          确认导入 {{ previewRows.length ? `(${previewRows.length})` : '' }}
        </button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.import-mask {
  position: fixed;
  inset: 0;
  background: rgba(15, 35, 60, 0.42);
  z-index: 800;
  display: grid;
  place-items: center;
}
.import-box {
  width: min(640px, 92vw);
  max-height: 82vh;
  background: #fff;
  border-radius: 12px;
  box-shadow: 0 20px 70px rgba(15, 46, 88, 0.22);
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.import-head {
  height: 46px;
  padding: 0 14px;
  border-bottom: 1px solid #eef1f5;
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 14px;
}
.close-btn { background: none; border: 0; font-size: 18px; cursor: pointer; color: #909399; }
.close-btn:hover { color: #303133; }
.import-body {
  padding: 14px 16px;
  overflow: auto;
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.tpl-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 12px;
  color: #606266;
  gap: 8px;
}
.btn {
  padding: 5px 12px;
  border: 1px solid #dcdfe6;
  border-radius: 4px;
  font-size: 12px;
  background: #fff;
  color: #303133;
  cursor: pointer;
}
.btn:hover { border-color: #c6e2ff; color: #409eff; }
.btn.primary { background: #409eff; color: #fff; border-color: #409eff; }
.btn.primary:hover { background: #66b1ff; border-color: #66b1ff; color: #fff; }
.btn:disabled { opacity: 0.55; cursor: not-allowed; }
.dropzone {
  border: 2px dashed #c8d3e6;
  border-radius: 8px;
  padding: 24px 16px;
  text-align: center;
  transition: all .15s;
  background: #fafbfd;
}
.dropzone.dragging { border-color: #409eff; background: #eff6ff; }
.dz-empty .dz-icon { font-size: 32px; color: #a0aec0; }
.dz-empty .dz-title { margin: 6px 0 8px; color: #606266; font-size: 13px; }
.dz-empty .dz-hint { margin-top: 8px; color: #909399; font-size: 11px; }
.dz-file { display: flex; flex-direction: column; align-items: center; gap: 8px; }
.dz-fname { font-size: 13px; color: #303133; }
.dz-meta { color: #67c23a; font-size: 12px; }
.err {
  color: #f56c6c;
  background: #fef0f0;
  border: 1px solid #fbc4c4;
  padding: 8px 10px;
  border-radius: 6px;
  font-size: 12px;
}
.preview .preview-title { font-size: 12px; color: #606266; margin-bottom: 6px; }
.preview-scroll { max-height: 240px; overflow: auto; border: 1px solid #e5e7eb; border-radius: 4px; }
.preview-grid { width: 100%; border-collapse: collapse; font-size: 12px; }
.preview-grid th, .preview-grid td { border: 1px solid #e5e7eb; padding: 4px 6px; white-space: nowrap; }
.preview-grid th { background: #f8fafc; font-weight: 600; text-align: left; position: sticky; top: 0; z-index: 1; }
.preview-grid td.c { text-align: center; }
.import-foot {
  height: 54px;
  border-top: 1px solid #eef1f5;
  padding: 0 14px;
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 8px;
}
</style>
