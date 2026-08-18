const fs = require('fs')
const path = 'e:/work/erp-wms-tms/backend/src/main/resources/db/migration/V1__schema.sql'
let text = fs.readFileSync(path, 'utf8')

const reCreate = /CREATE TABLE (\w+)\s*\(([\s\S]*?)\);/g
const tables = {}
let m
while ((m = reCreate.exec(text)) !== null) {
  const body = m[2]
  const cols = new Set()
  for (let line of body.split('\n')) {
    line = line.trim().replace(/,$/, '').trim()
    if (!line || line.startsWith('--')) continue
    const first = line.split(/\s+/)[0].replace(/[`"]/g, '').toLowerCase()
    if (!['primary','unique','key','index','constraint'].includes(first)) {
      cols.add(first)
    }
  }
  tables[m[1]] = cols
}

// 逐行处理，删掉重复 ALTER 及其上方的独立注释行
const lines = text.split(/\r?\n/)
const out = []
const rmALTER = /^ALTER TABLE (\w+) ADD COLUMN (\w+)\s+/
let removed = 0
for (let i = 0; i < lines.length; i++) {
  const line = lines[i]
  const m2 = line.match(rmALTER)
  if (m2) {
    const cols = tables[m2[1]]
    if (cols && cols.has(m2[2].toLowerCase())) {
      // remove this line; if previous line in out is a single-line comment starting with `-- xxx (无 IF NOT EXISTS)` we leave it
      removed++
      continue
    }
  }
  out.push(line)
}
fs.writeFileSync(path, out.join('\n'), 'utf8')
console.log('removed duplicate ALTERs:', removed)
