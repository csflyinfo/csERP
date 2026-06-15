const BASE = process.env.API_BASE || 'http://localhost:8080/api'

async function post(path, body) {
  const res = await fetch(`${BASE}${path}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: 'Bearer demo-token' },
    body: JSON.stringify(body),
  })
  const json = await res.json()
  if (json.code !== '0') throw new Error(`${path} failed: ${json.message}`)
  return json.data
}

function assert(condition, message) {
  if (!condition) throw new Error(message)
}

async function main() {
  const created = await post('/report/export', {
    moduleCode: 'salesReport',
    reportName: '销售报表',
    filters: { keyword: '华联超市', status: '已审核' },
  })
  assert(created.taskNo && created.taskNo.startsWith('EXP'), 'export should return task no')
  assert(created.status === 'FINISHED', 'export task should finish in demo mode')
  assert(String(created.fileName).includes(created.taskNo), 'export should return file name')

  const center = await post('/system/export-center/page', { pageNo: 1, pageSize: 20, filters: { keyword: created.taskNo } })
  assert(center.total >= 1, 'export center should contain created task')
  assert(center.records.some(record => record.code === created.taskNo), 'created export task should be queryable')

  const download = await post('/system/export-center/download', { taskNo: created.taskNo })
  assert(download.downloadUrl && download.downloadUrl.includes(created.taskNo), 'download should return task download url')
  assert(download.fileName === created.fileName, 'download should return created file name')

  console.log('V1 export center test passed')
}

main().catch(error => {
  console.error(error)
  process.exit(1)
})
