<script setup>
import { ref, watch, computed, nextTick } from 'vue'
import { post } from '../../api/client.js'
import BaseInfoForm from './components/BaseInfoForm.vue'
import MultiUnitMatrix from './components/MultiUnitMatrix.vue'
import StockPurchaseForm from './components/StockPurchaseForm.vue'

const props = defineProps({
  visible: { type: Boolean, default: false },
  mode: { type: String, default: 'add' }, // add/edit
  goodsData: { type: Object, default: null },
})

const emit = defineEmits(['close', 'save'])

// 当前实际模式（复制新增时会从 edit 切换到 add）
const currentMode = ref(props.mode)
watch(() => props.mode, (val) => { currentMode.value = val })

const tabs = [
  { key: 'base', label: '基本信息' },
  { key: 'unit', label: '多单位设置' },
  { key: 'stock', label: '采购与库存' },
]

// 表单验证错误
const formErrors = ref({})

// 滚动到指定区域
function scrollToSection(sectionKey) {
  nextTick(() => {
    const element = document.getElementById(`section-${sectionKey}`)
    if (element) {
      element.scrollIntoView({ behavior: 'smooth', block: 'start' })
    }
  })
}

const defaultForm = {
  goodsId: '',
  goodsCode: '',
  goodsName: '',
  spec: '',
  simpleCode: '',
  categoryName: '',
  brandName: '',
  goodsType: '正常商品',
  goodsLevel: '',
  defaultWarehouse: '',
  goodsOwnerId: '',
  goodsManager: '',
  storageProperty: '常温',
  shelfLifeDays: 0,
  warningDays: 0,
  origin: '',
  taxRate: '',
  isWeighted: false,
  isPresale: false,
  canSale: true,
  canPurchase: true,
  canReturn: true,
  standardPrice: 0,
  latestPurchasePrice: 0,
  minSalePrice: 0,
  suggestedRetailPrice: 0,
  wholesalePrice: 0,
  memberPrice: 0,
  retailPrice: 0,
  minOrderQty: 0,
  stockUpperLimit: 0,
  stockLowerLimit: 0,
  defaultSupplier: '',
  /** 默认采购单位（单位名称）；留空时采购单据按「大→中→小」默认逻辑 */
  defaultPurchaseUnit: '',
  palletQty: 0,
  stackLayers: 0,
  baseWeight: 0,
  baseVolume: 0,
  goodsIntro: '',
  remark: '',
  status: '正常',
  createTime: '',
  createBy: '',
  updateTime: '',
  updateBy: '',
  units: [
    { unitType: '小单位', unitName: '', barcode: '', convertQty: 1, standardPrice: 0, minPrice: 0, suggestRetailPrice: 0, weight: 0, volume: 0, minOrderQty: 0, isSaleUnit: true, isPurchaseUnit: true, enabled: true },
    { unitType: '中单位', unitName: '', barcode: '', convertQty: 12, standardPrice: 0, minPrice: 0, suggestRetailPrice: 0, weight: 0, volume: 0, minOrderQty: 0, isSaleUnit: true, isPurchaseUnit: true, enabled: false },
    { unitType: '大单位', unitName: '', barcode: '', convertQty: 24, standardPrice: 0, minPrice: 0, suggestRetailPrice: 0, weight: 0, volume: 0, minOrderQty: 0, isSaleUnit: true, isPurchaseUnit: true, enabled: false },
  ],
}

const formModel = ref(JSON.parse(JSON.stringify(defaultForm)))

