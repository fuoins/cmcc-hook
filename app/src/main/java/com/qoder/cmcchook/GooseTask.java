package com.qoder.cmcchook;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.ref.WeakReference;
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
 * 所以这里换一种做法：不自己发请求，而是等用户打开活动页后，
 * 往那个 WebView 里注入一段脚本，让页面自己去点「打卡 / 领饲料 / 投喂 / 收蛋」。
 * 加密、签名全交给页面自己的 JS，模块只负责触发和收结果。
 */
final class GooseTask {

    private static final long TIMEOUT_MS = 42_000L;

    private static volatile WeakReference<Object> webViewRef = new WeakReference<>(null);
    private static volatile String attachedUrl = "";
    private static volatile long attachedAt;

    private static volatile CountDownLatch latch;
    private static volatile String resultText = "";
    private static volatile boolean resultOk;
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
        HookLog.logcat("goose page attached");
    }

    static boolean isGoosePage(String url) {
        if (url == null || url.isEmpty()) {
            return false;
        }
        String lower = url.toLowerCase();
        return lower.contains("actno=goose") || lower.contains("raisegoose");
    }

    static TaskRunner.Result run() {
        Object webView = webViewRef.get();
        long age = System.currentTimeMillis() - attachedAt;
        if (webView == null || age > 10 * 60_000L) {
            return TaskRunner.Result.fail("收鹅蛋需要在活动页里执行",
                    "没有找到最近打开的收鹅蛋页面（10 分钟内）。\n"
                            + "这个活动是国密加密 + 瑞数签名，模块没法在后台直连，"
                            + "只能在页面里让页面自己去点。",
                    "先在 App 里打开「收鹅蛋换好礼」，停在活动页，再回来点一次。");
        }

        latch = new CountDownLatch(1);
        resultText = "";
        resultDetail = "";
        resultOk = false;

        String runId = String.valueOf(System.currentTimeMillis());
        boolean injected = JsInjector.evaluateRaw(webView, buildScript(runId));
        if (!injected) {
            return TaskRunner.Result.fail("注入失败",
                    "evaluateJavascript 调用失败，可能是 WebView 已被回收。",
                    "重新打开一次「收鹅蛋换好礼」页面再试。");
        }

        boolean done;
        try {
            done = latch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            done = false;
        }
        if (!done) {
            return TaskRunner.Result.fail("收鹅蛋超时",
                    "脚本已注入，但 42 秒内没有收到页面回报。\n页面: " + attachedUrl,
                    "页面可能被切到后台了。保持活动页在前台再点一次。");
        }
        if (resultOk) {
            return TaskRunner.Result.ok(resultText, resultDetail, resultText);
        }
        return TaskRunner.Result.fail(resultText, resultDetail,
                "页面上没找到可点的按钮，可能改版了。把这段日志发我。");
    }

    /** JsBridge 回调。 */
    static void onJsResult(String payload) {
        try {
            JSONObject json = new JSONObject(payload);
            resultOk = json.optBoolean("ok", false);
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
            resultText = "结果解析失败：" + e;
            resultDetail = payload;
        }
        CountDownLatch waiting = latch;
        if (waiting != null) {
            waiting.countDown();
        }
    }

    /**
     * 注入脚本：按语义找按钮，串行点四下。
     *
     * 只碰四类动作，都是「点了只会多不会少」的：
     *   打卡 / 领饲料 / 投喂 / 收蛋
     * 兑换、分享、跳转一律不点。
     */
    private static String buildScript(String runId) {
        return "(function(){"
                + "var RUN='" + runId + "';"
                + "if(window['__qoder_goose_'+RUN]){return;}"
                + "window['__qoder_goose_'+RUN]=true;"
                + "var B='" + JsInjector.BRIDGE_NAME + "';"
                + "var log=[];var hitCount=0;var clicked=[];"
                + "function send(ok,text){"
                + "  try{var br=window[B];"
                + "    if(br&&typeof br.goose==='function'){"
                + "      br.goose(JSON.stringify({ok:ok,text:text,log:log,page:location.href}));"
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
                + "function find(words,maxLen){"
                + "  var all=document.querySelectorAll('button,a,div,span,p,li,i,view,text');"
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
                + "function finish(){"
                + "  var ok=hitCount>0;"
                + "  var text=ok?('页面内已执行 '+hitCount+' 个动作'):'页面上没有可执行的按钮';"
                + "  log.push('结束: 共点击 '+hitCount+' 次');"
                + "  send(ok,text);"
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
                + "try{window.scrollTo(0,document.body.scrollHeight);}catch(e){}"
                + "setTimeout(function(){try{window.scrollTo(0,0);}catch(e){};phase1();},800);"
                + "})();";
    }
}
