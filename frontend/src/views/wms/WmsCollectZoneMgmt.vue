<script setup>
/**
 * WMS V1.5 集货区管理（V88 新增，PRD-28）
 * 左右表格：左侧集货区（按仓库过滤，新建/编辑/停用启用/导入/删除），
 * 右侧该集货区下的集货位（新建/编辑/停用启用/锁定释放单个/导入/删除）。
 *
 * 字段语义：
 *   wms_zone.frozen='Y'  集货区停用，分配时级联排除该区下所有集货位
 *   wms_bin.status       集货位停用(DISABLED)/启用(NORMAL)
 *   wms_bin.frozen='Y'   集货位人工锁定（释放/锁定），不参与自动分配；区别于 used_qty 实际占用
 */
import { ref, computed, onMounted } from 'vue'
import { get, post } from '../../api/client.js'

const warehouses = ref([])
const currentWarehouse = ref('')
const zones = ref([])
const currentZone = ref(null) // {warehouse, zoneCode, zoneName, frozen}
const bins = ref([])
const loadingZone = ref(false)
const loadingBin = ref(false)
const feedback = ref('')
const fKeyword = ref('')

function show(msg, level = 'ok') {
  feedback.value = { level, msg }
  setTimeout(() => (feedback.value = ''), 3000)
}
function num(v) { const n = Number(v); return Number.isFinite(n) ? n : 0 }

async function loadWarehouses() {
  try {
    warehouses.value = await get('/wms/warehouse/list')
    if (!currentWarehouse.value && warehouses.value.length) {
      currentWarehouse.value = warehouses.value[0].warehouseName
    }
  } catch (e) { show('仓库加载失败：' + (e?.message || e), 'err') }
}

async function loadZones() {
  if (!currentWarehouse.value) { zones.value = []; return }
  loadingZone.value = true
  try {
    const res = await post('/wms/collection-zone/page', {
      pageNo: 1, pageSize: 500,
      filters: { warehouse: currentWarehouse.value },
    })
    zones.value = res.records || []
    // 当前选中区不在新列表里（被删/换仓）则清空
    if (currentZone.value && !zones.value.some(z => z.zoneId === currentZone.value.zoneId)) {
      currentZone.value = null
      bins.value = []
    }
  } catch (e) { show('集货区加载失败：' + (e?.message || e), 'err') }
  finally { loadingZone.value = false }
}

async function loadBins() {
  if (!currentZone.value) { bins.value = []; return }
  loadingBin.value = true
  try {
    const res = await post('/wms/collection-bin/page', {
      pageNo: 1, pageSize: 500,
      filters: {
        warehouse: currentZone.value.warehouse,
        zoneCode: currentZone.value.zoneCode,
        keyword: fKeyword.value,
      },
    })
    bins.value = res.records || []
  } catch (e) { show('集货位加载失败：' + (e?.message || e), 'err') }
  finally { loadingBin.value = false }
}

function onWarehouseChange() {
  currentZone.value = null
  bins.value = []
  loadZones()
}

function selectZone(z) {
  currentZone.value = { zoneId: z.zoneId, warehouse: z.warehouse, zoneCode: z.zoneCode, zoneName: z.zoneName, frozen: z.frozen }
  fKeyword.value = ''
  loadBins()
}

