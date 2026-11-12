package com.cmcchook;

import org.json.JSONObject;

/**
 * 灵犀 AI 里的抢券（每周二、周四 10:00）。
 *
 * <p>和另两项的区别：这项没有已知的抢券接口，券在灵犀首页底栏的页面里，
 * 只能「在页面上点」。所以这里不猜接口，也不新建页面 —— 用户提前把灵犀停在
 * 券页（或者模块自己找到已打开的灵犀页），到点就在这个页面上反复点：
 *
 * <ul>
 *   <li>先看页面有没有已经抢到 / 已经抢光，有就直接收工，不空转 100 秒；</li>
 *   <li>没有明显按钮时，先点底栏的「福利 / 领券 / 优惠」这类入口（灵犀是单页，
 *       点完 URL 可能都不变，所以每轮重新扫，靠状态推进）；</li>
 *   <li>有抢券按钮就点，点完等 400ms 再扫，循环到出现成功文案或超时。</li>
 * </ul>
 *
 * <p>落点靠文案 + 尺寸 + 是否可点，不写死坐标；每轮命中的文案都写进日志，
 * 抢不到时能直接看出「页面长什么样、点到了什么」。
 */
final class LingxiGrab {

    /** 一共撞多久。开场后 100 秒没结果就停，别一直占着页面。 */
    private static final long WINDOW_MS = 100_000L;

    /** 单轮上限，防止脚本卡住时把整个窗口耗在等 JS 上。 */
    private static final int MAX_ROUNDS = 260;

    /** r13：立即发口令（不进抢券等待），由模块页的「灵犀发口令」按钮触发。 */
    static final String CMD_SEND = "lingxi:send";

    /** 模块随命令带过来的口令。目标进程读不到模块设置，只能走广播 extra。 */
    private static volatile String pendingText = "";

    private LingxiGrab() {
    }

    static void text(String value) {
        pendingText = value == null ? "" : value.trim();
    }

    static String text() {
        return pendingText;
    }

    /** 等待期间抓住的这一页：到点就在同一页上点，不再重新找（可能已经切走）。 */
    private static volatile WebPage prepared;

