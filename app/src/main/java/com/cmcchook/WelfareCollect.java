package com.cmcchook;

import android.content.Context;
import android.content.Intent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 福利专区就地采集。
 *
 * <p>为什么不再自己拼接口：福利专区（businessPlatform/shop）是 Vue 单页，
 * 楼层整屏都是运营配的图片，DOM 里没有文字也没有链接；接口走在 X5 内核的网络栈里，
 * OkHttp 钩子看不到。唯一稳定拿得到「当前到底上架了哪些活动」的地方，
 * 是 App 自己那个页面里 —— localStorage 的楼层缓存、Vue 实例的 $data、
 * performance 里的资源列表。
 *
 * <p>所以这里不新建页面去"重放"用户的登录流程，而是直接接管 App 当前 WebView，
 * 在它的上下文里执行一段采集脚本，把上面这几样一次性取回来。
 * 登录态、签名、Cookie 全是 App 自己的，不存在"未登录"。
 *
 * <p>采集结果一方面解析成入口清单落到「福利」页，另一方面原样存进探路报告，
 * 需要深挖某个入口时再导出看。
 */
final class WelfareCollect {

    /** 福利专区。点首页「领福利」进的就是这一页。 */
    static final String ENTRY = "https://wx.10086.cn/website/businessPlatform/shop"
            + "?pageid=f38143861a4a4a9fac920fdeecd119bb&channelId=P00000054178&yx=1597107001";

    private static final String[] HOSTS = {"10086.cn", "lncmcc.com", "cmcc-cs.cn"};

    /** 能落地到活动页的特征。 */
    private static final String[] ACT_HINTS = {
            "qwhdhub", "hlwyxhdhub", "rightsmarket-h5-canvas", "diy-client",
            "goldeneggs", "turntable", "newbuildgroup", "coc3", "act-wedrecharge",
            "raisegoose", "act-", "rightsmarket",
    };

    /** 图片、脚本、埋点、下载引导，一律不当入口。 */
    private static final String[] SKIP_HINTS = {
            ".png", ".jpg", ".jpeg", ".gif", ".webp", ".css", ".js", ".woff",
            ".ico", ".svg", ".map", "beian.miit", "transferdownload", "dx.10086.cn",
            "sensorsdata", "jiguang", "growing", "gdp_", "log.10086", "res-",
    };

    private static final Pattern URL_PATTERN =
            Pattern.compile("https?://[A-Za-z0-9._~:/?#\\[\\]@!$&'()*+,;=%-]{12,320}");

    private static final int MAX_CANDIDATES = 60;
    private static volatile long lastEntryTry;

    private WelfareCollect() {
    }

