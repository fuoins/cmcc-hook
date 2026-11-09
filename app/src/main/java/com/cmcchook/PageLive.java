package com.cmcchook;

/**
 * 活动页现场「只读诊断」。
 *
 * <p>v3.16 曾在这里做「借页面现场点按钮」，实测走不通：离屏页停在
 * 「加载中」（html=5302 / cards=0 / txt=加载中...），uni-app 没渲染，
 * 30 次触发全部找不到按钮；而且用户手工点一遍要 22 分钟，把这套搬进模块
 * 只会又慢又做不成。v3.17 起任务改走 {@link MarkTasks} 的接口编排，
 * 本类只保留一次现场快照，用来确认「页面上下文还活着、会话页没被顶走」。
 *
 * <p>不再切 hash 路由、不再点 DOM、不再拉回现场。
 */
final class PageLive {

    private PageLive() {
    }

    /** 现场侦察：页面在哪、渲染到什么程度。只写进判定型日志，不做任何点击。 */
    static String scan() {
        WebPage page = WebSession.current();
        if (page == null) {
            return "无页面现场";
        }
        String value = page.eval(JS_SCAN, 5_000L);
        return value == null || value.isEmpty() ? "现场无响应" : value;
    }

    /** 同步返回 JSON：域、readyState、标题、Vue、正文长度、任务卡片数。 */
    private static final String JS_SCAN = String.join("",
            "(function(){try{",
            "var cs=document.querySelectorAll('.task-card,.task-group');",
            "var go=0,award=0,done=0;",
            "for(var i=0;i<cs.length;i++){",
            "var t=(cs[i].innerText||'').replace(/\\s+/g,' ');",
            "if(t.indexOf('去完成')>=0)go++;",
            "if(t.indexOf('领奖励')>=0)award++;",
            "if(t.indexOf('已完成')>=0)done++;}",
            "return JSON.stringify({",
            "url:location.href,hash:location.hash,title:document.title||'',",
            "ready:document.readyState,",
            "html:document.body?document.body.innerHTML.length:0,",
            "cards:cs.length,go:go,award:award,done:done,",
            "txt:(document.body?document.body.innerText:'').replace(/\\s+/g,' ').slice(0,90)",
            "});}catch(e){return JSON.stringify({err:String(e)})}})()");
}
