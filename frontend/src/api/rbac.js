/**
 * PRD-28 RBAC 接口封装（卡片5）。
 * 用户管理 / 角色四分区授权 / 模块菜单管理 / 个人中心。
 */
import { get, post, put } from './client.js'

// ---------------- 用户管理 ----------------

export const userApi = {
  page: (body) => post('/system/rbac/user/page', body),
  detail: (userId) => get(`/system/rbac/user/${userId}`),
  create: (body) => post('/system/rbac/user/create', body),
  update: (body) => post('/system/rbac/user/update', body),
  resetPassword: (body) => post('/system/rbac/user/reset-password', body),
  disable: (userId) => post('/system/rbac/user/disable', { userId }),
  enable: (userId) => post('/system/rbac/user/enable', { userId }),
  unlock: (userId) => post('/system/rbac/user/unlock', { userId }),
  options: () => get('/system/rbac/user/options'),
  employees: (keyword, excludeUserId) =>
    get(`/system/rbac/user/employees?keyword=${encodeURIComponent(keyword || '')}&excludeUserId=${encodeURIComponent(excludeUserId || '')}`),
  scopeValues: (scopeType, keyword) =>
    get(`/system/rbac/user/scope-values?scopeType=${encodeURIComponent(scopeType)}&keyword=${encodeURIComponent(keyword || '')}`),
}

// ---------------- 角色管理 ----------------

export const roleApi = {
  page: (body) => post('/system/rbac/role/page', body),
  detail: (roleId) => get(`/system/rbac/role/${roleId}`),
  create: (body) => post('/system/rbac/role/create', body),
  update: (body) => post('/system/rbac/role/update', body),
  delete: (roleId) => post('/system/rbac/role/delete', { roleId }),
  copy: (body) => post('/system/rbac/role/copy', body),
  grants: (body) => post('/system/rbac/role/grants', body),
}

// ---------------- 权限元数据（角色页 / 菜单管理页） ----------------

export const permApi = {
  /** 角色授权树（排除 admin_only / STOPPED，已剪空目录） */
  grantTree: (appType = 'ERP') => get(`/system/menu/grant-tree?appType=${appType}`),
  /** 菜单管理全量树（含 STOPPED 与自定义标志） */
  menuTree: (appType = 'ERP') => get(`/system/menu/tree?appType=${appType}`),
  funcsByMenu: (menuId) => get(`/system/func/list?menuId=${encodeURIComponent(menuId)}`),
  fields: () => get('/system/field/list'),
}

// ---------------- 模块菜单管理写接口（硬校验超管） ----------------

export const menuManageApi = {
  rename: (menuId, name) => put(`/system/menu-manage/${menuId}/name`, { name }),
  move: (menuId, parentId, confirm = false) => put(`/system/menu-manage/${menuId}/parent`, { parentId, confirm }),
  sort: (items) => put('/system/menu-manage/sort', items),
  resetOne: (menuId) => post(`/system/menu-manage/${menuId}/reset`, {}),
  resetAll: () => post('/system/menu-manage/reset-all?confirm=true', {}),
  /** 新建自定义目录（一级/二级） */
  createDir: (appType, name, parentId = null) =>
    post('/system/menu-manage/dir', { appType, name, parentId }),
  /** 设置是否启用（停用级联子树，启用仅自身） */
  setEnabled: (menuId, enabled) => put(`/system/menu-manage/${menuId}/enabled`, { enabled }),
  /** 删除空的自定义目录 */
  deleteDir: (menuId) => post(`/system/menu-manage/${menuId}/delete`, {}),
}

// ---------------- 个人中心 ----------------

export const profileApi = {
  get: () => get('/auth/profile'),
  changePassword: (oldPassword, newPassword) =>
    post('/auth/change-password', { oldPassword, newPassword }),
}
