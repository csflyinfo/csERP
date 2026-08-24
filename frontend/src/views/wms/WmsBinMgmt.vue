<script setup>
/**
 * WMS V1.5 库位管理（V83 新增，PRD-28；V85 增加重量/体积/拣货顺序与导入）
 * 左侧：库区列表（带仓库分组），右侧：选中库区下的库位，支持新建/编辑/启停/删除。
 * 巷列层三个窄输入在第一行，自动拼接 binCode = {aisle}-{slot}-{layer}；
 * 新增最大存货重量(kg)/体积(m³)与拣货顺序；顶部"导入"按钮支持按行粘贴批量建库位。
 */
import { ref, computed, onMounted, watch } from 'vue'
import { post } from '../../api/client.js'

const zones = ref([])
const currentZone = ref(null) // {warehouse, zoneCode, zoneName}
const bins = ref([])
const loading = ref(false)
const feedback = ref('')
const fKeyword = ref('')

const BIN_TYPES = [
  { v: 'SHELF', l: '货架位' },
  { v: 'FLOOR', l: '平库位' },
  { v: 'STAGE', l: '暂存位' },
  { v: 'PICK', l: '拣货位' },
  // COLLECT(集货位) 已独立到「集货区管理」模块，此处不再创建/展示
]
const binTypeText = Object.fromEntries(BIN_TYPES.map(t => [t.v, t.l]))

const dialogOpen = ref(false)
const dialogTitle = ref('')
const form = ref(blankForm())
const binCodeTouched = ref(false) // 用户是否手工改过编码；改了就不再被自动覆盖
function blankForm() {
  return {
    binId: '', binCode: '', binName: '', binType: 'SHELF',
    aisle: '', slot: '', layer: '',
    capacityQty: 0, capacityWeight: 0, capacityVolume: 0, pickSeq: 0,
    remark: '',
  }
}
function show(msg, level = 'ok') {
  feedback.value = { level, msg }
  setTimeout(() => (feedback.value = ''), 3000)
}

async function loadZones() {
  try {
    const res = await post('/wms/zone/page', { pageNo: 1, pageSize: 500, filters: {} })
    // 集货区(COLLECT)已独立到「集货区管理」，本页库区树不展示
    zones.value = (res.records || []).filter(z => z.zoneType !== 'COLLECT')
    if (!currentZone.value && zones.value.length) {
      const z = zones.value[0]
      currentZone.value = { warehouse: z.warehouse, zoneCode: z.zoneCode, zoneName: z.zoneName }
    }
  } catch (e) { show('库区加载失败：' + (e?.message || e), 'err') }
}
async function loadBins() {
  if (!currentZone.value) { bins.value = []; return }
  loading.value = true
  try {
    const res = await post('/wms/bin/page', {
      pageNo: 1, pageSize: 500,
      filters: { warehouse: currentZone.value.warehouse, zoneCode: currentZone.value.zoneCode, keyword: fKeyword.value },
    })
    // 集货位(COLLECT)已独立到「集货区管理」，本页不展示
    bins.value = (res.records || []).filter(b => b.binType !== 'COLLECT')
  } catch (e) { show('库位加载失败：' + (e?.message || e), 'err') }
  finally { loading.value = false }
}

function selectZone(z) {
  currentZone.value = { warehouse: z.warehouse, zoneCode: z.zoneCode, zoneName: z.zoneName }
  fKeyword.value = ''
  loadBins()
}

// 按仓库把库区分组显示在左侧
const groupedZones = computed(() => {
  const m = new Map()
  for (const z of zones.value) {
    if (!m.has(z.warehouse)) m.set(z.warehouse, [])
    m.get(z.warehouse).push(z)
  }
  return [...m.entries()].map(([wh, list]) => ({ wh, list }))
})

