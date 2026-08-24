<script setup>
import { ref, computed, onMounted } from 'vue'
import { get, post } from '../../api/client.js'

// TMS / 公共参数仍用前端硬编码控件类型（这些参数值仍是 Y/N 等格式，未数字化）
const BOOL_KEYS = new Set([
  'TMS_DRIVER_FLOW_ENABLED', 'TMS_RETURN_MERGE_SETTLE', 'TMS_SIGN_ESIGN_REQUIRED',
  'TMS_SETTLE_PHOTO_REQUIRED', 'TMS_ACCEPT_BEFORE_SETTLE', 'TMS_ONSITE_RETURN_ENABLED',
  'TMS_HANDOVER_ESIGN_REQUIRED', 'TMS_APPEND_AFTER_DEPART',
  'TMS_ARRIVE_REQUIRED', 'TMS_ARRIVE_PHOTO_REQUIRED', 'TMS_EXCEPTION_PHOTO_REQUIRED',
  'TMS_PUSH_ENABLED', 'STOCK_NEGATIVE_ALLOWED',
])
const PHOTO_COUNT_KEYS = new Set(['TMS_SIGN_PHOTO_COUNT', 'TMS_RETURN_PHOTO_COUNT'])
const ACCOUNT_KEYS = new Set(['TMS_OFFSET_FUND_ACCOUNT'])
// 参数已落库但功能实现排在后续版本，界面置灰只读
const PENDING_KEYS = new Set([])

const groups = ref([])
const offsetAccounts = ref([])
const activeGroup = ref('')
const dirty = ref(false)
const loading = ref(false)
const feedback = ref('')

const currentItems = computed(() => {
  const g = groups.value.find(x => x.groupName === activeGroup.value)
  return g ? g.items : []
})

function show(msg) { feedback.value = msg; setTimeout(() => { feedback.value = '' }, 2200) }

/**
 * 判断控件类型：
 * - WMS 参数（paramType 非空）由后端元数据驱动
 * - TMS/公共参数沿用前端硬编码
 */
function ctlType(item) {
  // WMS 参数：以后端 paramType 为准
  if (item.paramType) return item.paramType.toLowerCase()
  const key = item.paramKey
  if (BOOL_KEYS.has(key)) return 'bool'
  if (PHOTO_COUNT_KEYS.has(key)) return 'count'
  if (ACCOUNT_KEYS.has(key)) return 'account'
  return 'text'
}

/** 解析后端下发的选项 JSON */
function options(item) {
  if (!item.optionJson) return []
  try { return JSON.parse(item.optionJson) } catch { return [] }
}

async function load() {
  loading.value = true
  try {
    const data = await get('/system/param/setting')
    groups.value = (data?.groups || []).map(g => ({
      groupName: g.groupName,
      items: (g.items || []).map(it => ({
        ...it,
        value: it.paramValue === null || it.paramValue === undefined ? (it.defaultValue ?? '') : String(it.paramValue),
      })),
    }))
    offsetAccounts.value = data?.offsetAccounts || []
    if (!activeGroup.value || !groups.value.some(g => g.groupName === activeGroup.value)) {
      const wms = groups.value.find(g => g.groupName === 'WMS出库')
      const tms = groups.value.find(g => g.groupName === 'TMS配送')
      activeGroup.value = wms ? wms.groupName : (tms ? tms.groupName : (groups.value[0]?.groupName || ''))
    }
    dirty.value = false
  } catch (e) {
    groups.value = []
    show(e.message || '参数加载失败')
  } finally {
    loading.value = false
  }
}

function pickGroup(name) {
  if (name === activeGroup.value) return
  if (dirty.value && !confirm('当前分组有未保存的修改，切换后将丢失，确定继续？')) return
  activeGroup.value = name
  if (dirty.value) load()
}

function onChange(item, value) {
  item.value = String(value)
  dirty.value = true
}

function clampCount(item) {
  const n = parseInt(item.value, 10)
  item.value = String(Number.isNaN(n) ? (item.defaultValue ?? '2') : Math.min(5, Math.max(0, n)))
}

function clampNumber(item) {
  const n = Number(item.value)
  if (Number.isNaN(n)) { item.value = item.defaultValue ?? '0'; return }
  const min = item.minValue != null ? Number(item.minValue) : -Infinity
  const max = item.maxValue != null ? Number(item.maxValue) : Infinity
  item.value = String(Math.min(max, Math.max(min, n)))
}

