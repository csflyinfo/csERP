<script setup>
/**
 * 客户价格调整单 新建 / 编辑 页面。
 *
 * 数据源全部走后端接口：
 *   - 客户下拉：/base/customer/page
 *   - 商品选择：CustomerPriceGoodsDialog（专用多选窗口，带出三级单位标价）
 *   - 保存：/base/customer-price-adjust/create（新建） / /update（编辑）
 *   - 编辑加载：/base/customer-price-adjust/detail?adjustId=X
 *
 * 布局：
 *   - 顶部：客户/生效方式/生效时间/有效范围
 *   - 中部：商品明细表（小/中/大三级单位标价 + 现价，未启用单位置灰）
 *   - 底部：保存/返回
 */
import { onMounted, ref, computed } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { post, get } from '../api/client.js'
import CustomerPriceGoodsDialog from '../components/CustomerPriceGoodsDialog.vue'

const router = useRouter()
const route = useRoute()
const isEdit = !!route.params.id
const adjustId = ref(route.params.id ? decodeURIComponent(String(route.params.id)) : '')

// 单据号与状态（编辑时从后端带出，仅展示）
const adjustNo = ref('')
const statusText = ref('待审核')
const loadError = ref('')

// ==================== 头部字段 ====================
const header = ref({
  customerCode: '',
  customerName: '',
  effectiveMode: 'IMMEDIATE',   // IMMEDIATE 立即 / SCHEDULED 定时
  effectiveTime: '',            // 定时生效时对应的时间
  validType: 'LONG_TERM',       // LONG_TERM / RANGE
  validFrom: '',
  validTo: '',
  remark: '',
})

// ==================== 客户下拉 ====================
const customerOpts = ref([])
async function loadCustomers() {
  try {
    const res = await post('/base/customer/page', { pageNo: 1, pageSize: 500, filters: {} })
    customerOpts.value = (res.records || []).map(r => ({
      code: r.customerCode || r.CUSTOMERCODE,
      name: r.customerName || r.CUSTOMERNAME,
    })).filter(o => o.code)
  } catch (e) {
    customerOpts.value = []
  }
}

function onCustomerChange(code) {
  header.value.customerCode = code
  const hit = customerOpts.value.find(o => o.code === code)
  header.value.customerName = hit?.name || ''
}

// ==================== 商品明细 ====================
/**
 * 明细行结构：
 * {
 *   goodsCode, goodsName, spec, barcode, brandName, categoryName, storageProperty,
 *   smallUnit, smallStandardPrice, smallCurrentPrice,
 *   mediumUnit, mediumStandardPrice, mediumCurrentPrice, mediumEnabled,
 *   largeUnit, largeStandardPrice, largeCurrentPrice, largeEnabled,
 * }
 */
const details = ref([])
const goodsDialogOpen = ref(false)

// 已添加的商品编码，传给选择窗口用于禁止重复添加
const existingCodes = computed(() => details.value.map(d => d.goodsCode))

function openGoodsDialog() {
  if (!header.value.customerCode) {
    alert('请先选择客户')
    return
  }
  goodsDialogOpen.value = true
}

/**
 * 接收商品选择窗口返回的商品数组，转为明细行。
 * 各单位标价自动回填到对应现价，用户可再修改；
 * 未启用的单位不回填（保持 null，输入框置灰）。
 */
function onGoodsSelected(rows) {
  const existing = new Set(details.value.map(d => d.goodsCode))
  ;(rows || []).forEach(row => {
    if (existing.has(row.goodsCode)) return   // 同一商品不可重复添加
    const p = row._profile || {}
    details.value.push({
      goodsCode: row.goodsCode,
      goodsName: row.goodsName,
      spec: row.spec || '',
      barcode: row.barcode || '',
      brandName: row.brandName || '',
      categoryName: row.categoryName || '',
      storageProperty: row.storageProperty || '',
      // 小单位：恒启用
      smallUnit: p.smallUnit || row.baseUnit || '',
      smallStandardPrice: p.smallStandardPrice ?? null,
      smallCurrentPrice: p.smallStandardPrice ?? null,   // 标价回显到现价
      // 中单位：停用则标价/现价都留空，避免展示档案里的占位 0
      mediumUnit: p.mediumUnit || '',
      mediumEnabled: !!p.mediumEnabled,
      mediumStandardPrice: p.mediumEnabled ? (p.mediumStandardPrice ?? null) : null,
      mediumCurrentPrice: p.mediumEnabled ? (p.mediumStandardPrice ?? null) : null,
      // 大单位：同上
      largeUnit: p.largeUnit || '',
      largeEnabled: !!p.largeEnabled,
      largeStandardPrice: p.largeEnabled ? (p.largeStandardPrice ?? null) : null,
      largeCurrentPrice: p.largeEnabled ? (p.largeStandardPrice ?? null) : null,
    })
    existing.add(row.goodsCode)
  })
  goodsDialogOpen.value = false
}

