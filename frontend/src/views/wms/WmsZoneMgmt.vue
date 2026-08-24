<script setup>
/**
 * WMS V1.5 库区管理（V83 重构，PRD-28）
 * 左侧：仓库信息中所有实物仓类型的仓库（取自 base_warehouse，默认按仓库编码排序）
 * 右侧：选中仓库下的库区，支持新建/编辑/冻结/删除
 */
import { ref, computed, onMounted } from 'vue'
import { get, post } from '../../api/client.js'

const warehouses = ref([])
const currentWarehouse = ref('')
const zones = ref([])
const loading = ref(false)
const feedback = ref('')

const ZONE_TYPES = [
  { v: 'STORAGE', l: '储区' },
  { v: 'RECEIVE', l: '收货区' },
  { v: 'SHIP', l: '发货区' },
  { v: 'PICK', l: '拣货区' },
  { v: 'SORT', l: '分拣区' },
  // COLLECT(集货区) 已独立到「集货区管理」模块，此处不再创建/展示
]
const zoneTypeText = Object.fromEntries(ZONE_TYPES.map(t => [t.v, t.l]))

// V84 拣货方式（库区级，替代仓库级 WMS_PICK_MODE 单一默认值）
const PICK_METHODS = [
  { v: '', l: '未配置（走系统默认）' },
  { v: 'CASE_PICK', l: '整件单拣' },
  { v: 'CASE_MERGE', l: '整件合拣' },
  { v: 'BULK_PICK', l: '拆零单拣' },
  { v: 'BULK_MERGE', l: '拆零合拣' },
]
const pickMethodText = Object.fromEntries(PICK_METHODS.map(m => [m.v, m.l]))
const SORT_MODES = [
  { v: 'PICK_THEN_SORT', l: '先拣后分（汇总拣货到分拣区再分播）' },
  { v: 'SORT_WHILE_PICK', l: '边拣边分（拣货同时按格口分播）' },
]
const sortModeText = Object.fromEntries(SORT_MODES.map(m => [m.v, m.l]))
// 存储属性 = 商品档案 storage_property 值域（温区）
const STORAGE_PROPERTIES = ['常温', '冷藏', '冷冻', '恒温', '避光']

// 是否拣货/储区：拣货方式字段只对这两类有意义，其它类型禁用并清空
function isPickCapable(zoneType) {
  return zoneType === 'PICK' || zoneType === 'STORAGE'
}

const dialogOpen = ref(false)
const dialogTitle = ref('')
const form = ref(blankForm())

function blankForm() {
  return {
    zoneId: '', zoneCode: '', zoneName: '', zoneType: 'STORAGE',
    pickMethod: '', sortMode: '', storageProperty: '',
    pickSeq: 0, remark: '',
  }
}

// 切换库区类型时，非拣货/储区清空拣货方式
function onZoneTypeChange() {
  if (!isPickCapable(form.value.zoneType)) {
    form.value.pickMethod = ''
    form.value.sortMode = ''
  }
}
// 切换拣货方式：单拣清空分拣方式；合拣默认先拣后分
function onPickMethodChange() {
  if (!form.value.pickMethod.endsWith('_MERGE')) {
    form.value.sortMode = ''
  } else if (!form.value.sortMode) {
    form.value.sortMode = 'PICK_THEN_SORT'
  }
}
function show(msg, level = 'ok') {
  feedback.value = { level, msg }
  setTimeout(() => (feedback.value = ''), 2500)
}

async function loadWarehouses() {
  try {
    warehouses.value = (await get('/wms/warehouse/list')) || []
    if (!currentWarehouse.value && warehouses.value.length) {
      currentWarehouse.value = warehouses.value[0].warehouseName
    }
  } catch (e) { show('仓库列表加载失败：' + (e?.message || e), 'err') }
}

async function loadZones() {
  if (!currentWarehouse.value) { zones.value = []; return }
  loading.value = true
  try {
    const res = await post('/wms/zone/page', {
      pageNo: 1, pageSize: 500,
      filters: { warehouse: currentWarehouse.value },
    })
    // 集货区(COLLECT)已独立到「集货区管理」，本页不展示
    zones.value = (res.records || []).filter(z => z.zoneType !== 'COLLECT')
  } catch (e) { show('库区加载失败：' + (e?.message || e), 'err') }
  finally { loading.value = false }
}

function selectWarehouse(whName) {
  currentWarehouse.value = whName
  loadZones()
}

