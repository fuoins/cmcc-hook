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

    /**
     * 同步返回 JSON：域、readyState、标题、Vue、正文长度、任务按钮数。
     *
     * <p>v4.20.0-r30：原来的 {@code .task-card,.task-group} 是猜的 class 名，
     * 这个页面是 uni-app 渲染、class 带随机后缀，实测 42KB HTML 却返回
     * {@code cards:0 / go:0}，真页据此判「没有待做」，剩下两个待办从没被点过。
     * 改成按叶子文本数按钮，不依赖任何 class 名。
     */
    private static final String JS_SCAN = String.join("",
            "(function(){try{",
            "function N(el){try{return (el.innerText||'').replace(/\\s+/g,'')}catch(e){return ''}}",
            "function leafCount(w){",
            "var n=0,all=document.querySelectorAll('*');",
            "for(var i=0;i<all.length;i++){",
            "var el=all[i];",
            "if(N(el)!==w)continue;",
            "var dup=false;",
            "for(var j=0;j<el.children.length;j++){if(N(el.children[j])===w){dup=true;break}}",
            "if(!dup)n++;}",
            "return n;}",
            "var go=leafCount('去完成'),award=leafCount('领奖励'),done=leafCount('已完成');",
            "return JSON.stringify({",
            "url:location.href,hash:location.hash,title:document.title||'',",
            "ready:document.readyState,",
            "html:document.body?document.body.innerHTML.length:0,",
            "cards:go+award+done,go:go,award:award,done:done,",
            "txt:(document.body?document.body.innerText:'').replace(/\\s+/g,' ').slice(0,90)",
            "});}catch(e){return JSON.stringify({err:String(e)})}})()");
}
