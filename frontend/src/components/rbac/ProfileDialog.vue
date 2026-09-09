<script setup>
/**
 * 个人中心（PRD-28 §10.5）：查看本人资料、绑定仓库，可发起修改密码。
 */
import { ref, watch } from 'vue'
import { profileApi } from '../../api/rbac.js'
import ChangePasswordDialog from './ChangePasswordDialog.vue'

const props = defineProps({ visible: Boolean })
const emit = defineEmits(['close', 'password-changed'])

const profile = ref(null)
const loading = ref(false)
const pwdVisible = ref(false)

watch(() => props.visible, async (v) => {
  if (!v) return
  loading.value = true
  try {
    profile.value = await profileApi.get()
  } catch (e) {
    alert('资料加载失败：' + (e.message || e))
  } finally {
    loading.value = false
  }
})

function fmt(t) {
  if (!t) return '—'
  return String(t).replace('T', ' ').slice(0, 19)
}
</script>

<template>
  <div v-if="visible" class="mask" @click.self="emit('close')">
    <div class="dialog profile-dialog">
      <div class="dialog-head">
        <b>个人中心</b>
        <button class="link-btn" @click="emit('close')">×</button>
      </div>
      <div class="dialog-body">
        <div v-if="loading" class="muted">加载中…</div>
        <template v-else-if="profile">
          <div class="avatar-line">
            <div class="avatar">{{ profile.displayName?.slice(0, 1) || '?' }}</div>
            <div>
              <div class="name">{{ profile.displayName }}</div>
              <div class="muted">@{{ profile.username }}</div>
            </div>
          </div>
          <div class="grid">
            <div class="cell"><label>账号</label><span>{{ profile.username }}</span></div>
            <div class="cell"><label>姓名</label><span>{{ profile.displayName }}</span></div>
            <div class="cell"><label>手机</label><span>{{ profile.mobile || '—' }}</span></div>
            <div class="cell"><label>邮箱</label><span>{{ profile.email || '—' }}</span></div>
            <div class="cell">
              <label>角色</label>
              <span>{{ profile.roles?.map(r => r.roleName).join('、') || '—' }}</span>
            </div>
            <div class="cell">
              <label>绑定仓库</label>
              <span>{{ profile.warehouses?.map(w => w.warehouseName).join('、') || '—' }}</span>
            </div>
            <div class="cell"><label>最近登录</label><span>{{ fmt(profile.lastLoginTime) }}</span></div>
            <div class="cell"><label>最近登录IP</label><span>{{ profile.lastLoginIp || '—' }}</span></div>
            <div class="cell"><label>上次改密</label><span>{{ fmt(profile.pwdUpdateTime) }}</span></div>
          </div>
        </template>
      </div>
      <div class="dialog-foot">
        <button class="btn" @click="emit('close')">关闭</button>
        <button class="btn primary" @click="pwdVisible = true">修改密码</button>
      </div>
    </div>

    <ChangePasswordDialog
      :visible="pwdVisible"
      @close="pwdVisible = false"
      @saved="pwdVisible = false; emit('password-changed')"
    />
  </div>
</template>

<style scoped>
.profile-dialog { width: 560px; }
.muted { color: #94a3b8; font-size: 12px; }
.avatar-line { display: flex; align-items: center; gap: 12px; margin-bottom: 8px; }
.avatar {
  width: 44px; height: 44px; border-radius: 50%; background: var(--primary);
  color: #fff; display: grid; place-items: center; font-size: 20px; font-weight: 700;
}
.name { font-size: 16px; font-weight: 700; color: #12385f; }
.grid { display: grid; grid-template-columns: 1fr 1fr; gap: 10px 20px; margin-top: 10px; }
.cell { display: grid; grid-template-columns: 76px 1fr; font-size: 13px; align-items: baseline; }
.cell label { color: #94a3b8; font-size: 12px; }
.mask {
  position: fixed; inset: 0; background: rgba(15, 35, 60, 0.28);
  display: grid; place-items: center; z-index: 240;
}
.dialog {
  width: 480px; background: #fff; border-radius: 8px;
  overflow: hidden; box-shadow: 0 20px 60px rgba(0, 0, 0, 0.2);
}
.dialog-head {
  padding: 10px 16px; border-bottom: 1px solid var(--line);
  display: flex; justify-content: space-between; align-items: center;
}
.dialog-body { padding: 16px; }
.dialog-foot {
  padding: 10px 16px; border-top: 1px solid var(--line);
  display: flex; justify-content: flex-end; gap: 8px;
}
.dialog-foot .btn { height: 30px; padding: 0 16px; }
.link-btn { border: 0; background: transparent; color: var(--primary); cursor: pointer; font-size: 16px; }
</style>
