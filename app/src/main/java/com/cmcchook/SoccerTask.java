package com.cmcchook;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 球赛狂欢季（辽宁 lyh.lncmcc.com，页面 #/footballGuess?actNo=AC2026DBCQUEZQ1G）。
 *
 * <p>11-06 A/B 两份人工日志实证的接口链，全部在瑞数 WAF + AES 域里：
 * <ol>
 *   <li>POST footballQuiz/taskIndex → 竞猜任务列表</li>
 *   <li>POST footballQuiz/doTask → 逐个完成浏览任务（日志里 5 次）</li>
 *   <li>POST greenSoccer2026/invite/taskInfo → 邀请任务</li>
 *   <li>POST greenSoccer2026/vote → 用竞猜票投注（要选队伍和倍数）</li>
 * </ol>
 *
 * <p>请求体是 {@code sinoEnParams} 密文、响应是 {@code dataEn} 密文，
 * 加密和 WAF 令牌只在页面内存里生成，Java 层拼不出来（和收鹅蛋同一套机制）。
 * 所以这里走「离屏页 + 页面内调用」：优先调页面组件自己的方法
 * （taskIndex / doTask，由页面生成加密参数并发请求），拿不到组件方法时
 * 再退到页面 webpack 业务接口层直发。
 *
 * <p><b>不代投票</b>：vote 要选哪支队、押几倍，属于用户判断，
 * 模块只把竞猜票拿到手，投注留给用户自己。
 */
final class SoccerTask {

    private static final String PREFS = "cmcc_hook_soccer";
    private static final String KEY_ENTRY = "entry_url";
    private static final String KEY_ENTRY_AT = "entry_at";
    /** pc/pk 由 App 每次进页面重新生成，缓存只保证「下次不用再手动进」。 */
    private static final long ENTRY_TTL = 24L * 3600_000L;

    static final String ACT_SOCCER =
            "https://lyh.lncmcc.com/lyhVue/index.html#/footballGuess?actNo=AC2026DBCQUEZQ1G";

    private SoccerTask() {
    }

    static boolean isSoccerPage(String url) {
        if (url == null) {
            return false;
        }
        String lower = url.toLowerCase();
        return lower.contains("footballguess") || lower.contains("greensoccer");
    }

    /**
     * 记住带 pc/pk 的真实地址。
     *
     * <p>和收鹅蛋一样：App 每次进活动都会新生成一套 pc/pk，模块造不出来；
     * 用户手动进一次页面，这里把带参数的地址存下来，之后自动跑直接复用。
     */
    static void rememberEntry(String url) {
        if (!isSoccerPage(url) || !url.contains("pc=") || !url.contains("pk=")) {
            return;
        }
        Context ctx = HookLog.context();
        if (ctx == null) {
            return;
        }
        try {
            SharedPreferences p = AccountScope.prefs(ctx, PREFS);
            if (url.equals(p.getString(KEY_ENTRY, ""))) {
                if (System.currentTimeMillis() - p.getLong(KEY_ENTRY_AT, 0L) > 3600_000L) {
                    p.edit().putLong(KEY_ENTRY_AT, System.currentTimeMillis()).apply();
                }
                return;
            }
            p.edit().putString(KEY_ENTRY, url)
                    .putLong(KEY_ENTRY_AT, System.currentTimeMillis()).apply();
            HookLog.logcat("soccer: entry cached, " + url.length() + " chars");
        } catch (Throwable e) {
            HookLog.logcat("soccer: cache entry failed " + e);
        }
    }

    static String entryUrl() {
        Context ctx = HookLog.context();
        if (ctx == null) {
            return ACT_SOCCER;
        }
        try {
            SharedPreferences p = AccountScope.prefs(ctx, PREFS);
            String url = p.getString(KEY_ENTRY, "");
            long at = p.getLong(KEY_ENTRY_AT, 0L);
            if (url.isEmpty() || System.currentTimeMillis() - at > ENTRY_TTL) {
                return ACT_SOCCER;
            }
            return url;
        } catch (Throwable e) {
            return ACT_SOCCER;
        }
    }