function onCreate() {
  if (!currentWarehouse.value) return show('请先选择左侧仓库', 'err')
  form.value = blankForm()
  dialogTitle.value = '新建库区（' + currentWarehouse.value + '）'
  dialogOpen.value = true
}
function onEdit(row) {
  form.value = {
    zoneId: row.zoneId, zoneCode: row.zoneCode, zoneName: row.zoneName,
    zoneType: row.zoneType,
    pickMethod: row.pickMethod || '',
    sortMode: row.sortMode || '',
    storageProperty: row.storageProperty || '',
    pickSeq: row.pickSeq || 0, remark: row.remark || '',
  }
  dialogTitle.value = '编辑库区：' + row.zoneCode
  dialogOpen.value = true
}
async function onSave() {
  if (!form.value.zoneCode.trim()) return show('库区编码不能为空', 'err')
  if (!form.value.zoneName.trim()) return show('库区名称不能为空', 'err')
  try {
    await post('/wms/zone/save', { ...form.value, warehouse: currentWarehouse.value })
    show('保存成功')
    dialogOpen.value = false
    loadZones()
  } catch (e) { show('保存失败：' + (e?.message || e), 'err') }
}
async function onToggle(row) {
  const action = row.frozen === 'Y' ? '解冻' : '冻结'
  if (!confirm(`确认${action}库区 ${row.zoneCode}？`)) return
  try {
    await post('/wms/zone/toggle', { zoneId: row.zoneId, frozen: row.frozen })
    show(action + '成功')
    loadZones()
  } catch (e) { show(action + '失败：' + (e?.message || e), 'err') }
}
async function onDelete(row) {
  if (!confirm(`确认删除库区 ${row.zoneCode}？删除后不可恢复。`)) return
  try {
    await post('/wms/zone/delete', { zoneId: row.zoneId })
    show('已删除')
    loadZones()
  } catch (e) { show('删除失败：' + (e?.message || e), 'err') }
}

const currentWarehouseObj = computed(() =>
  warehouses.value.find(w => w.warehouseName === currentWarehouse.value))

onMounted(async () => {
  await loadWarehouses()
  await loadZones()
})
</script>

