import { ref } from 'vue'

export function useToast() {
  const toastText = ref('')

  function toast(message) {
    toastText.value = message
    setTimeout(() => (toastText.value = ''), 1800)
  }

  return { toastText, toast }
}
