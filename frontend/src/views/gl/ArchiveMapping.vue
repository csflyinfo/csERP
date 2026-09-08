<script setup>
/**
 * 总账 M4——档案科目映射
 * 资金账户→资金类科目（库存现金/银行存款等 is_cash 科目）；
 * 费用类型→费用科目（@EXPENSE 取此，未配回落 560299/5051 并警告）；
 * 商品分类→收入/成本科目（@INCOME/@COST 取此，未配回落 500101/5401）。
 */
import { ref, onMounted } from 'vue'
import { post, saveTextFile } from '../../api/client.js'

const fundAccounts = ref([])
const expenseTypes = ref([])
const categories = ref([])
const leafAccounts = ref([])
const feedback = ref('')

function show(msg, level = 'ok') {
  feedback.value = { level, msg }
  setTimeout(() => (feedback.value = ''), 4000)
}

async function load() {
  try {
    const r = await post('/finance/gl/archive-mapping/list', {})
    fundAccounts.value = r.fundAccounts || []
    expenseTypes.value = r.expenseTypes || []
    categories.value = r.categories || []
    leafAccounts.value = r.leafAccounts || []
  } catch (e) { show('加载失败：' + (e?.message || e), 'err') }
}

async function save(type, row, extra = {}) {
  try {
    await post('/finance/gl/archive-mapping/save', { type, code: row.code, ...extra })
    show(`「${row.name}」映射已保存`)
  } catch (e) { show(e?.message || String(e), 'err') }
}

function accountName(code) {
  const a = leafAccounts.value.find(x => x.code === code)
  return a ? a.name : ''
}
const fundSubjects = () => leafAccounts.value.filter(a => a.isCash)

