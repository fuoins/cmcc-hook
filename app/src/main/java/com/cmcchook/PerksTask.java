package com.cmcchook;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 天天领权益（辽宁）—— 天天领 / 周周抢 / 月月享。
 *
 * <p>活动页：{@code https://rr.ln139.cn/rr/actH5/index.html#/workPerksMall?activityCode=36293370}，
 * 入口短链 {@code 0FKMeG} 由门户走
 * {@code ssoJump → dayWeekMoonReceive/entrance.do?activityCode=36293370&pc=… → 302 落地}。
 *
 * <p>和辽宁其它活动不同，这个站的业务请求体是<b>明文</b> form：
 * <pre>
 * POST /rr/lm/dayWeekMoonReceive/receive.do?LJRkIeoW=&lt;动态签名&gt;
 * body: activityCode=36293370&amp;ruleId=…&amp;prizeId=…&amp;prizeName=…
 * resp: {"msg":"操作成功","code":"0000","body":{"prizeRecordId":"…"},"success":true}
 * </pre>
 * 只有 {@code LJRkIeoW} 是页面 JS 现场算的签名（{@code queryJsEncodeConfig.do} 下发的
 * deffirst/defsecond 密钥），Java 层拼不出来，所以仍然<b>只借页面自己的按钮</b>：
 * 点每日卡片上的领取按钮，让页面发 receive.do，模块只在旁边读回执，不重放、不改包。
 *
 * <p>页面结构（2026-11-14 真机 DOM 实测）：
 * <ul>
 *   <li>每日区 {@code .wpm-benefit-section-daily}，奖品卡 {@code .wpm-benefit-card}，
 *       按钮 {@code .wpm-benefit-btn}（内部是 {@code SPAN}，文本只有箭头 {@code >}，
 *       并<b>没有</b>「立即领」三个字 —— 不能靠按钮文案认可领）</li>
 *   <li>卡片整段文本里才带状态词：{@code 已抢光} / {@code 明天再来} / {@code 下周再来} /
 *       {@code 下月再来} / {@code 未开启} / {@code 已领取}</li>
 *   <li>成功弹窗 {@code .wds-panel}「领取成功」+ {@code .wds-ticket}，关闭 {@code .wds-close}</li>
 * </ul>
 *
 * <p>奖池是两个独立 rule（都挂在 {@code ruleList} 下）：{@code ruleList["1"]} 10:00 起，
 * {@code ruleList["2"]} 12:00 起且 {@code weekDay="6,7"}（仅周六日）。两档各算一次，
 * 所以周六/周日最多能领 2 个；引擎领完一档会重新扫卡，12 点档出现且可点就接着领第二个。
 *
 * <p>页面加载时自己会查 {@code queryThirdReceiveRecords.do} / {@code queryLocalRecords.do}，
 * 返回里带 {@code prizeName} 和 {@code getTime}。引擎先读这两份记录，按奖品名把今天已领的那张
 * 标成「今日已领」不再点，剩下的照常领 —— 不会因为今天领过一个就整页收工。
 *
 * <p>周周抢是付费权益（stockOccupy → createPayOrder → 支付），月月享要点亮累计，两者都不在
 * 自动范围内。每日区没有卡片时（停在别的 tab）直接收工，绝不退到全部卡片去点，避免误触付费按钮。
 */
final class PerksTask {

    private static final String PREFS = "cmcc_hook_perks";
    private static final String KEY_ENTRY = "entry_url";
    private static final String KEY_ENTRY_AT = "entry_at";
    private static final String KEY_WORDS = "pick_words";
    private static final long ENTRY_TTL = 24L * 3600_000L;

    static final String ACT_CODE = "36293370";
    static final String ACT_PERKS =
            "https://rr.ln139.cn/rr/actH5/index.html#/workPerksMall?activityCode=" + ACT_CODE;

    private PerksTask() {
    }

    static boolean isPerksPage(String url) {
        if (url == null) {
            return false;
        }
        String low = url.toLowerCase();
        return low.contains("workperksmall") || low.contains("acth5/index.html");
    }

    /** 记住 App 现场打开的带 activityCode 的落地页，下次优先复用（省一次 SSO）。 */
    static void rememberEntry(String url) {
        if (url == null || !isPerksPage(url) || !url.contains("workPerksMall")) {
            return;
        }
        Context ctx = HookLog.context();
        if (ctx == null) {
            return;
        }
        try {
            SharedPreferences p = AccountScope.prefs(ctx, PREFS);
            if (url.equals(p.getString(KEY_ENTRY, ""))) {
                if (System.currentTimeMillis() - p.getLong(KEY_ENTRY_AT, 0L) > 3600_000L) {
                    p.edit().putLong(KEY_ENTRY_AT, System.currentTimeMillis()).apply();
                }
                return;
            }
            p.edit().putString(KEY_ENTRY, url)
                    .putLong(KEY_ENTRY_AT, System.currentTimeMillis()).apply();
        } catch (Throwable ignored) {
        }
    }

    static String entryUrl() {
        Context ctx = HookLog.context();
        if (ctx == null) {
            return SsoShortLink.PERKS;
        }
        try {
            SharedPreferences p = AccountScope.prefs(ctx, PREFS);
            String url = p.getString(KEY_ENTRY, "");
            long at = p.getLong(KEY_ENTRY_AT, 0L);
            if (!url.isEmpty() && System.currentTimeMillis() - at <= ENTRY_TTL
                    && url.contains("activityCode=")) {
                return url;
            }
            return SsoShortLink.PERKS;
        } catch (Throwable e) {
            return SsoShortLink.PERKS;
        }
    }

    private static void forgetEntry() {
        Context ctx = HookLog.context();
        if (ctx == null) {
            return;
        }
        try {
            AccountScope.prefs(ctx, PREFS)
                    .edit().remove(KEY_ENTRY).remove(KEY_ENTRY_AT).apply();
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------- 奖品偏好（关键词）

    /** 优先奖品关键词，原样保存（逗号/空格分隔）。空 = 按页面顺序。 */
    static String words() {
        Context ctx = HookLog.context();
        if (ctx == null) {
            return "";
        }
        try {
            return AccountScope.prefs(ctx, PREFS).getString(KEY_WORDS, "");
        } catch (Throwable e) {
            return "";
        }
    }

    static void setWords(String raw) {
        Context ctx = HookLog.context();
        if (ctx == null) {
            return;
        }
        try {
            String v = raw == null ? "" : raw.trim();
            if (v.isEmpty()) {
                AccountScope.prefs(ctx, PREFS).edit().remove(KEY_WORDS).apply();
            } else {
                AccountScope.prefs(ctx, PREFS).edit().putString(KEY_WORDS, v).apply();
            }
        } catch (Throwable ignored) {
        }
    }

    /** 关键词转 JSON 数组注入页面；最多 8 个，够用且不会把脚本撑爆。 */
    private static String wordsJson() {
        return wordsJson(words());
    }

    /** r45：抢兑偏好由模块界面随命令下发，优先用传入值；为空才回退本机 prefs。 */
    private static String wordsJson(String raw) {
        JSONArray arr = new JSONArray();
        String src = raw == null ? "" : raw.trim();
        if (src.isEmpty()) {
            src = words();
        }
        if (src.isEmpty()) {
            return arr.toString();
        }
        String[] parts = src.split("[,，;；\\s]+");
        for (String one : parts) {
            String w = one == null ? "" : one.trim();
            if (!w.isEmpty() && arr.length() < 8) {
                arr.put(w);
            }
        }
        return arr.toString();
    }

    // --------------------------------------------------------------- 执行

    static TaskRunner.Result run(String entry) {
        return run(entry, 0L, "", "");
    }

    static TaskRunner.Result run(String entry, long targetAt, String rule) {
        return run(entry, targetAt, rule, "");
    }

    /**
     * @param targetAt 抢兑目标时间（epoch 毫秒）；0 表示普通执行，立刻按顺序领。
     * @param rule     目标档位："1"=10 点档，"2"=12 点档，""=不限。
     * @param words    r45 模块界面下发的优先奖品关键词；空则回退本机 prefs。
     */
    static TaskRunner.Result run(String entry, long targetAt, String rule, String words) {
        StringBuilder detail = new StringBuilder();
        detail.append("入口: ").append(brief(entry)).append('\n');
        WebPage page = WebSession.current();
        if (page == null || page.raw() == null) {
            return TaskRunner.Result.fail("天天领权益活动页没起来",
                    detail.toString(),
                    "WebView 没能加载活动页。重开一次中国移动 App 再试。");
        }
        long overall = System.currentTimeMillis() + 25_000L;
        if (!GooseTask.pageAlive(page, detail, "PERKS")) {
            HookLog.log("PERKS", "真页空白 · 重建一次",
                    "存活检查没过，关掉当前 WebView 会话，按入口重开一次真页。");
            String key = WebSession.key();
            WebSession.end();
            WebSession.begin(key == null || key.isEmpty() ? "perks" : key,
                    entry, GooseTask.budget(overall, 12_000L));
            WebPage fresh = WebSession.current();
            if (fresh == null || !GooseTask.pageAlive(fresh, detail, "PERKS")) {
                return TaskRunner.Result.skip("天天领权益 · 真页没起来（空白 / 被跳转顶掉）",
                        detail.toString(),
                        "活动页没加载出来。重开一次中国移动 App，或先在 App 里手动点一次「天天领权益」再执行。");
            }
            page = fresh;
        }

        String landed = page.url() == null ? "" : page.url();
        detail.append("落地页: ").append(brief(landed)).append('\n');
        if (landed.contains("ssoJump") || landed.contains("entrance.do")) {
            // 还停在中转页：多等一会儿 SSO 跳转，不急着判死。
            for (int i = 0; i < 20 && System.currentTimeMillis() < overall; i++) {
                sleep(500L);
                landed = page.url() == null ? "" : page.url();
                if (landed.contains("workPerksMall") || landed.contains("actH5/index.html")) {
                    break;
                }
            }
            detail.append("等 SSO 落地: ").append(brief(landed)).append('\n');
        }
        if (!landed.contains("workPerksMall") && !landed.contains("actH5/index.html")) {
            forgetEntry();
            return TaskRunner.Result.skip("天天领权益 · 没走到活动落地页",
                    detail.toString(),
                    "短链还停在中转页。重开一次中国移动 App 再试；若连续两次都这样，把日志发我。");
        }

        // 关键词先注入，页面脚本读 window.__cmcc_perks_words。
        page.eval("(function(){try{window.__cmcc_perks_words=" + wordsJson(words)
                + ";window.__cmcc_perks_target=" + Math.max(0L, targetAt)
                + ";window.__cmcc_perks_rule='" + (rule == null ? "" : rule) + "'"
                + "}catch(e){}})()", 3_000L);
        page.eval(PERKS_SCRIPT, 8_000L);
        String raw = null;
        long deadline = System.currentTimeMillis() + 150_000L;
        while (System.currentTimeMillis() < deadline) {
            String v = page.eval(
                    "(function(){try{return String(window.__cmcc_perks||'')}catch(e){return ''}})()",
                    5_000L);
            if (v != null && !v.isEmpty()) {
                raw = v;
                break;
            }
            sleep(700L);
        }
        if (raw == null) {
            return TaskRunner.Result.fail("天天领权益脚本超时",
                    detail.append("页面脚本 150 秒没有回报\n").toString(),
                    "页面能打开但脚本没回报。把这段日志发我。");
        }

        JSONObject out;
        try {
            out = new JSONObject(raw);
        } catch (Throwable e) {
            return TaskRunner.Result.fail("天天领权益结果解析失败",
                    detail.append("回报: ").append(brief(raw)).toString(), "");
        }
        writeLines(out.optJSONArray("lines"));

        // r45：把页面读到的两档奖池回传给模块界面，供「优先抢」设置框列出奖品名。
        JSONObject rules = out.optJSONObject("rules");
        if (rules != null && rules.length() > 0) {
            try {
                HookLog.pushPerksRules(rules.toString());
            } catch (Throwable ignored) {
            }
        }

        String stage = out.optString("stage", "");
        int items = out.optInt("items", 0);
        int claimable = out.optInt("claimable", 0);
        String stop = out.optString("stop", "");
        String stopCode = out.optString("stopCode", "");
        String stopMsg = out.optString("stopMsg", "");
        String pageText = out.optString("text", "");
        String url = out.optString("url", "");
        JSONArray got = out.optJSONArray("got");
        int claimed = got == null ? 0 : got.length();
        String pickedWords = (words == null ? "" : words.trim());
        if (pickedWords.isEmpty()) {
            pickedWords = words();
        }

        detail.append("页面: ").append(brief(url)).append('\n');
        detail.append("每日奖品: ").append(items).append(" 个 · 可领 ").append(claimable)
                .append(" 个\n");
        detail.append("派奖偏好: ").append(pickedWords.isEmpty() ? "按页面顺序" : pickedWords)
                .append('\n');
        int grab = out.optInt("grab", 0);
        detail.append("场次: ")
                .append("1".equals(rule) ? "10 点档" : "2".equals(rule) ? "12 点档" : "不限")
                .append(grab > 0 ? " · 到点抢兑" : " · 直接领")
                .append('\n');
        String locked = out.optString("locked", "");
        String rawResp = out.optString("raw", "");
        if (!locked.isEmpty()) {
            detail.append("锁定奖品: ").append(locked)
                    .append("（忽略 status，只发这一个，不换别的）\n");
        }
        if (!rawResp.isEmpty()) {
            detail.append("服务端原始响应: ")
                    .append(rawResp.length() > 900 ? rawResp.substring(0, 900) : rawResp)
                    .append('\n');
        }
        if (!pageText.isEmpty()) {
            detail.append("页面文案: ").append(brief(pageText)).append('\n');
        }

        if ("no-page".equals(stage)) {
            return TaskRunner.Result.skip("天天领权益 · 页面没渲染出每日奖品区",
                    detail.toString(),
                    "页面打开了但每日区没有奖品卡（可能停在周周抢/月月享 tab，或活动信息还没回来）。\n"
                            + "进活动页确认默认停在「天天领」再跑一次；连续出现把日志发我。");
        }
        if ("error".equals(stage)) {
            return TaskRunner.Result.fail("天天领权益脚本异常",
                    detail.append(out.optString("err", "")).toString(),
                    "把日志发我。");
        }

        if (claimed > 0) {
            StringBuilder names = new StringBuilder();
            StringBuilder rec = new StringBuilder();
            for (int i = 0; i < got.length(); i++) {
                JSONObject g = got.optJSONObject(i);
                if (g == null) {
                    continue;
                }
                String nm = g.optString("name", "每日奖品");
                String rid = g.optString("rid", "");
                if (names.length() > 0) {
                    names.append("、");
                }
                names.append(nm);
                rec.append("  · ").append(nm).append(" → code=").append(g.optString("code", ""))
                        .append(rid.isEmpty() ? "" : " rid=" + rid).append('\n');
            }
            String summary = claimed == 1
                    ? "天天领权益 · 已领「" + names + "」"
                    : "天天领权益 · 已领 " + claimed + " 个（" + names + "）";
            detail.append("提交回执:\n").append(rec);
            if (!stop.isEmpty() && !stopCode.isEmpty()) {
                detail.append("第二档: ").append(stopCode)
                        .append(stopMsg.isEmpty() ? "" : " " + stopMsg).append('\n');
            }
            detail.append("说明: 回执 body 里有 prizeRecordId，页面自己说已受理；"
                    + "流量券会下发到「中国移动APP-我的-卡券」。\n");
            return TaskRunner.Result.ok(summary, detail.toString(), summary);
        }

        if (!stopCode.isEmpty()) {
            // 点了但页面给了业务码：照实写，不猜。
            String hint = "页面回执: " + stopCode + (stopMsg.isEmpty() ? "" : " " + stopMsg);
            if (stopCode.equals("0000")) {
                return TaskRunner.Result.skip("天天领权益 · 页面受理了但没返回奖品记录",
                        detail.append(hint).append('\n').toString(),
                        "接口回了 0000 但 body 里没有 prizeRecordId，把日志发我核对。");
            }
            boolean alreadyDone = stopMsg.contains("上限") || stopMsg.contains("已领")
                    || stopMsg.contains("领过") || stopMsg.contains("明天");
            if (alreadyDone) {
                return TaskRunner.Result.idle("天天领权益 · 今天这档已经领过了",
                        detail.append(hint).append('\n').toString(),
                        "两档各 1 次，这一档今天领过了，另一档还能领；12 点后再跑一次试试。");
            }
            boolean soldOut = stopMsg.contains("抢光") || stopMsg.contains("领完");
            if (soldOut) {
                return TaskRunner.Result.skip("天天领权益 · 今日奖品已抢光",
                        detail.append(hint).append('\n').toString(),
                        "这档的份额没了，12 点后再看另一档。");
            }
            return TaskRunner.Result.skip("天天领权益 · 没领到（" + stopCode + "）",
                    detail.append(hint).append('\n').toString(),
                    "页面回执如上。把日志发我，我按这个码补判定。");
        }

        if (grab > 0) {
            String head = locked.isEmpty()
                    ? "天天领权益 · 这一档没抢到"
                    : "天天领权益 · 指定的「" + locked + "」没抢到";
            String tail = stopCode.isEmpty() ? "" : "（业务码 " + stopCode
                    + (stopMsg.isEmpty() ? "" : " " + stopMsg) + "）";
            return TaskRunner.Result.skip(head + tail + (locked.isEmpty() && !stop.isEmpty()
                            ? "（" + stop + "）" : ""),
                    detail.toString(),
                    locked.isEmpty()
                            ? "到点了但没拿到：可能是份额被抢光、页面没刷新出按钮，或停在别的 tab。\n"
                                + "12 点档只在周六日开；下一场再试，或把日志发我看落点。"
                            : "已按你指定的奖品直接发请求，服务端原始响应见上方「服务端原始响应」。\n"
                                + "锁定模式不会改抢别的奖品；把这个码发我，我按实际回执判定它是「未开启」还是「已抢完」。");
        }

        if (claimable == 0) {
            String state = out.optString("state", "");
            return TaskRunner.Result.idle("天天领权益 · 这一轮没有可领的奖品"
                            + (state.isEmpty() ? "" : "（" + state + "）"),
                    detail.toString(),
                    "卡片上都是「今日已领 / 已抢光 / 明天再来」：10 点档已领就等 12 点档，"
                            + "周末 12 点后再跑一次。");
        }
        // 有可点按钮但点了没有任何回执。
        return TaskRunner.Result.fail("天天领权益 · 点了领取但没收到 receive.do 回执",
                detail.toString(),
                "按钮点了却没发出领取请求：可能弹了二次确认弹窗，或按钮在图片层上。\n"
                        + "把这份完整日志发我，我按页面实际 DOM 收敛选择器。");
    }

    private static void writeLines(JSONArray lines) {
        if (lines == null || lines.length() == 0) {
            return;
        }
        StringBuilder chunk = new StringBuilder();
        int part = 1;
        for (int i = 0; i < lines.length(); i++) {
            String one = lines.optString(i, "");
            if (chunk.length() + one.length() + 1 > 4000) {
                HookLog.log("PERKS", "天天领权益执行明细 " + part, chunk.toString());
                chunk.setLength(0);
                part++;
            }
            chunk.append(one).append('\n');
        }
        if (chunk.length() > 0) {
            HookLog.log("PERKS", "天天领权益执行明细 " + part, chunk.toString());
        }
    }

    private static String brief(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= 200 ? text : text.substring(0, 200) + "…";
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }

    /**
     * 页面内脚本：等每日区渲染 → 读「今天已领」记录 → 按关键词挑卡片 → 点它读 receive.do →
     * 再扫一轮（12 点档），最多领 2 次。
     *
     * <p>不拼 LJRkIeoW、不改请求体；只装一个只读 XHR 钩子记录
     * receive.do / receiveWithRecord.do 的状态码、响应体和请求体。
     *
     * <p>判定不依赖按钮文案（真实按钮里只有一个 {@code >}）：卡片整段文本命中
     * 抢光/明天再来/未开启 等状态词即视为不可领，否则有可见按钮就算可领。
     */
    private static final String PERKS_SCRIPT = """
            (async function(){
              var ID='__cmcc_perks';
              window[ID]='';
              var L=[], NET=[], WORDS=[], TARGET=0, RULE='', ACT='36293370';
              try{
                if(window.__cmcc_perks_words&&window.__cmcc_perks_words.length){
                  WORDS=window.__cmcc_perks_words;
                }
                TARGET=Number(window.__cmcc_perks_target||0)||0;
                RULE=String(window.__cmcc_perks_rule||'');
              }catch(e){}
              function line(s){ L.push(String(s)) }
              function cut(s,n){ s=String(s==null?'':s); return s.length>n?s.slice(0,n)+'…':s }
              function wait(ms){ return new Promise(function(r){ setTimeout(r,ms) }) }
              function txt(e){ try{ return String(e.innerText||e.textContent||'').replace(/\\s+/g,'') }catch(x){ return '' } }
              function vis(e){ try{ var r=e.getBoundingClientRect(); return r.width>2&&r.height>2 }catch(x){ return false } }
              function done(o){ try{ o.lines=L;
                try{ if(typeof RULES!=='undefined'&&RULES) o.rules=RULES }catch(e){}
                window[ID]=JSON.stringify(o) }catch(e){ window[ID]='{"stage":"crash","lines":[]}' } }

              // 只读回执钩子：不改请求、不改响应，只把 receive.do 记下来。
              (function(){
                try{
                  if(window.__cmcc_perks_netHooked){ NET=window.__cmcc_perks_net||[]; return }
                  window.__cmcc_perks_netHooked=1;
                  window.__cmcc_perks_net=NET;
                  var XO=window.XMLHttpRequest;
                  if(!XO||!XO.prototype) return;
                  var open0=XO.prototype.open, send0=XO.prototype.send;
                  XO.prototype.open=function(m,u){
                    try{ this.__cmccM=String(m||''); this.__cmccU=String(u||'') }catch(e){}
                    return open0.apply(this,arguments);
                  };
                  XO.prototype.send=function(b){
                    var x=this, q='';
                    try{ q=String(b||'') }catch(e){}
                    try{
                      x.addEventListener('load',function(){
                        var u=''; try{ u=String(x.__cmccU||'') }catch(e){}
                        var t=''; try{ t=String(x.responseText||'') }catch(e){}
                        var st=0; try{ st=x.status }catch(e){}
                        NET.push({m:String(x.__cmccM||''),u:u,s:st,b:t,q:q,t:Date.now()});
                        if(NET.length>80) NET.shift();
                      });
                    }catch(e){}
                    return send0.apply(this,arguments);
                  };
                }catch(e){}
              })();

              function netHit(re){
                var out=[];
                for(var i=0;i<NET.length;i++){ if(re.test(String(NET[i].u||''))) out.push(NET[i]) }
                return out
              }
              function jsonOf(rec){
                var d=null; try{ d=JSON.parse(String(rec&&rec.b||'')) }catch(e){}
                return d
              }

              // 每日奖品清单：DOM 没渲染时的兜底信息来源。
              function prizeListFromInfo(){
                var rs=netHit(/dayWeekMoonReceive\\/queryActivityInfo\\.do/);
                for(var i=rs.length-1;i>=0;i--){
                  var d=jsonOf(rs[i]), body=d&&d.body;
                  if(!body) continue;
                  var rl=body.ruleList||{}, day=rl['1']||rl[1];
                  if(!day||!day.length) continue;
                  var pl=day[0].prizeList||[];
                  if(pl.length) return pl;
                }
                return null
              }

              // 两档奖池：ruleList["1"]=10 点档(receive.do)，["2"]=12 点档(stockOccupy.do，周六日)。
              // 保留 prizeId/status/ruleType —— 直发请求体要用，不能只留奖品名。
              function parseRuleList(rl){
                var out={}, any=0;
                for(var key in rl){
                  if(!rl.hasOwnProperty(key)) continue;
                  var arr=rl[key];
                  if(!arr||!arr.length) continue;
                  var r=arr[0], names=[], prizes=[], pl=r.prizeList||[];
                  for(var j=0;j<pl.length;j++){
                    var p=pl[j]||{}, nm=String(p.prizeName||'').replace(/\\s+/g,'');
                    if(nm) names.push(nm);
                    prizes.push({name:String(p.prizeName||''),id:String(p.prizeId||''),
                                 status:String(p.status==null?'':p.status)});
                  }
                  out[key]={ruleId:String(r.ruleId||''),ruleType:String(r.ruleType||''),
                            weekDay:String(r.weekDay||''),begin:String(r.beginTime||''),
                            end:String(r.endTime||''),names:names,prizes:prizes};
                  any++;
                }
                return any?out:null
              }
              function rulesFromInfo(){
                var rs=netHit(/dayWeekMoonReceive\\/queryActivityInfo\\.do/);
                for(var i=rs.length-1;i>=0;i--){
                  var d=jsonOf(rs[i]), body=d&&d.body;
                  if(!body) continue;
                  var o=parseRuleList(body.ruleList||{});
                  if(o) return o;
                }
                return null
              }
              var RULES=rulesFromInfo();
              function fmtTime(ms){
                function p(n){ n=Number(n); return (n<10?'0':'')+n }
                var d=new Date(Number(ms)||0);
                return p(d.getHours())+':'+p(d.getMinutes())+':'+p(d.getSeconds())
              }

              // 页面自己查的「已领记录」，含 prizeName / getTime。
              function records(){
                var out=[], re1=/dayWeekMoonReceive\\/queryThirdReceiveRecords\\.do/,
                    re2=/dayWeekMoonReceive\\/queryLocalRecords\\.do/;
                var rs=netHit(re1).concat(netHit(re2));
                for(var i=0;i<rs.length;i++){
                  var d=jsonOf(rs[i]), body=d&&d.body;
                  if(!body||!body.records) continue;
                  for(var j=0;j<body.records.length;j++) out.push(body.records[j]);
                }
                return out
              }
              function todayStr(){
                function p(n){ n=Number(n); return (n<10?'0':'')+n }
                var d=new Date();
                return d.getFullYear()+'-'+p(d.getMonth()+1)+'-'+p(d.getDate())
              }

              // 完整触摸序列：页面按钮是 Vue 组件，只派发 click 常常没反应。
              function fire(e){
                try{ e.scrollIntoView({block:'center'}) }catch(x){}
                var r=null; try{ r=e.getBoundingClientRect() }catch(x){}
                var cx=r?(r.left+r.width/2):0, cy=r?(r.top+r.height/2):0;
                var tgt=e;
                try{ var h=document.elementFromPoint(cx,cy); if(h) tgt=h }catch(x){}
                var touch=null;
                try{
                  if(typeof Touch==='function'){
                    touch=new Touch({identifier:1,target:tgt,clientX:cx,clientY:cy,
                                     pageX:cx,pageY:cy,screenX:cx,screenY:cy});
                  }
                }catch(x){}
                var fake={identifier:1,target:tgt,clientX:cx,clientY:cy,
                          pageX:cx,pageY:cy,screenX:cx,screenY:cy};
                function tl(){ return [touch?touch:fake] }
                function mk(type){
                  var o={bubbles:true,cancelable:true,view:window,clientX:cx,clientY:cy,
                         button:0,buttons:type==='touchend'?0:1};
                  if(type==='touchend'){ o.touches=[]; o.targetTouches=[] }
                  else { o.touches=tl(); o.targetTouches=tl() }
                  o.changedTouches=tl();
                  return o;
                }
                ['touchstart','touchend'].forEach(function(tp){
                  try{
                    var ev=null;
                    try{ ev=new TouchEvent(tp,mk(tp)) }catch(x){}
                    if(!ev){
                      ev=new Event(tp,{bubbles:true,cancelable:true});
                      var o=mk(tp);
                      ['touches','targetTouches','changedTouches','clientX','clientY']
                        .forEach(function(k){
                          try{ Object.defineProperty(ev,k,{value:o[k],configurable:true}) }catch(x){}
                        });
                    }
                    tgt.dispatchEvent(ev);
                  }catch(x){}
                });
                ['mousedown','mouseup','click'].forEach(function(tp){
                  try{
                    tgt.dispatchEvent(new MouseEvent(tp,
                      {bubbles:true,cancelable:true,view:window,clientX:cx,clientY:cy}));
                  }catch(x){}
                });
                if(tgt!==e){ try{ e.click() }catch(x){} }
                return String(tgt.tagName||'')+'.'+String(tgt.className||'').slice(0,40);
              }

              var t0=Date.now();
              while(Date.now()-t0<25000){
                if(document.querySelector('.wpm-benefit-card')) break;
                await wait(700);
              }
              var bodyTxt=String((document.body&&document.body.innerText)||'').replace(/\\s+/g,' ').slice(0,300);
              var cards=document.querySelectorAll('.wpm-benefit-card');
              var PL=prizeListFromInfo();
              if(!cards.length){
                line('页面没渲染出每日奖品卡（body: '+cut(bodyTxt,120)+'）');
                if(PL&&PL.length){
                  line('接口里有 '+PL.length+' 个每日奖品:');
                  for(var z=0;z<PL.length;z++){
                    line('  · '+cut(String(PL[z].prizeName||''),30)
                         +' | status='+String(PL[z].status||''));
                  }
                }
                done({stage:'no-page', text:bodyTxt, items:PL?PL.length:0,
                      url:String(location.href)});
                return;
              }

              // 只认每日区。周周抢是付费权益，宁可不动也不能误点。
              var sec=document.querySelector('.wpm-benefit-section-daily');
              if(!sec||!sec.querySelectorAll('.wpm-benefit-card').length){
                line('每日区没有奖品卡（可能停在周周抢 / 月月享 tab）');
                line('页面卡片总数: '+cards.length);
                done({stage:'no-page', text:bodyTxt, items:0, url:String(location.href)});
                return;
              }

              var BLOCK=/已抢光|抢光|已领完|领完|明天再来|下周再来|下月再来|未开启|已领取|已领过|已结束|敬请期待|已达上限|暂无库存/;
              var OPEN=/立即领|立即领取|马上领|马上领取|去领取|^领取$|^抢$|^>|›|»/;
              var SKIP=/规则|说明|详情|更多|协议|帮助|记录|明细|我的奖品/;

              function stripState(s){
                var t=String(s||'');
                try{ t=t.replace(new RegExp(BLOCK.source,'g'),'') }catch(e){}
                return t.replace(/[>›»]/g,'').slice(0,26)
              }
              // 卡片名优先对接口清单，接口没抓到就从卡片文本里剥掉状态词。
              function nameOf(cardTxt){
                var t=String(cardTxt||'');
                if(PL){
                  for(var i=0;i<PL.length;i++){
                    var nm=String(PL[i].prizeName||'').replace(/\\s+/g,'');
                    if(nm && t.indexOf(nm)>=0) return PL[i].prizeName;
                  }
                }
                return stripState(t)
              }

              // 今天已领：按奖品名对上卡片，只跳过那一张，不整页收工。
              var GOT={}, today=todayStr(), recs=records();
              for(var r1=0;r1<recs.length;r1++){
                var g=String(recs[r1].getTime||'');
                var nm2=String(recs[r1].prizeName||'').replace(/\\s+/g,'');
                if(g.slice(0,10)===today && nm2){ GOT[nm2]=g.slice(11,16) }
              }
              var gotN=0;
              for(var gk in GOT){ if(GOT.hasOwnProperty(gk)) gotN++ }
              line('今日已领记录: '+gotN+' 条');
              for(var gk2 in GOT){
                if(GOT.hasOwnProperty(gk2)) line('  · '+gk2+' @'+GOT[gk2]);
              }

              function scan(){
                var list=sec.querySelectorAll('.wpm-benefit-card'), out=[];
                for(var i=0;i<list.length;i++){
                  var c=list[i], cardTxt=txt(c);
                  var name=nameOf(cardTxt), gotAt='';
                  for(var gk in GOT){
                    if(GOT.hasOwnProperty(gk) && gk && cardTxt.indexOf(gk)>=0){ gotAt=GOT[gk]; break }
                  }
                  var ruleTag='';
                  if(RULES){
                    for(var rk in RULES){
                      if(!RULES.hasOwnProperty(rk)) continue;
                      var ns=RULES[rk].names||[];
                      for(var ni=0;ni<ns.length;ni++){
                        if(ns[ni]&&cardTxt.indexOf(ns[ni])>=0){ ruleTag=rk; break }
                      }
                      if(ruleTag) break;
                    }
                  }
                  var blocked=BLOCK.test(cardTxt)||!!gotAt;
                  var bs=c.querySelectorAll('.wpm-benefit-btn,button,a,[role=button]');
                  var btn=null,label='',first=null;
                  for(var j=0;j<bs.length;j++){
                    var b=bs[j], bt=txt(b);
                    if(!vis(b)) continue;
                    if(SKIP.test(bt)) continue;
                    if(!first) first=b;
                    if(!blocked && OPEN.test(bt)){ btn=b; label=bt; break }
                  }
                  // 真机按钮里只有「>」，文案认不出来：卡片没状态词就算能点。
                  if(!btn && !blocked && first){ btn=first; label=txt(first)||'>' }
                  if(!label){
                    if(gotAt){ label='今日已领@'+gotAt }
                    else{
                      var m=null; try{ m=cardTxt.match(BLOCK) }catch(e){}
                      label=m?m[0]:(first?'按钮无文案':'无按钮');
                    }
                  }
                  out.push({el:c,btn:btn,name:name,label:label,can:!!btn&&!blocked,
                            got:gotAt,rule:ruleTag});
                }
                return out
              }
              function stateOf(rows){
                var st={};
                for(var i=0;i<rows.length;i++){ st[rows[i].label]=(st[rows[i].label]||0)+1 }
                var out=[];
                for(var k in st){ if(st.hasOwnProperty(k)) out.push(k+'×'+st[k]) }
                return out.join(' / ')
              }
              // 关键词按填写顺序优先；全没命中就退回第一个可点的。
              function pick(rows){
                var pool=[];
                for(var i=0;i<rows.length;i++){
                  if(!rows[i].can) continue;
                  // 明确属于另一档的卡不点：抢 10 点档时别把 12 点档点了。
                  if(RULE && rows[i].rule && rows[i].rule!==RULE) continue;
                  pool.push(rows[i]);
                }
                if(!pool.length) return null;
                for(var w=0;w<WORDS.length;w++){
                  var kw=String(WORDS[w]==null?'':WORDS[w]).replace(/\\s+/g,'');
                  if(!kw) continue;
                  for(var i2=0;i2<pool.length;i2++){
                    if(pool[i2].name.indexOf(kw)>=0) return pool[i2];
                  }
                }
                return pool[0]
              }

              // ===== 直发通道：页面上下文发同源 XHR，不等 DOM 按钮亮 =====
              // 签名 LJRkIeoW 每次请求都变，无法凭空拼；从页面自己发过的请求里借一条。
              function anySign(){
                var re=/[?&](LJRkIeoW=[^&]+)/, i, m;
                for(i=NET.length-1;i>=0;i--){
                  m=String(NET[i].u||'').match(re); if(m) return m[1];
                }
                try{
                  var es=performance.getEntriesByType('resource');
                  for(i=es.length-1;i>=0;i--){
                    m=String(es[i].name||'').match(re); if(m) return m[1];
                  }
                }catch(e){}
                return ''
              }
              function enc(s){ try{ return encodeURIComponent(String(s==null?'':s)) }catch(e){ return '' } }
              function xhrPost(name, form, sign){
                return new Promise(function(resolve){
                  var url='https://rr.ln139.cn/rr/lm/dayWeekMoonReceive/'+name+'.do'+(sign?('?'+sign):'');
                  var out={u:url,q:form,s:0,b:'',t:Date.now(),done:false};
                  var x=null;
                  function fin(){
                    if(out.done) return; out.done=true;
                    NET.push({m:'POST',u:url,s:out.s,b:out.b,q:form,t:Date.now()});
                    if(NET.length>80) NET.shift();
                    resolve(out);
                  }
                  try{
                    x=new XMLHttpRequest();
                    x.open('POST',url,true);
                    x.setRequestHeader('Content-Type','application/x-www-form-urlencoded');
                    x.onreadystatechange=function(){
                      if(x.readyState!==4) return;
                      out.s=x.status;
                      try{ out.b=String(x.responseText||'') }catch(e){}
                      fin();
                    };
                    x.send(form);
                  }catch(e){ out.err=String(e); fin() }
                  setTimeout(function(){ try{ if(x&&x.readyState!==4) x.abort() }catch(e){}; fin() },4500);
                });
              }
              // 主动查一次奖池：查询类，到点前发也安全，顺带拿到可借的签名。
              async function freshRules(){
                var rec=await xhrPost('queryActivityInfo','activityCode='+ACT,anySign());
                var d=jsonOf(rec), body=d&&d.body;
                if(body&&body.ruleList) return parseRuleList(body.ruleList);
                return null
              }
              function formOf(rule,p){
                return 'activityCode='+ACT+'&ruleId='+enc(rule.ruleId)
                      +'&prizeId='+enc(p.id)+'&prizeName='+enc(p.name)+'&provinceCode=91';
              }
              function judge(rec){
                var d=jsonOf(rec);
                var code=d?String(d.code||''):'', msg=d?String(d.msg||''):'';
                var rid=(d&&d.body&&d.body.prizeRecordId)?String(d.body.prizeRecordId):'';
                var txt=msg+' '+code;
                return {ok:code==='0000'&&!!rid, code:code, msg:msg, rid:rid,
                        terminal:/上限|已领|领过|明天|抢光|领完|存在未支付订单|已抢完|已达上限/.test(msg),
                        notStart:/未开始|未开启|未到时间|不在活动|活动时间|未开放|未到/.test(msg),
                        signErr:/签名|非法|鉴权|未登录|会话|失效|过期|LJRk|token|验签|重复请求|频繁/i.test(txt)};
              }
              // r46：关键词锁定在「全部奖品」里找，不看 status。
              // 用户明确指定了奖品，就不能因为 status≠1 而悄悄改抢别的（上次 2.88→花小猪）。
              // status 是页面态、且抢兑时拿的是到点前旧快照，本来就不该参与决策。
              function lockPrize(rule){
                var ps=(rule&&rule.prizes)||[];
                for(var w=0;w<WORDS.length;w++){
                  var kw=String(WORDS[w]==null?'':WORDS[w]).replace(/\\s+/g,'');
                  if(!kw) continue;
                  for(var i=0;i<ps.length;i++){
                    if(String(ps[i].name).replace(/\\s+/g,'').indexOf(kw)>=0) return ps[i];
                  }
                }
                return null
              }
              // 没设关键词时的默认行为：只挑 status=1，按页面顺序。
              function pickPrize(rule, skip){
                var ps=(rule&&rule.prizes)||[], pool=[], i, w, k;
                for(i=0;i<ps.length;i++){
                  if(skip[ps[i].id]) continue;
                  if(String(ps[i].status)==='1') pool.push(ps[i]);
                }
                if(!pool.length) return null;
                for(w=0;w<WORDS.length;w++){
                  var kw=String(WORDS[w]==null?'':WORDS[w]).replace(/\\s+/g,'');
                  if(!kw) continue;
                  for(k=0;k<pool.length;k++){
                    if(String(pool[k].name).replace(/\\s+/g,'').indexOf(kw)>=0) return pool[k];
                  }
                }
                return pool[0]
              }
              // 一档一次直发（含重试判定），返回 {ok,name,code,msg,rid,tries,stop,raw,locked}
              async function burstOnce(rule, path, hardStop){
                var skip={}, tries=0, lastCode='', lastMsg='', lastRaw='';
                var locked=lockPrize(rule), lockedName=locked?locked.name:'';
                if(locked) line('锁定奖品: '+lockedName+' · status='+locked.status+'（忽略 status，照发）');
                // 锁定模式只试 3 次就收：目的是看清服务端响应，不做无意义高频。
                var maxTries=locked?3:100000;
                while(Date.now()<hardStop && tries<maxTries){
                  var p=locked||pickPrize(rule,skip);
                  if(!p) return {ok:false,stop:'这一档没有可抢的奖品',tries:tries,
                                 code:lastCode,msg:lastMsg,raw:lastRaw,locked:lockedName};
                  var rec=await xhrPost(path,formOf(rule,p),anySign());
                  var jd=judge(rec); tries++; lastCode=jd.code; lastMsg=jd.msg;
                  lastRaw=String(rec.b||'');
                  line('直发#'+tries+' '+cut(p.name,16)+' → status='+rec.s
                       +' code='+jd.code+' '+cut(jd.msg,26));
                  // r46：把服务端原始响应体完整打出来，试 2.88 就看这一行。
                  line('原始响应: '+cut(lastRaw,400));
                  if(jd.ok){
                    line('直发命中: '+p.name+' rid='+jd.rid);
                    return {ok:true,name:p.name,code:jd.code,msg:jd.msg,rid:jd.rid,
                            tries:tries,stop:'',raw:lastRaw,locked:lockedName};
                  }
                  // 锁定模式：只认这个奖品，任何非成功响应都如实记下并停，绝不换别的。
                  if(locked){
                    return {ok:false,stop:'指定的奖品没抢到（'+jd.code+' '+cut(jd.msg,30)+'）',
                            tries:tries,code:jd.code,msg:jd.msg,raw:lastRaw,locked:lockedName};
                  }
                  if(jd.signErr){
                    return {ok:false,stop:'签名被拒('+jd.code+' '+jd.msg+')',tries:tries,
                            code:jd.code,msg:jd.msg,raw:lastRaw,locked:'',fallback:1};
                  }
                  if(jd.terminal && !jd.notStart){
                    return {ok:false,stop:jd.msg||('业务码 '+jd.code),tries:tries,
                            code:jd.code,msg:jd.msg,raw:lastRaw,locked:''};
                  }
                  if(/存在未支付订单|已抢光|抢光|已达上限/.test(jd.msg)) skip[p.id]=1;
                  await wait(Date.now()<hardStop-8000?160:350);
                }
                return {ok:false,stop:'直发窗口结束没拿到',tries:tries,
                        code:lastCode,msg:lastMsg,raw:lastRaw,locked:lockedName};
              }
              // ===== 直发通道结束 =====

              async function claimOne(t, quick){
                var before=NET.length, clickAt=Date.now();
                line('点「'+cut(t.label,10)+'」: '+cut(t.name,40));
                var hit=fire(t.btn);
                line('点击落点: '+hit);
                await wait(1200);
                if(NET.length===before){
                  var conf=document.querySelectorAll('button,a,div,span,[role=button]');
                  for(var q=0;q<conf.length&&q<400;q++){
                    var e2=conf[q], t2=txt(e2);
                    if(!t2||!vis(e2)) continue;
                    if(/^(确认领取|确认|立即领取|确定)$/.test(t2)){
                      line('二次确认: '+t2);
                      fire(e2);
                      break;
                    }
                  }
                }
                var rec=null;
                for(var n=0;n<(quick?12:24);n++){
                  await wait(500);
                  for(var m=before;m<NET.length;m++){
                    if(/dayWeekMoonReceive\\/(receive|receiveWithRecord|stockOccupy)\\.do/.test(String(NET[m].u||''))){
                      if(NET[m].t && NET[m].t<clickAt-2000) continue;
                      rec=NET[m];
                    }
                  }
                  if(rec) break;
                }
                var r={name:t.name,code:'',msg:'',rid:'',ok:false};
                if(rec){
                  var d=jsonOf(rec);
                  r.code=d?String(d.code||''):'';
                  r.msg=d?String(d.msg||''):'';
                  if(d&&d.body&&d.body.prizeRecordId){
                    r.rid=String(d.body.prizeRecordId);
                    r.ok=true;
                  }
                  line('提交回执: '+String(rec.u||'').split('/').pop().split('?')[0]
                       +' status='+rec.s+' code='+r.code+' msg='+r.msg
                       +(r.rid?(' rid='+r.rid):''));
                  if(!r.ok) line('回执原文: '+cut(String(rec.b||''),200));
                } else {
                  line('提交回执: 12 秒内没有领取回执（可能只弹了确认层或按钮没触发）');
                }
                return r
              }

              var first=scan(), canN=0;
              for(var k=0;k<first.length;k++){ if(first[k].can) canN++ }
              line('每日奖品: '+first.length+' 个 · 可领 '+canN+' 个');
              for(var k2=0;k2<first.length;k2++){
                line('  ['+k2+'] '+cut(first[k2].name,30)
                     +' | '+(first[k2].can?'可领·':'不可领·')+first[k2].label);
              }

              var got=[], stop='', stopCode='', stopMsg='';

              // 抢兑模式：不等按钮亮，直接在页面上下文把领取请求发出去。
              // 12 点档走 stockOccupy.do，10 点档走 receive.do（真机抓包实证）。
              if(TARGET>0){
                line('抢兑: 目标 '+fmtTime(TARGET)+' · 档位 '+(RULE||'不限'));
                var rule=RULES?RULES[RULE]:null;
                if(!rule){
                  var fr=await freshRules();
                  if(fr){ RULES=fr; rule=fr[RULE];
                    line('奖池: 主动查 queryActivityInfo 拿到 '+Object.keys(fr).length+' 档'); }
                }
                var sg=anySign();
                line('通道: 页面上下文直发（借页面签名）· 签名 '+(sg?'已借到':'没借到'));
                if(rule){
                  line('规则: ruleId='+cut(rule.ruleId,12)+' ruleType='+rule.ruleType
                       +' 奖品='+rule.prizes.length+' begin='+rule.begin+' weekDay='+rule.weekDay);
                } else {
                  line('规则: 没拿到这一档的奖池，只能退回按钮点击');
                }

                // 贴到目标前 350ms：开始高频发（提前量用于抵消网络/服务端时钟偏差）。
                var path=(rule&&String(rule.ruleType)==='2')?'stockOccupy':'receive';
                if(rule&&sg){
                  var fireAt=TARGET-350;
                  if(fireAt>Date.now()) line('贴点中: '+fmtTime(Date.now())+' → 开火 '+fmtTime(fireAt));
                  while(Date.now()<fireAt){ await wait(40) }
                  line('开火: '+fmtTime(Date.now())+' · '+path+'.do');
                  var hardStop=TARGET+55000;
                  var br=await burstOnce(rule,path,hardStop);
                  line('直发合计 '+br.tries+' 次'+(br.stop?(' · '+br.stop):''));
                  if(br.raw) line('最终原始响应: '+cut(String(br.raw),500));
                  if(br.ok){
                    got.push({name:br.name,code:br.code,msg:br.msg,rid:br.rid,ok:true});
                  } else if(br.fallback){
                    stop=br.stop; stopCode=br.code; stopMsg=br.msg;
                    line('签名被拒，退回页面按钮（页面自己会生成合法签名）');
                  } else {
                    stop=br.stop; stopCode=br.code; stopMsg=br.msg;
                    if(br.locked) line('锁定模式: 只发指定奖品，不换别的、不点按钮兜底');
                  }
                } else {
                  stop=rule?'没借到页面签名':'没拿到这一档规则表';
                }

                // 兜底：直发没成才扫按钮；已命中就不再动页面。
                if(!got.length && (sg||rule) && !(br && br.locked)){
                  line('兜底: 扫页面按钮点击');
                  var fbStop=Math.max(Date.now()+20000, TARGET+30000), fbTries=0;
                  while(Date.now()<fbStop){
                    var rows0=scan(), t0=pick(rows0);
                    if(t0){
                      var r0=await claimOne(t0,true);
                      if(r0.ok){ got.push(r0); line('兜底抢到: '+cut(r0.name,30)); break }
                      if(r0.code&&/上限|已领|领过|明天|抢光|领完/.test(String(r0.msg||''))){
                        if(!stopCode){ stopCode=r0.code; stopMsg=r0.msg; }
                        break;
                      }
                    } else {
                      fbTries++;
                      if(fbTries%40===0) line('兜底仍在等按钮… 已扫 '+fbTries+' 次');
                    }
                    await wait(150);
                  }
                }
                if(!got.length&&!stop) stop='直发与按钮都没拿到';
                var a0=scan(), aS0=stateOf(a0);
                if(a0.length) line('收工状态: '+aS0);
                done({stage:'done', grab:1, items:first.length, claimable:canN,
                      claimed:got.length, got:got, stop:stop, stopCode:stopCode,
                      stopMsg:stopMsg, state:aS0, text:bodyTxt, url:String(location.href),
                      raw:(br&&br.raw)?String(br.raw):'', locked:(br&&br.locked)?String(br.locked):''});
                return
              }

              // 普通模式（一键跑 / 手动执行）：两档各 1 次，领完一档重扫。
              for(var round=0; round<2; round++){
                var rows=scan(), t=pick(rows);
                if(!t){ stop = round===0 ? '无可领' : ''; break }
                var r=await claimOne(t);
                if(r.ok){
                  got.push(r);
                  await wait(2200);
                } else {
                  stop = round===0 ? '首档没成' : '第二档没成';
                  stopCode=r.code;
                  stopMsg=r.msg;
                  break;
                }
              }

              var after=scan(), afterState=stateOf(after);
              if(after.length) line('收工状态: '+afterState);
              if(got.length) line('本轮已领: '+got.length+' 个');

              await wait(600);
              ['wds-close','dlm-close','mlm-close','wpm-description-close'].forEach(function(cl){
                try{
                  var els=document.querySelectorAll('.'+cl);
                  for(var z=0;z<els.length;z++){ if(vis(els[z])){ fire(els[z]); break } }
                }catch(x){}
              });
              done({stage:'done', items:first.length, claimable:canN, claimed:got.length,
                    got:got, stop:stop, stopCode:stopCode, stopMsg:stopMsg,
                    state:afterState, text:bodyTxt, url:String(location.href)});
            })();
            """;
}
