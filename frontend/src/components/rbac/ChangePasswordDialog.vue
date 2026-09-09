<script setup>
/**
 * 修改密码弹窗（PRD-28 §8.4 / §10.5）。
 * forced=true 时为首登/过期强制改密：不显示关闭按钮，取消按钮置灰，不能跳过。
 * 强度规则与后端一致：8~50 位，大写/小写/数字/特殊四类至少 3 类。
 */
import { ref, watch } from 'vue'
import { profileApi } from '../../api/rbac.js'

const props = defineProps({
  visible: Boolean,
  forced: { type: Boolean, default: false },
})
const emit = defineEmits(['close', 'saved'])

const form = ref({ oldPassword: '', newPassword: '', confirm: '' })
const saving = ref(false)
const error = ref('')

watch(() => props.visible, (v) => {
  if (v) {
    form.value = { oldPassword: '', newPassword: '', confirm: '' }
    error.value = ''
  }
})

/** 前端预检，与后端 PasswordService 文案保持一致（最终以后端为准） */
function checkStrength(pwd) {
  if (!pwd || pwd.length < 8 || pwd.length > 50) return '密码长度需为 8~50 位'
  let kinds = 0
  if (/[a-z]/.test(pwd)) kinds++
  if (/[A-Z]/.test(pwd)) kinds++
  if (/[0-9]/.test(pwd)) kinds++
  if (/[^A-Za-z0-9]/.test(pwd)) kinds++
  if (kinds < 3) return '密码需包含大写字母、小写字母、数字、特殊字符中的至少 3 种'
  return ''
}

async function submit() {
  error.value = ''
  const f = form.value
  if (!f.oldPassword || !f.newPassword) { error.value = '请填写原密码与新密码'; return }
  const weak = checkStrength(f.newPassword)
  if (weak) { error.value = weak; return }
  if (f.newPassword !== f.confirm) { error.value = '两次输入的新密码不一致'; return }
  if (f.newPassword === f.oldPassword) { error.value = '新密码不能与原密码相同'; return }
  saving.value = true
  try {
    await profileApi.changePassword(f.oldPassword, f.newPassword)
    emit('saved')
  } catch (e) {
    error.value = e.message || '修改失败'
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <div v-if="visible" class="mask">
    <div class="dialog pwd-dialog">
      <div class="dialog-head">
        <b>{{ forced ? '首次登录/密码已过期，请修改密码' : '修改密码' }}</b>
        <button v-if="!forced" class="link-btn" @click="emit('close')">×</button>
      </div>
      <div class="dialog-body">
        <div v-if="forced" class="forced-tip">
          为保证账号安全，首次登录或管理员重置密码后必须修改密码，修改成功后才能使用系统。
        </div>
        <div class="field">
          <label>原密码 <span class="req">*</span></label>
          <input v-model="form.oldPassword" type="password" autocomplete="current-password" placeholder="请输入原密码" />
        </div>
        <div class="field">
          <label>新密码 <span class="req">*</span></label>
          <input v-model="form.newPassword" type="password" autocomplete="new-password" placeholder="8~50 位" />
        </div>
        <div class="field">
          <label>确认新密码 <span class="req">*</span></label>
          <input
            v-model="form.confirm" type="password" autocomplete="new-password"
            placeholder="再次输入新密码" @keydown.enter="submit"
          />
        </div>
        <div class="rule-hint">
          密码长度 8~50 位，须包含大写字母、小写字母、数字、特殊字符中的至少 3 种；
          不可与最近 3 次使用过的密码相同。
        </div>
        <div v-if="error" class="error-text">{{ error }}</div>
      </div>
      <div class="dialog-foot">
        <button v-if="!forced" class="btn" @click="emit('close')">取消</button>
        <button class="btn primary" :disabled="saving" @click="submit">
          {{ saving ? '提交中…' : '确认修改' }}
        </button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.pwd-dialog { width: 460px; }
.forced-tip {
  background: #fff7e6; border: 1px solid #ffd591; color: #ad4e00;
  border-radius: 8px; padding: 8px 10px; font-size: 12px;
}
.rule-hint { color: #94a3b8; font-size: 12px; line-height: 1.6; }
.error-text { color: #d93025; font-size: 12px; }
.mask {
  position: fixed; inset: 0; background: rgba(15, 35, 60, 0.32);
  display: grid; place-items: center; z-index: 260;
}
.dialog {
  width: 480px; background: #fff; border-radius: 8px;
  overflow: hidden; box-shadow: 0 20px 60px rgba(0, 0, 0, 0.2);
}
.dialog-head {
  padding: 10px 16px; border-bottom: 1px solid var(--line);
  display: flex; justify-content: space-between; align-items: center;
}
.dialog-body { padding: 16px; display: flex; flex-direction: column; gap: 12px; }
.field { display: grid; grid-template-columns: 92px 1fr; gap: 4px 12px; align-items: center; }
.field label { font-size: 12px; color: #606266; text-align: right; }
.field input {
  padding: 6px 10px; border: 1px solid var(--line);
  border-radius: 6px; font-size: 13px; outline: none;
}
.req { color: #f56c6c; }
.dialog-foot {
  padding: 10px 16px; border-top: 1px solid var(--line);
  display: flex; justify-content: flex-end; gap: 8px;
}
.dialog-foot .btn { height: 30px; padding: 0 16px; }
.link-btn { border: 0; background: transparent; color: var(--primary); cursor: pointer; font-size: 16px; }
</style>
