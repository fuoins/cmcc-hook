package com.cmcchook;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 流量余量「一键离屏查询」—— v4.0.2。
 *
 * <p>严格守红线：<b>不解密接口、不拼签名、不伪造票据、不发任何业务请求</b>。
 *
 * <p>v4.0 的做法（离屏 WebView 加载余量查询页读 Vuex）被 16:49 日志证明不成立：
 * 离屏页是全新 WebView 实例，读不到前台真页的<b>内存 Vuex</b>；裸 URL 又缺 App 现场
 * 参数，页面 {@code loadState} 一直 {@code loading}；探针把「items 空」判 {@code empty}
 * 后 {@code fetch} 立刻 break（日志只轮询 1 轮 / 1.8s），于是永远 0 条。
 *
 * <p>16:49 / 14:29 两份日志给出了真正稳的数据源：中国移动<b>首页</b>
 * {@code home.html} 把余量接口 {@code getNewPlanRemainQry} 的解密结果明文缓存进
 * <b>localStorage</b>：
 * <ul>
 *   <li>{@code BH/newPlanRemainQry/getNewPlanRemainQry} → {@code flowSceneData[]}
 *       （flowtype 0/1/2 = 通用/定向/其他，unit 03=MB，含 flowSumNum/flowUsdNum/
 *       flowRemainNum）+ {@code voiceSceneData[]}（通话，分钟）；</li>
 *   <li>{@code BN/realFeeQuery/getRealFee} → {@code realBalanceFee}（话费，元）；</li>
 *   <li>3317.96MB ÷ 1024 = 3.24GB，与首页卡片「3.24 GB 通用流量剩余」、
 *       14:29 余量页 DOM「国内通用流量 剩余 3.24 GB / 定向流量 剩余 10.81 GB /
 *       国内其他流量 剩余 562 MB」逐项吻合。</li>
 * </ul>
 *
 * <p>localStorage 是<b>按 origin 隔离、同 App 内跨 WebView 共享、磁盘持久化</b>的，
 * 只要离屏页落在首页同一个 origin（{@code h.app.coc.10086.cn}），就能读到 App
 * 已经写好的明文缓存 —— <b>不需要手动点进「余量查询 / 流量」页</b>，也不需要等页面
 * loadState。页面自己负责请求/解密/写缓存，模块只读现成结果。
 *
 * <p>读不到时不瞎编：把命中的 key、缓存原文、单位编码、DOM 明文、探针原文
 * 全写进「详细原始返回」日志，方便按证据核对。
 */
final class FlowTask {

    /** 目标进程 → 模块 App 的流量数据广播。 */
    static final String ACTION_FLOW_DATA = "com.cmcchook.action.FLOW_DATA";

    /**
     * 首页同源地址：余量明文缓存写在 {@code h.app.coc.10086.cn} 这个 origin 的
     * localStorage 里。必须与首页同域，否则 localStorage 读不到。
     * 刻意不带 {@code channelId/yx} 等 App 现场参数（硬编码等于伪造现场数据）。
     */
    static final String ENTRY_URL =
            "https://h.app.coc.10086.cn/cmcc-app/app-pages/home.html";

    /** 页面加载 + 缓存就绪的最长等待。缓存通常秒读，留足余量给页面自己刷新。 */
    private static final long DEADLINE_MS = 30_000L;
    private static final long POLL_GAP_MS = 1_200L;

    private FlowTask() {
    }