    /** 已开着的灵犀页：App 自己打开的那一个，不新建、不改地址。 */
    static WebPage live() {
        try {
            Object v = RealPage.find("xunfei", 10L * 60_000L);
            if (v == null) {
                v = RealPage.find("lingxi", 10L * 60_000L);
            }
            return v == null ? null : WebPage.attach(v);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 找页面：优先复用已经开着的灵犀页；没有就按模块记下的入口开一次；
     * 都没有返回 null（让调用方提示用户手动进页面，不记失败）。
     */
    static WebPage prepare(StringBuilder d) {
        WebPage page = live();
        if (page != null && page.raw() != null) {
            d.append("页面: 复用已打开的灵犀页 · ").append(shortUrl(page.href())).append('\n');
            prepared = page;
            return page;
        }
        String url = TaskApi.findEntry("xunfei");
        if (url.isEmpty()) {
            url = TaskApi.findEntry("lingxi");
        }
        if (url.isEmpty()) {
            d.append("页面: 没有灵犀入口，需要手动进一次\n");
            prepared = null;
            return null;
        }
        d.append("页面: 打开灵犀 · ").append(shortUrl(url)).append('\n');
        page = RealPage.open(url, "lingxi", 20_000L);
        if (page == null || page.raw() == null) {
            d.append("页面: 没打开\n");
            prepared = null;
            return null;
        }
        prepared = page;
        return page;
    }

    /** 到点以后：在这个页面上反复点，直到抢到 / 抢光 / 超时。 */
    static TaskRunner.Result run() {
        StringBuilder d = new StringBuilder();
        String cmd = pendingText;
        WebPage page = prepared;
        if (page == null || page.raw() == null) {
            page = live();
        }
        if (page == null || page.raw() == null) {
            return TaskRunner.Result.skip("灵犀抢券 · 没有页面",
                    d.toString(),
                    "先手动打开灵犀、进到抢券那一页，再点「进入等待」。");
        }
        d.append("页面: ").append(shortUrl(page.url())).append('\n');
        if (!cmd.isEmpty()) {
            d.append("备用口令: ").append(cmd).append('\n');
        }
        HookLog.log("GRAB", "灵犀抢券 · 开始", d.toString());
        try {
            return loop(page, d, cmd);
        } finally {
            prepared = null;
        }
    }

    private static TaskRunner.Result loop(WebPage page, StringBuilder d, String cmd) {
        long end = System.currentTimeMillis() + WINDOW_MS;
        int round = 0;
        int hits = 0;
        boolean triedTyping = false;
        String last = "";
        while (System.currentTimeMillis() < end && round < MAX_ROUNDS) {
            round++;
            String raw = page.eval(JS, 8_000L);
            if (raw == null || raw.isEmpty()) {
                if (round <= 3) {
                    d.append("第 ").append(round).append(" 轮: 脚本没回报\n");
                }
                WebPage.sleep(500L);
                continue;
            }
            JSONObject o;
            try {
                o = new JSONObject(unquote(raw));
            } catch (Throwable e) {
                if (round <= 3) {
                    d.append("第 ").append(round).append(" 轮: 回报解析失败 ")
                            .append(brief(raw)).append('\n');
                }
                WebPage.sleep(500L);
                continue;
            }
            if (o.has("err")) {
                d.append("脚本异常: ").append(o.optString("err")).append('\n');
                break;
            }
            if (o.has("done")) {
                d.append("第 ").append(round).append(" 轮: 出现「")
                        .append(o.optString("txt")).append("」\n");
                String text = "灵犀抢券 · " + o.optString("txt", "已领取");
                HookLog.log("GRAB", text, d.toString());
                return TaskRunner.Result.ok(text, d.toString(), text);
            }
            if (o.has("full")) {
                d.append("第 ").append(round).append(" 轮: 「")
                        .append(o.optString("txt")).append("」\n");
                String text = "灵犀抢券 · 已经没了（" + o.optString("txt") + "）";
                HookLog.log("GRAB", text, d.toString());
                return TaskRunner.Result.idle(text, d.toString(), text);
            }
            String txt = o.optString("txt", "");
            if (txt.isEmpty()) {
                if (!triedTyping && !cmd.isEmpty() && round >= 2) {
                    // r13：有些号根本没有那个 chip（服务端按号码下发）。这里不空等，
                    // 把上一个号捕获到的口令直接打进输入框发出去，成不成看回执。
                    triedTyping = true;
                    d.append("第 ").append(round).append(" 轮: 没有可点入口，改用输入框发口令\n");
                    HookLog.log("GRAB", "灵犀 · 没有入口，改用输入框发口令",
                            "口令: " + cmd + "\n页面: " + page.url());
                    JSONObject sr = sendOnce(page, cmd, d);
                    if (sr != null && sr.has("err")) {
                        d.append("发送脚本异常: ").append(sr.optString("err")).append('\n');
                    }
                    WebPage.sleep(2500L);
                    continue;
                }
                if (round <= 3 || round % 40 == 0) {
                    d.append("第 ").append(round).append(" 轮: 页面上没找到可点的抢券入口，继续等\n");
                    HookLog.log("GRAB", "灵犀第 " + round + " 轮 · 没有可点入口",
                            "页面: " + page.url());
                }
                WebPage.sleep(700L);
                continue;
            }
            hits++;
            last = txt;
            if (o.has("nav")) {
                d.append("第 ").append(round).append(" 轮: 点底栏「").append(txt).append("」\n");
                WebPage.sleep(900L);
            } else {
                d.append("第 ").append(round).append(" 轮: 点「").append(txt).append("」\n");
                WebPage.sleep(420L);
            }
            if (hits <= 10 || hits % 20 == 0) {
                HookLog.log("GRAB", "灵犀第 " + round + " 轮 · 点「" + txt + "」",
                        "页面: " + page.url() + "\n命中: " + hits);
            }
        }
        d.append("共 ").append(round).append(" 轮，命中 ").append(hits).append(" 次\n");
        d.append("最后点: ").append(last.isEmpty() ? "无" : last).append('\n');
        String text = "灵犀抢券 · 没抢到（" + (last.isEmpty() ? "页面上没找到入口" : last) + "）";
        HookLog.log("GRAB", text, d.toString());
        return TaskRunner.Result.idle(text, d.toString(), text);
    }

    /**
     * r13：立即把口令发出去（不等到 10 点）。
     *
     * <p>带按钮的号点 chip 走原路；没有 chip 的号走输入框。两条路都在日志里写清楚，
     * 失败时要能区分「没输入框 / 没发送键 / 发了没回执」。
     */
    static TaskRunner.Result send(String value) {
        StringBuilder d = new StringBuilder();
        String cmd = value == null ? "" : value.trim();
        if (cmd.isEmpty()) {
            return TaskRunner.Result.skip("灵犀发口令 · 没有口令", "",
                    "先在带按钮的号上点一次按钮，让模块记下口令，或在这里手动填写。");
        }
        WebPage page = prepared;
        if (page == null || page.raw() == null) {
            page = live();
        }
        if (page == null || page.raw() == null) {
            page = prepare(d);
        }
        if (page == null || page.raw() == null) {
            return TaskRunner.Result.skip("灵犀发口令 · 没有页面", d.toString(),
                    "先手动打开灵犀，再点「灵犀发口令」。");
        }
        d.append("页面: ").append(shortUrl(page.url())).append('\n');
        d.append("口令: ").append(cmd).append('\n');
        HookLog.log("GRAB", "灵犀发口令 · 开始", d.toString());
        HookLog.progress("grab", "· 正在发送口令…");

        JSONObject r = sendOnce(page, cmd, d);
        if (r == null) {
            String text = "灵犀发口令 · 页面没有回报";
            HookLog.log("GRAB", text, d.toString());
            HookLog.progress("grab", "· " + text);
            return TaskRunner.Result.idle(text, d.toString(), text);
        }
        if (r.has("err")) {
            String text = "灵犀发口令 · 脚本异常";
            d.append("异常: ").append(r.optString("err")).append('\n');
            HookLog.log("GRAB", text, d.toString());
            HookLog.progress("grab", "· " + text);
            return TaskRunner.Result.idle(text, d.toString(), text);
        }
        if (r.has("none")) {
            String text = "灵犀发口令 · 页面上没有输入框";
            d.append("iframe: ").append(r.optString("frames", "")).append('\n');
            HookLog.log("GRAB", text, d.toString());
            HookLog.progress("grab", "· " + text);
            return TaskRunner.Result.idle(text, d.toString(),
                    "灵犀页没有找到可输入的聊天框，可能页面还没加载完，稍后重试。");
        }
        String how = r.has("chip") ? "点 chip「" + r.optString("chip", "") + "」"
                : r.has("button") ? "填输入框 + 点「" + r.optString("button", "") + "」"
                : r.has("icon") ? "填输入框 + 点右侧发送图标"
                : r.has("enter") ? "填输入框 + 回车"
                : r.has("noSend") ? "填了输入框，但没找到发送键"
                : "填了输入框，发送结果未知";
        HookLog.log("GRAB", "灵犀发口令 · " + how, d.toString());

        WebPage.sleep(4_000L);
        JSONObject rd = read(page, d);
        String all = rd == null ? "" : rd.optString("tail", "") + "\n" + join(rd.optJSONArray("bubbles"));
        if (all.contains("领取成功") || all.contains("已领取") || all.contains("抢券成功")
                || all.contains("恭喜获得") || all.contains("已到账")) {
            String text = "灵犀发口令 · 回执成功";
            d.append("回执: 成功\n");
            HookLog.log("GRAB", text, d.toString());
            HookLog.progress("grab", "✓ " + text);
            return TaskRunner.Result.ok(text, d.toString(), text);
        }
        if (all.contains("已抢光") || all.contains("已领完") || all.contains("活动结束")
                || all.contains("未开始") || all.contains("不符合") || all.contains("无资格")
                || all.contains("稍后再试") || all.contains("系统繁忙") || all.contains("已参与")) {
            String text = "灵犀发口令 · 回执未通过";
            d.append("回执: 未通过\n").append(tailOf(all)).append('\n');
            HookLog.log("GRAB", text, d.toString());
            HookLog.progress("grab", "· " + text);
            return TaskRunner.Result.idle(text, d.toString(),
                    "口令发出去了，但服务端回执不通过 —— 可能是号码资格或活动已结束。");
        }
        String text = "灵犀发口令 · 已发送，未见回执";
        d.append("回执: 未见关键词\n").append(tailOf(all)).append('\n');
        HookLog.log("GRAB", text, d.toString());
        HookLog.progress("grab", "· " + text);
        return TaskRunner.Result.idle(text, d.toString(), "口令已发出，页面没有明确回执。");
    }

    private static JSONObject sendOnce(WebPage page, String cmd, StringBuilder d) {
        String js = SEND_JS.replace("__CMD__", JSONObject.quote(cmd));
        JSONObject r = evalJson(page, js, 12_000L, d, "发送脚本");
        if (r == null || !r.has("filled")) {
            return r;
        }
        // 受控输入框在 input 事件后要一帧才启用发送键，等 600ms 再点。
        WebPage.sleep(600L);
        JSONObject t = evalJson(page, TAP_SEND_JS, 8_000L, d, "点发送");
        if (t != null) {
            java.util.Iterator<String> it = t.keys();
            while (it.hasNext()) {
                String k = it.next();
                try {
                    r.put(k, t.get(k));
                } catch (Throwable ignored) {
                }
            }
        }
        return r;
    }

    private static JSONObject read(WebPage page, StringBuilder d) {
        return evalJson(page, READ_JS, 8_000L, d, "回执读取");
    }

    private static JSONObject evalJson(WebPage page, String js, long ms,
                                       StringBuilder d, String label) {
        String raw = page.eval(js, ms);
        if (raw == null || raw.isEmpty()) {
            d.append(label).append(": 没有回报\n");
            return null;
        }
        try {
            JSONObject o = new JSONObject(unquote(raw));
            d.append(label).append(": ").append(brief(raw)).append('\n');
            return o;
        } catch (Throwable e) {
            d.append(label).append(": 解析失败 ").append(brief(raw)).append('\n');
            return null;
        }
    }

    private static String join(org.json.JSONArray arr) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; arr != null && i < arr.length(); i++) {
            sb.append(arr.optString(i, "")).append('\n');
        }
        return sb.toString();
    }

