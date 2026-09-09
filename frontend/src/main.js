import { createApp } from 'vue'
import { createPinia } from 'pinia'
import router from './router/index.js'
import App from './App.vue'

// 全站字体：思源黑体 (中文) + Inter (英文数字) + Fira Code (等宽)
// Variable Font 一个文件覆盖 100-900，无需按权重挑选
import '@fontsource-variable/noto-sans-sc/index.css'
import '@fontsource/inter/400.css'
import '@fontsource/inter/500.css'
import '@fontsource/inter/700.css'
import '@fontsource/fira-code/400.css'
import '@fontsource/fira-code/500.css'

import './styles/app.css'
import { permissionDirective, actionPermsDirective } from './directives/permission.js'
import { usePermStore } from './stores/perm.js'

const app = createApp(App)
const pinia = createPinia()
app.use(pinia)
app.use(router)
app.directive('permission', permissionDirective)
app.directive('action-perms', actionPermsDirective)
// PRD-28 卡片8：权限判定注册为全局模板属性（脚本里优先用 usePerm()/useFieldPerm() 组合式）
const permStore = usePermStore()
app.config.globalProperties.$hasFunc = (code) => permStore.hasFunc(code)
app.config.globalProperties.$hasAnyFunc = (codes) => permStore.hasAnyFunc(codes)
app.config.globalProperties.$canViewField = (code) => permStore.canViewField(code)
app.mount('#app')