// ============ 集货区：新建/编辑/停用/删除 ============
const zoneDialogOpen = ref(false)
const zoneDialogTitle = ref('')
const zoneForm = ref(blankZoneForm())
function blankZoneForm() {
  return { zoneId: '', zoneCode: '', zoneName: '', warehouse: '', pickSeq: 0, remark: '' }
}
function onCreateZone() {
  zoneForm.value = blankZoneForm()
  zoneForm.value.warehouse = currentWarehouse.value
  zoneDialogTitle.value = '新建集货区'
  zoneDialogOpen.value = true
}
function onEditZone(z) {
  zoneForm.value = {
    zoneId: z.zoneId, zoneCode: z.zoneCode, zoneName: z.zoneName,
    warehouse: z.warehouse, pickSeq: num(z.pickSeq), remark: z.remark || '',
  }
  zoneDialogTitle.value = '编辑集货区：' + z.zoneCode
  zoneDialogOpen.value = true
}
async function onSaveZone() {
  if (!zoneForm.value.zoneCode.trim()) return show('集货区编码不能为空', 'err')
  if (!zoneForm.value.zoneName.trim()) return show('集货区名称不能为空', 'err')
  try {
    await post('/wms/collection-zone/save', zoneForm.value)
    show('保存成功')
    zoneDialogOpen.value = false
    await loadZones()
  } catch (e) { show('保存失败：' + (e?.message || e), 'err') }
}
async function onToggleZone(z) {
  const action = z.frozen === 'Y' ? '启用' : '停用'
  if (!confirm(`确认${action}集货区 ${z.zoneCode}？停用后该集货区下所有集货位都不可被分配使用。`)) return
  try {
    await post('/wms/collection-zone/toggle', { zoneId: z.zoneId })
    show(action + '成功')
    await loadZones()
    if (currentZone.value && currentZone.value.zoneId === z.zoneId) loadBins()
  } catch (e) { show(action + '失败：' + (e?.message || e), 'err') }
}
async function onDeleteZone(z) {
  if (!confirm(`确认删除集货区 ${z.zoneCode}？该区下还有集货位时不允许删除。`)) return
  try {
    await post('/wms/collection-zone/delete', { zoneId: z.zoneId })
    show('已删除')
    await loadZones()
  } catch (e) { show('删除失败：' + (e?.message || e), 'err') }
}

// ============ 集货位：新建/编辑/停用/锁定释放/删除 ============
const binDialogOpen = ref(false)
const binDialogTitle = ref('')
const binForm = ref(blankBinForm())
function blankBinForm() {
  return { binId: '', binCode: '', binName: '', capacityQty: 30, pickSeq: 0, remark: '' }
}
function onCreateBin() {
  if (!currentZone.value) return show('请先在左侧选择集货区', 'err')
  binForm.value = blankBinForm()
  binDialogTitle.value = `新建集货位（${currentZone.value.zoneName}）`
  binDialogOpen.value = true
}
function onEditBin(b) {
  binForm.value = {
    binId: b.binId, binCode: b.binCode, binName: b.binName || '',
    capacityQty: num(b.capacityQty), pickSeq: num(b.pickSeq), remark: b.remark || '',
  }
  binDialogTitle.value = '编辑集货位：' + b.binCode
  binDialogOpen.value = true
}
async function onSaveBin() {
  if (!binForm.value.binCode.trim()) return show('集货位编码不能为空', 'err')
  try {
    await post('/wms/collection-bin/save', {
      ...binForm.value,
      warehouse: currentZone.value.warehouse,
      zoneCode: currentZone.value.zoneCode,
    })
    show('保存成功')
    binDialogOpen.value = false
    loadBins()
  } catch (e) { show('保存失败：' + (e?.message || e), 'err') }
}
async function onToggleBin(b) {
  const action = b.status === 'DISABLED' ? '启用' : '停用'
  if (!confirm(`确认${action}集货位 ${b.binCode}？停用后不参与自动分配，但保留占用与历史。`)) return
  try {
    await post('/wms/collection-bin/toggle', { binId: b.binId })
    show(action + '成功')
    loadBins()
  } catch (e) { show(action + '失败：' + (e?.message || e), 'err') }
}
async function onLockBin(b) {
  if (!confirm(`确认锁定集货位 ${b.binCode}？锁定后该位不参与自动分配（人工挂起），实际占用不变。`)) return
  try {
    await post('/wms/collection-bin/lock', { binId: b.binId })
    show('已锁定')
    loadBins()
  } catch (e) { show('锁定失败：' + (e?.message || e), 'err') }
}
async function onReleaseBin(b) {
  if (!confirm(`确认释放集货位 ${b.binCode}？释放后恢复参与自动分配。`)) return
  try {
    await post('/wms/collection-bin/release', { binId: b.binId })
    show('已释放')
    loadBins()
  } catch (e) { show('释放失败：' + (e?.message || e), 'err') }
}
async function onDeleteBin(b) {
  if (!confirm(`确认删除集货位 ${b.binCode}？仅在无在库时允许。`)) return
  try {
    await post('/wms/collection-bin/delete', { binId: b.binId })
    show('已删除')
    loadBins()
  } catch (e) { show('删除失败：' + (e?.message || e), 'err') }
}

