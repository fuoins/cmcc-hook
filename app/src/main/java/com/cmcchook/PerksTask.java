package com.cmcchook;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 天天领权益（辽宁）—— 天天领 / 周周抢 / 月月享。
 *
 * <p>活动页：{@code https://rr.ln139.cn/rr/actH5/index.html#/workPerksMall?activityCode=36293370}，
 * 入口短链 {@code 0FKMeG} 由门户走
 * {@code ssoJump → dayWeekMoonReceive/entrance.do?activityCode=36293370&pc=… → 302 落地}。
 *
 * <p>和辽宁其它活动不同，这个站的业务请求体是<b>明文</b> form：
 * <pre>
 * POST /rr/lm/dayWeekMoonReceive/receive.do?LJRkIeoW=&lt;动态签名&gt;
 * body: activityCode=36293370&amp;ruleId=…&amp;prizeId=…&amp;prizeName=…
 * resp: {"msg":"操作成功","code":"0000","body":{"prizeRecordId":"…"},"success":true}
 * </pre>
 * 只有 {@code LJRkIeoW} 是页面 JS 现场算的签名（{@code queryJsEncodeConfig.do} 下发的
 * deffirst/defsecond 密钥），Java 层拼不出来，所以仍然<b>只借页面自己的按钮</b>：
 * 点每日卡片上的「立即领」，让页面发 receive.do，模块只在旁边读回执，不重放、不改包。
 *
 * <p>页面结构（抓包里的活动组件 chunk-8822bd48）：
 * <ul>
 *   <li>每日区 {@code .wpm-benefit-section-daily}，奖品卡 {@code .wpm-benefit-card}，
 *       按钮 {@code .wpm-benefit-btn}</li>
 *   <li>按钮状态文案：{@code 立即领} 可领 / {@code 已抢光} / {@code 明天再来} /
 *       {@code 下周再来} / {@code 下月再来} / {@code 未开启}</li>
 *   <li>成功弹窗 {@code .wds-panel}「领取成功」+ {@code .wds-ticket}，关闭 {@code .wds-close}</li>
 *   <li>领光/上限弹窗 {@code .dlm-*} / {@code .mlm-*}</li>
 * </ul>
 *
 * <p>只做每日「立即领」：周周抢是付费权益（stockOccupy → createPayOrder → 支付），
 * 月月享要点亮累计，两者都不在自动范围内，日志里如实标注未做。
 */
final class PerksTask {

    private static final String PREFS = "cmcc_hook_perks";
    private static final String KEY_ENTRY = "entry_url";
    private static final String KEY_ENTRY_AT = "entry_at";
    private static final long ENTRY_TTL = 24L * 3600_000L;

    static final String ACT_CODE = "36293370";
    static final String ACT_PERKS =
            "https://rr.ln139.cn/rr/actH5/index.html#/workPerksMall?activityCode=" + ACT_CODE;

    private PerksTask() {
    }

    static boolean isPerksPage(String url) {
        if (url == null) {
            return false;
        }
        String low = url.toLowerCase();
        return low.contains("workperksmall") || low.contains("acth5/index.html");
    }

    /** 记住 App 现场打开的带 activityCode 的落地页，下次优先复用（省一次 SSO）。 */
    static void rememberEntry(String url) {
        if (url == null || !isPerksPage(url) || !url.contains("workPerksMall")) {
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
        } catch (Throwable ignored) {
        }
    }

    static String entryUrl() {
        Context ctx = HookLog.context();
        if (ctx == null) {
            return SsoShortLink.PERKS;
        }
        try {
            SharedPreferences p = AccountScope.prefs(ctx, PREFS);
            String url = p.getString(KEY_ENTRY, "");
            long at = p.getLong(KEY_ENTRY_AT, 0L);
            if (!url.isEmpty() && System.currentTimeMillis() - at <= ENTRY_TTL
                    && url.contains("activityCode=")) {
                return url;
            }
            return SsoShortLink.PERKS;
        } catch (Throwable e) {
            return SsoShortLink.PERKS;
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

    static TaskRunner.Result run(String entry) {
        StringBuilder detail = new StringBuilder();
        detail.append("入口: ").append(brief(entry)).append('\n');
        WebPage page = WebSession.current();
        if (page == null || page.raw() == null) {
            return TaskRunner.Result.fail("天天领权益活动页没起来",
                    detail.toString(),
                    "WebView 没能加载活动页。重开一次中国移动 App 再试。");
        }
        long overall = System.currentTimeMillis() + 25_000L;
        if (!GooseTask.pageAlive(page, detail, "PERKS")) {
            HookLog.log("PERKS", "真页空白 · 重建一次",
                    "存活检查没过，关掉当前 WebView 会话，按入口重开一次真页。");
            String key = WebSession.key();
            WebSession.end();
            WebSession.begin(key == null || key.isEmpty() ? "perks" : key,
                    entry, GooseTask.budget(overall, 12_000L));
            WebPage fresh = WebSession.current();
            if (fresh == null || !GooseTask.pageAlive(fresh, detail, "PERKS")) {
                return TaskRunner.Result.skip("天天领权益 · 真页没起来（空白 / 被跳转顶掉）",
                        detail.toString(),
                        "活动页没加载出来。重开一次中国移动 App，或先在 App 里手动点一次「天天领权益」再执行。");
            }
            page = fresh;
        }

        String landed = page.url() == null ? "" : page.url();
        detail.append("落地页: ").append(brief(landed)).append('\n');
        if (landed.contains("ssoJump") || landed.contains("entrance.do")) {
            // 还停在中转页：多等一会儿 SSO 跳转，不急着判死。
            for (int i = 0; i < 20 && System.currentTimeMillis() < overall; i++) {
                sleep(500L);
                landed = page.url() == null ? "" : page.url();
                if (landed.contains("workPerksMall") || landed.contains("actH5/index.html")) {
                    break;
                }
            }
            detail.append("等 SSO 落地: ").append(brief(landed)).append('\n');
        }
        if (!landed.contains("workPerksMall") && !landed.contains("actH5/index.html")) {
            forgetEntry();
            return TaskRunner.Result.skip("天天领权益 · 没走到活动落地页",
                    detail.toString(),
                    "短链还停在中转页。重开一次中国移动 App 再试；若连续两次都这样，把日志发我。");
        }

        page.eval(PERKS_SCRIPT, 8_000L);
        String raw = null;
        long deadline = System.currentTimeMillis() + 75_000L;
        while (System.currentTimeMillis() < deadline) {
            String v = page.eval(
                    "(function(){try{return String(window.__cmcc_perks||'')}catch(e){return ''}})()",
                    5_000L);
            if (v != null && !v.isEmpty()) {
                raw = v;
                break;
            }
            sleep(700L);
        }
        if (raw == null) {
            return TaskRunner.Result.fail("天天领权益脚本超时",
                    detail.append("页面脚本 75 秒没有回报\n").toString(),
                    "页面能打开但脚本没回报。把这段日志发我。");
        }

        JSONObject out;
        try {
            out = new JSONObject(raw);
        } catch (Throwable e) {
            return TaskRunner.Result.fail("天天领权益结果解析失败",
                    detail.append("回报: ").append(brief(raw)).toString(), "");
        }
        writeLines(out.optJSONArray("lines"));

        String stage = out.optString("stage", "");
        int items = out.optInt("items", 0);
        int claimable = out.optInt("claimable", 0);
        int claimed = out.optInt("claimed", 0);
        String prize = out.optString("prize", "");
        String claimName = out.optString("claimName", "");
        String code = out.optString("code", "");
        String msg = out.optString("msg", "");
        String pageText = out.optString("text", "");
        String url = out.optString("url", "");

        detail.append("页面: ").append(brief(url)).append('\n');
        detail.append("每日奖品: ").append(items).append(" 个 · 可领 ").append(claimable)
                .append(" 个\n");
        if (!pageText.isEmpty()) {
            detail.append("页面文案: ").append(brief(pageText)).append('\n');
        }

        if ("no-page".equals(stage)) {
            return TaskRunner.Result.skip("天天领权益 · 页面没渲染出奖品列表",
                    detail.toString(),
                    "页面打开了但没渲染出每日奖品卡（可能还在加载活动信息，或登录态被顶掉）。\n"
                            + "退出重进一次活动页再跑；连续出现把日志发我。");
        }
        if ("error".equals(stage)) {
            return TaskRunner.Result.fail("天天领权益脚本异常",
                    detail.append(out.optString("err", "")).toString(),
                    "把日志发我。");
        }

        if (claimed > 0) {
            String got = prize.isEmpty() ? (claimName.isEmpty() ? "每日奖品" : claimName) : prize;
            String summary = "天天领权益 · 已领「" + got + "」";
            detail.append("提交回执: receive.do code=").append(code)
                    .append(" msg=").append(msg).append('\n');
            detail.append("说明: 回执 body 里有 prizeRecordId，页面自己说已受理；"
                    + "流量券会下发到「中国移动APP-我的-卡券」。\n");
            return TaskRunner.Result.ok(summary, detail.toString(), summary);
        }

        if (!code.isEmpty()) {
            // 有回执但没领到：页面明确给了业务码，照实写，不猜。
            String hint = "页面回执: " + code + (msg.isEmpty() ? "" : " " + msg);
            if (code.equals("0000")) {
                return TaskRunner.Result.skip("天天领权益 · 页面受理了但没返回奖品记录",
                        detail.append(hint).append('\n').toString(),
                        "接口回了 0000 但 body 里没有 prizeRecordId，把日志发我核对。");
            }
            boolean already = msg.contains("上限") || msg.contains("已领")
                    || msg.contains("领过") || msg.contains("明天");
            if (already) {
                return TaskRunner.Result.idle("天天领权益 · 今天已经领过了",
                        detail.append(hint).append('\n').toString(),
                        "每日最多领 1 个奖品，明天 10 点后再跑。");
            }
            boolean soldOut = msg.contains("抢光") || msg.contains("领完");
            if (soldOut) {
                return TaskRunner.Result.skip("天天领权益 · 今日奖品已抢光",
                        detail.append(hint).append('\n').toString(),
                        "今天的份额没了，明天 10 点后再来。");
            }
            return TaskRunner.Result.skip("天天领权益 · 没领到（" + code + "）",
                    detail.append(hint).append('\n').toString(),
                    "页面回执如上。把日志发我，我按这个码补判定。");
        }

        if (claimable == 0) {
            String state = out.optString("state", "");
            return TaskRunner.Result.idle("天天领权益 · 今天没有可领的奖品"
                            + (state.isEmpty() ? "" : "（" + state + "）"),
                    detail.toString(),
                    "每日奖品卡上都不是「立即领」：已抢光 / 明天再来 / 未开启。明天 10 点后再跑。");
        }
        // 有可领按钮但点了没有任何回执。
        return TaskRunner.Result.fail("天天领权益 · 点了「立即领」但没收到 receive.do 回执",
                detail.toString(),
                "按钮点了却没发出领取请求：可能弹了二次确认弹窗，或按钮在图片层上。\n"
                        + "把这份完整日志发我，我按页面实际 DOM 收敛选择器。");
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
                HookLog.log("PERKS", "天天领权益执行明细 " + part, chunk.toString());
                chunk.setLength(0);
                part++;
            }
            chunk.append(one).append('\n');
        }
        if (chunk.length() > 0) {
            HookLog.log("PERKS", "天天领权益执行明细 " + part, chunk.toString());
        }
    }

    private static String brief(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= 200 ? text : text.substring(0, 200) + "…";
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }

    /**
     * 页面内脚本：等每日奖品渲染 → 点第一张卡的「立即领」→ 只读 receive.do 回执。
     *
     * <p>不拼 LJRkIeoW、不改请求体；只装一个只读 XHR 钩子记录
     * receive.do / receiveWithRecord.do 的状态码、响应体和请求体（取 prizeName）。
     */
    private static final String PERKS_SCRIPT = """
            (async function(){
              var ID='__cmcc_perks';
              window[ID]='';
              var L=[], NET=[];
              function line(s){ L.push(String(s)) }
              function cut(s,n){ s=String(s==null?'':s); return s.length>n?s.slice(0,n)+'…':s }
              function wait(ms){ return new Promise(function(r){ setTimeout(r,ms) }) }
              function txt(e){ try{ return String(e.innerText||e.textContent||'').replace(/\\s+/g,'') }catch(x){ return '' } }
              function vis(e){ try{ var r=e.getBoundingClientRect(); return r.width>2&&r.height>2 }catch(x){ return false } }
              function done(o){ try{ o.lines=L; window[ID]=JSON.stringify(o) }catch(e){ window[ID]='{"stage":"crash","lines":[]}' } }

              // 只读回执钩子：不改请求、不改响应，只把 receive.do 记下来。
              (function(){
                try{
                  if(window.__cmcc_perks_netHooked){ NET=window.__cmcc_perks_net||[]; return }
                  window.__cmcc_perks_netHooked=1;
                  window.__cmcc_perks_net=NET;
                  var XO=window.XMLHttpRequest;
                  if(!XO||!XO.prototype) return;
                  var open0=XO.prototype.open, send0=XO.prototype.send;
                  XO.prototype.open=function(m,u){
                    try{ this.__cmccM=String(m||''); this.__cmccU=String(u||'') }catch(e){}
                    return open0.apply(this,arguments);
                  };
                  XO.prototype.send=function(b){
                    var x=this, q='';
                    try{ q=String(b||'') }catch(e){}
                    try{
                      x.addEventListener('load',function(){
                        var u=''; try{ u=String(x.__cmccU||'') }catch(e){}
                        var t=''; try{ t=String(x.responseText||'') }catch(e){}
                        var st=0; try{ st=x.status }catch(e){}
                        NET.push({m:String(x.__cmccM||''),u:u,s:st,b:t,q:q,t:Date.now()});
                        if(NET.length>80) NET.shift();
                      });
                    }catch(e){}
                    return send0.apply(this,arguments);
                  };
                }catch(e){}
              })();

              // 完整触摸序列：页面按钮是 Vue 组件，只派发 click 常常没反应。
              function fire(e){
                try{ e.scrollIntoView({block:'center'}) }catch(x){}
                var r=null; try{ r=e.getBoundingClientRect() }catch(x){}
                var cx=r?(r.left+r.width/2):0, cy=r?(r.top+r.height/2):0;
                var tgt=e;
                try{ var h=document.elementFromPoint(cx,cy); if(h) tgt=h }catch(x){}
                var touch=null;
                try{
                  if(typeof Touch==='function'){
                    touch=new Touch({identifier:1,target:tgt,clientX:cx,clientY:cy,
                                     pageX:cx,pageY:cy,screenX:cx,screenY:cy});
                  }
                }catch(x){}
                var fake={identifier:1,target:tgt,clientX:cx,clientY:cy,
                          pageX:cx,pageY:cy,screenX:cx,screenY:cy};
                function tl(){ return [touch?touch:fake] }
                function mk(type){
                  var o={bubbles:true,cancelable:true,view:window,clientX:cx,clientY:cy,
                         button:0,buttons:type==='touchend'?0:1};
                  if(type==='touchend'){ o.touches=[]; o.targetTouches=[] }
                  else { o.touches=tl(); o.targetTouches=tl() }
                  o.changedTouches=tl();
                  return o;
                }
                ['touchstart','touchend'].forEach(function(tp){
                  try{
                    var ev=null;
                    try{ ev=new TouchEvent(tp,mk(tp)) }catch(x){}
                    if(!ev){
                      ev=new Event(tp,{bubbles:true,cancelable:true});
                      var o=mk(tp);
                      ['touches','targetTouches','changedTouches','clientX','clientY']
                        .forEach(function(k){
                          try{ Object.defineProperty(ev,k,{value:o[k],configurable:true}) }catch(x){}
                        });
                    }
                    tgt.dispatchEvent(ev);
                  }catch(x){}
                });
                ['mousedown','mouseup','click'].forEach(function(tp){
                  try{
                    tgt.dispatchEvent(new MouseEvent(tp,
                      {bubbles:true,cancelable:true,view:window,clientX:cx,clientY:cy}));
                  }catch(x){}
                });
                if(tgt!==e){ try{ e.click() }catch(x){} }
                return String(tgt.tagName||'')+'.'+String(tgt.className||'').slice(0,40);
              }

              var t0=Date.now();
              while(Date.now()-t0<25000){
                if(document.querySelector('.wpm-benefit-card')) break;
                await wait(700);
              }
              var bodyTxt=String((document.body&&document.body.innerText)||'').replace(/\\s+/g,' ').slice(0,300);
              var cards=document.querySelectorAll('.wpm-benefit-card');
              if(!cards.length){
                line('页面没渲染出每日奖品卡（body: '+cut(bodyTxt,120)+'）');
                done({stage:'no-page', text:bodyTxt, url:String(location.href)});
                return;
              }

              // 每日区优先；页面没分区 class 就退到全部卡片。
              var scope=document.querySelector('.wpm-benefit-section-daily')||document;
              var daily=scope.querySelectorAll('.wpm-benefit-card');
              if(!daily.length) daily=cards;

              var items=[], claim=null, claimName='', stateCount={};
              for(var i=0;i<daily.length;i++){
                var c=daily[i], name=txt(c).slice(0,40);
                var btn=null, label='';
                var bs=c.querySelectorAll('.wpm-benefit-btn,button,a,[role=button]');
                for(var j=0;j<bs.length;j++){
                  var b=bs[j], bt=txt(b);
                  if(!bt) continue;
                  if(/^(立即领|立即领取|马上领|领取|抢)$/.test(bt)&&vis(b)){ btn=b; label=bt; break }
                  if(!label && /抢光|明天再来|下周再来|下月再来|未开启|已领取/.test(bt)){
                    label=bt;
                  }
                }
                if(!label) label='-';
                stateCount[label]=(stateCount[label]||0)+1;
                items.push({i:i,name:name,btn:label,can:!!btn});
                if(btn&&!claim){ claim=btn; claimName=name }
              }
              var canN=0;
              for(var k=0;k<items.length;k++){ if(items[k].can) canN++ }
              line('每日奖品: '+items.length+' 个 · 可领 '+canN+' 个');
              for(var k2=0;k2<items.length;k2++){
                line('  ['+items[k2].i+'] '+cut(items[k2].name,34)+' | 按钮='+items[k2].btn);
              }

              if(!claim){
                var states=[];
                for(var sk in stateCount){ if(stateCount.hasOwnProperty(sk)) states.push(sk+'×'+stateCount[sk]) }
                done({stage:'done', items:items.length, claimable:0,
                      state:states.join(' / '), text:bodyTxt, url:String(location.href)});
                return;
              }

              line('点「'+items[0].btn+'」: '+cut(claimName,40));
              var before=NET.length;
              var hit=fire(claim);
              line('点击落点: '+hit);

              // 有的版本先弹「确认领取」再发请求，顺手点掉一层确认。
              await wait(1200);
              if(NET.length===before){
                var conf=document.querySelectorAll('button,a,div,span,[role=button]');
                for(var q=0;q<conf.length&&q<400;q++){
                  var e2=conf[q], t2=txt(e2);
                  if(!t2||!vis(e2)) continue;
                  if(/^(确认领取|确认|立即领取|确定)$/.test(t2)){
                    line('二次确认: '+t2);
                    fire(e2);
                    break;
                  }
                }
              }

              var rec=null;
              for(var n=0;n<40;n++){
                await wait(500);
                for(var m=before;m<NET.length;m++){
                  if(/dayWeekMoonReceive\\/(receive|receiveWithRecord)\\.do/.test(String(NET[m].u||''))){
                    rec=NET[m];
                  }
                }
                if(rec) break;
              }

              var out={stage:'done', items:items.length, claimable:canN,
                       claimName:claimName, claimed:0, code:'', msg:'', prize:''};
              if(rec){
                var d=null;
                try{ d=JSON.parse(rec.b) }catch(x){}
                out.code=d?String(d.code||''):'';
                out.msg=d?String(d.msg||''):'';
                if(d&&d.body&&d.body.prizeRecordId) out.claimed=1;
                try{
                  var mm=/prizeName=([^&]*)/.exec(String(rec.q||''));
                  if(mm) out.prize=decodeURIComponent(mm[1]);
                }catch(x){}
                line('提交回执: receive.do status='+rec.s+' code='+out.code
                     +' msg='+out.msg+(out.prize?(' 奖品='+out.prize):''));
                if(!out.claimed&&out.code!=='0000'){
                  line('回执原文: '+cut(String(rec.b||''),200));
                }
              } else {
                line('提交回执: 20 秒内没有 receive.do（可能只弹了确认层或按钮没触发）');
              }

              // 关掉成功/上限弹窗，避免遮住后续操作。
              await wait(1000);
              ['wds-close','dlm-close','mlm-close','wpm-description-close'].forEach(function(cl){
                try{
                  var els=document.querySelectorAll('.'+cl);
                  for(var z=0;z<els.length;z++){ if(vis(els[z])){ fire(els[z]); break } }
                }catch(x){}
              });
              done(out);
            })();
            """;
}
