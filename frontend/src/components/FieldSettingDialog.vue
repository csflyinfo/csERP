<script setup>
/**
 * 通用字段设置弹窗。
 *
 * 使用（配合 useColumnSettings composable）：
 *   <FieldSettingDialog
 *     v-if="dialogOpen" :title="'字段设置 —— 我的模块'"
 *     :pending-settings="pendingSettings"
 *     :dialog-column-list="dialogColumnList"
 *     @save="saveSettings" @reset="resetSettings" @close="dialogOpen = false"
 *     @move="moveInDialog"
 *   />
 */
defineProps({
  title: { type: String, default: '字段设置' },
  pendingSettings: { type: Object, required: true },
  dialogColumnList: { type: Array, required: true },
})
const emit = defineEmits(['save', 'reset', 'close', 'move'])
</script>

<template>
  <div class="fs-mask" @click.self="emit('close')">
    <div class="fs-box">
      <div class="fs-head">
        <b>{{ title }}</b>
        <button class="btn" @click="emit('close')">×</button>
      </div>
      <div class="fs-body">
        <div class="fs-tip">
          <span>· 勾选「显示」控制列可见性；勾选「固定」将列吸附至表格左侧</span>
          <span>· 修改宽度或用 ↑↓ 调整顺序，点「保存」后生效并持久化</span>
          <span>· 也可以直接在表格里拖拽表头调整顺序、拖动列右边缘调整宽度</span>
        </div>
        <table class="fs-table">
          <thead>
            <tr>
              <th style="width:60px">显示</th>
              <th style="width:60px">固定</th>
              <th>字段</th>
              <th style="width:100px">宽度</th>
              <th style="width:90px">顺序</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(col, i) in dialogColumnList" :key="col.key">
              <td class="c">
                <input type="checkbox" v-model="pendingSettings[col.key].visible" />
              </td>
              <td class="c">
                <input type="checkbox" v-model="pendingSettings[col.key].fixed" />
              </td>
              <td>{{ col.title }}</td>
              <td>
                <input type="number" :min="60" step="10" v-model.number="pendingSettings[col.key].width" style="width:80px;height:26px" />
              </td>
              <td class="c">
                <button class="link link-btn" :disabled="i === 0" @click="emit('move', col.key, -1)">↑</button>
                <button class="link link-btn" :disabled="i === dialogColumnList.length - 1" @click="emit('move', col.key, 1)">↓</button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <div class="fs-foot">
        <button class="btn" @click="emit('reset')">恢复默认</button>
        <div style="flex:1"></div>
        <button class="btn" @click="emit('close')">取消</button>
        <button class="btn primary" @click="emit('save')">保存</button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.fs-mask { position: fixed; top: 0; left: 0; right: 0; bottom: 0; background: rgba(0,0,0,0.4); z-index: 1000; display: flex; align-items: center; justify-content: center; }
.fs-box { background: #fff; border-radius: 8px; width: 720px; max-width: 92vw; max-height: 80vh; display: flex; flex-direction: column; overflow: hidden; }
.fs-head { display: flex; align-items: center; justify-content: space-between; padding: 12px 16px; border-bottom: 1px solid #e5e7eb; }
.fs-body { padding: 16px; overflow: auto; display: flex; flex-direction: column; gap: 8px; }
.fs-tip { font-size: 12px; color: #909399; display: flex; flex-direction: column; gap: 4px; padding: 8px 12px; background: #f5f7fa; border-radius: 4px; }
.fs-table { width: 100%; border-collapse: collapse; font-size: 12px; }
.fs-table th, .fs-table td { padding: 6px 8px; border-bottom: 1px solid #f0f0f0; text-align: left; }
.fs-table th { background: #fafbfc; font-weight: 600; color: #303133; }
.fs-table td.c { text-align: center; }
.fs-table td.c input[type=checkbox] { margin: 0; }
.fs-table .link-btn:disabled { color: #c0c4cc; cursor: not-allowed; }
.fs-foot { padding: 12px 16px; border-top: 1px solid #e5e7eb; display: flex; justify-content: flex-end; gap: 8px; }
</style>