function removeDetail(index) {
  details.value.splice(index, 1)
}

/** 价格显示：null/空显示占位符「—」，否则两位小数 */
function fmtPrice(v) {
  return v == null || v === '' ? '—' : Number(v).toFixed(2)
}

/**
 * 标价显示：单位未启用时统一显示「—」。
 * 商品档案里停用的单位 standardPrice 往往是 0，直接显示 0.00 会被误读成「该单位真的卖 0 元」。
 */
function fmtStandardPrice(v, enabled) {
  return enabled ? fmtPrice(v) : '—'
}

/** 中/大单位启用状态：后端可能返回布尔或 0/1，统一归一 */
function isEnabled(v) {
  return v === true || v === 1 || v === '1' || v === 'TRUE' || v === 'true'
}

/**
 * 编辑模式：把已存在的单据回填到表单。
 * 明细的启用状态取单据快照（medium/largeUnitEnabled），
 * 不重新读商品档案 —— 单据是历史凭证，档案后续改动不应改变已开单据。
 */
async function loadExisting() {
  if (!isEdit || !adjustId.value) return
  loadError.value = ''
  try {
    const data = await get(`/base/customer-price-adjust/detail?adjustId=${encodeURIComponent(adjustId.value)}`)
    if (!data) { loadError.value = '调整单不存在'; return }

    adjustNo.value = data.adjustNo || ''
    statusText.value = data.statusText || '待审核'
    // 后端返回的真实主键（列表可能传的是单号）
    if (data.adjustId) adjustId.value = String(data.adjustId)

    header.value = {
      customerCode: data.customerCode || '',
      customerName: data.customerName || '',
      effectiveMode: data.effectiveMode || 'IMMEDIATE',
      effectiveTime: data.effectiveTime ? String(data.effectiveTime).replace(' ', 'T').slice(0, 16) : '',
      validType: data.validType || 'LONG_TERM',
      validFrom: data.validFrom || '',
      validTo: data.validTo || '',
      remark: data.remark || '',
    }

    details.value = (data.details || []).map(d => ({
      goodsCode: d.goodsCode,
      goodsName: d.goodsName,
      spec: d.spec || '',
      barcode: d.barcode || '',
      brandName: d.brandName || '',
      categoryName: d.categoryName || '',
      storageProperty: d.storageProperty || '',
      smallUnit: d.smallUnit || d.baseUnit || '',
      smallStandardPrice: d.smallStandardPrice ?? null,
      smallCurrentPrice: d.smallCurrentPrice ?? null,
      mediumUnit: d.mediumUnit || '',
      mediumEnabled: isEnabled(d.mediumUnitEnabled),
      mediumStandardPrice: d.mediumStandardPrice ?? null,
      mediumCurrentPrice: d.mediumCurrentPrice ?? null,
      largeUnit: d.largeUnit || '',
      largeEnabled: isEnabled(d.largeUnitEnabled),
      largeStandardPrice: d.largeStandardPrice ?? null,
      largeCurrentPrice: d.largeCurrentPrice ?? null,
    }))
  } catch (e) {
    loadError.value = '加载失败：' + (e.message || '未知错误')
  }
}

