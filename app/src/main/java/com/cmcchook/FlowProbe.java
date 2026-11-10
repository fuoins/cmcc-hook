package com.cmcchook;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * v4.0.7+flowprobe：流量专项探针 —— 纯诊断版。
 *
 * <p>来源：用户反馈「流量一键查询是首页的数据、不全」，并给出
 * {@code github.com/fuoins/flow-assistant}。查该项目 {@code MarginPageBridge} 得知，
 * 全量分套餐明细来自<b>页面 Vuex</b>
 * {@code state.planRemainQryInfo.newPlanRemainQry.planRemianFlowListRes}
 * （分类 → 套餐 → 流量资源叶子 + 共享流量），而现在模块读的是首页
 * {@code home.html} 的 localStorage {@code flowSceneData[]} —— 只有 3 个汇总数字。
 *
 * <p><b>11-10 日志实证（本轮动手前核实，修正了上一轮的判断）</b>：
 * 余量接口 {@code app.10086.cn/biz-orange/BH/newPlanRemainQry/getNewPlanRemainQry}
 * 的<b>请求体与响应体都是国密加密的 {@code {"body":"密文"}}</b>，且带
 * {@code x-sign / x-token / xs / x-nonce / x-time} 动态签名；会话串拼在 query 里
 * 只是登录态透传，<b>不等于能靠会话串重放拿到明文 JSON</b>。上一轮说的
 * 「接口无签名、可直接重放拿明文」是错的。全量树是<b>页面 JS 解密写进内存 Vuex</b> 的，
 * 所以本探针把重点放在「修好 X5 注入 → 离屏余量页能否 mount → 读 Vuex 全量树」。
 *
 * <p>4 组串行对照（每组独立离屏页 / 独立采集 / 独立关闭）：
 * <ul>
 *   <li><b>A 首页缓存基线</b>：现状数据源，dump {@code flowSceneData} 全文，确认只有汇总；</li>
 *   <li><b>B 接口直连·裸重放</b>：Java 层单次只读 POST（已捕获会话串拼 query + Cookie 头），
 *       如实记录 HTTP 码 / 响应是密文还是明文，作为「会话串重放能否拿明文」的证伪组；</li>
 *   <li><b>C 余量页·裸地址</b>：修好注入后读 Vuex {@code planRemianFlowListRes} 全量树 +
 *       页内 XHR 清单 + JS 错误；</li>
 *   <li><b>D 余量页·会话 Cookie 回灌</b>：在 C 基础上回灌已捕获 Cookie + 主文档带 Cookie 头，
 *       看能否让离屏页判定已登录、发请求、mount。</li>
 * </ul>
 *
 * <p><b>硬约束（用户明确要求）</b>：只复用已捕获的当前会话，不换票、不点「本机号码一键登录」、
 * 不调 {@code SsoLogin.ensure()}、不发起任何新登录请求；会话串 / token 一律脱敏；
 * 不改正式 {@code FlowTask} 解析逻辑、不采用命中结果、不改版本号、不发 Release。
 */
final class FlowProbe {

    private static final String[] HOSTS = {"10086.cn"};
    private static final String HOME_URL = FlowTask.ENTRY_URL;
    private static final String MARGIN_URL =
            "https://wx.10086.cn/cmcc-service/app-pages/main/marginQuery";
    private static final String API_URL =
            "https://app.10086.cn/biz-orange/BH/newPlanRemainQry/getNewPlanRemainQry";

    private static final long PER_GROUP_MS = 11_000L;
    private static final long TOTAL_MS = 95_000L;
    private static final long POLL_GAP_MS = 900L;

    private FlowProbe() {
    }