    /**
     * 采集脚本。
     *
     * <p>只做读取，不点任何按钮、不改任何状态。取回来的东西：
     * localStorage / sessionStorage 全量键值（楼层规则、A_C_CODE、JWT 都在这里）、
     * Vue 组件树的 $data（楼层内容和跳转地址在这里）、performance 的资源列表、
     * 页面里的链接和图片。
     */
    static final String SCRIPT = """
            (function(){
              function cut(v,n){
                try{ v=String(v==null?'':v) }catch(e){ v='' }
                return v.length>n ? v.slice(0,n) : v;
              }
              var out={title:'',url:'',text:'',html:'',ls:{},ss:{},vue:[],res:[],imgs:[],links:[],
                       urls:[],frames:[],globals:[]};
              try{ out.title=cut(document.title,120) }catch(e){}
              try{ out.url=String(location.href) }catch(e){}
              try{ out.text=cut(document.body?document.body.innerText:'',1500) }catch(e){}
              try{ out.html=cut(document.body?document.body.innerHTML:'',8000) }catch(e){}

              function dump(st,limit,cap){
                var o={};
                try{
                  for(var i=0;i<st.length && i<limit;i++){
                    var k=st.key(i); if(!k){ continue }
                    var v=''; try{ v=st.getItem(k)||'' }catch(e){}
                    o[k]=cut(v,cap);
                  }
                }catch(e){}
                return o;
              }
              try{ out.ls=dump(window.localStorage,20,4000) }catch(e){}
              try{ out.ss=dump(window.sessionStorage,20,3000) }catch(e){}

              // 1) 属性里的跳转地址：banner、图标卡往往只挂在 onclick / data-* 上，没有 <a href>
              try{
                var A=['href','src','data-url','data-href','data-link','data-jumpurl',
                       'data-jump-url','data-redirect','data-acturl','data-activityurl',
                       'data-path','onclick'];
                var els=document.querySelectorAll('a,area,iframe,img,button,div,span,li');
                for(var i=0;i<els.length && out.urls.length<400;i++){
                  for(var j=0;j<A.length;j++){
                    var v=''; try{ v=els[i].getAttribute(A[j])||'' }catch(e){}
                    if(v.indexOf('http')<0){ continue }
                    var m=v.match(/https?:\\/\\/[^"'\\\\\\s<>()]{16,320}/g);
                    if(m){ for(var k=0;k<m.length && out.urls.length<400;k++){ out.urls.push(m[k]) } }
                  }
                }
              }catch(e){}

              // 2) 正文 HTML 里出现的地址（懒加载出来的楼层也在里面）
              try{
                var body=document.body?document.body.innerHTML:'';
                var hm=body.match(/https?:\\/\\/[^"'\\\\\\s<>()]{16,320}/g)||[];
                for(var i=0;i<hm.length && out.urls.length<500;i++){ out.urls.push(hm[i]) }
              }catch(e){}

              // 3) 两个 storage 里的地址：楼层配置、A_C_CODE、跳转表都在这里
              try{
                var sts=[window.localStorage,window.sessionStorage];
                for(var s=0;s<sts.length;s++){
                  var st=sts[s]; if(!st){ continue }
                  for(var i=0;i<st.length && i<40;i++){
                    var k=st.key(i); if(!k){ continue }
                    var v=''; try{ v=st.getItem(k)||'' }catch(e){}
                    if(!v || v.indexOf('http')<0){ continue }
                    var m=v.match(/https?:\\/\\/[^"'\\\\\\s<>()]{16,320}/g)||[];
                    for(var j=0;j<m.length && out.urls.length<600;j++){ out.urls.push(m[j]) }
                  }
                }
              }catch(e){}

              // 4) 页面运行时挂到 window 上的配置对象（Vuex / 埋点配置 / 楼层数据）
              try{
                var skip={window:1,document:1,location:1,navigator:1,history:1,screen:1,
                          performance:1,console:1,localStorage:1,sessionStorage:1,
                          frames:1,parent:1,top:1,self:1,crypto:1,caches:1,indexedDB:1};
                var gk=Object.keys(window), gn=0;
                for(var i=0;i<gk.length && gn<4;i++){
                  var k=gk[i]; if(skip[k]){ continue }
                  var v; try{ v=window[k] }catch(e){ continue }
                  if(!v || typeof v!=='object'){ continue }
                  var s2=''; try{ s2=JSON.stringify(v) }catch(e){ continue }
                  if(!s2 || s2.length<60){ continue }
                  if(s2.indexOf('qwhdhub')<0 && s2.indexOf('hlwyxhdhub')<0
                     && s2.indexOf('diy-client')<0 && s2.indexOf('rightsmarket')<0
                     && s2.indexOf('newbuildgroup')<0){ continue }
                  out.globals.push({k:cut(k,30),d:cut(s2,8000)}); gn++;
                }
              }catch(e){}

              try{
                var es=performance.getEntriesByType('resource')||[];
                for(var i=0;i<es.length && out.res.length<300;i++){ out.res.push(cut(es[i].name,320)) }
              }catch(e){}

              try{
                var as=document.querySelectorAll('a[href]');
                for(var i=0;i<as.length && out.links.length<100;i++){
                  var h=as[i].getAttribute('href')||'';
                  if(h.indexOf('http')===0){ out.links.push({t:cut(as[i].innerText,40),h:h}) }
                }
              }catch(e){}

              try{
                var ifs=document.querySelectorAll('iframe[src]');
                for(var i=0;i<ifs.length && out.frames.length<30;i++){
                  out.frames.push(cut(ifs[i].getAttribute('src'),320));
                }
              }catch(e){}

              try{
                var is=document.querySelectorAll('img');
                for(var i=0;i<is.length && out.imgs.length<100;i++){
                  var im=is[i];
                  var src=im.currentSrc||im.src||im.getAttribute('data-src')
                        ||im.getAttribute('data-original')||'';
                  if(!src){ continue }
                  var p=(im.closest&&im.closest('a'))?im.closest('a'):null;
                  out.imgs.push({s:cut(src,240),a:cut(im.getAttribute('alt'),40),
                                 p:p?cut(p.getAttribute('href'),240):''});
                }
              }catch(e){}

              try{
                var root=null, host=document.querySelector('#app')||document.querySelector('#root');
                if(host&&host.__vue__){ root=host.__vue__ }
                if(!root){
                  var all=document.querySelectorAll('*');
                  for(var i=0;i<all.length&&i<500;i++){
                    if(all[i].__vue__){ root=all[i].__vue__; break }
                  }
                }
                if(root){
                  // 楼层配置就在某个组件的 $data / Vuex 里，长度动辄几万字符。
                  // 光截断保存会丢一半入口，所以先在这儿正则提一遍 URL。
                  var dig=function(obj,depth){
                    if(!obj || depth>4){ return }
                    var s3='';
                    try{ s3=JSON.stringify(obj) }catch(e){ return }
                    if(!s3 || s3.length<40 || s3.length>300000){ return }
                    var m3=s3.match(/https?:\\/\\/[^"'\\\\\\s<>()]{16,320}/g)||[];
                    for(var j3=0;j3<m3.length && out.urls.length<700;j3++){ out.urls.push(m3[j3]) }
                  };
                  try{ dig(root.$data,0) }catch(e){}
                  try{ if(root.$store&&root.$store.state){ dig(root.$store.state,0) } }catch(e){}

                  var q=[root], guard=0;
                  while(q.length && guard++<120){
                    var c=q.shift();
                    var nm=(c.$options&&(c.$options.name||c.$options._componentTag))||'';
                    var data={}, n=0;
                    try{
                      var keys=Object.keys(c.$data||{});
                      for(var k=0;k<keys.length&&k<20;k++){
                        var v=c.$data[keys[k]], t=typeof v;
                        if(t==='string'){ data[keys[k]]=cut(v,260); n++ }
                        else if(t==='number'||t==='boolean'){ data[keys[k]]=v; n++ }
                        else if(v&&t==='object'){
                          try{ data[keys[k]]=cut(JSON.stringify(v),600); n++ }catch(e){}
                          try{ dig(v,1) }catch(e){}
                        }
                      }
                    }catch(e){}
                    try{ dig(c.$data,1) }catch(e){}
                    if(nm||n>0){ out.vue.push({n:cut(nm,40),d:data}) }
                    try{
                      var ch=c.$children||[];
                      for(var j=0;j<ch.length;j++){ q.push(ch[j]) }
                    }catch(e){}
                  }
                }
              }catch(e){}

              // 去重，按出现顺序
              try{
                var seen={}, uniq=[];
                for(var i=0;i<out.urls.length;i++){
                  var u=out.urls[i]; if(!u||seen[u]){ continue }
                  seen[u]=1; uniq.push(u);
                }
                out.urls=uniq;
              }catch(e){}
              return JSON.stringify(out);
            })()
            """;