// ==================== 保存 ====================
async function saveAdjust() {
  if (!header.value.customerCode) return alert('请选择客户')
  if (!details.value.length) return alert('请至少选择一条商品明细')

  // 每行至少填一个启用单位的现价
  const bad = details.value.find(d => {
    const hasSmall = d.smallCurrentPrice != null && d.smallCurrentPrice !== ''
    const hasMedium = d.mediumEnabled && d.mediumCurrentPrice != null && d.mediumCurrentPrice !== ''
    const hasLarge = d.largeEnabled && d.largeCurrentPrice != null && d.largeCurrentPrice !== ''
    return !hasSmall && !hasMedium && !hasLarge
  })
  if (bad) return alert(`商品【${bad.goodsCode}】请至少填写一个单位的现价`)

  const payload = {
    customerId: header.value.customerCode,
    effectiveMode: header.value.effectiveMode,
    effectiveTime: header.value.effectiveTime || null,
    validType: header.value.validType,
    validFrom: header.value.validFrom || null,
    validTo: header.value.validTo || null,
    remark: header.value.remark,
    details: details.value.map(d => ({
      goodsId: d.goodsCode,
      unitId: d.smallUnit,
      // currentPrice 保留兼容老接口校验（@NotNull），取小单位现价
      currentPrice: Number(d.smallCurrentPrice) || 0,
      smallCurrentPrice: d.smallCurrentPrice != null && d.smallCurrentPrice !== '' ? Number(d.smallCurrentPrice) : null,
      mediumCurrentPrice: d.mediumEnabled && d.mediumCurrentPrice != null && d.mediumCurrentPrice !== '' ? Number(d.mediumCurrentPrice) : null,
      largeCurrentPrice: d.largeEnabled && d.largeCurrentPrice != null && d.largeCurrentPrice !== '' ? Number(d.largeCurrentPrice) : null,
    })),
  }

  try {
    if (isEdit) {
      await post('/base/customer-price-adjust/update', { ...payload, adjustId: adjustId.value })
      alert(`保存成功：${adjustNo.value || adjustId.value}`)
    } else {
      const res = await post('/base/customer-price-adjust/create', payload)
      alert(`保存成功：${res.adjustNo}`)
    }
    router.push('/customer-price')
  } catch (e) {
    alert('保存失败：' + (e.message || '未知错误'))
  }
}

function goBack() { router.push('/customer-price') }

onMounted(async () => {
  await loadCustomers()
  await loadExisting()
})
</script>