// 集货位状态标签：集货区停用 > 位停用 > 位锁定 > 正常
function binStatusTag(b) {
  if (b.zoneFrozen === 'Y') return { text: '集货区已停用', cls: 'warn' }
  if (b.status === 'DISABLED') return { text: '已停用', cls: 'warn' }
  if (b.frozen === 'Y') return { text: '已锁定', cls: 'frozen' }
  return { text: '正常', cls: 'ok' }
}

// ============ 导入（集货区 / 集货位共用一套粘贴解析） ============
const zoneImportOpen = ref(false)
const zoneImportText = ref('')
const zoneImporting = ref(false)
function openZoneImport() { zoneImportText.value = ''; zoneImportOpen.value = true }
function parseImport(text, headerMap, defaultOrder) {
  const lines = text.split(/\r?\n/).map(l => l.trim()).filter(Boolean)
  if (!lines.length) return []
  const firstCols = lines[0].split(/\t|,|;|\s+/).map(s => s.trim().toLowerCase())
  const hasHeader = firstCols.some(c => Object.values(headerMap).flat().includes(c))
  const cols = hasHeader
    ? firstCols.map(c => Object.entries(headerMap).find(([, ks]) => ks.includes(c))?.[0] || null)
    : null
  const dataLines = hasHeader ? lines.slice(1) : lines
  const rows = []
  for (const line of dataLines) {
    const parts = line.split(/\t|,|;|\s+/).map(s => s.trim())
    const row = {}
    if (cols) parts.forEach((v, i) => { if (cols[i]) row[cols[i]] = v })
    else parts.forEach((v, i) => { if (defaultOrder[i]) row[defaultOrder[i]] = v })
    if (row[defaultOrder[0]]) rows.push(row)
  }
  return rows
}
async function onImportZones() {
  const HEADER_MAP = {
    zoneCode: ['zonecode', '集货区编码', '编码'],
    zoneName: ['zonename', '集货区名称', '名称'],
    pickSeq: ['pickseq', '拣货顺序', '顺序', 'seq'],
    remark: ['remark', '备注'],
  }
  const rows = parseImport(zoneImportText.value, HEADER_MAP, ['zoneCode', 'zoneName', 'pickSeq', 'remark'])
  if (!rows.length) return show('没有可导入的行（检查第一列是否是集货区编码）', 'err')
  zoneImporting.value = true
  try {
    const res = await post('/wms/collection-zone/import', {
      warehouse: currentWarehouse.value, rows,
    })
    const failed = res.failed || []
    show(`导入完成：新增 ${res.inserted}，更新 ${res.updated}，失败 ${failed.length}${failed.length ? '（见下）' : ''}`)
    if (failed.length) {
      zoneImportText.value = failed.map(f => `${f.zoneCode}\t${f.reason}`).join('\n')
    } else {
      zoneImportOpen.value = false
      loadZones()
    }
  } catch (e) { show('导入失败：' + (e?.message || e), 'err') }
  finally { zoneImporting.value = false }
}

