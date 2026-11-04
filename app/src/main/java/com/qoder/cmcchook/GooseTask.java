package com.qoder.cmcchook;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 收鹅蛋换好礼（大鹅养成记）。
 *
 * 这个活动和另外两个不是一个体系：接口挂在 lyh.lncmcc.com/huodong-portal，
 * 请求体是国密加密后的 sinoEnParams、响应是 dataEn，URL 上还有瑞数（$_ts）动态签名，
 * 每次请求的 LJRkIeoW 都不一样。本地重放这条路走不通 —— 除非把整套瑞数 VM 也搬过来，
 * 成本远大于收益。
 *
 * 所以这里换一种做法：不自己发请求，而是等活动页打开后往那个 WebView 里注入脚本，
 * 让页面自己去点「打卡 / 领饲料 / 投喂 / 收蛋」。加密、签名全交给页面自己的 JS。
 *
 * 关键在地址。App 打开这个活动时用的是
 *   https://lyh.lncmcc.com/lyhVue/index.html#/bgIndex?actNo=goose2025&pc=...&pk=...
 * 其中 pc/pk 是门户后端按登录态生成的密文参数，缺了它们页面就是一片空白
 * （v2.3 的「页面文本: 空」就是这么来的，裸地址进去 DOM 根本没渲染）。
 *
 * 所以模块做两件事：
 *   1. 平时盯着 WebView，把带 pc/pk 的真实地址缓存下来，有效期 3 天；
 *   2. 跑任务时按「当前页 → 缓存地址 → 裸地址」的顺序试，谁渲染出内容就用谁。
 * 页面白屏不再直接判失败，而是换个地址重来一次，并把诊断信息写进日志。
 */
final class GooseTask {

    /** 单轮脚本最长等多久（页面等待渲染 15 秒 + 点击动作）。 */
    private static final long SCRIPT_TIMEOUT_MS = 40_000L;
    /** 发起 loadUrl 之后留一点时间给容器切页。 */
    private static final long LOAD_WAIT_MS = 2_500L;
    /** 缓存的入口地址能用多久。pc/pk 由服务端签发，放太久会被判失效。 */
    private static final long ENTRY_TTL = 24 * 3600_000L;

    private static final String PREFS = "cmcc_hook_goose";
    private static final String KEY_ENTRY = "entry_url";
    private static final String KEY_ENTRY_AT = "entry_at";

    private static volatile WeakReference<Object> webViewRef = new WeakReference<>(null);
    private static volatile String attachedUrl = "";
    private static volatile long attachedAt;

    private static volatile CountDownLatch latch;
    private static volatile String resultText = "";
    private static volatile boolean resultOk;
    private static volatile boolean resultBlank;
    private static volatile boolean resultStale;
    private static volatile String resultDetail = "";

    private GooseTask() {
    }

    /** 由 WebView 钩子在页面加载/路由变化时调用。 */
    static void attach(Object webView, String url) {
        if (webView == null || url == null) {
            return;
        }
        if (!isGoosePage(url)) {
            return;
        }
        webViewRef = new WeakReference<>(webView);
        attachedUrl = url;
        attachedAt = System.currentTimeMillis();
        rememberEntry(url);
        HookLog.logcat("goose page attached");
    }

    static boolean isGoosePage(String url) {
        if (url == null || url.isEmpty()) {
            return false;
        }
        String lower = url.toLowerCase();
        return lower.contains("actno=goose") || lower.contains("raisegoose");
    }

    // ------------------------------------------------------- 入口地址缓存

    /**
     * 记住带 pc/pk 的真实地址。
     *
     * App 每次进活动都会重新生成一套 pc/pk，所以缓存只用来「下次不用再手动进页面」，
     * 一旦服务端判失效（页面提示活动结束/重新进入），调用 {@link #forgetEntry()} 清掉。
     */
    static void rememberEntry(String url) {
        if (url == null || !isGoosePage(url)) {
            return;
        }
        if (!url.contains("pc=") || !url.contains("pk=")) {
            return;
        }
        Context ctx = HookLog.context();
        if (ctx == null) {
            return;
        }
        try {
            SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            if (url.equals(p.getString(KEY_ENTRY, ""))) {
                // 同一套参数反复刷新时间没意义，一小时刷一次就够，省得频繁写盘。
                if (System.currentTimeMillis() - p.getLong(KEY_ENTRY_AT, 0L) > 3600_000L) {
                    p.edit().putLong(KEY_ENTRY_AT, System.currentTimeMillis()).apply();
                }
                return;
            }
            p.edit().putString(KEY_ENTRY, url)
                    .putLong(KEY_ENTRY_AT, System.currentTimeMillis()).apply();
            HookLog.logcat("goose: entry cached, " + url.length() + " chars");
        } catch (Throwable e) {
            HookLog.logcat("goose: cache entry failed " + e);
        }
    }