<template>
  <section class="form-card">
    <div class="form-head">
      <b>{{ isEdit ? '编辑' : '新建' }} - 客户价格调整单</b>
      <span v-if="adjustNo" class="head-no">{{ adjustNo }}</span>
      <span class="badge" :class="statusText === '已审核' ? 'ok' : 'wait'" style="margin-left:10px">{{ statusText }}</span>
      <div style="flex:1"></div>
      <button class="btn primary" @click="saveAdjust">保存</button>
      <button class="btn" @click="goBack">返回列表</button>
    </div>

    <div v-if="loadError" class="tips-inline" style="color:#dc2626">{{ loadError }}</div>

    <div class="form-body">
      <!-- 头部 -->
      <div class="section">
        <h3>调价信息</h3>
        <div class="grid4">
          <div class="field">
            <label>客户 <span style="color:#f56c6c">*</span></label>
            <select :value="header.customerCode" @change="onCustomerChange($event.target.value)">
              <option value="">请选择客户</option>
              <option v-for="o in customerOpts" :key="o.code" :value="o.code">
                {{ o.code }} - {{ o.name }}
              </option>
            </select>
          </div>
          <div class="field">
            <label>生效方式 <span style="color:#f56c6c">*</span></label>
            <select v-model="header.effectiveMode">
              <option value="IMMEDIATE">立即生效</option>
              <option value="SCHEDULED">定时生效</option>
            </select>
          </div>
          <div class="field" v-if="header.effectiveMode === 'SCHEDULED'">
            <label>生效时间</label>
            <input type="datetime-local" v-model="header.effectiveTime" />
          </div>
          <div class="field">
            <label>价格有效范围 <span style="color:#f56c6c">*</span></label>
            <select v-model="header.validType">
              <option value="LONG_TERM">长期有效</option>
              <option value="RANGE">按时间段有效</option>
            </select>
          </div>
          <template v-if="header.validType === 'RANGE'">
            <div class="field">
              <label>有效自</label>
              <input type="date" v-model="header.validFrom" />
            </div>
            <div class="field">
              <label>有效至</label>
              <input type="date" v-model="header.validTo" />
            </div>
          </template>
          <div class="field field-full">
            <label>备注</label>
            <input v-model="header.remark" placeholder="选填" />
          </div>
        </div>
      </div>

      <!-- 明细 -->
      <div class="section">
        <h3>调价商品明细</h3>
        <div class="toolbar">
          <button class="btn primary" @click="openGoodsDialog">+ 添加商品</button>
          <span style="margin-left:12px;font-size:12px;color:#909399">共 {{ details.length }} 条</span>
          <span style="margin-left:12px;font-size:12px;color:#909399">未启用的中/大单位价格不可编辑</span>
        </div>
        <div class="scroll">
          <table class="detail-grid">
            <thead>
              <tr>
                <th style="width:40px">#</th>
                <th>商品编号</th>
                <th>商品名称</th>
                <th>规格</th>
                <th>小单位</th>
                <th class="num">小单位标价</th>
                <th class="num">小单位现价 *</th>
                <th>大单位</th>
                <th class="num">大单位标价</th>
                <th class="num">大单位现价</th>
                <th>中单位</th>
                <th class="num">中单位标价</th>
                <th class="num">中单位现价</th>
                <th>品牌</th>
                <th>商品分类</th>
                <th>存储属性</th>
                <th style="width:60px">操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-if="details.length === 0">
                <td colspan="17" style="text-align:center;color:#909399;padding:30px">
                  请先选择客户并点「+ 添加商品」录入明细
                </td>
              </tr>
              <tr v-for="(row, i) in details" :key="row.goodsCode">
                <td>{{ i + 1 }}</td>
                <td>{{ row.goodsCode }}</td>
                <td>{{ row.goodsName }}</td>
                <td>{{ row.spec || '-' }}</td>

                <!-- 小单位（恒启用） -->
                <td>{{ row.smallUnit || '-' }}</td>
                <td class="num std">{{ fmtPrice(row.smallStandardPrice) }}</td>
                <td class="num">
                  <input type="number" step="0.01" min="0" class="price-input"
                    v-model.number="row.smallCurrentPrice" />
                </td>

                <!-- 大单位 -->
                <td :class="{ off: !row.largeEnabled }">{{ row.largeUnit || '—' }}</td>
                <td class="num std" :class="{ off: !row.largeEnabled }">{{ fmtStandardPrice(row.largeStandardPrice, row.largeEnabled) }}</td>
                <td class="num">
                  <input type="number" step="0.01" min="0" class="price-input"
                    :disabled="!row.largeEnabled" placeholder="—"
                    v-model.number="row.largeCurrentPrice" />
                </td>

                <!-- 中单位 -->
                <td :class="{ off: !row.mediumEnabled }">{{ row.mediumUnit || '—' }}</td>
                <td class="num std" :class="{ off: !row.mediumEnabled }">{{ fmtStandardPrice(row.mediumStandardPrice, row.mediumEnabled) }}</td>
                <td class="num">
                  <input type="number" step="0.01" min="0" class="price-input"
                    :disabled="!row.mediumEnabled" placeholder="—"
                    v-model.number="row.mediumCurrentPrice" />
                </td>

                <td>{{ row.brandName || '-' }}</td>
                <td>{{ row.categoryName || '-' }}</td>
                <td>{{ row.storageProperty || '-' }}</td>
                <td>
                  <button class="link link-btn danger-link" @click="removeDetail(i)">删除</button>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
    </div>

    <div class="summary">
      <span>审核后价格生效，旧的有效价自动停用</span>
      <div class="spacer"></div>
      <button class="btn" @click="goBack">取消</button>
      <button class="btn primary" @click="saveAdjust">保存</button>
    </div>

    <!-- 添加商品窗口 —— 客户价格调整专用，多选不退出，带出三级单位标价 -->
    <CustomerPriceGoodsDialog
      :visible="goodsDialogOpen"
      :existing-codes="existingCodes"
      @confirm="onGoodsSelected"
      @close="goodsDialogOpen = false" />
  </section>
</template>

<style scoped>
.field-full { grid-column: 1 / -1; }
.head-no { margin-left: 10px; font-size: 12px; color: #606266; }
.section h3 { margin: 0 0 12px; font-size: 14px; color: #303133; }
.toolbar { margin-bottom: 10px; display: flex; align-items: center; }
.scroll { overflow-x: auto; }
.scroll table { width: 100%; border-collapse: collapse; font-size: 12px; }
.scroll th { background: #f5f7fa; padding: 8px 8px; text-align: left; font-weight: 600; color: #303133; border-bottom: 1px solid #e5e7eb; white-space: nowrap; }
.scroll td { padding: 5px 8px; border-bottom: 1px solid #f0f0f0; white-space: nowrap; }
.scroll .num { text-align: right; font-variant-numeric: tabular-nums; }

/* 标价列：只读展示，未启用单位置灰 */
.detail-grid td.std { color: #303133; }
.detail-grid td.off,
.detail-grid td.std.off { color: #c0c4cc; background: #f8fafc; }

/* 现价输入框 */
.detail-grid .price-input {
  width: 88px; height: 26px; padding: 0 6px; text-align: right;
  border: 1px solid #dcdfe6; border-radius: 4px; font-size: 12px;
  font-variant-numeric: tabular-nums;
}
.detail-grid .price-input:disabled {
  background: #f5f7fa; color: #c0c4cc; cursor: not-allowed;
}
</style>