const binImportOpen = ref(false)
const binImportText = ref('')
const binImporting = ref(false)
function openBinImport() {
  if (!currentZone.value) return show('请先在左侧选择集货区', 'err')
  binImportText.value = ''
  binImportOpen.value = true
}
async function onImportBins() {
  if (!currentZone.value) return show('请先在左侧选择集货区', 'err')
  const HEADER_MAP = {
    binCode: ['bincode', '集货位编码', '编码'],
    binName: ['binname', '集货位名称', '名称'],
    capacityQty: ['capacityqty', '容量件数', '件数', '容量'],
    pickSeq: ['pickseq', '拣货顺序', '顺序', 'seq'],
    remark: ['remark', '备注'],
  }
  const rows = parseImport(binImportText.value, HEADER_MAP, ['binCode', 'binName', 'capacityQty', 'pickSeq', 'remark'])
  if (!rows.length) return show('没有可导入的行（检查第一列是否是集货位编码）', 'err')
  binImporting.value = true
  try {
    const res = await post('/wms/collection-bin/import', {
      warehouse: currentZone.value.warehouse,
      zoneCode: currentZone.value.zoneCode,
      rows,
    })
    const failed = res.failed || []
    show(`导入完成：新增 ${res.inserted}，更新 ${res.updated}，失败 ${failed.length}${failed.length ? '（见下）' : ''}`)
    if (failed.length) {
      binImportText.value = failed.map(f => `${f.binCode}\t${f.reason}`).join('\n')
    } else {
      binImportOpen.value = false
      loadBins()
    }
  } catch (e) { show('导入失败：' + (e?.message || e), 'err') }
  finally { binImporting.value = false }
}

const currentZoneDisabled = computed(() => currentZone.value && currentZone.value.frozen === 'Y')

onMounted(async () => {
  await loadWarehouses()
  await loadZones()
})
</script>

