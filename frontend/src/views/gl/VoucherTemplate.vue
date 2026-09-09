<script setup>
/**
 * 总账 M3——凭证模板配置
 * 预置 16 事件码模板可改可停用（不可删）；分录行：科目（固定码/@AR/@AP/@FUND/@EXPENSE/@BIZ）、
 * 金额表达式（白名单四则）、条件表达式（纳税人/往来分流）、明细展开 GOODS_LINE/EXPENSE_LINE。
 * 支持试渲染：贴入事件 payload JSON，预览将生成的分录，不落库。
 */
import { ref, onMounted } from 'vue'
import { post } from '../../api/client.js'

const templates = ref([])
const current = ref(null)
const leafAccounts = ref([])
const feedback = ref('')
const previewPayload = ref('')
const previewResult = ref(null)
const previewErr = ref('')

function show(msg, level = 'ok') {
  feedback.value = { level, msg }
  setTimeout(() => (feedback.value = ''), 5000)
}
function money(v) {
  return Number(v || 0).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}

async function load() {
  try {
    templates.value = await post('/finance/gl/template/list', {})
    if (!current.value && templates.value.length) selectTpl(templates.value[0])
    if (current.value) {
      const fresh = templates.value.find(t => t.id === current.value.id)
      if (fresh) current.value = fresh
    }
  } catch (e) { show('模板加载失败：' + (e?.message || e), 'err') }
}
async function loadMeta() {
  try { leafAccounts.value = (await post('/finance/gl/account/leaf-options', {})) || [] }
  catch (e) { /* 科目加载失败不阻断编辑 */ }
}

function selectTpl(t) {
  // 深拷贝行，避免直接改列表
  current.value = { ...t, lines: (t.lines || []).map(l => ({ ...l })) }
  previewResult.value = null
  previewErr.value = ''
}

function addLine() {
  const no = (current.value.lines?.length || 0) + 1
  current.value.lines.push({
    lineNo: no, direction: '借', summaryPattern: '', accountExpr: '', amountExpr: '',
    qtyExpr: '', auxExpr: '', cashFlowItem: '', conditionExpr: '', expandBy: 'NONE', linesKey: 'lines', enabled: true,
  })
}
function removeLine(i) { current.value.lines.splice(i, 1) }

async function save() {
  try {
    const body = {
      id: current.value.id,
      eventCode: current.value.eventCode,
      templateName: current.value.templateName,
      voucherWord: current.value.voucherWord,
      summaryPattern: current.value.summaryPattern,
      enabled: current.value.enabled,
      lines: current.value.lines,
    }
    await post('/finance/gl/template/save', body)
    show('模板已保存')
    await load()
  } catch (e) { show('保存失败：' + (e?.message || e), 'err') }
}
async function toggle(t) {
  try {
    await post('/finance/gl/template/toggle', { id: t.id, enabled: !t.enabled })
    await load()
  } catch (e) { show(e?.message || String(e), 'err') }
}
async function remove(t) {
  if (!confirm('删除该自定义模板？预置模板不可删除。')) return
  try {
    await post('/finance/gl/template/delete', { id: t.id })
    show('已删除')
    current.value = null
    await load()
  } catch (e) { show(e?.message || String(e), 'err') }
}

async function preview() {
  previewErr.value = ''
  previewResult.value = null
  let payload
  try {
    payload = previewPayload.value ? JSON.parse(previewPayload.value) : {}
  } catch (e) { previewErr.value = 'payload 不是合法 JSON：' + e.message; return }
  try {
    previewResult.value = await post('/finance/gl/template/preview', {
      eventCode: current.value.eventCode,
      payload,
    })
  } catch (e) { previewErr.value = e?.message || String(e) }
}

onMounted(() => { loadMeta(); load() })
</script>