// 巷 / 列 / 层 任意一个变化都自动生成库位编码（除非用户手工改过）
watch(() => [form.value.aisle, form.value.slot, form.value.layer], ([a, s, l]) => {
  if (binCodeTouched.value || form.value.binId) return
  const parts = [a, s, l].map(x => (x || '').trim()).filter(Boolean)
  form.value.binCode = parts.join('-')
  if (!form.value.binName) form.value.binName = parts.join('巷')
})

function onCreate() {
  if (!currentZone.value) return show('请先在左侧选择库区', 'err')
  form.value = blankForm()
  binCodeTouched.value = false
  dialogTitle.value = `新建库位（${currentZone.value.warehouse} / ${currentZone.value.zoneCode}）`
  dialogOpen.value = true
}
function onEdit(row) {
  form.value = {
    binId: row.binId, binCode: row.binCode, binName: row.binName || '',
    binType: row.binType, aisle: row.aisle || '', slot: row.slot || '',
    layer: row.layer || '',
    capacityQty: num(row.capacityQty), capacityWeight: num(row.capacityWeight),
    capacityVolume: num(row.capacityVolume), pickSeq: num(row.pickSeq),
    remark: row.remark || '',
  }
  binCodeTouched.value = true
  dialogTitle.value = '编辑库位：' + row.binCode
  dialogOpen.value = true
}
function num(v) { const n = Number(v); return Number.isFinite(n) ? n : 0 }

async function onSave() {
  if (!form.value.binCode.trim()) return show('库位编码不能为空（填好巷列层会自动生成）', 'err')
  try {
    await post('/wms/bin/save', {
      ...form.value,
      warehouse: currentZone.value.warehouse,
      zoneCode: currentZone.value.zoneCode,
    })
    show('保存成功')
    dialogOpen.value = false
    loadBins()
  } catch (e) { show('保存失败：' + (e?.message || e), 'err') }
}
async function onToggle(row) {
  const action = row.status === 'DISABLED' ? '启用' : '停用'
  if (!confirm(`确认${action}库位 ${row.binCode}？停用后不再参与上架/拣货推荐，但保留库存与历史。`)) return
  try {
    await post('/wms/bin/toggle', { binId: row.binId })
    show(action + '成功')
    loadBins()
  } catch (e) { show(action + '失败：' + (e?.message || e), 'err') }
}
async function onDelete(row) {
  if (!confirm(`确认删除库位 ${row.binCode}？仅在库位无在库且无在途绑定时允许。`)) return
  try {
    await post('/wms/bin/delete', { binId: row.binId })
    show('已删除')
    loadBins()
  } catch (e) { show('删除失败：' + (e?.message || e), 'err') }
}

// ============ 批量导入 ============
const importOpen = ref(false)
const importText = ref('')
const importType = ref('SHELF')
const importing = ref(false)
function openImport() {
  if (!currentZone.value) return show('请先在左侧选择库区', 'err')
  importText.value = ''
  importType.value = 'SHELF'
  importOpen.value = true
}
/**
 * 解析粘贴文本，支持以下列（第一行表头可选，按列名匹配；无表头按列序）：
 *   binCode, binName, aisle, slot, layer, capacityQty, capacityWeight,
 *   capacityVolume, pickSeq, remark
 * 也允许"只贴 binCode 一列"——其余字段留空/默认。
 */