// 将列表行数据反解为表单对象
function parseRowToForm(row) {
  if (!row) return null
  if (row.goodsCode && !row.c1) return row
  const raw = row._raw || {}
  const form = JSON.parse(JSON.stringify(defaultForm))
  form.goodsId = raw.goodsId || ''
  form.goodsCode = raw.goodsCode || row.c1 || ''
  form.goodsName = raw.goodsName || row.c2 || ''
  form.goodsType = raw.goodsType || '正常商品'
  form.spec = raw.spec || ''
  form.categoryName = raw.categoryName || ''
  form.brandName = raw.brandName || ''
  form.baseUnit = raw.baseUnit || ''
  form.barcode = raw.barcode || ''
  form.standardPrice = raw.standardPrice != null ? Number(raw.standardPrice) : 0
  form.latestPurchasePrice = raw.latestPurchasePrice != null ? Number(raw.latestPurchasePrice) : 0
  form.minSalePrice = raw.minSalePrice != null ? Number(raw.minSalePrice) : 0
  form.suggestedRetailPrice = raw.suggestedRetailPrice != null ? Number(raw.suggestedRetailPrice) : 0
  form.wholesalePrice = raw.wholesalePrice != null ? Number(raw.wholesalePrice) : 0
  form.memberPrice = raw.memberPrice != null ? Number(raw.memberPrice) : 0
  form.retailPrice = raw.retailPrice != null ? Number(raw.retailPrice) : 0
  form.stockUpperLimit = raw.stockUpperLimit != null ? Number(raw.stockUpperLimit) : 0
  form.stockLowerLimit = raw.stockLowerLimit != null ? Number(raw.stockLowerLimit) : 0
  form.shelfLifeDays = raw.shelfLifeDays != null ? Number(raw.shelfLifeDays) : 0
  form.storageProperty = raw.storageProperty || '常温'
  form.defaultSupplier = raw.defaultSupplier || ''
  form.defaultPurchaseUnit = raw.defaultPurchaseUnit || ''
  form.defaultWarehouse = raw.defaultWarehouse || ''
  form.canReturn = raw.canReturn !== false
  form.status = raw.status === 'STOPPED' ? '停用' : '正常'
  form.simpleCode = raw.simpleCode || ''
  form.goodsLevel = raw.goodsLevel || ''
  form.taxRate = raw.taxRate || ''
  form.goodsManager = raw.goodsManager || ''
  form.isWeighted = raw.isWeighted === true
  form.isPresale = raw.isPresale === true
  form.canSale = raw.canSale !== false
  form.canPurchase = raw.canPurchase !== false
  form.origin = raw.origin || ''
  form.warningDays = raw.warningDays != null ? Number(raw.warningDays) : 0
  form.minOrderQty = raw.minOrderQty != null ? Number(raw.minOrderQty) : 0
  form.palletQty = raw.palletQty != null ? Number(raw.palletQty) : 0
  form.stackLayers = raw.stackLayers != null ? Number(raw.stackLayers) : 0
  form.baseWeight = raw.baseWeight != null ? Number(raw.baseWeight) : 0
  form.baseVolume = raw.baseVolume != null ? Number(raw.baseVolume) : 0
  form.goodsIntro = raw.goodsIntro || ''
  form.remark = raw.remark || ''

  // 回填 units[0]
  if (form.units && form.units[0]) {
    form.units[0].unitName = form.baseUnit || ''
    form.units[0].barcode = form.barcode || ''
    form.units[0].standardPrice = form.standardPrice || 0
    form.units[0].minPrice = form.minSalePrice || 0
    form.units[0].suggestRetailPrice = form.suggestedRetailPrice || 0
    form.units[0].enabled = true
  }
  // 从 unitConfig JSON 反序列化完整 units
  if (raw.unitConfig) {
    try {
      const parsedUnits = typeof raw.unitConfig === 'string' ? JSON.parse(raw.unitConfig) : raw.unitConfig
      if (Array.isArray(parsedUnits) && parsedUnits.length > 0) {
        form.units = parsedUnits
      }
    } catch (e) {
      console.warn('解析 unitConfig 失败', e)
    }
  }
  return form
}

// 监听打开抽屉，重置表单和错误
watch(() => props.visible, (val) => {
  if (val) {
    formErrors.value = {}
    currentMode.value = props.mode
    if (props.mode === 'edit' && props.goodsData) {
      const parsed = parseRowToForm(props.goodsData)
      formModel.value = parsed || JSON.parse(JSON.stringify(defaultForm))
    } else {
      formModel.value = JSON.parse(JSON.stringify(defaultForm))
    }
  }
})

// 表单验证 — 7个必填字段
function validateForm() {
  formErrors.value = {}
  const errors = {}
  const m = formModel.value

  if (!m.goodsCode || !String(m.goodsCode).trim()) errors.goodsCode = '商品编码不能为空'
  if (!m.goodsName || !String(m.goodsName).trim()) errors.goodsName = '商品名称不能为空'
  if (!m.categoryName || !String(m.categoryName).trim()) errors.categoryName = '商品分类不能为空'
  if (!m.brandName || !String(m.brandName).trim()) errors.brandName = '品牌不能为空'
  if (!m.spec || !String(m.spec).trim()) errors.spec = '规格型号不能为空'
  if (!m.defaultWarehouse || !String(m.defaultWarehouse).trim()) errors.defaultWarehouse = '默认仓库不能为空'
  const baseUnit = m.units?.[0]
  if (!baseUnit || !baseUnit.unitName || !String(baseUnit.unitName).trim()) errors.baseUnitName = '小单位名称不能为空'

  formErrors.value = errors
  return Object.keys(errors).length === 0
}