    private static String tailOf(String value) {
        if (value == null) {
            return "";
        }
        String s = value.replace('\n', ' ').trim();
        return s.length() <= 400 ? s : s.substring(s.length() - 400);
    }

    private static String shortUrl(String url) {
        if (url == null) {
            return "";
        }
        int q = url.indexOf('?');
        String s = q > 0 ? url.substring(0, q) : url;
        return s.length() > 90 ? s.substring(0, 90) + "…" : s;
    }

    private static String brief(String raw) {
        String s = raw.replace('\n', ' ');
        return s.length() > 120 ? s.substring(0, 120) + "…" : s;
    }

    /**
     * Android 的 evaluateJavascript 会把结果再包一层 JSON 字符串：
     * 脚本 return JSON.stringify(...) 时，外层多一对引号和转义。
     */
    private static String unquote(String raw) {
        String s = raw.trim();
        if (s.length() < 2 || !s.startsWith("\"")) {
            return s;
        }
        try {
            return new JSONObject("{\"v\":" + s + "}").optString("v", s);
        } catch (Throwable t) {
            return s;
        }
    }

    /**
     * 页面点击脚本。返回：
     * <ul>
     *   <li>{@code {done,txt}} 已经有成功文案</li>
     *   <li>{@code {full,txt}} 已经抢光 / 已结束</li>
     *   <li>{@code {nav,txt}} 点的是底栏入口</li>
     *   <li>{@code {hit,txt}} 点的是抢券按钮</li>
     *   <li>{@code {none:1}} 什么都没找到</li>
     * </ul>
     */
    private static final String JS = """
            (function(){
              try{
                var vh=window.innerHeight||800;
                var SOFT=['福利','领券','优惠','权益','活动','商城','羊毛','签到','红包'];
                var HARD=['10点','10:00','抢券','立即抢','马上抢','立即领取','去领取','领券',
                          '秒杀','抢购','领取','抢','兑换','立即参与'];
                var BAD=['已领取','已抢光','已抢完','已结束','已使用','已完成','明日再来',
                         '暂无库存','未开始','已兑换','去使用'];
                function txt(e){var s=e.innerText||e.textContent||'';return String(s).replace(/\\s+/g,'')}
                function vis(e){
                  try{
                    var r=e.getBoundingClientRect();
                    if(r.width<8||r.height<8)return false;
                    var cs=getComputedStyle(e);
                    return cs.visibility!=='hidden'&&cs.display!=='none'&&parseFloat(cs.opacity||'1')>0.05;
                  }catch(x){return false}
                }
                function cls(e){try{return String(e.className||'')}catch(x){return ''}}
                function clickable(e){
                  var t=e.tagName;
                  if(t==='BUTTON'||t==='A')return true;
                  if(/btn|button|tab|item|link|cell|entry|prize|card/i.test(cls(e)))return true;
                  try{return getComputedStyle(e).cursor==='pointer'}catch(x){return false}
                }
                function bad(s){for(var i=0;i<BAD.length;i++){if(s.indexOf(BAD[i])>=0)return true}return false}
                function has(s,a){for(var i=0;i<a.length;i++){if(s.indexOf(a[i])>=0)return true}return false}
                function fire(target,x,y){
                  if(!target)return;
                  try{
                    var o1={bubbles:true,cancelable:true,view:window,clientX:x,clientY:y,pointerType:'touch',isPrimary:true};
                    target.dispatchEvent(new PointerEvent('pointerdown',o1));
                    target.dispatchEvent(new PointerEvent('pointerup',o1));
                  }catch(e){}
                  try{
                    var ts=new Touch({identifier:1,target:target,clientX:x,clientY:y,pageX:x,pageY:y});
                    target.dispatchEvent(new TouchEvent('touchstart',{bubbles:true,cancelable:true,touches:[ts],targetTouches:[ts],changedTouches:[ts]}));
                    target.dispatchEvent(new TouchEvent('touchend',{bubbles:true,cancelable:true,touches:[],targetTouches:[],changedTouches:[ts]}));
                  }catch(e){}
                  var names=['mousedown','mouseup','click'];
                  for(var m=0;m<names.length;m++){
                    try{target.dispatchEvent(new MouseEvent(names[m],{bubbles:true,cancelable:true,view:window,clientX:x,clientY:y}))}catch(e){}
                  }
                  try{if(typeof target.click==='function')target.click()}catch(e){}
                }
                var body=String(document.body?document.body.innerText:'');
                var win=body.match(/领取成功|已领取|成功领取|已抢到|抢券成功|恭喜获得/);
                if(win)return JSON.stringify({done:1,txt:win[0]});
                var dry=body.match(/已抢光|抢完了|已结束|活动结束|已领完/);
                if(dry)return JSON.stringify({full:1,txt:dry[0]});

                var all=document.querySelectorAll('div,span,a,button,li,p');
                var bar=[],btn=[];
                for(var i=0;i<all.length;i++){
                  var e=all[i];
                  if(!vis(e)||!clickable(e))continue;
                  var s=txt(e);
                  if(!s||s.length>18)continue;
                  if(bad(s))continue;
                  var r=e.getBoundingClientRect();
                  var one={e:e,s:s,a:r.width*r.height};
                  if(r.top>vh*0.6&&s.length<=6&&has(s,SOFT)){bar.push(one);continue}
                  if(has(s,HARD)){btn.push(one)}
                }
                function best(list){
                  var b=null;
                  for(var i=0;i<list.length;i++){if(!b||list[i].a<b.a)b=list[i]}
                  return b;
                }
                var t=best(btn),kind='hit';
                if(!t){t=best(bar);kind='nav'}
                if(!t)return JSON.stringify({none:1,url:String(location.href)});
                try{t.e.scrollIntoView({block:'center'})}catch(x){}
                var r2=t.e.getBoundingClientRect();
                var x=r2.left+r2.width/2,y=r2.top+r2.height/2;
                fire(t.e,x,y);
                try{
                  var top=document.elementFromPoint(x,y);
                  if(top&&top!==t.e&&!t.e.contains(top)&&!top.contains(t.e))fire(top,x,y);
                }catch(x){}
                var out={txt:t.s,url:String(location.href)};
                out[kind]=1;
                return JSON.stringify(out);
              }catch(e){return JSON.stringify({err:String(e&&e.message||e)})}
            })()
            """;

