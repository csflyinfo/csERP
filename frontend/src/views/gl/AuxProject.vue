<script setup>
/**
 * 总账 M1——核算项目（项目档案）
 * 辅助核算 7 维中，客户/供应商/部门/员工/商品/片区复用主数据，项目维度由本页维护。
 */
import { ref, onMounted } from 'vue'
import { post } from '../../api/client.js'

const list = ref([])
const keyword = ref('')
const statusFilter = ref('')
const loading = ref(false)
const feedback = ref('')

const dialogOpen = ref(false)
const dialogTitle = ref('')
const form = ref(blank())
function blank() {
  return { id: '', projectCode: '', projectName: '', projectType: '', startDate: '', endDate: '',
    budgetAmount: 0, ownerName: '', status: '启用', remark: '' }
}

function show(msg, level = 'ok') {
  feedback.value = { level, msg }
  setTimeout(() => (feedback.value = ''), 3000)
}

async function load() {
  loading.value = true
  try {
    list.value = (await post('/finance/gl/aux-project/list', {
      keyword: keyword.value, status: statusFilter.value,
    })) || []
  } catch (e) { show('加载失败：' + (e?.message || e), 'err') }
  finally { loading.value = false }
}

function onCreate() { form.value = blank(); dialogTitle.value = '新增核算项目'; dialogOpen.value = true }
function onEdit(row) {
  form.value = {
    id: row.id, projectCode: row.projectCode, projectName: row.projectName,
    projectType: row.projectType || '', startDate: (row.startDate || '').slice(0, 10),
    endDate: (row.endDate || '').slice(0, 10), budgetAmount: row.budget || 0,
    ownerName: row.ownerName || '', status: row.status || '启用', remark: row.remark || '',
  }
  dialogTitle.value = '编辑核算项目'
  dialogOpen.value = true
}

async function onSave() {
  if (!form.value.projectCode.trim()) return show('项目编码不能为空', 'err')
  if (!form.value.projectName.trim()) return show('项目名称不能为空', 'err')
  try {
    if (form.value.id) await post('/finance/gl/aux-project/update', form.value)
    else await post('/finance/gl/aux-project/create', form.value)
    show('保存成功')
    dialogOpen.value = false
    load()
  } catch (e) { show('保存失败：' + (e?.message || e), 'err') }
}

onMounted(load)
</script>