    private static void forgetEntry() {
        Context ctx = HookLog.context();
        if (ctx == null) {
            return;
        }
        try {
            AccountScope.prefs(ctx, PREFS)
                    .edit().remove(KEY_ENTRY).remove(KEY_ENTRY_AT).apply();
        } catch (Throwable ignored) {
        }
    }

    // --------------------------------------------------------------- 执行

    static TaskRunner.Result run() {
        StringBuilder detail = new StringBuilder();
        String entry = entryUrl();
        WebPage page = WebSession.current();
        detail.append("入口: ").append(brief(entry)).append('\n');
        if (page == null || page.raw() == null) {
            return TaskRunner.Result.fail("球赛活动页没起来",
                    detail.toString(),
                    "离屏 WebView 没能加载球赛页面。重开一次中国移动 App 再试。");
        }
        // 没有 pc/pk 就没有登录态：这一轮不算失败，也不进当天失败次数。
        if (!entry.contains("pc=")) {
            return TaskRunner.Result.skip("球赛狂欢季：还没有登录参数（pc/pk）",
                    detail.toString(),
                    "在 App 里手动点一次「球赛狂欢季」，模块会把带 pc/pk 的入口记下来，"
                            + "之后就能自动跑。");
        }

        // r14：先看页面是不是「本省不支持」。球赛是辽宁 lyh 域的活动，外省号
        // 进去看到的是一张「活动未开放」的空页，脚本只会报「组件没挂载」，
        // 既看不出原因，还会被当成失败拉低一键成功率。这类明确的地域限制
        // 应报 skip（不算失败、不计次数），并在日志里写清楚。
        String pageText = page.eval(
                "(function(){try{return (document.body?document.body.innerText:'').replace(/\\s+/g,' ').slice(0,600)}catch(e){return ''}})()",
                4_000L);
        if (provinceBlocked(pageText)) {
            detail.append("页面: ").append(brief(pageText)).append('\n');
            return TaskRunner.Result.skip("球赛狂欢季 · 本省不支持",
                    detail.append("判定: 页面文案命中地域限制（本省/地区 + 不支持/未开放）\n").toString(),
                    "这是辽宁域的活动，当前号码所在省份没开放。换辽宁号再做，或忽略这项。");
        }

        page.eval(SOCCER_SCRIPT, 8_000L);
        String raw = null;
        int drifted = 0;
        long deadline = System.currentTimeMillis() + 90_000L;
        while (System.currentTimeMillis() < deadline) {
            // 一次拿两样：脚本结果 + 当前真实地址。
            // 页面被「下载 App」这类引导顶走后，原页的 JS 上下文当场销毁，
            // __cmcc_soccer 的写入也就没了 —— 只看变量会一直空等到 90 秒。
            String probe = page.eval(
                    "(function(){var v=window.__cmcc_soccer?String(window.__cmcc_soccer):'';"
                            + "return v+'\\u0001'+String(location.href||'');})()", 6_000L);
            if (probe != null && !probe.isEmpty()) {
                int cut = probe.indexOf('\u0001');
                String body = cut < 0 ? probe : probe.substring(0, cut);
                String here = cut < 0 ? "" : probe.substring(cut + 1).trim();
                if (!body.isEmpty()) {
                    raw = body;
                    break;
                }
                if (!here.isEmpty() && !here.contains("footballGuess") && drifted < 1) {
                    drifted++;
                    detail.append("页面被顶走: ").append(brief(here)).append(" → 拉回活动页\n");
                    HookLog.log("TASK", "球赛页被外部跳转顶走 · 已拉回",
                            "当前: " + brief(here));
                    try {
                        page.eval("(function(){location.replace(" + JSONObject.quote(entry)
                                + ")})()", 3_000L);
                        sleep(3_000L);
                        page.eval(SOCCER_SCRIPT, 8_000L);
                    } catch (Throwable e) {
                        detail.append("拉回失败: ").append(e).append('\n');
                    }
                    deadline = System.currentTimeMillis() + 60_000L;
                }
            }
            sleep(600L);
        }
        if (raw == null) {
            return TaskRunner.Result.fail("球赛页面脚本超时",
                    detail.append("页面组件脚本 90 秒没有回报")
                            .append(drifted > 0 ? "（中途被顶走过，已拉回重跑）" : "")
                            .append('\n').toString(),
                    "页面能打开但脚本没回报。把这段日志发我。");
        }

        JSONObject out;
        try {
            out = new JSONObject(raw);
        } catch (Throwable e) {
            return TaskRunner.Result.fail("球赛结果解析失败",
                    detail.append("回报: ").append(brief(raw)).toString(), "");
        }

        // 页面内的执行明细按 4000 字符分片落日志，完整日志里能看到每一步。
        JSONArray lines = out.optJSONArray("lines");
        writeLines(lines);

        String stage = out.optString("stage", "");
        if ("no-vue".equals(stage)) {
            forgetEntry();
            return TaskRunner.Result.skip("球赛：页面组件没挂载",
                    detail.append("页面没有 __vue__ 根组件，可能被重定向或还没渲染完\n").toString(),
                    "在 App 里手动点进一次球赛页面，再回来执行任务。");
        }
        if ("error".equals(stage)) {
            return TaskRunner.Result.fail("球赛脚本异常",
                    detail.append(out.optString("msg", "")).toString(),
                    "把日志发我。");
        }

        int total = out.optInt("total", 0);
        int undo = out.optInt("undo", 0);
        int done = out.optInt("done", 0);
        int left = out.optInt("left", 0);
        String mode = out.optString("mode", "");
        String actNo = out.optString("actNo", "");

        detail.append("actNo: ").append(actNo).append('\n');
        detail.append("方式: ").append(mode.isEmpty() ? "页面组件" : mode).append('\n');
        detail.append("竞猜任务: 共 ").append(total).append(" 个，待做 ").append(undo)
                .append("，完成 ").append(done).append("，复查仍待做 ").append(left).append('\n');

        JSONObject vote = out.optJSONObject("vote");
        int voteOk = vote == null ? 0 : vote.optInt("ok", 0);
        int voteTry = vote == null ? 0 : vote.optInt("try", 0);
        String voteSkip = vote == null ? "" : vote.optString("skip", "");
        String voteErr = vote == null ? "" : vote.optString("err", "");
        String voteTeam = vote == null ? "" : vote.optString("team", "");
        String votePct = vote == null ? "" : vote.optString("pct", "");
        String voteCost = vote == null ? "" : vote.optString("cost", "");
        String voteMatch = vote == null ? "" : vote.optString("match", "");
        if (vote != null) {
            StringBuilder vb = new StringBuilder("竞猜: ");
            if (voteOk > 0) {
                vb.append("已投");
            } else if (!voteSkip.isEmpty()) {
                vb.append(voteSkip);
            } else {
                vb.append("没投成");
            }
            if (!voteTeam.isEmpty()) {
                vb.append(" · ").append(voteMatch).append(' ').append(voteTeam)
                        .append(' ').append(votePct).append("% 用户选");
            }
            if (!voteCost.isEmpty()) {
                vb.append(" · 消耗 ").append(voteCost).append(" 张");
            }
            if (!voteErr.isEmpty()) {
                vb.append(" · ").append(voteErr);
            }
            detail.append(vb).append('\n');
        }

        String summary;
        // 竞猜投出去了就是真结果，优先于「今天没有待做任务」。
        if (voteOk > 0 || (done > 0 && left < undo)) {
            StringBuilder sb = new StringBuilder("球赛狂欢季 · ");
            boolean wrote = false;
            if (done > 0) {
                sb.append("完成 ").append(done).append(" 个竞猜任务");
                wrote = true;
                if (left > 0) {
                    sb.append("，还剩 ").append(left).append(" 个");
                }
            }
            if (voteOk > 0) {
                if (wrote) {
                    sb.append("；");
                }
                sb.append("竞猜 ").append(voteTeam.isEmpty() ? "已投" : voteTeam);
                if (!votePct.isEmpty()) {
                    sb.append("（").append(votePct).append("% 用户选）");
                }
                if (!voteCost.isEmpty()) {
                    sb.append(" ").append(voteCost).append(" 张票");
                }
            }
            summary = sb.toString();
            return TaskRunner.Result.ok(summary, detail.toString(), summary);
        }
        if (voteTry > 0) {
            return TaskRunner.Result.fail("球赛狂欢季 · 竞猜点了没成功",
                    detail.toString(),
                    "确认弹窗点了但没看到 vote 回执。把完整日志发我，我按页面回执改。");
        }
        if (undo == 0) {
            // v3.66：0 项任务和「任务都做完了」是两件事。11-08 08:33 页面改版后
            // 扫不到任务数组，以前直接报「今天没有待做任务」并打勾，属于假成功。
            if (total == 0) {
                summary = "球赛狂欢季 · 页面没列出任务项";
                return TaskRunner.Result.skip(summary, detail.toString(),
                        "页面里没扫到任务数组（可能是改版，也可能是今天确实没任务）。"
                                + "请在 App 里手动点开球赛页看一眼：有「去完成/去邀请」按钮就把完整日志发我；"
                                + "没有就说明今天没任务，不用再跑。");
            }
            summary = "球赛狂欢季 · 今天没有待做任务"
                    + (voteSkip.isEmpty() ? "" : "（" + voteSkip + "）");
            return TaskRunner.Result.idle(summary, detail.toString(), summary);
        }
        summary = "球赛狂欢季 · " + undo + " 个待做任务没做成";
        return TaskRunner.Result.fail(summary, detail.toString(),
                "球赛页面接口是加密的，只能借页面自己做。把完整日志发我，我按页面回报的方法清单改。");
    }

