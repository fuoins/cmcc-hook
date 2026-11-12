package com.cmcchook;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 爱购 AI 豆苗养成记（coc3 画布，2026dmycj）。
 *
 * <p>页面在「爱购商城」画布里，签到领阳光值、浇水施肥养豆苗都在同一页。
 * 画布页的请求带 WASM 生成的 wasmParams / phone 风控头，Java 层拼不出来，
 * 所以这里和 {@link CanvasTask} 一样走「离屏页 + 页面内真实操作」：
 *
 * <ol>
 *   <li>先等页面自己的登录桥走完（cmcc.getUserInfo）；</li>
 *   <li>读页面上的阳光值，作为执行前的基线；</li>
 *   <li>点页面上真实的「签到 / 领取 / 浇水 / 施肥 / 收豆」按钮，每条最多点一次；</li>
 *   <li>复查阳光值和页面文案，并回看 XHR/fetch 是否真有 2xx 回执。</li>
 * </ol>
 *
 * <p>判定只认证据：阳光值变大，或点击后页面确实发出了 2xx 请求。
 * 只是「点了按钮」不算成功，避免又出现 7 日打卡那种「日志写成功、账号没签到」。
 *
 * <p>r19：{@code run()} 开头先走 {@link SeedTasks} 的 coc3 服务端任务直报，
 * 「赚阳光值」列表里的浏览/分享任务改由 {@code getGrowthTaskList} 拉清单、
 * {@code visit}/{@code share} 上报、再复查 {@code isFinish=1} 验收。服务端确认的
 * 数量是最高优先级证据；通道不可用时才回退下面这套 DOM 点击链路，功能不回退。
 */
final class MiaoTask {

    static final String ACT_MIAO =
            "https://dev.coc.10086.cn/coc3/canvas/rightsmarket-h5-canvas/online/2026dmycj"
                    + "?channelCode=P00000153130";

    private MiaoTask() {
    }