    /**
     * 观测脚本：文档一开始就挂错误钩子 + XHR/fetch 抓取。带幂等守卫，
     * 会被 postDelayed 多点重复注入（覆盖系统内核与 X5），重复执行无害。
     * 只记录请求 URL / 状态 / 响应特征，<b>不重放、不改写</b>。
     */
    private static final String EARLY_JS = """
            (function(){
              try{
                if(window.__flowProbe)return;
                var d={t:Date.now(),errs:[],reqs:[]};
                window.__flowProbe=d;
                function push(a,s){try{if(a.length<20)a.push(String(s).replace(/\\s+/g,' ').substring(0,200))}catch(e){}}
                window.addEventListener('error',function(e){push(d.errs,'error:'+String((e&&e.message)||e))},true);
                window.addEventListener('unhandledrejection',function(e){push(d.errs,'rej:'+String((e&&e.reason)||''))},true);
                try{var ce=console.error;console.error=function(){push(d.errs,'console:'+Array.prototype.slice.call(arguments).join(' '));return ce.apply(console,arguments)}}catch(e){}
                function hit(u){u=String(u||'');return u.indexOf('RemainQry')>=0||u.indexOf('remain')>=0||u.indexOf('flowScene')>=0}
                function rec(u,st,txt){try{
                  txt=String(txt||'');
                  var enc=/^\\s*\\{\\s*"body"\\s*:/.test(txt)?'密文body':'明文/其它';
                  push(d.reqs,u.split('?')[0].substring(0,80)+' | '+st+' | '+txt.length+'B | '+enc+' | head='+txt.substring(0,50).replace(/\\s+/g,' '));
                }catch(e){}}
                try{
                  var XO=XMLHttpRequest.prototype.open,XS=XMLHttpRequest.prototype.send;
                  XMLHttpRequest.prototype.open=function(m,u){try{this.__fp=u}catch(e){}return XO.apply(this,arguments)};
                  XMLHttpRequest.prototype.send=function(){try{
                    var self=this;
                    if(hit(self.__fp))self.addEventListener('load',function(){try{rec(self.__fp,self.status,self.responseText)}catch(e){}});
                  }catch(e){}return XS.apply(this,arguments)};
                }catch(e){}
                try{
                  var of=window.fetch;
                  if(of)window.fetch=function(){try{
                    var a=arguments[0],u=(typeof a==='string')?a:((a&&a.url)||'');
                    var pr=of.apply(this,arguments);
                    if(hit(u)&&pr&&pr.then)pr.then(function(r){try{if(r&&r.clone)r.clone().text().then(function(t){rec(u,r.status,t)}).catch(function(){})}catch(e){}}).catch(function(){});
                    return pr;
                  }catch(e){return of.apply(this,arguments)}};
                }catch(e){}
              }catch(e){}
            })();
            """;

