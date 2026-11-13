package com.cmcchook;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Collections;
import java.util.Map;

/**
 * 流量余量「刷新」—— v4.0.2，v4.1.0 起与任务内完全同一条真页路径。
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
 *
 * <p>v4.10.0-r9：{@code flowtype=2}「国内其他流量」逐包过滤 —— 已用尽
 * （{@code remainRes<=0} 或已用≥总量）与已失效（{@code expireFlag!=0} 或
 * {@code expireDate/flowExpireDate} 已过）的包不进入 items，分类卡的总量/已用
 * 只累加剩余的有效包，不再用分类汇总兜底（汇总含失效量）。过滤条数与原因
 * 写入「详细原始返回」，便于核对。其他分类维持原样。
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

    /**
     * 构建指纹。日志第一行会打出来，用来一眼分辨手机里跑的是哪一版 dex ——
     * 前几轮反复出现「装了新包、日志还是旧行为」，靠 DIAG 字段猜太费劲。
     */
    static final String BUILD = "v4.20.0-r28-20261113";

    /** 真页登记的有效期：10 分钟内打开过的余量页才直读，太久的内容已经过时。 */
    private static final long REAL_TTL_MS = 10L * 60_000L;

    /**
     * 本轮自己用 App 容器打开的余量页。跑完必须关掉 —— 用户明确要求
     * 「每个任务跑完之后把页面关了，不然会攒一堆」。
     */
    private static volatile Object openedView;
    private static volatile String openedUrl;

    /**
     * 用户亲手打开的余量页（前台真页）。
     *
     * <p>11-10 18:12 日志实证：同一个 App 里，真页 {@code marginQuery?channelId=...&yx=...}
     * 能发出 10 个余量接口并全部 200，而模块离屏页用裸地址打开、19 秒内
     * <b>一个请求都没发</b>，Vuex 停在 {@code loadInit}。差别就在「真页会话 + App 现场参数」。
     * 所以只要真页还开着，直接读它的 Vuex，比离屏稳得多。
     */
    private static volatile Object realView;
    private static volatile String realUrl;
    private static volatile long realAt;
    /**
     * 上一条真页轮询是不是「一次都没回调」。
     *
     * <p>v4.2.1：区分两种空手而归 —— 「页面活着但 Vuex 还没就绪」（state=loading，值得等/重载）
     * 和「页面已经不响应」（零回调，多半是 WebView 被关掉或 detach，再等也没用，必须重开）。
     */
    private static volatile boolean realUnresponsive;

    /**
     * 离屏任务进行中的截止时间。期间 {@link #noteReal} 一律丢弃 —— 那时候页面回调里
     * 出现的 marginQuery 就是模块自己开的离屏页（本轮开始会给它带参数，光看 URL 分不出来）。
     */
    private static volatile long probeBusyUntil;

    /** 全量树等待上限。实测 5 秒内出树，留 18 秒兜住网络慢的情况。 */
    private static final long TREE_DEADLINE_MS = 18_000L;
    private static final long TREE_POLL_MS = 1_000L;

    /**
     * 首页缓存兜底的等待上限。
     *
     * <p>v4.2.1：原来是 30 秒轮询 + 12 秒建页，11-11 09:53 那次实测整段花了 <b>91 秒</b>
     * （离屏 WebView 建页时卡住），用户看着就是「刷新卡死」。现在整段封顶 15 秒，
     * 到点没读到就如实报错，别让人干等。
     */
    private static final long DEADLINE_MS = 15_000L;
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
                     error:'',plans:0,flows:0,share:'',stateKeys:'',rawTree:'',domText:'',
                     cutN:0,cutDry:0,cutDead:0,cutList:''};
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
              var OTHER='2',cutN=0,cutDry=0,cutDead=0,cutList=[];
              var NOW=Date.now();
              function num(v){
                if(v==null||v==='')return NaN;
                var n=Number(v);
                return isFinite(n)?n:NaN;
              }
              function ts(v){
                if(!v)return 0;
                var s=String(v).replace(/-/g,'/').replace('T',' ');
                var dot=s.indexOf('.');
                if(dot>0)s=s.substring(0,dot);
                var t=new Date(s).getTime();
                return isFinite(t)?t:0;
              }
              // 「已失效」：过期标记非 0，或到期时间已过（套餐/叶子任一）。
              function dead(o){
                if(!o)return false;
                var f=String(o.expireFlag==null?'':o.expireFlag);
                if(f!==''&&f!=='0'&&f!=='00')return true;
                var ex=ts(o.flowExpireDate||o.expireDate||o.invalidDate);
                return ex>0&&ex<NOW;
              }
              // 「已用尽」：剩余为 0（且确实有量），或已用 >= 总量。
              function dry(o){
                if(!o)return false;
                var t=num(o.totalRes!=null?o.totalRes:o.flowSumNum);
                var u=num(o.usedRes!=null?o.usedRes:o.flowUsdNum);
                var m=num(o.remainRes!=null?o.remainRes:o.flowRemainNum);
                if(isFinite(m)&&m<=0&&((isFinite(t)&&t>0)||(isFinite(u)&&u>0)))return true;
                return isFinite(t)&&t>0&&isFinite(u)&&u>=t;
              }
              function cut(nm,why){
                cutN++;
                if(why==='dead')cutDead++;else cutDry++;
                if(cutList.length<8)cutList.push(String(nm||'流量包').slice(0,18)+'('+(why==='dead'?'失效':'用尽')+')');
              }
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
                    var other=(ty===OTHER),plansBefore=plans;
                    L(g.planRemianFlowInfoRes).forEach(function(p){
                      plans++;
                      var fl=L(p.flowInfoRes),u=p.unit||g.unit||sec.unit;
                      if(other&&dead(p)){cut(p.planName,'dead');return;}
                      if(fl.length){
                        fl.forEach(function(f){
                          flows++;
                          if(other){
                            if(dead(f)){cut(f.flowName||p.planName,'dead');return;}
                            if(dry(f)){cut(f.flowName||p.planName,'dry');return;}
                          }
                          var nm=p.planName||f.flowName||cat;
                          if(f.flowName&&p.planName&&f.flowName!==p.planName)nm=nm+' · '+f.flowName;
                          push(nm,cat,f.usedRes,f.remainRes,f.totalRes,f.unit||u);
                        });
                      }else{
                        if(other&&dry(p)){cut(p.planName,'dry');return;}
                        push(p.planName||cat,cat,p.usedRes,p.remainRes,p.totalRes,u);
                      }
                    });
                    // 国内其他流量不走分类汇总兜底：汇总数含已用尽/已失效包，
                    // 会把上面刚排除掉的量又加回来。有效叶子逐条累加即可。
                    // 例外：这一类压根没有套餐明细（只有 flowtype 级汇总）时无从判定，
                    // 仍按汇总显示，免得整类流量直接消失。
                    if(r.items.length===s0){
                      if(!other){
                        push(cat+'（汇总）',cat,g.flowUsdNum,g.flowRemainNum,g.flowSumNum,g.unit||sec.unit);
                      }else if(plans===plansBefore){
                        push(cat+'（汇总）',cat,g.flowUsdNum,g.flowRemainNum,g.flowSumNum,g.unit||sec.unit);
                      }
                    }
                  });
                });
              }catch(e){r.error='树解析异常: '+e;}
              r.cutN=cutN;r.cutDry=cutDry;r.cutDead=cutDead;r.cutList=cutList.join('、');
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
              // 树正常解析出来（plans/flows>0）但条目被过滤空，也算就绪：
              // 别让「国内其他流量全是失效包」退化成超时 + 缓存兜底把失效量又显示回来。
              r.state=(r.items.length||plans>0||flows>0)?'ready':'empty';
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

    /** 模块点「刷新」→ TaskReceiver 起独立线程调这里。不进任务队列、不记今日状态。 */
    static void fetch() {
        long start = System.currentTimeMillis();
        StringBuilder diary = new StringBuilder();
        diary.append("build: ").append(BUILD).append('\n');
        diary.append("数据源: 余量查询页 Vuex 全量树（App 官方真页）\n");
        diary.append("入口: ").append(MARGIN_URL).append('\n');
        diary.append("方式: v4.10.0-r2 刷新 = 直接新开一屏余量页 —— 不检查/拉起前台、"
                + "不读也不复用任何已打开页面的页内数据，只用 H5 容器开一屏，"
                + "等它自己把数据请求并解密完，再读这一屏 Vuex 里的 "
                + "planRemianFlowListRes 全量树；"
                + "不发请求、不解密、不拼签名、不自动登录。跑完关掉自己开的页。\n");

        JSONObject tree;
        // v4.10.0-r2：删掉「先确认/拉起前台」整段。模块侧已确认「移动开着」才发广播，
        // 目标进程收到广播就说明它活着；刷新直接用 H5 容器新开一屏，不拉主界面、
        // 不经过开屏页（用户实证：从模块跳转过去的页面可以正常操作）。
        HookLog.progress("flow", "· 新开一屏读余量（不拉起主界面）");
        diary.append("前台: 不检查、不拉起；直接用 H5 容器新开一屏\n");
        RealPage.beginSession();
        try {
            tree = readTree(diary);
        } finally {
            RealPage.endSession();
            closeOpened(diary);
        }
        if (tree != null && hasData(tree)) {
            try {
                tree.put("at", System.currentTimeMillis());
            } catch (Throwable ignored) {
            }
            diary.append("全量树: 套餐 ").append(tree.optInt("plans"))
                    .append(" · 流量叶子 ").append(tree.optInt("flows"))
                    .append(" · 共享流量状态 ").append(tree.optString("share", "无")).append('\n');
            int cutN = tree.optInt("cutN");
            if (cutN > 0) {
                diary.append("过滤: 国内其他流量 已排除 ").append(cutN)
                        .append(" 项（用尽 ").append(tree.optInt("cutDry"))
                        .append(" / 失效 ").append(tree.optInt("cutDead")).append("）")
                        .append(tree.optString("cutList", "")).append('\n');
            }
            diary.append("条目 ").append(countItems(tree)).append(" 条 · 总耗时 ")
                    .append(System.currentTimeMillis() - start).append("ms\n");
            String rt = tree.optString("rawTree", "");
            diary.append("全量树原文(前 2000 字):\n")
                    .append(rt.length() > 2000 ? rt.substring(0, 2000) + "…" : rt).append('\n');
            HookLog.progress("flow", "✓ 读到 " + countItems(tree) + " 条套餐");
            HookLog.log("FLOW", "流量余量 · 全量树 " + countItems(tree) + " 条 · " + BUILD,
                    diary.toString());
            send(tree);
            return;
        }

        String st = tree == null ? "" : tree.optString("state", "");
        String why = tree == null ? "真页/离屏都没返回"
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
     * v4.10.0-r2：刷新 = 新开一屏余量页，只读这一屏自己加载出来的数据。
     *
     * <p>用户实证「别检查页内数据，有时候检查不到」：旧实现第一级先去读已打开
     * 页面的 Vuex（{@link #readLive}），而那些页常常 Vuex 已 reset、Activity 已
     * finish 或 WebView 已 detach，读它纯属碰运气，还白白吃掉十几秒预算。
     * 现在整条第一级删掉 —— 直接用 {@link RealPage#openFresh} 新开一屏，
     * 等它自己把数据请求并解密完再读；读不到只重载「这一屏」一次，
     * 不翻登记表、不复用任何旧页。两次都空才交给首页 localStorage 兜底。
     */
    private static JSONObject readTree(StringBuilder diary) {
        HookLog.progress("flow", "· 新开一屏余量页…");
        WebPage page = openRealPage(diary);
        if (page == null) {
            diary.append("新开一屏失败（容器没起来）\n");
            return null;
        }
        HookLog.progress("flow", "· 等新屏请求并解密…");
        JSONObject o = pollTree(page, TREE_DEADLINE_MS, 700L, diary, "新屏");
        if (o != null && hasData(o)) {
            diary.append("新屏直读成功，未读任何旧页\n");
            return o;
        }
        // 新屏在、树没出来（页面还在请求）→ 只重载这一屏一次，绝不回头翻旧页。
        HookLog.progress("flow", "· 新屏没数据，重载一次…");
        reloadOpened(diary);
        o = pollTree(page, TREE_DEADLINE_MS, 700L, diary, "新屏重载");
        if (o != null && hasData(o)) {
            return o;
        }
        HookLog.progress("flow", "· 新屏没读到，改读首页缓存…");
        diary.append("新屏这条路没拿到全量树（不再复用/检查任何已开页面）\n");
        return null;
    }

    /**
     * v4.10.0-r2：用 {@link RealPage#openFresh} 强制新开一屏余量页，
     * 返回的 WebPage 直接交给 pollTree 读；raw 记进 openedView 供 closeOpened 收页。
     */
    private static WebPage openRealPage(StringBuilder diary) {
        try {
            // 新开一屏一律用标准地址，不再拿 realUrl（旧页地址）来复用。
            String url = MARGIN_URL;
            diary.append("真页: 新开一屏 ").append(shortUrl(url)).append('\n');
            WebPage page = RealPage.openFresh(url, "marginQuery", 28_000L);
            if (page == null) {
                diary.append("真页: 新开一屏失败（容器无响应）\n");
                return null;
            }
            String href = page.href();
            diary.append("真页: 已开 ").append(shortUrl(href))
                    .append(" · ").append(page.kernel()).append('\n');
            Object raw = page.raw();
            openedView = raw;
            openedUrl = href == null || href.isEmpty() ? url : href;
            noteReal(openedUrl, raw);
            return page;
        } catch (Throwable t) {
            diary.append("真页打开异常: ").append(t).append('\n');
            return null;
        }
    }

    /** 真页在、树没出来时，让它重新加载一次（复用旧页时 Vuex 多半已经 reset）。 */
    private static void reloadOpened(StringBuilder diary) {
        Object view = openedView;
        if (view == null) {
            return;
        }
        try {
            diary.append("真页: 重载一次再读\n");
            HookLog.logcat("flow reload real page");
            new android.os.Handler(android.os.Looper.getMainLooper()).post(new Runnable() {
                @Override
                public void run() {
                    try {
                        de.robv.android.xposed.XposedHelpers.callMethod(view, "reload");
                    } catch (Throwable ignored) {
                    }
                }
            });
            Thread.sleep(2_500L);
        } catch (Throwable t) {
            diary.append("真页重载异常: ").append(t).append('\n');
        }
    }

    /** 关掉本轮自己打开的余量页，清掉登记。用户自己点开的页一律不动。 */
    private static void closeOpened(StringBuilder diary) {
        Object view = openedView;
        openedView = null;
        if (view == null) {
            return;
        }
        if (realView == view) {
            realView = null;
            realUrl = null;
            realAt = 0L;
        }
        if (!RealPage.isOwned(view)) {
            diary.append("真页: 这一页不是本轮打开的（用户自己的），不关\n");
            return;
        }
        try {
            boolean ok = RealPage.closePage(view);
            diary.append("真页: 收页 ").append(ok ? "已完成" : "失败").append('\n');
        } catch (Throwable t) {
            diary.append("真页收页异常: ").append(t).append('\n');
        }
    }

    /**
     * 第 1 级：真页余量页还开着，直接读它的 Vuex。
     *
     * <p>真页是 App 用带参 URL 打开的、带完整 JS 桥、会话齐全，页面自己已经请求并解密完，
     * 模块只是把结果读出来 —— 不发请求、不碰签名、不唤醒任何东西。
     *
     * <p>11-10 23:32 日志的教训：只认本类 {@code noteReal} 的私有登记，会漏掉
     * 「任务刚跑完、真页仍在，但登记已被清」的情况，于是白白重开一次。
     * 现在先问 {@link RealPage#find}（全 App 共享的真页登记表），再回退私有登记。
     */
    private static JSONObject readLive(StringBuilder diary) {
        Object view = null;
        String url = null;
        realUnresponsive = false;
        try {
            Object found = RealPage.find("marginQuery", REAL_TTL_MS);
            if (found != null) {
                view = found;
                url = RealPage.lastUrlOf(found);
                if (url == null || url.indexOf("marginQuery") < 0) {
                    url = realUrl != null ? realUrl : MARGIN_URL;
                }
                diary.append("真页: 命中 App 真页登记（").append(shortUrl(url)).append("）\n");
            }
        } catch (Throwable ignored) {
        }
        if (view == null) {
            view = realPage();
            url = realUrl;
            if (view != null) {
                long age = System.currentTimeMillis() - realAt;
                diary.append("真页: 本轮登记 ").append(age / 1000).append("s 前的余量页\n");
            }
        }
        if (view == null) {
            diary.append("真页: 未找到已打开的余量页\n");
            return null;
        }
        diary.append("真页地址: ").append(shortUrl(url)).append('\n');
        try {
            WebPage page = WebPage.attachReal(view, url);
            if (page == null) {
                diary.append("真页: attach 失败（页面已销毁？）\n");
                return null;
            }
            // 20 秒上限：单次 eval 自己就吃 3 秒，预算不够会出现「轮询 2 轮就判死」。
            JSONObject o = pollTree(page, TREE_DEADLINE_MS, 700L, diary, "真页");
            realUnresponsive = o == null;
            if (o != null && hasData(o)) {
                diary.append("真页直读成功，未新建任何 WebView\n");
            }
            if (o == null) {
                // 一次回调都没有 = 这个 WebView 已经不执行 JS 了，留着它下一轮还会命中。
                diary.append("真页: 零回调 · 从登记表摘掉\n");
                RealPage.forget(view);
                if (realView == view) {
                    realView = null;
                    realUrl = null;
                    realAt = 0L;
                }
            }
            return o;
        } catch (Throwable t) {
            diary.append("真页直读异常: ").append(t).append('\n');
            return null;
        }
    }

    /**
     * 第 2 级：离屏打开余量页。
     *
     * <p>相比 4.0.7 的三处修正（依据 11-10 18:12 日志）：
     * <ul>
     *   <li>优先用<b>真页用过的完整地址</b>（带 {@code channelId/yx}）——裸地址那次
     *       19 秒零请求，Vuex 停在 {@code loadInit}；</li>
     *   <li>先 {@link CookieStore#flushToWebView()} 把已捕获的会话刷进 X5，再把
     *       {@code Cookie} 作为请求头随 loadUrl 带进去（X5 对 setCookie 时灵时不灵，
     *       两条路一起走）；</li>
     *   <li>真页地址不可用或读不到时，再退回裸地址 + Cookie 头重试一次。</li>
     * </ul>
     */
    private static JSONObject readOffscreen(StringBuilder diary) {
        String real = realUrl;
        boolean fresh = real != null && System.currentTimeMillis() - realAt <= REAL_TTL_MS;
        JSONObject o = null;
        if (fresh) {
            diary.append("离屏第 1 次: 复用真页地址（含 channelId/yx）\n");
            o = openAndPoll(real, TREE_DEADLINE_MS - 4_000L, diary);
            if (o != null && hasData(o)) {
                return o;
            }
            diary.append("复用真页地址没读到，换裸地址重试\n");
        }
        diary.append("离屏第 ").append(fresh ? 2 : 1).append(" 次: 裸地址 + 会话 Cookie\n");
        return openAndPoll(MARGIN_URL, TREE_DEADLINE_MS / 2, diary);
    }

    private static JSONObject openAndPoll(String url, long deadlineMs, StringBuilder diary) {
        WebPage page = null;
        long start = System.currentTimeMillis();
        probeBusyUntil = start + deadlineMs + 30_000L;
        try {
            CookieStore.flushToWebView();
            String cookie = CookieStore.header(url);
            Map<String, String> headers = null;
            if (cookie != null && !cookie.isEmpty()) {
                headers = Collections.singletonMap("Cookie", cookie);
            }
            diary.append("Cookie: ").append(headers == null
                    ? "无（按已捕获会话为空处理）" : ("已带 " + cookie.length() + " 字")).append('\n');
            page = WebPage.openProbe(url, 9_000L, MARGIN_HOSTS, null, headers);
            if (page == null) {
                diary.append("余量页: 离屏 WebView 建不起来（App 未运行或 WebView 内核不可用）。\n");
                return null;
            }
            return pollTree(page, deadlineMs, TREE_POLL_MS, diary, "离屏");
        } catch (Throwable t) {
            diary.append("余量页异常: ").append(t).append('\n');
            return null;
        } finally {
            probeBusyUntil = 0L;
            if (page != null) {
                try {
                    page.close();
                } catch (Throwable ignored) {
                }
            }
            diary.append("本轮耗时: ").append(System.currentTimeMillis() - start).append("ms\n");
        }
    }

    /**
     * 轮询 {@link #TREE_JS}。
     *
     * <p>只把 {@code ready} / {@code login} / 明确 {@code error} 当终态；
     * {@code loading} / {@code empty}（页面 Vue 还没挂上、loadState 还没到 loadCompleted）
     * 一律继续等 —— 11-10 23:32 日志里「真页轮询 2 轮 · 6s 上限」就是被提前判死，
     * 页面其实还在请求数据。单次 eval 超时从 5s 收到 3s，免得一次卡顿吃掉全部预算。
     */
    private static JSONObject pollTree(WebPage page, long deadlineMs, long gapMs,
                                       StringBuilder diary, String who) {
        long end = System.currentTimeMillis() + deadlineMs;
        JSONObject last = null;
        int rounds = 0;
        int silent = 0;
        String lastState = "";
        long lastReportAt = -2_000L;
        while (System.currentTimeMillis() < end) {
            rounds++;
            String raw = null;
            try {
                raw = page.eval(TREE_JS, 3_000L);
            } catch (Throwable e) {
                diary.append(who).append(" 第 ").append(rounds).append(" 轮 eval 异常: ")
                        .append(e).append('\n');
            }
            if (raw != null && raw.startsWith("{")) {
                silent = 0;
                try {
                    JSONObject o = new JSONObject(raw);
                    last = o;
                    String st = o.optString("state", "loading");
                    lastState = st;
                    if (hasData(o)) {
                        break;
                    }
                    boolean hard = "login".equals(st) || "error".equals(st);
                    if (hard) {
                        break;
                    }
                } catch (Throwable ignored) {
                }
            } else {
                // v4.2.1：连续 3 轮一次都不回 = 页面已经不执行 JS 了。以前会把这 18 秒
                // 预算全烧光，再花 33 秒试离屏、91 秒试首页缓存 —— 全是白等。
                silent++;
                if (silent >= 3) {
                    diary.append(who).append(": 连续 ").append(silent)
                            .append(" 轮零回调 · 提前收工（页面已不响应）\n");
                    break;
                }
            }
            // v4.2.0：等的时候把真实进度写到屏幕上，用户不必猜「是不是卡死了」。
            long waited = System.currentTimeMillis() - (end - deadlineMs);
            if (waited >= lastReportAt + 2_000L) {
                lastReportAt = waited;
                HookLog.progress("flow", "· 等页面请求并解密（已等 " + (waited / 1000) + "s）");
            }
            try {
                Thread.sleep(gapMs);
            } catch (InterruptedException e) {
                break;
            }
        }
        diary.append(who).append(" 轮询 ").append(rounds).append(" 轮 · ")
                .append(deadlineMs / 1000).append("s 上限 · 末轮 ")
                .append(lastState.isEmpty() ? "无返回" : lastState).append('\n');
        return last;
    }

    /** 真页登记：由 WebViewHooks 在页面回调里喂进来，离屏任务期间的记录一律丢弃。 */
    static void noteReal(String url, Object view) {
        try {
            if (url == null || view == null || url.indexOf("marginQuery") < 0) {
                return;
            }
            if (System.currentTimeMillis() < probeBusyUntil) {
                return;
            }
            realView = view;
            realUrl = url;
            realAt = System.currentTimeMillis();
        } catch (Throwable ignored) {
        }
    }

    private static Object realPage() {
        Object v = realView;
        if (v == null || System.currentTimeMillis() - realAt > REAL_TTL_MS) {
            return null;
        }
        return v;
    }

    private static String shortUrl(String url) {
        if (url == null) {
            return "无";
        }
        return url.length() > 160 ? url.substring(0, 160) + "…" : url;
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

            // v4.10.0-r2：首页兜底也新开一屏，和主链路一致 —— 不复用旧页、
            // 不拉起主界面。离屏 WebView 落不进 App 的同源存储上下文，用真页才读得到
            // 那一份 localStorage。
            long openStart = System.currentTimeMillis();
            WebPage homePage = RealPage.openFresh(ENTRY_URL, "home.html", 12_000L);
            wv = homePage == null ? null : homePage.raw();
            diary.append("建页耗时: ").append(System.currentTimeMillis() - openStart).append("ms\n");
            if (wv == null) {
                diary.append("结果: App 真页没打开（容器不可用）。\n");
                send(error("无法打开 App 首页真页（请先打开一次中国移动 App）", diary));
                return;
            }
            diary.append("真页已就绪: ").append(wv.getClass().getName()).append('\n');

            JSONObject result = null;
            String lastRaw = "";
            String lastNote = "";
            int rounds = 0;
            long deadline = Math.min(System.currentTimeMillis() + DEADLINE_MS,
                    start + DEADLINE_MS + 6_000L);
            while (System.currentTimeMillis() < deadline) {
                try {
                    Thread.sleep(POLL_GAP_MS);
                } catch (InterruptedException e) {
                    break;
                }
                Object cur = wv;
                if (cur == null) {
                    lastNote = "真页已失效";
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
                result.put("error", "15 秒内没读到首页余量缓存"
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

            HookLog.log("FLOW", "流量余量刷新 · state=" + result.optString("state", "")
                    + " · " + items + " 条", diary.toString());
            send(result);
        } catch (Throwable t) {
            HookLog.error("流量余量刷新异常", t);
            diary.append("异常: ").append(t).append('\n');
            send(error("读取异常：" + t, diary));
        } finally {
            try {
                if (wv != null && RealPage.isOwned(wv)) {
                    RealPage.closePage(wv);
                }
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * 树是否已经可用。
     *
     * <p>不只看 items 条数：国内其他流量把「已用尽 / 已失效」逐条排除后，可能一条不剩，
     * 但树本身是完整的（state=ready）。这时必须停轮询、直接用树，不能判空去走缓存兜底 ——
     * 缓存只有分类汇总，会把刚排除掉的失效量又加回来。
     */
    private static boolean hasData(JSONObject o) {
        return countItems(o) > 0 || (o != null && "ready".equals(o.optString("state", "")));
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
            HookLog.log("FLOW", "流量余量刷新 · 失败",
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
