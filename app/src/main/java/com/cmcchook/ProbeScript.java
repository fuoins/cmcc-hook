package com.cmcchook;

/**
 * 探路用的页面脚本。
 *
 * <p>三段：
 * <ol>
 *   <li>{@link #INSTALL} 装 XHR/fetch 钩子，把页面自己发的请求连同请求体、响应体留下来；</li>
 *   <li>{@link #COLLECT} 抓标题、正文、可点控件、Vue 组件和 data 键名、全局数据对象；</li>
 *   <li>{@link #DRAIN} 把攒下的请求取走并清空，避免同一批重复上报。</li>
 * </ol>
 * 页面上抓不到请求也没关系 —— 原生渲染的页面由 OkHttp 侧兜底，
 * 两边靠毫秒时间戳对齐。
 */
final class ProbeScript {

    private ProbeScript() {
    }

    /** 幂等：重复调用不会重复包装。 */
    static final String INSTALL = """
            (function(){
              var ID='__cmcc_probe';
              var P = window[ID] || {reqs:[], hooked:false};
              window[ID]=P;
              if(P.hooked){ return 'ready' }
              P.hooked=true;
              function trim(v,n){
                try{ v=String(v==null?'':v) }catch(e){ v='' }
                v=v.replace(/\\s+/g,' ');
                return v.length>n ? v.slice(0,n)+'…' : v;
              }
              function push(o){ try{ if(P.reqs.length<260){ o.at=Date.now(); P.reqs.push(o) } }catch(e){} }
              try{
                var XO=XMLHttpRequest.prototype.open, XS=XMLHttpRequest.prototype.send;
                XMLHttpRequest.prototype.open=function(m,u){
                  try{ this.__p={m:String(m||'GET').toUpperCase(),u:String(u||'')} }catch(e){}
                  return XO.apply(this,arguments);
                };
                XMLHttpRequest.prototype.send=function(b){
                  var x=this, p=x.__p||{m:'GET',u:''};
                  try{
                    x.addEventListener('load',function(){
                      push({m:p.m,u:p.u,req:trim(b,560),resp:trim(x.responseText,760),s:x.status});
                    });
                  }catch(e){}
                  return XS.apply(this,arguments);
                };
              }catch(e){}
              try{
                var F=window.fetch;
                if(F){
                  window.fetch=function(input,init){
                    var u='', m='GET', b='';
                    try{
                      u=(typeof input==='string')?input:((input&&input.url)||'');
                      m=String((init&&init.method)||(input&&input.method)||'GET').toUpperCase();
                      b=(init&&init.body)?String(init.body):'';
                    }catch(e){}
                    var task=F.apply(this,arguments);
                    try{
                      task.then(function(r){
                        try{
                          r.clone().text().then(function(t){
                            push({m:m,u:u,req:trim(b,560),resp:trim(t,760),s:r.status});
                          });
                        }catch(e){}
                      });
                    }catch(e){}
                    return task;
                  };
                }
              }catch(e){}
              return 'ok';
            })()
            """;

    /** 采集当前屏。返回 JSON 字符串。 */
    static final String COLLECT = """
            (function(){
              function clean(v,n){
                try{ v=String(v==null?'':v) }catch(e){ v='' }
                v=v.replace(/\\s+/g,' ').trim();
                return v.length>n ? v.slice(0,n)+'…' : v;
              }
              var out={title:'',url:'',text:'',links:[],controls:[],globals:[],vue:[],perf:[]};
              try{ out.title=clean(document.title,120) }catch(e){}
              try{ out.url=String(location.href) }catch(e){}
              try{ out.text=clean(document.body?document.body.innerText:'',1500) }catch(e){}
              var seen={};
              try{
                var nodes=document.querySelectorAll('a,button,[onclick],[role=button],input,textarea,li,img[alt]');
                for(var i=0;i<nodes.length && out.controls.length<80;i++){
                  var el=nodes[i];
                  var t=clean(el.innerText||el.textContent||el.getAttribute('alt')||el.getAttribute('placeholder')||'',48);
                  if(!t || t.length<2){ continue }
                  var tag=el.tagName?el.tagName.toLowerCase():'';
                  var href=el.getAttribute?String(el.getAttribute('href')||''):'';
                  if(!href && tag!=='a' && t.length>36){ continue }
                  var key=t+'|'+href;
                  if(seen[key]){ continue }
                  seen[key]=1;
                  out.controls.push({tag:tag,t:t,h:href});
                  if(href && href.indexOf('javascript')!==0){
                    out.links.push({t:t,h:href});
                  }
                }
              }catch(e){}
              try{
                var keys=['pageInitData','__INITIAL_STATE__','__NUXT__','__VUE__','vueApp','__DATA__','initData','h5Data'];
                for(var k=0;k<keys.length;k++){
                  if(window[keys[k]]!==undefined){ out.globals.push(keys[k]) }
                }
              }catch(e){}
              try{
                var root=document.querySelector('#app')||document.querySelector('#root')||document.body;
                var inst=root&&root.__vue__;
                if(inst){
                  var stack=[inst], guard=0;
                  while(stack.length && guard++<60){
                    var c=stack.shift();
                    var name=(c.$options&&(c.$options.name||c.$options._componentTag))||'';
                    var dk=[];
                    try{ dk=Object.keys(c.$data||{}).slice(0,14) }catch(e){}
                    if(name||dk.length){ out.vue.push({name:clean(name,40),data:dk.join(',')}) }
                    try{
                      var ch=c.$children||[];
                      for(var j=0;j<ch.length;j++){ stack.push(ch[j]) }
                    }catch(e){}
                  }
                }
              }catch(e){}
              try{
                var entries=performance.getEntriesByType('resource')||[];
                for(var e=0;e<entries.length && e<120;e++){
                  out.perf.push(entries[e].name);
                }
              }catch(e){}
              return JSON.stringify(out);
            })()
            """;