    /**
     * 现场采集：页面状态 + 注入是否生效 + JS 错误 + 页内 XHR 清单
     * + Vuex 全量树结构与节点数 + 首页 localStorage 余量缓存。
     */
    private static final String PROBE_JS = """
            (function(){
              var out=[];
              function L(x){return !x?[]:Array.isArray(x)?x:[x]}
              function TX(s){try{return String(s||'').replace(/\\s+/g,' ').trim()}catch(e){return ''}}
              var href='';try{href=String(location.href||'')}catch(e){}
              out.push('HREF: '+href);
              try{out.push('TITLE: '+String(document.title||''))}catch(e){out.push('TITLE: ?')}
              var body='';try{body=(document.body?(document.body.innerText||document.body.textContent||''):'')}catch(e){}
              out.push('BODYLEN: '+TX(body).length);
              out.push('TPL: '+(body.indexOf('{{')>=0?'1':'0'));
              out.push('BODY: '+TX(body).substring(0,220));
              try{out.push('READY: '+String(document.readyState||'?'))}catch(e){out.push('READY: ?')}
              try{
                var d=window.__flowProbe;
                out.push('DIAG: '+(d?'有(注入生效)':'无(earlyScript 未生效)'));
                out.push('ERRS: '+(d&&d.errs&&d.errs.length?d.errs.slice(0,8).join(' || '):'无'));
                out.push('REQS: '+(d&&d.reqs?d.reqs.length:0));
                if(d&&d.reqs)for(var i=0;i<d.reqs.length&&i<8;i++)out.push('REQ'+i+': '+d.reqs[i]);
              }catch(e){out.push('DIAG: ?');out.push('REQS: 0')}
              var st=null,how='';
              try{
                var nodes=[document.body].concat(Array.prototype.slice.call(document.querySelectorAll('div'),0,2500));
                for(var j=0;j<nodes.length;j++){
                  var vm=nodes[j]&&nodes[j].__vue__,h=0;
                  while(vm&&!vm.$store&&vm.$parent&&h++<12)vm=vm.$parent;
                  if(vm&&vm.$store){st=vm.$store.state;how='div.__vue__';break}
                }
              }catch(e){out.push('STOREERR: '+e)}
              out.push('STORE: '+(st?'有('+how+')':'无'));
              if(st){
                try{var ks=[];for(var k in st){if(ks.length<30)ks.push(k)}out.push('STATEKEYS: '+ks.join(','))}catch(e){}
                var mod=st.planRemainQryInfo||{};
                out.push('MOD: '+(st.planRemainQryInfo?'有':'无'));
                out.push('LOADSTATE: '+String(mod.newPlanRemainQryLoadState||'?'));
                out.push('ERRTXT: '+String(mod.newPlanRemainQryErrorText||''));
                var q=mod.newPlanRemainQry;
                out.push('Q: '+(q?'有':'无'));
                var tree=q&&q.planRemianFlowListRes;
                out.push('TREE: '+(tree?'有':'无'));
                if(tree){
                  try{
                    var secs=L(tree),plans=0,flows=0,names=[];
                    secs.forEach(function(s){L(s.planRemianFlowRes).forEach(function(g){L(g.planRemianFlowInfoRes).forEach(function(p){plans++;var fl=L(p.flowInfoRes);flows+=fl.length;if(names.length<10)names.push(String(p.planName||g.flowtype||'?').substring(0,20))})})});
                    out.push('SECTIONS: '+secs.length);
                    out.push('PLANS: '+plans+' FLOWS: '+flows);
                    out.push('PLANNAMES: '+names.join(' | '));
                    out.push('TREEJSON: '+TX(JSON.stringify(tree)).substring(0,2600));
                  }catch(e){out.push('TREEERR: '+e)}
                }
                try{var sh=st.userShareInfo||{};out.push('SHARE: '+String(sh.shareUserInfoLoadState||'无'))}catch(e){}
              }
              try{
                var raw='',hit='';
                var keys=['BH/newPlanRemainQry/getNewPlanRemainQry','newPlanRemainQry','getNewPlanRemainQry'];
                for(var p=0;p<keys.length;p++){var v=localStorage.getItem(keys[p]);if(v){raw=v;hit=keys[p];break}}
                if(!raw){for(var q2=0;q2<localStorage.length;q2++){var kk=localStorage.key(q2),vv=localStorage.getItem(kk)||'';if(vv.indexOf('flowSceneData')>=0){raw=vv;hit=kk;break}}}
                out.push('FLOWKEY: '+(hit||'无'));
                out.push('FLOWITEMS: '+(String(raw).match(/flowSceneData/g)||[]).length);
                out.push('FLOWRAW: '+TX(raw).substring(0,1100));
              }catch(e){out.push('FLOWKEY: 读取异常')}
              try{var ls=[];for(var z=0;z<localStorage.length&&z<25;z++)ls.push(localStorage.key(z));out.push('LSKEYS: '+ls.join(','))}catch(e){out.push('LSKEYS: ?')}
              return out.join('\\n');
            })();
            """;

    private static final class Result {
        String code = "";
        String label = "";
        String landing = "-";
        String diag = "-";
        String errs = "-";
        String reqs = "-";
        String tree = "-";
        String flow = "-";
        String verdict = "";
        String site = "";
        long costMs;
    }