    /**
     * r13：把口令填进灵犀的聊天输入框。
     *
     * <p>分两步：这一脚本只负责「点 chip」或「填值」，发送交给 {@link #TAP_SEND_JS}。
     * 原因是 React/Vue 受控输入框在 input 事件后要一帧才启用发送键，同一个同步脚本里
     * 立刻找按钮经常扑空。填完等 600ms 再点，成功率高得多。
     *
     * <p>写值必须用原型上的原生 setter：直接 {@code el.value=} 只改 DOM，不改组件
     * 状态，页面会认为输入框还是空的。
     */
    private static final String SEND_JS = """
            (function(){
              try{
                var CMD = __CMD__;
                function txt(e){var s=e.innerText||e.textContent||e.value||'';return String(s).replace(/\\s+/g,' ').replace(/^\\s+|\\s+$/g,'')}
                function vis(e){
                  try{
                    var r=e.getBoundingClientRect();
                    if(r.width<8||r.height<8)return false;
                    var cs=getComputedStyle(e);
                    return cs.visibility!=='hidden'&&cs.display!=='none'&&parseFloat(cs.opacity||'1')>0.05;
                  }catch(x){return false}
                }
                function fire(t){
                  if(!t)return;
                  var r;
                  try{r=t.getBoundingClientRect()}catch(x){r={left:0,top:0,width:0,height:0}}
                  var x=r.left+r.width/2,y=r.top+r.height/2;
                  try{
                    var o={bubbles:true,cancelable:true,view:window,clientX:x,clientY:y,pointerType:'touch',isPrimary:true};
                    t.dispatchEvent(new PointerEvent('pointerdown',o));
                    t.dispatchEvent(new PointerEvent('pointerup',o));
                  }catch(e){}
                  try{
                    var ts=new Touch({identifier:1,target:t,clientX:x,clientY:y,pageX:x,pageY:y});
                    t.dispatchEvent(new TouchEvent('touchstart',{bubbles:true,cancelable:true,touches:[ts],targetTouches:[ts],changedTouches:[ts]}));
                    t.dispatchEvent(new TouchEvent('touchend',{bubbles:true,cancelable:true,touches:[],targetTouches:[],changedTouches:[ts]}));
                  }catch(e){}
                  var ns=['mousedown','mouseup','click'];
                  for(var i=0;i<ns.length;i++){
                    try{t.dispatchEvent(new MouseEvent(ns[i],{bubbles:true,cancelable:true,view:window,clientX:x,clientY:y}))}catch(e){}
                  }
                  try{if(typeof t.click==='function')t.click()}catch(e){}
                }
                var body=String(document.body?document.body.innerText:'');
                if(/领取成功|已领取|抢券成功|恭喜获得|已到账/.test(body))return JSON.stringify({done:1});
                var nodes=document.querySelectorAll('div,span,a,button,li,p');
                var chip=null,chipS='';
                for(var i=0;i<nodes.length;i++){
                  var e=nodes[i];
                  if(!vis(e))continue;
                  var s=txt(e).replace(/\\s+/g,'');
                  if(!s||s.length>24)continue;
                  if(s===CMD||(/口令/.test(s)&&/红包|领|抢/.test(s))){chip=e;chipS=s;break}
                }
                if(chip){fire(chip);return JSON.stringify({chip:chipS})}
                var ins=document.querySelectorAll('textarea,input,[contenteditable="true"]');
                var input=null,best=-1;
                for(var j=0;j<ins.length;j++){
                  var q=ins[j];
                  if(!vis(q))continue;
                  var tag=String(q.tagName||'').toLowerCase();
                  if(tag==='input'&&/button|submit|checkbox|radio|file|hidden/i.test(q.type||''))continue;
                  var r=q.getBoundingClientRect();
                  var hint=/咨询|输入|说|问|message|chat|send|input/i
                    .test(String(q.placeholder||'')+String(q.className||'')+String(q.id||''));
                  var score=r.bottom+(hint?10000:0)+r.width/1000;
                  if(score>best){best=score;input=q}
                }
                if(!input){
                  var fs=document.querySelectorAll('iframe');
                  var srcs=[];
                  for(var f=0;f<fs.length&&f<3;f++){srcs.push(String(fs[f].src||'').slice(0,120))}
                  return JSON.stringify({none:'input',frames:srcs.join(' | '),url:String(location.href)})
                }
                try{input.scrollIntoView({block:'center'})}catch(x){}
                try{input.focus()}catch(x){}
                try{
                  var low=String(input.tagName||'').toLowerCase();
                  if(low==='textarea'){
                    var st=Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype,'value').set;
                    st.call(input,CMD);
                  }else if(low==='input'){
                    var si=Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set;
                    si.call(input,CMD);
                  }else{
                    input.textContent=CMD;
                  }
                }catch(x){try{input.value=CMD}catch(y){}}
                try{
                  var iev=null;
                  try{iev=new InputEvent('input',{bubbles:true,inputType:'insertText',data:CMD})}catch(x){}
                  if(!iev)iev=new Event('input',{bubbles:true});
                  input.dispatchEvent(iev);
                  input.dispatchEvent(new Event('change',{bubbles:true}));
                }catch(x){}
                window.__cmcc_cmd_input=input;
                return JSON.stringify({
                  filled:1, tag:String(input.tagName||''),
                  id:String(input.id||''), cls:String(input.className||'').slice(0,80)
                });
              }catch(e){return JSON.stringify({err:String(e&&e.message||e)})}
            })()
            """;