    // ---------------------------------------------------------------- 对外

    /**
     * 任务缺入口时静默取一次地址。
     *
     * <p>只读福利页楼层里的跳转，不滚动、不出清单、不刷新界面、不写「上次采集」。
     * 清单仍然只由手动「立即采集」更新；这里只为让任务拿到地址。
     *
     * @return 已经有可用的入口缓存返回 true
     */
    static boolean ensureEntries() {
        Context ctx = HookLog.context();
        if (ctx == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        long at = AccountScope.prefs(ctx, TaskApi.WELFARE_PREFS)
                .getLong(TaskApi.KEY_ENTRIES_AT, 0L);
        if (at > 0 && now - at < 12 * 3600_000L) {
            return true;
        }
        synchronized (WelfareCollect.class) {
            if (now - lastEntryTry < 60_000L) {
                return at > 0;
            }
            lastEntryTry = now;
        }
        WebPage page = null;
        boolean borrowed = false;
        try {
            Object live = PageAuto.webView();
            String here = PageAuto.currentUrl();
            if (live != null && here != null && isWelfarePage(here)) {
                page = WebPage.attach(live);
                borrowed = true;
            } else {
                page = WebPage.open(ENTRY, 20_000L, HOSTS, null, false);
                if (page != null) {
                    waitRendered(page, 10_000L);
                }
            }
            if (page == null) {
                return false;
            }
            JSONObject raw = collect(page, new StringBuilder(), false);
            if (raw == null) {
                return false;
            }
            List<String[]> found = new ArrayList<>();
            harvest(raw, found);
            boolean ok = saveEntries(toArray(found));
            HookLog.logcat("entries fetched=" + found.size() + " saved=" + ok);
            return ok;
        } catch (Throwable e) {
            HookLog.logcat("entry fetch failed: " + e);
            return false;
        } finally {
            if (page != null && !borrowed) {
                page.close();
            }
        }
    }

    /** 只留能落到活动页的地址；图片、埋点、下载引导不算。 */
    private static boolean looksLikeActivity(String url) {
        if (url == null) {
            return false;
        }
        String lower = url.toLowerCase(Locale.US);
        for (String skip : SKIP_HINTS) {
            if (lower.contains(skip)) {
                return false;
            }
        }
        for (String hint : ACT_HINTS) {
            if (lower.contains(hint)) {
                return true;
            }
        }
        return false;
    }

    /** 把采集到的地址存进 App 进程，供任务按活动号查。只留活动类地址。 */
    private static boolean saveEntries(JSONArray list) {
        Context ctx = HookLog.context();
        if (ctx == null || list == null) {
            return false;
        }
        try {
            JSONArray keep = new JSONArray();
            for (int i = 0; i < list.length(); i++) {
                JSONObject one = list.optJSONObject(i);
                if (one == null) {
                    continue;
                }
                String url = one.optString("u", "");
                if (url.isEmpty() || !looksLikeActivity(url)) {
                    continue;
                }
                keep.put(one);
            }
            if (keep.length() == 0) {
                return false;
            }
            AccountScope.prefs(ctx, TaskApi.WELFARE_PREFS)
                    .edit()
                    .putString(TaskApi.KEY_ENTRIES, keep.toString())
                    .putLong(TaskApi.KEY_ENTRIES_AT, System.currentTimeMillis())
                    .apply();
            return true;
        } catch (Throwable e) {
            HookLog.logcat("welfare cache failed: " + e);
            return false;
        }
    }

    private static JSONArray toArray(List<String[]> found) {
        JSONArray list = new JSONArray();
        for (String[] one : found) {
            try {
                JSONObject item = new JSONObject();
                item.put("n", one[0]);
                item.put("u", one[1]);
                list.put(item);
            } catch (Throwable ignored) {
            }
        }
        return list;
    }

    /** 手动：有 App 页面就就地采，没有就离屏开一次。 */
    static TaskRunner.Result run() {
        long begin = System.currentTimeMillis();
        StringBuilder detail = new StringBuilder();
        WebPage page = null;
        boolean borrowed = false;
        try {
            JSONObject raw = null;
            String from = "";

            // 优先用 App 当前正开着的页面：登录态、Vue 实例、localStorage 都是现成的。
            Object live = PageAuto.webView();
            String liveUrl = PageAuto.currentUrl();
            if (live != null && liveUrl != null && isWelfarePage(liveUrl)) {
                page = WebPage.attach(live);
                borrowed = true;
                from = "App 当前页面";
                raw = collect(page, detail, true);
            }

            if (raw == null) {
                detail.append("App 当前没有福利专区页面，离屏开一次\n");
                page = WebPage.open(ENTRY, 30_000L, HOSTS, null, true);
                if (page == null) {
                    return TaskRunner.Result.fail("采集失败：离屏 WebView 打不开福利专区",
                            detail.toString(),
                            "确认中国移动 App 能正常打开「领福利」再试。");
                }
                // open 只等到 innerHTML 有内容，那时 Vue 往往还没挂载。
                // 楼层数据在组件树里，多等一会儿再采。
                waitRendered(page, 15_000L);
                from = "离屏页面";
                raw = collect(page, detail, true);
            }

            if (raw == null) {
                return TaskRunner.Result.fail("采集失败：页面脚本没有返回数据",
                        detail.toString(), "再点一次「扫描福利专区」试试。");
            }

            String title = raw.optString("title", "");
            String url = raw.optString("url", "");
            int lsCount = raw.optJSONObject("ls") == null ? 0 : raw.optJSONObject("ls").length();
            int vueCount = raw.optJSONArray("vue") == null ? 0 : raw.optJSONArray("vue").length();
            detail.append("来源: ").append(from).append('\n')
                    .append("页面: ").append(title).append(" · ")
                    .append(ProbeSession.shortOf(url)).append('\n')
                    .append("localStorage: ").append(lsCount).append(" 项 · ")
                    .append("Vue 组件: ").append(vueCount).append(" 个\n");

            List<String[]> found = new ArrayList<>();
            JSONObject floor = parseFloor(raw);
            harvest(raw, found);
            // 不读活动页标题：名字由内置表提供，采集只负责发现新活动和刷新地址。

            JSONObject report = buildReport(raw, floor, found, begin);
            publish(report);

            detail.append("楼层规则: ")
                    .append(floor.optString("floorRuleCode", "未取到"))
                    .append(" / 省份 ")
                    .append(floor.optString("provinceCode", "-"))
                    .append(" / 专区 ")
                    .append(floor.optString("regionSet", "-")).append('\n');
            detail.append("入口: ").append(found.size()).append(" 个\n");
            for (int i = 0; i < found.size() && i < 24; i++) {
                detail.append("  ").append(found.get(i)[0].isEmpty()
                                ? ProbeSession.shortOf(found.get(i)[1])
                                : found.get(i)[0])
                        .append("  ").append(ProbeSession.shortOf(found.get(i)[1])).append('\n');
            }
            detail.append("报告: ").append(report.toString().length() / 1024)
                    .append(" KB，已存到「福利」页\n");
            String text = "采集完成 · " + found.size() + " 个入口 · 来源 " + from;
            return TaskRunner.Result.ok(text, detail.toString(), text);
        } catch (Throwable e) {
            return TaskRunner.Result.fail("采集失败：" + e, detail.toString(),
                    "把这次日志发出来即可。");
        } finally {
            if (page != null && !borrowed) {
                page.close();
            }
        }
    }

    /** 是不是福利专区那类页面（同域、能读到 QWHD_CACHE 的页面）。 */
    static boolean isWelfarePage(String url) {
        if (url == null) {
            return false;
        }
        String u = url.toLowerCase(Locale.US);
        return u.contains("businessplatform/shop")
                || u.contains("qwhdhub/qwhdmark")
                || u.contains("wx.10086.cn/website");
    }

    // ---------------------------------------------------------------- 采集

    /** 等到 Vue 挂载或者正文出现，最多 timeoutMs。 */
    private static void waitRendered(WebPage page, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            String state = page.eval("(function(){try{"
                    + "var a=document.querySelector('#app')||document.body;"
                    + "if(a&&a.__vue__){return 'vue'}"
                    + "var t=document.body?document.body.innerText:'';"
                    + "return t.length>40?'text':'no'}catch(e){return 'no'}})()", 3_000L);
            if ("vue".equals(state) || "text".equals(state)) {
                WebPage.sleep(800L);
                return;
            }
            WebPage.sleep(600L);
        }
    }