    private static void writeLines(JSONArray lines) {
        if (lines == null || lines.length() == 0) {
            return;
        }
        StringBuilder chunk = new StringBuilder();
        int part = 1;
        for (int i = 0; i < lines.length(); i++) {
            String one = lines.optString(i, "");
            if (chunk.length() + one.length() + 1 > 4000) {
                HookLog.log("SOCCER", "球赛页面执行明细 " + part, chunk.toString());
                chunk.setLength(0);
                part++;
            }
            chunk.append(one).append('\n');
        }
        if (chunk.length() > 0) {
            HookLog.log("SOCCER", "球赛页面执行明细 " + part, chunk.toString());
        }
    }

    private static String brief(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= 180 ? text : text.substring(0, 180) + "…";
    }

    /**
     * 页面文案是否明确写了「本省/本地区不支持」。
     *
     * <p>必须同时出现「地域词」和「限制词」，避免活动规则里单独一句
     * 「不支持退换」把正常页面误判成地域限制。
     */
    private static boolean provinceBlocked(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        boolean geo = text.contains("本省") || text.contains("省份") || text.contains("本地区")
                || text.contains("地区") || text.contains("区域") || text.contains("归属地");
        boolean deny = text.contains("不支持") || text.contains("未开放") || text.contains("暂未")
                || text.contains("不在活动范围") || text.contains("非活动") || text.contains("敬请期待");
        return geo && deny;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }

    /**
     * 页面内脚本：先调组件方法，再退 webpack 业务接口层。
     *
     * <p>全程不点 DOM、不扫按钮文案。只调用页面真实存在的方法，
     * 或者页面自己的接口封装函数（加密与 WAF 令牌由页面拦截器补）。
     */
    private static final String SOCCER_SCRIPT = """
            (function(){
              var ID='__cmcc_soccer';
              window[ID]='';
              var L=[];
              function line(s){ L.push(String(s)) }
              function cut(s,n){ s=String(s==null?'':s); return s.length>n?s.slice(0,n)+'…':s }
              function isFn(x){ return typeof x==='function' }
              function wait(ms){ return new Promise(function(r){ setTimeout(r,ms) }) }
              function done(o){ try{ o.lines=L; window[ID]=JSON.stringify(o) }catch(e){ window[ID]='{"ok":false,"stage":"crash","lines":[]}' } }

              function walk(v,d,out){
                if(!v||d>8) return;
                out.push(v);
                var cs=[]; try{ cs=v.$children||[] }catch(e){}
                for(var i=0;i<cs.length;i++) walk(cs[i],d+1,out);
              }
              function mnames(c){
                var out=[];
                try{
                  var m=c.$options&&c.$options.methods;
                  if(m){ for(var k in m){ if(isFn(m[k]) && out.indexOf(k)<0) out.push(k) } }
                }catch(e){}
                try{
                  var own=Object.getOwnPropertyNames(c);
                  for(var i=0;i<own.length;i++){
                    var k=own[i];
                    if(k.charAt(0)==='$'||k.charAt(0)==='_'||k==='constructor') continue;
                    var ok=false; try{ ok=isFn(c[k]) }catch(e){}
                    if(ok && out.indexOf(k)<0) out.push(k);
                  }
                }catch(e){}
                return out;
              }
              function arrays(c){
                var out=[];
                try{
                  var d=c._data||c.$data||{};
                  for(var k in d){
                    var v=d[k];
                    if(Object.prototype.toString.call(v)!=='[object Array]' || !v.length) continue;
                    if(!v[0] || typeof v[0]!=='object') continue;
                    var keys=[]; try{ keys=Object.keys(v[0]) }catch(e){}
                    out.push({key:k, arr:v, keys:keys});
                  }
                }catch(e){}
                return out;
              }
              function stageOf(t){ return String((t&&(t.taskStage||t.stage||t.status))||'') }

              function btext(){
                try{ return (document.body?document.body.innerText:'').replace(/\\s+/g,' ').trim() }catch(e){ return '' }
              }
              function vok(e){
                try{
                  var r=e.getBoundingClientRect();
                  if(!r||r.width<2||r.height<2) return false;
                  var s=getComputedStyle(e);
                  return s.display!=='none' && s.visibility!=='hidden' && s.opacity!=='0';
                }catch(x){ return false }
              }
              function cok(e){
                try{
                  var tag=(e.tagName||'').toUpperCase();
                  if(tag==='BUTTON'||tag==='A') return true;
                  var cls=String(e.className||'')+' '+(e.id||'');
                  if(/btn|button|click|team|guess|option|item|confirm/i.test(cls)) return true;
                  if(getComputedStyle(e).cursor==='pointer') return true;
                }catch(x){}
                return false;
              }
              // 只点页面上真实存在的按钮，不造请求。
              function clickAny(words,max){
                var all=document.querySelectorAll('button,a,div,span');
                var n=0;
                for(var i=0;i<all.length && n<max;i++){
                  var e=all[i];
                  if(!vok(e)||!cok(e)) continue;
                  var t=(e.innerText||'').replace(/\\s+/g,' ').trim();
                  if(!t||t.length>8) continue;
                  for(var j=0;j<words.length;j++){
                    if(t!==words[j]) continue;
                    try{ e.click(); n++; return t }catch(x){}
                  }
                }
                return '';
              }
              // 页面上「XX队胜 N%用户选」的两个选项。
              // 同一选项会被父子多层命中，按队名只留文本最短的那层。
              function teamOptions(){
                var all=document.querySelectorAll('div,span,button,a,li');
                var raw=[];
                for(var i=0;i<all.length;i++){
                  var e=all[i];
                  // 这里只读比例，不要求元素本身带 btn 类：
                  // 真按钮常常是外层 div 绑 @click，内层 span 只放文案。
                  if(!vok(e)) continue;
                  var t=(e.innerText||'').replace(/\\s+/g,' ').trim();
                  if(t.length<3||t.length>18) continue;
                  var pm=/(\\d{1,3})%\\s*用户选/.exec(t);
                  if(!pm) continue;
                  var nm=/([^\\s%]{2,6}队)/.exec(t);
                  if(!nm) continue;
                  raw.push({el:e,name:nm[1],pct:parseInt(pm[1],10),len:t.length});
                }
                var best={};
                for(var j=0;j<raw.length;j++){
                  var o=raw[j];
                  if(!best[o.name]||o.len<best[o.name].len) best[o.name]=o;
                }
                var arr=[];
                for(var k in best) arr.push(best[k]);
                return arr;
              }

              (async function(){
                try{
                  line('=== 球赛狂欢季 v3.37（含自动竞猜）===');
                  line('URL: '+cut(location.href,200));
                  var am=/(?:actNo|actno)=([A-Za-z0-9]+)/.exec(location.href);
                  var actNo=am?am[1]:'';
                  line('actNo: '+actNo);

                  var root=null;
                  for(var i=0;i<50;i++){
                    try{ var el=document.querySelector('#app'); root=el&&el.__vue__ }catch(e){}
                    if(root) break;
                    await wait(400);
                  }
                  if(!root){ done({ok:false,stage:'no-vue',actNo:actNo}); return }

                  var comps=[]; walk(root,0,comps);
                  line('组件: '+comps.length+' 个');

                  for(var i=0;i<comps.length;i++){
                    var ms=mnames(comps[i]), keep=[];
                    for(var j=0;j<ms.length;j++){ if(/task|quiz|vote|guess|do/i.test(ms[j])) keep.push(ms[j]) }
                    if(keep.length){
                      var nm='?';
                      try{ nm=(comps[i].$options&&(comps[i].$options.name||comps[i].$options.__name))||'?' }catch(e){}
                      line('  ['+nm+'] '+cut(keep.join(','),400));
                    }
                  }

                  var owner=null, field='';
                  function scanTasks(){
                    owner=null; field='';
                    for(var i=0;i<comps.length;i++){
                      var as=arrays(comps[i]);
                      for(var j=0;j<as.length;j++){
                        var keys=as[j].keys.join(',');
                        if(keys.indexOf('taskStage')>=0 || keys.indexOf('taskId')>=0
                           || keys.indexOf('taskName')>=0){
                          owner=comps[i]; field=as[j].key;
                          line('任务数组: '+field+' 共 '+as[j].arr.length+' 项');
                          return true;
                        }
                      }
                    }
                    return false;
                  }
                  scanTasks();

                  // v3.66：11-08 08:33 的页面只剩 GuessTicket.buildInviteTask /
                  // fetchInviteTask，没有旧版的 taskIndex，于是扫出「0 项任务」被当成做完。
                  // 这里把任务刷新方法放宽，并把回执里像任务数组的东西直接捞出来。
                  var extra=null;
                  function harvest(v,depth){
                    if(extra||!v||depth>4) return;
                    if(Object.prototype.toString.call(v)==='[object Array]'){
                      if(v.length&&v[0]&&typeof v[0]==='object'&&
                         ('taskId' in v[0]||'taskName' in v[0]||'taskStage' in v[0])){
                        extra=v; return;
                      }
                      for(var q=0;q<v.length;q++) harvest(v[q],depth+1);
                      return;
                    }
                    if(typeof v==='object'){
                      for(var k in v){ try{ harvest(v[k],depth+1) }catch(e){} }
                    }
                  }
                  var refresh=null;
                  for(var i=0;i<comps.length && !refresh;i++){
                    var ms=mnames(comps[i]);
                    for(var j=0;j<ms.length;j++){
                      if(/taskIndex|fetchInviteTask|fetchTask|inviteTask|loadTask/i.test(ms[j])){
                        refresh={c:comps[i],m:ms[j]}; break
                      }
                    }
                  }
                  if(refresh){
                    try{
                      var r0=refresh.c[refresh.m]();
                      if(r0&&isFn(r0.then)) r0=await r0;
                      line('刷新: '+refresh.m+' OK');
                      harvest(r0,0);
                      if(extra) line('刷新回执里拿到任务 '+extra.length+' 项');
                    }catch(e){ line('刷新: '+refresh.m+' 失败 '+e) }
                    await wait(1500);
                    scanTasks();
                    if(!owner&&extra){
                      owner={}; field='__extra'; owner[field]=extra;
                      line('任务数组: 用刷新回执 共 '+extra.length+' 项');
                    }
                  } else line('刷新: 没找到任务方法');

                  var tasks=owner?(owner[field]||[]):[];
                  line('任务: 共 '+tasks.length+' 项');
                  var undo=0;
                  for(var i=0;i<tasks.length;i++){
                    var st=stageOf(tasks[i]);
                    if(/UNDO/i.test(st) || st==='') undo++;
                  }
                  line('待做: '+undo);

                  var cands=[];
                  for(var i=0;i<comps.length;i++){
                    var ms=mnames(comps[i]);
                    for(var j=0;j<ms.length;j++){
                      if(/doTask|toDoTask|doQuiz|completeTask/i.test(ms[j])){
                        cands.push({c:comps[i],m:ms[j],w:(comps[i]===owner?0:1)});
                      }
                    }
                  }
                  cands.sort(function(a,b){ return a.w-b.w });
                  line('doTask 候选: '+cands.length+' 个'+
                       (cands.length?('，用 '+cands[0].m):''));

                  var doneCount=0, mode='component';
                  for(var i=0;i<tasks.length;i++){
                    var t=tasks[i];
                    if(!/UNDO/i.test(stageOf(t)) && stageOf(t)!=='') continue;
                    if(!cands.length) break;
                    var call=cands[0], okOne=false;
                    try{
                      var ret=call.c[call.m](t);
                      if(ret&&isFn(ret.then)) await ret;
                      okOne=true;
                    }catch(e){
                      try{
                        var ret2=call.c[call.m](t.taskId, t.type||'');
                        if(ret2&&isFn(ret2.then)) await ret2;
                        okOne=true;
                      }catch(e2){ line('  做任务失败: '+e2) }
                    }
                    if(okOne){
                      doneCount++;
                      line('  做任务 '+cut(t.name||t.taskName||t.taskId,40)+' → '+call.m);
                    }
                    await wait(900);
                  }

                  await wait(1200);
                  if(refresh){
                    try{
                      var r3=refresh.c[refresh.m]();
                      if(r3&&isFn(r3.then)) await r3;
                    }catch(e){}
                    await wait(1200);
                  }
                  scanTasks();
                  tasks=owner?(owner[field]||[]):[];
                  var left=0;
                  for(var i=0;i<tasks.length;i++){ if(/UNDO/i.test(stageOf(tasks[i]))) left++ }
                  line('复查: 仍待做 '+left+' 个');

                  // 组件方法一个都没调成，退到页面自己的业务接口层直发。
                  if(doneCount===0 && left>0){
                    mode='api';
                    line('');
                    line('接口层: 组件方法不可用，改走页面请求封装');
                    var R=null;
                    try{
                      var gj=window.webpackJsonp||window.webpackChunk;
                      if(gj&&isFn(gj.push)){
                        var tok='__cmcc_soccer_probe__';
                        var mods={}; mods[tok]=function(m,e,r){ window.__cmccSoccerReq=r };
                        gj.push([[tok],mods,[[tok]]]);
                        R=window.__cmccSoccerReq||null;
                      }
                    }catch(e){ line('require 失败: '+e) }
                    if(R){
                      function apiFns(kw){
                        var out=[];
                        try{
                          var ids=Object.keys(R.c||{});
                          for(var i=0;i<ids.length;i++){
                            var ex=R.c[ids[i]]&&R.c[ids[i]].exports;
                            if(!ex) continue;
                            var ks=Object.getOwnPropertyNames(ex);
                            for(var j=0;j<ks.length;j++){
                              var f=null; try{ f=ex[ks[j]] }catch(e){}
                              if(!isFn(f)) continue;
                              var s=''; try{ s=String(f) }catch(e){}
                              if(s.indexOf(kw)>=0) out.push({mod:ids[i],key:ks[j],fn:f});
                            }
                          }
                        }catch(e){}
                        return out;
                      }
                      var idx=apiFns('footballQuiz/taskIndex');
                      var dof=apiFns('footballQuiz/doTask');
                      line('接口层: taskIndex '+idx.length+' 个, doTask '+dof.length+' 个');
                      var reqTs='';
                      try{
                        if(owner&&owner.pageInitData&&owner.pageInitData.reqTimestamp){
                          reqTs=String(owner.pageInitData.reqTimestamp);
                        }
                      }catch(e){}
                      if(idx.length){
                        var resp=null;
                        try{
                          resp=idx[0].fn({actNo:actNo,reqTimestamp:reqTs});
                          if(resp&&isFn(resp.then)) resp=await resp;
                        }catch(e){ line('taskIndex 直发失败: '+e) }
                        line('taskIndex 返回: '+cut(JSON.stringify(resp),400));
                        var arr=null;
                        if(resp&&resp.data&&Object.prototype.toString.call(resp.data)==='[object Array]'){
                          arr=resp.data;
                        }
                        if(arr&&dof.length){
                          for(var i=0;i<arr.length;i++){
                            var t=arr[i];
                            if(!/UNDO/i.test(stageOf(t))) continue;
                            try{
                              var r4=dof[0].fn({actNo:actNo,taskId:t.taskId});
                              if(r4&&isFn(r4.then)) await r4;
                              doneCount++;
                              line('  直发 doTask '+cut(t.taskId,36)+' OK');
                            }catch(e){ line('  直发 doTask 失败: '+e) }
                            await wait(900);
                          }
                          // 复查：再拉一次任务列表，确认服务端真的置成 DONE。
                          await wait(1200);
                          try{
                            var r5=idx[0].fn({actNo:actNo,reqTimestamp:reqTs});
                            if(r5&&isFn(r5.then)) r5=await r5;
                            var arr2=(r5&&r5.data
                              &&Object.prototype.toString.call(r5.data)==='[object Array]')
                              ?r5.data:null;
                            if(arr2){
                              tasks=arr2; left=0;
                              for(var i=0;i<arr2.length;i++){
                                if(/UNDO/i.test(stageOf(arr2[i]))) left++;
                              }
                              line('接口层复查: 仍待做 '+left+' 个');
                            }
                          }catch(e){ line('接口层复查失败: '+e) }
                        }
                      }
                    }
                  }

                  // ---- 自动竞猜（v3.35 新增）----
                  // 规则：取页面上当前这一场（就是距开赛最近、还没截止的那场），
                  // 比较两队「用户选」比例，点比例高的那队，再点确认。
                  // 全程走页面真实按钮，不拼 vote 的加密请求体。
                  var voteOut={ok:0,try:0,skip:'',team:'',pct:'',cost:'',match:'',err:''};
                  try{
                    var bt0=btext();
                    if(/您已参与本场竞猜|已参与本场/.test(bt0)){
                      voteOut.skip='本场已参与';
                    } else if(/本场竞猜已关闭|竞猜已截止/.test(bt0)){
                      voteOut.skip='本场已截止';
                    } else {
                      var opts=teamOptions();
                      line('竞猜选项: '+opts.length);
                      for(var vi=0;vi<opts.length;vi++){
                        line('  '+opts[vi].name+' '+opts[vi].pct+'%');
                      }
                      if(opts.length<2){
                        voteOut.err='页面上没找到两队选项';
                      } else {
                        var mm=/(\\d{4}-\\d{1,2}-\\d{1,2})/.exec(bt0);
                        if(mm) voteOut.match=mm[1];
                        opts.sort(function(a,b){ return b.pct-a.pct });
                        var pick=opts[0];
                        voteOut.team=pick.name;
                        voteOut.pct=String(pick.pct);
                        var netMarkV=NET.length;
                        voteOut.try++;
                        try{ pick.el.click(); line('  选队: '+pick.name+' '+pick.pct+'%') }catch(e){ line('  选队点击失败: '+e) }
                        await wait(900);
                        // Vue 常把 @click 绑在外层 div，点内层 span 没反应时补点父级。
                        if(!/消耗|确认竞猜|竞猜票|确认/.test(btext())){
                          try{
                            if(pick.el.parentElement){
                              pick.el.parentElement.click();
                              line('  选队补点父级');
                            }
                          }catch(e){}
                        }
                        await wait(1200);
                        var dlg=btext();
                        var cm=/消耗\\s*(\\d+)\\s*张竞猜票/.exec(dlg);
                        if(cm) voteOut.cost=cm[1];
                        var cf=clickAny(['确认','确定','立即竞猜'],1);
                        if(cf) line('  竞猜确认: '+cf);
                        await wait(2200);
                        for(var vq=netMarkV;vq<NET.length;vq++){
                          if(/greenSoccer2026\\/vote/i.test(NET[vq].u)
                             && NET[vq].s>=200 && NET[vq].s<400){
                            voteOut.ok=1;
                            line('  竞猜回执: '+NET[vq].s+' '+cut(NET[vq].b,160));
                            break;
                          }
                        }
                        if(voteOut.ok===0){
                          var bt1=btext();
                          if(/您已参与本场竞猜|已参与本场/.test(bt1)){
                            voteOut.ok=1;
                            line('  竞猜回执: 页面已显示「您已参与本场竞猜」');
                          } else {
                            voteOut.err='点了确认，但没看到 vote 回执';
                          }
                        }
                      }
                    }
                  }catch(e){ voteOut.err='竞猜异常 '+e }

                  done({ok:left===0, stage:'done', actNo:actNo, mode:mode,
                        total:tasks.length, undo:undo, done:doneCount, left:left,
                        vote:voteOut});
                }catch(err){
                  done({ok:false, stage:'error', msg:String((err&&err.stack)||err)});
                }
              })();
            })()
            """;
}
