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

const app = createApp(App)
app.use(createPinia())
app.use(router)
app.mount('#app')