<template>
  <div class="module-body">
    <div class="page-ops">
      <b>核算项目</b>
      <span class="tip">项目维度档案，用于科目辅助核算（如「在建工程/促销专项」按项目归集）</span>
      <div class="spacer"></div>
      <input v-model="keyword" class="search" placeholder="编码/名称" @keyup.enter="load" />
      <select v-model="statusFilter" @change="load">
        <option value="">全部状态</option>
        <option value="启用">启用</option>
        <option value="停用">停用</option>
      </select>
      <button class="btn" @click="load">查询</button>
      <button class="btn primary" @click="onCreate">＋ 新增项目</button>
    </div>
    <div v-if="feedback" class="toast-inline" :class="feedback.level">{{ feedback.msg }}</div>

    <div class="card-box">
      <table class="data">
        <thead>
          <tr>
            <th style="width:130px">项目编码</th><th>项目名称</th><th style="width:110px">项目类型</th>
            <th style="width:110px">负责人</th><th class="num" style="width:130px">预算（元）</th>
            <th style="width:200px">起止日期</th><th style="width:70px">状态</th>
            <th style="width:160px">备注</th><th class="ops" style="width:90px">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="r in list" :key="r.id" :class="{ off: r.status === '停用' }">
            <td><b class="code">{{ r.projectCode }}</b></td>
            <td>{{ r.projectName }}</td>
            <td>{{ r.projectType || '—' }}</td>
            <td>{{ r.ownerName || '—' }}</td>
            <td class="num">{{ (Number(r.budget || 0)).toLocaleString('zh-CN', { minimumFractionDigits: 2 }) }}</td>
            <td class="muted">{{ r.startDate || '?' }} ~ {{ r.endDate || '?' }}</td>
            <td><span class="tag" :class="r.status === '启用' ? 'ok' : 'warn'">{{ r.status }}</span></td>
            <td class="muted">{{ r.remark || '—' }}</td>
            <td class="ops"><a @click="onEdit(r)">编辑</a></td>
          </tr>
          <tr v-if="!list.length"><td colspan="9" class="empty">{{ loading ? '加载中...' : '暂无项目' }}</td></tr>
        </tbody>
      </table>
    </div>

    <div v-if="dialogOpen" class="modal-mask" @click.self="dialogOpen=false">
      <div class="modal w600">
        <div class="modal-h">{{ dialogTitle }}<span class="x" @click="dialogOpen=false">×</span></div>
        <div class="modal-b">
          <div class="form-grid">
            <div class="form-row"><label>项目编码 *</label>
              <input v-model="form.projectCode" :disabled="!!form.id" placeholder="如 P2026001" /></div>
            <div class="form-row"><label>项目名称 *</label>
              <input v-model="form.projectName" placeholder="如 2026 秋季促销专项" /></div>
            <div class="form-row"><label>项目类型</label>
              <input v-model="form.projectType" placeholder="如 市场活动 / 在建工程" /></div>
            <div class="form-row"><label>负责人</label>
              <input v-model="form.ownerName" /></div>
            <div class="form-row"><label>预算金额（元）</label>
              <input v-model.number="form.budgetAmount" type="number" step="0.01" /></div>
            <div class="form-row"><label>状态</label>
              <select v-model="form.status"><option value="启用">启用</option><option value="停用">停用</option></select></div>
            <div class="form-row"><label>开始日期</label>
              <input v-model="form.startDate" type="date" /></div>
            <div class="form-row"><label>结束日期</label>
              <input v-model="form.endDate" type="date" /></div>
            <div class="form-row full"><label>备注</label>
              <input v-model="form.remark" /></div>
          </div>
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
.search { padding: 6px 10px; border: 1px solid #d9d9d9; border-radius: 6px; width: 160px; }
.page-ops select { padding: 6px 8px; border: 1px solid #d9d9d9; border-radius: 6px; }
.card-box { background: #fff; border: 1px solid #f0f0f0; border-radius: 8px; overflow: auto; margin-top: 10px; }
.data { width: 100%; border-collapse: collapse; font-size: 13px; }
.data th, .data td { padding: 8px 10px; border-bottom: 1px solid #f0f0f0; text-align: left; }
.data th { background: #fafafa; font-weight: 600; color: #555; }
.data tr.off { color: #bbb; }
.num { text-align: right; }
.code { font-family: monospace; color: #1677ff; }
.muted { color: #999; font-size: 12px; }
.tag { display: inline-block; padding: 1px 8px; border-radius: 10px; font-size: 11px; }
.tag.ok { background: #f6ffed; color: #389e0d; }
.tag.warn { background: #f5f5f5; color: #999; }
.ops { white-space: nowrap; text-align: right; }
.ops a { color: #1677ff; cursor: pointer; font-size: 12px; }
.empty { text-align: center; color: #bbb; padding: 30px; font-size: 13px; }
.modal-mask { position: fixed; inset: 0; background: rgba(0,0,0,.4); display: flex; align-items: center; justify-content: center; z-index: 100; }
.modal { background: #fff; border-radius: 10px; width: 560px; max-width: 92vw; max-height: 90vh; overflow: auto; }
.modal.w600 { width: 600px; }
.modal-h { padding: 14px 18px; font-weight: 700; border-bottom: 1px solid #f0f0f0; display: flex; justify-content: space-between; }
.modal-h .x { cursor: pointer; color: #999; }
.modal-b { padding: 16px 18px; }
.modal-f { padding: 12px 18px; border-top: 1px solid #f0f0f0; text-align: right; }
.modal-f .btn { margin-left: 8px; }
.form-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 0 16px; }
.form-row { display: flex; align-items: center; gap: 10px; margin-bottom: 12px; }
.form-row.full { grid-column: 1 / -1; }
.form-row label { width: 90px; color: #555; font-size: 13px; flex-shrink: 0; }
.form-row input, .form-row select { flex: 1; padding: 6px 10px; border: 1px solid #d9d9d9; border-radius: 6px; }
.form-row input:disabled { background: #f5f5f5; color: #999; }
.toast-inline { padding: 8px 12px; border-radius: 6px; margin-bottom: 10px; font-size: 13px; background: #f6ffed; border: 1px solid #b7eb8f; color: #389e0d; }
.toast-inline.err { background: #fff1f0; border-color: #ffa39e; color: #cf1322; }
</style>
