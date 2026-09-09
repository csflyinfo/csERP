<script setup>
/**
 * 总账 M9——自动转账模板维护。
 * 期末在「期末处理」里预览/执行；本页维护模板本身：编号/名称/执行条件/启停/分录公式。
 * 系统预置模板（ZZ01 结转增值税、ZZ02 计提附加税费）可改可停用、不可删除。
 * 公式：QM/QC/FSD/FSC/LJFS/LJFSC/CF/CFY(科目或项目) + 四则运算；
 * 执行条件变量：taxpayer（GENERAL/SMALL）、surtax_rate（小数）。
 */
import { ref, onMounted } from 'vue'
import { post } from '../../api/client.js'

const list = ref([])
const leafAccounts = ref([])
const feedback = ref('')
const loading = ref(false)
const drawer = ref({ open: false })
const form = ref(blankForm())

function blankForm() {
  return {
    id: '', transferNo: '', transferName: '', conditionExpr: '',
    enabled: true, remark: '',
    entries: [blankEntry('借'), blankEntry('贷')],
  }
}
function blankEntry(direction = '借') {
  return { direction, summary: '', accountCode: '', amountExpr: '', auxConfig: '' }
}
function show(msg, level = 'ok') {
  feedback.value = { level, msg }
  setTimeout(() => (feedback.value = ''), 5000)
}

async function load() {
  loading.value = true
  try {
    list.value = (await post('/finance/gl/transfer/list')) || []
  } catch (e) { show('模板加载失败：' + (e?.message || e), 'err') }
  finally { loading.value = false }
}
async function loadMeta() {
  try {
    leafAccounts.value = (await post('/finance/gl/account/leaf-options', {})) || []
  } catch (e) { show('科目加载失败：' + (e?.message || e), 'err') }
}

function openCreate() {
  form.value = blankForm()
  drawer.value = { open: true }
}
function openEdit(row) {
  form.value = {
    id: row.id,
    transferNo: row.transferNo,
    transferName: row.transferName || '',
    conditionExpr: row.conditionExpr || '',
    enabled: !!row.enabled,
    remark: row.remark || '',
    entries: (row.entries || []).map(e => ({
      direction: e.direction || '借',
      summary: e.summary || '',
      accountCode: e.accountCode || '',
      amountExpr: e.amountExpr || '',
      auxConfig: e.auxConfig || '',
    })),
  }
  drawer.value = { open: true }
}
function addEntry() { form.value.entries.push(blankEntry('借')) }
function removeEntry(i) {
  if (form.value.entries.length <= 2) return show('转账模板至少保留 2 条分录', 'err')
  form.value.entries.splice(i, 1)
}

async function onSave() {
  if (!form.value.transferName.trim()) return show('请填写模板名称', 'err')
  if (form.value.entries.length < 2) return show('至少需要 2 条分录（有借有贷）', 'err')
  for (const [i, e] of form.value.entries.entries()) {
    if (!e.accountCode) return show(`第 ${i + 1} 条分录请选择科目`, 'err')
    if (!e.amountExpr.trim()) return show(`第 ${i + 1} 条分录请填写金额公式`, 'err')
  }
  try {
    await post('/finance/gl/transfer/save', {
      id: form.value.id,
      transferNo: form.value.transferNo,
      transferName: form.value.transferName,
      conditionExpr: form.value.conditionExpr,
      enabled: form.value.enabled,
      remark: form.value.remark,
      entries: form.value.entries.map(e => ({
        direction: e.direction, summary: e.summary, accountCode: e.accountCode,
        amountExpr: e.amountExpr, auxConfig: e.auxConfig || null,
      })),
    })
    show('模板已保存（公式已通过试算校验）')
    drawer.value.open = false
    await load()
  } catch (e) { show('保存失败：' + (e?.message || e), 'err') }
}

async function onToggle(row) {
  try {
    await post('/finance/gl/transfer/toggle', { id: row.id, enabled: !row.enabled })
    await load()
  } catch (e) { show('操作失败：' + (e?.message || e), 'err') }
}
async function onDelete(row) {
  if (!window.confirm(`确认删除转账模板「${row.transferName}」？删除后不可恢复。`)) return
  try {
    await post('/finance/gl/transfer/delete', { id: row.id })
    show('模板已删除')
    await load()
  } catch (e) { show('删除失败：' + (e?.message || e), 'err') }
}