function parseImport(text) {
  const lines = text.split(/\r?\n/).map(l => l.trim()).filter(Boolean)
  if (!lines.length) return []
  const HEADER_MAP = {
    binCode: ['bincode', '库位编码', '编码'],
    binName: ['binname', '库位名称', '名称'],
    aisle: ['aisle', '巷'],
    slot: ['slot', '列'],
    layer: ['layer', '层'],
    capacityQty: ['capacityqty', '容量件数', '件数', '容量'],
    capacityWeight: ['capacityweight', '重量', '最大重量'],
    capacityVolume: ['capacityvolume', '体积', '最大体积'],
    pickSeq: ['pickseq', '拣货顺序', '顺序', 'seq'],
    remark: ['remark', '备注'],
  }
  // 判断是否是表头
  const firstCols = lines[0].split(/\t|,|;|\s+/).map(s => s.trim().toLowerCase())
  const hasHeader = firstCols.some(c =>
    Object.values(HEADER_MAP).flat().includes(c))
  let cols = hasHeader
    ? firstCols.map(c => Object.entries(HEADER_MAP).find(([, ks]) => ks.includes(c))?.[0] || null)
    : null
  const dataLines = hasHeader ? lines.slice(1) : lines
  // 无表头时，按列序：binCode, aisle, slot, layer, binName, capacityQty, pickSeq
  const defaultOrder = ['binCode', 'aisle', 'slot', 'layer', 'binName', 'capacityQty', 'pickSeq']
  const rows = []
  for (const line of dataLines) {
    const parts = line.split(/\t|,|;|\s+/).map(s => s.trim())
    const row = {}
    if (cols) {
      parts.forEach((v, i) => { if (cols[i]) row[cols[i]] = v })
    } else {
      parts.forEach((v, i) => { if (defaultOrder[i]) row[defaultOrder[i]] = v })
    }
    if (!row.binCode) continue
    rows.push(row)
  }
  return rows
}
async function onImport() {
  if (!currentZone.value) return show('请先在左侧选择库区', 'err')
  const rows = parseImport(importText.value)
  if (!rows.length) return show('没有可导入的行（检查第一列是否是库位编码）', 'err')
  importing.value = true
  try {
    const res = await post('/wms/bin/import', {
      warehouse: currentZone.value.warehouse,
      zoneCode: currentZone.value.zoneCode,
      binType: importType.value,
      rows,
    })
    const failed = res.failed || []
    show(`导入完成：新增 ${res.inserted}，更新 ${res.updated}，失败 ${failed.length}${failed.length ? '（见下）' : ''}`)
    if (failed.length) {
      importText.value = failed.map(f => `${f.binCode}\t${f.reason}`).join('\n')
    } else {
      importOpen.value = false
      loadBins()
    }
  } catch (e) {
    show('导入失败：' + (e?.message || e), 'err')
  } finally {
    importing.value = false
  }
}

onMounted(async () => {
  await loadZones()
  await loadBins()
})
</script>

