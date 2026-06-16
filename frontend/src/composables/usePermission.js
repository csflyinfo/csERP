import { ref } from 'vue'
import { post } from '../api/client.js'

export function usePermission() {
  const hiddenFields = ref({})

  async function loadFieldScope(moduleCode, roleCode = 'ADMIN') {
    try {
      const result = await post('/system/field-scope', { moduleCode, roleCode })
      hiddenFields.value[moduleCode] = result?.hiddenFields || []
    } catch (error) {
      hiddenFields.value[moduleCode] = []
    }
  }

  function canViewField(moduleCode, title) {
    const fields = hiddenFields.value[moduleCode] || []
    return !fields.some(field => String(title).includes(field))
  }

  return { hiddenFields, loadFieldScope, canViewField }
}
