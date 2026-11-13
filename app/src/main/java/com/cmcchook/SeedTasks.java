package com.cmcchook;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * r19：AI 豆苗养成记（coc3 画布）服务端任务直报通道。
 *
 * <p>来源：上游 {@code cmcc_seed.SeedAPI}（{@code yupaiLy/cmcc-auto-checkin}）。
 * 之前 {@link MiaoTask} 一直在 DOM 里找 {@code IMG.content-btn} 逐个点：r16 改成
 * 按结构定位、r18 加了阳光值前后对比，仍反复出现「点了没跳 / 阳光值 160→160」。
 * 根因是那三个任务（看移动爱购直播 / 浏览热销榜单 / 浏览精选爆品）本质是服务端
 * 登记的浏览任务，页面点击只是触发一次上报，按钮是不是图片、有没有跳出去都不重要。
 *
 * <p>这里改成与上游一致的四步：
 * <ol>
 *   <li>{@code getGrowthTaskList} 拉服务端清单（{@code isFinish=0} 为待办）；</li>
 *   <li>按 {@code taskType} 分流：1/4/5 走 {@code visit}（visitUrl 取自
 *       {@code unfinishRedirectUrl} 里的 {@code /online/} 短路径），2 走 {@code share}；
 *       3 真实订购、6 真实好友助力按黑名单跳过，其余未知类型也跳过；</li>
 *   <li>统一复查 {@code getGrowthTaskList}，只认 {@code isFinish=1}；</li>
 *   <li>{@code getSlgCreditsHomePage} + {@code getGrowthBubble} 领待领阳光值。</li>
 * </ol>
 *
 * <p>「黑名单制」也来自上游 {@code cmcc_wed.MANUAL_SKIP}：服务端动态新增的任务 ID
 * 不需要维护白名单，只排除不能代做的类型，其余全部自动尝试；每项单独记结果，
 * 一个失败不影响其它。
 *
 * <p>请求全部在豆苗真页内发出。r19 优先复用页面 axios（自带 phone / wasmParams
 * 拦截器），但 r22 实测发现**这个拦截器会把 body 覆盖成当前页面上下文**：876 条
 * 录制流里 {@code channelCode} 从未出现 P00000011939（全是秒杀页的 P00000153130），
 * {@code visitUrl} 也被改成 2026dmycj，直播（agzbj）和精选爆品（bpzq）因此要么被
 * 答「该url不在配置范围内」，要么返回 {@code resultCode=0/data=null} 却根本没记账。
 * 现在改成**先原生 fetch 原样发 body**（只带 phone / x-token 这类会话级头，不带
 * 每次现算的 x-sign / wasmParams），业务码不对才回退页面 axios，并回读 XHR 实际
 * 发出的 body，被改写就在明细里点名；{@code channelCode} 也改成按任务跳转地址取。
 * 判定只认服务端复查 {@code isFinish=1}，绝不因为「请求发出去了」就记成功。通道不可用时如实回报，由 MiaoTask 回退
 * 原 DOM 点击链路，功能不回退。
 */
final class SeedTasks {

    private SeedTasks() {
    }

    /** 上游 cmcc_seed 常量：任务清单 ID / 活动 ID / 页面短标识 / 渠道。 */
    static final int TASK_LIST_ID = 55;
    static final int ACTIVITY_ID = 17692;
    static final String PAGE_KEY = "2026dmycj";
    /**
     * 兜底渠道。r22 起不再直接用它：优先取任务 {@code unfinishRedirectUrl} 里的
     * channelCode，其次取当前页 URL 的 channelCode。11-13 的录制流里这个常量
     * 一次都没出现在实际请求中。
     */
    static final String DEFAULT_CHANNEL = "P00000011939";

    static final String P_TASK = "/coc3/coc3-market-activity/cmpp-manage/task/";
    static final String P_CREDIT = "/coc3/coc3-market-activity/api/activity/slgCredits/";

    /** 任务类型黑名单：3 需真实办理/订购，6 需真实好友助力（上游 SKIP_REASONS）。 */
    static final int TYPE_ORDER = 3;
    static final int TYPE_INVITE = 6;

    static final class Outcome {
        /** 通道是否真的拿到了服务端清单。 */
        boolean available;
        int todo;
        int accepted;
        /** 复查 isFinish=1 的数量，唯一可记成功的证据。 */
        int confirmed;
        int skipped;
        int failed;
        int still;
        int gain;
        /** r22：直发（fetch，body 保真）成功的条数。 */
        int direct;
        /** r22：回退页面 axios 才成功的条数（含被拦截器改写 body 的情况）。 */
        int fallback;
        String reason = "";