<template>
  <div class="module-body">
    <div class="page-ops">
      <b>库位管理</b>
      <span class="tip">左侧选择库区，右侧维护库位；巷列层填好自动出编码；删除前请先移位清空在库。</span>
      <div class="spacer"></div>
      <input v-model="fKeyword" placeholder="库位编码/名称" @keydown.enter="loadBins" />
      <button class="btn" @click="loadBins">查询</button>
      <button class="btn" @click="loadZones(); loadBins()">刷新</button>
      <button class="btn" :disabled="!currentZone" @click="openImport">⇪ 导入库位</button>
      <button class="btn primary" :disabled="!currentZone" @click="onCreate">＋ 新建库位</button>
    </div>
    <div v-if="feedback" class="toast-inline" :class="feedback.level">{{ feedback.msg }}</div>

    <div class="split">
      <aside class="left">
        <div class="left-h">库区列表（{{ zones.length }}）</div>
        <div class="left-scroll">
          <template v-for="g in groupedZones" :key="g.wh">
            <div class="grp-title">{{ g.wh }}</div>
            <div v-for="z in g.list" :key="z.zoneId"
                 class="zn-item" :class="{ on: currentZone && currentZone.zoneCode===z.zoneCode && currentZone.warehouse===z.warehouse }"
                 @click="selectZone(z)">
              <div class="zn-code">{{ z.zoneCode }}</div>
              <div class="zn-name">{{ z.zoneName }}</div>
            </div>
          </template>
          <div v-if="!zones.length" class="empty">暂无库区，请先在「库区管理」维护</div>
        </div>
      </aside>

      <section class="right">
        <div class="right-h">
          <span v-if="currentZone">
            <b>{{ currentZone.zoneName }}</b>
            <span class="muted">（{{ currentZone.warehouse }} / {{ currentZone.zoneCode }}）</span>
          </span>
          <span v-else class="muted">请在左侧选择库区</span>
          <div class="spacer"></div>
          <span v-if="loading" class="muted">加载中...</span>
          <span v-else class="muted">共 {{ bins.length }} 个库位 · 按拣货顺序排列</span>
        </div>
        <div class="right-scroll">
          <table class="data">
            <thead>
              <tr>
                <th class="num">序</th>
                <th>库位编码</th><th>名称</th><th>类型</th><th>巷-列-层</th>
                <th class="num">件数</th>
                <th class="num">重量kg</th>
                <th class="num">体积m³</th>
                <th class="num">已占用</th>
                <th>状态</th><th>备注</th><th class="ops">操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="b in bins" :key="b.binId">
                <td class="num seq">{{ b.pickSeq || 0 }}</td>
                <td class="bin">{{ b.binCode }}</td>
                <td>{{ b.binName || '—' }}</td>
                <td>{{ binTypeText[b.binType] || b.binType }}</td>
                <td>{{ [b.aisle,b.slot,b.layer].filter(Boolean).join('-') || '—' }}</td>
                <td class="num">{{ b.capacityQty || 0 }}</td>
                <td class="num">{{ b.capacityWeight || 0 }}</td>
                <td class="num">{{ b.capacityVolume || 0 }}</td>
                <td class="num">{{ b.usedQty || 0 }}</td>
                <td><span class="tag" :class="b.status==='DISABLED'?'warn':(b.frozen==='Y'?'frozen':'ok')">
                  {{ b.status==='DISABLED' ? '已停用' : (b.frozen==='Y' ? '库区冻结' : '正常') }}
                </span></td>
                <td class="muted">{{ b.remark || '—' }}</td>
                <td class="ops">
                  <a @click="onEdit(b)">编辑</a>
                  <a @click="onToggle(b)">{{ b.status==='DISABLED' ? '启用' : '停用' }}</a>
                  <a class="danger" @click="onDelete(b)">删除</a>
                </td>
              </tr>
              <tr v-if="!bins.length"><td colspan="12" class="empty">该库区下暂无库位</td></tr>
            </tbody>
          </table>
        </div>
      </section>
    </div>

    <div v-if="dialogOpen" class="modal-mask" @click.self="dialogOpen=false">
      <div class="modal w620">
        <div class="modal-h">{{ dialogTitle }}<span class="x" @click="dialogOpen=false">×</span></div>
        <div class="modal-b">
          <div class="form-row aisle-row">
            <label>巷 / 列 / 层 <em>*</em></label>
            <input v-model="form.aisle" placeholder="巷" class="w-tiny" maxlength="8" />
            <span class="dash">-</span>
            <input v-model="form.slot" placeholder="列" class="w-tiny" maxlength="8" />
            <span class="dash">-</span>
            <input v-model="form.layer" placeholder="层" class="w-tiny" maxlength="8" />
            <span class="auto-hint">填好自动生成编码</span>
          </div>
          <div class="form-row"><label>库位编码 <em>*</em></label>
            <input v-model="form.binCode" placeholder="如 A-01-01"
                   :disabled="!!form.binId"
                   @input="binCodeTouched = true" />
            <span v-if="!form.binId" class="muted small">手工改过则停止自动生成</span>
          </div>
          <div class="form-row"><label>库位名称</label>
            <input v-model="form.binName" placeholder="如 A区01巷01列1层" /></div>
          <div class="form-row"><label>库位类型</label>
            <select v-model="form.binType">
              <option v-for="t in BIN_TYPES" :key="t.v" :value="t.v">{{ t.l }}</option>
            </select></div>
          <div class="form-row cap-row">
            <label>容量上限</label>
            <span class="cap-cell">件数 <input v-model.number="form.capacityQty" type="number" min="0" class="w-num" /></span>
            <span class="cap-cell">重量(kg) <input v-model.number="form.capacityWeight" type="number" min="0" step="0.001" class="w-num" /></span>
            <span class="cap-cell">体积(m³) <input v-model.number="form.capacityVolume" type="number" min="0" step="0.0001" class="w-num" /></span>
          </div>
          <div class="form-row"><label>拣货顺序</label>
            <input v-model.number="form.pickSeq" type="number" min="0" class="w-num" />
            <span class="muted small">数字越小越先拣；同库区按序号升序排列，PDA 按此顺序引导路径</span>
          </div>
          <div class="form-row"><label>备注</label>
            <input v-model="form.remark" /></div>
        </div>
        <div class="modal-f">
          <button class="btn" @click="dialogOpen=false">取消</button>
          <button class="btn primary" @click="onSave">保存</button>
        </div>
      </div>
    </div>

    <!-- 导入库位对话框 -->
    <div v-if="importOpen" class="modal-mask" @click.self="importOpen=false">
      <div class="modal w680">
        <div class="modal-h">批量导入库位（{{ currentZone && currentZone.zoneName }}）<span class="x" @click="importOpen=false">×</span></div>
        <div class="modal-b">
          <div class="import-hint">
            <p>从 Excel 粘贴多行，列用 Tab/逗号/空格分隔。第一行可以是表头，识别列名：<br />
            <code>库位编码 | 名称 | 巷 | 列 | 层 | 件数 | 重量 | 体积 | 拣货顺序 | 备注</code><br />
            没有表头时按列序：<code>编码, 巷, 列, 层, 名称, 件数, 拣货顺序</code>。已存在的编码会被更新（按当前仓库去重）。</p>
          </div>
          <div class="form-row">
            <label>默认类型</label>
            <select v-model="importType">
              <option v-for="t in BIN_TYPES" :key="t.v" :value="t.v">{{ t.l }}</option>
            </select>
            <span class="muted small">行内未指定类型时用此值</span>
          </div>
          <textarea v-model="importText" class="import-area"
                    placeholder="A-01-01	A区01巷01列1层	01	01	01	50	200	2.5	1
