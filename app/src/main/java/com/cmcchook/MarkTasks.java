package com.cmcchook;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 签到有礼「任务」体系（活动 1021122301 的 mark/task）。
 *
 * <p><b>v4.10.0-r2 起：任务一律在真页里点「去完成」，不再接口直发、不再离屏。</b>
 * 用户实证：功能体验区那 9 个浏览任务（查询我的套餐 / 浏览话费余额 /
 * 查询本月账单 / 查查我的余量 / 一键通查 / 看精彩视频 / 装宽带 /
 * 去小程序订阅提醒 / 点这里给自己充10元）用 {@code finishTask} 直发拿不到完成，
 * 必须走页面自己的「点『去完成』→ 跳落地页 → 停留 5 秒 → 回来上报」。
 * 所以接口只保留领奖，任务全部交给 {@link #runLiveTasks} 在真页里点。
 *
 * <p>接口只做「真页也能做但没必要点」的领奖：
 * <ul>
 *   <li>status=1 已完成待领 → {@code getTaskAward} 补领；</li>
 *   <li>status=2 首礼已领、awardType=2 且 secondStatus=0 → {@code getTaskSecondAward}。</li>
 * </ul>
 *
 * <p>真页任务的范围与跳过规则：
 * <ul>
 *   <li>只做<b>功能体验区</b>。外部合作区是第三方 App 记账，模块代做没有意义；
 *       福利活动区 / 分省专属区多为已领或需真消费；</li>
 *   <li>「点这里给自己充10元」是真消费，硬跳过且不计入待办 —— 否则它永远
 *       降不到 0，会把点击循环拖满 14 次；</li>
 *   <li>点完等页面自己跳、停留 5 秒（和用户手点一致）、再拉回任务页复查待做数。</li>
 * </ul>
 */
final class MarkTasks {

    private static final String P_LIST = "/qwhdhub/api/mark/task/taskList";
    private static final String P_AWARD = "/qwhdhub/api/mark/task/getTaskAward";
    private static final String P_SECOND = "/qwhdhub/api/mark/task/getTaskSecondAward";

    /** 任务页路由（页面自己的 uni-app 路由，21:01 日志实测地址）。 */
    private static final String TASK_ROUTE = "#/pages/qwhdmark/views/task/index";

    /** 每个任务都要停留够 5 秒门槛，和用户手点完全一致。 */
    private static final long LIVE_STAY_MS = 5_000L;

    /** 功能体验区最多 9 个任务，留一点余量防页面重排。 */
    private static final int LIVE_TRIES = 14;

    private MarkTasks() {
    }

    /**
     * 清单 + 领奖编排，返回多行判定型文本（由 ScriptEngine 的 call 写进 detail）。
     *
     * <p>这里<b>不做</b>任何完成上报：待办数量只统计出来，留给真页点击。
     */
    static String run(String referer, StringBuilder ignored) {
        StringBuilder d = new StringBuilder();
        int total = 0, todo = 0, ext = 0, blocked = 0;
        int awarded = 0, second = 0;

        JSONObject root = post(P_LIST, "{}", referer, d, "任务清单");
        if (root == null || !"SUCCESS".equals(root.optString("code"))) {
            return "任务编排: 清单获取失败，本次不领奖也不统计\n" + d;
        }
        JSONArray tasks = data(root).optJSONArray("tasks");
        if (tasks == null) {
            return "任务编排: 清单里没有 tasks\n" + d;
        }
        total = tasks.length();

        // 1) 上次遗留：已完成但没领奖的，先补领首礼。
        for (int i = 0; i < tasks.length(); i++) {
            JSONObject t = tasks.optJSONObject(i);
            if (t == null || t.optInt("status", -1) != 1) {
                continue;
            }
            String id = t.optString("taskId", "");
            if (id.isEmpty()) {
                continue;
            }
            String r = award(id, P_AWARD, referer);
            d.append("[补领] ").append(name(t)).append('(').append(id).append(") → ")
                    .append(r).append('\n');
            if ("OK".equals(r)) {
                awarded++;
            }
            pause();
        }

        // 2) 待办只统计，不接口直发 —— 交给 runLiveTasks 在真页点。
        for (int i = 0; i < tasks.length(); i++) {
            JSONObject t = tasks.optJSONObject(i);
            if (t == null || t.optInt("status", -1) != 0) {
                continue;
            }
            String cls = t.optString("taskClasifiName", "");
            if (cls.contains("外部合作")) {
                ext++;
                continue;
            }
            if (hardBlocked(name(t))) {
                blocked++;
                continue;
            }
            todo++;
        }
        if (todo > 0) {
            d.append("[待办] ").append(todo)
                    .append(" 个留给真页「去完成」（本版不接口直发）\n");
        }

        // 3) 重拉清单，领第二重礼（首礼领完后才解锁）。
        JSONObject root2 = post(P_LIST, "{}", referer, d, "任务清单复查");
        JSONArray tasks2 = root2 == null ? null : data(root2).optJSONArray("tasks");
        for (int i = 0; tasks2 != null && i < tasks2.length(); i++) {
            JSONObject t = tasks2.optJSONObject(i);
            if (t == null || !t.has("secondStatus")) {
                continue;
            }
            if (t.optInt("awardType", 0) != 2 || t.optInt("status", -1) != 2
                    || t.optInt("secondStatus", -1) != 0) {
                continue;
            }
            String id = t.optString("taskId", "");
            if (id.isEmpty()) {
                continue;
            }
            String r = award(id, P_SECOND, referer);
            d.append("[二礼] ").append(name(t)).append('(').append(id).append(") → ")
                    .append(r).append('\n');
            if ("OK".equals(r)) {
                second++;
            }
            pause();
        }

        String head = "任务编排 · 共" + total + " / 待办" + todo + " / 外部合作区跳过" + ext
                + " / 不代做" + blocked + " / 补领" + awarded + " / 二礼" + second;
        HookLog.log("TASK", "任务编排 · 补领与统计", head + "\n" + d);
        return head + "\n" + d;
    }

    // ------------------------------------------------------ 真页点「去完成」

    /**
     * 在已打开的签到真页里，把「功能体验区」的待办逐个点掉。
     *
     * <p>顺序：拉回任务页 → 数待做 → 点第一个「去完成」→ 等页面自己跳
     * → 停留 5 秒 → 拉回任务页 → 复查待做数。降了就是记上了；没降就换下一张卡片，
     * 全部卡片都试过仍不降则停止。
     *
     * @return 本轮真正记上的任务数（服务端口径：待做数下降才算）
     */
    static int runLiveTasks(WebPage page, String entry, StringBuilder detail) {
        StringBuilder d = new StringBuilder();
        d.append("▶ 功能体验区任务（真页点「去完成」· 停留 ")
                .append(LIVE_STAY_MS / 1000).append(" 秒）\n");
        if (page == null) {
            d.append("没有真页上下文，跳过\n");
            return out(detail, d, 0);
        }
        String taskUrl = taskUrl(entry);
        if (!page.navigate(taskUrl, 28_000L)) {
            d.append("任务页打不开: ").append(shortUrl(taskUrl)).append('\n');
            return out(detail, d, 0);
        }
        WebPage.sleep(3_000L);

        int left = todoCount(page);
        if (left < 0) {
            d.append("页面上找不到「功能体验区」（可能还在加载），跳过\n");
            return out(detail, d, 0);
        }
        if (left == 0) {
            d.append("功能体验区: 没有待做\n");
            return out(detail, d, 0);
        }
        d.append("功能体验区: 待做 ").append(left).append(" 个\n");

        int done = 0, skip = 0;
        for (int i = 0; i < LIVE_TRIES && left > 0; i++) {
            String raw = page.eval(SIGN_TASK_ONE
                            .replace("%SEC%", SEC_FN)
                            .replace("__SKIP__", String.valueOf(skip)),
                    10_000L);
            if (raw == null || raw.isEmpty()) {
                d.append("点击脚本没回报，停止\n");
                break;
            }
            JSONObject o;
            try {
                o = new JSONObject(raw);
            } catch (Throwable e) {
                d.append("回报解析失败 ").append(brief(raw)).append('\n');
                break;
            }
            if (o.has("none")) {
                d.append("页面已无「去完成」\n");
                break;
            }
            if (o.has("err")) {
                d.append("点击异常: ").append(o.optString("err")).append('\n');
                break;
            }
            String name = o.optString("name", "");
            d.append("任务 ").append(i + 1).append(": 点「")
                    .append(name.isEmpty() ? "去完成" : name).append("」\n");

            WebPage.sleep(1_500L);
            String landed = page.url();
            boolean jumped = landed == null || !landed.contains("qwhdmark");
            if (jumped) {
                d.append("  已跳到 ").append(shortUrl(landed)).append('\n');
            } else {
                d.append("  页面内没跳走，仍然计停留\n");
            }
            WebPage.sleep(LIVE_STAY_MS);

            if (!page.navigate(taskUrl, 28_000L)) {
                d.append("  拉回任务页失败，停止\n");
                break;
            }
            WebPage.sleep(3_000L);
            int now = todoCount(page);
            if (now >= 0 && now < left) {
                done++;
                skip = 0;
                d.append("  待做 ").append(left).append(" → ").append(now)
                        .append("（记上了）\n");
                left = now;
            } else if (now >= 0) {
                skip++;
                d.append("  待做仍 ").append(now).append("（这张没记上，换下一张）\n");
                left = now;
                if (skip >= Math.max(left, 1)) {
                    d.append("  所有卡片都试过仍没记上，停止\n");
                    break;
                }
            } else {
                skip++;
            }
        }
        d.append("功能体验区: 本轮记上 ").append(done).append(" 个，剩余 ")
                .append(left).append(" 个\n");
        return out(detail, d, done);
    }

    /** 明细统一出口：写进任务明细 + 落一条日志，返回记上的任务数。 */
    private static int out(StringBuilder detail, StringBuilder d, int done) {
        detail.append(d);
        HookLog.log("TASK", "签到有礼 · 功能体验区任务", d.toString());
        return done;
    }

    /** 入口地址 + 任务页路由；去掉入口自带的 hash，避免拼出两段路由。 */
    private static String taskUrl(String entry) {
        String base = entry == null ? "" : entry;
        int i = base.indexOf('#');
        if (i >= 0) {
            base = base.substring(0, i);
        }
        return base + TASK_ROUTE;
    }

    private static int todoCount(WebPage page) {
        String v = page.eval(SIGN_TODO_COUNT.replace("%SEC%", SEC_FN), 8_000L);
        if (v == null) {
            return -1;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (Throwable e) {
            return -1;
        }
    }

    private static String shortUrl(String url) {
        if (url == null) {
            return "";
        }
        int i = url.indexOf('?');
        String head = i > 0 ? url.substring(0, i) : url;
        int h = head.indexOf('#');
        if (h > 0) {
            head = head.substring(0, h);
        }
        return head.length() > 90 ? head.substring(0, 90) + "…" : head;
    }

    private static String brief(String s) {
        if (s == null) {
            return "";
        }
        String t = s.replace('\n', ' ').replace('\r', ' ');
        return t.length() > 160 ? t.substring(0, 160) + "…" : t;
    }

    /**
     * 定位「功能体验区」这个分区容器：从标题往上找第一个「内部有『去完成』
     * 且数量不超过 12」的祖先 —— 外部合作区 37 个、全页 65 个，
     * 用数量上界就能把「分区容器」和「整页容器」区分开。
     */
    private static final String SEC_FN = """
            var SEC=['外部合作区','功能体验区','福利活动区','分省专属区','任务中心'];
            function T(el){try{return (el.innerText||'').replace(/\\s+/g,'')}catch(e){return ''}}
            function deepest(name){
              var all=document.querySelectorAll('*'),best=null;
              for(var i=0;i<all.length;i++){
                var el=all[i];
                if(T(el)!==name)continue;
                var has=false;
                for(var j=0;j<el.children.length;j++){
                  if(T(el.children[j])===name){has=true;break}
                }
                if(!has)best=el;
              }
              return best;
            }
            function countGo(box){
              if(!box||!box.querySelectorAll)return 0;
              var n=0,all=box.querySelectorAll('*');
              for(var i=0;i<all.length;i++){if(T(all[i])==='去完成')n++}
              return n;
            }
            function funcBox(){
              var A=deepest('功能体验区');
              if(!A)return null;
              var box=A;
              for(var u=0;u<5&&box.parentElement;u++){
                box=box.parentElement;
                var c=countGo(box);
                if(c>0&&c<=12)return box;
                if(c>12)return null;
              }
              return null;
            }
            function isPay(card){
              return /充值|给自己充|充10元/.test(T(card));
            }
            function cardOf(btn){
              var p=btn;
              for(var k=0;k<6&&p;p=p.parentElement){
                if(T(p).indexOf('去完成')>0)return p;
              }
              return btn.parentElement||btn;
            }
            """;

    /** 功能体验区还剩几个待做（已排除「给自己充10元」这种真消费）。 */
    private static final String SIGN_TODO_COUNT = """
            (function(){
              try{
                %SEC%
                var box=funcBox();
                if(!box)return '-1';
                var all=box.querySelectorAll('*'),n=0;
                for(var i=0;i<all.length;i++){
                  var el=all[i];
                  if(T(el)!=='去完成')continue;
                  var r=el.getBoundingClientRect();
                  if(r.width<=0||r.height<=0)continue;
                  if(isPay(cardOf(el)))continue;
                  n++;
                }
                return String(n);
              }catch(e){return '-1'}
            })()
            """;

    /**
     * 点功能体验区第 {@code __SKIP__} 个可点的「去完成」，返回任务名。
     *
     * <p>uni-app 的按钮是 {@code @tap}，只发 MouseEvent 不触发（周周有好礼
     * 已踩过这个坑），所以这里补完整的 touch / pointer / mouse 序列，
     * 并用 {@code elementFromPoint} 对落点补一次，避免按钮外面盖着透明容器。
     */
    private static final String SIGN_TASK_ONE = """
            (function(){
              try{
                %SEC%
                var box=funcBox();
                if(!box)return JSON.stringify({none:1,msg:'没找到功能体验区'});
                var all=box.querySelectorAll('*'),cands=[];
                for(var i=0;i<all.length;i++){
                  var el=all[i];
                  if(T(el)!=='去完成')continue;
                  var rr=el.getBoundingClientRect();
                  if(rr.width<=0||rr.height<=0)continue;
                  var card=cardOf(el);
                  if(isPay(card))continue;
                  cands.push({el:el,card:card});
                }
                if(!cands.length)return JSON.stringify({none:1,total:0});
                var skip=__SKIP__;
                if(skip>=cands.length)skip=0;
                var pick=cands[skip];
                var btn=pick.el, card=pick.card;
                var name=T(card).replace(/去完成$/,'').slice(0,24);

                try{btn.scrollIntoView({block:'center'})}catch(e){}
                var r=btn.getBoundingClientRect();
                var x=r.left+r.width/2, y=r.top+r.height/2;

                function fire(target){
                  if(!target)return;
                  try{
                    var o1={bubbles:true,cancelable:true,view:window,clientX:x,clientY:y,pointerType:'touch',isPrimary:true};
                    target.dispatchEvent(new PointerEvent('pointerdown',o1));
                    target.dispatchEvent(new PointerEvent('pointerup',o1));
                  }catch(e){}
                  try{
                    var ts=new Touch({identifier:1,target:target,clientX:x,clientY:y,pageX:x,pageY:y});
                    target.dispatchEvent(new TouchEvent('touchstart',{bubbles:true,cancelable:true,touches:[ts],targetTouches:[ts],changedTouches:[ts]}));
                    target.dispatchEvent(new TouchEvent('touchend',{bubbles:true,cancelable:true,touches:[],targetTouches:[],changedTouches:[ts]}));
                  }catch(e){}
                  var names=['mousedown','mouseup','click'];
                  for(var m=0;m<names.length;m++){
                    try{
                      target.dispatchEvent(new MouseEvent(names[m],{bubbles:true,cancelable:true,view:window,clientX:x,clientY:y}));
                    }catch(e){}
                  }
                  try{if(typeof target.click==='function')target.click()}catch(e){}
                }

                fire(btn);
                try{
                  var top=document.elementFromPoint(x,y);
                  if(top&&top!==btn&&!btn.contains(top)&&!top.contains(btn))fire(top);
                }catch(e){}

                return JSON.stringify({name:name,idx:skip,total:cands.length,url:String(location.href)});
              }catch(e){return JSON.stringify({err:String(e&&e.message||e)})}
            })()
            """;

    // ------------------------------------------------------------ 辅助

    /** 领奖接口；返回 OK 或失败原因。 */
    private static String award(String taskId, String path, String referer) {
        JSONObject r = post(path, "{\"taskId\":\"" + taskId + "\"}", referer,
                new StringBuilder(), "领奖 " + taskId);
        if (r == null) {
            return "无响应";
        }
        if ("SUCCESS".equals(r.optString("code"))) {
            return "OK";
        }
        return r.optString("code", "?") + " " + r.optString("msg", "");
    }

    /** 真消费、邀好友、助力、公众号：这些不是模块能代做的，不猜、不试。 */
    private static boolean hardBlocked(String name) {
        String[] words = {"给自己充", "充10元", "充话费", "邀好友", "邀当日", "助力",
                "公众号", "分享给", "补签卡", "受邀"};
        for (String w : words) {
            if (name.contains(w)) {
                return true;
            }
        }
        return false;
    }

    private static String name(JSONObject t) {
        String n = t.optString("taskName", "");
        return n.isEmpty() ? t.optString("taskId", "?") : n;
    }

    private static JSONObject data(JSONObject root) {
        JSONObject d = root == null ? null : root.optJSONObject("data");
        return d == null ? new JSONObject() : d;
    }

    /** 统一发请求：失败/非 JSON 都如实记一行，返回 null 交给调用方判定。 */
    private static JSONObject post(String path, String body, String referer,
                                   StringBuilder d, String tag) {
        try {
            return TaskApi.post(path, body, referer);
        } catch (Throwable e) {
            d.append("[").append(tag).append("] 请求失败: ").append(e).append('\n');
            return null;
        }
    }

    private static void pause() {
        WebPage.sleep(200L + new java.util.Random().nextInt(300));
    }
}