async function save() {
  const items = currentItems.value
  for (const it of items) {
    if (PHOTO_COUNT_KEYS.has(it.paramKey)) {
      const n = parseInt(it.value, 10)
      if (Number.isNaN(n) || n < 0 || n > 5) { show(`「${it.paramName}」必须是 0~5 的整数`); return }
    }
    if (it.paramKey === 'TMS_OFFSET_FUND_ACCOUNT' && !it.value) {
      const merge = items.find(x => x.paramKey === 'TMS_RETURN_MERGE_SETTLE')
      if (merge && merge.value === 'Y') { show('已开启退货合并结算，必须先指定销退冲抵资金账户'); return }
    }
    // WMS NUMBER 类型范围校验
    if (it.paramType === 'NUMBER') {
      const n = Number(it.value)
      if (Number.isNaN(n)) { show(`「${it.paramName}」必须是数字`); return }
      if (it.minValue != null && n < Number(it.minValue)) { show(`「${it.paramName}」不能小于 ${it.minValue}`); return }
      if (it.maxValue != null && n > Number(it.maxValue)) { show(`「${it.paramName}」不能大于 ${it.maxValue}`); return }
    }
  }
  loading.value = true
  try {
    const data = await post('/system/param/batch-update', {
      items: items.map(it => ({ paramKey: it.paramKey, paramValue: it.value })),
    })
    show(`保存成功，已更新 ${data?.updated ?? items.length} 项`)
    await load()
  } catch (e) {
    show(e.message || '保存失败')
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="param-page">
    <aside class="groups">
      <div
        v-for="g in groups"
        :key="g.groupName"
        class="group"
        :class="{ on: g.groupName === activeGroup }"
        @click="pickGroup(g.groupName)"
      >{{ g.groupName }}</div>
    </aside>

    <main class="param-main">
      <div class="bar">
        <button class="btn primary" :disabled="loading || !currentItems.length" @click="save">保 存</button>
        <button class="btn" :disabled="loading" @click="load">刷 新</button>
        <span v-if="dirty" class="dirty">当前分组有未保存的修改</span>
      </div>

      <div v-if="!currentItems.length" class="empty">
        {{ loading ? '加载中…' : '该分组暂无参数' }}
      </div>

      <div v-for="item in currentItems" :key="item.paramKey" class="item">
        <div class="lab" :class="{ off: PENDING_KEYS.has(item.paramKey) }">{{ item.paramName }}</div>
        <div class="ctl">
          <!-- WMS BOOL: 是/否 单选，值 1/0 -->
          <template v-if="ctlType(item) === 'bool'">
            <label class="rd" :class="{ sel: item.value === '1' || item.value === 'Y', dis: PENDING_KEYS.has(item.paramKey) }">
              <input
                type="radio" :name="item.paramKey" value="1"
                :checked="item.value === '1' || item.value === 'Y'" :disabled="PENDING_KEYS.has(item.paramKey)"
                @change="onChange(item, '1')"
              ><span>是</span>
            </label>
            <label class="rd" :class="{ sel: item.value !== '1' && item.value !== 'Y', dis: PENDING_KEYS.has(item.paramKey) }">
              <input
                type="radio" :name="item.paramKey" value="0"
                :checked="item.value !== '1' && item.value !== 'Y'" :disabled="PENDING_KEYS.has(item.paramKey)"
                @change="onChange(item, '0')"
              ><span>否</span>
            </label>
          </template>

          <!-- WMS SELECT: 下拉选项来自后端 option_json -->
          <template v-else-if="ctlType(item) === 'select'">
            <select :value="item.value" :disabled="PENDING_KEYS.has(item.paramKey)"
                    @change="onChange(item, $event.target.value)">
              <option v-for="opt in options(item)" :key="opt.value" :value="opt.value">{{ opt.label }}</option>
            </select>
          </template>

          <!-- WMS NUMBER: 数字输入，带 min/max/unit -->
          <template v-else-if="ctlType(item) === 'number'">
            <input
              type="number"
              class="num"
              :min="item.minValue ?? undefined"
              :max="item.maxValue ?? undefined"
              :step="item.step ?? 1"
              :value="item.value"
              :disabled="PENDING_KEYS.has(item.paramKey)"
              @input="onChange(item, $event.target.value)"
              @blur="clampNumber(item)"
            >
            <span v-if="item.unit" class="unit">{{ item.unit }}</span>
          </template>

          <!-- TMS 照片张数（0~5） -->
          <template v-else-if="ctlType(item) === 'count'">
            <input
              type="number" min="0" max="5" class="num"
              :value="item.value"
              @input="onChange(item, $event.target.value)"
              @blur="clampCount(item)"
            >
            <span class="unit">张（0~5，0 表示不校验）</span>
          </template>

          <!-- TMS 冲抵账户 -->
          <template v-else-if="ctlType(item) === 'account'">
            <select :value="item.value" @change="onChange(item, $event.target.value)">
              <option value="">请选择资金账户</option>
              <option v-for="a in offsetAccounts" :key="a.code" :value="a.code">
                {{ a.name }}（{{ a.code }}）
              </option>
            </select>
          </template>

          <!-- TEXT / fallback -->
          <template v-else>
            <input type="text" class="txt" :value="item.value"
                   :disabled="PENDING_KEYS.has(item.paramKey)"
                   @input="onChange(item, $event.target.value)">
          </template>

          <span v-if="PENDING_KEYS.has(item.paramKey)" class="tag">后续版本支持</span>
        </div>
        <div v-if="item.remark" class="hint">{{ item.remark }}</div>
      </div>
    </main>

    <div v-if="feedback" class="toast">{{ feedback }}</div>
  </div>
</template>

<style scoped>
.param-page { height: 100%; background: #fff; display: grid; grid-template-columns: 220px 1fr; }
.groups { border-right: 2px solid #e5eaf2; padding: 14px 0; background: #fff; }
.group { height: 46px; display: flex; align-items: center; padding: 0 0 0 28px; color: #4b5563; cursor: pointer; }
.group:hover { background: #f5f6f8; }
.group.on { background: #eaf2ff; color: #1677ff; font-weight: 700; }
.param-main { padding: 20px 30px; overflow: auto; min-width: 0; }
.bar { margin-bottom: 24px; display: flex; align-items: center; gap: 12px; }
.btn { height: 34px; border: 1px solid #dbe3ef; background: #fff; border-radius: 4px; padding: 0 20px; cursor: pointer; font-size: 14px; }
.btn:disabled { color: #9aa4b2; cursor: not-allowed; }
.btn.primary { background: #1677ff; color: #fff; border-color: #1677ff; font-weight: 600; }
.btn.primary:disabled { background: #a3c8ff; border-color: #a3c8ff; color: #fff; }
.dirty { color: #d46b08; font-size: 13px; }
.item { display: grid; grid-template-columns: 230px 1fr; gap: 0 18px; margin-bottom: 20px; }
.lab { text-align: right; font-weight: 700; padding-top: 7px; color: #1f2937; }
.lab.off { color: #9aa4b2; }
.ctl { min-height: 34px; display: flex; align-items: center; gap: 22px; flex-wrap: wrap; }
.hint { grid-column: 2; color: #667085; font-size: 13px; margin-top: 6px; }
select, input.num, input.txt { height: 34px; border: 1px solid #dbe3ef; border-radius: 4px; padding: 0 10px; font-size: 14px; color: #1f2937; background: #fff; }
select { width: 430px; }
input.num { width: 120px; }
input.txt { width: 430px; }
select:disabled, input:disabled { background: #f5f6f8; color: #9aa4b2; }
.unit { color: #667085; font-size: 13px; margin-left: -12px; }
.rd { display: flex; align-items: center; gap: 7px; cursor: pointer; user-select: none; }
.rd input { margin: 0; width: 15px; height: 15px; accent-color: #1677ff; }
.rd.sel span { color: #1677ff; font-weight: 600; }
.rd.dis { cursor: not-allowed; }
.rd.dis span { color: #9aa4b2; }
.tag { display: inline-block; padding: 1px 7px; border-radius: 3px; background: #fff7e6; border: 1px solid #ffd591; color: #d46b08; font-size: 12px; }
.empty { color: #9aa4b2; padding: 40px 0; }
.toast { position: fixed; right: 20px; bottom: 20px; background: #101828; color: #fff; border-radius: 8px; padding: 12px 16px; z-index: 3000; }
</style>
