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
  const created = await post('/system/import-list/create', {
    moduleCode: 'goods',
    taskName: '商品导入任务',
    fileName: '商品导入模板.xlsx',
  })
  assert(created.taskNo && created.taskNo.startsWith('IMP'), 'import should return task no')
  assert(created.status === 'FINISHED', 'import task should finish in demo mode')
  assert(created.successRows === 10, 'import task should return success rows')

  const list = await post('/system/import-list/page', { pageNo: 1, pageSize: 20, filters: { keyword: created.taskNo } })
  assert(list.total >= 1, 'import list should contain created task')
  assert(list.records.some(record => record.code === created.taskNo), 'created import task should be queryable')

  const failures = await post('/system/import-list/download-failures', { taskNo: created.taskNo })
  assert(failures.downloadUrl && failures.downloadUrl.includes(created.taskNo), 'failure download should return task url')
  assert(String(failures.fileName).includes('失败原因'), 'failure download should return failure file name')

  console.log('V1 import list test passed')
}

main().catch(error => {
  console.error(error)
  process.exit(1)
})