<template>
  <div class="tpl-page">
    <div class="tpl-left card-box">
      <div class="tpl-left-h">事件模板（{{ templates.length }}）</div>
      <div v-for="t in templates" :key="t.id" :class="['tpl-item', current?.id === t.id ? 'active' : '']" @click="selectTpl(t)">
        <div class="tpl-name">
          {{ t.templateName }}
          <span v-if="!t.enabled" class="tag tag-gray">已停用</span>
        </div>
        <div class="sub">{{ t.eventCode }} · 凭证字「{{ t.voucherWord }}」{{ t.isSystem ? ' · 预置' : ' · 自定义' }}</div>
      </div>
    </div>

    <div class="tpl-right">
      <div v-if="feedback" :class="['toast-inline', feedback.level]">{{ feedback.msg }}</div>
      <div v-if="!current" class="card-box empty">请选择左侧模板</div>
      <template v-else>
        <div class="card-box">
          <div class="tpl-head">
            <label>模板名称 <input v-model="current.templateName" style="width:160px" /></label>
            <label>事件码 <input :value="current.eventCode" disabled style="width:150px" /></label>
            <label>凭证字
              <select v-model="current.voucherWord" style="width:70px">
                <option>记</option><option>收</option><option>付</option><option>转</option>
              </select>
            </label>
            <label class="chk"><input type="checkbox" v-model="current.enabled" /> 启用</label>
            <span style="flex:1"></span>
            <button class="btn primary" v-permission="'finance.gl.voucher_template.add'" @click="save">保存模板</button>
            <button v-if="!current.isSystem" v-permission="'finance.gl.voucher_template.delete'" class="btn" @click="remove(current)">删除</button>
          </div>
          <div class="tpl-head">
            <label style="flex:1">凭证摘要 <input v-model="current.summaryPattern" placeholder="采购收货 {bill_no}" /></label>
            <button class="btn" v-permission="'finance.gl.voucher_template.edit'" @click="toggle(current)">{{ current.enabled ? '停用' : '启用' }}</button>
          </div>

          <table class="data line-table">
            <thead>
              <tr>
                <th style="width:44px">行</th><th style="width:56px">方向</th><th style="width:200px">摘要</th>
                <th style="width:190px">科目</th><th style="width:180px">金额表达式</th><th style="width:90px">数量表达式</th>
                <th style="width:170px">辅助映射 JSON</th><th style="width:90px">现金流</th>
                <th style="width:190px">启用条件</th><th style="width:120px">展开方式</th><th style="width:90px">数组键</th>
                <th style="width:44px">启用</th><th></th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="(l, i) in current.lines" :key="i">
                <td>{{ i + 1 }}</td>
                <td>
                  <select v-model="l.direction"><option>借</option><option>贷</option></select>
                </td>
                <td><input v-model="l.summaryPattern" placeholder="{goods_name}" /></td>
                <td>
                  <input v-model="l.accountExpr" list="account-list" placeholder="1405 / @AR / @BIZ" />
                </td>
                <td><input v-model="l.amountExpr" placeholder="amount_excl" /></td>
                <td><input v-model="l.qtyExpr" placeholder="qty" /></td>
                <td><input v-model="l.auxExpr" placeholder='{"goods":"goods_code"}' /></td>
                <td><input v-model="l.cashFlowItem" placeholder="CF01" /></td>
                <td><input v-model="l.conditionExpr" placeholder="taxpayer == 'GENERAL'" /></td>
                <td>
                  <select v-model="l.expandBy">
                    <option value="NONE">整单一行</option>
                    <option value="GOODS_LINE">商品行</option>
                    <option value="EXPENSE_LINE">费用行</option>
                  </select>
                </td>
                <td><input v-model="l.linesKey" /></td>
                <td><input type="checkbox" v-model="l.enabled" /></td>
                <td><a class="lk" @click="removeLine(i)">删</a></td>
              </tr>
            </tbody>
          </table>
          <div class="tpl-head">
            <button class="btn" @click="addLine">+ 新增分录行</button>
            <span class="sub">科目支持固定编码或占位符：@AR 应收(1122) / @AP 应付(2202) / @FUND 资金账户映射 / @EXPENSE 明细行科目 / @BIZ 业务类型映射</span>
          </div>
        </div>

        <datalist id="account-list">
          <option v-for="a in leafAccounts" :key="a.accountCode" :value="a.accountCode">{{ a.accountName }}</option>
          <option value="@AR">@AR 应收账款</option>
          <option value="@AP">@AP 应付账款</option>
          <option value="@FUND">@FUND 资金账户映射科目</option>
          <option value="@EXPENSE">@EXPENSE 明细行费用/收入科目</option>
          <option value="@BIZ">@BIZ 业务类型映射科目</option>
        </datalist>

        <div class="card-box">
          <div class="tpl-head"><b>试渲染预览</b><span class="sub">贴入事件 payload JSON，验证金额/拆税/条件分流，不落库</span></div>
          <textarea v-model="previewPayload" class="json-input" rows="8"
            placeholder='{"bill_no":"PO202609001","amount_tax_incl":11300,"tax_amount":1300,"taxpayer":"GENERAL","supplier_code":"S001","supplier_name":"某供应商","lines":[{"goods_code":"G001","goods_name":"商品A","qty":10,"amount_excl":10000,"tax_amount":1300,"cost_amount":10000}]}'></textarea>
          <div class="tpl-head">
            <button class="btn primary" @click="preview">试渲染</button>
            <span v-if="previewErr" class="err-text">{{ previewErr }}</span>
          </div>
          <template v-if="previewResult">
            <p class="sub">凭证字「{{ previewResult.word }}」　摘要：{{ previewResult.summary }}　借方合计 {{ money(previewResult.debitTotal) }} / 贷方合计 {{ money(previewResult.creditTotal) }}</p>
            <div v-for="(w, i) in previewResult.warnings || []" :key="'w' + i" class="warn-text">⚠ {{ w }}</div>
            <table class="data">
              <thead><tr><th>行</th><th>方向</th><th>摘要</th><th>科目</th><th class="num">借方</th><th class="num">贷方</th><th>数量</th><th>辅助</th></tr></thead>
              <tbody>
                <tr v-for="e in previewResult.entries" :key="e.lineNo">
                  <td>{{ e.lineNo }}</td><td>{{ e.direction }}</td><td>{{ e.summary }}</td>
                  <td>{{ e.accountCode }} {{ e.accountName }}</td>
                  <td class="num">{{ money(e.debit) }}</td><td class="num">{{ money(e.credit) }}</td>
                  <td>{{ e.qty || '' }}</td><td>{{ e.auxText }}</td>
                </tr>
              </tbody>
            </table>
          </template>
        </div>
      </template>
    </div>
  </div>
