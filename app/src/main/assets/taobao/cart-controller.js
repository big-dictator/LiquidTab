(function start(options) {
'use strict';
try {
var document=globalThis.document, window=globalThis.window||globalThis;
if(!document || !window.__megability_bridge__) return;
if(window.__liquidtabCartController){window.__liquidtabCartController.configure(options);return;}
var footer=document.getElementById('cartSubmitBar'), main=document.getElementById('mainCard'), list=document.getElementById('mainCardList');
if(!footer||!main||!list||document.querySelectorAll('#cartSubmitBar').length!==1){
 if(typeof MutationObserver==='function'&&!window.__liquidtabCartPending){
  var pendingAlive=true,pendingActive=true,pendingGeneration=0,pendingQueued=false,pending=new MutationObserver(retryPending);
  function retryPending(){if(pendingQueued||!pendingAlive||!pendingActive)return;pendingQueued=true;var token=pendingGeneration;requestAnimationFrame(function(){if(token!==pendingGeneration)return;pendingQueued=false;if(!pendingAlive||!pendingActive)return;if(document.getElementById('cartSubmitBar')&&document.getElementById('mainCard')&&document.getElementById('mainCardList')&&document.querySelectorAll('#cartSubmitBar').length===1){pending.disconnect();pendingAlive=false;window.removeEventListener('__liquidtabCartAction',cancelPending);delete window.__liquidtabCartPending;start(options);}});}
  function cancelPending(e){var data=e.detail||e.data||e;if(data.kind==='__pause'){pendingActive=false;pendingGeneration++;pendingQueued=false;pending.disconnect();return;}if(data.kind==='__resume'){options.activation=Number(data.phase)||0;pendingActive=true;pendingGeneration++;pending.observe(document.body||document,{childList:true,subtree:true});retryPending();return;}if(data.kind==='__geometry'){var geometry=JSON.parse(data.phase);Object.keys(geometry).forEach(function(k){options[k]=geometry[k];});return;}if(data.kind==='__dispose'){pendingAlive=false;pendingGeneration++;pending.disconnect();window.removeEventListener('__liquidtabCartAction',cancelPending);delete window.__liquidtabCartPending;}}
  window.addEventListener('__liquidtabCartAction',cancelPending);window.__liquidtabCartPending=pending;pending.observe(document.body||document,{childList:true,subtree:true});
 }
 return;
}
var alive=true, active=true, generation=0, activation=Number(options.activation)||0, queued=false, layoutQueued=false, last='', ready=false, owned=false, ownershipStyle=null, saved=[], observer;
function set(el,key,value){var current=el.style[key];if(current===value)return;if(typeof current==='string'&&/px$/.test(current)&&/px$/.test(value)&&Math.abs(parseFloat(current)-parseFloat(value))<0.02)return;if(current!==value){if(!saved.some(function(x){return x[0]===el&&x[1]===key;}))saved.push([el,key,el.style[key]]);el.style[key]=value;}}
function holdNative(){if(!owned)return;if(!ownershipStyle){ownershipStyle=document.createElement('style');ownershipStyle.textContent='#cartSubmitBar{opacity:0!important;pointer-events:none!important;position:absolute!important}';(document.head||document.body).appendChild(ownershipStyle);}set(footer,'opacity','0');set(footer,'pointerEvents','none');}
function restoreSaved(){saved.reverse().forEach(function(x){x[0].style[x[1]]=x[2];});saved=[];}
function restore(explicit){if(owned&&!explicit){holdNative();return;}owned=false;if(ownershipStyle&&ownershipStyle.parentNode)ownershipStyle.parentNode.removeChild(ownershipStyle);ownershipStyle=null;ready=false;restoreSaved();}
function text(el){return String(el&&(el.getAttribute('aria-label')||el.textContent)||'').trim();}
function unique(nodes,test){var found=Array.prototype.filter.call(nodes,test);return found.length===1?found[0]:null;}
function controls(){var buttons=footer.querySelectorAll('[role="button"]'),checkout=unique(buttons,function(e){return /结算|checkout/i.test(text(e));}),price=unique(buttons,function(e){return /合计|总计|total/i.test(text(e));}),row=checkout&&checkout.parentNode;for(var depth=0;row&&row!==footer&&depth<6;depth++){if(Array.prototype.indexOf.call(row.querySelectorAll('[role="button"]'),price)>=0)break;row=row.parentNode;}var select=row&&row!==footer?unique(row.querySelectorAll('[aria-selected]'),function(e){var state=e.getAttribute('aria-selected');return e.getAttribute('role')==='button'&&(state==='true'||state==='false');}):null;return {checkout:checkout,price:price,selectAll:select};}
var coupon=null, topControl=null, feedback=null, discoveredChrome=false, chromeObserver=null, additions=null, watched=new WeakSet();
function semanticClass(el,name){return String(el.className||'').split(/\s+/).some(function(c){return c.split('--')[0]===name;});}
function layout(){
 if(!active||!alive)return;
 var scale=window.innerWidth/options.viewportWidth, viewport=options.viewportHeight*scale, buttonTop=options.buttonTop*scale, gap=options.gap*scale;
 set(footer,'position','absolute');
 set(main,'flexShrink','0');set(main,'overflow','visible');set(main,'height',Math.max(1,viewport-main.getBoundingClientRect().top)+'px');
 set(list,'height',Math.max(1,viewport-list.getBoundingClientRect().top)+'px');set(list,'maxHeight','none');
 if(coupon&&!coupon.parentNode)coupon=null;if(!discoveredChrome){discoveredChrome=true;var candidates=Array.prototype.filter.call(document.querySelectorAll('div'),function(e){if(!semanticClass(e,'footerPromotion')&&!semanticClass(e,'frameworkV5Container'))return false;var r=e.getBoundingClientRect();return r.width>window.innerWidth*.6&&r.height>0&&r.height<window.innerWidth*.25;});coupon=candidates.length===1?candidates[0]:null;}
 if(typeof MutationObserver==='function'&&!chromeObserver)chromeObserver=new MutationObserver(scheduleLayout);function watch(e){if(e&&chromeObserver&&!watched.has(e)){watched.add(e);chromeObserver.observe(e,{attributes:true,attributeFilter:['style']});}}var couponHeight=0;
 if(coupon){watch(coupon);couponHeight=coupon.getBoundingClientRect().height;set(coupon,'position','fixed');set(coupon,'bottom',(viewport-buttonTop+gap)+'px');set(coupon,'zIndex','100');}
 topControl=document.getElementById('back2Top');
 if(!feedback||!feedback.parentNode){var matches=Array.prototype.filter.call(main.querySelectorAll('img'),function(e){return semanticClass(e,'surveyIcon');});feedback=matches.length===1?matches[0]:null;}
 var lift=viewport-buttonTop+gap+couponHeight+gap;
 
  var feedbackClass=feedback?String(feedback.className||'').split(/\s+/).filter(function(c){return c.split('--')[0]==='surveyIcon'&&/^[A-Za-z_][A-Za-z0-9_-]*$/.test(c);})[0]:null;
 var feedbackSelector=feedbackClass?'#mainCard .'+feedbackClass:'#mainCard img[class*="surveyIcon--"]';
 var feedbackBottom=lift+(topControl&&topControl.getBoundingClientRect().height>0?topControl.getBoundingClientRect().height:window.innerWidth*.10667)+gap;
 // Native scroll handlers rewrite inline transforms and mount these controls lazily.
 // Page-scoped important rules apply in the native style pass, including first mount.
 // This avoids a JS style-write/layout loop for every scroll tick.
 if(ownershipStyle){var rules='#cartSubmitBar{opacity:0!important;pointer-events:none!important;position:absolute!important}'+
 '#mainCard #back2Top{position:fixed!important;bottom:'+lift+'px!important;transform:none!important;z-index:101!important}'+
 feedbackSelector+'{position:fixed!important;bottom:'+feedbackBottom+'px!important;transform:none!important;z-index:101!important}';
 if(ownershipStyle.textContent!==rules)ownershipStyle.textContent=rules;}
}
function send(params,callback){params.__liquidtabCartEpoch=activation;window.__megability_bridge__.asyncCall('Cart','callNative',{instanceID:options.id,method:'saveInstanceState',params:params},callback||function(){});}
function refreshDom(){var nextFooter=document.getElementById('cartSubmitBar'),nextMain=document.getElementById('mainCard'),nextList=document.getElementById('mainCardList');if(!nextFooter||!nextMain||!nextList||document.querySelectorAll('#cartSubmitBar').length!==1)return false;if(footer===nextFooter&&main===nextMain&&list===nextList)return true;disconnect();restoreSaved();footer=nextFooter;main=nextMain;list=nextList;coupon=null;feedback=null;discoveredChrome=false;observationRoot=commonRoot();observe();holdNative();scheduleLayout();return true;}
function publish(force){if(!alive||!active)return;try{var c=refreshDom()?controls():{},valid=!!(c.checkout&&c.price&&c.selectAll),label=text(c.price),match=label.match(/[¥￥]?\s*([\d,]+(?:\.\d+)?)/),amount=match?'￥'+match[1].replace(/,/g,''):'',payload={title:match?label.slice(0,match.index).replace(/[,\s]+$/,''):'合计:',amount:amount,discount:match?label.slice(match.index+match[0].length).replace(/^[,\s]+/,''):'',checkout:text(c.checkout),subtitle:'',allText:'全选',checked:c.selectAll&&c.selectAll.getAttribute('aria-selected')==='true',showSelect:true,available:valid&&amount!==''};if(!payload.available){payload.contract=Array.prototype.slice.call(footer.querySelectorAll("*"),0,48).map(function(e){return [e.tagName,e.getAttribute("role"),e.getAttribute("aria-label"),e.getAttribute("aria-checked"),String(e.textContent||"").slice(0,60)];});restore();}var token=JSON.stringify(payload);if(force||token!==last){last=token;send({__liquidtabCartState:payload});}}catch(e){restore();}}
function schedule(){if(queued||!alive||!active)return;queued=true;var token=generation;requestAnimationFrame(function(){if(token!==generation)return;queued=false;publish(false);});}
function scheduleLayout(){if(layoutQueued||!alive||!active||!ready)return;layoutQueued=true;var token=generation;requestAnimationFrame(function(){if(token!==generation)return;layoutQueued=false;layout();});}
function disconnect(){if(observer)observer.disconnect();if(chromeObserver)chromeObserver.disconnect();if(additions)additions.disconnect();watched=new WeakSet();}
function observe(){if(observer)observer.observe(footer,{subtree:true,childList:true,characterData:true,attributes:true,attributeFilter:['aria-label','aria-selected','aria-disabled']});if(additions)additions.observe(observationRoot||document.body||document,{childList:true,subtree:true});}
function action(event){try{var data=event.detail||event.data||event;if(data.kind==='__dispose'){alive=false;generation++;disconnect();restore(true);window.removeEventListener('__liquidtabCartAction',action);window.removeEventListener('resize',scheduleLayout);delete window.__liquidtabCartController;return;}
 if(data.kind==='__pause'){if(!active)return;active=false;generation++;queued=false;layoutQueued=false;disconnect();return;}
 if(data.kind==='__resume'){activation=Number(data.phase)||0;if(active){publish(true);return;}active=true;generation++;refreshDom();discoveredChrome=false;observe();holdNative();if(ready)layout();publish(true);return;}
 if(data.kind==='__visualFallback'){restore();return;}if(data.kind==='__publish'){publish(true);return;}if(data.kind==='__geometry'){var geometry=JSON.parse(data.phase);Object.keys(geometry).forEach(function(k){options[k]=geometry[k];});if(ready)layout();return;}if(data.kind==='__visualReady'){if(!active||!refreshDom())return;var c=controls();if(!c.checkout||!c.price||!c.selectAll)return;var alreadyReady=ready;owned=true;holdNative();ready=true;layout();if(alreadyReady){send({__liquidtabCartReady:true});return;}var token=generation;requestAnimationFrame(function(){requestAnimationFrame(function(){if(token===generation&&alive&&active&&ready)send({__liquidtabCartReady:true});});});return;}if(!active||!refreshDom())return;var target=controls()[data.kind];if(!target||(data.phase!=='click'&&data.phase!=='down'))return;target.dispatchEvent(new Event(data.phase==='down'?'touchstart':'click',{bubbles:true,cancelable:true}));schedule();}catch(e){restore();}}
window.addEventListener('__liquidtabCartAction',action);
if(typeof MutationObserver==='function'){observer=new MutationObserver(schedule);observer.observe(footer,{subtree:true,childList:true,characterData:true,attributes:true,attributeFilter:['aria-label','aria-selected','aria-disabled']});}
function commonRoot(){var candidate=footer.parentNode;for(var parentDepth=0;candidate&&parentDepth<16;parentDepth++){var node=main,depth=0;while(node&&depth++<32){if(node===candidate)return candidate;node=node.parentNode;}candidate=candidate.parentNode;}return null;}
if(typeof MutationObserver==='function'){additions=new MutationObserver(function(records){if(!active||!alive)return;var budget=96,found=false;function visit(node,depth){if(!node||node===list||depth>4||budget--<=0)return;if(semanticClass(node,'footerPromotion')||semanticClass(node,'frameworkV5Container')){coupon=node;found=true;return;}if(node.id==='cartSubmitBar'||node.id==='mainCard'||node.id==='mainCardList'){schedule();found=true;}if(node.id==='back2Top'||semanticClass(node,'surveyIcon')){found=true;}var children=node.children||[];for(var i=0;i<children.length&&budget>0;i++)visit(children[i],depth+1);}records.forEach(function(r){if(coupon&&!coupon.parentNode&&r.removedNodes&&r.removedNodes.length){coupon=null;found=true;}var ancestor=r.target,depth=0;while(ancestor&&depth++<32){if(ancestor===list)return;ancestor=ancestor.parentNode;}Array.prototype.forEach.call(r.addedNodes||[],function(n){visit(n,0);});});if(found&&ready)scheduleLayout();});var observationRoot=commonRoot();
additions.observe(observationRoot||document.body||document,{childList:true,subtree:true});}
window.addEventListener('resize',scheduleLayout);
window.__liquidtabCartController={publish:publish,configure:function(next){Object.keys(next).forEach(function(k){options[k]=next[k];});if(ready)layout();publish(true);}};publish(true);
}catch(e){}
})(__LIQUIDTAB_OPTIONS__);
