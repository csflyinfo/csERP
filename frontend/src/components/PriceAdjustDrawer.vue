<script setup>
import { ref, computed, watch } from 'vue'
import { post } from '../api/client.js'
import { formatDateTime } from '../utils/dateTime.js'
import * as XLSX from 'xlsx'

const props = defineProps({
  visible: { type: Boolean, default: false },
  mode: { type: String, default: 'add' }, // add | edit | view
  orderData: { type: Object, default: null }, // 编辑/查看时的数据 { orderId, ... }
})
const emit = defineEmits(['close', 'saved'])

// 单据头
const form = ref({
  orderId: '',
  orderNo: '',
  priceGroupCode: '',
  priceGroupName: '',
  remark: '',
  status: 'DRAFT',
  createUser: '',
  createTime: '',
  auditUser: '',
  auditTime: '',
  submitUser: '',
  submitTime: '',
  rejectReason: '',
})

// 明细 [{ goodsCode, goodsName, smallNewPrice, mediumNewPrice, largeNewPrice, smallOldPrice, mediumOldPrice, largeOldPrice }]
const items = ref([])

// 价格组下拉（仅启用中）
const priceGroups = ref([])
async function loadPriceGroups() {
  try {
    const data = await post('/base/master/price-group/page', { pageNo: 1, pageSize: 200, filters: {} })
    priceGroups.value = (data.records || []).filter(r => r.enabled === true || r.enabled === 1)
  } catch (e) { priceGroups.value = [] }
}

// 商品选择器
const goodsPickerOpen = ref(false)
const goodsList = ref([])
const goodsSearch = ref('')
const selectedGoods = ref(new Set())
async function openGoodsPicker() {
  goodsPickerOpen.value = true
  selectedGoods.value = new Set()
  if (goodsList.value.length === 0) {
    try {
      const data = await post('/base/goods/page', { pageNo: 1, pageSize: 500, filters: {} })
      goodsList.value = data.records || []
    } catch (e) { goodsList.value = [] }
  }
}
const filteredGoods = computed(() => {
  const q = goodsSearch.value.trim().toLowerCase()
  if (!q) return goodsList.value
  return goodsList.value.filter(g =>
    (g.goodsCode || '').toLowerCase().includes(q) ||
    (g.goodsName || '').toLowerCase().includes(q) ||
    (g.simpleCode || '').toLowerCase().includes(q)
  )
})
function toggleGoods(code) {
  if (selectedGoods.value.has(code)) selectedGoods.value.delete(code)
  else selectedGoods.value.add(code)
}
function confirmGoodsPick() {
  const existing = new Set(items.value.map(i => i.goodsCode))
  goodsList.value.forEach(g => {
    if (selectedGoods.value.has(g.goodsCode) && !existing.has(g.goodsCode)) {
      items.value.push(makeItem(g.goodsCode, g.goodsName, g))
    }
  })
  goodsPickerOpen.value = false
}

// 从商品档案的 unitConfig JSON（索引 0=小 1=中 2=大）解出 3 单位的 enabled + standardPrice
function parseUnitProfile(g) {
  const out = {
    smallEnabled: true, mediumEnabled: false, largeEnabled: false,
    smallStandardPrice: null, mediumStandardPrice: null, largeStandardPrice: null,
  }
  if (!g) return out
  try {
    const cfg = typeof g.unitConfig === 'string' ? JSON.parse(g.unitConfig) : g.unitConfig
    if (Array.isArray(cfg)) {
      const keys = [
        ['smallEnabled', 'smallStandardPrice'],
        ['mediumEnabled', 'mediumStandardPrice'],
        ['largeEnabled', 'largeStandardPrice'],
      ]
      cfg.slice(0, 3).forEach((u, i) => {
        const [ek, pk] = keys[i]
        if (u && typeof u === 'object') {
          out[ek] = u.enabled !== false // 缺省视为启用
          const sp = Number(u.standardPrice)
          out[pk] = Number.isFinite(sp) ? sp : null
        }
      })
    }
  } catch (_) { /* 忽略解析错误 */ }
  // 兜底：小单位标价用 base_goods.standardPrice
  if (out.smallStandardPrice == null && g.standardPrice != null) {
    const sp = Number(g.standardPrice)
    out.smallStandardPrice = Number.isFinite(sp) ? sp : null
  }
  return out
}

