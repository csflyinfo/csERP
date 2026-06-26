import { defineStore } from 'pinia'
import { ref } from 'vue'

export const useAppStore = defineStore('app', () => {
  const menus = ref([])
  const activeTop = ref('')
  const openTabs = ref([])
  const currentModule = ref('')
  const toastText = ref('')

  function addTab(route) {
    if (!route.meta?.title) return
    const exists = openTabs.value.find(t => t.path === route.path)
    if (!exists) {
      openTabs.value.push({ path: route.path, name: route.meta.title, module: route.meta.module || '' })
    }
  }

  function removeTab(path) {
    openTabs.value = openTabs.value.filter(t => t.path !== path)
  }

  function showToast(msg) {
    toastText.value = msg
    setTimeout(() => { toastText.value = '' }, 2000)
  }

  return { menus, activeTop, openTabs, currentModule, toastText, addTab, removeTab, showToast }
})
