<script setup>
import { ref, computed, watch } from 'vue'

const props = defineProps({
  visible: { type: Boolean, default: false },
  selectedRows: { type: Array, default: () => [] },
})

const emit = defineEmits(['close', 'save'])

// PRD 定义的批量编辑字段配置（不含价格相关字段）
const batchEditFieldsConfig = [
  { key: 'goodsType', label: '商品类型', type: 'select', options: ['正常商品', '组合商品', '赠品', '服务商品'] },
  { key: 'brandName', label: '品牌', type: 'select', options: ['农夫山泉', '可口可乐', '百草味', '统一企业', '康师傅'] },
  { key: 'categoryName', label: '分类', type: 'select', options: ['瓶装水', '饮料', '方便食品', '日化百货', '休闲零食'] },
  { key: 'defaultWarehouse', label: '默认仓库', type: 'select', options: ['总仓', '东区仓', '西区仓', '南区仓', '北区仓'] },
  { key: 'defaultSupplier', label: '默认供应商', type: 'select', options: ['农夫山泉杭州经销', '可口可乐经销', '百草味供应商', '统一企业'] },
  { key: 'goodsManager', label: '商品负责人', type: 'select', options: ['张三', '李四', '王五', '赵六'] },
  { key: 'storageProperty', label: '存储属性', type: 'select', options: ['常温', '冷藏', '冷冻', '恒温', '避光'] },
  { key: 'shelfLifeDays', label: '保质期(天)', type: 'number', placeholder: '0' },
  { key: 'stockUpperLimit', label: '库存上限', type: 'number', placeholder: '0' },
  { key: 'stockLowerLimit', label: '库存下限', type: 'number', placeholder: '0' },
  { key: 'nearExpiryDays', label: '临期预警天数', type: 'number', placeholder: '0' },
  { key: 'canSale', label: '是否可售', type: 'yesno' },
  { key: 'canPurchase', label: '是否可采', type: 'yesno' },
  { key: 'canReturn', label: '是否可退', type: 'yesno' },
  { key: 'isWeighted', label: '是否称重', type: 'yesno' },
  { key: 'isPresale', label: '是否预售', type: 'yesno' },
  { key: 'status', label: '状态', type: 'select', options: ['正常', '停用', '待审核'] },
]

// 表单数据 - 动态生成
const formModel = ref({})

// 初始化表单
function initForm() {
  const form = {}
  batchEditFieldsConfig.forEach(field => {
    let defaultValue = ''
    if (field.type === 'number') {
      defaultValue = 0
    } else if (field.type === 'yesno') {
      defaultValue = true
    } else if (field.type === 'select' && field.options.length > 0) {
      defaultValue = field.options[0]
    }
    form[field.key] = { enabled: false, value: defaultValue }
  })
  formModel.value = form
}

initForm()

// 检查是否有字段被选中
const hasSelectedField = computed(() => {
  return Object.values(formModel.value).some(field => field.enabled)
})

// 重置表单
function resetForm() {
  Object.keys(formModel.value).forEach(key => {
    const field = batchEditFieldsConfig.find(f => f.key === key)
    formModel.value[key].enabled = false
    if (field.type === 'number') {
      formModel.value[key].value = 0
    } else if (field.type === 'yesno') {
      formModel.value[key].value = true
    } else if (field.type === 'select' && field.options.length > 0) {
      formModel.value[key].value = field.options[0]
    } else {
      formModel.value[key].value = ''
    }
  })
}

// 提交保存
function handleSave() {
  if (!hasSelectedField.value) {
    alert('请至少选择一个要修改的字段')
    return
  }

  // 构建更新数据 - 只包含启用的字段
  const updateData = {}
  Object.entries(formModel.value).forEach(([key, field]) => {
    if (field.enabled) {
      updateData[key] = field.value
    }
  })

  emit('save', updateData)
  handleClose()
}

// 关闭
function handleClose() {
  resetForm()
  emit('close')
}

// 监听 visible 变化，打开时重置表单
watch(() => props.visible, (val) => {
  if (val) {
    resetForm()
  }
})
</script>

<template>
  <!-- 批量编辑独立悬浮弹窗 -->
  <div v-if="visible" class="batch-modal-overlay" @click.self="handleClose">
    <div class="batch-modal-container">
      <!-- 头部 -->
      <div class="modal-header">
        <div class="header-left">
          <span class="modal-title">批量编辑商品</span>
          <span class="selected-count">已选择 {{ selectedRows.length }} 个商品</span>
        </div>
        <button class="close-btn" @click="handleClose">×</button>
      </div>

      <!-- 内容区域 -->
      <div class="modal-body">
        <div class="batch-edit-content">
          <div class="hint-row">
            <span class="hint">勾选需要修改的字段，填写新值后点击"确认修改"，系统将批量更新所有选中的商品。</span>
          </div>

          <!-- 字段表单 - 每行3个字段 -->
          <div class="fields-grid">
            <div
              v-for="field in batchEditFieldsConfig"
              :key="field.key"
              class="field-item"
            >
              <label class="field-checkbox">
                <input type="checkbox" v-model="formModel[field.key].enabled" />
                <span>{{ field.label }}</span>
              </label>

              <!-- 是/否 选择器 -->
              <select
                v-if="field.type === 'yesno'"
                v-model="formModel[field.key].value"
                :disabled="!formModel[field.key].enabled"
                class="field-input"
              >
                <option :value="true">是</option>
                <option :value="false">否</option>
              </select>

              <!-- 下拉选择器 -->
              <select
                v-else-if="field.type === 'select'"
                v-model="formModel[field.key].value"
                :disabled="!formModel[field.key].enabled"
                class="field-input"
              >
                <option value="">请选择</option>
                <option v-for="opt in field.options" :key="opt" :value="opt">{{ opt }}</option>
              </select>

              <!-- 数字输入框 -->
              <input
                v-else-if="field.type === 'number'"
                type="number"
                v-model.number="formModel[field.key].value"
                :disabled="!formModel[field.key].enabled"
                class="field-input"
                :placeholder="field.placeholder"
                step="1"
              />

              <!-- 文本输入框 -->
              <input
                v-else
                type="text"
                v-model="formModel[field.key].value"
                :disabled="!formModel[field.key].enabled"
                class="field-input"
                :placeholder="field.placeholder || '请输入' + field.label"
              />
            </div>
          </div>
        </div>
      </div>

      <!-- 底部按钮 -->
      <div class="modal-footer">
        <button class="btn btn-default" @click="handleClose">取消</button>
        <button class="btn btn-primary" @click="handleSave">确认修改</button>
      </div>
    </div>
  </div>