onMounted(() => { load(); loadMeta() })
</script>

<template>
  <div class="module-body">
    <div class="page-ops">
      <b>自动转账模板</b>
      <span class="muted small">期末处理 → 自动转账按启用模板生成「转」字凭证（零金额/借贷不平自动跳过）</span>
      <div class="spacer"></div>
      <button class="btn primary" v-permission="'finance.gl.transfer_template.add'" @click="openCreate">＋ 新增模板</button>
    </div>
    <div v-if="feedback" class="toast-inline" :class="feedback.level">{{ feedback.msg }}</div>

    <div class="card-box">
      <table class="data">
        <thead>
          <tr><th style="width:80px">编号</th><th>模板名称</th><th style="width:240px">执行条件</th>
              <th style="width:70px">分录数</th><th style="width:80px">状态</th><th style="width:70px">预置</th>
              <th style="width:200px">操作</th></tr>
        </thead>
        <tbody>
          <tr v-for="r in list" :key="r.id">
            <td><b class="code">{{ r.transferNo }}</b></td>
            <td>{{ r.transferName }}<div v-if="r.remark" class="muted small">{{ r.remark }}</div></td>
            <td class="small muted">{{ r.conditionExpr || '（无条件，每期执行）' }}</td>
            <td class="num">{{ (r.entries || []).length }}</td>
            <td><span class="tag" :class="r.enabled ? 'ok' : 'info'">{{ r.enabled ? '启用中' : '已停用' }}</span></td>
            <td><span v-if="r.isSystem" class="tag sys">系统</span></td>
            <td class="ops">
              <a v-permission="'finance.gl.transfer_template.add'" @click="openEdit(r)">编辑</a>
              <a v-permission="'finance.gl.transfer_template.edit'" @click="onToggle(r)">{{ r.enabled ? '停用' : '启用' }}</a>
              <a v-if="!r.isSystem" v-permission="'finance.gl.transfer_template.delete'" class="danger" @click="onDelete(r)">删除</a>
            </td>
          </tr>
          <tr v-if="!list.length"><td colspan="7" class="empty">{{ loading ? '加载中...' : '暂无转账模板' }}</td></tr>
        </tbody>
      </table>
    </div>

    <!-- 编辑抽屉 -->
    <div v-if="drawer.open" class="drawer-mask" @click.self="drawer.open=false">
      <div class="drawer">
        <div class="drawer-h">
          <b>{{ form.id ? '编辑转账模板' : '新增转账模板' }}</b>
          <span class="x" @click="drawer.open=false">×</span>
        </div>
        <div class="drawer-body">
          <div class="form-grid">
            <label>模板编号
              <input v-model="form.transferNo" placeholder="留空自动编号 ZZ03" :disabled="!!form.id" />
            </label>
            <label>模板名称 *
              <input v-model="form.transferName" placeholder="如：结转未交增值税" />
            </label>
            <label class="full">执行条件（可空）
              <input v-model="form.conditionExpr" placeholder="如 taxpayer == 'GENERAL'；变量：taxpayer、surtax_rate" />
            </label>
            <label class="full">备注
              <input v-model="form.remark" />
            </label>
            <label class="check">
              <input type="checkbox" v-model="form.enabled" /> 启用（停用后期末处理不执行）
            </label>
          </div>

          <div class="entries-title">
            <b>分录设置</b>
            <span class="muted small">金额公式取数函数：QM 期末余额 / QC 期初余额 / FSD 本期借方发生 / FSC 本期贷方发生 / LJFS·LJFSC 本年累计 / CF 现金流量项目；支持 + - * / 与括号，如 FSC('22210102')-FSD('22210101')</span>
          </div>
          <table class="data entry-table">
            <thead>
              <tr><th style="width:60px">方向</th><th style="width:200px">摘要</th><th style="width:240px">科目</th>
                  <th>金额公式</th><th style="width:60px"></th></tr>
            </thead>
            <tbody>
              <tr v-for="(e, i) in form.entries" :key="i">
                <td>
                  <select v-model="e.direction">
                    <option value="借">借</option>
                    <option value="贷">贷</option>
                  </select>
                </td>
                <td><input v-model="e.summary" placeholder="分录摘要" /></td>
                <td>
                  <select v-model="e.accountCode">
                    <option value="">请选择末级科目</option>
                    <option v-for="a in leafAccounts" :key="a.accountCode" :value="a.accountCode">
                      {{ a.accountCode }} {{ a.accountName }}
                    </option>
                  </select>
                </td>
                <td><input v-model="e.amountExpr" placeholder="如 FSC('5403')*0.12" /></td>
                <td><a class="danger del-link" @click="removeEntry(i)">删</a></td>
              </tr>
            </tbody>
          </table>
          <button class="btn small add-btn" @click="addEntry">＋ 增行</button>
        </div>
        <div class="drawer-f">
          <button class="btn" @click="drawer.open=false">取消</button>
          <button class="btn primary" v-permission="'finance.gl.transfer_template.add'" @click="onSave">保存模板</button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.spacer { flex: 1; }