function closeDrawer() {
  emit('close')
}

async function doSave() {
  if (!validateForm()) {
    const firstError = Object.values(formErrors.value)[0]
    alert(firstError)
    return null
  }
  const isEdit = currentMode.value === 'edit'
  const endpoint = isEdit ? '/base/goods/update' : '/base/goods/create'
  const payload = { ...formModel.value }
  // 从小单位提取 baseUnit / barcode / 价格
  const baseUnit = formModel.value.units?.[0]
  if (baseUnit) {
    payload.baseUnit = baseUnit.unitName || payload.baseUnit
    payload.barcode = baseUnit.barcode || payload.barcode || ''
    if (baseUnit.standardPrice != null) payload.standardPrice = baseUnit.standardPrice
    if (baseUnit.minPrice != null) payload.minSalePrice = baseUnit.minPrice
    if (baseUnit.suggestRetailPrice != null) payload.suggestedRetailPrice = baseUnit.suggestRetailPrice
  }
  try {
    const result = await post(endpoint, payload)
    return result || payload
  } catch (error) {
    alert('保存失败：' + (error.message || '未知错误'))
    return null
  }
}

async function saveAndExit() {
  const result = await doSave()
  if (result) {
    emit('save', result)
    closeDrawer()
  }
}

async function saveOnly() {
  const result = await doSave()
  if (result) {
    emit('save', result)
  }
}

// 复制新增：保留所有字段，清空商品编码，切换到新增模式
function copyAsNew() {
  const cloned = JSON.parse(JSON.stringify(formModel.value))
  cloned.goodsCode = ''
  cloned.goodsId = ''
  formModel.value = cloned
  currentMode.value = 'add'
  formErrors.value = {}
  alert('已复制商品信息，请填写新的商品编码后保存')
}

/** 快速调价：创建商品调价单草稿 → 打开商品调价抽屉 */
async function onQuickAdjust() {
  if (!formModel.value.goodsCode || !formModel.value.goodsName) {
    alert('请先保存商品后再进行快速调价')
    return
  }
  if (!confirm('将跳转到商品调价单，当前商品价格信息不会被保存，是否继续？\n\n提示：快速调价将创建一个仅包含该商品的调价单草稿，审核后价格生效。')) return

  try {
    const result = await post('/base/goods-price-adjust/save', {
      goodsCode: formModel.value.goodsCode,
      goodsName: formModel.value.goodsName,
      goodsLocked: true,
      remark: `快速调价 - ${formModel.value.goodsCode} ${formModel.value.goodsName}`,
      items: []
    })
    emit('save', null)
    closeDrawer()
    const app = (await import('../../stores/app.js')).useAppStore()
    app.openGoodsPriceAdjustDrawer(result.orderId, 'edit')
  } catch (e) {
    alert('创建调价单失败：' + (e.message || '未知错误'))
  }
}
</script>

