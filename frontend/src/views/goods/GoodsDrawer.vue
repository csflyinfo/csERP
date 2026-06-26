<script setup>
import { ref, watch, computed, nextTick } from 'vue'
import BaseInfoForm from './components/BaseInfoForm.vue'
import MultiUnitMatrix from './components/MultiUnitMatrix.vue'
import StockPurchaseForm from './components/StockPurchaseForm.vue'

const props = defineProps({
  visible: { type: Boolean, default: false },
  mode: { type: String, default: 'add' }, // add/edit
  goodsData: { type: Object, default: null },
})

const emit = defineEmits(['close', 'save'])

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
  minOrderQty: 0,
  stockUpperLimit: 0,
  stockLowerLimit: 0,
  defaultSupplier: '',
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
    { unitType: '小单位', unitName: '瓶', barcode: '', convertQty: 1, standardPrice: 0, weight: 0, volume: 0, minOrderQty: 0, isSaleUnit: true, isPurchaseUnit: true, enabled: true },
    { unitType: '中单位', unitName: '', barcode: '', convertQty: 12, standardPrice: 0, weight: 0, volume: 0, minOrderQty: 0, isSaleUnit: true, isPurchaseUnit: true, enabled: false },
    { unitType: '大单位', unitName: '', barcode: '', convertQty: 24, standardPrice: 0, weight: 0, volume: 0, minOrderQty: 0, isSaleUnit: true, isPurchaseUnit: true, enabled: false },
  ],
}

const formModel = ref({ ...defaultForm })

// 监听打开抽屉，重置表单和错误
watch(() => props.visible, (val) => {
  if (val) {
    formErrors.value = {}
    if (props.mode === 'edit' && props.goodsData) {
      formModel.value = { ...props.goodsData }
    } else {
      formModel.value = JSON.parse(JSON.stringify(defaultForm))
    }
  }
})

// 表单验证
function validateForm() {
  formErrors.value = {}
  const errors = {}

  // 商品名称必填验证
  if (!formModel.value.goodsName || !formModel.value.goodsName.trim()) {
    errors.goodsName = '商品名称不能为空'
  }

  // 小单位名称必填
  const baseUnit = formModel.value.units[0]
  if (!baseUnit.unitName || !baseUnit.unitName.trim()) {
    errors.baseUnitName = '基本单位名称不能为空'
  }

  formErrors.value = errors
  return Object.keys(errors).length === 0
}

function closeDrawer() {
  emit('close')
}

function saveAndExit() {
  if (!validateForm()) {
    const firstError = Object.values(formErrors.value)[0]
    alert(firstError)
    return
  }
  console.log('保存并退出:', formModel.value)
  emit('save', formModel.value)
  alert('商品保存成功！')
  closeDrawer()
}

function saveOnly() {
  if (!validateForm()) {
    const firstError = Object.values(formErrors.value)[0]
    alert(firstError)
    return
  }
  console.log('保存:', formModel.value)
  emit('save', formModel.value)
  alert('商品保存成功！')
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
            <span class="drawer-title">{{ mode === 'edit' ? '编辑商品' : '新增商品' }}</span>
            <span class="goods-code" v-if="formModel.goodsCode">
              编码：{{ formModel.goodsCode }}
            </span>
          </div>
          <div class="action-btns">
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
            <BaseInfoForm :model-value="formModel" />
          </div>

          <!-- 多单位设置 -->
          <div id="section-unit" class="section-card">
            <div class="section-title">多单位设置</div>
            <MultiUnitMatrix
              :model-value="formModel.units"
              :is-weighted="formModel.isWeighted"
              @update:model-value="formModel.units = $event"
            />
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
          <div v-if="mode === 'edit'" class="section-card">
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

@keyframes fadeIn {
  from { opacity: 0; }
  to { opacity: 1; }
}

@keyframes slideIn {
  from { transform: translateX(100%); }
  to { transform: translateX(0); }
}
</style>