    /**
     * 读首页 localStorage 里的明文余量缓存，产出与 {@link FlowView} 兼容的
     * {@code items:[{name,cat,used,remain,total}]}（数值统一带 MB 单位，FlowView 按
     * 1024 进制换算），另带 {@code fee/voice} 供 UI 展示话费与通话。
     *
     * <p>JS 里避免正则（历史上踩过 Java 吃 {@code \s} 转义的坑），用纯字符串判断。
     */
    private static final String PROBE_JS =
            "(function(){" +
            "try{" +
            "var r={state:'loading',items:[],source:'',cacheKey:'',fee:'',voice:''," +
            "  units:'',rawCache:'',domText:'',url:location.href,title:document.title,error:''};" +
            "try{var t=document.body?document.body.innerText:'';r.domText=(t||'').slice(0,8000);}catch(e){}" +
            "function toAmt(v,u){" +
            "  if(v==null||v==='')return '';" +
            "  var n=Number(v);if(!isFinite(n)||n<0)return '';" +
            "  u=String(u||'').toUpperCase();" +
            "  if(u==='04'||u==='GB')return n+'GB';" +
            "  if(u==='KB')return n+'KB';" +
            "  if(u==='TB')return n+'TB';" +
            "  return n+'MB';" +
            "}" +
            "var CAT={'0':'国内通用流量','1':'定向流量','2':'国内其他流量','3':'WLAN流量'};" +
            "var KEYS=['BH/newPlanRemainQry/getNewPlanRemainQry','newPlanRemainQry','getNewPlanRemainQry'];" +
            "var raw='',hit='';" +
            "try{" +
            "  for(var i=0;i<KEYS.length;i++){" +
            "    var v=localStorage.getItem(KEYS[i]);" +
            "    if(v&&v.indexOf('flowSceneData')>=0){raw=v;hit=KEYS[i];break;}" +
            "  }" +
            "}catch(e){}" +
            "if(!raw){" +
            "  try{" +
            "    for(var j=0;j<localStorage.length;j++){" +
            "      var k=localStorage.key(j);var v2=localStorage.getItem(k);" +
            "      if(v2&&v2.indexOf('flowSceneData')>=0){raw=v2;hit=k;break;}" +
            "    }" +
            "  }catch(e){}" +
            "}" +
            "r.cacheKey=hit;r.rawCache=String(raw||'').slice(0,4000);" +
            "if(raw){" +
            "  try{" +
            "    var d=JSON.parse(raw);" +
            "    var fs=d.flowSceneData||d.flowData||[];" +
            "    if(!Array.isArray(fs))fs=[fs];" +
            "    var un=[];" +
            "    for(var a=0;a<fs.length;a++){" +
            "      var g=fs[a]||{};" +
            "      var ty=String(g.flowtype!=null?g.flowtype:g.flowType);" +
            "      var cat=CAT[ty]||('流量'+ty);" +
            "      var u=g.unit||'';un.push(ty+':'+u);" +
            "      var used=toAmt(g.flowUsdNum,u),remain=toAmt(g.flowRemainNum,u),total=toAmt(g.flowSumNum,u);" +
            "      if(used||remain||total)r.items.push({name:cat,cat:cat,used:used,remain:remain,total:total});" +
            "    }" +
            "    r.units=un.join(',');" +
            "    var vs=d.voiceSceneData||[];if(!Array.isArray(vs))vs=[vs];" +
            "    var vsum=0,vok=false;" +
            "    for(var b=0;b<vs.length;b++){var n=Number(vs[b]&&vs[b].voiceRemainNum);if(isFinite(n)){vsum+=n;vok=true;}}" +
            "    if(vok)r.voice=vsum+' 分钟';" +
            "    r.source='localStorage(首页同源明文缓存)';" +
            "  }catch(e){r.error='缓存解析失败: '+e;}" +
            "}" +
            "try{" +
            "  var f=localStorage.getItem('BN/realFeeQuery/getRealFee')||localStorage.getItem('getRealFee');" +
            "  if(f){var fj=JSON.parse(f);if(fj.realBalanceFee!=null)r.fee=fj.realBalanceFee+' 元';}" +
            "}catch(e){}" +
            "r.state=r.items.length?'ready':'loading';" +
            "return JSON.stringify(r);" +
            "}catch(e){return JSON.stringify({state:'error',items:[],error:'PROBE 异常: '+e,domText:''});}" +
            "})()";

