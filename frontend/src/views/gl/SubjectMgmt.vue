<script setup>
/**
 * 总账 M1——会计科目管理
 * 科目体系执行《小企业会计准则》(2013)，编码 4-2-2-2；末级科目才能录凭证/期初。
 * 系统预置科目不可改编码/类别/方向，可停用；停用前须先停用下级。
 */
import { ref, computed, onMounted } from 'vue'
import { post } from '../../api/client.js'

const accounts = ref([])
const loading = ref(false)
const feedback = ref('')

const AUX_DIMS = [
  { v: 'customer', l: '客户' },
  { v: 'supplier', l: '供应商' },
  { v: 'department', l: '部门' },
  { v: 'employee', l: '员工' },
  { v: 'goods', l: '商品' },
  { v: 'project', l: '项目' },
  { v: 'area', l: '片区' },
]
const auxLabel = Object.fromEntries(AUX_DIMS.map(d => [d.v, d.l]))
const TYPE_COLORS = { 资产: '#1677ff', 负债: '#cf1322', 权益: '#722ed1', 成本: '#d46b08', 损益: '#389e0d' }

function auxText(dims) {
  if (!dims) return ''
  return dims.split(',').map(d => auxLabel[d] || d).join('、')
}

const dialogOpen = ref(false)
const dialogTitle = ref('')
const form = ref(blankForm())
function blankForm() {
  return {
    id: '', accountCode: '', accountName: '', parentCode: '', parentName: '',
    auxDimensions: [], isQty: false, isCash: false, remark: '',
    isSystem: false, accountType: '',
  }
}

function show(msg, level = 'ok') {
  feedback.value = { level, msg }
  setTimeout(() => (feedback.value = ''), 3000)
}

async function load() {
  loading.value = true
  try {
    accounts.value = (await post('/finance/gl/account/list')) || []
  } catch (e) { show('科目加载失败：' + (e?.message || e), 'err') }
  finally { loading.value = false }
}

function onCreateTop() {
  form.value = blankForm()
  dialogTitle.value = '新增一级科目'
  dialogOpen.value = true
}
function onCreateChild(row) {
  form.value = { ...blankForm(), parentCode: row.accountCode, parentName: row.accountName, accountType: row.accountType }
  dialogTitle.value = `在 ${row.accountCode} ${row.accountName} 下新增下级`
  dialogOpen.value = true
}
function onEdit(row) {
  form.value = {
    id: row.id, accountCode: row.accountCode, accountName: row.accountName,
    parentCode: row.parentCode || '', parentName: '',
    auxDimensions: row.auxDimensions ? row.auxDimensions.split(',') : [],
    isQty: !!row.isQty, isCash: !!row.isCash, remark: row.remark || '',
    isSystem: !!row.isSystem, accountType: row.accountType,
  }
  dialogTitle.value = `编辑科目 ${row.accountCode} ${row.accountName}`
  dialogOpen.value = true
}

async function onSave() {
  if (!form.value.accountName.trim()) return show('科目名称不能为空', 'err')
  const body = {
    id: form.value.id || undefined,
    accountName: form.value.accountName.trim(),
    auxDimensions: form.value.auxDimensions.join(','),
    isQty: form.value.isQty,
    isCash: form.value.isCash,
    remark: form.value.remark,
  }
  try {
    if (form.value.id) {
      await post('/finance/gl/account/update', body)
    } else {
      body.accountCode = form.value.accountCode.trim()
      body.parentCode = form.value.parentCode
      await post('/finance/gl/account/create', body)
    }
    show('保存成功')
    dialogOpen.value = false
    load()
  } catch (e) { show('保存失败：' + (e?.message || e), 'err') }
}

async function onToggle(row) {
  const disabling = row.status === '启用'
  if (!confirm(disabling ? `确认停用科目 ${row.accountCode} ${row.accountName}？停用后不能再录凭证。` : `确认启用科目 ${row.accountCode}？`)) return
  try {
    await post('/finance/gl/account/toggle-status', { id: row.id })
    show(disabling ? '已停用' : '已启用')
    load()
  } catch (e) { show((disabling ? '停用' : '启用') + '失败：' + (e?.message || e), 'err') }
}

const stats = computed(() => ({
  total: accounts.value.length,
  leaf: accounts.value.filter(a => a.isLeaf).length,
  disabled: accounts.value.filter(a => a.status === '停用').length,
}))

onMounted(load)
</script>

