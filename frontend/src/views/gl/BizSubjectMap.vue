<script setup>
/**
 * 总账 M3——业务类型科目映射
 * 其他出/入库类型继续留在 sys_dictionary（库存模块零改动），本页维护"类型 → 对方科目"。
 * 对方科目留空 = 该类型不生成凭证（如期初库存）；字典里有值但未配映射的类型红字提示。
 */
import { ref, onMounted } from 'vue'
import { post, saveTextFile } from '../../api/client.js'

const groups = ref([])
const leafAccounts = ref([])
const feedback = ref('')
const dirty = ref({})

function show(msg, level = 'ok') {
  feedback.value = { level, msg }
  setTimeout(() => (feedback.value = ''), 4000)
}

async function load() {
  try {
    groups.value = await post('/finance/gl/biz-subject-map/list', {})
  } catch (e) { show('加载失败：' + (e?.message || e), 'err') }
}
async function loadMeta() {
  try { leafAccounts.value = (await post('/finance/gl/account/leaf-options', {})) || [] }
  catch (e) { /* 静默 */ }
}

function markDirty(eventCode, row) {
  dirty.value[eventCode + ':' + row.bizTypeCode] = row.counterSubjectCode || ''
}

async function save(eventCode, row) {
  try {
    await post('/finance/gl/biz-subject-map/save', {
      eventCode,
      bizTypeCode: row.bizTypeCode,
      bizTypeName: row.bizTypeName,
      counterSubjectCode: row.counterSubjectCode || '',
    })
    delete dirty.value[eventCode + ':' + row.bizTypeCode]
    show(`「${row.bizTypeName}」映射已保存`)
    await load()
  } catch (e) { show(e?.message || String(e), 'err') }
}

function accountName(code) {
  const a = leafAccounts.value.find(x => x.accountCode === code)
  return a ? a.accountName : ''
}

// M9：导出科目对照配置（当前页面已加载的全部映射，CSV 带 BOM 供 Excel 直开）
function csvCell(v) {
  const s = (v === null || v === undefined) ? '' : String(v)
  return /[",\n]/.test(s) ? '"' + s.replace(/"/g, '""') + '"' : s
}
function exportCsv() {
  const lines = [['事件码', '事件名称', '类型编码', '类型名称', '对方科目编码', '对方科目名称', '配置状态'].map(csvCell).join(',')]
  for (const g of groups.value) {
    for (const row of (g.rows || [])) {
      const code = row.counterSubjectCode || ''
      const status = code ? '已配置' : (row.dictMissing ? '字典已删' : '未配置(不生成凭证)')
      lines.push([g.eventCode, g.eventName, row.bizTypeCode, row.bizTypeName, code, code ? accountName(code) : '', status].map(csvCell).join(','))
    }
  }
  saveTextFile(`业务类型科目对照-${new Date().toISOString().slice(0, 10)}.csv`, String.fromCharCode(0xFEFF) + lines.join('\r\n'), 'text/csv;charset=UTF-8')
}

onMounted(() => { loadMeta(); load() })
</script>

<template>
  <div class="module-body">
    <div v-if="feedback" :class="['toast-inline', feedback.level]">{{ feedback.msg }}</div>
    <div class="page-ops">
      <b>业务类型科目映射</b>
      <div class="spacer"></div>
      <button class="btn" v-permission="'finance.gl.voucher.export'" @click="exportCsv">导出对照配置（CSV）</button>
    </div>
    <div class="card-box tip-box">
      业务类型（其他出/入库类型）仍在「系统管理→数据字典」维护，本页只配置每种类型生成凭证时的<b>对方科目</b>。
      对方科目留空表示该类型<b>不生成凭证</b>（如「期初库存」）。字典新增类型后若未配置，事件生成时会失败并提示来此补配。
    </div>

    <div v-for="g in groups" :key="g.eventCode" class="card-box">
      <div class="group-h">{{ g.eventName }}（事件码 {{ g.eventCode }}）</div>
      <table class="data">
        <thead>
          <tr>
            <th style="width:90px">类型编码</th>
            <th style="width:180px">类型名称</th>
            <th>对方科目（末级）</th>
            <th style="width:220px">科目名称</th>
            <th style="width:90px">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="row in g.rows" :key="row.bizTypeCode" :class="{ 'row-unmapped': row.unmapped }">
            <td>{{ row.bizTypeCode }}</td>
            <td>
              {{ row.bizTypeName }}
              <span v-if="row.unmapped" class="tag tag-red">未配置</span>
              <span v-if="row.dictMissing" class="tag tag-gray">字典已删</span>
              <span v-if="row.isSystem" class="tag tag-blue">预置</span>
            </td>
            <td>
              <select :value="row.counterSubjectCode || ''" @change="row.counterSubjectCode = $event.target.value; markDirty(g.eventCode, row)">
                <option value="">（不生成凭证）</option>
                <option v-for="a in leafAccounts" :key="a.accountCode" :value="a.accountCode">
                  {{ a.accountCode }} {{ a.accountName }}
                </option>
              </select>
            </td>
            <td>{{ row.counterSubjectCode ? accountName(row.counterSubjectCode) : '—' }}</td>
            <td>
              <a class="lk" v-permission="'finance.gl.biz_subject_map.edit'" @click="save(g.eventCode, row)">保存</a>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>

<style scoped>
.page-ops { display: flex; align-items: center; gap: 10px; margin-bottom: 10px; }
.spacer { flex: 1; }
.tip-box { background: #f7faff; color: #555; font-size: 13px; line-height: 1.7; }
.group-h { font-weight: 600; padding: 4px 0 10px; }
.tag { display: inline-block; padding: 0 8px; border-radius: 10px; font-size: 11px; margin-left: 6px; }
.tag-red { background: #fdeaea; color: #d33; }
.tag-gray { background: #f0f0f0; color: #888; }
.tag-blue { background: #e8f1fd; color: #1d6fd1; }
.row-unmapped { background: #fff7f7; }
.lk { color: #1d6fd1; cursor: pointer; }
select { min-width: 260px; }
</style>
