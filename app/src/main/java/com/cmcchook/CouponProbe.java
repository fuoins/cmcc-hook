package com.cmcchook;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * v4.0.7+probe：一键扫描「全方案探针」—— 纯诊断版。
 *
 * <p>用户拍板：与其一轮一轮赌某个假设，不如一次点击把想到的方案全跑一遍，
 * 用一份日志的对照表说话。这里串行跑 A~F 六组累加方案，每组<b>独立开一张
 * 离屏页、独立注入、独立采集、独立 close</b>，互不干扰（{@link OffscreenWeb}
 * 的 current 是单例，必须一张用完再开下一张）。
 *
 * <p><b>硬约束（用户明确要求）</b>：
 * <ul>
 *   <li>纯离屏、界面不动、只点一次「一键扫描」；</li>
 *   <li><b>不点「本机号码一键登录」、不调 {@code SsoLogin.ensure} 换票、
 *       不发起任何新登录请求</b> —— 避免一天内多次登录。所有方案只复用当次
 *       现场已有的登录态（Cookie / headers / jwt），绝不制造新会话；</li>
 *   <li>不落盘任何「入口缓存」：带渠道参数的 URL、headers 都来自
 *       {@link CouponScene} 的本进程内存快照，带采集时间戳，日志写明新鲜度；
 *       没有现场就如实标注、对应变量退化为裸地址，绝不拿旧缓存冒充最新入口；</li>
 *   <li>只读页面渲染好的 DOM / performance 资源记录 / localStorage 缓存，
 *       不解密接口、不拼签名、不发业务请求。</li>
 * </ul>
 *
 * <p>累加变量（后一组 = 前一组 + 一个新变量，命中在哪一步一目了然）：
 * A 基线(裸地址+全桥) → B +渠道参数入口 → C +会话Cookie → D +重放真页headers
 * → E +SSO jwt(localStorage) → F +主动唤醒(点「可使用」tab / Vue refresh)。
 *
 * <p>本版是诊断测试版：不改正式扫描逻辑、不采用命中结果、不动版本号。
 * 得出「哪组能出券」后，下一轮再做精简正式版。
 */
final class CouponProbe {

    private static final String[] HOSTS = {"10086.cn"};
    /** 每方案时间上限（含开页），用户拍板 12s。 */
    private static final long PER_PLAN_MS = 12_000L;
    /** 整轮上限，留一点余量，用户拍板总计 2 分钟。 */
    private static final long TOTAL_MS = 112_000L;
    private static final long POLL_GAP_MS = 900L;
    private static final long FLOW_MS = 9_000L;

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

