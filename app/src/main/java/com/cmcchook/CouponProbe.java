package com.cmcchook;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * v4.0.7+probe-v2：一键扫描「全方案探针」第二轮 —— 纯诊断版。
 *
 * <p>第一轮（probe）的结论：6 组方案落地都是 mycoupons.html、没被踢登录页，
 * 但 body 停在 {@code {{item.title}}} 模板原文、券接口一次都没发。更关键的是
 * 「无当次现场」导致 B~F 的变量全部退化为空，6 组实际只测了 1 个变量。
 *
 * <p>本轮按用户确认的方案重做：
 * <ul>
 *   <li><b>原料实时读取</b>：不再依赖前台真页现场。channelId / QWHDSSOJWT / yx
 *       从当前会话的 dev.coc.10086.cn 同源 localStorage 现场读（localStorage 同域
 *       跨 WebView 共享）；会话串从 {@link CookieStore#sessionString()} 读已捕获的
 *       当前会话。全部只存本次 run 的内存变量，<b>不落盘、不带时间戳冒充最新入口</b>，
 *       日志写明来源与新鲜度，敏感值只报长度。</li>
 *   <li><b>先定位 Vue 为什么不 mount</b>：所有方案在 onPageStarted 就注入
 *       {@link #EARLY_DIAG_JS}，挂 window.onerror / unhandledrejection /
 *       console.error；PROBE_JS 额外采 readyState、完整资源清单、脚本清单、
 *       Vue 实例状态、window.cmcc 方法、localStorage 键名。</li>
 *   <li><b>三路会话注入对照</b>：Cookie 回灌+Cookie 头 / 会话串作 URL 参数
 *       （App 就是把 JSESSIONID;UID;ticketID 拼进 XHR 参数）/ localStorage.QWHDSSOJWT
 *       + window 全局，逐组累加。</li>
 *   <li><b>缺桥垫片</b>：cardInterface / newWindowInterface / androidIMScene
 *       用 Proxy 兜底（B 组起引入，A 组原样复现做对照），不打开真页。</li>
 * </ul>
 *
 * <p><b>硬约束（用户明确要求）</b>：纯离屏、界面不动、只点一次「一键扫描」；
 * 不点「本机号码一键登录」、不调 {@code SsoLogin.ensure} 换票、不发起任何新登录
 * 请求；只复用已登录会话，日志对会话串脱敏；不发 Release、不动版本号。
 */
final class CouponProbe {

    private static final String[] HOSTS = {"10086.cn"};
    /** 每方案时间上限（含开页），用户拍板 12s。 */
    private static final long PER_PLAN_MS = 12_000L;
    /** 整轮上限：6 组 + 原料页 + 首页流量页 + 余量查询页。 */
    private static final long TOTAL_MS = 145_000L;
    private static final long POLL_GAP_MS = 900L;
    private static final long MATERIAL_MS = 6_000L;
    private static final long AUX_MS = 7_000L;

    /** 当前会话的渠道/SSO 原料所在 origin（爱购商城画布页，日志实证）。 */
    private static final String MATERIAL_URL =
            "https://dev.coc.10086.cn/coc3/canvas/rightsmarket-h5-canvas/online/home";
    /** 「余量查询」页：判断有没有分套餐明细可解析（只诊断，不改流量逻辑）。 */
    private static final String MARGIN_URL =
            "https://wx.10086.cn/cmcc-service/app-pages/main/marginQuery";

    private CouponProbe() {
    }

    /** 一组待验证的方案。 */
    private static final class Plan {
        final String code;
        final String label;
        final String url;
        final Map<String, String> headers;
        final String early;
        final boolean flushCookie;

        Plan(String code, String label, String url, Map<String, String> headers,
             String early, boolean flushCookie) {
            this.code = code;
            this.label = label;
            this.url = url;
            this.headers = headers;
            this.early = early;
            this.flushCookie = flushCookie;
        }
    }

    private static final class Result {
        String code;
        String site = "";
        long costMs;
        String verdict = "";
    }

    /** 本轮实时采到的原料，只在 run() 内存里活，不落盘。 */
    private static final class Material {
        String channelId = "";
        String yx = "";
        String jwt = "";
        String cookie = "";
        String session = "";
        long at = 0L;
        String origin = "";

        boolean hasChannel() {
            return !channelId.isEmpty();
        }

        boolean hasSession() {
            return !session.isEmpty();
        }

        String describe() {
            return "采集时间: " + (at <= 0 ? "未采到" : (System.currentTimeMillis() - at) / 1000L + "s 前")
                    + " · 来源: " + (origin.isEmpty() ? "无" : origin) + "\n"
                    + "channelId: " + (channelId.isEmpty() ? "无" : channelId)
                    + " · yx: " + (yx.isEmpty() ? "无" : yx) + "\n"
                    + "QWHDSSOJWT: " + (jwt.isEmpty() ? "无" : jwt.length() + " 字符, 已脱敏")
                    + " · 会话串(JSESSIONID/UID/ticketID): " + (session.isEmpty() ? "无" : session.length() + " 字符, 已脱敏")
                    + " · Cookie头: " + (cookie.isEmpty() ? "无" : cookie.length() + " 字符, 已脱敏");
        }
    }

    /**
     * 观测用，所有方案共用：文档刚建立、业务脚本还没跑时挂错误钩子。
     * 在 onPageStarted 注入，能抓到 Vue 初始化阶段的异常。
     */
    private static final String EARLY_DIAG_JS = """
            (function(){
              try{
                if(window.__cmccDiag)return;
                var d={errs:[],t:Date.now()};
                window.__cmccDiag=d;
                function rec(kind,a){try{
                  var s=kind+': '+String(a&&a.message?a.message:a);
                  if(a&&a.filename)s+=' @'+String(a.filename).split('/').pop()+':'+(a.lineno||0);
                  if(d.errs.length<24)d.errs.push(String(s).replace(/\\s+/g,' ').substring(0,220));
                }catch(e){}}
                window.addEventListener('error',function(e){rec('error',e.error||e.message||e)},true);
                window.addEventListener('unhandledrejection',function(e){try{rec('rejection',e.reason)}catch(x){}},true);
                try{
                  var ce=console.error;
                  console.error=function(){
                    try{
                      if(d.errs.length<24)d.errs.push('console: '+Array.prototype.slice.call(arguments).join(' ').replace(/\\s+/g,' ').substring(0,200));
                    }catch(x){}
                    return ce.apply(console,arguments);
                  };
                }catch(e){}
              }catch(e){}
            })();
            """;

    /**
     * B 组起的注入变量：卡券页专用桥在离屏页里从没被注册过（用户不打开真页，
     * 就永远采不到），页面脚本调 cardInterface.xxx 会直接 TypeError 中断 mount。
     * 这里用 Proxy 兜一个「任何方法都返回空串」的垫片，先验证这一步是不是卡点。
     * 不打开真页、不创建 Activity，符合「纯离屏」硬约束。
     */
    private static final String SHIM_JS = """
            (function(){
              try{
                var names=['cardInterface','newWindowInterface','androidIMScene'],made=[];
                for(var i=0;i<names.length;i++){
                  (function(n){
                    if(typeof window[n]!=='undefined')return;
                    try{
                      window[n]=new Proxy({}, {get:function(t,k){
                        if(k==='toString'||k==='valueOf')return function(){return ''};
                        if(k==='then')return undefined;
                        if(typeof Symbol!=='undefined'&&k===Symbol.toPrimitive)return undefined;
                        return function(){return ''};
                      }});
                      made.push(n);
                    }catch(e){
                      try{window[n]={};made.push(n+'(空对象)')}catch(x){}
                    }
                  })(names[i]);
                }
                if(window.__cmccDiag)window.__cmccDiag.shim=made.join(',')||'无需补';
              }catch(e){}
            })();
            """;

    /** 在 dev.coc.10086.cn 同源离屏页里读当前会话的渠道/SSO 原料。 */
    private static final String MATERIAL_JS = """
            (function(){
              var out=[];
              function g(k){try{return localStorage.getItem(k)||''}catch(e){return ''}}
              out.push('CHANNELID: '+g('channelId'));
              out.push('JWT: '+g('QWHDSSOJWT'));
              var yx='';
              try{
                var p=JSON.parse(g('gioPerformanceInfo')||'{}');
                var h=(p.gioPageParams&&p.gioPageParams.href)||'';
                var m=String(h).match(/[?&]yx=([^&]+)/);if(m)yx=m[1];
              }catch(e){}
              if(!yx){try{var m2=String(location.href).match(/[?&]yx=([^&]+)/);if(m2)yx=m2[1]}catch(e){}}
              out.push('YX: '+yx);
              var keys=[];
              try{for(var i=0;i<localStorage.length&&i<40;i++)keys.push(localStorage.key(i))}catch(e){}
              out.push('LSKEYS: '+keys.join(','));
              out.push('HREF: '+location.href);
              return out.join('\\n');
            })();
            """;

    /** 在「余量查询」页只采集，不改流量逻辑。 */
    private static final String MARGIN_JS = """
            (function(){
              var out=[];
              var t='';try{t=(document.body?(document.body.innerText||document.body.textContent||''):'')}catch(e){}
              try{t=t.replace(/\\s+/g,' ').trim()}catch(e){}
              out.push('BODY: '+t.substring(0,1400));
              var keys=[],raw='';
              try{
                for(var i=0;i<localStorage.length;i++){
                  var k=localStorage.key(i);if(keys.length<40)keys.push(k);
                  var v=localStorage.getItem(k)||'';
                  if(v&&(v.indexOf('flowSceneData')>=0||v.indexOf('remain')>=0||v.indexOf('Remain')>=0)){
                    if(raw.length<1800)raw+=k+'='+v.substring(0,700)+' | ';
                  }
                }
              }catch(e){}
              out.push('LSKEYS: '+keys.join(','));
              out.push('FLOWRAW: '+(raw||'无'));
              return out.join('\\n');
            })();
            """;

    /** 现场采集脚本：一次性把「落地 / 登录 / 请求 / 桥 / 正文 / JS 错误」全采出来。 */
    private static final String PROBE_JS = """
            (function(){
              var out=[];
              function TX(e){try{return (e.innerText||e.textContent||'').replace(/\\s+/g,' ').trim()}catch(x){return ''}}
              var href='';try{href=String(location.href||'')}catch(e){}
              out.push('HREF: '+href);
              try{out.push('TITLE: '+(document.title||''))}catch(e){out.push('TITLE: ?')}
              var h=href.toLowerCase();
              out.push('LOGIN: '+(h.indexOf('onclicklogin')>=0||h.indexOf('autologin')>=0||h.indexOf('login.10086')>=0||h.indexOf('qwhdsso/login')>=0||h.indexOf('passport')>=0?'1':'0'));
              out.push('CHANNEL: '+(h.indexOf('channelnonsupport')>=0||h.indexOf('nonsupport')>=0?'1':'0'));
              var t='';try{t=(document.body?(document.body.innerText||document.body.textContent||''):'')}catch(e){}
              try{t=t.replace(/\\s+/g,' ').trim()}catch(e){}
              out.push('TPL: '+(t.indexOf('{{')>=0?'1':'0'));
              out.push('LOGINTXT: '+(t.indexOf('一键登录')>=0||t.indexOf('本机号码')>=0||t.indexOf('手机号登录')>=0||t.indexOf('渠道未开放')>=0?'1':'0'));
              out.push('BODYLEN: '+t.length);
              out.push('BODY: '+t.substring(0,300));
              try{
                var ns=['cardInterface','androidJsInterface','newSdkInterface','RequestInspection','newWindowInterface','androidIMScene','cmcc','leadeon'],g=[],m=[];
                for(var i=0;i<ns.length;i++){try{typeof window[ns[i]]!=='undefined'?g.push(ns[i]):m.push(ns[i])}catch(x){m.push(ns[i]+'?')}}
                out.push('BRIDGES: '+(g.join(',')||'无'));
                out.push('MISSING: '+(m.join(',')||'无'));
              }catch(e){out.push('BRIDGES: 探测异常 '+e)}
              try{
                var rs=performance.getEntriesByType('resource')||[],a=[],all=[];
                for(var j=0;j<rs.length&&a.length<20;j++){
                  var u=String(rs[j].name||'').split('?')[0],lu=u.toLowerCase();
                  if(j<24){var c0=u.indexOf('/',8);all.push((c0>0?u.substring(c0):u).substring(0,80))}
                  if(lu.indexOf('coupon')>=0||lu.indexOf('voucher')>=0||lu.indexOf('mycard')>=0||lu.indexOf('mycoupons')>=0){
                    var cut=u.indexOf('/',8);a.push(cut>0?u.substring(cut):u);
                  }
                }
                out.push('APIS('+rs.length+'): '+(a.join(' | ')||'无'));
                out.push('RES: '+(all.join(' | ')||'无'));
              }catch(e){out.push('APIS: 探测异常 '+e);out.push('RES: ?')}
              try{
                var W=['去使用','立即使用','马上使用','去兑换','立即兑换','去充值'],bs=document.querySelectorAll('button,a,[role=button],.cp-btn'),n=0,w=[];
                for(var k=0;k<bs.length;k++){
                  var bt=TX(bs[k]).replace(/\\s+/g,''),hit=false;
                  for(var z=0;z<W.length;z++){if(bt===W[z]){hit=true;break}}
                  if(hit)n++;
                  if(bt&&bt.length<14&&w.length<20)w.push(bt);
                }
                out.push('COUPONBTN: '+n);
                out.push('ANYBTN: '+bs.length);
                out.push('WORDS: '+(w.join('|')||'无'));
              }catch(e){out.push('COUPONBTN: 探测异常 '+e)}
              try{out.push('READY: '+(document.readyState||'?'))}catch(e){out.push('READY: ?')}
              try{
                var d=window.__cmccDiag;
                out.push('DIAG: '+(d?'有':'无(earlyScript 未生效)')+(d&&d.shim?' 垫片:'+d.shim:''));
                out.push('ERRS: '+(d&&d.errs&&d.errs.length?d.errs.slice(0,12).join(' || ').replace(/\\n/g,' '):'无'));
              }catch(e){out.push('DIAG: ?');out.push('ERRS: ?')}
              try{
                var app=document.querySelector('#app')||document.querySelector('#root');
                out.push('VUE: '+(app?((app.__vue__?'实例有':'实例无')+' 子节点'+app.children.length+' html'+String(app.innerHTML||'').length):'无容器'));
              }catch(e){out.push('VUE: ?')}
              try{
                var sc=document.scripts||[],ss=[];
                for(var z2=0;z2<sc.length&&z2<20;z2++){var s2=sc[z2].src||'(inline)';ss.push(s2.substring(s2.lastIndexOf('/')+1).substring(0,55))}
                out.push('SCRIPTS: '+sc.length+' '+ss.join(','));
              }catch(e){out.push('SCRIPTS: ?')}
              try{
                var c=window.cmcc,mm=[];
                if(c){for(var kk in c){if(typeof c[kk]==='function'&&mm.length<18)mm.push(kk)}}
                out.push('CMCC: '+(c?(mm.join(',')||'对象无方法'):'无'));
              }catch(e){out.push('CMCC: ?')}
              try{
                var lk=[];for(var q=0;q<localStorage.length&&q<30;q++)lk.push(localStorage.key(q));
                out.push('LSKEYS: '+(lk.join(',')||'空'));
              }catch(e){out.push('LSKEYS: 读取异常')}
              try{
                var keys=['BH/newPlanRemainQry/getNewPlanRemainQry','newPlanRemainQry','getNewPlanRemainQry'],raw='';
                for(var p=0;p<keys.length;p++){var v=localStorage.getItem(keys[p]);if(v&&v.indexOf('flowSceneData')>=0){raw=v;break}}
                if(!raw){for(var q2=0;q2<localStorage.length;q2++){var kk2=localStorage.key(q2);var vv=localStorage.getItem(kk2);if(vv&&vv.indexOf('flowSceneData')>=0){raw=vv;break}}}
                out.push('FLOWKEY: '+(raw?'命中':'无'));
                out.push('FLOWRAW: '+String(raw).replace(/\\s+/g,' ').substring(0,900));
              }catch(e){out.push('FLOWKEY: 无');out.push('FLOWRAW: 读取异常 '+e)}
              return out.join('\\n');
            })();
            """;

    /** 方案 F 的主动唤醒：反复点「可使用」tab + 调 Vue refresh，绝不碰登录按钮。 */
    private static final String WAKE_JS = """
            (function(){
              var n=0;
              var iv=setInterval(function(){
                n++;
                try{
                  var tabs=document.querySelectorAll('li,a,span,div');
                  for(var i=0;i<tabs.length;i++){
                    var tx=(tabs[i].innerText||tabs[i].textContent||'');
                    if(tx.indexOf('可使用')>=0){tabs[i].click();break}
                  }
                }catch(e){}
                try{
                  var app=document.querySelector('#app')||document.querySelector('#root');
                  var r=app&&app.__vue__;
                  if(r){if(typeof r.refresh==='function')r.refresh();else if(typeof r.couponQry==='function')r.couponQry()}
                }catch(e){}
                if(n>=14)clearInterval(iv);
              },600);
            })();
            """;

    static void run() {
        long t0 = System.currentTimeMillis();
        long globalEnd = t0 + TOTAL_MS;

        // ---- 1. 实时原料：不依赖前台真页现场，只读当前会话 ----
        Material mat = collectMaterials(globalEnd);
        try {
            mat.cookie = CookieStore.header(CouponTask.URL);
        } catch (Throwable t) {
            mat.cookie = "";
        }
        try {
            mat.session = CookieStore.sessionString();
        } catch (Throwable t) {
            mat.session = "";
        }
        if (mat.jwt.isEmpty()) {
            try {
                mat.jwt = SsoLogin.ssoJwt();
            } catch (Throwable ignored) {
            }
        }

        String bare = CouponTask.URL;
        String chUrl = bare;
        if (mat.hasChannel()) {
            chUrl = bare + "?c=11&e=99&channelId=" + enc(mat.channelId)
                    + (mat.yx.isEmpty() ? "" : "&yx=" + enc(mat.yx));
        }
        // App 把会话串拼进 XHR 参数而不是靠 Cookie，这里照它的格式做一路对照。
        String sessUrl = mat.hasSession()
                ? chUrl + (chUrl.indexOf('?') >= 0 ? "&" : "?") + mat.session
                : chUrl;

        Map<String, String> hdrFull = new LinkedHashMap<>();
        if (!mat.cookie.isEmpty()) {
            hdrFull.put("Cookie", mat.cookie);
        }
        hdrFull.putAll(CouponScene.headers());

        String earlyBase = EARLY_DIAG_JS;
        String earlyShim = earlyBase + "\n" + SHIM_JS;
        String earlyJwt = mat.jwt.isEmpty() ? earlyShim : earlyShim + "\n" + jwtEarly(mat.jwt);
        String earlyWake = earlyJwt + "\n" + WAKE_JS;

        HookLog.log("COUPON", "一键扫描 · 全方案探针 v2 启动（4.0.7+probe-v2 诊断版）",
                "券探针 v2 已装载 · 纯诊断版（不改正式扫描、不采用命中结果、不发 Release）\n"
                        + "第一轮结论: 页面加载成功、没被踢登录页，但 Vue 不 mount、券接口一个没发；"
                        + "且「无当次现场」使 B~F 变量全退化，6 组实际只测了 1 个变量。\n"
                        + "本轮改为: 原料实时读当前会话 + 先抓 JS 错误定位 mount 失败 + 三路会话注入对照。\n"
                        + "硬约束: 不点『本机号码一键登录』、不调 SsoLogin.ensure 换票、"
                        + "不发起任何新登录请求；会话串日志已脱敏。\n"
                        + "—— 实时原料(本次 run 内存, 不落盘) ——\n" + mat.describe()
                        + "\n—— 前台真页现场(仅参考, 不再作为变量来源) ——\n" + CouponScene.describe()
                        + "\n本轮入口: A/B 裸地址" + (mat.hasChannel() ? " · C/D 带实时渠道参数" : " · 渠道参数无(退化为裸地址)")
                        + (mat.hasSession() ? " · E/F 再叠加会话串 URL 参数" : " · 会话串无(E/F 退化为渠道参数)")
                        + "\nCookie 头: " + (mat.cookie.isEmpty() ? "无" : mat.cookie.length() + " 字符")
                        + " · 真页额外 headers: " + CouponScene.headers().size() + " 项"
                        + " · QWHDSSOJWT: " + (mat.jwt.isEmpty() ? "无" : mat.jwt.length() + " 字符")
                        + "\n方案: A 基线 / B +缺桥垫片 / C +实时渠道参数 / D +会话Cookie回灌 /"
                        + " E +会话串URL参数+JWT / F +主动唤醒"
                        + "\n每方案 " + (PER_PLAN_MS / 1000) + "s 上限, 整轮 " + (TOTAL_MS / 1000) + "s 上限。");

        Plan[] plans = new Plan[]{
                new Plan("A", "基线·裸地址+全桥+错误钩子", bare, null, earlyBase, false),
                new Plan("B", "+缺桥垫片(cardInterface等)", bare, null, earlyShim, false),
                new Plan("C", "+实时渠道参数", chUrl, null, earlyShim, false),
                new Plan("D", "+会话Cookie回灌/头", chUrl, hdrFull, earlyShim, true),
                new Plan("E", "+会话串URL参数+QWHDSSOJWT", sessUrl, hdrFull, earlyJwt, true),
                new Plan("F", "+主动唤醒(点tab/Vue refresh)", sessUrl, hdrFull, earlyWake, true),
        };

        StringBuilder table = new StringBuilder();
        table.append("方案 | 变量 | 落地 | 登录 | 模板 | 券按钮 | 正文 | JS错误 | 资源 | Vue | 耗时 | 结论\n");
        table.append("--- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | ---\n");

        boolean flushed = false;
        String hit = "";
        for (int i = 0; i < plans.length; i++) {
            Plan p = plans[i];
            if (System.currentTimeMillis() >= globalEnd) {
                table.append(p.code).append(" | ").append(p.label)
                        .append(" | 总时限到，跳过 | - | - | - | - | - | - | - | 未跑\n");
                continue;
            }
            if (p.flushCookie && !flushed) {
                try {
                    CookieStore.flushToWebView();
                    flushed = true;
                } catch (Throwable e) {
                    HookLog.log("COUPON", "探针 · Cookie 回灌失败", String.valueOf(e));
                }
            }
            CouponTask.sendProgress("probe", i,
                    "方案 " + p.code + " · " + p.label, 0, 0, plans.length, "");
            Result r = runOne(p, globalEnd);

            int btn = intField(r.site, "COUPONBTN", 0);
            int tpl = intField(r.site, "TPL", 1);
            int login = intField(r.site, "LOGIN", 0) + intField(r.site, "LOGINTXT", 0);
            int channel = intField(r.site, "CHANNEL", 0);
            String landed = shortUrl(fieldOf(r.site, "HREF"));
            String errs = fieldOf(r.site, "ERRS");
            String vue = fieldOf(r.site, "VUE");
            String res = fieldOf(r.site, "RES");
            r.verdict = verdict(r, btn, tpl, login, channel, errs);
            if (hit.isEmpty() && btn > 0 && tpl == 0 && login == 0 && channel == 0) {
                hit = p.code + "（" + btn + " 个券按钮）";
            }

            table.append(p.code).append(" | ").append(p.label)
                    .append(" | ").append(landed)
                    .append(" | ").append(login > 0 || channel > 0 ? "是" : "否")
                    .append(" | ").append(tpl > 0 ? "是" : "否")
                    .append(" | ").append(btn)
                    .append(" | ").append(intField(r.site, "BODYLEN", 0))
                    .append(" | ").append(shortCell(errs, 42))
                    .append(" | ").append(res.isEmpty() ? "-" : res.split("\\|").length + "条")
                    .append(" | ").append(shortCell(vue, 22))
                    .append(" | ").append(r.costMs / 1000L).append("s")
                    .append(" | ").append(r.verdict).append('\n');

            HookLog.log("COUPON", "探针 v2 · 方案 " + p.code + " · " + p.label,
                    "入口(已脱敏): " + safeUrl(p.url)
                            + "\n附加headers: " + (p.headers == null || p.headers.isEmpty()
                            ? "无" : p.headers.keySet().toString())
                            + "\n预注入脚本: " + (p.early == null || p.early.isEmpty() ? "无" : "有")
                            + (p.early != null && p.early.contains("QWHDSSOJWT") ? "(含 JWT)" : "")
                            + (p.early != null && p.early.contains("setInterval") ? "(含唤醒)" : "")
                            + (p.early != null && p.early.contains("new Proxy") ? "(含缺桥垫片)" : "")
                            + "\nCookie回灌: " + (p.flushCookie ? "是" : "否")
                            + "\n耗时: " + r.costMs + "ms"
                            + "\n结论: " + r.verdict
                            + "\n—— 页面现场 ——\n" + (r.site.isEmpty() ? "(空)" : r.site));
        }

        // ---- 2. 辅助诊断：首页流量缓存原文 + 余量查询页明细 ----
        probeAux(globalEnd);

        HookLog.log("COUPON", "一键扫描 · 全方案探针 v2 汇总",
                table.toString()
                        + "\n命中出券的方案: " + (hit.isEmpty() ? "无（6 组都没读到券）" : hit)
                        + "\n实时原料新鲜度: " + (mat.at > 0 ? (System.currentTimeMillis() - mat.at) / 1000L + "s 前采集" : "未采到")
                        + " · 前台真页现场: " + (CouponScene.fresh() ? CouponScene.ageMs() / 1000L + "s 前" : "无（符合纯离屏要求）")
                        + "\n说明: 完整页面现场(含 JS 错误/资源清单/Vue 状态)见上方 6 条『探针 v2 · 方案 X』；"
                        + "流量原文见『探针 · 首页余量缓存原文』与『探针 · 余量查询页明细』。"
                        + "\n本版为诊断测试版，不采用命中结果、不改正式扫描、不发 Release。");

        CouponTask.sendDone(0, 0, plans.length,
                hit.isEmpty() ? "探针 v2 完成 · 6 组均未读到券 · 详见日志『全方案探针 v2 汇总』"
                        : "探针 v2 完成 · 命中 " + hit + " · 详见日志");
    }

    /** 开一张 dev.coc.10086.cn 同源离屏页，现场读 channelId / QWHDSSOJWT / yx。 */
    private static Material collectMaterials(long globalEnd) {
        Material m = new Material();
        if (System.currentTimeMillis() >= globalEnd) {
            return m;
        }
        WebPage page = null;
        try {
            page = WebPage.openProbe(MATERIAL_URL, 5_000L, HOSTS, null, null);
            if (page == null) {
                HookLog.log("COUPON", "探针 v2 · 实时原料", "同源离屏页没起来，原料留空（不拿缓存冒充）");
                return m;
            }
            long end = Math.min(System.currentTimeMillis() + MATERIAL_MS, globalEnd);
            String site = null;
            while (System.currentTimeMillis() < end) {
                site = page.eval(MATERIAL_JS, 4_000L);
                if (site != null && !fieldOf(site, "CHANNELID").isEmpty()) {
                    break;
                }
                sleep(POLL_GAP_MS);
            }
            if (site != null) {
                m.channelId = fieldOf(site, "CHANNELID");
                m.yx = fieldOf(site, "YX");
                m.jwt = fieldOf(site, "JWT");
                m.at = System.currentTimeMillis();
                m.origin = "dev.coc.10086.cn 同源 localStorage（当前会话实时读）";
            }
            HookLog.log("COUPON", "探针 v2 · 实时原料",
                    m.describe()
                            + "\n该 origin localStorage 键: " + (site == null ? "(采集失败)" : fieldOf(site, "LSKEYS"))
                            + "\n说明: 只从当前会话同源 localStorage 现场读，不落盘、不用旧缓存；"
                            + "读不到就如实留空，对应方案退化为裸地址。");
        } catch (Throwable e) {
            HookLog.log("COUPON", "探针 v2 · 实时原料", "异常: " + e + "（原料留空，不兜底造数据）");
        } finally {
            if (page != null) {
                try {
                    page.close();
                } catch (Throwable ignored) {
                }
            }
        }
        return m;
    }

    private static Result runOne(Plan p, long globalEnd) {
        Result r = new Result();
        r.code = p.code;
        long start = System.currentTimeMillis();
        long deadline = Math.min(start + PER_PLAN_MS, globalEnd);
        WebPage page = null;
        try {
            page = WebPage.openProbe(p.url, 9_000L, HOSTS,
                    emptyToNull(p.early),
                    (p.headers == null || p.headers.isEmpty()) ? null : p.headers);
            if (page == null) {
                r.site = "NO_PAGE: 离屏 WebView 建不起来";
            } else {
                String site = null;
                while (System.currentTimeMillis() < deadline) {
                    site = page.eval(PROBE_JS, 5_000L);
                    if (site != null) {
                        int btn = intField(site, "COUPONBTN", 0);
                        int tpl = intField(site, "TPL", 1);
                        int login = intField(site, "LOGIN", 0) + intField(site, "LOGINTXT", 0);
                        if (btn > 0 && tpl == 0) {
                            break;
                        }
                        if (login > 0 && System.currentTimeMillis() - start > 3_000L) {
                            break;
                        }
                    }
                    sleep(POLL_GAP_MS);
                }
                if (site == null) {
                    site = page.eval(PROBE_JS, 5_000L);
                }
                r.site = site == null ? "NO_SITE: 采集脚本无返回" : site;
            }
        } catch (Throwable e) {
            r.site = "ERROR: " + e;
        } finally {
            if (page != null) {
                try {
                    page.close();
                } catch (Throwable ignored) {
                }
            }
            r.costMs = System.currentTimeMillis() - start;
        }
        return r;
    }

    /** 首页同源 flowSceneData 原文 + 余量查询页 DOM/缓存，只诊断不改流量逻辑。 */
    private static void probeAux(long globalEnd) {
        probeOne(globalEnd, "首页余量缓存原文", FlowTask.ENTRY_URL, PROBE_JS,
                new String[]{"FLOWKEY", "FLOWRAW"});
        probeOne(globalEnd, "余量查询页明细", MARGIN_URL, MARGIN_JS,
                new String[]{"BODY", "LSKEYS", "FLOWRAW"});
    }

    private static void probeOne(long globalEnd, String title, String url, String js,
                                 String[] keys) {
        if (System.currentTimeMillis() >= globalEnd) {
            HookLog.log("FLOW", "探针 · " + title, "整轮总时限到，跳过（不影响券结论）");
            return;
        }
        WebPage page = null;
        try {
            page = WebPage.openProbe(url, 6_000L, HOSTS, null, null);
            if (page == null) {
                HookLog.log("FLOW", "探针 · " + title, "离屏页没起来");
                return;
            }
            long end = Math.min(System.currentTimeMillis() + AUX_MS, globalEnd);
            String site = null;
            while (System.currentTimeMillis() < end) {
                site = page.eval(js, 4_000L);
                if (site != null && !site.isEmpty()) {
                    break;
                }
                sleep(POLL_GAP_MS);
            }
            StringBuilder sb = new StringBuilder();
            sb.append("入口: ").append(safeUrl(url)).append('\n');
            for (String k : keys) {
                sb.append(k).append(": ").append(site == null ? "(采集失败)" : fieldOf(site, k)).append('\n');
            }
            sb.append("说明: 首页 flowSceneData[] 只有 3 个汇总项、无分套餐明细；"
                    + "余量查询页是否有更细数据见上面 BODY / FLOWRAW。本轮只诊断，不改流量逻辑。");
            HookLog.log("FLOW", "探针 · " + title, sb.toString());
        } catch (Throwable e) {
            HookLog.log("FLOW", "探针 · " + title, "异常: " + e);
        } finally {
            if (page != null) {
                try {
                    page.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static String verdict(Result r, int btn, int tpl, int login, int channel,
                                  String errs) {
        if (r.site.startsWith("NO_PAGE") || r.site.startsWith("ERROR")) {
            return "页没起来";
        }
        if (login > 0) {
            return "被踢登录/中转页";
        }
        if (channel > 0) {
            return "渠道未开放";
        }
        if (btn > 0 && tpl == 0) {
            return "✓ 券按钮 " + btn + " 个（可能出券）";
        }
        boolean hasErr = !errs.isEmpty() && !errs.equals("无") && !errs.equals("?");
        if (tpl > 0) {
            return hasErr ? "Vue 未渲染（有 JS 报错）" : "Vue 未渲染（无 JS 报错）";
        }
        return "0 张（券接口/按钮都无）";
    }

    /** jwt 注入 localStorage 的预注入脚本；转义引号/反斜杠/换行。 */
    private static String jwtEarly(String jwt) {
        String q = jwt.replace("\\", "\\\\").replace("'", "\\'")
                .replace("\n", "").replace("\r", "");
        return "(function(){try{localStorage.setItem('QWHDSSOJWT','" + q
                + "');window.__cmccSessionJwt=1;}catch(e){}})();";
    }

    private static String emptyToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }

    private static String enc(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '-' || c == '_' || c == '.') {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** 导出日志会被用户外发，会话串/token 一律脱敏。 */
    private static String safeUrl(String u) {
        if (u == null) {
            return "";
        }
        return u.replaceAll("(?i)(JSESSIONID|UID|ticketID|cmccssotoken|QWHD_SESSION_TOKEN|HLWHD_SESSION_TOKEN|Comment)=[^;&\\s]+",
                "$1=<已脱敏>");
    }

    /** 从 PROBE_JS 的逐行输出里取某个键的值。 */
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
        for (String line : site.split("\n")) {
            if (line.startsWith(key + "(")) {
                int c = line.indexOf("): ");
                if (c >= 0) {
                    return line.substring(c + 3).trim();
                }
            }
        }
        return "";
    }

    private static int intField(String site, String key, int def) {
        String v = fieldOf(site, key).replaceAll("[^0-9-]", "");
        if (v.isEmpty()) {
            return def;
        }
        try {
            return Integer.parseInt(v);
        } catch (Throwable e) {
            return def;
        }
    }

    private static String shortUrl(String u) {
        if (u == null || u.isEmpty()) {
            return "?";
        }
        String s = safeUrl(u);
        if (s.length() > 46) {
            s = s.substring(0, 46);
        }
        return s.replace("|", "/");
    }

    private static String shortCell(String a, int max) {
        if (a == null || a.isEmpty()) {
            return "-";
        }
        String s = a.length() > max ? a.substring(0, max) + "…" : a;
        return s.replace("|", "/").replace("\n", " ");
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }
}