<template>
  <!-- 抽屉遮罩 -->
  <div v-show="visible" class="drawer-overlay" @click.self="closeDrawer">
    <div class="drawer-container">
      <!-- 抽屉顶部：标题 + Tab + 操作按钮 -->
      <div class="drawer-header">
        <div class="header-top">
          <div class="title-row">
            <span class="drawer-title">{{ currentMode === 'edit' ? '编辑商品' : '新增商品' }}</span>
            <span class="goods-code" v-if="formModel.goodsCode">
              编码：{{ formModel.goodsCode }}
            </span>
          </div>
          <div class="action-btns">
            <button v-if="props.mode === 'edit'" class="btn btn-copy" @click="copyAsNew">
              复制新增
            </button>
            <button class="btn btn-default" @click="closeDrawer">
              退出
            </button>
            <button class="btn btn-primary" @click="saveOnly">
              保存
            </button>
            <button class="btn btn-primary btn-save-exit" @click="saveAndExit">
              保存并退出
            </button>
          </div>
        </div>

        <!-- Tab 导航（作为锚点使用） -->
        <div class="tab-navigation">
          <div
            v-for="tab in tabs"
            :key="tab.key"
            class="tab-item"
            @click="scrollToSection(tab.key)"
          >
            {{ tab.label }}
          </div>
        </div>
      </div>

      <!-- 抽屉内容区域 - 所有内容在一页显示 -->
      <div class="drawer-body">
        <div class="content-inner">
          <!-- 基础信息 -->
          <div id="section-base" class="section-card">
            <div class="section-title">基本信息</div>
            <BaseInfoForm :model-value="formModel" :reload-trigger="visible ? 1 : 0" />
          </div>

          <!-- 多单位设置 -->
          <div id="section-unit" class="section-card">
            <div class="section-title">多单位设置</div>
            <MultiUnitMatrix
              :model-value="formModel.units"
              :is-weighted="formModel.isWeighted"
              :visible="visible"
              :goods-code="formModel.goodsCode"
              :goods-name="formModel.goodsName"
              :readonly="currentMode === 'edit'"
              @update:model-value="formModel.units = $event"
              @quick-adjust="onQuickAdjust"
            />
          </div>

          <!-- 价格信息 -->
          <div id="section-price" class="section-card">
            <div class="section-title">价格信息</div>
            <div class="price-info-grid">
              <div class="field">
                <label>批发价</label>
                <input type="number" v-model.number="formModel.wholesalePrice" placeholder="0.00" step="0.01" />
              </div>
              <div class="field">
                <label>会员价</label>
                <input type="number" v-model.number="formModel.memberPrice" placeholder="0.00" step="0.01" />
              </div>
              <div class="field">
                <label>零售价</label>
                <input type="number" v-model.number="formModel.retailPrice" placeholder="0.00" step="0.01" />
              </div>
            </div>
          </div>

          <!-- 采购与库存 -->
          <div id="section-stock" class="section-card">
            <div class="section-title">采购与库存</div>
            <StockPurchaseForm :model-value="formModel" />
          </div>

          <!-- 备注和商品介绍（放在页面最下面） -->
          <div class="section-card bottom-section">
            <div class="section-title">备注与介绍</div>
            <div class="form-grid">
              <div class="form-item full-width">
                <label>商品介绍</label>
                <textarea v-model="formModel.goodsIntro" placeholder="请输入商品介绍" rows="4"></textarea>
              </div>
              <div class="form-item full-width">
                <label>备注</label>
                <textarea v-model="formModel.remark" placeholder="请输入备注信息" rows="3"></textarea>
              </div>
            </div>
          </div>

          <!-- 其他信息（编辑时只读展示） -->
          <div v-if="currentMode === 'edit'" class="section-card">
            <div class="section-title">其他信息</div>
            <div class="other-info-card">
              <div class="info-row">
                <span class="label">创建时间</span>
                <span class="value">{{ formModel.createTime || '-' }}</span>
              </div>
              <div class="info-row">
                <span class="label">创建人</span>
                <span class="value">{{ formModel.createBy || '-' }}</span>
              </div>
              <div class="info-row">
                <span class="label">更新时间</span>
                <span class="value">{{ formModel.updateTime || '-' }}</span>
              </div>
              <div class="info-row">
                <span class="label">更新人</span>
                <span class="value">{{ formModel.updateBy || '-' }}</span>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
/* ============ 大抽屉样式 ============ */
.drawer-overlay {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background: rgba(0, 0, 0, 0.4);
  z-index: 500;
  display: flex;
  justify-content: flex-end;
  animation: fadeIn 0.2s ease;
}
/* v-show隐藏时确保不阻挡点击 */
.drawer-overlay[style*="display: none"] {
  pointer-events: none !important;
}

.drawer-container {
  width: 80%;
  max-width: 1000px;
  height: 100%;
  background: #fff;
  box-shadow: -2px 0 10px rgba(0, 0, 0, 0.15);
  display: flex;
  flex-direction: column;
  animation: slideIn 0.3s ease;
}

/* 抽屉头部 */
.drawer-header {
  border-bottom: 1px solid #e4e7ed;
  background: #fff;
  flex-shrink: 0;
}

.header-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 16px;
}

.title-row {
  display: flex;
  align-items: center;
  gap: 12px;
}

.drawer-title {
  font-size: 15px;
  font-weight: 600;
  color: #303133;
}

.goods-code {
  padding: 2px 8px;
  background: #ecf5ff;
  color: #409eff;
  border-radius: 3px;
  font-size: 11px;
  font-weight: 500;
}

.action-btns {
  display: flex;
  gap: 8px;
}

.btn {
  padding: 5px 14px;
  border-radius: 4px;
  font-size: 12px;
  font-weight: 500;
  cursor: pointer;
  border: 1px solid #dcdfe6;
  background: #fff;
  color: #606266;
  transition: all 0.2s;
  height: 28px;
}

.btn:hover {
  color: #409eff;
  border-color: #c6e2ff;
  background: #ecf5ff;
}

.btn-default {
  background: #fff;
  border-color: #dcdfe6;
  color: #606266;
}