<template>
  <div class="module-body">
    <div class="page-ops">
      <b>集货区管理</b>
      <span class="tip">左侧维护集货区，右侧维护集货位；集货区停用后其下所有集货位不可被分配；集货位可单独锁定/释放。</span>
      <div class="spacer"></div>
      <label class="wh-label">仓库</label>
      <select v-model="currentWarehouse" @change="onWarehouseChange" class="wh-select">
        <option v-for="w in warehouses" :key="w.warehouseName" :value="w.warehouseName">{{ w.warehouseName }}</option>
      </select>
      <button class="btn" @click="loadZones(); loadBins()">刷新</button>
      <button class="btn" @click="openZoneImport">⇪ 导入集货区</button>
      <button class="btn primary" @click="onCreateZone">＋ 新建集货区</button>
    </div>
    <div v-if="feedback" class="toast-inline" :class="feedback.level">{{ feedback.msg }}</div>

    <div class="split">
      <!-- 左：集货区 -->
      <aside class="left">
        <div class="left-h">
          集货区（{{ zones.length }}）
          <div class="spacer"></div>
          <span v-if="loadingZone" class="muted">加载中...</span>
        </div>
        <div class="left-scroll">
          <div v-for="z in zones" :key="z.zoneId"
               class="zn-item" :class="{ on: currentZone && currentZone.zoneId === z.zoneId }"
               @click="selectZone(z)">
            <div class="zn-main">
              <div class="zn-code">{{ z.zoneCode }}</div>
              <div class="zn-name">{{ z.zoneName }}</div>
            </div>
            <div class="zn-meta">
              <span class="tag" :class="z.frozen==='Y'?'warn':'ok'">{{ z.frozen==='Y' ? '已停用' : '正常' }}</span>
              <span class="muted">{{ z.binCount || 0 }} 位</span>
            </div>
            <div class="zn-ops" @click.stop>
              <a @click="onEditZone(z)">编辑</a>
              <a @click="onToggleZone(z)">{{ z.frozen==='Y' ? '启用' : '停用' }}</a>
              <a class="danger" @click="onDeleteZone(z)">删除</a>
            </div>
          </div>
          <div v-if="!zones.length" class="empty">该仓库暂无集货区，点「新建集货区」创建</div>
        </div>
      </aside>

      <!-- 右：集货位 -->
      <section class="right">
        <div class="right-h">
          <span v-if="currentZone">
            <b>{{ currentZone.zoneName }}</b>
            <span class="muted">（{{ currentZone.warehouse }} / {{ currentZone.zoneCode }}）</span>
            <span v-if="currentZoneDisabled" class="tag warn inline">集货区已停用，集货位不可分配</span>
          </span>
          <span v-else class="muted">请在左侧选择集货区</span>
          <div class="spacer"></div>
          <input v-model="fKeyword" placeholder="集货位编码/名称" @keydown.enter="loadBins" />
          <button class="btn" @click="loadBins">查询</button>
          <span v-if="loadingBin" class="muted">加载中...</span>
          <span v-else class="muted">共 {{ bins.length }} 个集货位</span>
          <button class="btn" :disabled="!currentZone" @click="openBinImport">⇪ 导入集货位</button>
          <button class="btn primary" :disabled="!currentZone" @click="onCreateBin">＋ 新建集货位</button>
        </div>
        <div class="right-scroll">
          <table class="data">
            <thead>
              <tr>
                <th class="num">序</th>
                <th>集货位编码</th><th>名称</th>
                <th class="num">容量上限</th>
                <th class="num">已占用</th>
                <th>状态</th><th>备注</th><th class="ops">操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="b in bins" :key="b.binId">
                <td class="num seq">{{ b.pickSeq || 0 }}</td>
                <td class="bin">{{ b.binCode }}</td>
                <td>{{ b.binName || '—' }}</td>
                <td class="num">{{ b.capacityQty || 0 }}</td>
                <td class="num">{{ b.usedQty || 0 }}</td>
                <td><span class="tag" :class="binStatusTag(b).cls">{{ binStatusTag(b).text }}</span></td>
                <td class="muted">{{ b.remark || '—' }}</td>
                <td class="ops">
                  <a @click="onEditBin(b)">编辑</a>
                  <a @click="onToggleBin(b)">{{ b.status==='DISABLED' ? '启用' : '停用' }}</a>
                  <a v-if="b.frozen==='Y'" @click="onReleaseBin(b)">释放</a>
                  <a v-else class="lock" @click="onLockBin(b)">锁定</a>
                  <a class="danger" @click="onDeleteBin(b)">删除</a>
                </td>
              </tr>
              <tr v-if="!bins.length"><td colspan="8" class="empty">该集货区下暂无集货位</td></tr>
            </tbody>
          </table>
        </div>
      </section>
    </div>

    <!-- 集货区新建/编辑 -->
    <div v-if="zoneDialogOpen" class="modal-mask" @click.self="zoneDialogOpen=false">
      <div class="modal w560">
        <div class="modal-h">{{ zoneDialogTitle }}<span class="x" @click="zoneDialogOpen=false">×</span></div>
        <div class="modal-b">
          <div class="form-row">
            <label>仓库 <em>*</em></label>
            <select v-model="zoneForm.warehouse" :disabled="!!zoneForm.zoneId">
              <option v-for="w in warehouses" :key="w.warehouseName" :value="w.warehouseName">{{ w.warehouseName }}</option>
            </select>
          </div>
          <div class="form-row"><label>集货区编码 <em>*</em></label>
            <input v-model="zoneForm.zoneCode" placeholder="如 COL" :disabled="!!zoneForm.zoneId" maxlength="50" /></div>
          <div class="form-row"><label>集货区名称 <em>*</em></label>
            <input v-model="zoneForm.zoneName" placeholder="如 集货区" maxlength="100" /></div>
          <div class="form-row"><label>拣货顺序</label>
            <input v-model.number="zoneForm.pickSeq" type="number" min="0" class="w-num" />
            <span class="muted small">数字越小越靠前</span></div>
          <div class="form-row"><label>备注</label><input v-model="zoneForm.remark" maxlength="255" /></div>
        </div>
        <div class="modal-f">
          <button class="btn" @click="zoneDialogOpen=false">取消</button>
          <button class="btn primary" @click="onSaveZone">保存</button>
        </div>
      </div>
    </div>

    <!-- 集货位新建/编辑 -->
    <div v-if="binDialogOpen" class="modal-mask" @click.self="binDialogOpen=false">
      <div class="modal w560">
        <div class="modal-h">{{ binDialogTitle }}<span class="x" @click="binDialogOpen=false">×</span></div>
        <div class="modal-b">
          <div class="form-row"><label>集货位编码 <em>*</em></label>
            <input v-model="binForm.binCode" placeholder="如 COL-03" :disabled="!!binForm.binId" maxlength="50" /></div>
          <div class="form-row"><label>集货位名称</label>
            <input v-model="binForm.binName" placeholder="如 集货位03" maxlength="100" /></div>
          <div class="form-row"><label>容量上限(件)</label>
            <input v-model.number="binForm.capacityQty" type="number" min="0" class="w-num" />
            <span class="muted small">智能占用模式按此阈值判断是否继续拼放</span></div>
          <div class="form-row"><label>拣货顺序</label>
            <input v-model.number="binForm.pickSeq" type="number" min="0" class="w-num" /></div>
          <div class="form-row"><label>备注</label><input v-model="binForm.remark" maxlength="255" /></div>
        </div>
        <div class="modal-f">
          <button class="btn" @click="binDialogOpen=false">取消</button>
          <button class="btn primary" @click="onSaveBin">保存</button>
        </div>
      </div>
    </div>

    <!-- 导入集货区 -->
    <div v-if="zoneImportOpen" class="modal-mask" @click.self="zoneImportOpen=false">
      <div class="modal w680">
        <div class="modal-h">批量导入集货区（{{ currentWarehouse }}）<span class="x" @click="zoneImportOpen=false">×</span></div>
        <div class="modal-b">
          <div class="import-hint">
            <p>从 Excel 粘贴多行，列用 Tab/逗号/空格分隔。第一行可以是表头，识别列名：<br />
            <code>集货区编码 | 集货区名称 | 拣货顺序 | 备注</code><br />
            没有表头时按列序：<code>编码, 名称, 拣货顺序, 备注</code>。已存在的编码会被更新（按当前仓库去重）。</p>
          </div>
          <textarea v-model="zoneImportText" class="import-area"
                    placeholder="COL-A	集货区A	10
