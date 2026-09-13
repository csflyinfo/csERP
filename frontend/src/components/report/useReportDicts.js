/**
 * 报表筛选字典：仓库 / 品牌 / 商品分类（树） / 采购员。
 * 全部按名称下拉（与后端各报表 filters 取值一致）；分类树支持多选，
 * 勾选上级自动带入全部下级名称。接口 403/异常时静默为空，不阻塞报表打开。
 */
import { ref } from 'vue'
import { post } from '../../api/client.js'
import { loadWarehouses } from '../../api/report-center.js'

export function useReportDicts() {
  const warehouses = ref([])
  const brands = ref([])
  const buyers = ref([])
  /** 扁平分类 [{code,name,parentCode}] */
  const categoryFlat = ref([])
  /** 树形分类 [{code,name,children:[]}] */
  const categoryTree = ref([])
  let loaded = null

  function buildTree(flat) {
    const map = new Map()
    flat.forEach(c => map.set(c.code, { code: c.code, name: c.name, children: [] }))
    const roots = []
    flat.forEach(c => {
      const node = map.get(c.code)
      const parent = c.parentCode && map.get(c.parentCode)
      if (parent) parent.children.push(node)
      else roots.push(node)
    })
    return roots
  }

  async function load() {
    if (loaded) return loaded
    loaded = (async () => {
      const results = await Promise.allSettled([
        loadWarehouses(),
        post('/base/brand/page', { pageNo: 1, pageSize: 1000, filters: {} }),
        post('/base/employee/buyers', {}),
        post('/base/category/page', { pageNo: 1, pageSize: 1000, filters: {} }),
      ])
      warehouses.value = results[0].status === 'fulfilled' ? (results[0].value || []) : []
      const brandRows = results[1].status === 'fulfilled' ? (results[1].value?.records || []) : []
      brands.value = brandRows.map(b => b.brandName).filter(Boolean)
      const buyerRows = results[2].status === 'fulfilled' ? (results[2].value || []) : []
      buyers.value = buyerRows.map(b => b.employeeName || b.name).filter(Boolean)
      const catRows = results[3].status === 'fulfilled' ? (results[3].value?.records || []) : []
      categoryFlat.value = catRows
        .filter(c => c.categoryCode && c.categoryName && c.status !== 'DISABLED')
        .map(c => ({ code: c.categoryCode, name: c.categoryName, parentCode: c.parentCode || '' }))
      categoryTree.value = buildTree(categoryFlat.value)
    })()
    return loaded
  }

  return { warehouses, brands, buyers, categoryFlat, categoryTree, load }
}

/** 单例缓存：所有报表页共享同一份字典，避免每页重复请求。 */
let singleton = null
export function sharedReportDicts() {
  if (!singleton) singleton = useReportDicts()
  return singleton
}