</template>

<style scoped>
/* 弹窗遮罩 */
.batch-modal-overlay {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background: rgba(0, 0, 0, 0.5);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
  animation: fadeIn 0.2s ease-out;
}

/* 弹窗容器 */
.batch-modal-container {
  background: #fff;
  border-radius: 8px;
  box-shadow: 0 10px 40px rgba(0, 0, 0, 0.2);
  width: 720px;
  max-width: 90vw;
  max-height: 85vh;
  display: flex;
  flex-direction: column;
  animation: slideUp 0.3s ease-out;
}

/* 弹窗头部 */
.modal-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16px 20px;
  border-bottom: 1px solid #e4e7ed;
  flex-shrink: 0;
}

.header-left {
  display: flex;
  align-items: center;
  gap: 12px;
}

.modal-title {
  font-size: 16px;
  font-weight: 600;
  color: #303133;
}

.selected-count {
  padding: 2px 8px;
  background: #ecf5ff;
  color: #409eff;
  border-radius: 3px;
  font-size: 12px;
  font-weight: 500;
}

.close-btn {
  width: 28px;
  height: 28px;
  border: none;
  background: #f5f7fa;
  border-radius: 4px;
  font-size: 20px;
  color: #909399;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: all 0.2s;
}

.close-btn:hover {
  background: #e4e7ed;
  color: #606266;
}

/* 弹窗内容 */
.modal-body {
  flex: 1;
  overflow-y: auto;
  padding: 0;
}

.batch-edit-content {
  padding: 20px;
}

.hint-row {
  padding: 12px 16px;
  background: #f0f9ff;
  border-radius: 4px;
  border-left: 3px solid #409eff;
  margin-bottom: 20px;
}

.hint {
  font-size: 13px;
  color: #409eff;
  line-height: 1.5;
}

/* 字段网格 - 每行3个 */
.fields-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 16px 24px;
}

.field-item {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.field-checkbox {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  font-weight: 600;
  color: #303133;
  cursor: pointer;
  user-select: none;
}

.field-checkbox input[type="checkbox"] {
  width: 16px;
  height: 16px;
  cursor: pointer;
  accent-color: #409eff;
}

.field-input {
  width: 100%;
  height: 36px;
  padding: 0 12px;
  border: 1px solid #dcdfe6;
  border-radius: 4px;
  font-size: 13px;
  color: #606266;
  font-weight: 400;
  transition: all 0.2s;
  box-sizing: border-box;
  outline: none;
  background: #fff;
}

.field-input:disabled {
  background: #f5f7fa;
  cursor: not-allowed;
  color: #c0c4cc;
}

.field-input:focus:not(:disabled) {
  border-color: #409eff;
  box-shadow: 0 0 0 2px rgba(64, 158, 255, 0.1);
}

/* 弹窗底部 */
.modal-footer {
  padding: 16px 20px;
  border-top: 1px solid #e4e7ed;
  display: flex;
  justify-content: flex-end;
  gap: 12px;
  flex-shrink: 0;
}

.btn {
  padding: 8px 20px;
  height: 36px;
  border-radius: 4px;
  font-size: 14px;
  cursor: pointer;
  transition: all 0.2s;
  border: 1px solid #dcdfe6;
  background: #fff;
  color: #606266;
}

.btn:hover {
  border-color: #c0c4cc;
  color: #409eff;
}

.btn-primary {
  background: #409eff;
  border-color: #409eff;
  color: #fff;
}

.btn-primary:hover {
  background: #66b1ff;
  border-color: #66b1ff;
  color: #fff;
}

/* 滚动条样式 */
.modal-body::-webkit-scrollbar {
  width: 6px;
}

.modal-body::-webkit-scrollbar-track {
  background: #f1f1f1;
  border-radius: 3px;
}

.modal-body::-webkit-scrollbar-thumb {
  background: #c1c1c1;
  border-radius: 3px;
}

.modal-body::-webkit-scrollbar-thumb:hover {
  background: #a8a8a8;
}

/* 动画 */
@keyframes fadeIn {
  from {
    opacity: 0;
  }
  to {
    opacity: 1;
  }
}

@keyframes slideUp {
  from {
    opacity: 0;
    transform: translateY(20px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}
</style>