        @Override
        public String toString() {
            return "清单=" + available + " 待办=" + todo + " 上报=" + accepted
                    + " 确认=" + confirmed + " 待刷新=" + still
                    + " 跳过=" + skipped + " 失败=" + failed + " 阳光值+" + gain
                    + " 直发=" + direct + " 回退=" + fallback;
        }
    }

    /**
     * 在豆苗真页里跑一遍服务端通道。
     *
     * <p>r22：预算改成按「可自动任务数」动态（每个 8 秒 + 12 秒固定开销，封顶
     * 180 秒）。r19 固定 38 秒只够发 3 条 visit，剩下十几个任务永远轮不到；
     * 而 26 个任务里真正可自动的只有 7 个，按 7 个算约 68 秒，既不会漏也不会
     * 白占一键总时长。Java 侧硬上限 195 秒，防脚本 await 挂死。
     *
     * <p>任何异常都返回 {@code available=false}，由调用方回退 DOM 链路。
     */
    static Outcome run(WebPage page, StringBuilder detail) {
        Outcome out = new Outcome();
        if (page == null || page.raw() == null) {
            out.reason = "真页没起来";
            detail.append("coc3 服务端通道: 跳过（真页没起来）\n");
            return out;
        }
        try {
            page.eval(SEED_START, 6_000L);
            long deadline = System.currentTimeMillis() + 195_000L;
            String raw = null;
            String lastBeat = "";
            while (System.currentTimeMillis() < deadline) {
                String value = page.eval(POLL, 3_000L);
                if (value != null && !value.isEmpty()) {
                    boolean beat = false;
                    try {
                        JSONObject one = new JSONObject(value);
                        beat = one.optInt("beat", 0) == 1;
                        if (beat) {
                            String last = one.optString("last", "");
                            if (!last.isEmpty() && !last.equals(lastBeat)) {
                                lastBeat = last;
                                detail.append("  coc3: ").append(brief(last)).append('\n');
                            }
                        }
                    } catch (Throwable ignored) {
                        // 不是 JSON 就当最终回报
                    }
                    if (!beat) {
                        raw = value;
                        break;
                    }
                }
                sleep(400L);
            }
            if (raw == null) {
                out.reason = "脚本 195 秒没回报";
                detail.append("coc3 服务端通道: 超时，回退 DOM 点击链路\n");
                return out;
            }
            JSONObject o = new JSONObject(raw);
            String stage = o.optString("stage", "");
            if (!"ok".equals(stage)) {
                out.reason = o.optString("reason", o.optString("msg", stage));
                detail.append("coc3 服务端通道: 不可用（").append(brief(out.reason))
                        .append("），回退 DOM 点击链路\n");
                return out;
            }
            out.available = true;
            out.todo = o.optInt("todo", 0);
            out.accepted = o.optInt("accepted", 0);
            out.confirmed = o.optInt("confirmed", 0);
            out.skipped = o.optInt("skipped", 0);
            out.failed = o.optInt("failed", 0);
            out.still = o.optInt("still", 0);
            out.gain = o.optInt("gain", 0);
            out.direct = o.optInt("direct", 0);
            out.fallback = o.optInt("fallback", 0);
            JSONArray lines = o.optJSONArray("lines");
            if (lines != null) {
                for (int i = 0; i < lines.length(); i++) {
                    detail.append("  coc3: ").append(brief(lines.optString(i, ""))).append('\n');
                }
            }
            detail.append("coc3 服务端通道: ").append(out).append('\n');
        } catch (Throwable e) {
            out.reason = "异常 " + e;
            detail.append("coc3 服务端通道: 异常 ").append(brief(String.valueOf(e)))
                    .append("，回退 DOM 点击链路\n");
        }
        return out;
    }

    /**
     * 从 {@code unfinishRedirectUrl} 取 {@code /online/} 后面的短路径，作为 visitUrl。
     * 抽成纯函数供 {@link ActivityCatalog#selfCheck()} 离线自验。
     */
    static String targetKey(String url) {
        if (url == null || url.isEmpty()) {
            return "";
        }
        int at = url.indexOf("/online/");
        if (at < 0) {
            return "";
        }
        String rest = url.substring(at + "/online/".length());
        int end = rest.length();
        int q = rest.indexOf('?');
        int h = rest.indexOf('#');
        if (q >= 0 && q < end) {
            end = q;
        }
        if (h >= 0 && h < end) {
            end = h;
        }
        String head = rest.substring(0, end).split("/")[0];
        return head == null ? "" : head.trim();
    }

