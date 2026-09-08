<script setup>
/**
 * 总账 M2——账簿查询：总账 / 明细账 / 发生额及余额表 / 序时账。
 * 全部实时聚合：期初余额 + 已过账凭证分录，不落余额表。
 */
import { ref, onMounted } from 'vue'
import { post } from '../../api/client.js'

const AUX_DIMS = [
  { v: 'customer', l: '客户', key: 'customers' },
  { v: 'supplier', l: '供应商', key: 'suppliers' },
  { v: 'department', l: '部门', key: 'departments' },
  { v: 'employee', l: '员工', key: 'employees' },
  { v: 'goods', l: '商品', key: 'goods' },
  { v: 'project', l: '项目', key: 'projects' },
  { v: 'area', l: '片区', key: null },
]
const auxField = dim => 'aux' + dim.charAt(0).toUpperCase() + dim.slice(1)

const tab = ref('general')
const feedback = ref('')
const loading = ref(false)
function show(msg, level = 'ok') {
  feedback.value = { level, msg }
  setTimeout(() => (feedback.value = ''), 4000)
}
function money(v) {
  const n = Number(v || 0)
  return n.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}
function qty(v) {
  const n = Number(v || 0)
  return n.toLocaleString('zh-CN', { maximumFractionDigits: 4 })
}
function monthInputToPeriod(v) {
  return v ? v.replace('-', '') : ''
}

const allAccounts = ref([])
const leafAccounts = ref([])
const auxOptions = ref({ customers: [], suppliers: [], departments: [], employees: [], goods: [], projects: [] })

onMounted(async () => {
  try {
    allAccounts.value = (await post('/finance/gl/account/list')) || []
    parentAccounts.value = allAccounts.value.filter(a => !a.isLeaf)
    leafAccounts.value = (await post('/finance/gl/account/leaf-options', {})) || []
    auxOptions.value = await post('/finance/gl/voucher/aux-options')
    queryGeneral()
  } catch (e) { show('档案加载失败：' + (e?.message || e), 'err') }
})

// ================= 总账 =================
const generalYear = ref(String(new Date().getFullYear()))
const generalAccount = ref('')
const generalData = ref([])
async function queryGeneral() {
  loading.value = true
  try {
    generalData.value = (await post('/finance/gl/ledger/general', {
      year: generalYear.value,
      accountCode: generalAccount.value,
    })) || []
  } catch (e) { show('总账查询失败：' + (e?.message || e), 'err') }
  finally { loading.value = false }
}

// ================= 余额表 =================
const balanceFrom = ref(String(new Date().getFullYear()) + '-01')
const balanceTo = ref(String(new Date().getFullYear()) + '-' + String(new Date().getMonth() + 1).padStart(2, '0'))
const balanceData = ref([])
async function queryBalance() {
  loading.value = true
  try {
    balanceData.value = (await post('/finance/gl/ledger/balance-table', {
      periodFrom: monthInputToPeriod(balanceFrom.value),
      periodTo: monthInputToPeriod(balanceTo.value),
    })) || []
  } catch (e) { show('余额表查询失败：' + (e?.message || e), 'err') }
  finally { loading.value = false }
}

// ================= 明细账 =================
const subAccount = ref('')
const subDateFrom = ref('')
const subDateTo = ref('')
const subAux = ref({})
const subData = ref(null)
function subDims() {
  const acc = leafAccounts.value.find(a => a.accountCode === subAccount.value)
  return acc && acc.auxDimensions ? acc.auxDimensions.split(',') : []
}
async function querySubsidiary() {
  if (!subAccount.value) return show('请选择末级科目', 'err')
  loading.value = true
  try {
    subData.value = await post('/finance/gl/ledger/subsidiary', {
      accountCode: subAccount.value,
      dateFrom: subDateFrom.value,
      dateTo: subDateTo.value,
      ...subAux.value,
    })
  } catch (e) { show('明细账查询失败：' + (e?.message || e), 'err') }
  finally { loading.value = false }
}

