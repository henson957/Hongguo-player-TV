'use strict';
(function(){
 var main=document.getElementById('main'),overlay=document.getElementById('overlay'),pending={},sequence=0,version=0,toastTimer;
 var state={view:'home',type:'real',tag:'',page:1,items:[],hasMore:false,keyword:'',searchSession:'',searching:false,searchFamilies:[],searchMatched:0,searchPartial:false,searchProblem:'',detail:null,detailFavorite:false,libraryHint:'未登录 · 记录保存在这台电视上',signedIn:false,syncEnabled:true,episodePage:0,priorFocus:null};
 var navs=[['home','▶','推荐'],['catalog','⌕','找剧'],['rank','♛','排行榜'],['history','◷','历史'],['favorites','☆','收藏']];
 document.getElementById('navigation').innerHTML=navs.map(function(n){return '<button class="nav" data-view="'+n[0]+'" data-focus="nav-'+n[0]+'"><span class="nav-icon">'+n[1]+'</span>'+n[2]+'</button>';}).join('');
 function esc(s){return String(s==null?'':s).replace(/[&<>"']/g,function(c){return {'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c];});}
 function image(s){return /^https:\/\//i.test(s||'')?esc(s):'';}
 // The WebView shipped with Android 6 supports generators and Promise.
 function asyncTask(task){
  return function(){
   var steps=task.apply(this,arguments);
   return new Promise(function(resolve,reject){
    function step(method,value){
     var result;
     try{result=steps[method](value);}catch(error){reject(error);return;}
     if(result.done){resolve(result.value);return;}
     Promise.resolve(result.value).then(function(value){step('next',value);},function(error){step('throw',error);});
    }
    step('next');
   });
  };
 }
 function findIndex(items,match){for(var i=0;i<items.length;i++)if(match(items[i]))return i;return -1;}
 function find(items,match){return items[findIndex(items,match)];}
 function rpc(method,args){return new Promise(function(resolve,reject){var id=String(++sequence);if(!window.TVNative){reject(new Error('请在电视版应用中打开'));return;}var timer=setTimeout(function(){delete pending[id];reject(new Error('请求超时，请检查网络后重试'));},90000);pending[id]={resolve:resolve,reject:reject,timer:timer};window.TVNative.call(id,method,JSON.stringify(args||{}));});}
 window.TVReply=function(id,response){var p=pending[id];if(!p)return;clearTimeout(p.timer);delete pending[id];if(response.ok)p.resolve(response.data);else p.reject(new Error(response.error||'加载失败'));};
 function resize(){document.getElementById('app').style.transform='scale('+Math.min(innerWidth/1280,innerHeight/720)+')';}
 addEventListener('resize',resize);resize();
 function toast(value){var t=document.getElementById('toast');t.textContent=value;t.style.display='block';clearTimeout(toastTimer);toastTimer=setTimeout(function(){t.style.display='none';},3300);}
 function focusFirst(root,selector){requestAnimationFrame(function(){var el=(root||main).querySelector(selector||'button,input');if(el)el.focus();});}
 function navActive(){Array.prototype.forEach.call(document.querySelectorAll('[data-view]'),function(n){n.classList.toggle('active',n.dataset.view===state.view);});}
 function spinner(label){main.innerHTML='<div class="empty"><div class="spinner"></div><p>'+esc(label||'正在加载好剧…')+'</p></div>';}
 function card(s,index,small){return '<button class="card" data-series="'+esc(s.id)+'" data-focus="card-'+esc(s.id)+'" aria-label="'+esc(s.title)+'"><div class="poster"><img loading="lazy" src="'+image(s.cover)+'" alt="">'+(state.view==='rank'?'<span class="rank-index">'+(index+1)+'</span>':'')+'<span class="badge">'+esc(s.badge||'')+'</span></div><div class="card-title">'+esc(s.title)+'</div><div class="card-meta">'+esc(cardMeta(s))+'</div></button>';}
 function cardMeta(s){if(state.view==='history'||state.view==='cloudHistory')return '第 '+((s.index||0)+1)+' 集 · 已看 '+Math.floor((s.position||0)/60000)+' 分钟';if(state.view==='search'&&s.seasonLabel)return s.seasonLabel+(s.tags?' · '+s.tags:'');return s.tags||s.description||'红果短剧';}
 function empty(title,description,retry){return '<div class="empty"><div class="empty-symbol">'+(retry?'↻':'◇')+'</div><h2>'+esc(title)+'</h2><p>'+esc(description)+'</p>'+(retry?'<button class="primary" data-action="retry">重新加载</button>':'')+'</div>';}
 function types(){return '<div class="filter-row"><span class="filter-label">体裁</span>'+[['real','真人剧'],['comic','漫剧'],['ai','AI剧']].map(function(t){return '<button class="chip '+(state.type===t[0]?'selected':'')+'" data-type="'+t[0]+'">'+t[1]+'</button>';}).join('')+'</div>';}
 var tags={real:[['','全部'],['romance','爱情'],['period','年代'],['comeback','逆袭'],['costume','古装'],['urban','都市'],['cute-kids','萌宝'],['suspense','悬疑'],['family','家庭'],['comedy','喜剧']],comic:[['','全部'],['creative','脑洞'],['fantasy','玄幻'],['drama','剧情'],['apocalypse','末世'],['wealthy-family','豪门'],['wonder','奇幻']],ai:[['','全部'],['creative','脑洞'],['fantasy','玄幻'],['drama','剧情'],['apocalypse','末世'],['sci-fi','科幻']]};
 function filters(){return '<div class="filters">'+types()+(state.view==='catalog'?'<div class="filter-row"><span class="filter-label">主题</span>'+tags[state.type].map(function(t){return '<button class="chip '+(state.tag===t[0]?'selected':'')+'" data-tag="'+t[0]+'">'+t[1]+'</button>';}).join('')+'</div>':'')+'</div>';}
 function renderHome(){
  if(!state.items.length){main.innerHTML=empty('暂时没有推荐','可以重新加载，或到“找剧”搜索。',true);return;}
  var first=state.items[0];
  main.innerHTML='<div class="home"><div class="hero-background"></div><div class="hero-shade"></div><div class="home-top"><div class="tabs">'+[['real','真人剧'],['comic','漫剧'],['ai','AI剧']].map(function(t){return '<button class="chip '+(state.type===t[0]?'selected':'')+'" data-type="'+t[0]+'">'+t[1]+'</button>';}).join('')+'</div></div><section class="hero"><img class="hero-cover" src="'+image(first.cover)+'" alt=""><div class="hero-info"><div class="eyebrow">今日好剧 · 热播推荐</div><h1>'+esc(first.title)+'</h1><div class="hero-meta">'+esc(first.badge||first.tags||'红果短剧')+'</div><div class="hero-desc">'+esc(first.description||'好戏正在上演，选择剧集，开启大屏追剧。')+'</div><div class="hero-actions"><button class="primary" data-series="'+esc(first.id)+'" data-focus="hero-play">▶ 立即观看</button><button class="secondary" data-action="browse">找更多好剧</button></div></div></section><section class="home-strip"><div class="section-head"><h2>大家都在看</h2><button data-action="allrank">查看全部 ›</button></div><div class="strip">'+state.items.slice(1,7).map(card).join('')+'</div></section><div class="home-footer">方向键移动 · 确认查看 · 菜单打开设置</div></div>';
  var url=first.cover||'';if(/^https:\/\//i.test(url))main.querySelector('.hero-background').style.backgroundImage='url('+JSON.stringify(url)+')';
 }
 var browsePagination;
 function isBrowseView(){return state.view==='catalog'||state.view==='rank';}
 function renderList(){
  var title={catalog:'找剧',rank:'排行榜',search:'搜索结果',history:'观看历史',favorites:'我的收藏',cloudFavorites:'我的收藏',cloudHistory:'观看历史'}[state.view]||'找剧';
  var search=(state.view==='catalog'||state.view==='search')?'<form class="search-form" id="searchForm"><input id="keyword" maxlength="80" placeholder="剧名、全拼或首字母" value="'+esc(state.keyword)+'" aria-label="输入剧名、全拼或首字母"><button class="primary" type="submit">搜索</button><button class="secondary search-keyboard-open" type="button" data-action="searchKeyboard">遥控键盘</button></form>':'<span class="hint">'+(isLibraryView(state.view)?esc(state.libraryHint):'红果热播好剧')+'</span>';
  if(state.view==='search'){main.innerHTML='<div class="page"><header class="page-head"><h1>'+title+'</h1>'+search+'</header><div id="searchResults">'+searchContent()+'</div></div>';return;}
  var browse=isBrowseView();
  var footer=browse?(state.hasMore?'<div class="browse-footer"><span role="status"></span><button class="secondary" data-action="browseRetry" hidden>重试加载</button></div>':''):(state.hasMore?'<button class="secondary load-more" data-action="more">加载更多</button>':'');
  main.innerHTML='<div class="page"><header class="page-head"><h1>'+title+'</h1>'+search+'</header>'+(browse?filters():'')+(state.items.length?'<div class="catalog'+(browse?' browse-list':'')+'"><div class="grid">'+state.items.map(card).join('')+'</div>'+footer+'</div>':empty(state.view==='history'?'还没有观看记录':state.view==='favorites'?'还没有收藏':'没有找到相关短剧',state.view==='history'?'看过的剧会出现在这里，并为你保存进度。':state.view==='favorites'?'在剧集详情中选择“收藏”，下次更方便找到。':'可以换一个关键词或分类试试。',false))+'</div>';
  browsePagination=null;
  var scroller=main.querySelector('.browse-list');
  if(!scroller||!state.hasMore)return;
  var pagination={scroller:scroller,grid:scroller.querySelector('.grid'),status:scroller.querySelector('[role="status"]'),retry:scroller.querySelector('[data-action="browseRetry"]'),version:version,top:scroller.scrollTop,loading:false,failed:false};
  browsePagination=pagination;
  scroller.addEventListener('scroll',function(){
   var top=scroller.scrollTop,down=top>pagination.top;
   pagination.top=top;if(down)maybeLoadBrowse(pagination);
  });
  scroller.addEventListener('wheel',function(event){if(event.deltaY>0)maybeLoadBrowse(pagination);});
 }
 function maybeLoadBrowse(pagination){
  var scroller=pagination.scroller;
  if(!pagination.failed&&scroller.scrollHeight-scroller.scrollTop-scroller.clientHeight<=300)appendBrowsePage(pagination);
 }
 var appendBrowsePage=asyncTask(function*(pagination){
  if(pagination.version!==version||pagination.loading||!state.hasMore||!overlay.classList.contains('hidden'))return;
  pagination.loading=true;
  pagination.status.textContent='正在加载更多…';
  try{
   var rows=[];
   // A page can overlap the previous one. Advance through a short run of duplicate pages.
   for(var attempt=0;attempt<3&&state.hasMore&&!rows.length;attempt++){
    var next=state.page+1;
    var data=yield rpc(state.view,{type:state.type,tag:state.tag,page:next});
    if(pagination.version!==version)return;
    state.page=next;state.hasMore=!!data.hasMore;
    var seen=Object.create(null);
    state.items.forEach(function(item){seen[item.id]=true;});
    rows=(data.items||[]).filter(function(item){if(seen[item.id])return false;seen[item.id]=true;return true;});
   }
   var start=state.items.length;
   if(rows.length){
    state.items=state.items.concat(rows);
    pagination.grid.insertAdjacentHTML('beforeend',rows.map(function(item,index){return card(item,start+index);}).join(''));
   }
   if(document.activeElement===pagination.retry){
    var cardToFocus=pagination.grid.children[start]||pagination.grid.lastElementChild;
    if(cardToFocus)cardToFocus.focus();
   }
   pagination.failed=false;pagination.retry.hidden=true;pagination.status.textContent='';
  }catch(e){
   if(pagination.version!==version)return;
   pagination.failed=true;pagination.retry.hidden=false;pagination.status.textContent='加载失败';
  }finally{pagination.loading=false;}
 });
 var load=asyncTask(function*(append){
  if(state.view==='search'){loadSearch(append);return;}
  var token=++version,view=state.view;
  if(!append){state.page=1;spinner();}else{var more=main.querySelector('[data-action="more"]');if(more)more.textContent='正在加载…';}
  try{
   var method=view==='home'?'rank':view,args={type:state.type,tag:state.tag,page:state.page,keyword:state.keyword};
   var data=yield rpc(method,args);if(token!==version)return;
   state.items=append?state.items.concat(data.items||[]).filter(function(s,i,a){return findIndex(a,function(x){return x.id===s.id;})===i;}):(data.items||[]);
   state.hasMore=!!data.hasMore;if(isLibraryView(view))libraryHint(data);
   if(view==='home')renderHome();else renderList();
   focusFirst(main,append?'[data-action="more"]':view==='home'?'[data-focus="hero-play"]':'.card,button,input');
  }catch(e){if(token!==version)return;if(append){state.page=Math.max(1,state.page-1);toast(e.message);var b=main.querySelector('[data-action="more"]');if(b)b.textContent='加载更多';}else{main.innerHTML=empty('暂时无法加载',e.message,true);focusFirst(main);}}
 });
 var searchTimer,searchSignature='',keyboardQuery='';
 function submitSearch(value){
  var keyword=value.trim();if(!keyword){toast('请输入剧名、全拼或首字母');return;}
  state.keyword=keyword;state.view='search';navActive();load(false);
 }
 function updateKeyboardQuery(){
  var display=overlay.querySelector('.keyboard-query');
  display.textContent=keyboardQuery||'例如：爱情公寓可输入 aiqinggongyu 或 aqgy';
  display.classList.toggle('hint',!keyboardQuery);
 }
 function openSearchKeyboard(button){
  var keys='ABCDEFGHIJKLMNOPQRSTUVWXYZ1234567890'.split('').map(function(key){
   return '<button type="button" class="keyboard-key" data-search-key="'+key.toLowerCase()+'">'+key+'</button>';
  }).join('');
  keyboardQuery=document.getElementById('keyword').value;state.priorFocus=button;
  dialog('<section class="dialog search-keyboard" aria-labelledby="keyboardTitle"><h2 id="keyboardTitle">遥控键盘</h2><div class="keyboard-query" aria-live="polite"></div><div class="keyboard-keys">'+keys+'</div><div class="keyboard-actions"><button class="secondary" data-action="keyboardDelete">退格</button><button class="secondary" data-action="keyboardClear">清空</button><button class="secondary" data-action="close">关闭</button><button class="primary" data-action="keyboardSearch">搜索</button></div></section>');
  updateKeyboardQuery();
 }
 function stopSearch(){clearTimeout(searchTimer);var id=state.searchSession;state.searchSession='';if(id)rpc('searchCancel',{session:id}).catch(function(){});}
 function searchContent(){
  var status=state.searchProblem||(state.searching?(state.searchMatched?'已找到 '+state.searchMatched+' 部匹配剧集，正在补齐相关剧集…':'正在搜索并补全剧名…'):state.searchMatched?'已找到 '+state.searchMatched+' 部匹配剧集':state.items.length?'下面是相关搜索结果':'当前未找到匹配剧集');
  var families=(state.searchFamilies||[]).map(function(family){return '<section class="search-family"><h2>'+esc(family.title)+'<small>同系列 · 已找到 '+family.items.length+' 部</small></h2><div class="season-picks">'+family.items.map(function(item){return '<button class="season-choice" data-series="'+esc(item.id)+'" data-focus="season-'+esc(item.id)+'" aria-label="'+esc(item.title)+'">'+esc(item.label)+'</button>';}).join('')+'</div></section>';}).join('');
  var more=state.hasMore?'<button class="secondary load-more" data-action="more" '+(state.searching?'disabled':'')+'>'+(state.searching?'正在查找…':'继续查找更多匹配')+'</button>':'';
  var body=state.items.length?'<div class="catalog"><div class="grid">'+state.items.map(card).join('')+'</div>'+more+'</div>':state.searching?'<div class="empty"><div class="spinner"></div><p>支持输入部分剧名，正在查找匹配内容…</p></div>':empty(state.searchPartial||state.searchProblem?'搜索暂未完成':'暂未找到匹配',state.searchProblem||(state.searchPartial?'可以重试，或继续查找更多匹配。':'可以继续查找，或换一个关键词。'),state.searchPartial||!!state.searchProblem)+more;
  return '<p id="searchStatus" class="search-status" role="status">'+esc(status)+'</p>'+(families?'<div class="search-families">'+families+'</div>':'')+body;
 }
 function updateSearchContent(){
  var region=document.getElementById('searchResults');if(!region)return;
  var active=document.activeElement,inside=region.contains(active),key=inside&&active.getAttribute('data-focus'),more=inside&&active.dataset.action==='more';
  var prior=state.priorFocus&&state.priorFocus.getAttribute('data-focus'),scroll=region.querySelector('.catalog'),top=scroll?scroll.scrollTop:0;
  region.innerHTML=searchContent();
  var nodes=region.querySelectorAll('[data-focus]');
  if(prior){var source=find(nodes,function(node){return node.dataset.focus===prior;});if(source)state.priorFocus=source;}
  if(inside&&overlay.classList.contains('hidden')){
   var replacement=key?find(nodes,function(node){return node.dataset.focus===key;}):more?region.querySelector('[data-action="more"]:not(:disabled)'):null;
   if(!replacement&&more)replacement=region.querySelector('.card:last-child')||region.querySelector('.season-choice');
   if(replacement)replacement.focus();
  }
  scroll=region.querySelector('.catalog');if(scroll)scroll.scrollTop=top;
 }
 function applySearch(data){
  var signature=JSON.stringify(data);state.searchSession=data.session||state.searchSession;
  state.items=data.items||[];state.searchFamilies=data.families||[];state.searching=!!data.searching;state.hasMore=!!data.hasMore;
  state.searchMatched=typeof data.matched==='number'?data.matched:state.items.length;state.searchPartial=!!data.partial;state.searchProblem='';
  if(signature!==searchSignature){searchSignature=signature;updateSearchContent();}
 }
 function scheduleSearch(token){
  clearTimeout(searchTimer);if(!state.searching||!state.searchSession)return;
  searchTimer=setTimeout(function(){pollSearch(token);},800);
 }
 var pollSearch=asyncTask(function*(token){
  var id=state.searchSession;if(state.view!=='search'||token!==version||!id)return;
  try{var data=yield rpc('searchStatus',{session:id});if(state.view!=='search'||token!==version||state.searchSession!==id)return;applySearch(data);scheduleSearch(token);}
  catch(e){if(state.view==='search'&&token===version){state.searching=false;state.searchProblem=e.message;updateSearchContent();}}
 });
 var loadSearch=asyncTask(function*(append){
  var token=++version,id=state.searchSession;clearTimeout(searchTimer);
  if(!append){stopSearch();state.page=1;state.items=[];state.searchFamilies=[];state.searchMatched=0;state.hasMore=false;searchSignature='';}
  state.searching=true;state.searchProblem='';
  if(!append){renderList();focusFirst(main,'#searchForm button[type="submit"]');}else updateSearchContent();
  try{
   var data=yield rpc('search',{keyword:state.keyword,page:append?state.page:1,session:append?id:''});
   if(token!==version||state.view!=='search'){if(data.session)rpc('searchCancel',{session:data.session}).catch(function(){});return;}
   applySearch(data);scheduleSearch(token);
  }catch(e){if(token!==version||state.view!=='search')return;state.searching=false;state.searchProblem=e.message;state.searchPartial=true;if(append)state.page=Math.max(1,state.page-1);updateSearchContent();}
 });
 var settings=asyncTask(function*(focusSelector){
  if(state.view!=='settings')return;
  var token=++version;spinner('正在读取设置…');
  try{
   var d=yield rpc('settings');if(token!==version)return;state.signedIn=!!d.signedIn;state.syncEnabled=d.syncEnabled!==false;
   var qualityChoices=[[0,'自动'],[1080,'1080P'],[720,'720P'],[480,'480P']].map(function(item){
    var selected=item[0]===(d.quality||0);
    return '<button class="chip '+(selected?'selected':'')+'" aria-pressed="'+selected+'" data-pref="playback_quality" data-pref-value="'+item[0]+'">'+item[1]+'</button>';
   }).join('');
   main.innerHTML='<div class="page"><header class="page-head"><h1>设置</h1><span class="hint">红果电视 · '+esc(d.version)+'</span></header><div class="settings-list"><section class="setting"><div><h2>播放清晰度</h2><p>自动选择最高可播清晰度。指定档位不可播放时尝试更低档位。</p></div><div class="settings-row">'+qualityChoices+'</div></section><section class="setting"><div><h2>观看历史</h2><p>未登录时保存在电视；登录后按自动合并设置同步到当前账号。</p></div><button class="secondary" data-action="clear">清空历史</button></section><section class="setting" data-setting="about"><div><h2>关于红果电视</h2><p>按红果电脑版界面风格制作的第三方电视测试版。<br>支持找剧、倍速播放、预加载连播，以及账号收藏与观看历史自动合并。</p></div></section><section class="setting"><div><h2>设备与最近提示</h2><div class="diagnostic">'+esc(d.model)+' · Android '+esc(d.android)+'<br>'+esc(d.lastProblem||'暂无错误记录')+'</div></div></section></div></div>';
   var about=main.querySelector('[data-setting="about"]');
   var legal=document.createElement('button');legal.className='secondary';legal.dataset.action='license';legal.textContent='开源许可';about.appendChild(legal);
   settingsFeatures(d);focusFirst(main,focusSelector||'[data-pref="playback_quality"].selected');
  }catch(e){main.innerHTML=empty('无法读取设置',e.message,true);focusFirst(main);}
 });
 var authMode='qr',authId='',authEpoch=0,authTimer,authCountdown,authBusy=false,retryUntil=0;
 function stopAuth(){++authEpoch;clearTimeout(authTimer);clearInterval(authCountdown);authBusy=false;var id=authId;authId='';rpc('authCancel',{session:id}).catch(function(){});}
 function loginMessage(message){var e=document.getElementById('loginStatus');if(e)e.textContent=message;}
 function loginButtons(busy){authBusy=busy;Array.prototype.forEach.call(main.querySelectorAll('[data-auth]'),function(b){b.disabled=busy;});}
 var account=asyncTask(function*(){
  if(state.view!=='account')return;
  var token=++version;spinner('正在读取账号…');
  try{
   var d=yield rpc('account');if(token!==version||state.view!=='account')return;
   main.innerHTML='<div class="page"><header class="page-head"><h1>我的账号</h1><span class="hint">红果账号</span></header><section class="account-panel" id="accountPanel"></section></div>';
   state.signedIn=!!d.signedIn;state.syncEnabled=!d.sync||d.sync.enabled!==false;var p=document.getElementById('accountPanel');
   if(d.signedIn){
    p.innerHTML='<h2 class="account-name">'+esc(d.nickname)+'</h2><p class="hint">'+(d.displayId?'红果号：'+esc(d.displayId):'已取得官网登录状态；可刷新账号资料')+'</p><div class="account-actions"><button class="primary" data-auth="refresh">刷新账号资料</button><button class="secondary" data-view="favorites">我的收藏</button><button class="secondary" data-view="history">观看历史</button><button class="secondary" data-action="logout">退出账号</button></div><p class="account-note">'+(d.saved?'登录信息已加密保存在这台电视上。':'当前登录只在本次运行有效，重启应用后需要重新登录。')+'<br>首次登录会合并未登录时的本机收藏和历史；后续按账号分别保存。</p><div id="loginStatus" class="login-status" role="status"></div>';
   }else{
    p.innerHTML='<div class="account-tabs"><button class="chip '+(authMode==='qr'?'selected':'')+'" data-auth-mode="qr">扫码登录</button><button class="chip '+(authMode==='sms'?'selected':'')+'" data-auth-mode="sms">短信验证码</button></div>'+(authMode==='qr'?'<div class="login-layout"><div class="qr-side"><div class="qr-box" id="qrBox">获取二维码后<br>用手机扫码</div><button class="primary" data-auth="qr">获取二维码</button></div><div class="login-guide"><h2>使用自己的红果账号</h2><p>1. 获取二维码<br>2. 用手机扫码，按手机提示完成确认<br>3. 电视会自动完成登录</p><p>若扫码无法使用，可以选择短信验证码。</p></div></div>':'<form class="login-form" id="loginForm"><label for="loginPhone">手机号</label><input id="loginPhone" type="tel" inputmode="tel" maxlength="24" autocomplete="off" placeholder="11 位手机号"><button class="secondary" type="button" data-auth="send">发送验证码</button><label for="loginCode">短信验证码</label><input id="loginCode" type="password" inputmode="numeric" maxlength="8" autocomplete="off" placeholder="输入验证码"><br><button class="primary" type="submit" data-auth="verify">登录已有账号</button></form>')+'<div id="loginStatus" class="login-status" role="status"></div><p class="account-note">登录由红果官网验证，仅登录已有账号。手机号和验证码不会保存在电视上。<br>遇到滑块验证时，请改用扫码登录。</p>';
   }
   if(d.signedIn)accountFeatures(d,p);focusFirst(main);
  }catch(e){if(token===version){main.innerHTML=empty('无法读取账号',e.message,true);focusFirst(main);}}
 });
 var pollLogin=asyncTask(function*(epoch,deadline){
  if(epoch!==authEpoch||state.view!=='account')return;
  if(Date.now()>=deadline){loginMessage('二维码已过期，请重新获取');return;}
  try{
   var d=yield rpc('authPoll',{session:authId});if(epoch!==authEpoch||state.view!=='account')return;
   if(d.confirmed){authId='';clearInterval(authCountdown);yield account();toast('账号已登录');return;}
   authTimer=setTimeout(function(){pollLogin(epoch,deadline);},2500);
  }catch(e){if(epoch===authEpoch){clearInterval(authCountdown);loginMessage(e.message+'；可以重新获取二维码。');}}
 });
 var authAction=asyncTask(function*(action){
  if(authBusy)return;
  if(action==='qr'){
   stopAuth();var epoch=authEpoch;loginButtons(true);loginMessage('正在获取二维码…');
   try{
    var d=yield rpc('authQr');if(epoch!==authEpoch||state.view!=='account'){rpc('authCancel',{session:d.session}).catch(function(){});return;}
    authId=d.session;var img=document.createElement('img');img.alt='红果官网登录二维码';img.src=d.image;var box=document.getElementById('qrBox');box.textContent='';box.appendChild(img);
    var deadline=Date.now()+d.expiresIn*1000;
    function countdown(){var seconds=Math.max(0,Math.ceil((deadline-Date.now())/1000));loginMessage(seconds?'请用手机扫码并确认 · '+seconds+' 秒后过期':'二维码已过期，请重新获取');if(!seconds)clearInterval(authCountdown);}
    countdown();authCountdown=setInterval(countdown,1000);authTimer=setTimeout(function(){pollLogin(epoch,deadline);},2000);
   }catch(e){if(epoch===authEpoch)loginMessage(e.message);}
   finally{if(epoch===authEpoch)loginButtons(false);}return;
  }
  if(action==='send'){
   if(Date.now()<retryUntil){loginMessage('请稍后再发送验证码');return;}
   var phone=document.getElementById('loginPhone').value.trim();if(!/^(1[0-9]{10}|\+[0-9 ]{7,20})$/.test(phone)){loginMessage('请输入 11 位手机号，或以 + 开头的国际号码');return;}
   var epoch=authEpoch;loginButtons(true);loginMessage('正在发送验证码…');
   try{var d=yield rpc('authSend',{phone:phone,session:authId});if(epoch!==authEpoch){rpc('authCancel',{session:d.session}).catch(function(){});return;}authId=d.session;retryUntil=Date.now()+d.retrySeconds*1000;loginMessage('验证码已发送，请在下方输入');var input=document.getElementById('loginPhone');input.disabled=true;document.getElementById('loginCode').focus();
    clearInterval(authCountdown);authCountdown=setInterval(function(){var b=main.querySelector('[data-auth="send"]');if(!b)return;var n=Math.max(0,Math.ceil((retryUntil-Date.now())/1000));b.textContent=n?n+' 秒后重发':'重新发送';b.disabled=n>0||authBusy;if(!n)clearInterval(authCountdown);},1000);
   }catch(e){if(epoch===authEpoch)loginMessage(e.message);}finally{if(epoch===authEpoch){loginButtons(false);var b=main.querySelector('[data-auth="send"]');if(b)b.disabled=Date.now()<retryUntil;}}return;
  }
  if(action==='verify'){
   var input=document.getElementById('loginCode'),code=input.value;if(!authId){loginMessage('请先发送验证码');return;}if(!/^[0-9]{4,8}$/.test(code)){loginMessage('请输入短信验证码');return;}
   var epoch=authEpoch;loginButtons(true);loginMessage('正在确认登录…');
   try{var d=yield rpc('authVerify',{session:authId,code:code});input.value='';if(epoch!==authEpoch)return;authId='';clearInterval(authCountdown);yield account();toast('账号已登录');}
   catch(e){input.value='';if(epoch===authEpoch)loginMessage(e.message);}finally{if(epoch===authEpoch)loginButtons(false);}return;
  }
  if(action==='refresh'){
   var epoch=authEpoch;loginButtons(true);loginMessage('正在刷新账号资料…');
   try{yield rpc('accountRefresh');if(epoch===authEpoch)yield account();}catch(e){if(epoch===authEpoch)loginMessage(e.message);}finally{if(epoch===authEpoch)loginButtons(false);}
  }
 });
 function route(name){if(state.view==='search')stopSearch();if(state.view==='account')stopAuth();closeDialog(false);state.view=name;navActive();if(name==='settings')settings();else if(name==='account')account();else load(false);}
 function dialog(content){overlay.innerHTML=content;overlay.classList.remove('hidden');focusFirst(overlay);}
 function closeDialog(restore){overlay.classList.add('hidden');overlay.innerHTML='';state.detail=null;if(restore&&state.priorFocus&&document.contains(state.priorFocus))state.priorFocus.focus();}
 function renderDetail(){
  var d=state.detail,episodes=d.episodes||[],start=state.episodePage*30,total=Math.ceil(episodes.length/30),resume=Math.min(episodes.length-1,d.resumeIndex||0);
  dialog('<section class="dialog"><button class="dialog-close" data-action="close" aria-label="关闭">×</button><div class="detail-top"><img class="detail-cover" src="'+image(d.cover)+'" alt=""><div class="detail-info"><h2>'+esc(d.title)+'</h2><div class="hint">'+esc(d.badge||'共 '+episodes.length+' 集')+'</div><div class="detail-description">'+esc(d.description||d.tags||'选择下方剧集开始播放。')+'</div><div class="detail-actions"><button class="primary" data-play="'+resume+'">'+((d.resumePosition>0||resume>0)?'▶ 续看第 '+(resume+1)+' 集':'▶ 开始观看')+'</button><button class="secondary" data-action="favorite">'+(state.detailFavorite?'★ 已收藏':'☆ 收藏')+'</button></div></div></div><div class="episodes-header"><h3>选集 · 共 '+episodes.length+' 集</h3><div class="episode-pages"><button data-ep-page="-1">上一页</button><span>'+(state.episodePage+1)+' / '+Math.max(1,total)+'</span><button data-ep-page="1">下一页</button></div></div><div class="episode-grid">'+episodes.slice(start,start+30).map(function(ep,i){var n=start+i;return '<button class="episode '+(n===resume?'current':'')+'" data-play="'+n+'">'+(n+1)+'</button>';}).join('')+'</div></section>');
  var actions=overlay.querySelector('.detail-actions');if(actions)actions.insertAdjacentHTML('beforeend','<button class="secondary" data-action="comments">评论</button>');
  focusFirst(overlay,'[data-play="'+resume+'"],[data-play]');
 }
 var detail=asyncTask(function*(id){
  state.priorFocus=document.activeElement;dialog('<section class="dialog"><button class="dialog-close" data-action="close" aria-label="关闭">×</button><div class="empty"><div class="spinner"></div><p>正在加载剧集…</p></div></section>');
  var requestId=id;overlay.dataset.request=id;
  try{var d=yield rpc('detail',{id:id});if(overlay.classList.contains('hidden')||overlay.dataset.request!==requestId)return;state.detail=d.detail;state.detailFavorite=d.favorite;state.episodePage=Math.floor((d.detail.resumeIndex||0)/30);renderDetail();}
  catch(e){if(overlay.classList.contains('hidden'))return;dialog('<section class="small-dialog dialog"><button class="dialog-close" data-action="close">×</button><h2>暂时无法打开</h2><p>'+esc(e.message)+'</p><div class="detail-actions"><button class="primary" data-retry-series="'+esc(id)+'">重试</button><button class="secondary" data-action="close">返回</button></div></section>');}
 });
 document.addEventListener('click',asyncTask(function*(event){
  var b=event.target;
  while(b&&b.tagName!=='BUTTON')b=b.parentElement;
  if(!b)return;
  if(b.dataset.searchKey){if(keyboardQuery.length<80){keyboardQuery+=b.dataset.searchKey;updateKeyboardQuery();}return;}
  if(b.dataset.view){route(b.dataset.view);return;}
  if(b.dataset.authMode){stopAuth();authMode=b.dataset.authMode;account();return;}
  if(b.dataset.auth){if(b.dataset.auth!=='verify')authAction(b.dataset.auth);return;}
  if(b.dataset.series){detail(b.dataset.series);return;}
  if(b.dataset.type){state.type=b.dataset.type;state.tag='';load(false);return;}
  if(b.hasAttribute('data-tag')){state.tag=b.dataset.tag;load(false);return;}
  if(b.dataset.retrySeries){detail(b.dataset.retrySeries);return;}
  if(b.hasAttribute('data-ep-page')&&state.detail){var next=state.episodePage+Number(b.dataset.epPage);if(next>=0&&next<Math.ceil(state.detail.episodes.length/30)){state.episodePage=next;renderDetail();focusFirst(overlay,'.episode');}return;}
  if(b.hasAttribute('data-play')&&state.detail){try{toast('正在打开播放器…');var selected=Number(b.dataset.play),resumeCloud=!!state.detail.cloudResume&&selected===state.detail.resumeIndex;yield rpc('play',{id:state.detail.id,index:selected,cloudResume:resumeCloud,position:resumeCloud?state.detail.resumePosition:0});}catch(e){toast(e.message);}return;}
  var action=b.dataset.action;
  if(action==='searchKeyboard'){openSearchKeyboard(b);return;}
  if(action==='keyboardDelete'||action==='keyboardClear'){
   keyboardQuery=action==='keyboardClear'?'':keyboardQuery.replace(/[\uD800-\uDBFF][\uDC00-\uDFFF]$|[\s\S]$/,'');updateKeyboardQuery();return;
  }
  if(action==='keyboardSearch'){
   if(keyboardQuery.trim())closeDialog(false);
   submitSearch(keyboardQuery);return;
  }
  if(yield featureAction(b,action))return;
  if(action==='logout'){state.priorFocus=b;dialog('<section class="dialog small-dialog"><h2>退出红果账号？</h2><p>退出后移除电视上的登录信息。此账号的记录和待同步修改会单独保留，再次登录同一账号时恢复；其他账号不会读取这些记录。</p><div class="detail-actions"><button class="secondary" data-action="close">继续登录</button><button class="primary" data-action="confirmLogout">退出账号</button></div></section>');return;}
  if(action==='confirmLogout'){try{yield rpc('accountLogout');closeDialog(false);account();toast('已退出账号');}catch(e){toast(e.message);}return;}
  if(action==='license'){state.priorFocus=b;try{var legal=yield rpc('licenses');if(state.view!=='settings')return;dialog('<section class="dialog"><button class="dialog-close" data-action="close" aria-label="关闭">×</button><h2>开源许可</h2><pre class="license-copy">'+esc(legal.text)+'</pre><button class="primary" data-action="close">返回设置</button></section>');}catch(e){toast(e.message);}return;}
  if(action==='close'){closeDialog(true);return;}
  if(action==='browse'){route('catalog');return;}
  if(action==='allrank'){route('rank');return;}
  if(action==='retry'){if(state.view==='settings')settings();else if(state.view==='account')account();else load(false);return;}
  if(action==='browseRetry'&&browsePagination){appendBrowsePage(browsePagination);return;}
  if(action==='more'){state.page++;load(true);return;}
  if(action==='favorite'&&state.detail){try{var result=yield rpc('favorite',{id:state.detail.id});state.detailFavorite=result.favorite;b.textContent=result.favorite?'★ 已收藏':'☆ 收藏';toast(result.favorite?'已加入收藏':'已取消收藏');}catch(e){toast(e.message);}return;}
  if(action==='clear'){state.priorFocus=b;dialog('<section class="dialog small-dialog"><h2>清空观看历史？</h2><p>'+esc(state.signedIn?'当前账号在本机保存的观看记录和续播进度会被清除。删除会在自动合并开启后同步到红果账号。':'未登录时保存在这台电视上的观看记录和续播进度会被清除。')+'</p><div class="detail-actions"><button class="secondary" data-action="close">保留记录</button><button class="primary" data-action="confirmClear">确认清空</button></div></section>');return;}
  if(action==='confirmClear'){try{yield rpc('clearHistory');closeDialog(false);settings();toast('观看历史已清空');}catch(e){toast(e.message);}}
 }));
 document.addEventListener('submit',function(event){if(event.target.id==='loginForm'){event.preventDefault();authAction('verify');return;}if(event.target.id!=='searchForm')return;event.preventDefault();var input=document.getElementById('keyword');submitSearch(input.value);if(!input.value.trim())input.focus();});
 function syncText(sync){
  var text=sync.message||'登录后自动合并本机与云端资料';
  if(sync.enabled===false)text='自动合并已关闭，本机修改已保留';
  if(sync.pending)text+=' · 待同步 '+sync.pending+' 项';
  if(sync.lastPullAt)text+=' · 最近读取 '+new Date(sync.lastPullAt).toLocaleTimeString('zh-CN',{hour:'2-digit',minute:'2-digit'});
  return text;
 }
 function syncMarkup(sync){
  return '<p id="syncStatus" role="status">'+esc(syncText(sync))+'</p><div class="account-actions"><button class="secondary" data-sync="toggle" data-enabled="'+(!sync.enabled)+'">自动合并：'+(sync.enabled?'开启':'关闭')+'</button><button class="secondary" data-sync="retry">立即合并</button></div><p class="sync-rules">收藏合并去重；观看进度以最近一次实际观看为准，支持重看前面的集数。<br>同时发生修改时，删除优先；网络中断会保留本机修改，恢复后继续合并。</p><p id="syncConflict" class="sync-footnote">'+(sync.conflicts?'已自动处理 '+sync.conflicts+' 次冲突。':'')+(sync.partial?' 云端只返回部分记录，较早的本机历史会保留。':'')+'</p>';
 }
 function accountFeatures(d,p){
  var sync=d.sync||{enabled:true,pending:0},note=p.querySelector('.account-note');
  if(note)note.textContent=(d.saved?'登录信息已加密保存在电视本机。':'本次登录暂未保存，重启后需重新登录。')+' 首次登录自动合并未登录时的收藏与历史；不同账号的记录、删除标记和待同步修改分别保存。';
  p.insertAdjacentHTML('beforeend','<div class="sync-panel">'+syncMarkup(sync)+'</div>');
 }
 function isLibraryView(view){return ['history','favorites','cloudHistory','cloudFavorites'].indexOf(view)>=0;}
 function libraryHint(d){
  state.signedIn=!!d.signedIn;state.syncEnabled=!d.sync||d.sync.enabled!==false;
  state.libraryHint=!state.signedIn?'未登录 · 记录保存在这台电视上':!state.syncEnabled?'当前账号的本机记录 · 自动合并已关闭':d.sync&&!d.sync.accountReady?'正在确认账号，确认后自动合并':'本机与云端自动合并'+(d.sync&&d.sync.pending?' · '+d.sync.pending+' 项待同步':'');
 }
 var libraryTimer,libraryRefresh=0;
 var refreshLibraryUi=asyncTask(function*(){
  var token=++libraryRefresh,view=state.view,pageToken=version;
  if(isLibraryView(view)){
   var active=document.activeElement,activeKey=active&&active.getAttribute('data-focus'),oldIndex=active&&active.dataset.series?findIndex(state.items,function(row){return row.id===active.dataset.series;}):-1;
   var priorId=state.priorFocus&&state.priorFocus.dataset.series,scroller=main.querySelector('.catalog'),scroll=scroller?scroller.scrollTop:0;
   var count=Math.max(24,Math.ceil(state.items.length/24)*24);
   try{
    var data=yield rpc(view,{page:1,limit:count});if(token!==libraryRefresh||view!==state.view||pageToken!==version)return;
    libraryHint(data);state.items=data.items||[];state.hasMore=!!data.hasMore;state.page=Math.max(1,Math.ceil(state.items.length/24));renderList();
    scroller=main.querySelector('.catalog');if(scroller)scroller.scrollTop=scroll;
    var cards=main.querySelectorAll('.card'),replacement=activeKey&&find(main.querySelectorAll('[data-focus]'),function(e){return e.dataset.focus===activeKey;});
    if(!replacement&&oldIndex>=0)replacement=cards[Math.min(oldIndex,cards.length-1)];
    if(priorId)state.priorFocus=find(cards,function(e){return e.dataset.series===priorId;})||cards[0]||document.querySelector('[data-view="'+view+'"]');
    if(overlay.classList.contains('hidden')&&active&&main.contains(active)===false&&active!==document.body&&(activeKey||oldIndex>=0)){if(replacement)replacement.focus();else focusFirst(document,'[data-view="'+view+'"]');}
   }catch(e){}
  }
  if(state.view==='account'&&main.querySelector('.sync-panel')){
   try{var accountData=yield rpc('account');if(token!==libraryRefresh||state.view!=='account'||!accountData.signedIn)return;var sync=accountData.sync||{},text=document.getElementById('syncStatus'),toggle=main.querySelector('[data-sync="toggle"]'),name=main.querySelector('.account-name'),foot=document.getElementById('syncConflict');
    if(text)text.textContent=syncText(sync);if(toggle){toggle.dataset.enabled=String(!sync.enabled);toggle.textContent='自动合并：'+(sync.enabled?'开启':'关闭');}
    if(name)name.textContent=accountData.nickname;var idLine=name&&name.nextElementSibling;if(idLine&&accountData.displayId)idLine.textContent='红果号：'+accountData.displayId;
    if(foot)foot.textContent=(sync.conflicts?'已自动处理 '+sync.conflicts+' 次冲突。':'')+(sync.partial?' 云端只返回部分记录，较早的本机历史会保留。':'');
   }catch(e){}
  }
  if(state.detail&&!overlay.classList.contains('hidden')){
   var id=state.detail.id;
   try{var entry=yield rpc('libraryEntry',{id:id});if(token!==libraryRefresh||!state.detail||state.detail.id!==id)return;
    state.detailFavorite=!!entry.favorite;state.detail.resumeIndex=entry.resumeIndex||0;state.detail.resumePosition=entry.resumePosition||0;
    var favorite=overlay.querySelector('[data-action="favorite"]');if(favorite)favorite.textContent=entry.favorite?'★ 已收藏':'☆ 收藏';
    var resume=Math.max(0,Math.min(state.detail.episodes.length-1,state.detail.resumeIndex)),play=overlay.querySelector('.detail-actions [data-play]');
    if(play){play.dataset.play=String(resume);play.textContent=(state.detail.resumePosition>0||resume>0)?'▶ 续看第 '+(resume+1)+' 集':'▶ 开始观看';}
    Array.prototype.forEach.call(overlay.querySelectorAll('.episode'),function(button){button.classList.toggle('current',Number(button.dataset.play)===resume);});
   }catch(e){}
  }
 });
 window.TVLibraryChanged=function(){clearTimeout(libraryTimer);libraryTimer=setTimeout(refreshLibraryUi,180);};
 function settingsFeatures(d){
  var list=main.querySelector('.settings-list');if(!list)return;
  list.insertAdjacentHTML('beforeend','<section class="setting feature-setting"><div><h2>弹幕显示</h2><p>播放时可用遥控器菜单调整弹幕，关闭后不请求弹幕数据。</p></div><div class="settings-row">'+[['hidden','关闭'],['single','单行'],['top','上方'],['full','全屏']].map(function(m){return '<button class="chip '+(m[0]===(d.danmakuMode||'top')?'selected':'')+'" data-pref="danmaku_mode" data-pref-value="'+m[0]+'">'+m[1]+'</button>';}).join('')+'</div><div class="settings-row font-row"><span class="hint">字号</span>'+[16,20,24].map(function(n){return '<button class="chip '+(n===(d.danmakuSize||20)?'selected':'')+'" data-pref="danmaku_size" data-pref-value="'+n+'">'+({16:'小',20:'标准',24:'大'}[n])+'</button>';}).join('')+'</div></section>');
 }
 var featureAction=asyncTask(function*(b,action){
  try{
   if(b.dataset.pref){yield rpc('featurePreference',{name:b.dataset.pref,value:b.dataset.pref==='danmaku_mode'?b.dataset.prefValue:Number(b.dataset.prefValue)});settings('[data-pref="'+b.dataset.pref+'"][data-pref-value="'+b.dataset.prefValue+'"]');return true;}
   if(action==='comments'&&state.detail){yield rpc('openComments',{id:state.detail.id,index:state.detail.resumeIndex||0});return true;}
   if(b.dataset.sync){yield rpc(b.dataset.sync==='toggle'?'cloudSyncToggle':'cloudSyncNow',{enabled:b.dataset.enabled==='true'});refreshLibraryUi();return true;}
  }catch(e){toast(e.message);return true;}
  return false;
 });
 function candidates(){var root=overlay.classList.contains('hidden')?document:overlay;return Array.prototype.filter.call(root.querySelectorAll('button,input'),function(e){var r=e.getBoundingClientRect();return !e.disabled&&r.width>0&&r.height>0;});}
 function move(key){
  var nodes=candidates(),current=document.activeElement;
  if(nodes.indexOf(current)<0){if(nodes.length)nodes[0].focus();return;}
  var browseDown=key==='ArrowDown'&&isBrowseView()&&current.classList.contains('card');
  if(browseDown)nodes=nodes.filter(function(node){return current.parentNode===node.parentNode||node.dataset.action==='browseRetry';});
  var r=current.getBoundingClientRect(),x=r.left+r.width/2,y=r.top+r.height/2,best=null,bestScore=Infinity;
  nodes.forEach(function(node){if(node===current)return;var q=node.getBoundingClientRect(),dx=q.left+q.width/2-x,dy=q.top+q.height/2-y;
   var primary=key==='ArrowLeft'?-dx:key==='ArrowRight'?dx:key==='ArrowUp'?-dy:dy;
   var cross=(key==='ArrowLeft'||key==='ArrowRight')?Math.abs(dy):Math.abs(dx);
   if(primary<3)return;
   var score=primary+cross*3.4+(cross>primary?cross*2:0);
   if(score<bestScore){bestScore=score;best=node;}
  });
  if(best){best.focus();try{best.scrollIntoView({block:'nearest',inline:'nearest'});}catch(e){best.scrollIntoView(false);}}
  if(browseDown&&browsePagination)maybeLoadBrowse(browsePagination);
 }
 document.addEventListener('keydown',function(e){
  var key={Left:'ArrowLeft',Right:'ArrowRight',Up:'ArrowUp',Down:'ArrowDown',Esc:'Escape'}[e.key]||e.key;
  if(!key||key==='Unidentified')key={13:'Enter',27:'Escape',37:'ArrowLeft',38:'ArrowUp',39:'ArrowRight',40:'ArrowDown'}[e.keyCode];
  if(['ArrowLeft','ArrowRight','ArrowUp','ArrowDown'].indexOf(key)>=0){
   if(e.target.tagName==='INPUT'&&(key==='ArrowLeft'||key==='ArrowRight'))return;
   e.preventDefault();move(key);
  }else if(key==='Escape'||key==='BrowserBack'){e.preventDefault();TVBack();}
  else if(key==='Enter'&&e.target===document.body){e.preventDefault();focusFirst(main);}
 });
 window.TVBack=function(){if(!overlay.classList.contains('hidden')){closeDialog(true);return true;}if(state.view!=='home'){route('home');return true;}return false;};
 window.TVMenu=function(){if(!overlay.classList.contains('hidden'))return;route('settings');};
 window.TVResume=function(){if(state.view==='account'){stopAuth();account();}else{window.TVLibraryChanged();if(state.view==='search'&&state.searchSession)pollSearch(version);}};
 window.TVSuspend=function(){clearTimeout(searchTimer);if(state.view==='account')stopAuth();};
 document.addEventListener('error',function(e){if(e.target.tagName==='IMG')e.target.style.visibility='hidden';},true);
 navActive();load(false);
 window.TVReady=true;
 if(window.TVNative&&typeof window.TVNative.ready==='function')window.TVNative.ready();
})();