.small { font-size: 12px; }
.muted { color: #999; }
.code { font-family: monospace; color: #1677ff; }
.card-box { background: #fff; border: 1px solid #f0f0f0; border-radius: 8px; overflow: auto; margin-top: 10px; }
.data { width: 100%; border-collapse: collapse; font-size: 13px; }
.data th, .data td { padding: 7px 10px; border-bottom: 1px solid #f0f0f0; text-align: left; vertical-align: middle; }
.data th { background: #fafafa; font-weight: 600; color: #555; }
.num { text-align: right; }
.ops a { margin-right: 10px; color: #1677ff; cursor: pointer; font-size: 12px; }
.ops a.danger, .del-link { color: #cf1322; cursor: pointer; }
.tag { display: inline-block; padding: 1px 8px; border-radius: 10px; font-size: 11px; }
.tag.ok { background: #f6ffed; color: #389e0d; }
.tag.info { background: #f0f0f0; color: #888; }
.tag.sys { background: #e6f4ff; color: #1677ff; }
.empty { text-align: center; color: #bbb; padding: 30px; font-size: 13px; }
.toast-inline { padding: 8px 12px; border-radius: 6px; margin-top: 10px; font-size: 13px; background: #f6ffed; border: 1px solid #b7eb8f; color: #389e0d; }
.toast-inline.err { background: #fff1f0; border-color: #ffa39e; color: #cf1322; }
.drawer-mask { position: fixed; inset: 0; background: rgba(0,0,0,.35); z-index: 100; display: flex; justify-content: flex-end; }
.drawer { width: 820px; max-width: 96vw; background: #fff; height: 100%; display: flex; flex-direction: column; }
.drawer-h { padding: 14px 18px; border-bottom: 1px solid #f0f0f0; display: flex; justify-content: space-between; align-items: center; }
.x { cursor: pointer; font-size: 20px; color: #999; }
.drawer-body { flex: 1; overflow: auto; padding: 16px 18px; }
.drawer-f { padding: 12px 18px; border-top: 1px solid #f0f0f0; display: flex; justify-content: flex-end; gap: 10px; }
.form-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }
.form-grid label { display: flex; flex-direction: column; gap: 5px; font-size: 13px; color: #555; }
.form-grid label.full { grid-column: 1 / -1; }
.form-grid label.check { grid-column: 1 / -1; flex-direction: row; align-items: center; }
.form-grid input, .form-grid select { padding: 6px 9px; border: 1px solid #d9d9d9; border-radius: 6px; font-size: 13px; }
.entries-title { margin: 18px 0 8px; display: flex; flex-direction: column; gap: 4px; }
.entry-table td { padding: 4px 6px; }
.entry-table select, .entry-table input { width: 100%; box-sizing: border-box; padding: 5px 7px; border: 1px solid #d9d9d9; border-radius: 6px; font-size: 13px; }
.add-btn { margin-top: 8px; }
.btn.small { padding: 4px 12px; font-size: 12px; }
</style>
