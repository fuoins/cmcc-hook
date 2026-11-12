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

    private LingxiGrab() {
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
        try {
            return loop(page, d);
        } finally {
            prepared = null;
        }
    }

    private static TaskRunner.Result loop(WebPage page, StringBuilder d) {
        long end = System.currentTimeMillis() + WINDOW_MS;
        int round = 0;
        int hits = 0;
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
                return TaskRunner.Result.ok(text, d.toString(), text);
            }
            if (o.has("full")) {
                d.append("第 ").append(round).append(" 轮: 「")
                        .append(o.optString("txt")).append("」\n");
                String text = "灵犀抢券 · 已经没了（" + o.optString("txt") + "）";
                return TaskRunner.Result.idle(text, d.toString(), text);
            }
            String txt = o.optString("txt", "");
            if (txt.isEmpty()) {
                if (round <= 3 || round % 40 == 0) {
                    d.append("第 ").append(round).append(" 轮: 页面上没找到可点的抢券入口，继续等\n");
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
        }
        d.append("共 ").append(round).append(" 轮，命中 ").append(hits).append(" 次\n");
        d.append("最后点: ").append(last.isEmpty() ? "无" : last).append('\n');
        String text = "灵犀抢券 · 没抢到（" + (last.isEmpty() ? "页面上没找到入口" : last) + "）";
        return TaskRunner.Result.idle(text, d.toString(), text);
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
}