    /**
     * 从头滚到底。
     *
     * <p>福利专区的楼层是懒加载：不滚到那一段，DOM 里连图片节点都没有，
     * 采集脚本自然扫不到对应的跳转地址。只滚动、不点任何按钮，滚完回到顶部。
     *
     * @return 实际滚过的屏数，0 表示页面没有可滚内容
     */
    private static int scrollThrough(WebPage page) {
        int steps = 0;
        try {
            String ih = page.eval("String(Math.round(window.innerHeight||700))", 2_000L);
            int screen = 700;
            try {
                screen = Integer.parseInt(ih == null ? "700" : ih.trim());
            } catch (Throwable ignored) {
            }
            int step = Math.max(360, (int) (screen * 0.85));
            int lastY = -1;
            for (int i = 0; i < 16; i++) {
                page.eval("window.scrollBy(0," + step + ");1", 2_000L);
                WebPage.sleep(280L);
                String sy = page.eval(
                        "String(Math.round(window.scrollY||window.pageYOffset||0))", 2_000L);
                int y = lastY;
                try {
                    y = Integer.parseInt(sy == null ? "-1" : sy.trim());
                } catch (Throwable ignored) {
                }
                steps++;
                // 连续两次位置没变，说明到底了（或页面本身不够长）
                if (y <= lastY + 4) {
                    break;
                }
                lastY = y;
            }
            page.eval("window.scrollTo(0,0);1", 2_000L);
            WebPage.sleep(200L);
        } catch (Throwable e) {
            HookLog.logcat("welfare scroll failed: " + e);
        }
        return steps;
    }

