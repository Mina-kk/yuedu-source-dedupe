package com.mina.yuedu.network;
public final class YckCollectorScript {
 private YckCollectorScript(){}
 public static String source(){return "(function(){"
 +"var mark='data-yuedu-dedupe-button';"
 +"function text(e){return (e.innerText||e.textContent||'').replace(/\\s+/g,' ').trim();}"
 +"function cardFor(label){var n=label;for(var i=0;n&&i<7;i++,n=n.parentElement){if(n.querySelector&&n.querySelector('input'))return n;}return label.parentElement;}"
 +"function inputFor(card){var all=card.querySelectorAll('input');for(var i=0;i<all.length;i++){var x=all[i],v=(x.value||'')+' '+(x.placeholder||'');if(/https?:|json|书源/i.test(v)||x.id==='jsoninput')return x;}return all[0]||null;}"
 +"function flash(b,msg){var old=b.textContent;b.textContent=msg;b.disabled=true;setTimeout(function(){b.textContent=old;b.disabled=false;},1500);}"
 +"function scan(){var nodes=document.querySelectorAll('div,span,p,h1,h2,h3,h4,h5,strong,label'),seen=[];"
 +"for(var i=0;i<nodes.length;i++){if(text(nodes[i])!=='网络导入')continue;var card=cardFor(nodes[i]);if(!card||seen.indexOf(card)>=0||card.querySelector('['+mark+']'))continue;seen.push(card);var input=inputFor(card);if(!input)continue;var b=document.createElement('button');b.type='button';b.setAttribute(mark,'1');b.textContent='添加到去重工具';b.style.cssText='display:block;width:100%;margin-top:10px;padding:12px;border:0;border-radius:4px;background:#007AFF;color:#fff;font-size:16px;line-height:1.2;';b.onclick=(function(input,button){return function(){var u=(input.value||'').trim();if(!/^https?:\\/\\//i.test(u)||!/(?:\\.json|\\/d\\/[^\\/?#]+)(?:[?#]|$)/i.test(u)){flash(button,'未找到有效书源地址');return;}if(!window.YckDedupe||!window.YckDedupe.addToDedupe){flash(button,'App 连接不可用');return;}var r=window.YckDedupe.addToDedupe(u);flash(button,r==='added'?'已添加 ✓':r==='duplicate'?'已在列表中':'未找到有效书源地址');};})(input,b);var anchors=card.querySelectorAll('button,a,[role=button]'),a=null;for(var j=0;j<anchors.length;j++){if(/3\\.[0-9]|一键导入/.test(text(anchors[j]))){a=anchors[j];break;}}if(a&&a.parentNode)a.parentNode.insertBefore(b,a.nextSibling);else card.appendChild(b);}}"
 +"scan();if(!window.__yckDedupeObserver){var debounceTimer=0;window.__yckDedupeObserver=new MutationObserver(function(){clearTimeout(debounceTimer);debounceTimer=setTimeout(scan,200);});window.__yckDedupeObserver.observe(document.documentElement||document.body,{childList:true,subtree:true});}})();";}
}