    /** 模块点「一键查询」→ TaskReceiver 起独立线程调这里。不进任务队列、不记今日状态。 */
    static void fetch() {
        long start = System.currentTimeMillis();
        Object wv = null;
        StringBuilder diary = new StringBuilder();
        try {
            Context ctx = HookLog.context();
            if (ctx == null) {
                send(error("拿不到中国移动进程上下文（App 可能没在运行）", diary));
                return;
            }
            diary.append("入口(首页同源, 不带 App 现场参数): ").append(ENTRY_URL).append('\n');
            diary.append("方式: 目标进程离屏 WebView 落在首页同 origin，只读页面自己写进 "
                    + "localStorage 的明文余量缓存；不解密接口、不拼签名、不发业务请求、"
                    + "不需要手动进余量查询/流量页。\n");

            // 每次查询都重建离屏页，避免复用上一次已切走/已销毁的页面。
            OffscreenWeb.close();
            wv = OffscreenWeb.open(ENTRY_URL, 12_000L);
            if (wv == null) {
                diary.append("结果: 无法创建离屏 WebView（App 未运行或 WebView 内核不可用）。\n");
                send(error("无法创建离屏 WebView（请先打开一次中国移动 App）", diary));
                return;
            }
            diary.append("离屏页已创建: ").append(wv.getClass().getName()).append('\n');

            JSONObject result = null;
            String lastRaw = "";
            String lastNote = "";
            int rounds = 0;
            long deadline = System.currentTimeMillis() + DEADLINE_MS;
            while (System.currentTimeMillis() < deadline) {
                try {
                    Thread.sleep(POLL_GAP_MS);
                } catch (InterruptedException e) {
                    break;
                }
                Object cur = OffscreenWeb.current();
                if (cur == null) {
                    lastNote = "离屏页已失效";
                    break;
                }
                rounds++;
                String raw;
                try {
                    raw = JsInjector.evalValue(cur, PROBE_JS);
                } catch (Throwable e) {
                    lastNote = "evalValue 异常: " + e;
                    continue;
                }
                if (raw == null || !raw.startsWith("{")) {
                    lastNote = "探针无返回（第 " + rounds + " 轮）";
                    continue;
                }
                lastRaw = raw;
                JSONObject o;
                try {
                    o = new JSONObject(raw);
                } catch (Throwable e) {
                    lastNote = "探针返回非 JSON（第 " + rounds + " 轮）";
                    continue;
                }
                String st = o.optString("state", "loading");
                // v4.0.2：只有拿到数据(ready)或明确 error/login 才收工；
                // loading/empty 一律继续轮询 —— v4.0 就是在 empty 上立刻 break，
                // 页面还没把缓存写好就判 0 条。
                if ("ready".equals(st) || "error".equals(st) || "login".equals(st)) {
                    result = o;
                    break;
                }
                lastNote = "state=" + st + " · 命中key=" + o.optString("cacheKey")
                        + " · 条目=" + countItems(o) + "（第 " + rounds + " 轮）";
            }
            if (result == null && lastRaw.startsWith("{")) {
                // 超时也没崩：把最后一轮探针结果交给模块端（可能 state=loading，
                // 但 domText 有明文，FlowView 会从 DOM 兜底提取）。
                try {
                    result = new JSONObject(lastRaw);
                } catch (Throwable ignored) {
                }
            }
            if (result == null) {
                result = new JSONObject();
                result.put("state", "error");
                result.put("error", "30 秒内没读到首页余量缓存"
                        + (lastNote.isEmpty() ? "" : "：" + lastNote));
            }
            result.put("at", System.currentTimeMillis());

            int items = countItems(result);
            diary.append("轮询轮数: ").append(rounds)
                    .append(" · 耗时 ").append(System.currentTimeMillis() - start).append("ms\n");
            diary.append("页面: ").append(result.optString("url", ""))
                    .append(" · ").append(result.optString("title", "")).append('\n');
            diary.append("数据源: ").append(result.optString("source", ""))
                    .append(" · 命中缓存key: ").append(result.optString("cacheKey", ""))
                    .append(" · 单位编码: ").append(result.optString("units", "")).append('\n');
            diary.append("话费: ").append(result.optString("fee", ""))
                    .append(" · 通话剩余: ").append(result.optString("voice", "")).append('\n');
            diary.append("判定: state=").append(result.optString("state", ""))
                    .append(" · 结构化条目 ").append(items).append(" 条\n");
            String info = result.optString("error", "");
            if (!info.isEmpty()) {
                diary.append("探针消息: ").append(info).append('\n');
            }
            String rc = result.optString("rawCache", "");
            diary.append("余量缓存原文(前 1500 字):\n")
                    .append(rc.length() > 1500 ? rc.substring(0, 1500) + "…" : rc).append('\n');
            String dt = result.optString("domText", "");
            diary.append("DOM 明文(兜底用, 前 800 字):\n")
                    .append(dt.length() > 800 ? dt.substring(0, 800) + "…" : dt).append('\n');
            diary.append("探针原始返回(前 3000 字):\n")
                    .append(lastRaw.length() > 3000 ? lastRaw.substring(0, 3000) + "…" : lastRaw);

            HookLog.log("FLOW", "流量余量一键离屏查询 · state=" + result.optString("state", "")
                    + " · " + items + " 条", diary.toString());
            send(result);
        } catch (Throwable t) {
            HookLog.error("流量余量一键离屏查询异常", t);
            diary.append("异常: ").append(t).append('\n');
            send(error("读取异常：" + t, diary));
        } finally {
            try {
                OffscreenWeb.detach(wv);
            } catch (Throwable ignored) {
            }
            try {
                OffscreenWeb.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private static int countItems(JSONObject o) {
        JSONArray a = o == null ? null : o.optJSONArray("items");
        return a == null ? 0 : a.length();
    }

    private static JSONObject error(String msg, StringBuilder diary) {
        try {
            JSONObject o = new JSONObject();
            o.put("state", "error");
            o.put("error", msg);
            o.put("at", System.currentTimeMillis());
            HookLog.log("FLOW", "流量余量一键离屏查询 · 失败",
                    diary.append("失败: ").append(msg).toString());
            return o;
        } catch (Throwable t) {
            return new JSONObject();
        }
    }

    /** 显式广播回模块 App（静态 LogReceiver，FLAG 保证进程没起来也能拉起）。 */
    private static void send(JSONObject o) {
        Context ctx = HookLog.context();
        if (ctx == null) {
            return;
        }
        try {
            Intent it = new Intent(ACTION_FLOW_DATA);
            it.setComponent(new ComponentName(
                    HookLog.MODULE_PACKAGE, "com.cmcchook.LogReceiver"));
            it.setPackage(HookLog.MODULE_PACKAGE);
            it.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES
                    | Intent.FLAG_RECEIVER_FOREGROUND);
            it.putExtra("json", o.toString());
            it.putExtra("uid", AccountScope.uid());
            it.putExtra("phone", SsoLogin.maskedPhone());
            ctx.sendBroadcast(it);
        } catch (Throwable t) {
            HookLog.logcat("flow broadcast failed: " + t);
        }
    }
}