    private static JSONObject collect(WebPage page, StringBuilder detail, boolean scroll) {
        try {
            if (scroll) {
                int steps = scrollThrough(page);
                if (steps > 0) {
                    detail.append("滚动 ").append(steps).append(" 屏，触发懒加载\n");
                }
            }
            String raw = page.eval(SCRIPT, 15_000L);
            if (raw == null) {
                detail.append("页面脚本超时（15 秒）\n");
                return null;
            }
            raw = raw.trim();
            if (raw.isEmpty() || "null".equals(raw)) {
                detail.append("页面脚本返回空（页面可能还没渲染完）\n");
                return null;
            }
            return new JSONObject(raw);
        } catch (Throwable e) {
            detail.append("页面脚本异常: ").append(e).append('\n');
            return null;
        }
    }

    /** 从 localStorage 的楼层缓存里取规则参数，下一版直接拿它调接口。 */
    private static JSONObject parseFloor(JSONObject raw) {
        JSONObject out = new JSONObject();
        try {
            JSONObject ls = raw.optJSONObject("ls");
            if (ls == null) {
                return out;
            }
            JSONArray names = ls.names();
            if (names == null) {
                return out;
            }
            for (int i = 0; i < names.length(); i++) {
                String key = names.optString(i, "");
                if (!key.contains("CONTACT_DATA")) {
                    continue;
                }
                String value = ls.optString(key, "");
                JSONObject root = new JSONObject(value);
                JSONObject rule = root.optJSONObject("value");
                if (rule == null) {
                    continue;
                }
                JSONObject data = rule.optJSONObject("floorRuleData");
                if (data == null) {
                    continue;
                }
                out.put("key", key);
                out.put("floorRuleCode", data.optString("floorRuleCode", ""));
                out.put("provinceCode", data.optString("provinceCode", ""));
                JSONArray floors = data.optJSONArray("floorRule");
                if (floors != null && floors.length() > 0) {
                    JSONObject first = floors.optJSONObject(0);
                    JSONArray regions = first == null ? null : first.optJSONArray("regionJson");
                    if (regions != null && regions.length() > 0) {
                        JSONArray set = regions.optJSONObject(0).optJSONArray("regionSet");
                        if (set != null && set.length() > 0) {
                            String region = set.optString(0, "");
                            out.put("regionSet", region);
                            int dash = region.lastIndexOf('-');
                            if (dash > 0) {
                                out.put("operationCode", region.substring(dash + 1));
                            }
                        }
                    }
                }
                return out;
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /** 把所有字段里的 URL 扫一遍，挑出活动入口。 */
    private static void harvest(JSONObject raw, List<String[]> found) {
        Set<String> seen = new LinkedHashSet<>();

        JSONArray res = raw.optJSONArray("res");
        if (res != null) {
            for (int i = 0; i < res.length(); i++) {
                add(found, seen, "", res.optString(i, ""), "");
            }
        }

        // 页面里直接提取出来的地址（属性、HTML、storage 三路合并去重）
        JSONArray urls = raw.optJSONArray("urls");
        if (urls != null) {
            for (int i = 0; i < urls.length(); i++) {
                add(found, seen, "", urls.optString(i, ""), "");
            }
        }

        JSONArray frames = raw.optJSONArray("frames");
        if (frames != null) {
            for (int i = 0; i < frames.length(); i++) {
                add(found, seen, "", frames.optString(i, ""), "");
            }
        }

        // window 上挂的配置对象
        JSONArray globals = raw.optJSONArray("globals");
        if (globals != null) {
            for (int i = 0; i < globals.length(); i++) {
                JSONObject one = globals.optJSONObject(i);
                if (one != null) {
                    scanText(one.optString("d", ""), found, seen, "");
                }
            }
        }

        JSONArray links = raw.optJSONArray("links");
        if (links != null) {
            for (int i = 0; i < links.length(); i++) {
                JSONObject one = links.optJSONObject(i);
                if (one != null) {
                    add(found, seen, one.optString("t", ""), one.optString("h", ""), "");
                }
            }
        }

        JSONArray imgs = raw.optJSONArray("imgs");
        if (imgs != null) {
            for (int i = 0; i < imgs.length(); i++) {
                JSONObject one = imgs.optJSONObject(i);
                if (one != null) {
                    add(found, seen, one.optString("a", ""), one.optString("p", ""), "");
                }
            }
        }

        // localStorage / sessionStorage / Vue $data / HTML：字符串里藏着入口
        scanJson(raw.optJSONObject("ls"), found, seen, "");
        scanJson(raw.optJSONObject("ss"), found, seen, "");
        JSONArray vue = raw.optJSONArray("vue");
        if (vue != null) {
            for (int i = 0; i < vue.length(); i++) {
                JSONObject one = vue.optJSONObject(i);
                if (one == null) {
                    continue;
                }
                scanJson(one.optJSONObject("d"), found, seen, one.optString("n", ""));
            }
        }
        scanText(raw.optString("html", ""), found, seen, "");
        dedupe(found);
    }

    /**
     * 同一个活动只留一条。
     *
     * <p>同一个入口在页面里会出现十几次：埋点参数不同、渠道号不同、
     * 推荐位不同。清单要给人看，不能一屏全是同一个活动。
     */
    private static void dedupe(List<String[]> found) {
        java.util.LinkedHashMap<String, String[]> unique = new java.util.LinkedHashMap<>();
        for (String[] one : found) {
            String key = one[1];
            int q = key.indexOf('?');
            if (q > 0) {
                key = key.substring(0, q);
            }
            String[] old = unique.get(key);
            if (old == null || (old[0].isEmpty() && !one[0].isEmpty())) {
                unique.put(key, one);
            }
        }
        found.clear();
        found.addAll(unique.values());
    }

    private static void scanJson(JSONObject obj, List<String[]> found, Set<String> seen,
                                 String name) {
        if (obj == null) {
            return;
        }
        JSONArray names = obj.names();
        if (names == null) {
            return;
        }
        for (int i = 0; i < names.length(); i++) {
            String key = names.optString(i, "");
            String value = obj.optString(key, "");
            if (value.isEmpty()) {
                continue;
            }
            // 「name + url」成对出现时，name 就是活动名
            String label = ("url".equalsIgnoreCase(key) || key.toLowerCase(Locale.US).contains("jump"))
                    ? name : "";
            scanText(value, found, seen, label);
        }
    }

    private static void scanText(String text, List<String[]> found, Set<String> seen,
                                 String name) {
        if (text == null || text.length() < 16) {
            return;
        }
        Matcher m = URL_PATTERN.matcher(text);
        int guard = 0;
        while (m.find() && guard++ < 300) {
            add(found, seen, name, m.group(), text);
        }
    }

    /** 命中活动特征、且不是静态资源的，才算入口。 */
    private static void add(List<String[]> found, Set<String> seen, String name, String url,
                            String context) {
        if (url == null || found.size() >= MAX_CANDIDATES) {
            return;
        }
        String clean = url.trim();
        int cut = clean.indexOf('#');
        if (cut > 0) {
            clean = clean.substring(0, cut);
        }
        if (clean.length() < 20) {
            return;
        }
        String lower = clean.toLowerCase(Locale.US);
        for (String skip : SKIP_HINTS) {
            if (lower.contains(skip)) {
                return;
            }
        }
        boolean hit = false;
        for (String hint : ACT_HINTS) {
            if (lower.contains(hint)) {
                hit = true;
                break;
            }
        }
        if (!hit) {
            return;
        }
        if (!seen.add(clean)) {
            return;
        }
        found.add(new String[]{pickName(name, context, clean), clean});
    }

    /**
     * 名字的取法：先看调用方给的，再从上下文里找 url 旁边最近的 title/name。
     * 福利专区的楼层数据是 {"floorName":"签到有礼","jumpUrl":"..."} 这种结构，
     * 挨着找比统一叫「活动」有用。
     */
    private static String pickName(String name, String context, String url) {
        if (name != null && !name.trim().isEmpty()) {
            return name.trim().replaceAll("\\s+", " ");
        }
        if (context == null || context.isEmpty()) {
            return "";
        }
        int at = context.indexOf(url);
        if (at <= 0) {
            return "";
        }
        String before = context.substring(Math.max(0, at - 160), at);
        String[] keys = {"\"floorName\":\"", "\"name\":\"", "\"title\":\"", "\"activityName\":\""};
        for (String key : keys) {
            int k = before.lastIndexOf(key);
            if (k < 0) {
                continue;
            }
            int from = k + key.length();
            int to = before.indexOf('"', from);
            if (to > from) {
                String value = before.substring(from, to).trim();
                if (!value.isEmpty() && value.length() <= 24) {
                    return value;
                }
            }
        }
        return "";
    }

    /**
     * 回传模块进程。
     *
     * <p>不整份发：localStorage 全量、Vue 每个组件的 $data 加起来几百 KB，
     * Binder 事务装不下，发着发着就静默失败。模块只需要「有哪些入口」，
     * 原始数据留在这边（需要时再单独取）。
     */
    private static void publish(JSONObject full) {
        Context ctx = HookLog.context();
        if (ctx == null) {
            return;
        }
        try {
            JSONObject slim = new JSONObject();
            slim.put("v", 2);
            slim.put("at", full.optLong("at"));
            slim.put("entry", ENTRY);
            slim.put("kind", "welfare");
            slim.put("candidates", full.optJSONArray("candidates"));
            slim.put("summary", full.optJSONObject("summary"));

            // 留一份在 App 进程：跑任务时要按活动号取入口，不能跨进程去模块那边要。
            saveEntries(full.optJSONArray("candidates"));

            JSONArray pages = full.optJSONArray("pages");
            JSONObject page = pages == null ? null : pages.optJSONObject(0);
            JSONObject brief = new JSONObject();
            if (page != null) {
                brief.put("title", page.optString("title", ""));
                brief.put("url", page.optString("url", ""));
                brief.put("label", "福利专区");
                brief.put("floor", page.optJSONObject("floor"));
            }
            JSONArray one = new JSONArray();
            one.put(brief);
            slim.put("pages", one);

            Intent intent = new Intent(LogReceiver.ACTION_PROBE);
            intent.setPackage(HookLog.MODULE_PACKAGE);
            intent.putExtra("json", slim.toString());
            ctx.sendBroadcast(intent);
        } catch (Throwable e) {
            HookLog.logcat("welfare publish failed: " + e);
        }
    }

    /**
     * 这个入口模块能不能自己跑。
     *
     * <p>只做粗判，给导出清单用：用户拿着它决定「哪些要做、哪些不做」，
     * 不必再去翻代码。
     */
    private static String implState(String url) {
        String u = url == null ? "" : url.toLowerCase(Locale.US);
        String[] done = {
                "qwhdmark/1021122301", "goldeneggs/1026071510",
                "diy-client/1126082530", "diy-client/1126086939",
                "diy-client/1126090514", "diy-client/1126090575",
                "raisegoose",
        };
        for (String one : done) {
            if (u.contains(one)) {
                return "done";
            }
        }
        return "new";
    }

    private static JSONObject buildReport(JSONObject raw, JSONObject floor,
                                          List<String[]> found, long begin) {
        JSONObject report = new JSONObject();
        try {
            report.put("v", 2);
            report.put("at", System.currentTimeMillis());
            report.put("entry", ENTRY);
            report.put("kind", "welfare");

            JSONObject page = new JSONObject();
            page.put("label", "福利专区");
            page.put("url", raw.optString("url", ""));
            page.put("title", raw.optString("title", ""));
            page.put("text", raw.optString("text", ""));
            page.put("html", raw.optString("html", ""));
            page.put("imgs", raw.optJSONArray("imgs"));
            page.put("links", raw.optJSONArray("links"));
            page.put("vue", raw.optJSONArray("vue"));
            page.put("localStorage", raw.optJSONObject("ls"));
            page.put("sessionStorage", raw.optJSONObject("ss"));
            page.put("floor", floor);

            JSONArray pages = new JSONArray();
            pages.put(page);
            report.put("pages", pages);

            JSONArray candidates = new JSONArray();
            for (String[] one : found) {
                JSONObject item = new JSONObject();
                item.put("n", one[0]);
                item.put("u", one[1]);
                item.put("s", implState(one[1]));
                candidates.put(item);
            }
            report.put("candidates", candidates);

            JSONObject summary = new JSONObject();
            summary.put("pages", 1);
            summary.put("reqs", raw.optJSONArray("res") == null ? 0 : raw.optJSONArray("res").length());
            summary.put("candidates", found.size());
            summary.put("cost", System.currentTimeMillis() - begin);
            report.put("summary", summary);
        } catch (Throwable ignored) {
        }
        return report;
    }
}