    /**
     * r13：点发送。先找文字按钮（发送/send），再找输入框右侧 96px 内的图标，
     * 最后才回车 —— 移动端很多聊天框的 Enter 是换行，不是发送，不能当首选。
     */
    private static final String TAP_SEND_JS = """
            (function(){
              try{
                function txt(e){var s=e.innerText||e.textContent||e.value||'';return String(s).replace(/\\s+/g,' ').replace(/^\\s+|\\s+$/g,'')}
                function vis(e){
                  try{
                    var r=e.getBoundingClientRect();
                    if(r.width<8||r.height<8)return false;
                    var cs=getComputedStyle(e);
                    return cs.visibility!=='hidden'&&cs.display!=='none'&&parseFloat(cs.opacity||'1')>0.05;
                  }catch(x){return false}
                }
                function fire(t){
                  if(!t)return;
                  var r;
                  try{r=t.getBoundingClientRect()}catch(x){r={left:0,top:0,width:0,height:0}}
                  var x=r.left+r.width/2,y=r.top+r.height/2;
                  try{
                    var o={bubbles:true,cancelable:true,view:window,clientX:x,clientY:y,pointerType:'touch',isPrimary:true};
                    t.dispatchEvent(new PointerEvent('pointerdown',o));
                    t.dispatchEvent(new PointerEvent('pointerup',o));
                  }catch(e){}
                  try{
                    var ts=new Touch({identifier:1,target:t,clientX:x,clientY:y,pageX:x,pageY:y});
                    t.dispatchEvent(new TouchEvent('touchstart',{bubbles:true,cancelable:true,touches:[ts],targetTouches:[ts],changedTouches:[ts]}));
                    t.dispatchEvent(new TouchEvent('touchend',{bubbles:true,cancelable:true,touches:[],targetTouches:[],changedTouches:[ts]}));
                  }catch(e){}
                  var ns=['mousedown','mouseup','click'];
                  for(var i=0;i<ns.length;i++){
                    try{t.dispatchEvent(new MouseEvent(ns[i],{bubbles:true,cancelable:true,view:window,clientX:x,clientY:y}))}catch(e){}
                  }
                  try{if(typeof t.click==='function')t.click()}catch(e){}
                }
                var btn=null,label='';
                var all=document.querySelectorAll('button,[role="button"],div,span,a');
                for(var i=0;i<all.length;i++){
                  var b=all[i];
                  if(!vis(b))continue;
                  var s=txt(b).replace(/\\s+/g,'');
                  if(!s||s.length>8)continue;
                  if(s==='发送'||s==='发送消息'||/^send$/i.test(s)){btn=b;label=s;break}
                }
                if(btn){fire(btn);return JSON.stringify({button:label})}
                var input=window.__cmcc_cmd_input;
                if(!input||!document.contains(input)){
                  var ins=document.querySelectorAll('textarea,input,[contenteditable="true"]');
                  var best=-1;
                  for(var j=0;j<ins.length;j++){
                    var q=ins[j];
                    if(!vis(q))continue;
                    var tag=String(q.tagName||'').toLowerCase();
                    if(tag==='input'&&/button|submit|checkbox|radio|file|hidden/i.test(q.type||''))continue;
                    var qr=q.getBoundingClientRect();
                    var hint=/咨询|输入|说|问|message|chat|send|input/i
                      .test(String(q.placeholder||'')+String(q.className||'')+String(q.id||''));
                    var score=qr.bottom+(hint?10000:0);
                    if(score>best){best=score;input=q}
                  }
                }
                if(input){
                  try{
                    var ir=input.getBoundingClientRect();
                    var near=null,nearHint=null,nearD=1e9,hintD=1e9;
                    var cs=document.querySelectorAll('button,div,span,img,svg,i,a');
                    for(var m=0;m<cs.length;m++){
                      var c=cs[m];
                      if(!vis(c))continue;
                      var cr=c.getBoundingClientRect();
                      if(cr.width<18||cr.height<18||cr.width>96||cr.height>96)continue;
                      if(cr.left<ir.right-16)continue;
                      if(cr.top+cr.height<ir.top-16||cr.top>ir.bottom+24)continue;
                      var d=Math.abs(cr.left-ir.right)+Math.abs((cr.top+cr.height/2)-(ir.top+ir.height/2));
                      var hint=/send|submit|arrow|paper|plane|发送/i.test(String(c.id||'')+String(c.className||'')+String(c.getAttribute('aria-label')||''));
                      if(hint&&d<hintD){hintD=d;nearHint=c}
                      if(d<nearD){nearD=d;near=c}
                    }
                    if(nearHint){fire(nearHint);return JSON.stringify({icon:1,hint:1})}
                    if(near){fire(near);return JSON.stringify({icon:1,d:Math.round(nearD)})}
                  }catch(x){}
                  try{
                    var o={bubbles:true,cancelable:true,key:'Enter',code:'Enter',keyCode:13,which:13};
                    input.dispatchEvent(new KeyboardEvent('keydown',o));
                    input.dispatchEvent(new KeyboardEvent('keypress',o));
                    input.dispatchEvent(new KeyboardEvent('keyup',o));
                    return JSON.stringify({enter:1})
                  }catch(x){}
                }
                return JSON.stringify({noSend:1})
              }catch(e){return JSON.stringify({err:String(e&&e.message||e)})}
            })()
            """;

    private static final String READ_JS = """
            (function(){
              try{
                var b=String(document.body?document.body.innerText:'');
                var out=[];
                var ns=document.querySelectorAll('[class*="msg"],[class*="message"],[class*="bubble"],[class*="chat"],[class*="content"]');
                var start=Math.max(0,ns.length-50);
                for(var i=start;i<ns.length;i++){
                  var e=ns[i];
                  if(!e)continue;
                  try{
                    var r=e.getBoundingClientRect();
                    if(r.width<8||r.height<8)continue;
                  }catch(x){continue}
                  var s=String(e.innerText||e.textContent||'').replace(/\\s+/g,' ').replace(/^\\s+|\\s+$/g,'');
                  if(!s||s.length>120)continue;
                  out.push(s);
                  if(out.length>=12)break;
                }
                return JSON.stringify({tail:b.slice(-3000),bubbles:out,url:String(location.href)});
              }catch(e){return JSON.stringify({err:String(e&&e.message||e)})}
            })()
            """;
}