    static void run() {
        long t0 = System.currentTimeMillis();
        long globalEnd = t0 + TOTAL_MS;

        String session = "";
        String cookie = "";
        try {
            session = CookieStore.sessionString();
        } catch (Throwable ignored) {
        }
        try {
            cookie = CookieStore.header(API_URL);
        } catch (Throwable ignored) {
        }

        HookLog.log("FLOW", "流量专项探针启动（4.0.7+flowprobe 诊断版）",
                "流量专项探针已装载 · 纯诊断版（不改正式查询、不采用命中结果、不发 Release）\n"
                        + "目标: 定位「流量信息全」的数据源到底能不能在纯离屏、一键、后台拿到。\n"
                        + "已核实: flow-assistant 全量明细 = 页面 Vuex "
                        + "state.planRemainQryInfo.newPlanRemainQry.planRemianFlowListRes；"
                        + "首页 localStorage.flowSceneData[] 只有 3 项汇总；"
                        + "getNewPlanRemainQry 接口请求/响应均为国密密文 {\"body\":...} 且带动态签名。\n"
                        + "—— 本次 run 实时原料(内存, 不落盘, 全部脱敏) ——\n"
                        + "会话串(JSESSIONID/UID/Comment/ticketID): "
                        + (session.isEmpty() ? "无" : session.length() + " 字符, 已脱敏")
                        + " · Cookie 头: " + (cookie.isEmpty() ? "无" : cookie.length() + " 字符, 已脱敏")
                        + "\n方案: A 首页缓存基线 / B 接口直连·裸重放(证伪) / "
                        + "C 余量页·裸地址(读 Vuex 全量树) / D 余量页·会话 Cookie 回灌"
                        + "\n本版已修 X5 earlyScript 注入(v2 全轮 DIAG:无 的根因): "
                        + "改用 postDelayed 多点注入, 不依赖内核类型。"
                        + "\n硬约束: 不点『本机号码一键登录』、不调 SsoLogin.ensure 换票、"
                        + "不发起任何新登录请求；会话串日志已脱敏。");

        StringBuilder table = new StringBuilder();
        table.append("组 | 变量 | 落地/状态 | DIAG(注入) | JS错误 | 页内XHR | Vuex全量树 | 余量缓存 | 耗时 | 结论\n");
        table.append("--- | --- | --- | --- | --- | --- | --- | --- | --- | ---\n");

        Result[] results = new Result[4];
        results[0] = runPage("A", "首页缓存基线", HOME_URL, null, globalEnd);
        results[1] = runDirect("B", "接口直连·裸重放(证伪)", session, cookie);
        results[2] = runPage("C", "余量页·裸地址", MARGIN_URL, null, globalEnd);

        boolean flushed = false;
        if (!cookie.isEmpty()) {
            try {
                CookieStore.flushToWebView();
                flushed = true;
            } catch (Throwable e) {
                HookLog.log("FLOW", "流量探针 · Cookie 回灌失败", String.valueOf(e));
            }
        }
        java.util.Map<String, String> hdr = new java.util.LinkedHashMap<>();
        if (!cookie.isEmpty()) {
            hdr.put("Cookie", cookie);
        }
        results[3] = runPage("D", "余量页·会话Cookie回灌" + (flushed ? "" : "(回灌失败)"),
                MARGIN_URL, hdr.isEmpty() ? null : hdr, globalEnd);

        String treeHit = "";
        for (int i = 0; i < results.length; i++) {
            Result r = results[i];
            appendRow(table, r);
            if (treeHit.isEmpty() && r.tree.startsWith("有")) {
                treeHit = r.code + "（" + r.tree + "）";
            }
            HookLog.log("FLOW", "流量探针 · 组 " + r.code + " · " + r.label,
                    "耗时: " + r.costMs + "ms · 结论: " + r.verdict
                            + "\n—— 现场原文 ——\n" + (r.site.isEmpty() ? "(空)" : r.site));
        }

        HookLog.log("FLOW", "流量专项探针汇总",
                table.toString()
                        + "\n读到 Vuex 全量树的组: " + (treeHit.isEmpty() ? "无" : treeHit)
                        + "\n判据: C/D 组 DIAG 应为『有(注入生效)』—— 若为『无』说明 X5 注入修复未生效。"
                        + "\n本版为诊断测试版, 不改正式查询逻辑、不采用命中结果、不发 Release。");

        try {
            JSONObject o = new JSONObject();
            o.put("state", "error");
            o.put("error", "【流量专项探针·诊断版】已跑完 4 组，未产出正式明细。"
                    + "请导出完整日志看『流量专项探针汇总』对照表与 4 条『流量探针 · 组 X』。");
            o.put("at", System.currentTimeMillis());
            FlowTask.send(o);
        } catch (Throwable e) {
            HookLog.log("FLOW", "流量探针 · 回传 UI 失败", String.valueOf(e));
        } finally {
            try {
                OffscreenWeb.close();
            } catch (Throwable ignored) {
            }
        }
    }

    /** 开一张离屏页，注入观测脚本，轮询采集现场。 */
    private static Result runPage(String code, String label, String url,
                                  java.util.Map<String, String> headers,
                                  long globalEnd) {
        Result r = new Result();
        r.code = code;
        r.label = label;
        long start = System.currentTimeMillis();
        if (System.currentTimeMillis() >= globalEnd) {
            r.verdict = "总时限到，跳过";
            r.costMs = System.currentTimeMillis() - start;
            return r;
        }
        long deadline = Math.min(start + PER_GROUP_MS, globalEnd);
        WebPage page = null;
        try {
            page = WebPage.openProbe(url, 9_000L, HOSTS, EARLY_JS, headers);
            if (page == null) {
                r.site = "NO_PAGE: 离屏 WebView 建不起来";
                r.verdict = "页没起来";
            } else {
                String site = null;
                while (System.currentTimeMillis() < deadline) {
                    site = page.eval(PROBE_JS, 5_000L);
                    if (site != null) {
                        // 拿到全量树就早收工；否则给页面足够时间 mount / 发请求。
                        if (fieldOf(site, "TREE").startsWith("有")) {
                            break;
                        }
                    }
                    sleep(POLL_GAP_MS);
                }
                if (site == null) {
                    site = page.eval(PROBE_JS, 5_000L);
                }
                r.site = site == null ? "NO_SITE: 采集脚本无返回" : site;
                fill(r);
            }
        } catch (Throwable e) {
            r.site = "ERROR: " + e;
            r.verdict = "异常: " + e;
        } finally {
            if (page != null) {
                try {
                    page.close();
                } catch (Throwable ignored) {
                }
            }
            sleep(300L); // 让上一张页销毁完，避免 current 串台
            r.costMs = System.currentTimeMillis() - start;
        }
        return r;
    }