// M9：导出科目对照配置（档案→科目三类映射，CSV 带 BOM 供 Excel 直开）
function csvCell(v) {
  const s = (v === null || v === undefined) ? '' : String(v)
  return /[",\n]/.test(s) ? '"' + s.replace(/"/g, '""') + '"' : s
}
function exportCsv() {
  const lines = [['对照类型', '档案编码', '档案名称', '科目编码', '科目名称', '配置状态'].map(csvCell).join(',')]
  const push = (type, rows, getCodes) => {
    for (const row of (rows || [])) {
      const codes = getCodes(row)
      const configured = codes.filter(c => c)
      const names = configured.map(c => {
        const a = leafAccounts.value.find(x => x.code === c)
        return a ? a.name : ''
      })
      lines.push([
        type, row.code, row.name,
        configured.join(' / ') || '', names.join(' / '),
        configured.length ? '已配置' : '未配置(兜底科目)',
      ].map(csvCell).join(','))
    }
  }
  push('资金账户→资金科目', fundAccounts.value, r => [r.glAccountCode])
  push('费用类型→费用科目', expenseTypes.value, r => [r.glAccountCode])
  push('商品分类→收入/成本科目', categories.value, r => [r.glIncomeAccountCode, r.glCostAccountCode])
  saveTextFile(`档案科目对照-${new Date().toISOString().slice(0, 10)}.csv`, String.fromCharCode(0xFEFF) + lines.join('\r\n'), 'text/csv;charset=UTF-8')
}

onMounted(load)
</script>

<template>
  <div class="module-body">
    <div v-if="feedback" :class="['toast-inline', feedback.level]">{{ feedback.msg }}</div>
    <div class="page-ops">
      <b>档案科目映射</b>
      <div class="spacer"></div>
      <button class="btn" @click="exportCsv">导出对照配置（CSV）</button>
    </div>
    <div class="card-box tip-box">
      基础档案（资金账户/费用类型/商品分类）仍在各自档案页维护，本页只配置它们生成凭证时对应的<b>总账科目</b>。
      科目必须为<b>末级且启用</b>；资金账户只能映射资金类科目。留空表示未配置——事件生成时按科目体系默认科目兜底并给出警告。
    </div>

    <!-- 资金账户 -->
    <div class="card-box">
      <div class="group-h">资金账户 → 资金科目（收款/付款/费用贷方 @FUND）</div>
      <table class="data">
        <thead>
          <tr><th style="width:110px">账户编码</th><th style="width:180px">账户名称</th><th>对应总账科目</th><th style="width:220px">科目名称</th><th style="width:80px">操作</th></tr>
        </thead>
        <tbody>
          <tr v-for="row in fundAccounts" :key="row.code" :class="{ 'row-unmapped': !row.glAccountCode }">
            <td>{{ row.code }}</td>
            <td>{{ row.name }}<span v-if="row.isSystem" class="tag tag-blue">预置</span></td>
            <td>
              <select v-model="row.glAccountCode">
                <option value="">（未配置）</option>
                <option v-for="a in fundSubjects()" :key="a.code" :value="a.code">{{ a.code }} {{ a.name }}</option>
              </select>
            </td>
            <td>{{ row.glAccountCode ? accountName(row.glAccountCode) : '—' }}</td>
            <td><a class="lk" @click="save('fund', row, { glAccountCode: row.glAccountCode || '' })">保存</a></td>
          </tr>
          <tr v-if="!fundAccounts.length"><td colspan="5" class="empty">暂无资金账户</td></tr>
        </tbody>
      </table>
    </div>

    <!-- 费用类型 -->
    <div class="card-box">
      <div class="group-h">费用类型 → 费用科目（费用单/其他收入 @EXPENSE）</div>
      <table class="data">
        <thead>
          <tr><th style="width:110px">类型编码</th><th style="width:180px">类型名称</th><th>对应总账科目</th><th style="width:220px">科目名称</th><th style="width:80px">操作</th></tr>
        </thead>
        <tbody>
          <tr v-for="row in expenseTypes" :key="row.code" :class="{ 'row-unmapped': !row.glAccountCode }">
            <td>{{ row.code }}</td>
            <td>{{ row.name }}</td>
            <td>
              <select v-model="row.glAccountCode">
                <option value="">（未配置，兜底 560299/5051）</option>
                <option v-for="a in leafAccounts" :key="a.code" :value="a.code">{{ a.code }} {{ a.name }}</option>
              </select>
            </td>
            <td>{{ row.glAccountCode ? accountName(row.glAccountCode) : '—' }}</td>
            <td><a class="lk" @click="save('expense', row, { glAccountCode: row.glAccountCode || '' })">保存</a></td>
          </tr>
          <tr v-if="!expenseTypes.length"><td colspan="5" class="empty">暂无费用类型</td></tr>
        </tbody>
      </table>
    </div>

    <!-- 商品分类 -->
    <div class="card-box">
      <div class="group-h">商品分类 → 收入/成本科目（销售签收 @INCOME、销售成本 @COST）</div>
      <table class="data">
        <thead>
          <tr><th style="width:110px">分类编码</th><th style="width:180px">分类名称</th><th>收入科目</th><th>成本科目</th><th style="width:80px">操作</th></tr>
        </thead>
        <tbody>
          <tr v-for="row in categories" :key="row.code" :class="{ 'row-unmapped': !row.glIncomeAccountCode && !row.glCostAccountCode }">
            <td>{{ row.code }}</td>
            <td>{{ row.name }}</td>
            <td>
              <select v-model="row.glIncomeAccountCode">
                <option value="">（兜底 500101）</option>
                <option v-for="a in leafAccounts" :key="a.code" :value="a.code">{{ a.code }} {{ a.name }}</option>
              </select>
            </td>
            <td>
              <select v-model="row.glCostAccountCode">
                <option value="">（兜底 5401）</option>
                <option v-for="a in leafAccounts" :key="a.code" :value="a.code">{{ a.code }} {{ a.name }}</option>
              </select>
            </td>
            <td>
              <a class="lk" @click="save('category', row, {
                glIncomeAccountCode: row.glIncomeAccountCode || '',
                glCostAccountCode: row.glCostAccountCode || '',
              })">保存</a>
            </td>
          </tr>
          <tr v-if="!categories.length"><td colspan="5" class="empty">暂无商品分类</td></tr>
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
.tag-blue { background: #e8f1fd; color: #1d6fd1; }
.row-unmapped { background: #fffaf0; }
.lk { color: #1d6fd1; cursor: pointer; }
.empty { text-align: center; color: #999; padding: 20px; }
select { min-width: 240px; }
</style>