function makeItem(code, name, goodsRow = null) {
  const p = parseUnitProfile(goodsRow)
  return {
    goodsCode: code, goodsName: name,
    smallNewPrice: null, mediumNewPrice: null, largeNewPrice: null,
    smallOldPrice: null, mediumOldPrice: null, largeOldPrice: null,
    smallEnabled: p.smallEnabled, mediumEnabled: p.mediumEnabled, largeEnabled: p.largeEnabled,
    smallStandardPrice: p.smallStandardPrice, mediumStandardPrice: p.mediumStandardPrice, largeStandardPrice: p.largeStandardPrice,
  }
}

function removeItem(i) { items.value.splice(i, 1) }

// Excel 导入
const fileInputRef = ref(null)
async function onImportFile(e) {
  const file = e.target.files?.[0]
  if (!file) return
  try {
    // 导入时保证商品档案已在 goodsList，便于按 code 匹配 unitConfig
    if (goodsList.value.length === 0) {
      try {
        const data = await post('/base/goods/page', { pageNo: 1, pageSize: 500, filters: {} })
        goodsList.value = data.records || []
      } catch (_) { /* ignore */ }
    }
    const goodsByCode = new Map(goodsList.value.map(g => [g.goodsCode, g]))
    const buf = await file.arrayBuffer()
    const wb = XLSX.read(buf, { type: 'array' })
    const ws = wb.Sheets[wb.SheetNames[0]]
    const rows = XLSX.utils.sheet_to_json(ws, { defval: '' })
    let added = 0, skipped = 0
    const existing = new Set(items.value.map(i => i.goodsCode))
    rows.forEach(r => {
      const code = String(r['商品编码'] || r.goodsCode || '').trim()
      if (!code) { skipped++; return }
      if (existing.has(code)) { skipped++; return }
      const g = goodsByCode.get(code) || null
      const name = String(r['商品名称'] || r.goodsName || (g?.goodsName) || '').trim()
      const it = makeItem(code, name, g)
      const sp = Number(r['小单位新价格'] ?? r.smallNewPrice)
      const mp = Number(r['中单位新价格'] ?? r.mediumNewPrice)
      const lp = Number(r['大单位新价格'] ?? r.largeNewPrice)
      if (Number.isFinite(sp)) it.smallNewPrice = sp
      if (Number.isFinite(mp)) it.mediumNewPrice = mp
      if (Number.isFinite(lp)) it.largeNewPrice = lp
      items.value.push(it)
      existing.add(code)
      added++
    })
    alert(`导入完成：新增 ${added} 条，跳过 ${skipped} 条（重复或缺编码）`)
  } catch (err) {
    alert('导入失败：' + (err?.message || '未知错误'))
  } finally {
    if (fileInputRef.value) fileInputRef.value.value = ''
  }
}

function downloadTemplate() {
  const data = [{ '商品编码': 'G001', '商品名称': '示例商品', '小单位新价格': 3.50, '中单位新价格': 42.00, '大单位新价格': 84.00 }]
  const ws = XLSX.utils.json_to_sheet(data)
  const wb = XLSX.utils.book_new()
  XLSX.utils.book_append_sheet(wb, ws, '调价单商品')
  XLSX.writeFile(wb, '调价单导入模板.xlsx')
}

// 加载详情
async function loadDetail(orderId) {
  try {
    const data = await post('/base/price-adjust-order/detail', { orderId })
    Object.keys(form.value).forEach(k => { if (data[k] !== undefined) form.value[k] = data[k] })
    // 补齐 goodsList 用于取 unit profile（enabled + standardPrice）
    if (goodsList.value.length === 0) {
      try {
        const gp = await post('/base/goods/page', { pageNo: 1, pageSize: 500, filters: {} })
        goodsList.value = gp.records || []
      } catch (_) { /* ignore */ }
    }
    const goodsByCode = new Map(goodsList.value.map(g => [g.goodsCode, g]))
    items.value = (data.items || []).map(it => {
      const p = parseUnitProfile(goodsByCode.get(it.goodsCode))
      return {
        goodsCode: it.goodsCode, goodsName: it.goodsName,
        smallNewPrice: it.smallNewPrice ?? null, mediumNewPrice: it.mediumNewPrice ?? null, largeNewPrice: it.largeNewPrice ?? null,
        smallOldPrice: it.smallOldPrice ?? null, mediumOldPrice: it.mediumOldPrice ?? null, largeOldPrice: it.largeOldPrice ?? null,
        smallEnabled: p.smallEnabled, mediumEnabled: p.mediumEnabled, largeEnabled: p.largeEnabled,
        smallStandardPrice: p.smallStandardPrice, mediumStandardPrice: p.mediumStandardPrice, largeStandardPrice: p.largeStandardPrice,
      }
    })
  } catch (e) { alert('加载详情失败：' + (e?.message || '')) }
}

