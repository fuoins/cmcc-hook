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
 * <p>请求全部在豆苗真页内发出，优先复用页面自己的 axios 实例（自带 phone /
 * wasmParams 拦截器），兜底 fetch 也带上从页面 XHR 抓到的 phone 头 —— 与
 * {@link CanvasTask} 同一套做法，Java 层不拼风控参数。判定只认服务端复查，
 * 绝不因为「请求发出去了」就记成功。通道不可用时如实回报，由 MiaoTask 回退
 * 原 DOM 点击链路，功能不回退。
 */
final class SeedTasks {

    private SeedTasks() {
    }

    /** 上游 cmcc_seed 常量：任务清单 ID / 活动 ID / 页面短标识 / 渠道。 */
    static final int TASK_LIST_ID = 55;
    static final int ACTIVITY_ID = 17692;
    static final String PAGE_KEY = "2026dmycj";
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
        String reason = "";

        @Override
        public String toString() {
            return "清单=" + available + " 待办=" + todo + " 上报=" + accepted
                    + " 确认=" + confirmed + " 待刷新=" + still
                    + " 跳过=" + skipped + " 失败=" + failed + " 阳光值+" + gain;
        }
    }

    /**
     * 在豆苗真页里跑一遍服务端通道。最长约 50 秒；任何异常都返回
     * {@code available=false}，由调用方回退 DOM 链路。
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
            long deadline = System.currentTimeMillis() + 50_000L;
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
                out.reason = "脚本 50 秒没回报";
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
     * <p>刻意不使用正则里的反斜杠转义，避免 Java text block 双重转义踩坑。
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

              // 页面自己的 axios 实例带 phone / wasmParams 拦截器，优先复用
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
              // 兜底 fetch 需要的 phone 头：从页面自己的 XHR 里原样记下来
              function hookPhone(){
                try{
                  var sr=XMLHttpRequest.prototype.setRequestHeader;
                  XMLHttpRequest.prototype.setRequestHeader=function(k,v){
                    try{ if(String(k).toLowerCase()==='phone') window.__cmccPhone=String(v) }catch(e){}
                    return sr.apply(this,arguments);
                  };
                }catch(e){}
              }
              async function post(path,body){
                var err='';
                for(var i=0;i<AX.length;i++){
                  try{
                    var ret=AX[i].i.post(path,body);
                    if(ret&&isFn(ret.then)) ret=await ret;
                    return {ok:true,data:(ret&&ret.data!==undefined)?ret.data:ret};
                  }catch(e){ err=String(e) }
                }
                try{
                  var ph=''; try{ ph=window.__cmccPhone||'' }catch(e){}
                  var h={'Content-Type':'application/json;charset=utf-8'};
                  if(ph) h.phone=ph;
                  var f=await fetch(path,{method:'POST',credentials:'include',headers:h,body:JSON.stringify(body)});
                  var t=await f.text();
                  return {ok:true,data:t};
                }catch(e){ return {ok:false,err:(err?err+' / ':'')+String(e)} }
              }
              function obj(r){
                var d=(r&&r.data!==undefined)?r.data:r;
                try{ return typeof d==='string'?JSON.parse(d):d }catch(e){ return null }
              }
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
              async function list(){
                var r=await post(PT+'getGrowthTaskList',{taskListId:LIST_ID});
                var o=obj(r);
                if(!okCode(o)) return null;
                var d=o.data;
                // 清单必须是数组；拿到对象说明接口变了，按「通道不可用」如实回报，
                // 由 Java 侧回退 DOM，绝不把空对象当成「任务已全部完成」。
                if(!d||typeof d.length!=='number') return null;
                return d;
              }
              async function main(){
                hookPhone(); findAxios();
                line('通道: axios '+AX.length+' 个 / phone '+(window.__cmccPhone?'已取':'待页面请求时取'));
                var t0=Date.now();
                var tasks=await list();
                if(tasks===null){
                  done({stage:'no-channel',reason:'getGrowthTaskList 未返回 resultCode=0'});
                  return;
                }
                var todo=[];
                for(var i=0;i<tasks.length;i++){ if(Number(tasks[i].isFinish)===0) todo.push(tasks[i]) }
                line('任务清单: 共 '+tasks.length+' 个，待办 '+todo.length+' 个');
                var accepted=[], skipped=0, failed=0;
                for(var i=0;i<todo.length;i++){
                  if(Date.now()-t0>38000){ line('总预算到，停止后续任务'); break }
                  var t=todo[i], tid=t.taskId, tt=Number(t.taskType);
                  var title=String(t.taskMainTitle||('任务'+tid)).slice(0,40);
                  if(tt===3){ line('· '+title+'（需真实办理/订购，跳过）'); skipped++; continue }
                  if(tt===6){ line('· '+title+'（需真实好友助力，跳过）'); skipped++; continue }
                  if(tt!==1&&tt!==2&&tt!==4&&tt!==5){ line('· '+title+'（未知类型 '+tt+'，跳过）'); skipped++; continue }
                  var vurl=tt===2?PAGE_KEY:visitUrlOf(t.unfinishRedirectUrl);
                  if(!vurl){ line('· '+title+'（缺目标页，跳过）'); skipped++; continue }
                  var path=tt===2?'share':'visit';
                  var body={channelCode:CHANNEL,visitUrl:vurl,taskId:tid};
                  var r=await post(PT+path,body), o=obj(r), c=codeOf(o), d=o?o.data:null;
                  if(d&&typeof d==='string'&&d.indexOf('频繁')>=0){
                    await wait(3000);
                    r=await post(PT+path,body); o=obj(r); c=codeOf(o); d=o?o.data:null;
                  }
                  if(okCode(o)&&(d===null||d===true||d===undefined||d==='')){
                    line('✓ '+title+' 已上报（'+path+'）'); accepted.push(tid);
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
                  var hr=await post(PC+'getSlgCreditsHomePage',{activityId:ACT_ID});
                  var ho=obj(hr), recs=(ho&&ho.data&&ho.data.unreceivedCreditsVOList)||[];
                  line('待领阳光值: '+recs.length+' 条');
                  for(var k=0;k<recs.length&&k<12;k++){
                    var rec=recs[k];
                    var gr=await post(PC+'getGrowthBubble',{activityId:ACT_ID,
                      creditsRecordId:rec.recordId,referer:PAGE_KEY,channelNo:CHANNEL});
                    var go=obj(gr);
                    if(go&&okCode(go)){ gain+=Number(rec.showNum||0); }
                    await wait(400);
                  }
                  if(gain>0) line('领取阳光值 +'+gain);
                }catch(e){ line('领阳光值跳过: '+cut(e,60)) }
                done({stage:'ok',todo:todo.length,accepted:accepted.length,confirmed:confirmed,
                      skipped:skipped,failed:failed,still:still,gain:gain});
              }
              main().catch(function(e){ done({stage:'error',msg:String(e)}) });
            })()
            """;
}
