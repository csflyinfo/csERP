import api from './index.js'

export const categoryApi = {
  page: (params) => api.post('/base/category/page', params),
  create: (data) => api.post('/base/category/create', data),
  update: (data) => api.post('/base/category/update', data),
}

export const unitApi = {
  page: (params) => api.post('/base/unit/page', params),
  create: (data) => api.post('/base/unit/create', data),
}

export const brandApi = {
  page: (params) => api.post('/base/brand/page', params),
  create: (data) => api.post('/base/brand/create', data),
}

export const warehouseApi = {
  page: (params) => api.post('/base/warehouse/page', params),
  create: (data) => api.post('/base/warehouse/create', data),
}

export const goodsApi = {
  page: (params) => api.post('/base/goods/page', params),
  create: (data) => api.post('/base/goods/create', data),
  update: (data) => api.post('/base/goods/update', data),
}

export const customerApi = {
  page: (params) => api.post('/base/customer/page', params),
  create: (data) => api.post('/base/customer/create', data),
  update: (data) => api.post('/base/customer/update', data),
}

export const supplierApi = {
  page: (params) => api.post('/base/supplier/page', params),
  create: (data) => api.post('/base/supplier/create', data),
  update: (data) => api.post('/base/supplier/update', data),
}
