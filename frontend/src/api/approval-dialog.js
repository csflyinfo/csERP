/**
 * 敏感操作二次授权弹窗（PRD-28 §6.2.1，卡片6）。
 *
 * 后端在低价销售 / 超信用 / 负库存出库命中红线、且请求未带授权人凭证时，
 * 返回 code=NEED_APPROVAL；client.js 捕获后调用本函数，用原生 DOM 弹出
 * 「授权账号 + 授权密码」输入框（不引入新 UI 库），操作员当场找有权限者输密码，
 * 确认后由 client 把 approverAccount/approverPassword 合并进原请求体重放一次。
 *
 * @param {{approvalType?:string, bizNo?:string, message?:string}} payload 后端结构化数据
 * @returns {Promise<{account:string,password:string}|null>} 确认返回凭证；取消/关闭返回 null
 */

const TITLE_MAP = {
  LOW_PRICE: '低价销售授权',
  OVER_CREDIT: '超信用授权',
  NEGATIVE_STOCK: '负库存出库授权',
}

let active = null

export function promptApproval(payload = {}) {
  // 重放/并发场景下已有弹窗时不叠加
  if (active) return active
  ensureApprovalDialogStyle()

  active = new Promise((resolve) => {
    const finish = (value) => {
      document.removeEventListener('keydown', onKeydown, true)
      overlay.remove()
      active = null
      resolve(value)
    }

    const title = TITLE_MAP[payload.approvalType] || '敏感操作授权'

    const overlay = document.createElement('div')
    overlay.className = 'erp-approval-overlay'
    overlay.innerHTML = `
      <div class="erp-approval-card" role="dialog" aria-modal="true" aria-label="${title}">
        <div class="erp-approval-title">${title}</div>
        ${payload.bizNo ? `<div class="erp-approval-bizno">单据：${payload.bizNo}</div>` : ''}
        <div class="erp-approval-msg"></div>
        <label class="erp-approval-label">授权人账号
          <input class="erp-approval-input" data-role="account" type="text" autocomplete="username" placeholder="拥有该授权权限的账号" />
        </label>
        <label class="erp-approval-label">授权人密码
          <input class="erp-approval-input" data-role="password" type="password" autocomplete="current-password" placeholder="授权人登录密码" />
        </label>
        <div class="erp-approval-error" data-role="error"></div>
        <div class="erp-approval-actions">
          <button type="button" class="erp-approval-btn erp-approval-cancel">取消</button>
          <button type="button" class="erp-approval-btn erp-approval-ok">确认授权并重试</button>
        </div>
      </div>`
    // 消息以后端文本为准，textContent 防注入
    overlay.querySelector('.erp-approval-msg').textContent =
      payload.message || '该操作触发管控红线，需要更高权限人员授权。'
    document.body.appendChild(overlay)

    const accountEl = overlay.querySelector('[data-role="account"]')
    const passwordEl = overlay.querySelector('[data-role="password"]')
    const errorEl = overlay.querySelector('[data-role="error"]')
    accountEl.focus()

    const submit = () => {
      const account = accountEl.value.trim()
      const password = passwordEl.value
      if (!account || !password) {
        errorEl.textContent = '请输入授权人账号和密码'
        return
      }
      finish({ account, password })
    }
    const onKeydown = (e) => {
      if (e.key === 'Escape') {
        e.stopPropagation()
        finish(null)
      } else if (e.key === 'Enter' && overlay.contains(e.target)) {
        e.stopPropagation()
        e.preventDefault()
        submit()
      }
    }
    // 捕获阶段监听，避免被页面其它回车监听抢先
    document.addEventListener('keydown', onKeydown, true)
    overlay.querySelector('.erp-approval-ok').addEventListener('click', submit)
    overlay.querySelector('.erp-approval-cancel').addEventListener('click', () => finish(null))
    overlay.addEventListener('click', (e) => {
      if (e.target === overlay) finish(null)
    })
  })

  return active
}

/** 注入弹窗所需样式（仅一次）；随全站样式体系，不引外部依赖。 */
export function ensureApprovalDialogStyle() {
  if (document.getElementById('erp-approval-style')) return
  const style = document.createElement('style')
  style.id = 'erp-approval-style'
  style.textContent = `
.erp-approval-overlay{position:fixed;inset:0;z-index:9999;display:flex;align-items:center;justify-content:center;background:rgba(15,23,42,.45);font-family:"Noto Sans SC",Inter,system-ui,sans-serif}
.erp-approval-card{width:420px;max-width:calc(100vw - 48px);background:#fff;border-radius:10px;padding:22px 24px;box-shadow:0 12px 40px rgba(15,23,42,.24)}
.erp-approval-title{font-size:17px;font-weight:700;color:#1f2937}
.erp-approval-bizno{margin-top:6px;font-size:12px;color:#2563eb;font-weight:600}
.erp-approval-msg{margin:12px 0 16px;font-size:13px;line-height:1.6;color:#b45309;background:#fffbeb;border:1px solid #fde68a;border-radius:6px;padding:8px 10px;word-break:break-all}
.erp-approval-label{display:block;font-size:13px;color:#374151;margin-bottom:12px}
.erp-approval-input{display:block;width:100%;margin-top:5px;height:34px;padding:0 10px;border:1px solid #d1d5db;border-radius:6px;font-size:14px;box-sizing:border-box;outline:none}
.erp-approval-input:focus{border-color:#2563eb;box-shadow:0 0 0 2px rgba(37,99,235,.15)}
.erp-approval-error{min-height:16px;font-size:12px;color:#dc2626;margin-bottom:6px}
.erp-approval-actions{display:flex;justify-content:flex-end;gap:10px;margin-top:6px}
.erp-approval-btn{height:34px;padding:0 16px;border-radius:6px;font-size:13px;cursor:pointer;border:1px solid transparent}
.erp-approval-cancel{background:#fff;border-color:#d1d5db;color:#374151}
.erp-approval-ok{background:#2563eb;color:#fff}
.erp-approval-ok:hover{background:#1d4ed8}`
  document.head.appendChild(style)
}