.btn-default:hover {
  color: #409eff;
  border-color: #c6e2ff;
  background: #ecf5ff;
}

.btn-primary {
  background: #409eff;
  color: #fff;
  border-color: #409eff;
}

.btn-primary:hover {
  background: #66b1ff;
  border-color: #66b1ff;
}

.btn-save-exit {
  background: #67c23a;
  border-color: #67c23a;
}

.btn-save-exit:hover {
  background: #85ce61;
  border-color: #85ce61;
}

.btn-copy {
  background: #e6a23c;
  border-color: #e6a23c;
  color: #fff;
}

.btn-copy:hover {
  background: #ebb563;
  border-color: #ebb563;
}

/* Tab 导航（作为锚点按钮） */
.tab-navigation {
  display: flex;
  gap: 8px;
  padding: 0 16px 12px;
  background: #fff;
  border-bottom: 1px solid #e4e7ed;
  position: sticky;
  top: 0;
  z-index: 10;
}

.tab-item {
  padding: 6px 16px;
  cursor: pointer;
  transition: all 0.2s;
  color: #606266;
  font-size: 12px;
  border-radius: 4px;
  background: #f5f7fa;
  border: 1px solid #e4e7ed;
}

.tab-item:hover {
  color: #409eff;
  background: #ecf5ff;
  border-color: #c6e2ff;
}

/* 抽屉内容 */
.drawer-body {
  flex: 1;
  overflow-y: auto;
  background: #f5f7fa;
  scroll-behavior: smooth;
}

.content-inner {
  padding: 16px;
}

/* 区块卡片样式 */
.section-card {
  background: #fff;
  border-radius: 8px;
  padding: 20px;
  margin-bottom: 16px;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.05);
}

.section-card.bottom-section {
  margin-bottom: 0;
}

.section-title {
  font-size: 14px;
  font-weight: 600;
  color: #303133;
  margin-bottom: 16px;
  padding-bottom: 12px;
  border-bottom: 1px solid #f0f2f5;
}

/* 表单网格布局 */
.form-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 16px 24px;
}

.form-item {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.form-item.full-width {
  grid-column: 1 / -1;
}

.form-item label {
  font-size: 12px;
  color: #606266;
  font-weight: 500;
}

.form-item input,
.form-item select,
.form-item textarea {
  padding: 8px 12px;
  border: 1px solid #dcdfe6;
  border-radius: 4px;
  font-size: 13px;
  color: #303133;
  transition: all 0.2s;
  outline: none;
}

.form-item input:focus,
.form-item select:focus,
.form-item textarea:focus {
  border-color: #409eff;
  box-shadow: 0 0 0 2px rgba(64, 158, 255, 0.1);
}

.form-item textarea {
  resize: vertical;
  min-height: 80px;
  font-family: inherit;
}

.form-item textarea::placeholder {
  color: #c0c4cc;
}

/* 其他信息只读卡片 */
.other-info-card {
  background: #fafafa;
  border-radius: 6px;
  border: 1px solid #e5e7eb;
  padding: 16px;
}

.info-row {
  display: flex;
  padding: 8px 0;
  border-bottom: 1px solid #f0f2f5;
}

.info-row:last-child {
  border-bottom: none;
}

.info-row .label {
  width: 100px;
  font-size: 12px;
  color: #909399;
  text-align: right;
  padding-right: 12px;
}

.info-row .value {
  flex: 1;
  font-size: 12px;
  color: #606266;
}

/* 价格信息网格 —— 批发价 / 会员价 / 零售价 */
.price-info-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 12px 16px;
  padding: 8px 0;
}
.price-info-grid .field {
  display: flex;
  flex-direction: row;
  align-items: center;
  gap: 8px;
}
.price-info-grid .field label {
  font-size: 12px;
  font-weight: 600;
  color: #303133;
  min-width: 72px;
  text-align: right;
  flex-shrink: 0;
  line-height: 32px;
}
.price-info-grid .field input {
  flex: 1;
  height: 32px; padding: 0 10px;
  border: 1px solid #dcdfe6; border-radius: 4px;
  font-size: 12px; color: #606266;
  transition: all 0.2s;
  box-sizing: border-box; min-width: 0;
  outline: none; background: #fff;
}
.price-info-grid .field input:focus {
  border-color: #409eff;
  box-shadow: 0 0 0 2px rgba(64, 158, 255, 0.1);
}

@keyframes fadeIn {
  from { opacity: 0; }
  to { opacity: 1; }
}

@keyframes slideIn {
  from { transform: translateX(100%); }
  to { transform: translateX(0); }
}
</style>