    static TaskRunner.Result run(String url) {
        StringBuilder detail = new StringBuilder();
        String entry = (url == null || url.isEmpty()) ? ACT_MIAO : url;
        detail.append("入口: ").append(brief(entry)).append('\n');
        WebPage page = WebSession.current();
        if (page == null || page.raw() == null) {
            return TaskRunner.Result.fail("豆苗养成记 · 活动页没起来",
                    detail.toString(),
                    "离屏 WebView 没能加载活动页。重开一次中国移动 App 再试。");
        }

        // v4.10.0：先做「赚阳光值」列表里的免费任务。这些任务点了会跳到别的
        // 活动页，脚本上下文会被销毁，所以由 Java 侧逐个点 → 停 5 秒 → 拉回本页复查。
        // 全部做完（或没有待做）之后，再注入主脚本做页面内的签到/浇水/收豆。
        //
        // r18：阳光值必须在点击**之前**取一次。主脚本里的基线是脚本启动时才读的，
        // 那时免费任务已经点完，拿它做前后对比等于拿「点完之后」比「点完之后」，
        // 永远判不出点击有没有换来阳光值（r17 日志「160→160 未变」就是这么来的）。
        int sunClick0 = readSun(page);
        // r19：先走 coc3 服务端任务直报（上游 cmcc_seed 的 getGrowthTaskList /
        // visit / share 阶梯）。那三个浏览任务此前靠点 IMG.content-btn，r16 按结构
        // 定位、r18 加阳光值前后对比，仍反复「点了没跳 / 阳光值 160→160」——
        // 它们本质是服务端登记的浏览任务，页面点击只是触发一次上报。
        // 服务端清单复查 isFinish=1 才是硬证据；通道不可用时回退原 DOM 链路。
        SeedTasks.Outcome seed = SeedTasks.run(page, detail);
        int freeTasks = seed.accepted > 0 ? 0 : runFreeTasks(page, entry, detail);
        int sunClick1 = readSun(page);
        detail.append("赚阳光值免费任务: 服务端确认 ").append(seed.confirmed)
                .append(" 个，DOM 点击且真正跳转过 ").append(freeTasks).append(" 个\n");
        if (sunClick0 >= 0 && sunClick1 >= 0) {
            detail.append("阳光值(点击前后): ").append(sunClick0).append(" → ").append(sunClick1)
                    .append(sunClick1 > sunClick0 ? "（+" + (sunClick1 - sunClick0) + "）" : "（未变）")
                    .append('\n');
        }

        page.eval(MIAO_SCRIPT, 8_000L);
        String raw = null;
        String lastBeat = "";
        // r14：95 秒 → 45 秒。这条链路绝大多数在 20 秒内出结果，剩下的一直在等
        // 「页面没动作」，白占一键总时长。到点就判失败，用户再单独点一次即可。
        long deadline = System.currentTimeMillis() + 45_000L;
        while (System.currentTimeMillis() < deadline) {
            String value = page.eval(
                    "(function(){var v=window.__cmcc_miao;return v?String(v):'';})()", 6_000L);
            if (value != null && !value.isEmpty()) {
                boolean beat = false;
                try {
                    JSONObject one = new JSONObject(value);
                    beat = one.optInt("beat", 0) == 1;
                    if (beat) {
                        String last = one.optString("last", "");
                        if (!last.isEmpty() && !last.equals(lastBeat)) {
                            lastBeat = last;
                            detail.append("阶段: ").append(brief(last)).append('\n');
                        }
                    }
                } catch (Throwable ignored) {
                    // 不是 JSON 就当最终回报处理
                }
                if (!beat) {
                    raw = value;
                    break;
                }
            }
            sleep(600L);
        }
        if (raw == null) {
            detail.append("脚本 45 秒没有回报");
            if (!lastBeat.isEmpty()) {
                detail.append("，最后阶段: ").append(brief(lastBeat));
            }
            detail.append('\n');
            return TaskRunner.Result.fail("豆苗养成记 · 页面脚本超时",
                    detail.toString(),
                    "页面能打开但脚本没回报。把完整日志发我。");
        }

        JSONObject out;
        try {
            out = new JSONObject(raw);
        } catch (Throwable e) {
            return TaskRunner.Result.fail("豆苗养成记 · 结果解析失败",
                    detail.append("回报: ").append(brief(raw)).toString(), "");
        }

        writeLines(out.optJSONArray("lines"));

        String stage = out.optString("stage", "");
        if ("no-vue".equals(stage)) {
            return TaskRunner.Result.fail("豆苗养成记 · 页面组件没挂载",
                    detail.append("页面没有 Vue 根组件，可能被重定向或还没渲染完\n").toString(),
                    "在 App 里手动点进一次这个活动，再回来执行任务。");
        }
        if ("error".equals(stage)) {
            return TaskRunner.Result.fail("豆苗养成记 · 脚本异常",
                    detail.append(out.optString("msg", "")).toString(), "把日志发我。");
        }

        int sun0 = out.optInt("sun0", -1);
        int sun1 = out.optInt("sun1", -1);
        int clickTry = out.optInt("clickTry", 0);
        int clickOk = out.optInt("clickOk", 0);
        int netOk = out.optInt("netOk", 0);
        String text0 = out.optString("text0", "");
        String text1 = out.optString("text1", "");
        JSONArray acts = out.optJSONArray("acts");

        detail.append("阳光值: ").append(sun0 < 0 ? "没读到" : String.valueOf(sun0))
                .append(" → ").append(sun1 < 0 ? "没读到" : String.valueOf(sun1)).append('\n');
        detail.append("动作: 点了 ").append(clickTry).append(" 个，页面回执 ").append(netOk)
                .append(" 次\n");
        if (acts != null && acts.length() > 0) {
            detail.append("动作明细: ").append(acts.toString()).append('\n');
        }
        if (!text0.isEmpty()) {
            detail.append("执行前: ").append(brief(text0)).append('\n');
        }
        if (!text1.isEmpty() && !text1.equals(text0)) {
            detail.append("执行后: ").append(brief(text1)).append('\n');
        }

        // 0) r19：服务端清单复查 isFinish=1 是最硬证据，优先于阳光值差与点击回执。
        //    主脚本（签到/浇水/收豆）这一轮照跑完了，这里只决定记不记战果。
        if (seed.confirmed > 0) {
            String summary = "豆苗养成记 · 服务端确认完成 " + seed.confirmed + " 个任务"
                    + (seed.gain > 0 ? "，阳光值 +" + seed.gain : "")
                    + (seed.still > 0 ? "（另有 " + seed.still + " 个状态待刷新）" : "");
            return TaskRunner.Result.ok(summary,
                    detail.append("判定(r19): 以 coc3 getGrowthTaskList 复查 isFinish=1 为准，"
                            + "本轮跳过 DOM 点击链路，不再靠阳光值差或 2xx 回执猜。\n")
                            .toString(), summary);
        }
        // 1) 阳光值变多：最硬的证据。
        if (sun0 >= 0 && sun1 > sun0) {
            String summary = "豆苗养成记 · 阳光值 +" + (sun1 - sun0) + "（" + sun0 + " → " + sun1 + "）";
            return TaskRunner.Result.ok(summary, detail.toString(), summary);
        }
        // 2) 确实点到了按钮，且页面跟着发出了 2xx 业务请求。
        //    注意必须同时有 clickTry：否则页面初始化那几条 2xx 会被当成「做成了」。
        if (netOk > 0 && clickTry > 0) {
            String summary = "豆苗养成记 · 完成 " + Math.max(clickOk, 1) + " 个动作";
            return TaskRunner.Result.ok(summary, detail.toString(), summary);
        }
        // r19：服务端已接受上报但复查状态还没刷新 —— 不记成功，也不许走到下面
        // 「点了按钮但页面没回执」把它误报成失败。本轮标「无需操作」，下轮复查补。
        if (seed.accepted > 0) {
            String summary = "豆苗养成记 · 已上报 " + seed.accepted
                    + " 个任务，服务端状态待刷新";
            return TaskRunner.Result.idle(summary,
                    detail.append("判定(r19): 上报已接受但复查 isFinish 未刷新，本轮不记完成，"
                            + "也不重复点 DOM。\n").toString(), summary);
        }
        // v4.10.0：赚阳光值免费任务已经逐个点了（跳出去停 5 秒再拉回来），
        // 即使页面内主脚本这一轮没读到新的 2xx，也不能判成「没做成」。
        // r17：但「点过」不等于「做成」—— 本轮实测点了 3 个、回执 0 次、阳光值
        // 160 没动，却报了 ✓。现在要求阳光值确实上涨才算完成，否则标无需操作，
        // 让下一轮复查时再补，避免把白点记成战果。
        if (freeTasks > 0) {
            // r18：优先用「点击前 / 点击后」这一对（sunClick0/1），它才是真正对应
            // 免费任务点击的差值；主脚本的 sun0/sun1 只在读不到时兜底。
            int base = sunClick0 >= 0 ? sunClick0 : sun0;
            int now = sunClick1 >= 0 ? sunClick1 : sun1;
            if (base >= 0 && now >= 0 && now <= base) {
                String summary = "豆苗养成记 · 点了 " + freeTasks + " 个任务，阳光值 "
                        + base + "→" + now + " 未变";
                return TaskRunner.Result.idle(summary,
                        detail.append("判定(r18): 点击后阳光值没有上涨，任务多半没被服务端记账，本轮不记完成。\n")
                                .toString(), summary);
            }
            String summary = "豆苗养成记 · 完成 " + freeTasks + " 个赚阳光值任务"
                    + (base >= 0 && now > base ? "（阳光值 +" + (now - base) + "）" : "");
            return TaskRunner.Result.ok(summary, detail.toString(), summary);
        }
        // 3) v4.0.5：判定顺序修正 —— 旧版先判「点了按钮没回执 → 失败」，
        //    导致下面「今天已经做过」的兜底永远走不到（只要点过任何按钮）。
        //    19:24 日志实证：点了「主动领取」0 回执，但首屏是一串「+ 10 已领」
        //    且没有「点击领」待领项，说明页面上本来就没有待领项，不是失败。
        //    先看页面状态，再看点击结果。前面 1)/2) 已经把真做成的拦掉了。
        if (text1.contains("今日已签") || text1.contains("今天已签")
                || text1.contains("已签到") || text1.contains("明天再来")
                || text1.contains("已领取") || text1.contains("已浇")
                || text1.contains("今日已完成") || text1.contains("任务已完成")
                || text1.contains("已全部完成") || text1.contains("已浇过水")
                || text1.contains("阳光值已满") || text1.contains("今日已领完")
                || text1.contains("已领完") || text1.contains("明天继续")) {
            String summary = "豆苗养成记 · 今天已经做过";
            return TaskRunner.Result.idle(summary, detail.toString(), summary);
        }
        boolean noPending = (text1.contains("已领") || text0.contains("已领"))
                && !text1.contains("点击领") && !text0.contains("点击领");
        if (noPending) {
            String summary = "豆苗养成记 · 页面没有待领项（已领完）";
            return TaskRunner.Result.idle(summary,
                    detail.append("判定(v4.0.5): 页面全是「已领」且无「点击领」待领项，\n")
                            .append("点了按钮没回执不是服务端拒绝，而是没有可领的项。\n")
                            .toString(), summary);
        }
        // 4) 点了按钮但页面没反应：判失败，不写成功。
        if (clickTry > 0) {
            return TaskRunner.Result.fail("豆苗养成记 · 点了按钮但页面没回执",
                    detail.toString(),
                    "页面按钮点了但服务端没响应。把完整日志发我，我按页面真实按钮改。");
        }
        String summary = "豆苗养成记 · 页面上没找到可做的按钮";
        return TaskRunner.Result.idle(summary, detail.toString(),
                "页面结构和预期不一致。手动进一次豆苗页面看看有哪些按钮，把日志发我。");
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
                HookLog.log("MIAO", "豆苗页面执行明细 " + part, chunk.toString());
                chunk.setLength(0);
                part++;
            }
            chunk.append(one).append('\n');
        }
        if (chunk.length() > 0) {
            HookLog.log("MIAO", "豆苗页面执行明细 " + part, chunk.toString());
        }
    }

    /** 一轮最多点几个「赚阳光值」任务，防异常情况下无限跳转。 */
    private static final int MIAO_FREE_TRIES = 12;
    /** 浏览类任务的停留门槛：用户实测 5 秒就够。 */
    private static final long MIAO_STAY_MS = 5_000L;

    /**
     * v4.10.0：逐个做「赚阳光值」列表里的免费任务。
     *
     * <p>这些任务点了会跳到别的活动页（26xysrqtyld / ydyhl / fls / wljxmh …），
     * 页面脚本上下文会被销毁，所以跟周周「去逛逛」一样走 Java 侧循环：
     * 点一个 → 停 5 秒 → 拉回豆苗页 → 复查剩余待做数，直到没有待做或收敛。
     *
     * <p>付费订购类（办理铂金会员 / 畅看会员 / 去订购）由脚本按「去订购」按钮
     * 与卡片文案双重排除，只点免费的「去浏览/去分享/去领取/去看看/去邀请/去关注」。
     */
    /** r18：只读阳光值，供「点击前 / 点击后」对比用，不依赖主脚本。 */
    private static final String MIAO_SUN = """
            (function(){
              try{
                var t=String((document.body&&document.body.innerText)||'');
                var m=t.match(/我的阳光值\\s*(\\d+)/)||t.match(/阳光值\\s*(\\d+)/);
                return m?m[1]:'';
              }catch(e){return ''}
            })()
            """;

    /** 读不到返回 -1（页面还在渲染时是正常的，不要当 0）。 */
    private static int readSun(WebPage page) {
        String v = page.eval(MIAO_SUN, 3_000L);
        if (v == null) {
            return -1;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (Throwable e) {
            return -1;
        }
    }

    private static int runFreeTasks(WebPage page, String entry, StringBuilder detail) {
        detail.append("赚阳光值任务: 图片按钮(IMG.content-btn)按结构定位，不靠 innerText\n");
        int done = 0;
        int lastLeft = -1;
        int stall = 0;
        for (int i = 0; i < MIAO_FREE_TRIES; i++) {
            String raw = page.eval(MIAO_FREE_ONE, 10_000L);
            if (raw == null || raw.isEmpty()) {
                detail.append("  免费任务: 点击脚本没回报，停止\n");
                break;
            }
            JSONObject o;
            try {
                o = new JSONObject(raw);
            } catch (Throwable e) {
                detail.append("  免费任务: 回报解析失败 ").append(brief(raw)).append('\n');
                break;
            }
            if (o.has("err")) {
                detail.append("  免费任务脚本: ").append(o.optString("err")).append('\n');
                break;
            }
            int left = o.optInt("left", 0);
            if (o.has("none")) {
                detail.append("  免费任务: 没有待做的了\n");
                break;
            }
            String name = o.optString("name", "免费任务");
            detail.append("  免费任务 ").append(i + 1).append(": 点「").append(name)
                    .append("」，页面待做 ").append(left).append('\n');
            sleep(1_200L);
            String landed = page.url();
            boolean jumped = landed != null && !landed.contains("2026dmycj");
            if (!jumped) {
                // r18：没离开豆苗页 = 点到的不是任务按钮（多半是 tab 图标或卡片容器）。
                // 以前这里照样停 5 秒、照样 done++，于是日志写「点了 4 个」，
                // 而服务端一分阳光值都没记，页面还报 TypeError。
                // 现在：不计数、不空等，连续 3 次就收手，如实报「点了没跳」。
                detail.append("    点击未触发跳转（仍在豆苗页），不计入完成\n");
                if (++stall >= 3) {
                    detail.append("  免费任务: 连续 3 次点击都没跳转，收手（按钮定位需修正）\n");
                    break;
                }
                continue;
            }
            detail.append("    已跳到 ").append(brief(landed)).append("，停留 5 秒\n");
            sleep(MIAO_STAY_MS);
            if (!page.navigate(entry, 25_000L)) {
                detail.append("  免费任务: 拉回豆苗页失败，停止\n");
                break;
            }
            sleep(2_500L);
            done++;
            if (left == lastLeft) {
                if (++stall >= 3) {
                    detail.append("  免费任务: 待做数连续不降，收手\n");
                    break;
                }
            } else {
                stall = 0;
            }
            lastLeft = left;
        }
        detail.append("  免费任务: 本轮点了 ").append(done).append(" 个\n");
        return done;
    }

    /**
     * 点「赚阳光值」列表里第一个待做的免费任务。做完的任务按钮会变成「已完成」，
     * 所以每次点第一个不会重复。返回点到的任务名、页面剩余待做数；none=没待做。
     *
     * <p>r16：11-12 20:42 日志实证，豆苗这三个浏览任务（看移动爱购直播 /
     * 浏览热销榜单 / 浏览精选爆品）的按钮是 {@code IMG.content-btn}，元素链
     * {@code IMG.content-btn < DIV.content-bg < DIV.comp-box}，图片上没有任何文字。
     * 旧版只按 innerText 精确匹配「去浏览」这类词，一个都找不到，于是日志写
     * 「赚阳光值免费任务: 完成 0 个」、活动被判 idle。现在改成按结构找图片按钮：
     * 用 class / 尺寸 / 可见性定位，沿父链找整块任务卡片读名字与「已完成」状态，
     * 文本只用来写日志和排除付费项，不用来定位按钮本身。
     */
    private static final String MIAO_FREE_ONE = """
            (function(){
              try{
                function vis(e){
                  try{
                    var r=e.getBoundingClientRect();
                    if(!r||r.width<2||r.height<2)return false;
                    var s=getComputedStyle(e);
                    return s.display!=='none'&&s.visibility!=='hidden'&&s.opacity!=='0';
                  }catch(x){return false}
                }
                function fire(e){
                  try{
                    var r=e.getBoundingClientRect(),cx=r.left+r.width/2,cy=r.top+r.height/2;
                    var mk=function(){return {bubbles:true,cancelable:true,view:window,clientX:cx,clientY:cy}};
                    var ts=['pointerdown','touchstart','mousedown','pointerup','touchend','mouseup'];
                    for(var i=0;i<ts.length;i++){
                      try{
                        var ev;
                        if(ts[i].indexOf('pointer')===0)ev=new PointerEvent(ts[i],mk());
                        else if(ts[i].indexOf('touch')===0)ev=new Event(ts[i],{bubbles:true,cancelable:true});
                        else ev=new MouseEvent(ts[i],mk());
                        e.dispatchEvent(ev);
                      }catch(x){}
                    }
                    if(typeof e.click==='function')e.click();
                    return true;
                  }catch(x){return false}
                }
                function txt(e){try{return (e.innerText||'').replace(/\\s+/g,' ').trim()}catch(x){return ''}}
                // 图片按钮没有文字，按结构向上找整块任务卡片：.content-bg 只包图片，
                // 再上一层 .comp-box 才带任务名。只有单块卡片才能判断「已完成」。
                function cardOf(e){
                  var cur=e,fallback=null;
                  for(var d=0;d<5&&cur;d++){
                    var c=String(cur.className||'');
                    if(/comp-box|task-item|task-card|doTaskDiv/i.test(c))return cur;
                    if(/content-bg/i.test(c)&&!fallback)fallback=cur;
                    cur=cur.parentElement;
                  }
                  return fallback||e;
                }
                function labelOf(card){
                  var t=txt(card);
                  if(t.length<4&&card.parentElement)t=txt(card.parentElement);
                  return t.slice(0,60);
                }
                var PAY=/去订购|办理|会员|铂金|畅看|订购订单/;
                var DONE=/已完成|已领取|已浏览|已看过|已打卡|明天再来|已参与/;
                // r18：卡片必须带「收益或任务关键词」才算任务卡。
                // 只按 content-btn 找会命中页面底部的 tab 图标，点下去只是切 tab
                // （日志里 URL 的 pageKey 从 2 一路涨到 5，阳光值一分没动）。
                var POS=/\\+\\s*10|阳光|直播|榜单|爆品|网龄|分享|一豆|签到|浏览|观看/;
                var WORDS=['去浏览','去分享','去领取','去看看','去邀请','去关注','去完成','去参与'];
                var cards=[],seen=[];
                function add(el,card,name,kind){
                  if(!card)card=el;
                  for(var i=0;i<seen.length;i++){
                    var s=seen[i];
                    if(s===card)return;
                    try{
                      if(card.contains&&card.contains(s))return;
                      if(s.contains&&s.contains(card))return;
                    }catch(x){}
                  }
                  seen.push(card);
                  cards.push({el:el,card:card,name:name.slice(0,40),kind:kind});
                }
                // ① 图片按钮（r16 新增）：豆苗三个浏览任务的按钮就是这张图。
                var imgs=document.querySelectorAll('img,[class*=content-btn],[class*=task-btn]');
                for(var i=0;i<imgs.length;i++){
                  var e=imgs[i];
                  if(!vis(e))continue;
                  var tag=(e.tagName||'').toUpperCase();
                  var cls=String(e.className||'');
                  if(tag!=='IMG'&&!/content-btn|task-btn/i.test(cls))continue;
                  var r=null;try{r=e.getBoundingClientRect()}catch(x){continue}
                  if(!r||r.width<24||r.height<16)continue;
                  var ratio=r.width/Math.max(r.height,1);
                  if(r.height>160||ratio<0.6||ratio>12)continue;
                  var card=cardOf(e),ct=labelOf(card);
                  if(PAY.test(ct)||DONE.test(ct))continue;
                  if(!POS.test(ct))continue;
                  add(e,card,ct||'图片浏览任务','img');
                }
                // ② 文字按钮：老版本页面仍用「去浏览」这类纯文字按钮，保留兜底。
                var all=document.querySelectorAll('button,a,div,span,li,p,i');
                for(var j=0;j<all.length;j++){
                  var n=all[j];
                  if(!vis(n))continue;
                  var t=txt(n).replace(/\\s/g,''),ok=false;
                  for(var w=0;w<WORDS.length;w++){if(t===WORDS[w]){ok=true;break}}
                  if(!ok)continue;
                  var card2=cardOf(n),ct2=labelOf(card2);
                  if(PAY.test(ct2)||DONE.test(ct2))continue;
                  add(n,card2,ct2||t,'text');
                }
                if(!cards.length)return JSON.stringify({none:1,left:0});
                var pick=cards[0];
                fire(pick.el);
                // r18：只补点卡片本身和它的直接父级。以前连点 3 层祖先，
                // 会把 tab 容器/整页容器的点击也触发，日志里表现为 pageKey 连续变化
                // 外加一串页面 TypeError，任务却一个没记上。
                if(pick.card&&pick.card!==pick.el)fire(pick.card);
                if(pick.card&&pick.card.parentElement)fire(pick.card.parentElement);
                return JSON.stringify({name:pick.name,left:cards.length,
                                       kind:pick.kind,url:String(location.href)});
              }catch(err){return JSON.stringify({err:String(err&&err.message||err)})}
            })()
            """;

    private static String brief(String text) {
        if (text == null) {
            return "";
        }
        String value = text.replace('\n', ' ').trim();
        return value.length() <= 240 ? value : value.substring(0, 240) + "…";
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }

    private static final String MIAO_SCRIPT = """
            (function(){
              var ID='__cmcc_miao';
              window[ID]='';
              var L=[], NET=[];
              // r14：阶段心跳。脚本跑起来后每写一行明细就更新一次 window 值，
              // 外层轮询能看到「走到哪一步了」，不用等 45 秒才知道卡在哪。
              function line(s){ L.push(String(s)); try{ window[ID]=JSON.stringify({beat:1,last:String(s),n:L.length}) }catch(e){} }
              function cut(s,n){ s=String(s==null?'':s); return s.length>n?s.slice(0,n)+'…':s }
              function isFn(x){ return typeof x==='function' }
              function wait(ms){ return new Promise(function(r){ setTimeout(r,ms) }) }
              function done(o){ try{ o.lines=L; window[ID]=JSON.stringify(o) }catch(e){ window[ID]='{"stage":"crash"}' } }
              function btext(){ try{ return (document.body?document.body.innerText:'').replace(/\\s+/g,' ').trim() }catch(e){ return '' } }

              function hookNet(){
                try{
                  var op=XMLHttpRequest.prototype.open, se=XMLHttpRequest.prototype.send;
                  XMLHttpRequest.prototype.open=function(m,u){
                    try{ this.__q={m:String(m||''),u:String(u||'')} }catch(e){}
                    return op.apply(this,arguments);
                  };
                  XMLHttpRequest.prototype.send=function(){
                    var x=this;
                    try{
                      x.addEventListener('loadend',function(){
                        try{ NET.push({u:x.__q?x.__q.u:'', s:x.status||0,
                          b:String(x.responseText||'').slice(0,300)}) }catch(e){}
                      });
                    }catch(e){}
                    return se.apply(this,arguments);
                  };
                }catch(e){}
                try{
                  var f=window.fetch;
                  if(isFn(f)){
                    window.fetch=function(){
                      var u=''; try{ u=String(arguments[0]&&arguments[0].url||arguments[0]||'') }catch(e){}
                      return f.apply(this,arguments).then(function(r){
                        try{ NET.push({u:u,s:r.status||0,b:''}) }catch(e){}
                        return r;
                      });
                    };
                  }
                }catch(e){}
              }

              function ensureSdk(){
                if(window.cmcc&&isFn(window.cmcc.checkUID)) return 'native';
                var NAMES=['checkUID','getUserInfo','getLoginInfo','userStatus',
                  'checkSessionIsvalid','showLogin','getCatchInfo','getLocation',
                  'permissionIsEnable','showMsgSubscribeDialog','shareMessage','newWebview',
                  'goNativePage','setTitle','closeWindow','showNavCustomBtn','hiddenShareButton',
                  'showTitleBarRightIcons','openOtherAPP','copyToClipboard','getIDMPLoginToken',
                  'getDeviceMultiSimInfo','getNetInfo','getRiskLevel','getStatusBarHeight'];
                var face=window.cmcc||{}, n=0;
                function call(o,nm){
                  o=o||{};
                  o.businessName=nm;
                  o.callbackSuccess='callbackSuccess_'+nm;
                  o.callbackError='callbackError_'+nm;
                  if(!isFn(o.success)) o.success=function(){};
                  if(!isFn(o.error)) o.error=function(){};
                  try{ delete o.success; delete o.error }catch(e){}
                  if(window.newSdkInterface&&isFn(window.newSdkInterface[nm])){
                    window.newSdkInterface[nm](JSON.stringify(o));
                    return true;
                  }
                  return false;
                }
                for(var i=0;i<NAMES.length;i++){
                  (function(nm){
                    if(isFn(face[nm])) return;
                    face[nm]=function(o){ return call(o,nm) };
                    n++;
                  })(NAMES[i]);
                }
                window.cmcc=face;
                if(!window.leadeon) window.leadeon=face;
                return 'shim+'+n;
              }

              function visible(e){
                try{
                  var r=e.getBoundingClientRect();
                  if(!r||r.width<2||r.height<2) return false;
                  var s=getComputedStyle(e);
                  return s.display!=='none' && s.visibility!=='hidden' && s.opacity!=='0';
                }catch(x){ return false }
              }
              function clickable(e){
                try{
                  var tag=(e.tagName||'').toUpperCase();
                  if(tag==='BUTTON'||tag==='A') return true;
                  var cls=String(e.className||'')+' '+(e.id||'');
                  if(/btn|button|click|sign|water|feed|harvest|receive/i.test(cls)) return true;
                  if(getComputedStyle(e).cursor==='pointer') return true;
                }catch(x){}
                return false;
              }

              // 顶部弹层（签到成功 / 公告 / 奖励到账）会挡住页面，先点掉。
              function closePop(){
                var all=document.querySelectorAll('button,div,span,a');
                var words=['知道了','我知道了','确定','确认','关闭','开心收下','收下'];
                for(var i=0;i<all.length;i++){
                  var e=all[i];
                  if(!visible(e)) continue;
                  var t=(e.innerText||'').replace(/\\s+/g,' ').trim();
                  if(!t||t.length>6) continue;
                  for(var j=0;j<words.length;j++){
                    if(t!==words[j]) continue;
                    if(!clickable(e)) continue;
                    try{ e.click(); line('  关弹层: '+t); return true }catch(x){}
                  }
                }
                return false;
              }

              // 画布组件的 class 是编辑器生成的（comp-xxx / rights-xxx），
              // 用 btn/click 白名单筛会一个都点不到 —— 11-07 豆苗「点了 0 个」就是这个原因。
              // 现在改成沿父链找真正挂点击的元素，找不到就点文本自己（事件会冒泡上去）。
              function targetOf(e){
                var cur=e;
                for(var d=0; d<4 && cur; d++){
                  try{
                    var tag=(cur.tagName||'').toUpperCase();
                    if(tag==='BUTTON'||tag==='A') return cur;
                    if(getComputedStyle(cur).cursor==='pointer') return cur;
                    if(/btn|button|click|comp-|item|card/i.test(String(cur.className||''))) return cur;
                  }catch(x){}
                  cur=cur.parentElement;
                }
                return e;
              }

              // v3.60：画布组件的点击监听挂在 pointer/touch 上，单发 el.click()
              // 11-07 只打出「点击: + 10 点击领 <DIV >」，页面一条请求都没发。
              // 这里补齐指针序列，并记下最后点的是谁，供上层补点。
              function fire(e){
                try{
                  var r=e.getBoundingClientRect();
                  var cx=r.left+r.width/2, cy=r.top+r.height/2;
                  var mk=function(){return {bubbles:true,cancelable:true,view:window,
                                            clientX:cx,clientY:cy}};
                  var ts=['pointerdown','touchstart','mousedown','pointerup','touchend','mouseup'];
                  for(var i=0;i<ts.length;i++){
                    try{
                      var ev;
                      if(ts[i].indexOf('pointer')===0) ev=new PointerEvent(ts[i],mk());
                      else if(ts[i].indexOf('touch')===0) ev=new Event(ts[i],{bubbles:true,cancelable:true});
                      else ev=new MouseEvent(ts[i],mk());
                      e.dispatchEvent(ev);
                    }catch(x){}
                  }
                  if(typeof e.click==='function') e.click();
                  window.__cmcc_lastTap=e;
                  return true;
                }catch(x){return false}
              }

              // v3.93：22:08 日志实证——模块点「+ 10 点击领」文字 DIV 和父层
              // UL list-ul / DIV list 全无回执（阳光值 0→0）；用户手动点的是
              // class=sign-box 的整块签到卡，点完阳光值 0→10、「点击领」变「已领」。
              // 说明点击监听挂在 sign-box 容器上，旧版补点只到 2 层父级够不到它。
              function ownText(e){
                var s='';
                try{
                  var n=e.firstChild;
                  while(n){ if(n.nodeType===3) s+=n.nodeValue; n=n.nextSibling }
                }catch(x){}
                return String(s||'').replace(/\\s+/g,' ').trim();
              }
              function signBoxTarget(box){
                var all=box.querySelectorAll('*'), best=null, bl=1e9;
                for(var i=0;i<all.length;i++){
                  var e=all[i];
                  if(!visible(e)) continue;
                  var t=ownText(e);
                  if(t.indexOf('点击领')<0 || /已领/.test(t)) continue;
                  if(t.length<bl){ best={e:e,t:t}; bl=t.length }
                }
                if(best){
                  line('  sign-box 命中「点击领」最小元素: <'+best.e.tagName+' '
                       +cut(String(best.e.className||''),40)+'> '+cut(best.t,30));
                  return best;
                }
                try{
                  var r=box.getBoundingClientRect();
                  var cx=r.left+r.width/2, cy=r.top+r.height/2;
                  var at=document.elementFromPoint(cx,cy);
                  if(at&&visible(at)){
                    line('  sign-box 中心命中: <'+at.tagName+' '
                         +cut(String(at.className||''),40)+'> @'+Math.round(cx)+','+Math.round(cy));
                    return {e:at,t:'签到卡(sign-box 中心)'};
                  }
                }catch(x){}
                line('  sign-box 用容器本身: <'+box.tagName+' '
                     +cut(String(box.className||''),40)+'>');
                return {e:box,t:'签到卡(sign-box 容器)'};
              }
              function signBoxTap(words){
                if(!/点击领|签到|领取/.test(words.join(''))) return null;
                var boxes=document.querySelectorAll('[class*=sign-box],.sign-box,[class*=signBox]');
                for(var i=0;i<boxes.length;i++){
                  var box=boxes[i];
                  if(!visible(box)) continue;
                  var bt=(box.innerText||'').replace(/\\s+/g,' ').trim();
                  if(!/点击领|立即签到|去签到/.test(bt)) continue;
                  // 已经领过的卡片不重复点（22:08 手动后「点击领」变「已领」）。
                  if(/已领/.test(bt) && !/点击领/.test(bt)) continue;
                  line('  sign-box 容器: <'+box.tagName+' '
                       +cut(String(box.className||''),50)+'> 文本 '+cut(bt,60));
                  return signBoxTarget(box);
                }
                // v4.0.5：19:24 日志实证 —— 真页已经没有 [class*=sign-box] 容器，
                // 3 次都落到这里。把页面上所有「领/签到/点击/阳光」相关的可见元素
                // 连 class 原样打进明细，下一份日志就能定位新的签到卡容器，不用猜。
                line('  sign-box 未找到：页面无 [class*=sign-box] 容器');
                var cand=document.querySelectorAll('button,a,div,span,li,p');
                var shown=0;
                for(var ci=0;ci<cand.length && shown<18;ci++){
                  var ce=cand[ci];
                  if(!visible(ce)) continue;
                  var ct=(ce.innerText||'').replace(/\\s+/g,' ').trim();
                  if(!ct||ct.length>40) continue;
                  if(!/领|签到|点击|阳光/.test(ct)) continue;
                  line('  候选<'+ce.tagName+' '+cut(String(ce.className||''),46)+'> '+cut(ct,40));
                  shown++;
                }
                if(shown===0) line('  （页面上没有任何含「领/签到/点击/阳光」的可见元素）');
                return null;
              }

              // 按文案找页面上的真实按钮。只点一次，不点「已领取/已签到」。
              function hit(words, max){
                // v3.93：签到词优先走 sign-box 精确目标（22:08 用户手动实证有效）。
                var sb=signBoxTap(words);
                if(sb&&sb.e){
                  try{
                    fire(sb.e);
                    line('  点击(sign-box): '+sb.t+' <'+sb.e.tagName+' '
                         +cut(String(sb.e.className||''),40)+'>');
                    return sb.t;
                  }catch(x){}
                }
                var all=document.querySelectorAll('button,a,div,span,i,p');
                var n=0;
                for(var i=0;i<all.length && n<max;i++){
                  var e=all[i];
                  if(!visible(e)) continue;
                  var t=(e.innerText||'').replace(/\\s+/g,' ').trim();
                  if(!t||t.length>14) continue;
                  if(/已领取|已签到|已打卡|已完成|明天再来|已领/.test(t)) continue;
                  // 说明文字不是按钮：「每日可签到1次~」「签到奖励记录」这类点上去没反应。
                  if(/可签到|签到1次|每日|规则|说明|记录|剩余|我的阳光/.test(t)) continue;
                  var ok=false;
                  for(var j=0;j<words.length;j++){
                    if(t.indexOf(words[j])>=0){ ok=true; break }
                  }
                  if(!ok) continue;
                  var el=targetOf(e);
                  try{
                    fire(el);
                    n++;
                    line('  点击: '+t+' <'+(el.tagName||'')+' '
                         +cut(String(el.className||''),40)+'>');
                    return t;
                  }catch(x){}
                }
                return '';
              }

              function sun(){
                var t=btext();
                var m=/阳光值[^0-9]{0,6}(\\d{1,7})/.exec(t);
                if(m) return parseInt(m[1],10);
                return -1;
              }

              (async function(){
                try{
                  line('=== 爱购AI豆苗养成记 v3.95 ===');
                  line('URL: '+cut(location.href,220));
                  hookNet();
                  var sdk=ensureSdk();
                  line('登录桥: '+sdk);

                  var t0='';
                  var loginWait=0;
                  for(var w=0;w<40;w++){
                    t0=btext();
                    if(t0.length>0 && !/登录中|加载中|校验中|请稍候/.test(t0)) break;
                    loginWait++;
                    await wait(500);
                  }
                  if(loginWait>0) line('等登录: '+(loginWait*500)+'ms');

                  var root=null;
                  for(var i=0;i<24;i++){
                    try{
                      var el=document.querySelector('#app')||document.querySelector('#root');
                      root=el&&el.__vue__;
                      if(!root && el&&el.__vue_app__&&el.__vue_app__._instance) root=el.__vue_app__._instance;
                    }catch(e){}
                    if(root) break;
                    await wait(400);
                  }
                  if(!root){
                    line('没有 Vue 根组件');
                    done({stage:'no-vue',text0:btext(),text1:btext()});
                    return;
                  }

                  await wait(1200);
                  closePop();
                  await wait(600);

                  var sun0=sun();
                  var t00=btext();
                  line('阳光值基线: '+(sun0<0?'没读到':sun0));
                  line('首屏: '+cut(t00,300));

                  var acts=[], tryN=0, okN=0, netMark=0;
                  // 顺序按「先签到、再领取、后养成」排：签到是最稳的日常。
                  var plan=[
                    // 先只认「动作词」。「签到」单独出现时多半是卡片标题，
                    // 11-07 就点在了「每日可签到1次~」这行说明文字上，白点三次没回执。
                    {w:['点击领','立即领取','立即签到'], n:1},
                    {w:['一键领取','领取奖励'], n:2},
                    {w:['浇水','浇水壶','施肥','喂食'], n:2},
                    {w:['收豆','收取','收获'], n:1}
                  ];
                  for(var p=0;p<plan.length;p++){
                    for(var k=0;k<plan[p].n;k++){
                      netMark=NET.length;
                      var got=hit(plan[p].w,1);
                      if(!got) break;
                      tryN++;
                      acts.push(got);
                      await wait(1400);
                      closePop();
                      await wait(400);
                      if(NET.length>netMark){
                        var hit2=false;
                        for(var z=netMark;z<NET.length;z++){
                          if(NET[z].s>=200&&NET[z].s<400) hit2=true;
                        }
                        if(hit2) okN++;
                      }else{
                        // v3.93：监听挂在更外层 sign-box 卡片上，补点扩到 5 层父节点。
                        var up=window.__cmcc_lastTap;
                        for(var d=1;d<=5&&up;d++){
                          up=up.parentElement;
                          if(!up) break;
                          try{ fire(up); line('  补点父层 '+d+' <'+(up.tagName||'')+' '
                              +cut(String(up.className||''),30)+'>'); }catch(x){}
                          await wait(1100);
                          if(NET.length>netMark) break;
                        }
                        for(var z3=netMark;z3<NET.length;z3++){
                          if(NET[z3].s>=200&&NET[z3].s<400){ okN++; break }
                        }
                      }
                    }
                  }

                  // 精确词一个都没命中，退回宽泛词再试一次：
                  // 有些版本按钮就叫「签到」，不能因为怕点错标题就整个放弃。
                  if(tryN===0){
                    netMark=NET.length;
                    var fb=hit(['签到','打卡','领取'],1);
                    if(fb){
                      tryN++;
                      acts.push(fb);
                      await wait(1500);
                      closePop();
                      await wait(400);
                      for(var z2=netMark;z2<NET.length;z2++){
                        if(NET[z2].s>=200&&NET[z2].s<400){ okN++; break }
                      }
                    }
                  }

                  var sun1=sun();
                  var t11=btext();
                  // 只认「点击之后」新增的 2xx，页面开屏那几条不算。
                  var netOk=okN;
                  line('动作: 点了 '+tryN+' 个，点击后有回执 '+okN+' 次');
                  // 一个都没点到时，把页面上真实存在的短文案打出来：
                  // 下一版直接照这些名字补关键词，不用再靠猜。
                  if(tryN===0){
                    var allEl=document.querySelectorAll('button,a,div,span,i,p');
                    var seenTxt={}, cntTxt=0;
                    for(var q=0;q<allEl.length && cntTxt<26;q++){
                      var ee=allEl[q];
                      if(!visible(ee)) continue;
                      var tt=(ee.innerText||'').replace(/\\s+/g,' ').trim();
                      if(!tt||tt.length>12) continue;
                      if(ee.querySelector('button,a,span,i')) continue;
                      var kk=tt+'|'+String(ee.className||'');
                      if(seenTxt[kk]) continue;
                      seenTxt[kk]=1; cntTxt++;
                      line('  可见文本: '+tt+' <'+cut(String(ee.className||''),40)+'>');
                    }
                  }
                  done({stage:'done',sun0:sun0,sun1:sun1,text0:t00,text1:t11,
                        clickTry:tryN,clickOk:okN,netOk:netOk,acts:acts,
                        net:NET.slice(0,10)});
                }catch(err){
                  done({stage:'error',msg:String((err&&err.stack)||err)});
                }
              })();
            })()
            """;
}