    /**
     * 按 taskType 决定动作：返回 {@code visit} / {@code share} / {@code skip} /
     * {@code no-target} / {@code unknown}。纯函数，供离线自验。
     */
    static String actionFor(int type, boolean hasTarget) {
        if (type == TYPE_ORDER || type == TYPE_INVITE) {
            return "skip";
        }
        if (type == 2) {
            return "share";
        }
        if (type == 1 || type == 4 || type == 5) {
            return hasTarget ? "visit" : "no-target";
        }
        return "unknown";
    }

    private static String brief(String s) {
        if (s == null) {
            return "";
        }
        String v = s.replace('\n', ' ').trim();
        return v.length() <= 200 ? v : v.substring(0, 200) + "…";
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }

    private static final String POLL =
            "(function(){var v=window.__cmcc_seed;return v?String(v):'';})()";

    /**
     * 注入脚本：异步跑完写 {@code window.__cmcc_seed}，Java 侧轮询取值。
     * 心跳期间写 {@code {beat:1,last:...}}，最终写完整结果（无 beat）。
     *
     * <p>r22 三处关键改动（依据 11-13 11:21 录制流的实证）：
     * <ol>
     *   <li><b>上报走原生 fetch</b>：页面 axios 的请求拦截器会把 body 覆盖成
     *       当前页面上下文（{@code channelCode} 从 P00000011939 变成 P00000153130、
     *       {@code visitUrl} 从 bpzq 变成 2026dmycj），服务端因此答「该url不在配置
     *       范围内」。现在先用 fetch 原样发 body，业务码不对才回退页面 axios，
     *       并回读 XHR 实际发出的 body，被改写就在明细里点名。</li>
     *   <li><b>预算动态</b>：{@code 12s + 可自动任务数 × 8s}，封顶 180 秒。
     *       固定 38 秒时 20 个待办只发得出 3 条。</li>
     *   <li><b>连续直发失败降级</b>：2 次直发都被业务拒绝就整体切回页面 axios，
     *       避免每个任务白撞一发。</li>
     * </ol>
     *
     * <p>刻意不使用反斜杠，避免 Java text block 双重转义踩坑。
     */
    private static final String SEED_START = """
            (function(){
              var ID='__cmcc_seed';
              window[ID]='';
              var L=[];
              function line(s){ L.push(String(s)); try{ window[ID]=JSON.stringify({beat:1,last:String(s),n:L.length}) }catch(e){} }
              function done(o){ try{ o.lines=L; window[ID]=JSON.stringify(o) }catch(e){ window[ID]='{"stage":"crash"}' } }
              function isFn(x){ return typeof x==='function' }
              function wait(ms){ return new Promise(function(r){ setTimeout(r,ms) }) }
              function cut(s,n){ s=String(s==null?'':s); return s.length>n?s.slice(0,n):s }
              var PT='/coc3/coc3-market-activity/cmpp-manage/task/';
              var PC='/coc3/coc3-market-activity/api/activity/slgCredits/';
              var LIST_ID=55, ACT_ID=17692, PAGE_KEY='2026dmycj';
              var CHANNEL='P00000011939';
              var REQ_MS=15000;

              // 页面 XHR 上抓风控头，同时记下「实际发出去的 body」
              function hookXhr(){
                // r24：记下真实 URL 与全部请求头名。12:25 那轮只报「-102 参数错误」，
                // 没有 URL 也没法判断是不是打错了实例/拼错了 baseURL。
                try{
                  var op=XMLHttpRequest.prototype.open;
                  XMLHttpRequest.prototype.open=function(m,u){
                    try{ window.__cmccLastUrl=(typeof u==='string')?u:String(u) }catch(e){}
                    return op.apply(this,arguments);
                  };
                }catch(e){}
                try{
                  var sr=XMLHttpRequest.prototype.setRequestHeader;
                  XMLHttpRequest.prototype.setRequestHeader=function(k,v){
                    try{
                      if(!window.__cmccHdrKeys) window.__cmccHdrKeys={};
                      window.__cmccHdrKeys[k]=String(v).length;
                      var lk=String(k).toLowerCase();
                      // 只留会话级头。x-sign / x-time / x-nonce / wasmParams 是
                      // 每次请求现算的，拿上一条的会直接验签失败，宁可不要。
                      if(lk==='phone'||lk==='x-token'||lk==='appsession'){
                        if(!window.__cmccHdrs) window.__cmccHdrs={};
                        window.__cmccHdrs[k]=String(v);
                      }
                    }catch(e){}
                    return sr.apply(this,arguments);
                  };
                }catch(e){}
                try{
                  var ss=XMLHttpRequest.prototype.send;
                  XMLHttpRequest.prototype.send=function(b){
                    try{ window.__cmccLastBody=(typeof b==='string')?b:'' }catch(e){}
                    return ss.apply(this,arguments);
                  };
                }catch(e){}
              }
              function hdrKeys(){
                try{
                  var h=window.__cmccHdrKeys||{}, out=[];
                  for(var k in h) out.push(k+'('+h[k]+')');
                  return out.join(',');
                }catch(e){ return '' }
              }
              function diag(tag){
                line('    ['+tag+'] url='+cut(window.__cmccLastUrl||'?',90)
                     +' body='+cut(window.__cmccLastBody||'?',90)
                     +' hdr='+cut(hdrKeys()||'?',90));
              }
              function hdrs(){
                var out={'Content-Type':'application/json;charset=utf-8'};
                try{
                  var h=window.__cmccHdrs||{};
                  for(var k in h){ if(String(k).toLowerCase()!=='content-type') out[k]=h[k] }
                }catch(e){}
                return out;
              }
              // r23：11-13 原始流实证 —— coc3 业务接口的会话头只有 phone 一个
              //（36 位，Accept/Content-Type 之外再无 x-token/x-sign）。
              // 没抓到 phone 就直发，等于空手敲门，白白触发「连续 2 次被拒整体降级」。
              function hasPhone(){
                try{
                  var h=window.__cmccHdrs||{};
                  for(var k in h){ if(String(k).toLowerCase()==='phone') return String(h[k]).length>0 }
                }catch(e){}
                return false;
              }
              function timeout(p,ms){
                return new Promise(function(res,rej){
                  var t=setTimeout(function(){ rej(new Error('req timeout')) },ms);
                  p.then(function(v){ clearTimeout(t); res(v) },
                         function(e){ clearTimeout(t); rej(e) });
                });
              }
              function parsed(t){ try{ return typeof t==='string'?JSON.parse(t):t }catch(e){ return null } }

              // 直发：body 原样出门，绝不过页面拦截器
              async function fetchJson(path,body){
                var f=window.fetch;
                if(!isFn(f)) return {ok:false,err:'页面没有 fetch'};
                try{
                  var r=await timeout(f(path,{method:'POST',credentials:'include',
                    headers:hdrs(),body:JSON.stringify(body)}),REQ_MS);
                  var t=await r.text();
                  return {ok:true,data:t,status:r.status};
                }catch(e){ return {ok:false,err:String(e)} }
              }

              var AX=[];
              function isAxios(o){ try{ return o && isFn(o.post) && isFn(o.get) && o.interceptors && o.interceptors.request }catch(e){ return false } }
              function addAx(o,src){
                if(!isAxios(o)) return;
                for(var i=0;i<AX.length;i++){ if(AX[i].i===o) return }
                AX.push({i:o,src:src});
              }
              function findAxios(){
                try{ for(var k in window){ try{ addAx(window[k],'window.'+k) }catch(e){} } }catch(e){}
                try{
                  var gj=window.webpackJsonp||window.webpackChunk;
                  if(gj&&isFn(gj.push)){
                    var tok='__cmcc_seed_probe__';
                    var mods={}; mods[tok]=function(m,e,r){ window.__cmccSeedReq=r };
                    gj.push([[tok],mods,[[tok]]]);
                    var R=window.__cmccSeedReq||null;
                    if(R){
                      var ids=Object.keys(R.c||{});
                      for(var i=0;i<ids.length;i++){
                        var ex=R.c[ids[i]]&&R.c[ids[i]].exports;
                        if(!ex) continue;
                        addAx(ex,'exports['+ids[i]+']');
                        try{
                          var ks=Object.getOwnPropertyNames(ex);
                          for(var j=0;j<ks.length;j++){ try{ addAx(ex[ks[j]],'exports['+ids[i]+'].'+ks[j]) }catch(e){} }
                        }catch(e){}
                      }
                    }
                  }
                }catch(e){}
              }

              // 页面 axios：风控头齐，但 body 可能被拦截器改写。
              // r24：遍历全部实例，取第一个业务码通过的。r23 只要第一个实例不抛异常
              // 就直接 return（-102 也算「正常响应」），后面两个从来没被试过；
              // 12:25 实测第一个返回 -102「参数错误」，很可能不是页面主实例。
              async function postAxios(path,body){
                var err='', last=null, tried=[];
                for(var i=0;i<AX.length;i++){
                  try{
                    window.__cmccLastBody='';
                    var ret=AX[i].i.post(path,body);
                    if(ret&&isFn(ret.then)) ret=await timeout(ret,REQ_MS);
                    // r23：区分「axios 原始响应」和「页面拦截器已解包的业务对象」。
                    // 旧写法只看 ret.data 是否存在，遇到已解包的实例会把业务 data
                    //（成功时是 null）当成整个响应，于是一路判成业务码 -999。
                    var rawAxios=!!(ret&&ret.data!==undefined&&
                        (ret.status!==undefined||ret.config!==undefined||ret.headers!==undefined));
                    var data=rawAxios?ret.data:ret;
                    var o=parsed(data), c=codeOf(o);
                    tried.push('#'+i+':'+c);
                    var one={ok:true,via:'axios',data:data,sent:window.__cmccLastBody||'',
                             tries:tried.join(' '),err:''};
                    if(okCode(o)) return one;
                    last=one;
                  }catch(e){ err=String(e); tried.push('#'+i+':异常') }
                }
                if(last) return last;
                try{
                  var r=await fetchJson(path,body);
                  return {ok:r.ok,via:'fetch',data:r.data,sent:'',tries:tried.join(' '),
                          err:(err?err+' / ':'')+(r.err||'')};
                }catch(e){ return {ok:false,via:'fetch',err:(err?err+' / ':'')+String(e)} }
              }
              function obj(r){ var d=(r&&r.data!==undefined)?r.data:r; return parsed(d) }
              function codeOf(o){
                if(!o) return -999;
                if(o.resultCode!=null) return Number(o.resultCode);
                if(o.code!=null) return Number(o.code);
                return -999;
              }
              // coc3 域两套成功码：cmcc_seed 用 resultCode=0，画布抽奖用 code=200。
              // 两个都认，但都不认「HTTP 200 却业务失败」。
              function okCode(o){ var c=codeOf(o); return c===0||c===200 }
              // 不用正则：从 /online/ 之后取到第一个 ? # 或 / 为止
              function visitUrlOf(u){
                u=String(u||''); var at=u.indexOf('/online/');
                if(at<0) return '';
                var rest=u.slice(at+8), end=rest.length;
                var q=rest.indexOf('?'), h=rest.indexOf('#'), s=rest.indexOf('/');
                if(q>=0&&q<end)end=q; if(h>=0&&h<end)end=h; if(s>=0&&s<end)end=s;
                return rest.slice(0,end).trim();
              }
              // 从 URL 里取 channelCode
              function channelOf(u){
                u=String(u||''); var k='channelCode=';
                var at=u.indexOf(k);
                if(at<0) return '';
                var rest=u.slice(at+k.length), end=rest.length;
                var amp=rest.indexOf('&'), h=rest.indexOf('#');
                if(amp>=0&&amp<end)end=amp; if(h>=0&&h<end)end=h;
                return rest.slice(0,end).trim();
              }
              function pageChannel(){
                try{ return channelOf(String(location.href||'')) }catch(e){ return '' }
              }
              // r23：清单请求失败必须留证。11-13 11:53 那轮只留下一句
              // 「getGrowthTaskList 未返回 resultCode=0」，而页面自己随后崩在
              // data[0]（Cannot read properties of undefined），说明真实情况
              // 很可能是「resultCode=0 但 data 为 null」，被这句文案吞掉了。
              // 现在四类失败分开报，各带响应前 140 字。
              var listWhy=[];
              function short(r){ try{ return cut((typeof r==='string')?r:JSON.stringify(r),90) }catch(e){ return cut(String(r),90) } }
              async function listOnce(viaDirect){
                var path=PT+'getGrowthTaskList', body={taskListId:LIST_ID}, r, src;
                if(viaDirect){
                  if(!hasPhone()) return {err:'直发跳过：还没抓到 phone 头'};
                  r=await fetchJson(path,body); src='直发';
                }else{
                  r=await postAxios(path,body); src=(r.via==='fetch')?'页面fetch':'页面axios';
                }
                if(!r.ok){ diag(src); return {err:src+' 调用异常: '+short(r.err||'')}; }
                if(r.data===null||r.data===undefined||r.data===''){
                  diag(src); return {err:src+' 返回空（响应体没有内容）'};
                }
                var o=parsed(r.data);
                if(o===null){ diag(src); return {err:src+' 返回不是 JSON: '+short(r.data)}; }
                // 有的 axios 实例响应拦截器已经解过包，ret 直接就是数组
                if(o instanceof Array) return {tasks:o,src:src+'（已解包）'};
                var c=codeOf(o);
                if(!okCode(o)){
                  diag(src);
                  return {err:src+' 业务码 '+c+' ['+short(r.tries||'')+']: '+short(r.data)};
                }
                var d=o.data;
                // 清单必须是数组；拿到 null 说明服务端这次没给清单，按「通道不可用」
                // 如实回报，由 Java 侧回退 DOM，绝不把空值当成「任务已全部完成」。
                if(!d||typeof d.length!=='number'){
                  diag(src);
                  return {err:src+' 业务码 0 但 data 不是数组: '+short(r.data)};
                }
                return {tasks:d,src:src};
              }
              async function list(){
                var a=await listOnce(false);
                if(a.tasks){ if(a.src.indexOf('已解包')>=0) line('清单: '+a.src); return a.tasks }
                listWhy.push(a.err);
                var b=await listOnce(true);
                if(b.tasks){ listWhy.push('清单改直发取回'); line('清单: 页面 axios 取不到，改直发取回'); return b.tasks }
                if(b.err) listWhy.push(b.err);
                return null;
              }

              // 上报：先直发（body 保真），业务拒绝再回退页面 axios 并回读实际 body
              var directTried=0, directFail=0, useDirect=true, directOk=0, fbOk=0, noPhoneHint=false;
              async function postTask(path,body){
                var want=String(body.visitUrl||'');
                if(useDirect&&!hasPhone()){
                  // r23：没 phone 头直发必被拒，先让页面 axios 发一次把头带出来
                  if(!noPhoneHint){ noPhoneHint=true; line('    直发暂缓：还没抓到 phone 头，先走页面 axios 打一发') }
                }else if(useDirect){
                  directTried++;
                  var fr=await fetchJson(path,body), fo=obj(fr);
                  if(okCode(fo)){ directOk++; return {o:fo,mode:'direct'} }
                  directFail++;
                  line('    直发未通过（'+codeOf(fo)+'），改走页面 axios');
                  if(directFail>=2&&directOk===0){
                    useDirect=false;
                    line('    连续 2 次直发被拒，后续任务改走页面 axios');
                  }
                }
                var a=await postAxios(path,body), ao=obj(a);
                var sent=String(a.sent||'');
                var rewritten=(want&&sent&&sent.indexOf(want)<0);
                if(rewritten) line('    ⚠ 页面拦截器改写了 body（visitUrl 不含 '+want+'）');
                if(okCode(ao)) fbOk++;
                return {o:ao,mode:rewritten?'fallback-rewritten':'fallback'};
              }

              async function main(){
                hookXhr(); findAxios();
                line('通道: axios '+AX.length+' 个 / 风控头 '+(window.__cmccHdrs?'已取':'待页面请求时取'));
                var t0=Date.now();
                var tasks=await list();
                if(tasks===null){
                  done({stage:'no-channel',reason:listWhy.length?listWhy.join(' | '):'getGrowthTaskList 没拿到清单'});
                  return;
                }
                var todo=[], actionable=0;
                for(var i=0;i<tasks.length;i++){
                  var t1=tasks[i];
                  if(Number(t1.isFinish)!==0) continue;
                  todo.push(t1);
                  var ty=Number(t1.taskType);
                  if(ty!==3&&ty!==6&&(ty===1||ty===2||ty===4||ty===5)) actionable++;
                }
                var budget=Math.min(180000,12000+actionable*8000);
                line('任务清单: 共 '+tasks.length+' 个，待办 '+todo.length
                     +' 个，可自动 '+actionable+' 个');
                line('预算: '+Math.round(budget/1000)+' 秒');
                var accepted=[], skipped=0, failed=0;
                for(var i=0;i<todo.length;i++){
                  if(Date.now()-t0>budget){ line('预算到，停止后续任务（剩余 '+(todo.length-i)+' 个）'); break }
                  var t=todo[i], tid=t.taskId, tt=Number(t.taskType);
                  var title=String(t.taskMainTitle||('任务'+tid)).slice(0,40);
                  if(tt===3){ line('· '+title+'（需真实办理/订购，跳过）'); skipped++; continue }
                  if(tt===6){ line('· '+title+'（需真实好友助力，跳过）'); skipped++; continue }
                  if(tt!==1&&tt!==2&&tt!==4&&tt!==5){ line('· '+title+'（未知类型 '+tt+'，跳过）'); skipped++; continue }
                  var vurl=tt===2?PAGE_KEY:visitUrlOf(t.unfinishRedirectUrl);
                  if(!vurl){ line('· '+title+'（缺目标页，跳过）'); skipped++; continue }
                  var path=tt===2?'share':'visit';
                  // r22：渠道按任务自己的跳转地址取。11-13 录制流里唯一真正生效的那次
                  // visit 用的是 P00000153856（任务跳转页渠道），而页面上发出去的全是
                  // 秒杀页的 P00000153130；硬编码的 P00000011939 一次都没出现在请求里。
                  var ch=channelOf(t.unfinishRedirectUrl)||pageChannel()||CHANNEL;
                  var body={channelCode:ch,visitUrl:vurl,taskId:tid};
                  var pr=await postTask(PT+path,body), o=pr.o, c=codeOf(o), d=o?o.data:null;
                  if(d&&typeof d==='string'&&d.indexOf('频繁')>=0){
                    await wait(2500);
                    pr=await postTask(PT+path,body); o=pr.o; c=codeOf(o); d=o?o.data:null;
                  }
                  if(okCode(o)&&(d===null||d===true||d===undefined||d==='')){
                    // resultCode=0 只代表「服务端收到了」，不代表任务被记账
                    // （录制里 3108 传错 visitUrl 也返回 0/null，isFinish 仍是 0），
                    // 所以这里只记「已上报」，成败一律以复查 isFinish=1 为准。
                    line('✓ '+title+' 已上报（'+path+'/'+pr.mode+'/渠道 '+ch+'）'); accepted.push(tid);
                  }else if(okCode(o)){
                    line('· '+title+'：'+cut(d,60));
                  }else{
                    line('✗ '+title+'：'+c+' '+cut(o&&o.msg,60)); failed++;
                  }
                  await wait(900);
                }
                var confirmed=0, still=0;
                if(accepted.length){
                  await wait(2000);
                  var again=await list();
                  if(again){
                    for(var j=0;j<again.length;j++){
                      var x=again[j];
                      if(accepted.indexOf(x.taskId)<0) continue;
                      if(Number(x.isFinish)===1) confirmed++; else still++;
                    }
                  }
                  line('复查: 服务端确认 '+confirmed+' 个，状态待刷新 '+still+' 个');
                }
                var gain=0;
                try{
                  var hr=await postAxios(PC+'getSlgCreditsHomePage',{activityId:ACT_ID});
                  var ho=obj(hr), recs=(ho&&ho.data&&ho.data.unreceivedCreditsVOList)||[];
                  line('待领阳光值: '+recs.length+' 条');
                  for(var k=0;k<recs.length&&k<12;k++){
                    var rec=recs[k];
                    var gr=await postAxios(PC+'getGrowthBubble',{activityId:ACT_ID,
                      creditsRecordId:rec.recordId,referer:PAGE_KEY,channelNo:CHANNEL});
                    var go=obj(gr);
                    if(go&&okCode(go)){ gain+=Number(rec.showNum||0); }
                    await wait(400);
                  }
                  if(gain>0) line('领取阳光值 +'+gain);
                }catch(e){ line('领阳光值跳过: '+cut(e,60)) }
                line('上报方式: 直发 '+directOk+' 条 / 回退 '+fbOk+' 条');
                done({stage:'ok',todo:todo.length,accepted:accepted.length,confirmed:confirmed,
                      skipped:skipped,failed:failed,still:still,gain:gain,
                      direct:directOk,fallback:fbOk});
              }
              main().catch(function(e){ done({stage:'error',msg:String(e)}) });
            })()
            """;
}