</template>

<style scoped>
.tpl-page { display: flex; gap: 12px; align-items: flex-start; }
.tpl-left { width: 240px; flex-shrink: 0; padding: 0; max-height: 75vh; overflow: auto; }
.tpl-left-h { padding: 10px 12px; font-weight: 600; border-bottom: 1px solid #eee; position: sticky; top: 0; background: #fff; }
.tpl-item { padding: 8px 12px; cursor: pointer; border-bottom: 1px solid #f2f2f2; }
.tpl-item:hover { background: #f7faff; }
.tpl-item.active { background: #e8f1fd; }
.tpl-name { font-weight: 500; }
.tpl-right { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 12px; }
.tpl-head { display: flex; gap: 12px; align-items: center; padding: 8px 0; flex-wrap: wrap; }
.tpl-head label { display: flex; align-items: center; gap: 6px; font-size: 13px; }
.line-table input, .line-table select { width: 100%; min-width: 0; box-sizing: border-box; }
.line-table { font-size: 12px; }
.chk { display: flex; align-items: center; gap: 4px; }
.sub { color: #888; font-size: 12px; }
.lk { color: #1d6fd1; cursor: pointer; }
.tag { display: inline-block; padding: 0 8px; border-radius: 10px; font-size: 11px; }
.tag-gray { background: #f0f0f0; color: #888; }
.err-text { color: #d33; font-size: 13px; }
.warn-text { color: #b07010; font-size: 12px; }
.empty { text-align: center; color: #999; padding: 40px; }
.num { text-align: right; }
.json-input { width: 100%; box-sizing: border-box; font-family: monospace; font-size: 12px; padding: 8px; border: 1px solid #d9d9d9; border-radius: 4px; }
</style>