    /**
     * 现场采集脚本：一次性把「落地 URL / 是否被踢登录 / 券相关请求 / 桥 /
     * 正文 / 券按钮 / 流量缓存原文」全采出来，返回 "键: 值" 逐行的纯文本。
     * 所有值压成单行，Java 侧按前缀解析（避开 evaluateJavascript 的 JSON 引号转义）。
     */
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
                var rs=performance.getEntriesByType('resource')||[],a=[];
                for(var j=0;j<rs.length&&a.length<20;j++){
                  var u=String(rs[j].name||'').split('?')[0],lu=u.toLowerCase();
                  if(lu.indexOf('coupon')>=0||lu.indexOf('voucher')>=0||lu.indexOf('mycard')>=0||lu.indexOf('mycoupons')>=0){
                    var cut=u.indexOf('/',8);a.push(cut>0?u.substring(cut):u);
                  }
                }
                out.push('APIS('+rs.length+'): '+(a.join(' | ')||'无'));
              }catch(e){out.push('APIS: 探测异常 '+e)}
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
              try{
                var keys=['BH/newPlanRemainQry/getNewPlanRemainQry','newPlanRemainQry','getNewPlanRemainQry'],raw='';
                for(var p=0;p<keys.length;p++){var v=localStorage.getItem(keys[p]);if(v&&v.indexOf('flowSceneData')>=0){raw=v;break}}
                if(!raw){for(var q=0;q<localStorage.length;q++){var kk=localStorage.key(q);var vv=localStorage.getItem(kk);if(vv&&vv.indexOf('flowSceneData')>=0){raw=vv;break}}}
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

        boolean hasScene = CouponScene.fresh();
        String bare = CouponTask.URL;
        String sceneUrl = CouponScene.entryUrl();
        String withParams = hasScene && !sceneUrl.isEmpty() ? sceneUrl : bare;
        String cookie = CouponScene.cookie();
        String jwt = CouponScene.jwt();
        Map<String, String> sceneHeaders = CouponScene.headers();

        // C 起把会话 Cookie 刷进 X5/系统内核（只是把已捕获的 Cookie 写回 CookieManager，
        // 不产生任何登录动作）。同时把它作为主文档 Cookie 头带上做对照。
        Map<String, String> hdrCookie = new LinkedHashMap<>();
        if (!cookie.isEmpty()) {
            hdrCookie.put("Cookie", cookie);
        }
        // D 起在 Cookie 之上叠加真页 loadUrl 的额外 headers（可能为空，如实记录）。
        Map<String, String> hdrFull = new LinkedHashMap<>(hdrCookie);
        hdrFull.putAll(sceneHeaders);

        String earlyJwt = jwt.isEmpty() ? "" : jwtEarly(jwt);
        String earlyWake = (earlyJwt.isEmpty() ? "" : earlyJwt + "\n") + WAKE_JS;

        HookLog.log("COUPON", "一键扫描 · 全方案探针启动（4.0.7+probe 诊断版）",
                "券探针 4.0.7+probe 已装载 · 纯诊断版（不改正式扫描、不采用命中结果、不发 Release）\n"
                        + "一次点击串行测试 6 组方案，每组独立开一张离屏页、独立采集、独立关闭。\n"
                        + "硬约束: 不点『本机号码一键登录』、不调 SsoLogin.ensure 换票、"
                        + "不发起任何新登录请求；只用当次现场已有的登录态做对照，避免一天多登。\n"
                        + "现场原料(内存快照, 不落盘, 带时间戳):\n" + CouponScene.describe()
                        + "\n本轮入口选择: " + (hasScene && !withParams.equals(bare)
                        ? "有当次现场 → B~F 用带渠道参数真页 URL" : "无当次现场 → 全部退化用裸地址")
                        + "\nCookie 头: " + (cookie.isEmpty() ? "无（A/B 不刷, C 起也无可刷）" : cookie.length() + " 字符")
                        + " · 真页额外 headers: " + (sceneHeaders.isEmpty() ? "无（D 退化为 C）" : sceneHeaders.size() + " 项")
                        + " · SSO jwt: " + (jwt.isEmpty() ? "无（E 退化为 D）" : jwt.length() + " 字符")
                        + "\n方案: A 基线 / B +渠道参数 / C +会话Cookie / D +真页headers / E +SSO jwt / F +主动唤醒"
                        + "\n每方案 " + (PER_PLAN_MS / 1000) + "s 上限, 整轮 " + (TOTAL_MS / 1000) + "s 上限。");

        Plan[] plans = new Plan[]{
                new Plan("A", "基线·裸地址+全桥", bare, null, null, false),
                new Plan("B", "+渠道参数入口", withParams, null, null, false),
                new Plan("C", "+会话Cookie", withParams, hdrCookie, null, true),
                new Plan("D", "+重放真页headers", withParams, hdrFull, null, true),
                new Plan("E", "+SSO jwt(localStorage)", withParams, hdrFull,
                        earlyJwt.isEmpty() ? null : earlyJwt, true),
                new Plan("F", "+主动唤醒(点tab/Vue refresh)", withParams, hdrFull, earlyWake, true),
        };

        StringBuilder table = new StringBuilder();
        table.append("方案 | 变量 | 落地 | 登录页 | 渠道 | Vue模板 | 券按钮 | 券请求 | 正文 | 耗时 | 结论\n");
        table.append("--- | --- | --- | --- | --- | --- | --- | --- | --- | --- | ---\n");

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
            r.verdict = verdict(r, btn, tpl, login, channel);
            if (hit.isEmpty() && btn > 0 && tpl == 0 && login == 0 && channel == 0) {
                hit = p.code + "（" + btn + " 个券按钮）";
            }

            table.append(p.code).append(" | ").append(p.label)
                    .append(" | ").append(landed)
                    .append(" | ").append(login > 0 ? "是" : "否")
                    .append(" | ").append(channel > 0 ? "未开放" : "否")
                    .append(" | ").append(tpl > 0 ? "是" : "否")
                    .append(" | ").append(btn)
                    .append(" | ").append(shortApis(fieldOf(r.site, "APIS")))
                    .append(" | ").append(intField(r.site, "BODYLEN", 0))
                    .append(" | ").append(r.costMs / 1000L).append("s")
                    .append(" | ").append(r.verdict).append('\n');

            HookLog.log("COUPON", "探针 · 方案 " + p.code + " · " + p.label,
                    "入口: " + p.url
                            + "\n附加headers: " + (p.headers == null || p.headers.isEmpty()
                            ? "无" : p.headers.keySet().toString())
                            + "\n预注入脚本: " + (p.early == null || p.early.isEmpty() ? "无" : "有")
                            + "\nCookie回灌: " + (p.flushCookie ? "是" : "否")
                            + "\n耗时: " + r.costMs + "ms"
                            + "\n结论: " + r.verdict
                            + "\n—— 页面现场 ——\n" + (r.site.isEmpty() ? "(空)" : r.site));
        }

        // 流量诊断：把首页同源的余量缓存原文打进日志，确认有没有 flowSceneData[] 明细。
        probeFlowCache(globalEnd);

        HookLog.log("COUPON", "一键扫描 · 全方案探针汇总",
                table.toString()
                        + "\n命中出券的方案: " + (hit.isEmpty() ? "无（6 组都没读到券）" : hit)
                        + "\n现场原料新鲜度: " + (hasScene ? CouponScene.ageMs() / 1000L + "s 前采集" : "无当次现场")
                        + "\n说明: 完整页面现场见上方 6 条『探针 · 方案 X』；流量缓存原文见『探针 · 首页余量缓存原文』。"
                        + "\n本版为诊断测试版，不采用命中结果、不改正式扫描、不发 Release。");

        CouponTask.sendDone(0, 0, plans.length,
                hit.isEmpty() ? "全方案探针完成 · 6 组均未读到券 · 详见日志『全方案探针汇总』"
                        : "全方案探针完成 · 命中 " + hit + " · 详见日志");
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
                            break; // 命中券列表，没必要再等
                        }
                        if (login > 0 && System.currentTimeMillis() - start > 3_000L) {
                            break; // 已经被踢到登录页，多等也不会自己登录（本版不点登录）
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

    private static void probeFlowCache(long globalEnd) {
        if (System.currentTimeMillis() >= globalEnd) {
            HookLog.log("FLOW", "探针 · 首页余量缓存原文", "整轮总时限到，跳过流量诊断（不影响券结论）");
            return;
        }
        WebPage page = null;
        try {
            page = WebPage.openProbe(FlowTask.ENTRY_URL, 8_000L, HOSTS, null, null);
            if (page == null) {
                HookLog.log("FLOW", "探针 · 首页余量缓存原文", "离屏页没起来");
                return;
            }
            long end = Math.min(System.currentTimeMillis() + FLOW_MS, globalEnd);
            String site = null;
            while (System.currentTimeMillis() < end) {
                site = page.eval(PROBE_JS, 5_000L);
                if (site != null && fieldOf(site, "FLOWKEY").contains("命中")) {
                    break;
                }
                sleep(POLL_GAP_MS);
            }
            HookLog.log("FLOW", "探针 · 首页余量缓存原文",
                    "首页同源 origin: " + FlowTask.ENTRY_URL
                            + "\n命中 flowSceneData 缓存: " + (site == null ? "采集失败" : fieldOf(site, "FLOWKEY"))
                            + "\n缓存原文(前900字, 判断有没有分套餐明细 flowSceneData[]):\n"
                            + (site == null ? "(空)" : fieldOf(site, "FLOWRAW"))
                            + "\n说明: 当前「一键查询」只读首页 4 个汇总数字；若原文含 flowSceneData[] "
                            + "分套餐明细，下一轮可据此解析全字段。本轮只诊断，不改流量逻辑。");
        } catch (Throwable e) {
            HookLog.log("FLOW", "探针 · 首页余量缓存原文", "异常: " + e);
        } finally {
            if (page != null) {
                try {
                    page.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static String verdict(Result r, int btn, int tpl, int login, int channel) {
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
        if (tpl > 0) {
            return "Vue 未渲染（正文仍是模板）";
        }
        return "0 张（券接口/按钮都无）";
    }

    /** jwt 注入 localStorage 的预注入脚本；转义引号/反斜杠/换行。 */
    private static String jwtEarly(String jwt) {
        String q = jwt.replace("\\", "\\\\").replace("'", "\\'")
                .replace("\n", "").replace("\r", "");
        return "(function(){try{localStorage.setItem('QWHDSSOJWT','" + q
                + "');}catch(e){}})();";
    }

    private static String emptyToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
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
        // APIS(12): 这种带资源计数的键，前缀匹配兜底。
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
        String s = u;
        if (s.length() > 46) {
            s = s.substring(0, 46);
        }
        // 表格里竖线会破坏分栏。
        return s.replace("|", "/");
    }

    private static String shortApis(String a) {
        if (a == null || a.isEmpty() || a.equals("无")) {
            return "无";
        }
        String s = a.length() > 60 ? a.substring(0, 60) + "…" : a;
        return s.replace("|", "/");
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }
}