// ================= 序时账 =================
const journalDateFrom = ref('')
const journalDateTo = ref('')
const journalStatus = ref('')
const journalKeyword = ref('')
const journalData = ref([])
async function queryJournal() {
  loading.value = true
  try {
    journalData.value = (await post('/finance/gl/ledger/journal', {
      dateFrom: journalDateFrom.value,
      dateTo: journalDateTo.value,
      status: journalStatus.value,
      keyword: journalKeyword.value,
    })) || []
  } catch (e) { show('序时账查询失败：' + (e?.message || e), 'err') }
  finally { loading.value = false }
}

// ================= 多栏账（M9） =================
const mcAccount = ref('')
const mcFrom = ref(String(new Date().getFullYear()) + '-01')
const mcTo = ref(String(new Date().getFullYear()) + '-' + String(new Date().getMonth() + 1).padStart(2, '0'))
const mcData = ref(null)
const parentAccounts = ref([])
async function queryMultiColumn() {
  if (!mcAccount.value) return show('请选择上级科目（非末级，如管理费用）', 'err')
  loading.value = true
  try {
    mcData.value = await post('/finance/gl/ledger/multi-column', {
      accountCode: mcAccount.value,
      periodFrom: monthInputToPeriod(mcFrom.value),
      periodTo: monthInputToPeriod(mcTo.value),
    })
  } catch (e) { mcData.value = null; show('多栏账查询失败：' + (e?.message || e), 'err') }
  finally { loading.value = false }
}

// ================= 辅助核算账（M9） =================
const AUX_DIM_OPTIONS = [
  { v: 'customer', l: '客户', key: 'customers' },
  { v: 'supplier', l: '供应商', key: 'suppliers' },
  { v: 'department', l: '部门', key: 'departments' },
  { v: 'employee', l: '员工', key: 'employees' },
  { v: 'goods', l: '商品', key: 'goods' },
  { v: 'project', l: '项目', key: 'projects' },
  { v: 'area', l: '片区', key: null },
]
const auxTab = ref('balance')
const abDim = ref('customer')
const abFrom = ref(String(new Date().getFullYear()) + '-01')
const abTo = ref(String(new Date().getFullYear()) + '-' + String(new Date().getMonth() + 1).padStart(2, '0'))
const abAccount = ref('')
const abData = ref(null)
const asDim = ref('customer')
const asAuxCode = ref('')
const asDateFrom = ref('')
const asDateTo = ref('')
const asAccount = ref('')
const asData = ref(null)
function dimLabel(v) { return AUX_DIM_OPTIONS.find(d => d.v === v)?.l || v }
function auxObjectOptions(dim) {
  const d = AUX_DIM_OPTIONS.find(x => x.v === dim)
  return d && d.key ? (auxOptions.value[d.key] || []) : []
}
async function queryAuxBalance() {
  loading.value = true
  try {
    abData.value = await post('/finance/gl/ledger/aux-balance', {
      dimension: abDim.value,
      periodFrom: monthInputToPeriod(abFrom.value),
      periodTo: monthInputToPeriod(abTo.value),
      accountCode: abAccount.value,
    })
  } catch (e) { abData.value = null; show('辅助余额表查询失败：' + (e?.message || e), 'err') }
  finally { loading.value = false }
}
async function queryAuxSubsidiary() {
  if (!asAuxCode.value) return show('请选择' + dimLabel(asDim.value) + '对象', 'err')
  loading.value = true
  try {
    asData.value = await post('/finance/gl/ledger/aux-subsidiary', {
      dimension: asDim.value,
      auxCode: asAuxCode.value,
      dateFrom: asDateFrom.value,
      dateTo: asDateTo.value,
      accountCode: asAccount.value,
    })
  } catch (e) { asData.value = null; show('辅助明细账查询失败：' + (e?.message || e), 'err') }
  finally { loading.value = false }
}

function switchTab(t) {
  tab.value = t
  if (t === 'multi' && !mcData.value) queryMultiColumn()
  if (t === 'aux' && !abData.value) queryAuxBalance()
}
</script>