    private static void fill(Result r) {
        r.landing = shortUrl(fieldOf(r.site, "HREF"));
        r.diag = fieldOf(r.site, "DIAG");
        if (r.diag.isEmpty()) {
            r.diag = "-";
        }
        String errs = fieldOf(r.site, "ERRS");
        r.errs = errs.isEmpty() ? "-" : (errs.equals("无") ? "无" : shortCell(errs, 30));
        String reqs = fieldOf(r.site, "REQS");
        r.reqs = reqs.isEmpty() ? "-" : reqs;
        r.tree = fieldOf(r.site, "TREE");
        if (r.tree.isEmpty()) {
            r.tree = "-";
        }
        String fk = fieldOf(r.site, "FLOWKEY");
        String fi = fieldOf(r.site, "FLOWITEMS");
        r.flow = (fk.isEmpty() ? "-" : fk) + (fi.isEmpty() ? "" : "(" + fi + ")");

        if (r.site.startsWith("NO_PAGE") || r.site.startsWith("ERROR")) {
            r.verdict = "页没起来/异常";
            return;
        }
        if (r.tree.startsWith("有")) {
            r.verdict = "✓ 读到 Vuex 全量树（可移植 flow-assistant 解析）";
            return;
        }
        if (r.diag.startsWith("无")) {
            r.verdict = "注入仍未生效(需复查 X5 通道)";
            return;
        }
        String st = fieldOf(r.site, "STORE");
        if (st.startsWith("无")) {
            r.verdict = "Vue 未 mount（无 $store）· 缓存="
                    + (fk.isEmpty() ? "无" : "仅汇总");
        } else {
            r.verdict = "有 $store 但无全量树 · loadState="
                    + fieldOf(r.site, "LOADSTATE") + " · 缓存=" + (fk.isEmpty() ? "无" : "仅汇总");
        }
    }

