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

    /**
     * 余量查询页（新版容器页）。v4.0.8 起作为主数据源。
     *
     * <p>依据 4.0.7+flowprobe 两轮真机实证：纯离屏、裸地址（不带 channelId/yx、
     * 不回灌 Cookie）即可打开该页并正常 mount Vue，5 秒内读到
     * {@code planRemainQryInfo.newPlanRemainQry.planRemianFlowListRes} 全量树；
     * 同一份日志里首页 localStorage 的 {@code flowSceneData[]} 只有 3 项汇总，
     * 这就是「流量信息不全」的根源。
     */
    private static final String MARGIN_URL =
            "https://wx.10086.cn/cmcc-service/app-pages/main/marginQuery";

    /** 余量页允许停留的域；页面内部跳转 10086.cn 各子域都放行，广告/下载页照旧拦截。 */
    private static final String[] MARGIN_HOSTS = {"wx.10086.cn", "10086.cn", "app.10086.cn"};

    /** 全量树等待上限。实测 5 秒内出树，留 18 秒兜住网络慢的情况。 */
    private static final long TREE_DEADLINE_MS = 18_000L;
    private static final long TREE_POLL_MS = 1_000L;

    /** 页面加载 + 缓存就绪的最长等待。缓存通常秒读，留足余量给页面自己刷新。 */
    private static final long DEADLINE_MS = 30_000L;
    private static final long POLL_GAP_MS = 1_200L;

    /**
     * 余量页 Vuex 全量树读取脚本。
     *
     * <p>树结构（兼容 {@code planRemianFlowListRes} 为对象或数组两种线上形态）：
     * <pre>
     * planRemianFlowListRes
     *  └ planRemianFlowRes[]            flowtype 0通用/1定向/2其他/3WLAN
     *     └ planRemianFlowInfoRes[]     套餐：planName / validDate / expireDate
     *        └ flowInfoRes[]            流量叶子：flowName / totalRes / usedRes / remainRes / unit
     * </pre>
     * 单位 {@code unit}：03=MB、04=GB，转换成 FlowView 认识的「数字+单位」字符串。
     * 共享流量取 {@code userShareInfo.shareFlowOverallData} 的主卡/副卡合计。
     * 只读页面内存，不发请求、不解密、不拼签名。
     */
    private static final String TREE_JS = """
            (function(){
              var r={state:'loading',items:[],source:'',url:location.href,title:document.title,
                     error:'',plans:0,flows:0,share:'',stateKeys:'',rawTree:'',domText:''};
              function L(x){return !x?[]:(Array.isArray(x)?x:[x])}
              try{var bt=document.body?document.body.innerText:'';r.domText=(bt||'').slice(0,6000);}catch(e){}
              var st=null;
              try{
                var nodes=[document.body].concat(Array.prototype.slice.call(document.querySelectorAll('div'),0,2500));
                for(var i=0;i<nodes.length;i++){
                  var vm=nodes[i]&&nodes[i].__vue__,h=0;
                  while(vm&&!vm.$store&&vm.$parent&&h++<12)vm=vm.$parent;
                  if(vm&&vm.$store){st=vm.$store.state;break;}
                }
              }catch(e){}
              if(!st){
                r.state=/login|autoLogin|onclicklogin/i.test(location.href)?'login':'loading';
                r.error='页面 Vue 未就绪';
                return JSON.stringify(r);
              }
              try{var ks=[];for(var k in st){if(ks.length<24)ks.push(k)}r.stateKeys=ks.join(',');}catch(e){}
              var mod=st.planRemainQryInfo||{};
              var q=mod.newPlanRemainQry||{};
              var tree=q.planRemianFlowListRes||q.planRemianFlowRes;
              var load=String(mod.newPlanRemainQryLoadState||'');
              if(!tree){
                r.state=(load&&load!=='loadCompleted')?'loading':'empty';
                r.error='未拿到全量树 loadState='+load+' err='+String(mod.newPlanRemainQryErrorText||'');
                return JSON.stringify(r);
              }
              try{r.rawTree=JSON.stringify(tree).slice(0,3000);}catch(e){}
              function amt(v,u){
                if(v==null||v==='')return '';
                var n=Number(v);
                if(!isFinite(n)||n<0)return '';
                u=String(u||'').toUpperCase();
                if(u==='04'||u==='GB')return n+'GB';
                if(u==='KB')return n+'KB';
                if(u==='TB')return n+'TB';
                return n+'MB';
              }
              var CAT={'0':'国内通用流量','1':'定向流量','2':'国内其他流量','3':'WLAN流量'};
              function push(nm,cat,usd,rem,tot,u){
                var a=amt(usd,u),b=amt(rem,u),c=amt(tot,u);
                if(!a&&!b&&!c)return;
                r.items.push({name:String(nm||cat),cat:cat,used:a,remain:b,total:c});
              }
              var plans=0,flows=0;
              try{
                L(tree).forEach(function(sec){
                  L(sec&&sec.planRemianFlowRes).forEach(function(g){
                    var ty=String(g.flowtype),cat=CAT[ty]||('流量'+ty),s0=r.items.length;
                    L(g.planRemianFlowInfoRes).forEach(function(p){
                      plans++;
                      var fl=L(p.flowInfoRes),u=p.unit||g.unit||sec.unit;
                      if(fl.length){
                        fl.forEach(function(f){
                          flows++;
                          var nm=p.planName||f.flowName||cat;
                          if(f.flowName&&p.planName&&f.flowName!==p.planName)nm=nm+' · '+f.flowName;
                          push(nm,cat,f.usedRes,f.remainRes,f.totalRes,f.unit||u);
                        });
                      }else{
                        push(p.planName||cat,cat,p.usedRes,p.remainRes,p.totalRes,u);
                      }
                    });
                    if(r.items.length===s0){
                      push(cat+'（汇总）',cat,g.flowUsdNum,g.flowRemainNum,g.flowSumNum,g.unit||sec.unit);
                    }
                  });
                });
              }catch(e){r.error='树解析异常: '+e;}
              r.plans=plans;r.flows=flows;
              try{
                var sh=st.userShareInfo||{},ov=sh.shareFlowOverallData||{};
                r.share=String(sh.shareUserInfoLoadState||'');
                if(sh.shareUserInfoLoadState==='loadCompleted'){
                  var m=ov.mainShareFlowOverallData,s=ov.subShareFlowOverallData;
                  if(m)push('主卡共享流量','共享流量',m.overallUsedData,m.overallRemainData,m.overallTotalData,'');
                  if(s)push('副卡共享流量','共享流量',s.overallUsedData,s.overallRemainData,s.overallTotalData,'');
                }
              }catch(e){}
              r.source='Vuex 余量查询页全量树';
              r.state=r.items.length?'ready':'empty';
              return JSON.stringify(r);
            })()
            """;

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
        StringBuilder diary = new StringBuilder();
        diary.append("数据源: 余量查询页 Vuex 全量树（纯离屏、裸地址、不回灌 Cookie）\n");
        diary.append("入口: ").append(MARGIN_URL).append('\n');
        diary.append("方式: 离屏 WebView 打开余量页，只读页面自己请求并解密后写进 Vuex 的\n"
                + "planRemianFlowListRes 全量树；不发请求、不解密、不拼签名、不自动登录。\n");

        JSONObject tree = readTree(diary);
        if (tree != null && countItems(tree) > 0) {
            try {
                tree.put("at", System.currentTimeMillis());
            } catch (Throwable ignored) {
            }
            diary.append("全量树: 套餐 ").append(tree.optInt("plans"))
                    .append(" · 流量叶子 ").append(tree.optInt("flows"))
                    .append(" · 共享流量状态 ").append(tree.optString("share", "无")).append('\n');
            diary.append("条目 ").append(countItems(tree)).append(" 条 · 总耗时 ")
                    .append(System.currentTimeMillis() - start).append("ms\n");
            String rt = tree.optString("rawTree", "");
            diary.append("全量树原文(前 2000 字):\n")
                    .append(rt.length() > 2000 ? rt.substring(0, 2000) + "…" : rt).append('\n');
            HookLog.log("FLOW", "流量余量 · 全量树 " + countItems(tree) + " 条", diary.toString());
            send(tree);
            return;
        }

        String st = tree == null ? "" : tree.optString("state", "");
        String why = tree == null ? "离屏余量页没有返回"
                : ("state=" + st + " · " + tree.optString("error", ""));
        diary.append("全量树未拿到: ").append(why).append('\n');
        if ("login".equals(st)) {
            send(error("余量查询页跳到了登录页：请先在「中国移动」App 里登录后再试"
                    + "（模块不会自动登录、不点一键登录）", diary));
            return;
        }
        diary.append("→ 兜底: 首页 localStorage 明文缓存\n");
        fetchHomeCache(diary, start);
    }

    /**
     * 开离屏余量页并轮询 Vuex 全量树。返回最后一轮探针结果（可能为 {@code loading} /
     * {@code login} / {@code empty}），完全拿不到页时返回 {@code null}。
     */
    private static JSONObject readTree(StringBuilder diary) {
        WebPage page = null;
        JSONObject last = null;
        int rounds = 0;
        long start = System.currentTimeMillis();
        try {
            page = WebPage.openProbe(MARGIN_URL, 9_000L, MARGIN_HOSTS, null, null);
            if (page == null) {
                diary.append("余量页: 离屏 WebView 建不起来（App 未运行或 WebView 内核不可用）。\n");
                return null;
            }
            long deadline = System.currentTimeMillis() + TREE_DEADLINE_MS;
            while (System.currentTimeMillis() < deadline) {
                rounds++;
                String raw = null;
                try {
                    raw = page.eval(TREE_JS, 5_000L);
                } catch (Throwable e) {
                    diary.append("第 ").append(rounds).append(" 轮 eval 异常: ").append(e).append('\n');
                }
                if (raw != null && raw.startsWith("{")) {
                    try {
                        JSONObject o = new JSONObject(raw);
                        last = o;
                        String st = o.optString("state", "loading");
                        // ready/login/error 收工；loading/empty 继续等页面挂载与请求。
                        if ("ready".equals(st) || "login".equals(st) || "error".equals(st)) {
                            break;
                        }
                    } catch (Throwable ignored) {
                    }
                }
                try {
                    Thread.sleep(TREE_POLL_MS);
                } catch (InterruptedException e) {
                    break;
                }
            }
        } catch (Throwable t) {
            diary.append("余量页异常: ").append(t).append('\n');
        } finally {
            if (page != null) {
                try {
                    page.close();
                } catch (Throwable ignored) {
                }
            }
        }
        diary.append("余量页轮询: ").append(rounds).append(" 轮 · ")
                .append(System.currentTimeMillis() - start).append("ms\n");
        return last;
    }

    /**
     * 兜底：首页同源 localStorage 明文缓存（v4.0.2 的老数据源，只有 3 项汇总）。
     * 全量树拿不到时才会走到这里，保证不会比旧版更差。
     */
    private static void fetchHomeCache(StringBuilder diary, long start) {
        Object wv = null;
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

    /** 显式广播回模块 App（静态 LogReceiver，FLAG 保证进程没起来也能拉起）。包级可见，供流量探针回传诊断结果。 */
    static void send(JSONObject o) {
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