<template>
  <div class="module-body">
    <div class="page-ops">
      <b>会计科目</b>
      <span class="tip">共 {{ stats.total }} 个科目（末级 {{ stats.leaf }}，停用 {{ stats.disabled }}）；编码规则 4-2-2-2，末级科目才能录凭证</span>
      <div class="spacer"></div>
      <button class="btn" @click="load">刷新</button>
      <button class="btn primary" v-permission="'finance.gl.account.add'" @click="onCreateTop">＋ 新增一级科目</button>
    </div>
    <div v-if="feedback" class="toast-inline" :class="feedback.level">{{ feedback.msg }}</div>

    <div class="card-box">
      <table class="data">
        <thead>
          <tr>
            <th style="width:200px">科目编码</th><th>科目名称</th><th style="width:70px">类别</th>
            <th style="width:50px">方向</th><th style="width:160px">辅助核算</th>
            <th style="width:90px">数量/现金</th><th style="width:60px">状态</th><th class="ops" style="width:200px">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="a in accounts" :key="a.accountCode" :class="{ disabled: a.status === '停用' }">
            <td>
              <span :style="{ paddingLeft: ((a.accountLevel - 1) * 18) + 'px' }" class="code-cell">
                <span v-if="!a.isLeaf" class="folder">📁</span>
                <b class="code">{{ a.accountCode }}</b>
              </span>
            </td>
            <td>{{ a.accountName }}
              <em v-if="a.isSystem" class="sys-tag">预置</em>
            </td>
            <td><span class="type-tag" :style="{ color: TYPE_COLORS[a.accountType] }">{{ a.accountType }}</span></td>
            <td>{{ a.balanceDirection }}</td>
            <td class="muted">{{ auxText(a.auxDimensions) || '—' }}</td>
            <td>
              <span v-if="a.isQty" class="flag">数量</span>
              <span v-if="a.isCash" class="flag cash">现金</span>
              <span v-if="!a.isQty && !a.isCash" class="muted">—</span>
            </td>
            <td><span class="tag" :class="a.status === '启用' ? 'ok' : 'warn'">{{ a.status }}</span></td>
            <td class="ops">
              <a v-if="a.accountLevel < 4" v-permission="'finance.gl.account.add'" @click="onCreateChild(a)">新增下级</a>
              <a v-permission="'finance.gl.account.edit'" @click="onEdit(a)">编辑</a>
              <a v-permission="'finance.gl.account.edit'" :class="{ danger: a.status === '启用' }" @click="onToggle(a)">{{ a.status === '启用' ? '停用' : '启用' }}</a>
            </td>
          </tr>
          <tr v-if="!accounts.length"><td colspan="8" class="empty">{{ loading ? '加载中...' : '暂无科目' }}</td></tr>
        </tbody>
      </table>
    </div>

    <!-- 新增/编辑弹窗 -->
    <div v-if="dialogOpen" class="modal-mask" @click.self="dialogOpen=false">
      <div class="modal w640">
        <div class="modal-h">{{ dialogTitle }}<span class="x" @click="dialogOpen=false">×</span></div>
        <div class="modal-b">
          <div class="form-row">
            <label>上级科目</label>
            <input :value="form.parentCode ? form.parentCode + ' ' + form.parentName : '（一级科目）'" disabled />
          </div>
          <div class="form-row">
            <label>科目编码 *</label>
            <input v-model="form.accountCode" :disabled="!!form.id || !!form.parentCode"
                   :placeholder="form.parentCode ? '留空自动生成（上级编码+两位序号）' : '4 位数字，如 1001'" />
          </div>
          <div v-if="!form.id && !form.parentCode" class="form-row indent hint-row">
            <span></span>
            <span class="hint">一级科目编码首位决定类别：1 资产 / 2 负债 / 3 权益 / 4 成本 / 5 损益；余额方向按准则自动确定。</span>
          </div>
          <div class="form-row">
            <label>科目名称 *</label>
            <input v-model="form.accountName" placeholder="如 工行基本户 / 办公费" />
          </div>
          <div class="form-row align-top">
            <label>辅助核算</label>
            <div class="checks">
              <label v-for="d in AUX_DIMS" :key="d.v" class="chk">
                <input type="checkbox" :value="d.v" v-model="form.auxDimensions" :disabled="form.isSystem" />
                {{ d.l }}
              </label>
            </div>
          </div>
          <div class="form-row indent hint-row">
            <span></span>
            <span class="hint">勾选后，该科目凭证/期初必须按辅助维度录入（如应收账款按客户）。预置科目的辅助维度不可改。</span>
          </div>
          <div class="form-row">
            <label>核算标志</label>
            <div class="checks">
              <label class="chk"><input type="checkbox" v-model="form.isQty" /> 数量核算（如库存商品，录数量单价）</label>
              <label class="chk"><input type="checkbox" v-model="form.isCash" /> 现金类科目（现金流量表取数）</label>
            </div>
          </div>
          <div class="form-row">
            <label>备注</label>
            <input v-model="form.remark" placeholder="可留空" />
          </div>
        </div>
        <div class="modal-f">
          <button class="btn" @click="dialogOpen=false">取消</button>
          <button class="btn primary"
                  v-permission="['finance.gl.account.add', 'finance.gl.account.edit']"
                  @click="onSave">保存</button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.tip { color: #874d00; background: #fffbe6; border: 1px solid #ffe58f; padding: 4px 10px; border-radius: 6px; font-size: 12px; margin-left: 10px; }
.spacer { flex: 1; }
.card-box { background: #fff; border: 1px solid #f0f0f0; border-radius: 8px; overflow: auto; margin-top: 10px; }
.data { width: 100%; border-collapse: collapse; font-size: 13px; }
.data th, .data td { padding: 7px 10px; border-bottom: 1px solid #f0f0f0; text-align: left; white-space: nowrap; }
.data th { background: #fafafa; font-weight: 600; color: #555; position: sticky; top: 0; }
.data tr.disabled { color: #bbb; }
.code { font-family: monospace; color: #1677ff; }
.folder { margin-right: 4px; font-size: 12px; }
.sys-tag { font-style: normal; font-size: 10px; color: #999; border: 1px solid #ddd; border-radius: 8px; padding: 0 6px; margin-left: 6px; }
.type-tag { font-size: 12px; font-weight: 600; }
.muted { color: #999; font-size: 12px; }
.flag { display: inline-block; font-size: 11px; background: #f0f5ff; color: #1677ff; border-radius: 8px; padding: 0 7px; margin-right: 4px; }
.flag.cash { background: #fff7e6; color: #d46b08; }
.tag { display: inline-block; padding: 1px 8px; border-radius: 10px; font-size: 11px; }
.tag.ok { background: #f6ffed; color: #389e0d; }
.tag.warn { background: #f5f5f5; color: #999; }
.ops { white-space: nowrap; text-align: right; }
.ops a { color: #1677ff; cursor: pointer; margin-left: 10px; font-size: 12px; }
.ops a.danger { color: #cf1322; }
.empty { text-align: center; color: #bbb; padding: 30px; font-size: 13px; }
.modal-mask { position: fixed; inset: 0; background: rgba(0,0,0,.4); display: flex; align-items: center; justify-content: center; z-index: 100; }
.modal { background: #fff; border-radius: 10px; width: 560px; max-width: 92vw; max-height: 90vh; overflow: auto; }
.modal.w640 { width: 640px; }
.modal-h { padding: 14px 18px; font-weight: 700; border-bottom: 1px solid #f0f0f0; display: flex; justify-content: space-between; }
.modal-h .x { cursor: pointer; color: #999; }
.modal-b { padding: 16px 18px; }
.modal-f { padding: 12px 18px; border-top: 1px solid #f0f0f0; text-align: right; }
.modal-f .btn { margin-left: 8px; }
.form-row { display: flex; align-items: center; gap: 10px; margin-bottom: 12px; }
.form-row.align-top { align-items: flex-start; }
.form-row label { width: 90px; color: #555; font-size: 13px; flex-shrink: 0; }
.form-row input[type=text], .form-row input:not([type]), .form-row select { flex: 1; padding: 6px 10px; border: 1px solid #d9d9d9; border-radius: 6px; }
.form-row input:disabled { background: #f5f5f5; color: #999; }
.checks { display: flex; flex-wrap: wrap; gap: 6px 16px; flex: 1; }
.chk { font-size: 13px; color: #555; display: flex; align-items: center; gap: 4px; white-space: nowrap; }
.form-row.indent { margin-left: 0; }
.hint-row { margin-bottom: 16px; }
.hint-row .hint { color: #999; font-size: 12px; line-height: 1.5; flex: 1; }
.toast-inline { padding: 8px 12px; border-radius: 6px; margin-bottom: 10px; font-size: 13px; background: #f6ffed; border: 1px solid #b7eb8f; color: #389e0d; }
.toast-inline.err { background: #fff1f0; border-color: #ffa39e; color: #cf1322; }
</style>