COL-B	集货区B	20"></textarea>
        </div>
        <div class="modal-f">
          <button class="btn" @click="zoneImportOpen=false">取消</button>
          <button class="btn primary" :disabled="zoneImporting" @click="onImportZones">{{ zoneImporting ? '导入中...' : '开始导入' }}</button>
        </div>
      </div>
    </div>

    <!-- 导入集货位 -->
    <div v-if="binImportOpen" class="modal-mask" @click.self="binImportOpen=false">
      <div class="modal w680">
        <div class="modal-h">批量导入集货位（{{ currentZone && currentZone.zoneName }}）<span class="x" @click="binImportOpen=false">×</span></div>
        <div class="modal-b">
          <div class="import-hint">
            <p>从 Excel 粘贴多行，列用 Tab/逗号/空格分隔。第一行可以是表头，识别列名：<br />
            <code>集货位编码 | 集货位名称 | 容量件数 | 拣货顺序 | 备注</code><br />
            没有表头时按列序：<code>编码, 名称, 容量件数, 拣货顺序, 备注</code>。已存在的编码会被更新（按当前仓库去重）。</p>
          </div>
          <textarea v-model="binImportText" class="import-area"
                    placeholder="COL-03	集货位03	30	3
COL-04	集货位04	30	4"></textarea>
        </div>
        <div class="modal-f">
          <button class="btn" @click="binImportOpen=false">取消</button>
          <button class="btn primary" :disabled="binImporting" @click="onImportBins">{{ binImporting ? '导入中...' : '开始导入' }}</button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.tip { color: #874d00; background: #fffbe6; border: 1px solid #ffe58f; padding: 4px 10px; border-radius: 6px; font-size: 12px; margin-left: 10px; }
.spacer { flex: 1; }
.wh-label { color: #555; font-size: 13px; }
.wh-select { padding: 5px 10px; border: 1px solid #d9d9d9; border-radius: 6px; font-size: 13px; width: 150px; }
.page-ops input { padding: 5px 10px; border: 1px solid #d9d9d9; border-radius: 6px; font-size: 13px; width: 160px; }
.split { display: grid; grid-template-columns: 300px 1fr; gap: 12px; margin-top: 10px; height: calc(100vh - 200px); min-height: 480px; }
.left, .right { background: #fff; border: 1px solid #f0f0f0; border-radius: 8px; display: flex; flex-direction: column; overflow: hidden; }
.left-h, .right-h { padding: 10px 14px; border-bottom: 1px solid #f0f0f0; background: #fafafa; font-size: 13px; display: flex; align-items: center; gap: 8px; }
.left-scroll, .right-scroll { overflow: auto; flex: 1; }
.zn-item { padding: 10px 14px; border-bottom: 1px solid #f5f5f5; cursor: pointer; }
.zn-item:hover { background: #fafcff; }
.zn-item.on { background: #e6f4ff; border-left: 3px solid #1677ff; padding-left: 11px; }
.zn-main { display: flex; align-items: center; gap: 10px; }
.zn-code { font-family: monospace; color: #1677ff; font-weight: 600; min-width: 48px; }
.zn-name { color: #333; font-size: 13px; font-weight: 500; }
.zn-meta { display: flex; align-items: center; gap: 8px; margin-top: 4px; padding-left: 58px; }
.zn-ops { margin-top: 6px; padding-left: 58px; }
.zn-ops a { color: #1677ff; cursor: pointer; margin-right: 12px; font-size: 12px; }
.zn-ops a.danger { color: #cf1322; }
.muted { color: #999; font-size: 12px; }
.small { font-size: 11px; }
.tag.inline { margin-left: 8px; }
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
.ops a.lock { color: #d46b08; }
.ops a.danger { color: #cf1322; }
.empty { text-align: center; color: #bbb; padding: 30px; font-size: 13px; }
.modal-mask { position: fixed; inset: 0; background: rgba(0,0,0,.4); display: flex; align-items: center; justify-content: center; z-index: 100; }
.modal { background: #fff; border-radius: 10px; width: 560px; max-width: 92vw; max-height: 90vh; overflow: auto; }
.modal.w560 { width: 560px; }
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
.w-num { flex: 0 0 110px !important; width: 110px; padding: 5px 8px; }
.import-hint { background: #f5faff; border: 1px solid #d6e8ff; border-radius: 6px; padding: 8px 12px; font-size: 12px; color: #555; margin-bottom: 12px; line-height: 1.7; }
.import-hint code { background: #eef5ff; padding: 1px 5px; border-radius: 3px; font-size: 11px; color: #1d4ed8; }
.import-area { width: 100%; min-height: 220px; font-family: 'Cascadia Code', Consolas, monospace; font-size: 12px; padding: 10px; border: 1px solid #d9d9d9; border-radius: 6px; resize: vertical; }
.toast-inline { padding: 8px 12px; border-radius: 6px; margin-bottom: 10px; font-size: 13px; background: #f6ffed; border: 1px solid #b7eb8f; color: #389e0d; }
.toast-inline.err { background: #fff1f0; border-color: #ffa39e; color: #cf1322; }
</style>