    /**
     * 单活动适配采集。
     *
     * <p>跟 {@link #COLLECT} 的差别只有三处，都是「写引擎要用、但全局盘点用不上」的：
     * 控件带上 class（定位按钮要用）、本地存储里跟活动相关的键值、
     * 正文留得更长（任务说明通常写在正文里）。
     *
     * <p>不点任何按钮：点了可能真的领奖、抽奖、下单。
     */
    static final String COLLECT_ACT = """
            (function(){
              function clean(v,n){
                try{ v=String(v==null?'':v) }catch(e){ v='' }
                v=v.replace(/\\s+/g,' ').trim();
                return v.length>n ? v.slice(0,n)+'…' : v;
              }
              function clsOf(el){
                try{
                  var c=el.className;
                  if(typeof c==='string'){ return clean(c,64) }
                  if(c&&c.baseVal){ return clean(c.baseVal,64) }
                }catch(e){}
                return '';
              }
              var out={title:'',url:'',text:'',links:[],controls:[],globals:[],vue:[],perf:[],storage:{},storageKeys:0};
              try{ out.title=clean(document.title,120) }catch(e){}
              try{ out.url=String(location.href) }catch(e){}
              try{ out.text=clean(document.body?document.body.innerText:'',2600) }catch(e){}
              var seen={};
              try{
                var nodes=document.querySelectorAll('a,button,[role=button],input,textarea,li,img[alt],[onclick],[class*=btn],[class*=Btn],[class*=button],[class*=tab],[class*=item],[class*=sign],[class*=icon],[class*=act],[class*=entry],[class*=link],[class*=Link]');
                for(var i=0;i<nodes.length && out.controls.length<120;i++){
                  var el=nodes[i];
                  var t=clean(el.innerText||el.textContent||el.getAttribute('alt')||el.getAttribute('placeholder')||'',60);
                  if(!t || t.length<2){ continue }
                  var tag=el.tagName?el.tagName.toLowerCase():'';
                  var href=el.getAttribute?String(el.getAttribute('href')||''):'';
                  if(!href && tag!=='a' && t.length>64){ continue }
                  var key=t+'|'+href;
                  if(seen[key]){ continue }
                  seen[key]=1;
                  out.controls.push({i:i,tag:tag,t:t,h:href,c:clsOf(el)});
                  if(href && href.indexOf('javascript')!==0){
                    out.links.push({t:t,h:href});
                  }
                }
              }catch(e){}
              try{
                var keys=['pageInitData','__INITIAL_STATE__','__NUXT__','__VUE__','vueApp','__DATA__','initData','h5Data'];
                for(var k=0;k<keys.length;k++){
                  if(window[keys[k]]!==undefined){ out.globals.push(keys[k]) }
                }
              }catch(e){}
              try{
                var root=document.querySelector('#app')||document.querySelector('#root')||document.body;
                var inst=root&&root.__vue__;
                if(inst){
                  var stack=[inst], guard=0;
                  while(stack.length && guard++<60){
                    var c=stack.shift();
                    var name=(c.$options&&(c.$options.name||c.$options._componentTag))||'';
                    var dk=[];
                    try{ dk=Object.keys(c.$data||{}).slice(0,14) }catch(e){}
                    if(name||dk.length){ out.vue.push({name:clean(name,40),data:dk.join(',')}) }
                    try{
                      var ch=c.$children||[];
                      for(var j=0;j<ch.length;j++){ stack.push(ch[j]) }
                    }catch(e){}
                  }
                }
              }catch(e){}
              try{
                var entries=performance.getEntriesByType('resource')||[];
                for(var e=0;e<entries.length && e<120;e++){
                  out.perf.push(entries[e].name);
                }
              }catch(e){}
              try{
                var ls=window.localStorage;
                out.storageKeys=ls.length;
                for(var s=0;s<ls.length && s<40;s++){
                  var k=ls.key(s)||'';
                  if(!k){ continue }
                  var lk=k.toLowerCase();
                  if(lk.indexOf('qwhd')<0 && lk.indexOf('act')<0 && lk.indexOf('welf')<0
                     && lk.indexOf('coc')<0 && lk.indexOf('diy')<0){ continue }
                  out.storage[k]=clean(ls.getItem(k),400);
                }
              }catch(e){}
              return JSON.stringify(out);
            })()
            """;

