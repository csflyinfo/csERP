/* WMS V1.5 共享交互：toast / modal / drawer / wizard / tabs / table-select / 假数据动作 */
(function(){
  // ---------- Toast ----------
  function toast(msg, type){
    var t = document.getElementById('__toast');
    if(!t){ t=document.createElement('div'); t.id='__toast';
      t.style.cssText='position:fixed;left:50%;top:24px;transform:translateX(-50%);z-index:9999;padding:11px 20px;border-radius:8px;font-size:14px;color:#fff;background:#1677ff;box-shadow:0 8px 28px rgba(0,0,0,.2);display:none;max-width:80vw;text-align:center;';
      document.body.appendChild(t);
    }
    var bg={ok:'#15803d',bad:'#b91c1c',warn:'#c2410c',info:'#1677ff'};
    t.style.background=bg[type]||bg.info; t.textContent=msg; t.style.display='block';
    clearTimeout(t._h); t._h=setTimeout(function(){t.style.display='none'},2400);
  }
  window.toast=toast;

  // ---------- Modal ----------
  // openModal({title, body(HTML or node), size, okText, onOk, footer, noFooter})
  function openModal(opt){
    var mask=document.getElementById('__modal');
    if(!mask){mask=document.createElement('div');mask.id='__modal';mask.className='mask';document.body.appendChild(mask);}
    var size=opt.size==='lg'?'lg':opt.size==='sm'?'sm':'';
    var footer='';
    if(!opt.noFooter){
      footer='<div class="modal-f">'+(opt.cancelText!==null?'<button class="btn" data-act="cancel">'+(opt.cancelText||'取消')+'</button>':'')+
        '<button class="btn primary" data-act="ok">'+(opt.okText||'确定')+'</button></div>';
    }
    mask.innerHTML='<div class="modal '+size+'"><div class="modal-h"><h3></h3><span class="x" data-act="close">×</span></div><div class="modal-b"></div>'+footer+'</div>';
    mask.querySelector('h3').textContent=opt.title||'';
    var body=mask.querySelector('.modal-b');
    if(typeof opt.body==='string') body.innerHTML=opt.body; else if(opt.body) body.appendChild(opt.body);
    function close(){mask.classList.remove('show');mask.innerHTML='';}
    mask.onclick=function(e){
      var act=e.target.getAttribute&&e.target.getAttribute('data-act');
      if(e.target===mask||act==='close'||act==='cancel'){close();}
      if(act==='ok'){ var r = opt.onOk?opt.onOk(mask):undefined; if(r!==false) close(); }
    };
    requestAnimationFrame(function(){mask.classList.add('show');});
    mask._close=close;
    return {close:close,el:mask};
  }
  window.openModal=openModal;
  window.closeModal=function(){var m=document.getElementById('__modal');if(m)m.classList.remove('show');};

  // ---------- Drawer (right) ----------
  function openDrawer(opt){
    var dm=document.getElementById('__dmask'),dw=document.getElementById('__drawer');
    if(!dm){dm=document.createElement('div');dm.id='__dmask';dm.className='drawer-mask';document.body.appendChild(dm);
      dw=document.createElement('div');dw.id='__drawer';dw.className='drawer';document.body.appendChild(dw);
      dm.onclick=function(){closeDrawer();};
    }
    dw.innerHTML='<div class="drawer-h"><h3></h3><span class="x" style="cursor:pointer;color:#98a2b3;font-size:20px" data-act="close">×</span></div><div class="drawer-b"></div>'+(opt.footer?'<div class="drawer-f"></div>':'');
    dw.querySelector('h3').textContent=opt.title||'';
    var b=dw.querySelector('.drawer-b');
    if(typeof opt.body==='string')b.innerHTML=opt.body;else if(opt.body)b.appendChild(opt.body);
    if(opt.footer){var f=dw.querySelector('.drawer-f');if(typeof opt.footer==='string')f.innerHTML=opt.footer;}
    dw.onclick=function(e){if(e.target.getAttribute&&e.target.getAttribute('data-act')==='close')closeDrawer();};
    if(opt.onReady)opt.onReady(dw);
    requestAnimationFrame(function(){dm.classList.add('show');dw.classList.add('show');});
    return {el:dw,close:closeDrawer};
  }
  window.openDrawer=openDrawer;
  function closeDrawer(){var dm=document.getElementById('__dmask'),dw=document.getElementById('__drawer');if(dm)dm.classList.remove('show');if(dw)dw.classList.remove('show');}
  window.closeDrawer=closeDrawer;

  // ---------- Tabs (data-tabs group) ----------
  function bindTabs(root){
    (root||document).querySelectorAll('[data-tabs]').forEach(function(group){
      var tabs=group.querySelectorAll('.tab');
      tabs.forEach(function(tab,i){
        tab.onclick=function(){
          tabs.forEach(function(t){t.classList.remove('on')});
          tab.classList.add('on');
          var panes=group.querySelectorAll('.tabpane');
          panes.forEach(function(p,j){p.classList.toggle('on',j===i);});
        };
      });
    });
  }
  window.bindTabs=bindTabs;

  // ---------- Wizard (data-wizard) ----------
  function bindWizard(root){
    (root||document).querySelectorAll('[data-wizard]').forEach(function(wz){
      var steps=wz.querySelectorAll('.wiz-steps .s');
      var panes=wz.querySelectorAll('.wiz-pane');
      var idx=0;
      function show(i){
        idx=Math.max(0,Math.min(panes.length-1,i));
        steps.forEach(function(s,j){s.classList.toggle('done',j<idx);s.classList.toggle('cur',j===idx);});
        panes.forEach(function(p,j){p.classList.toggle('on',j===idx);});
        var next=wz.querySelector('[data-wiz="next"]'),prev=wz.querySelector('[data-wiz="prev"]'),done=wz.querySelector('[data-wiz="done"]');
        if(prev)prev.style.visibility=idx===0?'hidden':'visible';
        if(next)next.style.display=idx===panes.length-1?'none':'';
        if(done)done.style.display=idx===panes.length-1?'':'none';
      }
      wz.querySelector('[data-wiz="next"]').onclick=function(){show(idx+1);};
      wz.querySelector('[data-wiz="prev"]').onclick=function(){show(idx-1);};
      if(wz.querySelector('[data-wiz="done"]'))wz.querySelector('[data-wiz="done"]').onclick=function(){toast('已保存','ok');if(wz._done)wz._done();};
      show(0);
      wz._show=show;
    });
  }
  window.bindWizard=bindWizard;

  // ---------- 表格行多选 ----------
  function bindTableSelect(tbl, onChange){
    if(!tbl)return;
    var head=tbl.querySelector('thead .ck'),all=tbl.querySelectorAll('tbody .ck');
    if(head)head.onchange=function(){all.forEach(function(c){c.checked=head.checked;var r=c.closest('tr');if(r)r.classList.toggle('sel',c.checked);});onChange&&onChange(selRows());};
    all.forEach(function(c){c.onchange=function(){var r=c.closest('tr');if(r)r.classList.toggle('sel',c.checked);if(head)head.checked=[].every.call(all,function(x){return x.checked;});onChange&&onChange(selRows());};});
    function selRows(){return [].map.call(all,function(c){return c.checked?c.closest('tr'):null;}).filter(Boolean);}
  }
  window.bindTableSelect=bindTableSelect;

  // ---------- 通用：点击按钮 toast ----------
  document.addEventListener('click',function(e){
    var act=e.target.getAttribute&&e.target.getAttribute('data-toast');
    if(act){var t=e.target.getAttribute('data-type')||'info';toast(act,t);}
  });

  document.addEventListener('DOMContentLoaded',function(){bindTabs();bindWizard();});
})();