<template>
  <div class="module-body">
    <div class="page-ops">
      <b>库区管理</b>
      <span class="tip">左侧选择仓库，右侧维护库区；冻结后该区不允许新的入/出库任务落到此区。</span>
      <div class="spacer"></div>
      <button class="btn" @click="loadZones">刷新</button>
      <button class="btn primary" :disabled="!currentWarehouse" @click="onCreate">＋ 新建库区</button>
    </div>
    <div v-if="feedback" class="toast-inline" :class="feedback.level">{{ feedback.msg }}</div>

    <div class="split">
      <!-- 左：仓库列表 -->
      <aside class="left">
        <div class="left-h">仓库（{{ warehouses.length }}）</div>
        <div class="left-scroll">
          <div v-for="w in warehouses" :key="w.warehouseId"
               class="wh-item" :class="{ on: w.warehouseName === currentWarehouse }"
               @click="selectWarehouse(w.warehouseName)">
            <div class="wh-name">{{ w.warehouseName }}
              <span class="wh-type">{{ w.warehouseType || '未分类' }}</span>
            </div>
            <div class="wh-meta">编码 {{ w.warehouseCode }} · 库区 {{ w.zoneCount || 0 }}</div>
          </div>
          <div v-if="!warehouses.length" class="empty">暂无仓库（请先在「基础资料-仓库信息」中维护）</div>
        </div>
      </aside>

      <!-- 右：库区列表 -->
      <section class="right">
        <div class="right-h">
          <span v-if="currentWarehouseObj">
            <b>{{ currentWarehouseObj.warehouseName }}</b>
            <span class="muted">（{{ currentWarehouseObj.warehouseType || '未分类' }}）的库区</span>
          </span>
          <span v-else class="muted">请在左侧选择仓库</span>
          <div class="spacer"></div>
          <span v-if="loading" class="muted">加载中...</span>
        </div>
        <div class="right-scroll">
          <table class="data">
            <thead>
              <tr>
                <th>库区编码</th><th>名称</th><th>类型</th>
                <th>拣货方式</th><th>存储属性</th>
                <th class="num">拣货顺序</th><th class="num">库位数</th>
                <th>状态</th><th>备注</th><th class="ops">操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="z in zones" :key="z.zoneId">
                <td><b class="zone">{{ z.zoneCode }}</b></td>
                <td>{{ z.zoneName }}</td>
                <td>{{ zoneTypeText[z.zoneType] || z.zoneType }}</td>
                <td>
                  <span v-if="z.pickMethod">{{ pickMethodText[z.pickMethod] || z.pickMethod }}
                    <em v-if="z.sortMode" class="sub">{{ sortModeText[z.sortMode] }}</em>
                  </span>
                  <span v-else class="muted">—</span>
                </td>
                <td>
                  <span v-if="z.storageProperty" class="storagetag" :class="'sp-' + z.storageProperty">{{ z.storageProperty }}</span>
                  <span v-else class="muted">—</span>
                </td>
                <td class="num">{{ z.pickSeq }}</td>
                <td class="num">{{ z.binCount || 0 }}</td>
                <td><span class="tag" :class="z.frozen==='Y'?'warn':'ok'">
                  {{ z.frozen==='Y' ? '已冻结' : '正常' }}</span></td>
                <td class="muted">{{ z.remark || '—' }}</td>
                <td class="ops">
                  <a @click="onEdit(z)">编辑</a>
                  <a @click="onToggle(z)">{{ z.frozen==='Y' ? '解冻' : '冻结' }}</a>
                  <a class="danger" @click="onDelete(z)">删除</a>
                </td>
              </tr>
              <tr v-if="!zones.length"><td colspan="10" class="empty">该仓库下暂无库区，点右上角「新建库区」</td></tr>
            </tbody>
          </table>
        </div>
      </section>
    </div>

    <!-- 新建/编辑弹窗 -->
    <div v-if="dialogOpen" class="modal-mask" @click.self="dialogOpen=false">
      <div class="modal w600">
        <div class="modal-h">{{ dialogTitle }}<span class="x" @click="dialogOpen=false">×</span></div>
        <div class="modal-b">
          <div class="form-row"><label>库区编码 *</label>
            <input v-model="form.zoneCode" placeholder="如 A / PICK / COL" :disabled="!!form.zoneId" /></div>
          <div class="form-row"><label>库区名称 *</label>
            <input v-model="form.zoneName" placeholder="如 整箱储区 / 拣货区" /></div>
          <div class="form-row"><label>库区类型</label>
            <select v-model="form.zoneType" @change="onZoneTypeChange">
              <option v-for="t in ZONE_TYPES" :key="t.v" :value="t.v">{{ t.l }}</option>
            </select></div>
          <div class="form-row"><label>拣货方式</label>
            <select v-model="form.pickMethod"
                    :disabled="!isPickCapable(form.zoneType)"
                    @change="onPickMethodChange"
                    :title="isPickCapable(form.zoneType) ? '' : '仅拣货区/储区可配置拣货方式'">
              <option v-for="m in PICK_METHODS" :key="m.v" :value="m.v">{{ m.l }}</option>
            </select>
          </div>
          <div class="form-row indent" v-if="form.pickMethod.endsWith('_MERGE')">
            <label>合拣分拣方式</label>
            <select v-model="form.sortMode">
              <option v-for="s in SORT_MODES" :key="s.v" :value="s.v">{{ s.l }}</option>
            </select>
          </div>
          <div class="form-row indent hint-row" v-if="form.pickMethod.endsWith('_MERGE')">
            <span></span>
            <span class="hint">合拣时，该区任务会把多张订单的同品合并拣货，再按此方式分播到订单。</span>
          </div>
          <div class="form-row"><label>存储属性（温区）</label>
            <select v-model="form.storageProperty">
              <option value="">未指定（不限温区）</option>
              <option v-for="sp in STORAGE_PROPERTIES" :key="sp" :value="sp">{{ sp }}</option>
            </select>
          </div>
          <div class="form-row indent hint-row">
            <span></span>
            <span class="hint">对应商品档案的「存储属性」。入库收货/上架时，系统按此温区推荐库区；同一温区可建多个库区（如常温整箱、常温拆零）。</span>
          </div>
          <div class="form-row"><label>拣货路径顺序</label>
            <input v-model.number="form.pickSeq" type="number" min="0" placeholder="数字越小越先拣" /></div>
          <div class="form-row"><label>备注</label>
            <input v-model="form.remark" placeholder="可留空" /></div>
        </div>
        <div class="modal-f">
          <button class="btn" @click="dialogOpen=false">取消</button>
          <button class="btn primary" @click="onSave">保存</button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.tip { color: #874d00; background: #fffbe6; border: 1px solid #ffe58f; padding: 4px 10px; border-radius: 6px; font-size: 12px; margin-left: 10px; }
.spacer { flex: 1; }
.split { display: grid; grid-template-columns: 280px 1fr; gap: 12px; margin-top: 10px; height: calc(100vh - 200px); min-height: 480px; }
.left, .right { background: #fff; border: 1px solid #f0f0f0; border-radius: 8px; display: flex; flex-direction: column; overflow: hidden; }
.left-h, .right-h { padding: 10px 14px; border-bottom: 1px solid #f0f0f0; background: #fafafa; font-size: 13px; display: flex; align-items: center; }
.left-scroll, .right-scroll { overflow: auto; flex: 1; }
.wh-item { padding: 10px 14px; border-bottom: 1px solid #f5f5f5; cursor: pointer; }
.wh-item:hover { background: #fafcff; }
.wh-item.on { background: #e6f4ff; border-left: 3px solid #1677ff; padding-left: 11px; }
.wh-name { font-weight: 600; font-size: 13px; display: flex; align-items: center; gap: 8px; }
.wh-type { font-weight: 400; font-size: 11px; color: #1677ff; background: #e6f4ff; padding: 1px 7px; border-radius: 10px; }
.wh-meta { color: #999; font-size: 12px; margin-top: 3px; }
.muted { color: #999; font-size: 12px; }
.data { width: 100%; border-collapse: collapse; font-size: 13px; }
.data th, .data td { padding: 8px 10px; border-bottom: 1px solid #f0f0f0; text-align: left; }
.data th { background: #fafafa; font-weight: 600; color: #555; }
.num { text-align: right; }
.zone { color: #1677ff; font-family: monospace; }
.tag { display: inline-block; padding: 1px 8px; border-radius: 10px; font-size: 11px; }
.tag.ok { background: #f6ffed; color: #389e0d; }
.tag.warn { background: #fff1f0; color: #cf1322; }
.ops { white-space: nowrap; text-align: right; }
.ops a { color: #1677ff; cursor: pointer; margin-left: 10px; font-size: 12px; }
.ops a.danger { color: #cf1322; }
.empty { text-align: center; color: #bbb; padding: 30px; font-size: 13px; }
.modal-mask { position: fixed; inset: 0; background: rgba(0,0,0,.4); display: flex; align-items: center; justify-content: center; z-index: 100; }
.modal { background: #fff; border-radius: 10px; width: 560px; max-width: 92vw; max-height: 90vh; overflow: auto; }
.modal.w520 { width: 520px; }
.modal.w600 { width: 600px; }
.modal-h { padding: 14px 18px; font-weight: 700; border-bottom: 1px solid #f0f0f0; display: flex; justify-content: space-between; }
.modal-h .x { cursor: pointer; color: #999; }
.modal-b { padding: 16px 18px; }
.modal-f { padding: 12px 18px; border-top: 1px solid #f0f0f0; text-align: right; }
.modal-f .btn { margin-left: 8px; }
.form-row { display: flex; align-items: center; gap: 10px; margin-bottom: 12px; }
.form-row label { width: 110px; color: #555; font-size: 13px; }
.form-row input, .form-row select { flex: 1; padding: 6px 10px; border: 1px solid #d9d9d9; border-radius: 6px; }
.form-row.indent { margin-left: 40px; }
.form-row.indent label { width: 110px; }
.hint-row { margin-bottom: 16px; }
.hint-row .hint { color: #999; font-size: 12px; line-height: 1.5; }
.form-row em.sub { display: block; color: #1677ff; font-style: normal; font-size: 11px; margin-top: 2px; }
.storagetag { display: inline-block; padding: 1px 8px; border-radius: 10px; font-size: 11px; background: #f5f5f5; color: #555; }
.storagetag.sp-冷藏 { background: #e6f7ff; color: #096dd9; }
.storagetag.sp-冷冻 { background: #e6fffb; color: #08979c; }
.storagetag.sp-恒温 { background: #fff7e6; color: #d46b08; }
.storagetag.sp-避光 { background: #f9f0ff; color: #531dab; }
.toast-inline { padding: 8px 12px; border-radius: 6px; margin-bottom: 10px; font-size: 13px; background: #f6ffed; border: 1px solid #b7eb8f; color: #389e0d; }
.toast-inline.err { background: #fff1f0; border-color: #ffa39e; color: #cf1322; }
</style>
