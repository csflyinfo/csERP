import axios from 'axios'

const instance = axios.create({
  baseURL: import.meta.env.VITE_API_BASE || '/api',
  timeout: 30000,
  headers: { 'Content-Type': 'application/json' }
})

instance.interceptors.request.use(config => {
  const token = localStorage.getItem('erp-token')
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

instance.interceptors.response.use(
  response => {
    const data = response.data
    if (data.code && data.code !== '0') {
      return Promise.reject(new Error(data.message || '请求失败'))
    }
    return data.data
  },
  error => {
    if (error.response?.status === 401) {
      localStorage.removeItem('erp-token')
      window.location.href = '/login'
    }
    return Promise.reject(error)
  }
)

export default instance