<template>
  <div class="module-body">
    <div class="page-ops">
      <b>账簿查询</b>
      <div class="tabs">
        <a :class="{ active: tab === 'general' }" @click="switchTab('general')">总账</a>
        <a :class="{ active: tab === 'subsidiary' }" @click="switchTab('subsidiary')">明细账</a>
        <a :class="{ active: tab === 'balance' }" @click="switchTab('balance')">余额表</a>
        <a :class="{ active: tab === 'multi' }" @click="switchTab('multi')">多栏账</a>
        <a :class="{ active: tab === 'aux' }" @click="switchTab('aux')">辅助账</a>
        <a :class="{ active: tab === 'journal' }" @click="switchTab('journal')">序时账</a>
      </div>
      <div class="spacer"></div>
    </div>
    <div v-if="feedback" class="toast-inline" :class="feedback.level">{{ feedback.msg }}</div>

    <!-- ===== 总账 ===== -->
    <template v-if="tab === 'general'">
      <div class="filter-bar">
        <label>年度 <input type="number" v-model="generalYear" min="2000" max="2999" style="width:90px" /></label>
        <label>科目
          <select v-model="generalAccount" style="width:260px">
            <option value="">全部科目</option>
            <option v-for="a in allAccounts" :key="a.accountCode" :value="a.accountCode">
              {{ a.accountCode }} {{ a.accountName }}
            </option>
          </select>
        </label>
        <button class="btn primary" @click="queryGeneral">查询</button>
      </div>
      <div class="card-box">
        <table class="data">
          <thead>
            <tr><th style="width:200px">科目</th><th style="width:90px">月份</th>
                <th style="width:120px" class="num">期初借方</th><th style="width:120px" class="num">期初贷方</th>
                <th style="width:120px" class="num">本期借方</th><th style="width:120px" class="num">本期贷方</th>
                <th style="width:120px" class="num">期末借方</th><th style="width:120px" class="num">期末贷方</th></tr>
          </thead>
          <tbody v-for="g in generalData" :key="g.accountCode">
            <tr class="acct-row">
              <td colspan="8"><b class="code">{{ g.accountCode }}</b> {{ g.accountName }}
                <span class="muted">[{{ g.accountType }}/{{ g.balanceDirection }}]</span></td>
            </tr>
            <tr class="open-row">
              <td class="muted">年初余额</td><td></td>
              <td class="num">{{ money(g.yearOpening.debit) }}</td>
              <td class="num">{{ money(g.yearOpening.credit) }}</td>
              <td colspan="4"></td>
            </tr>
            <tr v-for="m in g.months" :key="m.period">
              <td></td><td class="muted">{{ m.month }}</td>
              <td class="num">{{ money(m.opening.debit) }}</td>
              <td class="num">{{ money(m.opening.credit) }}</td>
              <td class="num">{{ money(m.debitAmount) }}</td>
              <td class="num">{{ money(m.creditAmount) }}</td>
              <td class="num">{{ money(m.closing.debit) }}</td>
              <td class="num">{{ money(m.closing.credit) }}</td>
            </tr>
            <tr class="year-row">
              <td class="muted">本年累计</td><td></td>
              <td colspan="2"></td>
              <td class="num"><b>{{ money(g.yearDebit) }}</b></td>
              <td class="num"><b>{{ money(g.yearCredit) }}</b></td>
              <td colspan="2"></td>
            </tr>
          </tbody>
          <tr v-if="!generalData.length"><td colspan="8" class="empty">{{ loading ? '加载中...' : '暂无数据' }}</td></tr>
        </table>
      </div>
    </template>

    <!-- ===== 明细账 ===== -->
    <template v-if="tab === 'subsidiary'">
      <div class="filter-bar">
        <label>末级科目 *
          <select v-model="subAccount" style="width:260px">
            <option value="">请选择</option>
            <option v-for="a in leafAccounts" :key="a.accountCode" :value="a.accountCode">
              {{ a.accountCode }} {{ a.accountName }}
            </option>
          </select>
        </label>
        <label>日期 <input type="date" v-model="subDateFrom" /></label>
        <span class="range-sep">至</span>
        <label><input type="date" v-model="subDateTo" /></label>
        <template v-for="d in AUX_DIMS.filter(d => subDims().includes(d.v))" :key="d.v">
          <label>{{ d.l }}
            <select v-if="d.key" v-model="subAux[auxField(d.v)]" style="width:150px">
              <option value="">全部</option>
              <option v-for="o in (auxOptions[d.key] || [])" :key="o.code" :value="o.code">{{ o.name }}</option>
            </select>
            <input v-else v-model="subAux[auxField(d.v)]" placeholder="片区" style="width:120px" />
          </label>
        </template>
        <button class="btn primary" @click="querySubsidiary">查询</button>
      </div>
      <div v-if="subData" class="card-box">
        <div class="sub-title">
          <b>{{ subData.account.accountCode }} {{ subData.account.accountName }}</b>
          <span class="muted">数量核算：{{ subData.account.isQty ? '是' : '否' }}</span>
        </div>
        <table class="data">
          <thead>
            <tr><th style="width:100px">日期</th><th style="width:150px">凭证号</th><th>摘要</th>
                <th style="width:110px" class="num">借方</th><th style="width:110px" class="num">贷方</th>
                <th style="width:60px">方向</th><th style="width:120px" class="num">余额</th>
                <th v-if="subData.account.isQty" style="width:90px" class="num">数量</th>
                <th style="width:200px">辅助核算</th></tr>
          </thead>
          <tbody>
            <tr class="open-row">
              <td colspan="3" class="muted">期初余额</td>
              <td class="num">{{ money(subData.opening.debit) }}</td>
              <td class="num">{{ money(subData.opening.credit) }}</td>
              <td>{{ subData.opening.direction }}</td>
              <td class="num">{{ money(subData.opening.direction === '贷' ? subData.opening.credit : subData.opening.debit) }}</td>
              <td v-if="subData.account.isQty" class="num">{{ qty(subData.openingQty) }}</td>
              <td></td>
            </tr>
            <tr v-for="(r, i) in subData.rows" :key="i" :class="{ redline: r.isRed }">
              <td>{{ String(r.voucherDate).slice(0, 10) }}</td>
              <td><span class="vno">{{ r.voucherNo }}</span><span v-if="r.isRed" class="red-tag">红字</span></td>
              <td>{{ r.summary || r.headSummary || '' }}</td>
              <td class="num">{{ money(r.debitAmount) }}</td>
              <td class="num">{{ money(r.creditAmount) }}</td>
              <td>{{ r.balanceDirection }}</td>
              <td class="num">{{ money(r.balanceDirection === '贷' ? r.balanceCredit : r.balanceDebit) }}</td>
              <td v-if="subData.account.isQty" class="num">{{ qty(r.balanceQty) }}</td>
              <td class="muted small">{{ r.auxText || '' }}</td>
            </tr>
            <tr class="year-row">
              <td colspan="3" class="muted">本期合计</td>
              <td class="num"><b>{{ money(subData.totalDebit) }}</b></td>
              <td class="num"><b>{{ money(subData.totalCredit) }}</b></td>
              <td colspan="2"></td>
              <td v-if="subData.account.isQty"></td>
              <td></td>
            </tr>
            <tr class="close-row">
              <td colspan="3" class="muted">期末余额</td>
              <td class="num">{{ money(subData.closing.debit) }}</td>
              <td class="num">{{ money(subData.closing.credit) }}</td>
              <td>{{ subData.closing.direction }}</td>
              <td class="num">{{ money(subData.closing.direction === '贷' ? subData.closing.credit : subData.closing.debit) }}</td>
              <td v-if="subData.account.isQty" class="num">{{ qty(subData.closingQty) }}</td>
              <td></td>
            </tr>
          </tbody>
        </table>
        <div v-if="!subData.rows.length" class="empty">该期间内无已过账分录</div>
      </div>
    </template>

    <!-- ===== 余额表 ===== -->
    <template v-if="tab === 'balance'">
      <div class="filter-bar">
        <label>期间 <input type="month" v-model="balanceFrom" /></label>
        <span class="range-sep">至</span>
        <label><input type="month" v-model="balanceTo" /></label>
        <button class="btn primary" @click="queryBalance">查询</button>
      </div>
      <div class="card-box">
        <table class="data">
          <thead>
            <tr><th style="width:200px">科目编码</th><th>科目名称</th>
                <th style="width:110px" class="num">期初借方</th><th style="width:110px" class="num">期初贷方</th>
                <th style="width:110px" class="num">本期借方</th><th style="width:110px" class="num">本期贷方</th>
                <th style="width:110px" class="num">期末借方</th><th style="width:110px" class="num">期末贷方</th>
                <th style="width:110px" class="num">本年累借</th><th style="width:110px" class="num">本年累贷</th>
                <th v-if="balanceData.some(r => r.isQty)" style="width:80px" class="num">期末数量</th></tr>
          </thead>
          <tbody>
            <tr v-for="r in balanceData" :key="r.accountCode" :class="{ parent: !r.isLeaf }">
              <td><span :style="{ paddingLeft: ((r.accountLevel - 1) * 14) + 'px' }">
                <b class="code">{{ r.accountCode }}</b></span></td>
              <td>{{ r.accountName }}</td>
              <td class="num">{{ money(r.opening.debit) }}</td>
              <td class="num">{{ money(r.opening.credit) }}</td>
              <td class="num">{{ money(r.periodDebit) }}</td>
              <td class="num">{{ money(r.periodCredit) }}</td>
              <td class="num">{{ money(r.closing.debit) }}</td>
              <td class="num">{{ money(r.closing.credit) }}</td>
              <td class="num muted">{{ money(r.yearDebit) }}</td>
              <td class="num muted">{{ money(r.yearCredit) }}</td>
              <td v-if="balanceData.some(x => x.isQty)" class="num">{{ r.isQty ? qty(r.closeQty) : '' }}</td>
            </tr>
            <tr v-if="!balanceData.length"><td colspan="11" class="empty">{{ loading ? '加载中...' : '暂无数据' }}</td></tr>
          </tbody>
        </table>
      </div>
    </template>

    <!-- ===== 多栏账 ===== -->
    <template v-if="tab === 'multi'">
      <div class="filter-bar">
        <label>上级科目 *
          <select v-model="mcAccount" style="width:260px">
            <option value="">请选择（非末级科目）</option>
            <option v-for="a in parentAccounts" :key="a.accountCode" :value="a.accountCode">
              {{ a.accountCode }} {{ a.accountName }}
            </option>
          </select>
        </label>
        <label>期间 <input type="month" v-model="mcFrom" /></label>
        <span class="range-sep">至</span>
        <label><input type="month" v-model="mcTo" /></label>
        <button class="btn primary" @click="queryMultiColumn">查询</button>
        <span v-if="mcData" class="muted small">
          分析侧：{{ mcData.account.analyzeSide === '借' ? '借方发生（费用/成本类）' : '贷方发生（收入类）' }}；
          期初余额 借 {{ money(mcData.opening.debit) }} / 贷 {{ money(mcData.opening.credit) }}
        </span>
      </div>
      <div v-if="mcData" class="card-box">
        <div class="sub-title">
          <b>{{ mcData.account.accountCode }} {{ mcData.account.accountName }}（多栏式明细账）</b>
          <span class="muted">{{ mcData.periodFrom }} ~ {{ mcData.periodTo }}，共 {{ mcData.columns.length }} 个明细专栏</span>
        </div>
        <table class="data">
          <thead>
            <tr>
              <th style="min-width:80px">期间</th>
              <th v-for="c in mcData.columns" :key="c.accountCode" class="num">
                <div class="col-code">{{ c.accountCode }}</div>
                <div class="col-name">{{ c.accountName }}</div>
              </th>
              <th class="num">合计</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="r in mcData.rows" :key="r.period">
              <td class="muted">{{ r.period }}</td>
              <td v-for="c in mcData.columns" :key="c.accountCode" class="num">
                {{ Number(r.cells[c.accountCode] || 0) ? money(r.cells[c.accountCode]) : '' }}
              </td>
              <td class="num"><b>{{ Number(r.total || 0) ? money(r.total) : '' }}</b></td>
            </tr>
            <tr class="year-row">
              <td class="muted">合计</td>
              <td v-for="c in mcData.columns" :key="c.accountCode" class="num">
                <b>{{ Number(mcData.totalRow.cells[c.accountCode] || 0) ? money(mcData.totalRow.cells[c.accountCode]) : '' }}</b>
              </td>
              <td class="num"><b>{{ money(mcData.totalRow.total) }}</b></td>
            </tr>
          </tbody>
        </table>
        <div v-if="!mcData.rows.length" class="empty">该期间范围内无发生额</div>
      </div>
    </template>

    <!-- ===== 辅助核算账 ===== -->
    <template v-if="tab === 'aux'">
      <div class="tabs sub-tabs">
        <a :class="{ active: auxTab === 'balance' }" @click="auxTab = 'balance'">辅助余额表</a>
        <a :class="{ active: auxTab === 'detail' }" @click="auxTab = 'detail'">辅助明细账</a>
      </div>

      <!-- 辅助余额表 -->
      <template v-if="auxTab === 'balance'">
        <div class="filter-bar">
          <label>核算维度
            <select v-model="abDim" style="width:110px">
              <option v-for="d in AUX_DIM_OPTIONS" :key="d.v" :value="d.v">{{ d.l }}</option>
            </select>
          </label>
          <label>期间 <input type="month" v-model="abFrom" /></label>
          <span class="range-sep">至</span>
          <label><input type="month" v-model="abTo" /></label>
          <label>科目
            <select v-model="abAccount" style="width:220px">
              <option value="">全部科目</option>
              <option v-for="a in allAccounts" :key="a.accountCode" :value="a.accountCode">
                {{ a.accountCode }} {{ a.accountName }}
              </option>
            </select>
          </label>
          <button class="btn primary" @click="queryAuxBalance">查询</button>
        </div>
        <div v-if="abData" class="card-box">
          <div class="sub-title">
            <b>{{ abData.dimensionLabel }}辅助核算余额表</b>
            <span class="muted">{{ abData.periodFrom }} ~ {{ abData.periodTo }}<template v-if="abData.accountCode">；科目 {{ abData.accountCode }} 及其下级</template></span>
          </div>
          <table class="data">
            <thead>
              <tr><th style="width:160px">{{ abData.dimensionLabel }}编码</th><th>{{ abData.dimensionLabel }}名称</th>
                    <th style="width:110px" class="num">期初借方</th><th style="width:110px" class="num">期初贷方</th>
                    <th style="width:110px" class="num">本期借方</th><th style="width:110px" class="num">本期贷方</th>
                    <th style="width:60px">方向</th><th style="width:120px" class="num">期末余额</th></tr>
            </thead>
            <tbody>
              <tr v-for="r in abData.rows" :key="r.auxCode">
                <td><b class="code">{{ r.auxCode }}</b></td>
                <td>{{ r.auxName }}</td>
                <td class="num">{{ money(r.opening.debit) }}</td>
                <td class="num">{{ money(r.opening.credit) }}</td>
                <td class="num">{{ money(r.periodDebit) }}</td>
                <td class="num">{{ money(r.periodCredit) }}</td>
                <td>{{ r.closing.direction }}</td>
                <td class="num">{{ money(r.closing.direction === '贷' ? r.closing.credit : r.closing.debit) }}</td>
              </tr>
              <tr v-if="!abData.rows.length"><td colspan="8" class="empty">{{ loading ? '加载中...' : '该维度在查询范围内无辅助核算发生额' }}</td></tr>
            </tbody>
          </table>
        </div>
      </template>

      <!-- 辅助明细账 -->
      <template v-if="auxTab === 'detail'">
        <div class="filter-bar">
          <label>核算维度
            <select v-model="asDim" style="width:110px">
              <option v-for="d in AUX_DIM_OPTIONS" :key="d.v" :value="d.v">{{ d.l }}</option>
            </select>
          </label>
          <label>{{ dimLabel(asDim) }}对象 *
            <select v-if="AUX_DIM_OPTIONS.find(d => d.v === asDim)?.key" v-model="asAuxCode" style="width:200px">
              <option value="">请选择</option>
              <option v-for="o in auxObjectOptions(asDim)" :key="o.code" :value="o.code">{{ o.code }} {{ o.name }}</option>
            </select>
            <input v-else v-model="asAuxCode" placeholder="填写片区文本" style="width:200px" />
          </label>
          <label>日期 <input type="date" v-model="asDateFrom" /></label>
          <span class="range-sep">至</span>
          <label><input type="date" v-model="asDateTo" /></label>
          <label>科目
            <select v-model="asAccount" style="width:200px">
              <option value="">全部科目</option>
              <option v-for="a in allAccounts" :key="a.accountCode" :value="a.accountCode">
                {{ a.accountCode }} {{ a.accountName }}
              </option>
            </select>
          </label>
          <button class="btn primary" @click="queryAuxSubsidiary">查询</button>
        </div>
        <div v-if="asData" class="card-box">
          <div class="sub-title">
            <b>{{ asData.dimensionLabel }}辅助明细账 — {{ asData.auxCode }} {{ asData.auxName }}</b>
            <span class="muted">{{ asData.dateFrom }} ~ {{ asData.dateTo }}<template v-if="asData.accountCode">；科目 {{ asData.accountCode }} 及其下级</template></span>
          </div>
          <table class="data">
            <thead>
              <tr><th style="width:100px">日期</th><th style="width:150px">凭证号</th><th style="width:200px">科目</th>
                    <th>摘要</th><th style="width:110px" class="num">借方</th><th style="width:110px" class="num">贷方</th>
                    <th style="width:60px">方向</th><th style="width:120px" class="num">余额</th></tr>
            </thead>
            <tbody>
              <tr class="open-row">
                <td colspan="4" class="muted">期初余额</td>
                <td class="num">{{ money(asData.opening.debit) }}</td>
                <td class="num">{{ money(asData.opening.credit) }}</td>
                <td>{{ asData.opening.direction }}</td>
                <td class="num">{{ money(asData.opening.direction === '贷' ? asData.opening.credit : asData.opening.debit) }}</td>
              </tr>
              <tr v-for="(r, i) in asData.rows" :key="i" :class="{ redline: r.isRed }">
                <td>{{ String(r.voucherDate).slice(0, 10) }}</td>
                <td><span class="vno">{{ r.voucherNo }}</span><span v-if="r.isRed" class="red-tag">红字</span></td>
                <td><b class="code">{{ r.accountCode }}</b> {{ r.accountName }}</td>
                <td>{{ r.summary }}</td>
                <td class="num">{{ money(r.debitAmount) }}</td>
                <td class="num">{{ money(r.creditAmount) }}</td>
                <td>{{ r.balanceDirection }}</td>
                <td class="num">{{ money(r.balanceDirection === '贷' ? r.balanceCredit : r.balanceDebit) }}</td>
              </tr>
              <tr class="year-row">
                <td colspan="4" class="muted">本期合计</td>
                <td class="num"><b>{{ money(asData.totalDebit) }}</b></td>
                <td class="num"><b>{{ money(asData.totalCredit) }}</b></td>
                <td colspan="2"></td>
              </tr>
              <tr class="close-row">
                <td colspan="4" class="muted">期末余额</td>
                <td class="num">{{ money(asData.closing.debit) }}</td>
                <td class="num">{{ money(asData.closing.credit) }}</td>
                <td>{{ asData.closing.direction }}</td>
                <td class="num">{{ money(asData.closing.direction === '贷' ? asData.closing.credit : asData.closing.debit) }}</td>
              </tr>
            </tbody>
          </table>
          <div v-if="!asData.rows.length" class="empty">该范围内无已过账分录</div>
        </div>
      </template>
    </template>

    <!-- ===== 序时账 ===== -->
    <template v-if="tab === 'journal'">
      <div class="filter-bar">
        <label>日期 <input type="date" v-model="journalDateFrom" /></label>
        <span class="range-sep">至</span>
        <label><input type="date" v-model="journalDateTo" /></label>
        <label>状态
          <select v-model="journalStatus" style="width:110px">
            <option value="">全部（除作废）</option>
            <option value="已审核">已审核</option>
            <option value="已过账">已过账</option>
          </select>
        </label>
        <input v-model="journalKeyword" placeholder="凭证号/摘要/科目" style="width:180px" />
        <button class="btn primary" @click="queryJournal">查询</button>
      </div>
      <div class="card-box">
        <table class="data">
          <thead>
            <tr><th style="width:100px">日期</th><th style="width:150px">凭证号</th><th style="width:50px">字</th>
                <th>摘要</th><th style="width:200px">科目</th>
                <th style="width:110px" class="num">借方</th><th style="width:110px" class="num">贷方</th>
                <th style="width:180px">辅助核算</th><th style="width:70px">状态</th><th style="width:60px">来源</th></tr>
          </thead>
          <tbody>
            <tr v-for="(r, i) in journalData" :key="i" :class="{ redline: r.isRed }">
              <td>{{ String(r.voucherDate).slice(0, 10) }}</td>
              <td><span class="vno">{{ r.voucherNo }}</span><span v-if="r.isRed" class="red-tag">红字</span></td>
              <td>{{ r.voucherWord }}</td>
              <td>{{ r.entrySummary || r.headSummary }}</td>
              <td><b class="code">{{ r.accountCode }}</b> {{ r.accountName }}</td>
              <td class="num">{{ money(r.debitAmount) }}</td>
              <td class="num">{{ money(r.creditAmount) }}</td>
              <td class="muted small">{{ r.auxText || '' }}</td>
              <td><span class="tag" :class="r.status === '已过账' ? 'ok' : 'info'">{{ r.status }}</span></td>
              <td class="muted">{{ r.source }}</td>
            </tr>
            <tr v-if="!journalData.length"><td colspan="10" class="empty">{{ loading ? '加载中...' : '暂无数据' }}</td></tr>
          </tbody>
        </table>
      </div>
    </template>
  </div>