watch(() => props.visible, (v) => {
  if (!v) return
  form.value = { orderId: '', orderNo: '', priceGroupCode: '', priceGroupName: '', remark: '', status: 'DRAFT', createUser: '', createTime: '', auditUser: '', auditTime: '', submitUser: '', submitTime: '', rejectReason: '' }
  items.value = []
  loadPriceGroups()
  if ((props.mode === 'edit' || props.mode === 'view') && props.orderData?.orderId) {
    loadDetail(props.orderData.orderId)
  }
})

const isReadonly = computed(() => props.mode === 'view' || form.value.status !== 'DRAFT')
const title = computed(() => {
  if (props.mode === 'view') return '查看调价单'
  if (props.mode === 'edit') return '编辑调价单'
  return '新建调价单'
})

function onGroupChange(e) {
  const code = e.target.value
  form.value.priceGroupCode = code
  const hit = priceGroups.value.find(g => g.priceGroupCode === code)
  form.value.priceGroupName = hit?.priceGroupName || ''
}

async function saveDraft(submit = false) {
  if (!form.value.priceGroupCode) { alert('请选择价格组'); return }
  if (items.value.length === 0) { alert('请添加至少 1 个商品'); return }
  // 校验每行至少填一个新价格
  const bad = items.value.find(it => (it.smallNewPrice == null || it.smallNewPrice === '') && (it.mediumNewPrice == null || it.mediumNewPrice === '') && (it.largeNewPrice == null || it.largeNewPrice === ''))
  if (bad) { alert(`商品【${bad.goodsCode}】三个单位价格至少填一个`); return }
  try {
    const payload = { orderId: form.value.orderId || undefined, priceGroupCode: form.value.priceGroupCode, remark: form.value.remark, items: items.value }
    const r = await post('/base/price-adjust-order/save', payload)
    const orderId = r?.orderId || form.value.orderId
    if (submit && orderId) {
      await post('/base/price-adjust-order/submit', { orderId })
    }
    emit('saved', { orderId })
    emit('close')
  } catch (e) {
    alert('保存失败：' + (e?.message || '未知错误'))
  }
}

async function approve() {
  if (!confirm(`审核通过【${form.value.orderNo}】？\n\n审核通过后价格立即生效并写入变价日志。`)) return
  try {
    await post('/base/price-adjust-order/approve', { orderId: form.value.orderId })
    emit('saved', {})
    emit('close')
  } catch (e) { alert('审核失败：' + (e?.message || '')) }
}
async function reject() {
  const reason = prompt('驳回原因：', '')
  if (reason === null) return
  try {
    await post('/base/price-adjust-order/reject', { orderId: form.value.orderId, rejectReason: reason })
    emit('saved', {})
    emit('close')
  } catch (e) { alert('驳回失败：' + (e?.message || '')) }
}

function close() { emit('close') }

const statusText = computed(() => ({ DRAFT: '草稿', PENDING: '待审核', APPROVED: '已审核', REJECTED: '已驳回' }[form.value.status] || form.value.status))
</script>