    /**
     * B 组：Java 层单次只读 POST，会话串拼 query + Cookie 头，不发登录、不换票、不解密。
     * 只记录 HTTP 码 / 响应类型（密文 body / 明文 JSON / HTML）/ 长度，用来证伪或证实
     * 「会话串重放能否拿明文全量树」。
     */
    private static Result runDirect(String code, String label, String session, String cookie) {
        Result r = new Result();
        r.code = code;
        r.label = label;
        r.diag = "-(Java直连,无页面)";
        r.errs = "-";
        r.reqs = "-";
        r.tree = "-";
        r.flow = "-";
        long start = System.currentTimeMillis();
        HttpURLConnection conn = null;
        try {
            String url = API_URL + (session.isEmpty() ? "" : ("?" + session));
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(9_000);
            conn.setReadTimeout(9_000);
            conn.setInstanceFollowRedirects(false);
            conn.setRequestProperty("User-Agent", TaskNet.UA);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            conn.setRequestProperty("x-requested-with", "XMLHttpRequest");
            if (!cookie.isEmpty()) {
                conn.setRequestProperty("Cookie", cookie);
            }
            byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
            conn.setDoOutput(true);
            conn.setFixedLengthStreamingMode(body.length);
            OutputStream os = conn.getOutputStream();
            os.write(body);
            os.flush();
            os.close();

            int status = conn.getResponseCode();
            String ctype = String.valueOf(conn.getHeaderField("Content-Type"));
            InputStream in = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
            String text = readAll(in);
            String trimmed = text == null ? "" : text.trim();
            String type;
            if (trimmed.startsWith("{\"body\"")) {
                type = "密文body(JSON封装)";
            } else if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
                type = "明文JSON";
            } else if (trimmed.startsWith("<")) {
                type = "HTML(登录/错误页)";
            } else if (trimmed.isEmpty()) {
                type = "空";
            } else {
                type = "其它";
            }
            r.landing = "HTTP " + status + " · " + type;
            r.site = "HTTPSTATUS: " + status + "\n"
                    + "RESPTYPE: " + type + "\n"
                    + "RESPLEN: " + (text == null ? 0 : text.length()) + "\n"
                    + "CTYPE: " + ctype + "\n"
                    + "RESPHEAD: " + flat(mask(text)).substring(0, 240) + "\n"
                    + "入口(已脱敏): " + safeUrl(url) + "\n"
                    + "说明: 裸重放只带会话串 query + Cookie 头, 不带 x-sign/x-token 动态签名, "
                    + "不解密响应体; 用于判断「会话串 alone 能否拿到明文全量树」。";
            if (status >= 200 && status < 300 && type.equals("明文JSON")) {
                r.verdict = "HTTP " + status + " 明文JSON（需进一步看是否含全量树）";
            } else if (type.equals("密文body(JSON封装)")) {
                r.verdict = "HTTP " + status + " 但响应为国密密文 → 会话串重放拿不到明文";
            } else if (status >= 400) {
                r.verdict = "HTTP " + status + "（缺签名/参数被拒）→ 裸重放不可行";
            } else {
                r.verdict = "HTTP " + status + " · " + type + " → 裸重放未拿到可用明文";
            }
        } catch (Throwable e) {
            r.landing = "请求异常";
            r.site = "HTTPSTATUS: 异常 " + mask(String.valueOf(e)) + "\n"
                    + "说明: 目标进程网络不可用或接口拒绝，裸重放未成立。";
            r.verdict = "请求异常(见现场)";
        } finally {
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Throwable ignored) {
                }
            }
            r.costMs = System.currentTimeMillis() - start;
        }
        return r;
    }

    private static void appendRow(StringBuilder t, Result r) {
        t.append(r.code).append(" | ").append(r.label)
                .append(" | ").append(r.landing.isEmpty() ? "-" : r.landing)
                .append(" | ").append(shortCell(r.diag, 16))
                .append(" | ").append(shortCell(r.errs, 26))
                .append(" | ").append(r.reqs)
                .append(" | ").append(r.tree)
                .append(" | ").append(r.flow)
                .append(" | ").append(r.costMs / 1000L).append("s")
                .append(" | ").append(r.verdict).append('\n');
    }

    private static String fieldOf(String site, String key) {
        if (site == null) {
            return "";
        }
        String prefix = key + ": ";
        for (String line : site.split("\n")) {
            if (line.startsWith(prefix)) {
                return line.substring(prefix.length()).trim();
            }
        }
        return "";
    }

    private static String shortCell(String a, int max) {
        if (a == null || a.isEmpty()) {
            return "-";
        }
        String s = a.length() > max ? a.substring(0, max) + "…" : a;
        return s.replace("|", "/").replace("\n", " ");
    }

    private static String shortUrl(String u) {
        if (u == null || u.isEmpty()) {
            return "-";
        }
        String s = safeUrl(u);
        int q = s.indexOf('?');
        if (q > 0) {
            s = s.substring(0, q);
        }
        if (s.length() > 42) {
            s = s.substring(s.length() - 42);
        }
        return s.replace("|", "/");
    }

    /** 导出日志会被用户外发，会话串 / token / 签名一律脱敏。 */
    private static String mask(String s) {
        if (s == null) {
            return "";
        }
        return s.replaceAll("(?i)(JSESSIONID|UID|ticketID|cmccssotoken|QWHDSSOJWT|"
                        + "HLWHD_SESSION_TOKEN|QWHD_SESSION_TOKEN|x-token|x-sign|xs)=[^;&\\s\"']+",
                "$1=<已脱敏>");
    }

    private static String safeUrl(String u) {
        return u == null ? "" : mask(u);
    }

    private static String flat(String s) {
        return s == null ? "" : s.replaceAll("\\s+", " ").trim();
    }

    private static String readAll(InputStream in) {
        if (in == null) {
            return "";
        }
        try {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            int total = 0;
            while ((n = in.read(buf)) > 0 && total < 65_536) {
                b.write(buf, 0, n);
                total += n;
            }
            return new String(b.toByteArray(), StandardCharsets.UTF_8);
        } catch (Throwable e) {
            return "";
        } finally {
            try {
                in.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }
}