</template>

<style scoped>
.spacer { flex: 1; }
.tabs { display: flex; gap: 4px; margin-left: 18px; }
.tabs a { padding: 4px 14px; border-radius: 6px; font-size: 13px; cursor: pointer; color: #666; }
.tabs a.active { background: #1677ff; color: #fff; }
.sub-tabs { margin: 10px 0 0; }
.col-code { font-family: monospace; font-size: 11px; color: #1677ff; }
.col-name { font-size: 11px; color: #666; font-weight: normal; white-space: nowrap; }
.filter-bar { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; margin: 10px 0; }
.filter-bar label { display: flex; align-items: center; gap: 6px; font-size: 13px; color: #555; }
.filter-bar input, .filter-bar select { padding: 5px 8px; border: 1px solid #d9d9d9; border-radius: 6px; font-size: 13px; }
.range-sep { color: #999; font-size: 12px; }
.card-box { background: #fff; border: 1px solid #f0f0f0; border-radius: 8px; overflow: auto; }
.data { width: 100%; border-collapse: collapse; font-size: 13px; }
.data th, .data td { padding: 6px 10px; border-bottom: 1px solid #f0f0f0; text-align: left; white-space: nowrap; }
.data th { background: #fafafa; font-weight: 600; color: #555; position: sticky; top: 0; }
.num { text-align: right; font-variant-numeric: tabular-nums; }
.code { font-family: monospace; color: #1677ff; }
.muted { color: #999; }
.small { font-size: 12px; }
.acct-row td { background: #f0f7ff; padding-top: 8px; }
.open-row td { background: #fafafa; color: #777; }
.year-row td { background: #fafafa; font-weight: 600; }
.close-row td { background: #fffbe6; font-weight: 600; }
tr.parent td { font-weight: 600; background: #fcfcfc; }
tr.redline td { color: #cf1322; }
.vno { font-family: monospace; color: #1677ff; }
.red-tag { display: inline-block; font-size: 10px; background: #fff1f0; color: #cf1322; border: 1px solid #ffa39e; border-radius: 8px; padding: 0 6px; margin-left: 4px; }
.tag { display: inline-block; padding: 1px 8px; border-radius: 10px; font-size: 11px; }
.tag.ok { background: #f6ffed; color: #389e0d; }
.tag.info { background: #e6f4ff; color: #1677ff; }
.empty { text-align: center; color: #bbb; padding: 30px; font-size: 13px; }
.sub-title { padding: 10px 12px; border-bottom: 1px solid #f0f0f0; display: flex; gap: 14px; align-items: center; }
.toast-inline { padding: 8px 12px; border-radius: 6px; margin-bottom: 10px; font-size: 13px; background: #f6ffed; border: 1px solid #b7eb8f; color: #389e0d; }
.toast-inline.err { background: #fff1f0; border-color: #ffa39e; color: #cf1322; }
</style>