<template>
  <div v-show="visible" class="drawer-overlay drawer-lite" @click.self="close">
    <div class="modal-lite-box" style="width:min(1080px,96vw);height:100vh;max-height:100vh;border-radius:0">
      <div class="modal-lite-head">
        <b>
          {{ title }}
          <span v-if="form.orderNo" style="margin-left:10px;color:#606266;font-weight:500;font-size:12px">{{ form.orderNo }}</span>
          <span v-if="form.status" class="badge wait" style="margin-left:8px">{{ statusText }}</span>
        </b>
        <div class="actions">
          <button class="btn" @click="close">关闭</button>
          <template v-if="form.status === 'DRAFT' && props.mode !== 'view'">
            <button class="btn" @click="saveDraft(false)">保存草稿</button>
            <button class="btn primary" @click="saveDraft(true)">保存并提交审核</button>
          </template>
          <template v-else-if="form.status === 'PENDING' && props.mode !== 'view'">
            <button class="btn danger" @click="reject">驳回</button>
            <button class="btn primary" @click="approve">审核通过</button>
          </template>
        </div>
      </div>

      <div class="modal-lite-body" style="padding:14px;display:flex;flex-direction:column;gap:12px">
        <!-- 单据头 -->
        <div class="head-grid">
          <div class="field">
            <label>价格组 <span class="req">*</span></label>
            <select :value="form.priceGroupCode" :disabled="isReadonly" @change="onGroupChange">
              <option value="">请选择</option>
              <option v-for="g in priceGroups" :key="g.priceGroupCode" :value="g.priceGroupCode">{{ g.priceGroupCode }} {{ g.priceGroupName }}</option>
            </select>
          </div>
          <div class="field">
            <label>备注</label>
            <input v-model="form.remark" :disabled="isReadonly" placeholder="调价说明" />
          </div>
          <div class="field">
            <label>制单人 / 时间</label>
            <input :value="form.createUser ? `${form.createUser} / ${formatDateTime(form.createTime)}` : '(新建)'" readonly />
          </div>
          <div class="field" v-if="form.auditUser">
            <label>审核人 / 时间</label>
            <input :value="`${form.auditUser} / ${formatDateTime(form.auditTime)}`" readonly />
          </div>
          <div class="field field-full" v-if="form.status === 'REJECTED' && form.rejectReason">
            <label>驳回原因</label>
            <input :value="form.rejectReason" readonly style="color:#dc2626" />
          </div>
        </div>

        <!-- 明细工具栏 -->
        <div class="bar">
          <b style="margin-right:auto">商品明细（{{ items.length }}）</b>
          <template v-if="!isReadonly">
            <button class="btn" @click="downloadTemplate">下载模板</button>
            <label class="btn">
              导入 Excel
              <input ref="fileInputRef" type="file" accept=".xlsx,.xls" style="display:none" @change="onImportFile" />
            </label>
            <button class="btn primary" @click="openGoodsPicker">添加商品</button>
          </template>
        </div>

        <!-- 明细表 -->
        <div class="scroll" style="flex:1;min-height:0">
          <table class="detail-grid">
            <thead>
              <tr>
                <th style="width:40px">#</th>
                <th style="width:110px">商品编码</th>
                <th>商品名称</th>
                <th class="c price-col">小单位<br>标价</th>
                <th class="c price-col">小单位<br>现价</th>
                <th class="c price-col">大单位<br>标价</th>
                <th class="c price-col">大单位<br>现价</th>
                <th class="c price-col">中单位<br>标价</th>
                <th class="c price-col">中单位<br>现价</th>
                <th v-if="props.mode === 'view'" class="c price-col">小单位<br>原价</th>
                <th v-if="props.mode === 'view'" class="c price-col">大单位<br>原价</th>
                <th v-if="props.mode === 'view'" class="c price-col">中单位<br>原价</th>
                <th v-if="!isReadonly" style="width:56px">操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-if="items.length === 0">
                <td :colspan="isReadonly ? 12 : 10" style="text-align:center;color:#909399;padding:24px">暂无明细，点击右上角【添加商品】或【导入 Excel】</td>
              </tr>
              <tr v-for="(it, idx) in items" :key="idx">
                <td>{{ idx + 1 }}</td>
                <td>{{ it.goodsCode }}</td>
                <td>{{ it.goodsName }}</td>
                <!-- 小 -->
                <td class="c std" :class="{ off: !it.smallEnabled }">{{ it.smallStandardPrice != null ? Number(it.smallStandardPrice).toFixed(2) : '—' }}</td>
                <td class="c">
                  <input v-model.number="it.smallNewPrice" :disabled="isReadonly || !it.smallEnabled" type="number" step="0.01" placeholder="—" class="price-input" />
                </td>
                <!-- 大 -->
                <td class="c std" :class="{ off: !it.largeEnabled }">{{ it.largeStandardPrice != null ? Number(it.largeStandardPrice).toFixed(2) : '—' }}</td>
                <td class="c">
                  <input v-model.number="it.largeNewPrice" :disabled="isReadonly || !it.largeEnabled" type="number" step="0.01" placeholder="—" class="price-input" />
                </td>
                <!-- 中 -->
                <td class="c std" :class="{ off: !it.mediumEnabled }">{{ it.mediumStandardPrice != null ? Number(it.mediumStandardPrice).toFixed(2) : '—' }}</td>
                <td class="c">
                  <input v-model.number="it.mediumNewPrice" :disabled="isReadonly || !it.mediumEnabled" type="number" step="0.01" placeholder="—" class="price-input" />
                </td>
                <!-- 只读原价（仅 view 展示） -->
                <td v-if="props.mode === 'view'" class="c old">{{ it.smallOldPrice ?? '-' }}</td>
                <td v-if="props.mode === 'view'" class="c old">{{ it.largeOldPrice ?? '-' }}</td>
                <td v-if="props.mode === 'view'" class="c old">{{ it.mediumOldPrice ?? '-' }}</td>
                <td v-if="!isReadonly"><button class="link link-btn danger-link" @click="removeItem(idx)">删除</button></td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
    </div>

    <!-- 商品选择器 -->
    <div v-if="goodsPickerOpen" class="drawer-overlay" @click.self="goodsPickerOpen = false">
      <div class="modal-lite-box" style="width:min(680px,92vw);max-height:80vh">
        <div class="modal-lite-head">
          <b>选择商品（已选 {{ selectedGoods.size }}）</b>
          <div class="actions">
            <button class="btn" @click="goodsPickerOpen = false">取消</button>
            <button class="btn primary" :disabled="selectedGoods.size === 0" @click="confirmGoodsPick">添加所选</button>
          </div>
        </div>
        <div class="modal-lite-body">
          <input v-model="goodsSearch" placeholder="搜索商品编码 / 名称 / 简拼" style="width:100%;padding:6px 10px;margin-bottom:8px" />
          <div class="scroll" style="max-height:52vh">
            <table>
              <tr><th style="width:40px"></th><th>编码</th><th>名称</th><th>规格</th></tr>
              <tr v-for="g in filteredGoods" :key="g.goodsCode" @click="toggleGoods(g.goodsCode)" style="cursor:pointer">
                <td><input type="checkbox" :checked="selectedGoods.has(g.goodsCode)" @click.stop="toggleGoods(g.goodsCode)" /></td>
                <td>{{ g.goodsCode }}</td>
                <td>{{ g.goodsName }}</td>
                <td>{{ g.spec || '-' }}</td>
              </tr>
              <tr v-if="filteredGoods.length === 0"><td colspan="4" style="text-align:center;color:#909399;padding:20px">无匹配商品</td></tr>
            </table>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.head-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 10px 16px;
}
.head-grid .field {
  display: grid;
  grid-template-columns: 96px 1fr;
  gap: 4px 10px;
  align-items: center;
}
.head-grid .field label {
  text-align: right;
  font-size: 12px;
  color: #606266;
  font-weight: 600;
}
.head-grid .field.field-full {
  grid-column: 1 / -1;
  grid-template-columns: 96px 1fr;
}
.head-grid .field input,
.head-grid .field select {
  height: 30px;
  padding: 0 8px;
  border: 1px solid #dcdfe6;
  border-radius: 4px;
  font-size: 12px;
}
.head-grid .field .req { color: #dc2626; margin-left: 2px; }
.bar {
  display: flex;
  align-items: center;
  gap: 8px;
}
table { border-collapse: collapse; width: 100%; }
th, td { border-bottom: 1px solid #eef3f8; padding: 6px 8px; font-size: 12px; text-align: left; }
th { background: #f7fbff; }
td input {
  width: 100%;
  height: 26px;
  padding: 0 6px;
  border: 1px solid #dcdfe6;
  border-radius: 4px;
  font-size: 12px;
}
td input:disabled { background: #f5f7fa; color: #606266; }

/* 明细表：小/大/中 × 标价/现价 六列紧凑布局 */
.detail-grid { width: 100%; }
.detail-grid th.price-col, .detail-grid td.c { width: 78px; padding: 4px 6px; text-align: center; }
.detail-grid th.price-col { font-weight: 600; line-height: 1.2; }
.detail-grid td.std { color: #303133; }
.detail-grid td.std.off { color: #c0c4cc; background: #f8fafc; }
.detail-grid td.old { color: #909399; }
.detail-grid td .price-input {
  width: 100%;
  height: 26px;
  padding: 0 6px;
  border: 1px solid #dcdfe6;
  border-radius: 3px;
  font-size: 12px;
  text-align: right;
  box-sizing: border-box;
}
.detail-grid td .price-input:disabled { background: #f5f7fa; color: #909399; cursor: not-allowed; }
</style>
