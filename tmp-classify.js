const fs = require('fs')
const path = 'e:/work/erp-wms-tms/backend/src/main/resources/db/migration/V1__schema.sql'
const text = fs.readFileSync(path, 'utf8')

// 抽取每个 CREATE TABLE 块（不能贪婪匹配 —— 直到匹配的 `);`）
const tables = {}
const reCreate = /CREATE TABLE (\w+)\s*\(([\s\S]*?)\);/g
let m
while ((m = reCreate.exec(text)) !== null) {
  tables[m[1]] = m[2]
}

// 每个 ALTER，看列是否已存在
const reAlter = /ALTER TABLE (\w+) ADD COLUMN (\w+)/g
const duplicate = [], missing = []
while ((m = reAlter.exec(text)) !== null) {
  const [_, table, col] = m
  const body = tables[table] || ''
  const cols = new Set()
  for (let line of body.split('\n')) {
    line = line.trim().replace(/,$/, '').trim()
    if (!line || line.startsWith('--')) continue
    const first = line.split(/\s+/)[0].replace(/[`"]/g, '').toLowerCase()
    if (!['primary','unique','key','index','constraint'].includes(first)) {
      cols.add(first)
    }
  }
  if (cols.has(col.toLowerCase())) duplicate.push([table, col])
  else missing.push([table, col])
}
console.log(`duplicate (already in CREATE): ${duplicate.length}`)
duplicate.slice(0, 15).forEach(([t,c]) => console.log(`  ${t}.${c}`))
console.log(`missing (need to add): ${missing.length}`)
missing.slice(0, 30).forEach(([t,c]) => console.log(`  ${t}.${c}`))