    /** 取走攒下的请求并清空。 */
    static final String DRAIN = """
            (function(){
              var P=window.__cmcc_probe||{reqs:[]};
              var list=P.reqs||[];
              P.reqs=[];
              return JSON.stringify(list);
            })()
            """;

    /**
     * 引导式采集（前台页面）：装点击监听 + 页面内浮层结束按钮 + 自动滚一屏。
     *
     * <p>页面是 App 自己的 WebView 打开的，用户在屏幕上能看见、能点。
     * 浮层是页面 DOM 里的元素，不需要任何悬浮窗权限。点浮层设置
     * {@code __cmcc_probe.done=true}，模块的轮询线程看到就收尾。
     */
    static final String COLLECT_LIVE = """
            (function(){
              if(window.__cmcc_live){ return 'installed' }
              window.__cmcc_live=1;
              var P=window.__cmcc_probe||{reqs:[],hooked:false};
              window.__cmcc_probe=P;
              P.clicks=P.clicks||[];
              try{
                document.addEventListener('click',function(e){
                  try{
                    var el=e.target;
                    while(el && el.nodeType!==1){ el=el.parentNode }
                    if(!el){ return }
                    var t=String(el.innerText||el.textContent||el.getAttribute('alt')||el.getAttribute('placeholder')||'')
                      .replace(/\\s+/g,' ').trim().slice(0,60);
                    var c='';
                    try{ c=String(el.className||'').replace(/\\s+/g,' ').trim().slice(0,64) }catch(err){}
                    if(!t && !c){ return }
                    P.clicks.push({at:Date.now(),tag:String(el.tagName||'').toLowerCase(),t:t,c:c});
                  }catch(err){}
                },true);
              }catch(e){}
              try{
                var f=document.createElement('div');
                f.id='__xhy_probe_bar';
                f.setAttribute('style','position:fixed;right:8px;bottom:100px;z-index:2147483647;'
                  +'background:rgba(103,58,183,.92);color:#fff;font-size:12px;line-height:1.4;'
                  +'padding:8px 12px;border-radius:18px;box-shadow:0 2px 8px rgba(0,0,0,.35);'
                  +'max-width:72vw;');
                f.innerHTML='采集进行中 · 点活动里的按钮，点我结束';
                f.addEventListener('click',function(){ P.done=true; f.innerHTML='正在收尾…'; });
                var mount=function(){
                  if(f.parentNode){ return }
                  if(document.body){ document.body.appendChild(f) }
                };
                mount();
                setTimeout(mount,1200);
                setTimeout(mount,3000);
              }catch(e){}
              try{
                window.scrollTo(0, Math.max(400, (document.documentElement.scrollHeight||0)/3));
              }catch(e){}
              return 'installed';
            })()
            """;

    /**
     * 轮询拉取增量：reqs/clicks 出队、done 标志、当前 URL/标题，顺带刷新浮层计数。
     */
    static final String PULL_LIVE = """
            (function(){
              var P=window.__cmcc_probe||{reqs:[],clicks:[]};
              var out={done:!!P.done,reqs:P.reqs||[],clicks:P.clicks||[]};
              P.reqs=[];P.clicks=[];
              try{ out.url=String(location.href) }catch(e){}
              try{ out.title=String(document.title||'') }catch(e){}
              try{
                P.seen=(P.seen||0)+out.reqs.length;
                var f=document.getElementById('__xhy_probe_bar');
                if(f && !P.done){
                  f.innerHTML='采集进行中 · 已抓 '+P.seen+' 条请求 · 点我结束';
                }
              }catch(e){}
              return JSON.stringify(out);
            })()
            """;

    static String scroll(int y) {
        return "(function(){try{window.scrollTo(0," + y + ");"
                + "var se=document.scrollingElement;"
                + "if(se){se.scrollTop=" + y + ";}"
                + "return 'ok'}catch(e){return 'no'}})()";
    }

    /** 页面总高度，用来算分屏滚动的位置。 */
    static final String HEIGHT = """
            (function(){
              try{
                var de=document.documentElement||{}, b=document.body||{};
                var se=document.scrollingElement||de;
                return String(Math.max(de.scrollHeight||0, b.scrollHeight||0,
                  se.scrollHeight||0, window.innerHeight||0));
              }catch(e){ return '0' }
            })()
            """;
}