A-01-02	..."></textarea>
        </div>
        <div class="modal-f">
          <button class="btn" @click="importOpen=false">取消</button>
          <button class="btn primary" :disabled="importing" @click="onImport">{{ importing ? '导入中...' : '开始导入' }}</button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.tip { color: #874d00; background: #fffbe6; border: 1px solid #ffe58f; padding: 4px 10px; border-radius: 6px; font-size: 12px; margin-left: 10px; }
.spacer { flex: 1; }
.page-ops input { padding: 5px 10px; border: 1px solid #d9d9d9; border-radius: 6px; font-size: 13px; width: 180px; }
.split { display: grid; grid-template-columns: 260px 1fr; gap: 12px; margin-top: 10px; height: calc(100vh - 200px); min-height: 480px; }
.left, .right { background: #fff; border: 1px solid #f0f0f0; border-radius: 8px; display: flex; flex-direction: column; overflow: hidden; }
.left-h, .right-h { padding: 10px 14px; border-bottom: 1px solid #f0f0f0; background: #fafafa; font-size: 13px; display: flex; align-items: center; }
.left-scroll, .right-scroll { overflow: auto; flex: 1; }
.grp-title { padding: 8px 14px 4px; color: #999; font-size: 11px; background: #fcfcfc; border-bottom: 1px dashed #f0f0f0; }
.zn-item { padding: 8px 14px; border-bottom: 1px solid #f5f5f5; cursor: pointer; display: flex; align-items: center; gap: 10px; }
.zn-item:hover { background: #fafcff; }
.zn-item.on { background: #e6f4ff; border-left: 3px solid #1677ff; padding-left: 11px; }
.zn-code { font-family: monospace; color: #1677ff; font-weight: 600; min-width: 40px; }
.zn-name { color: #555; font-size: 13px; }
.muted { color: #999; font-size: 12px; }
.small { font-size: 11px; }
.data { width: 100%; border-collapse: collapse; font-size: 13px; }
.data th, .data td { padding: 8px 10px; border-bottom: 1px solid #f0f0f0; text-align: left; }
.data th { background: #fafafa; font-weight: 600; color: #555; }
.num { text-align: right; }
td.seq { color: #1677ff; font-weight: 600; font-family: monospace; }
.bin { font-family: monospace; color: #1677ff; }
.tag { display: inline-block; padding: 1px 8px; border-radius: 10px; font-size: 11px; }
.tag.ok { background: #f6ffed; color: #389e0d; }
.tag.warn { background: #fff1f0; color: #cf1322; }
.tag.frozen { background: #fff7e6; color: #d46b08; }
.ops { white-space: nowrap; text-align: right; }
.ops a { color: #1677ff; cursor: pointer; margin-left: 10px; font-size: 12px; }
.ops a.danger { color: #cf1322; }
.empty { text-align: center; color: #bbb; padding: 30px; font-size: 13px; }
.modal-mask { position: fixed; inset: 0; background: rgba(0,0,0,.4); display: flex; align-items: center; justify-content: center; z-index: 100; }
.modal { background: #fff; border-radius: 10px; width: 560px; max-width: 92vw; max-height: 90vh; overflow: auto; }
.modal.w620 { width: 620px; }
.modal.w680 { width: 680px; }
.modal-h { padding: 14px 18px; font-weight: 700; border-bottom: 1px solid #f0f0f0; display: flex; justify-content: space-between; }
.modal-h .x { cursor: pointer; color: #999; }
.modal-b { padding: 16px 18px; }
.modal-f { padding: 12px 18px; border-top: 1px solid #f0f0f0; text-align: right; }
.modal-f .btn { margin-left: 8px; }
.form-row { display: flex; align-items: center; gap: 10px; margin-bottom: 12px; }
.form-row label { width: 100px; color: #555; font-size: 13px; }
.form-row label em { color: #cf1322; font-style: normal; }
.form-row input, .form-row select { flex: 1; padding: 6px 10px; border: 1px solid #d9d9d9; border-radius: 6px; }
/* 巷列层一行：三个窄输入 + 短横，明确"一行显示完" */
.aisle-row { gap: 6px; }
.aisle-row label { width: 100px; }
.aisle-row .w-tiny {
  flex: 0 0 56px !important;
  width: 56px;
  text-align: center;
  font-family: monospace;
  font-weight: 600;
  padding: 6px 4px;
}
.aisle-row .dash { color: #999; font-weight: 700; }
.aisle-row .auto-hint { margin-left: 8px; color: #1677ff; font-size: 12px; }
.cap-row { flex-wrap: wrap; gap: 14px; }
.cap-row .cap-cell { display: inline-flex; align-items: center; gap: 6px; color: #555; font-size: 12px; }
.cap-row .cap-cell .w-num { flex: 0 0 88px; width: 88px; padding: 5px 8px; }
.w-num { flex: 0 0 110px !important; width: 110px; padding: 5px 8px; }
.import-hint { background: #f5faff; border: 1px solid #d6e8ff; border-radius: 6px; padding: 8px 12px; font-size: 12px; color: #555; margin-bottom: 12px; line-height: 1.7; }
.import-hint code { background: #eef5ff; padding: 1px 5px; border-radius: 3px; font-size: 11px; color: #1d4ed8; }
.import-area { width: 100%; min-height: 220px; font-family: 'Cascadia Code', Consolas, monospace; font-size: 12px; padding: 10px; border: 1px solid #d9d9d9; border-radius: 6px; resize: vertical; }
.toast-inline { padding: 8px 12px; border-radius: 6px; margin-bottom: 10px; font-size: 13px; background: #f6ffed; border: 1px solid #b7eb8f; color: #389e0d; }
.toast-inline.err { background: #fff1f0; border-color: #ffa39e; color: #cf1322; }
</style>