    private static String entryUrl() {
        Context ctx = HookLog.context();
        if (ctx == null) {
            return "";
        }
        try {
            SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            String url = p.getString(KEY_ENTRY, "");
            long at = p.getLong(KEY_ENTRY_AT, 0L);
            if (url.isEmpty() || System.currentTimeMillis() - at > ENTRY_TTL) {
                return "";
            }
            return url;
        } catch (Throwable e) {
            return "";
        }
    }

    private static void forgetEntry() {
        Context ctx = HookLog.context();
        if (ctx == null) {
            return;
        }
        try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().remove(KEY_ENTRY).remove(KEY_ENTRY_AT).apply();
            HookLog.logcat("goose: cached entry dropped");
        } catch (Throwable ignored) {
        }
    }

    // --------------------------------------------------------------- 执行

    static TaskRunner.Result run() {
        StringBuilder detail = new StringBuilder();

        // 一、当前就挂着一个带登录参数的鹅蛋页：直接用它，效果最好。
        Object attached = webViewRef.get();
        long age = System.currentTimeMillis() - attachedAt;
        if (attached != null && isGoosePage(attachedUrl) && attachedUrl.contains("pc=")
                && age < 10 * 60_000L) {
            if (age < 3_000L) {
                // 刚跳转过来，页面还在加载，直接注入会打在旧文档上。
                sleep(2_500L);
            }
            HookLog.logcat("goose: reuse attached page");
            Attempt first = attempt(attached, attachedUrl);
            if (!first.blank && !first.stale) {
                return first.result;
            }
            detail.append(first.detail);
            if (first.stale) {
                forgetEntry();
            }
        }

        // 二、按候选地址逐个试：缓存地址（带 pc/pk）→ 裸地址。
        String backUrl = PageAuto.currentUrl();
        List<String> urls = new ArrayList<>();
        String cached = entryUrl();
        if (!cached.isEmpty()) {
            urls.add(cached);
        }
        urls.add(TaskApi.ACT_GOOSE);

        StringBuilder tried = new StringBuilder();
        TaskRunner.Result lastFail = null;
        boolean loadedByUs = false;
        for (int i = 0; i < urls.size(); i++) {
            String url = urls.get(i);
            if (i > 0 && url.equals(urls.get(0))) {
                continue;
            }
            tried.append("  ").append(i + 1).append(". ")
                    .append(brief(url)).append(url.contains("pc=") ? "（带登录参数）" : "（裸地址）")
                    .append('\n');
            long mark = System.currentTimeMillis();
            if (!PageAuto.load(url)) {
                detail.append("· ").append(brief(url)).append(" 加载失败\n");
                continue;
            }
            loadedByUs = true;
            waitAttached(mark);
            sleep(800L);

            Object webView = webViewRef.get();
            if (webView == null) {
                webView = PageAuto.webView();
            }
            if (webView == null) {
                detail.append("· ").append(brief(url)).append(" 没有挂上 WebView\n");
                continue;
            }
            Attempt one = attempt(webView, url);
            detail.append(one.detail);
            if (!one.blank && !one.stale) {
                if (loadedByUs && !backUrl.isEmpty() && !isGoosePage(backUrl)) {
                    // 把用户原来的页面换回去，别一直停在活动页。
                    PageAuto.load(backUrl);
                }
                return one.result;
            }
            if (one.stale) {
                forgetEntry();
            }
            lastFail = one.result;
        }

        if (loadedByUs && !backUrl.isEmpty() && !isGoosePage(backUrl)) {
            PageAuto.load(backUrl);
        }
        if (lastFail != null) {
            return lastFail;
        }
        return TaskRunner.Result.fail("收鹅蛋页面加载失败",
                "既没有可复用的活动页，也没能把页面加载到 App 的 WebView 里。\n"
                        + "试过的地址:\n" + tried + detail,
                "先在 App 里打开一次「收鹅蛋换好礼」，让模块记住登录地址，之后就能自动跑。");
    }

    /** 单轮：注入脚本、等回报。 */
    private static Attempt attempt(Object webView, String url) {
        Attempt attempt = new Attempt();
        latch = new CountDownLatch(1);
        resultText = "";
        resultDetail = "";
        resultOk = false;
        resultBlank = false;
        resultStale = false;

        String runId = String.valueOf(System.currentTimeMillis());
        if (!JsInjector.evaluateRaw(webView, buildScript(runId))) {
            attempt.result = TaskRunner.Result.fail("注入失败",
                    "evaluateJavascript 调用失败，可能是 WebView 已被回收。",
                    "重新打开一次「收鹅蛋换好礼」页面再试。");
            attempt.detail = "· " + brief(url) + " 脚本注入失败\n";
            return attempt;
        }

        boolean done;
        try {
            done = latch.await(SCRIPT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            done = false;
        }
        attempt.blank = resultBlank;
        attempt.stale = resultStale;
        attempt.detail = "· " + brief(url) + "\n"
                + indent(resultDetail.isEmpty() ? "（无回报）" : resultDetail);
        if (!done) {
            attempt.result = TaskRunner.Result.fail("收鹅蛋超时",
                    "脚本已注入，但 40 秒内没有收到页面回报。\n页面: " + url,
                    "页面可能被切到后台了。保持活动页在前台再点一次。");
            return attempt;
        }
        if (resultOk) {
            attempt.result = TaskRunner.Result.ok(resultText, resultDetail, resultText);
        } else {
            attempt.result = TaskRunner.Result.fail(resultText, resultDetail,
                    "页面上没找到可点的按钮，可能改版了。把这段日志发我。");
        }
        return attempt;
    }

    /** JsBridge 回调。 */
    static void onJsResult(String payload) {
        try {
            JSONObject json = new JSONObject(payload);
            resultOk = json.optBoolean("ok", false);
            resultBlank = json.optBoolean("blank", false);
            resultStale = json.optBoolean("stale", false);
            resultText = json.optString("text", "");
            StringBuilder detail = new StringBuilder();
            detail.append("页面: ").append(json.optString("page", attachedUrl)).append('\n');
            JSONArray log = json.optJSONArray("log");
            if (log != null) {
                detail.append("--- 步骤 ---\n");
                for (int i = 0; i < log.length(); i++) {
                    detail.append("· ").append(log.optString(i)).append('\n');
                }
            }
            resultDetail = detail.toString();
        } catch (Throwable e) {
            resultOk = false;
            resultBlank = false;
            resultText = "结果解析失败：" + e;
            resultDetail = payload;
        }
        CountDownLatch waiting = latch;
        if (waiting != null) {
            waiting.countDown();
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }

    /**
     * 等页面真正切过去再注入。
     *
     * 固定 sleep 有个坑：WebView 还在加载旧文档时注入，脚本跑在旧页面上，
     * 白等 15 秒才发现「页面白屏」。这里盯 attachedAt，页面一换过来就继续。
     */
    private static void waitAttached(long mark) {
        long end = mark + LOAD_WAIT_MS * 3;
        while (System.currentTimeMillis() < end && attachedAt < mark) {
            sleep(300L);
        }
    }

    private static String brief(String url) {
        if (url == null) {
            return "";
        }
        int cut = url.indexOf("pc=");
        String head = cut > 0 ? url.substring(0, cut) + "pc=…" : url;
        return head.length() <= 90 ? head : head.substring(0, 90) + "…";
    }

    private static String indent(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        return "  " + text.replace("\n", "\n  ") + "\n";
    }

    private static final class Attempt {
        TaskRunner.Result result;
        String detail = "";
        boolean blank;
        boolean stale;
    }

    /**
     * 注入脚本：先等页面真正渲染出来，再按语义找按钮串行点。
     *
     * 只碰四类动作，都是「点了只会多不会少」的：打卡 / 领饲料 / 投喂 / 收蛋。
     * 兑换、分享、跳转一律不点。
     *
     * 页面 15 秒还没文字就回报 blank，宿主换个地址重来 —— 白屏基本等于 pc/pk 失效，
     * 死等下去没有意义。
     */
    private static String buildScript(String runId) {
        return "(function(){"
                + "var RUN='" + runId + "';"
                + "if(window['__qoder_goose_'+RUN]){return;}"
                + "window['__qoder_goose_'+RUN]=true;"
                + "var B='" + JsInjector.BRIDGE_NAME + "';"
                + "var log=[];var hitCount=0;var sent=false;"
                + "function send(ok,text,blank,stale){"
                + "  if(sent){return;}sent=true;"
                + "  try{var br=window[B];"
                + "    if(br&&typeof br.goose==='function'){"
                + "      br.goose(JSON.stringify({ok:ok,text:text,blank:!!blank,stale:!!stale,"
                + "log:log,page:location.href}));"
                + "    }}catch(e){}"
                + "}"
                + "function txt(el){return (el.innerText||el.textContent||'').replace(/\\s+/g,'').trim();}"
                + "function shown(el){"
                + "  try{var s=getComputedStyle(el);"
                + "    if(s.display==='none'||s.visibility==='hidden'||s.opacity==='0'){return false;}"
                + "    var r=el.getBoundingClientRect();"
                + "    return r.width>2&&r.height>2;}catch(e){return false;}"
                + "}"
                + "function visibleText(el){"
                + "  var t=txt(el);"
                + "  if(!t){return '';}"
                + "  var kids=el.children||[];"
                + "  for(var i=0;i<kids.length;i++){"
                + "    if(shown(kids[i])){"
                + "      var kt=txt(kids[i]);"
                + "      if(kt===t){return '';}"
                + "    }"
                + "  }"
                + "  return t;"
                + "}"
                + "var BAD=['兑换','分享','邀请','充值','购买','开通','订购','退订','支付','确认','取消','关闭','规则','明细','记录','去完成','查看','更多','立即兑换','去逛逛'];"
                + "function bad(t){for(var i=0;i<BAD.length;i++){if(t.indexOf(BAD[i])>=0){return true;}}return false;}"
                // 页面可能把内容放在同源 iframe 里，两个文档都查一遍。
                + "function docs(){"
                + "  var out=[document];"
                + "  var fs=document.querySelectorAll('iframe');"
                + "  for(var i=0;i<fs.length&&i<5;i++){"
                + "    try{var d=fs[i].contentDocument;if(d&&d.body){out.push(d);}}catch(e){}"
                + "  }"
                + "  return out;"
                + "}"
                + "function findAll(sel){"
                + "  var ds=docs(),out=[];"
                + "  for(var i=0;i<ds.length;i++){"
                + "    try{var n=ds[i].querySelectorAll(sel);"
                + "      for(var j=0;j<n.length;j++){out.push(n[j]);}}catch(e){}"
                + "  }"
                + "  return out;"
                + "}"
                + "function find(words,maxLen){"
                + "  var all=findAll('button,a,div,span,p,li,i,view,text');"
                + "  var best=null,bestLen=999;"
                + "  for(var i=0;i<all.length;i++){"
                + "    var el=all[i];"
                + "    if(!shown(el)||el.__qoderHit){continue;}"
                + "    var t=visibleText(el);"
                + "    if(!t||t.length>(maxLen||8)||bad(t)){continue;}"
                + "    for(var j=0;j<words.length;j++){"
                + "      if(t===words[j]&&t.length<bestLen){best=el;bestLen=t.length;}"
                + "    }"
                + "  }"
                + "  if(best){return best;}"
                + "  for(var k=0;k<all.length;k++){"
                + "    var e2=all[k];"
                + "    if(!shown(e2)||e2.__qoderHit){continue;}"
                + "    var t2=visibleText(e2);"
                + "    if(!t2||t2.length>(maxLen||8)||bad(t2)){continue;}"
                + "    for(var m=0;m<words.length;m++){"
                + "      if(t2.indexOf(words[m])>=0&&t2.length<bestLen){best=e2;bestLen=t2.length;}"
                + "    }"
                + "  }"
                + "  return best;"
                + "}"
                + "function hit(el){"
                + "  if(!el){return false;}"
                + "  el.__qoderHit=1;"
                + "  try{el.scrollIntoView({block:'center'});}catch(e){}"
                + "  try{var r=el.getBoundingClientRect();"
                + "    var x=r.left+r.width/2,y=r.top+r.height/2;"
                + "    ['touchstart','touchend','mousedown','mouseup','click'].forEach(function(t){"
                + "      var ev;"
                + "      try{"
                + "        if(t.indexOf('touch')===0){ev=new TouchEvent(t,{bubbles:true,cancelable:true});}"
                + "        else{ev=new MouseEvent(t,{bubbles:true,cancelable:true,clientX:x,clientY:y});}"
                + "        el.dispatchEvent(ev);"
                + "      }catch(e){}"
                + "    });"
                + "    if(typeof el.click==='function'){el.click();}"
                + "    return true;"
                + "  }catch(e){return false;}"
                + "}"
                + "function dump(){"
                + "  var all=findAll('button,a,div,span,p,li,i,view,text,img');"
                + "  var out=[];"
                + "  for(var i=0;i<all.length&&out.length<30;i++){"
                + "    var el=all[i];"
                + "    if(!shown(el)){continue;}"
                + "    var t=txt(el);"
                + "    if(!t||t.length>12){continue;}"
                + "    if(out.indexOf(t)>=0){continue;}"
                + "    out.push(t);"
                + "  }"
                + "  return out.join('/');"
                + "}"
                + "function pageText(){"
                + "  var ds=docs(),out='';"
                + "  for(var i=0;i<ds.length;i++){"
                + "    try{out+=(ds[i].body&&ds[i].body.innerText||'');}catch(e){}"
                + "  }"
                + "  return out.replace(/\\s+/g,'');"
                + "}"
                + "function diag(){"
                + "  var t=pageText();"
                + "  log.push('链接: '+location.href.slice(0,120));"
                + "  log.push('标题: '+(document.title||'（空）'));"
                + "  log.push('文本: '+t.length+' 字');"
                + "  log.push('节点: '+document.querySelectorAll('*').length);"
                + "  log.push('iframe: '+document.querySelectorAll('iframe').length);"
                + "  log.push('脚本: '+document.scripts.length);"
                + "  if(t){log.push('开头: '+t.slice(0,60));}"
                + "}"
                + "function finish(){"
                + "  var ok=hitCount>0;"
                + "  var text=ok?('页面内已执行 '+hitCount+' 个动作'):'页面上没有可执行的按钮';"
                + "  log.push('结束: 共点击 '+hitCount+' 次');"
                + "  try{log.push('页面文本: '+dump());}catch(e){}"
                + "  var all=pageText();"
                + "  var stale=/已结束|活动结束|已下线|已过期|已失效|重新进入|请重新登录/.test(all);"
                + "  if(stale&&!ok){log.push('判定: 页面提示活动失效，需要换地址');}"
                + "  send(ok,text,false,stale);"
                + "}"
                + "var steps=["
                + "  {name:'打卡',words:['打卡','立即打卡','签到','立即签到'],max:6},"
                + "  {name:'领饲料',words:['领取','领饲料','收下','立即领取'],max:6},"
                + "  {name:'投喂',words:['投喂','喂养','立即投喂'],max:6},"
                + "  {name:'收蛋',words:['收蛋','收取','收鹅蛋','一键收取','收获'],max:6}"
                + "];"
                + "var i=0;"
                + "function phase1(){"
                + "  if(i>=steps.length){phase2();return;}"
                + "  var s=steps[i++];"
                + "  var el=find(s.words,s.max);"
                + "  if(el&&hit(el)){hitCount++;log.push(s.name+': 已点击 ('+visibleText(el).slice(0,10)+')');}"
                + "  else{log.push(s.name+': 未找到按钮');}"
                + "  setTimeout(phase1,1200);"
                + "}"
                + "var extra=0;"
                + "var CLAIM=['领取','领奖励','领取奖励','去领取','领饲料','收下','一键领取','全部领取','收蛋','收取','收获'];"
                + "function phase2(){"
                + "  if(extra>=8){finish();return;}"
                + "  var el=find(CLAIM,8);"
                + "  if(!el){finish();return;}"
                + "  var label=visibleText(el).slice(0,10);"
                + "  if(hit(el)){hitCount++;extra++;log.push('补充领取: '+label);}"
                + "  setTimeout(phase2,1300);"
                + "}"
                + "var waited=0;"
                + "function waitReady(){"
                + "  var t='';"
                + "  try{t=pageText();}catch(e){}"
                + "  if(t.length>=8){"
                + "    log.push('页面已渲染: '+t.length+' 字');"
                + "    try{window.scrollTo(0,document.body.scrollHeight);}catch(e){}"
                + "    setTimeout(function(){"
                + "      try{window.scrollTo(0,0);}catch(e){}"
                + "      phase1();"
                + "    },900);"
                + "    return;"
                + "  }"
                + "  waited+=500;"
                + "  if(waited>=15000){"
                + "    log.push('页面 15 秒内没有渲染出任何文字');"
                + "    try{diag();}catch(e){}"
                + "    send(false,'页面白屏（缺少登录参数 pc/pk）',true,false);"
                + "    return;"
                + "  }"
                + "  setTimeout(waitReady,500);"
                + "}"
                + "setTimeout(waitReady,300);"
                + "})();";
    }
}
