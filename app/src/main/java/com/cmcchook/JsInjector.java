package com.cmcchook;

import android.os.Handler;
import android.os.Looper;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.WeakHashMap;

import de.robv.android.xposed.XposedHelpers;

final class JsInjector {
    static final String BRIDGE_NAME = "__cmcc_capture_7f3a";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Set<Object> BRIDGED = Collections.synchronizedSet(
            Collections.newSetFromMap(new WeakHashMap<Object, Boolean>()));
    private static final Set<String> REPORTED = Collections.synchronizedSet(new HashSet<String>());

    private JsInjector() {
    }

    static void installBridge(final Object webView, final String reason) {
        if (webView == null) {
            return;
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            ensureBridge(webView, reason);
        } else {
            MAIN.post(() -> ensureBridge(webView, reason));
        }
    }

    static void inject(final Object webView, final String reason) {
        if (webView == null) {
            return;
        }
        if (!HookPrefs.h5() && !HookPrefs.ui() && !HookPrefs.storage()) {
            return;
        }
        MAIN.post(() -> {
            ensureBridge(webView, reason);
            evaluate(webView);
            MAIN.postDelayed(() -> evaluate(webView), 700L);
            MAIN.postDelayed(() -> evaluate(webView), 2200L);
        });
    }

    private static void ensureBridge(Object webView, String reason) {
        if (BRIDGED.contains(webView)) {
            return;
        }
        try {
            Method method = findAddJavascriptInterface(webView.getClass());
            if (method == null) {
                reportOnce(webView.getClass().getName(),
                        "addJavascriptInterface not found on " + webView.getClass().getName());
                return;
            }
            method.invoke(webView, new JsBridge(), BRIDGE_NAME);
            BRIDGED.add(webView);
            HookLog.logcat("H5 bridge installed (" + reason + ")");
        } catch (Throwable e) {
            reportOnce(webView.getClass().getName() + ":bridge",
                    "install H5 bridge failed: " + e);
        }
    }

    private static Method findAddJavascriptInterface(Class<?> clazz) {
        try {
            return clazz.getMethod("addJavascriptInterface", Object.class, String.class);
        } catch (Throwable ignored) {
        }
        for (Method method : clazz.getMethods()) {
            Class<?>[] types = method.getParameterTypes();
            if (method.getName().equals("addJavascriptInterface")
                    && types.length == 2
                    && types[1] == String.class) {
                return method;
            }
        }
        return null;
    }

    private static void evaluate(Object webView) {
        try {
            Method method = findEvaluate(webView.getClass());
            if (method == null) {
                reportOnce(webView.getClass().getName() + ":eval",
                        "evaluateJavascript not found on " + webView.getClass().getName());
                return;
            }
            method.invoke(webView, SCRIPT, null);
        } catch (Throwable e) {
            reportOnce(webView.getClass().getName() + ":evalfail",
                    "evaluateJavascript failed: " + e);
        }
    }

    /**
     * 执行 JS 并取回字符串结果。
     *
     * <p>evaluateJavascript 的结果包在 JSON 字面量里，这里去外层引号并反转义。
     * 系统 WebView 和 X5 的 ValueCallback 类型不同，回调接口按方法签名动态取。
     * 返回值 null 可能是没执行成功，也可能是 JS 真的返回了 null，调用方自己兜底。
     */
    static String evalValue(final Object webView, final String js) {
        if (webView == null || js == null || js.isEmpty()) {
            return null;
        }
        final Method method = findEvaluate(webView.getClass());
        if (method == null) {
            return null;
        }
        final java.util.concurrent.CountDownLatch wait =
                new java.util.concurrent.CountDownLatch(1);
        final String[] out = {null};
        final Class<?> cbType = method.getParameterTypes()[1];
        final Object cb = java.lang.reflect.Proxy.newProxyInstance(
                cbType.getClassLoader(),
                new Class<?>[]{cbType},
                new java.lang.reflect.InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, java.lang.reflect.Method m,
                                         Object[] args) {
                        try {
                            if (args != null && args.length > 0) {
                                out[0] = WebPage.unquote(String.valueOf(args[0]));
                            }
                        } catch (Throwable ignored) {
                        }
                        wait.countDown();
                        return null;
                    }
                });
        Runnable run = new Runnable() {
            @Override
            public void run() {
                try {
                    method.invoke(webView, js, cb);
                } catch (Throwable e) {
                    HookLog.logcat("evalValue failed: " + e);
                    wait.countDown();
                }
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) {
            run.run();
        } else {
            MAIN.post(run);
        }
        try {
            wait.await(5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
        }
        return out[0];
    }

    private static Method findEvaluate(Class<?> clazz) {
        for (Method method : clazz.getMethods()) {
            Class<?>[] types = method.getParameterTypes();
            if (method.getName().equals("evaluateJavascript")
                    && types.length == 2
                    && types[0] == String.class) {
                return method;
            }
        }
        return null;
    }

    /**
     * 业务脚本注入（收鹅蛋用）。
     *
     * 调用方多半在任务线程，evaluateJavascript 必须回主线程，所以这里切一下并等结果。
     */
    static boolean evaluateRaw(final Object webView, final String js) {
        if (webView == null || js == null || js.isEmpty()) {
            return false;
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return rawOnMain(webView, js);
        }
        final java.util.concurrent.CountDownLatch wait =
                new java.util.concurrent.CountDownLatch(1);
        final boolean[] ok = {false};
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                ok[0] = rawOnMain(webView, js);
                wait.countDown();
            }
        });
        try {
            wait.await(4, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
        }
        return ok[0];
    }

    /**
     * 只把脚本丢进页面，不等结果。
     *
     * <p>从 JSBridge 方法里回调页面时必须用这个：JS 线程正卡在同步桥上，
     * evaluateJavascript 要等桥返回才执行，任何「等回调」的写法都会先超时。
     */
    static void postRaw(final Object webView, final String js) {
        if (webView == null || js == null || js.isEmpty()) {
            return;
        }
        Runnable run = new Runnable() {
            @Override
            public void run() {
                rawOnMain(webView, js);
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) {
            run.run();
        } else {
            MAIN.post(run);
        }
    }

    private static boolean rawOnMain(Object webView, String js) {
        try {
            Method method = findEvaluate(webView.getClass());
            if (method != null) {
                method.invoke(webView, js, null);
                return true;
            }
        } catch (Throwable e) {
            HookLog.logcat("evaluateRaw failed: " + e);
        }
        // X5 在个别版本上 evaluateJavascript 会抛 NPE，退回 javascript: 伪协议。
        try {
            XposedHelpers.callMethod(webView, "loadUrl", "javascript:" + js);
            return true;
        } catch (Throwable e) {
            HookLog.logcat("evaluateRaw loadUrl failed: " + e);
            return false;
        }
    }

    private static void reportOnce(String key, String message) {
        if (REPORTED.add(key)) {
            HookLog.logcat(message);
        }
    }

    private static final String SCRIPT = """
            (function(){
              var B = '__cmcc_capture_7f3a';
              if (window.__cmcc_cap_v2) {
                try { if (window.__cmcc_cap_snapshot) window.__cmcc_cap_snapshot(document.readyState === 'complete'); } catch (e) {}
                return;
              }
              window.__cmcc_cap_v2 = true;
              var MAX = 180000;
              function cut(v, n) {
                if (v === null || v === undefined) return '';
                v = String(v);
                return v.length > n ? v.slice(0, n) + '...[truncated]' : v;
              }
              function send(type, data) {
                try {
                  var payload = data || {};
                  payload.type = type;
                  payload.page = location.href;
                  payload.ts = Date.now();
                  var bridge = window[B];
                  if (bridge && typeof bridge.log === 'function') {
                    bridge.log(JSON.stringify(payload));
                  }
                } catch (e) {}
              }
              function absolute(u) {
                try { return new URL(u, location.href).href; } catch (e) {}
                try { var a = document.createElement('a'); a.href = u; return a.href; } catch (e) { return String(u || ''); }
              }
              function headersOf(h) {
                var out = {};
                try {
                  if (!h) return out;
                  if (typeof h.forEach === 'function' && !Array.isArray(h)) {
                    h.forEach(function(v, k) { out[k] = cut(v, 2000); });
                    return out;
                  }
                  if (Array.isArray(h)) {
                    h.forEach(function(p) { if (p && p.length >= 2) out[p[0]] = cut(p[1], 2000); });
                    return out;
                  }
                  if (typeof h === 'string') {
                    var lines = h.split(/\\r?\\n/);
                    for (var i = 0; i < lines.length; i++) {
                      var line = lines[i];
                      if (!line) continue;
                      var idx = line.indexOf(':');
                      if (idx <= 0) continue;
                      out[line.substring(0, idx).trim().toLowerCase()] =
                        cut(line.substring(idx + 1).trim(), 2000);
                    }
                    return out;
                  }
                  for (var k in h) {
                    if (Object.prototype.hasOwnProperty.call(h, k)) out[k] = cut(h[k], 2000);
                  }
                } catch (e) {}
                return out;
              }
              function bodyOf(v) {
                try {
                  if (v === null || v === undefined) return '';
                  if (typeof v === 'string') return cut(v, MAX);
                  if (typeof FormData !== 'undefined' && v instanceof FormData) {
                    var parts = [];
                    try { v.forEach(function(val, k) { parts.push(k + '=' + (typeof val === 'string' ? cut(val, 2000) : '[file]')); }); } catch (e) {}
                    return cut(parts.join('&'), MAX);
                  }
                  if (typeof URLSearchParams !== 'undefined' && v instanceof URLSearchParams) return cut(v.toString(), MAX);
                  if (typeof Blob !== 'undefined' && v instanceof Blob) return '[Blob ' + (v.size || 0) + ' bytes]';
                  if (typeof ArrayBuffer !== 'undefined' && v instanceof ArrayBuffer) return '[ArrayBuffer ' + (v.byteLength || 0) + ' bytes]';
                  if (typeof v === 'object') {
                    try { return cut(JSON.stringify(v), MAX); } catch (e) { return '[object body]'; }
                  }
                  return cut(String(v), MAX);
                } catch (e) { return '[unreadable body]'; }
              }
              function dumpStore(store) {
                var out = {};
                try {
                  for (var i = 0; i < store.length && i < 100; i++) {
                    var k = store.key(i);
                    out[k] = cut(store.getItem(k), 3000);
                  }
                } catch (e) {}
                return out;
              }
              function collectControls() {
                var out = [];
                var seen = {};
                try {
                  var nodes = document.querySelectorAll('button,a,[role="button"],input[type="button"],input[type="submit"]');
                  for (var i = 0; i < nodes.length && out.length < 80; i++) {
                    var el = nodes[i];
                    try {
                      var rect = el.getBoundingClientRect();
                      if (rect.width === 0 && rect.height === 0) continue;
                    } catch (e) {}
                    var text = (el.innerText || el.textContent || el.value || el.getAttribute('aria-label') || el.title || '').replace(/[\\r\\n\\t ]+/g, ' ').replace(/^\\s+|\\s+$/g, '');
                    if (!text) continue;
                    var sig = (el.tagName || '') + '|' + text + '|' + (el.id || '');
                    if (seen[sig]) continue;
                    seen[sig] = true;
                    out.push({
                      text: cut(text, 200),
                      tag: el.tagName || '',
                      id: cut(el.id || '', 100),
                      cls: cut(String(el.className || ''), 200),
                      href: cut(el.href || '', 500)
                    });
                  }
                } catch (e) {}
                return out;
              }
              var lastSnap = 0;
              var lastSnapKey = '';
              function snapshot(force) {
                try {
                  var now = Date.now();
                  var key = location.href + '|' + document.title;
                  if (force && key === lastSnapKey && now - lastSnap < 1500) return;
                  if (!force && now - lastSnap < 3000) return;
                  lastSnap = now;
                  lastSnapKey = key;
                  var local = {};
                  var session = {};
                  try { local = dumpStore(localStorage); } catch (e) {}
                  try { session = dumpStore(sessionStorage); } catch (e) {}
                  send('page', {
                    title: document.title,
                    referrer: document.referrer,
                    ua: navigator.userAgent,
                    cookie: cut(document.cookie, 50000),
                    local: local,
                    session: session,
                    controls: collectControls()
                  });
                } catch (e) {}
              }
              window.__cmcc_cap_snapshot = snapshot;

              try {
                var XO = XMLHttpRequest.prototype.open;
                var XS = XMLHttpRequest.prototype.send;
                var XH = XMLHttpRequest.prototype.setRequestHeader;
                XMLHttpRequest.prototype.open = function(m, u) {
                  try { this.__cmcc_req = { m: String(m || 'GET'), u: absolute(u), h: {}, t: Date.now() }; } catch (e) {}
                  return XO.apply(this, arguments);
                };
                XMLHttpRequest.prototype.setRequestHeader = function(k, v) {
                  try { if (this.__cmcc_req) this.__cmcc_req.h[String(k)] = cut(v, 2000); } catch (e) {}
                  return XH.apply(this, arguments);
                };
                XMLHttpRequest.prototype.send = function(b) {
                  var x = this;
                  try { if (x.__cmcc_req) x.__cmcc_req.b = bodyOf(b); } catch (e) {}
                  try {
                    x.addEventListener('loadend', function() {
                      var q = x.__cmcc_req || {};
                      var resp = '';
                      try {
                        var rt = x.responseType || '';
                        if (rt === '' || rt === 'text') resp = cut(x.responseText, MAX);
                        else if (rt === 'json') resp = cut(JSON.stringify(x.response), MAX);
                        else resp = '[' + rt + ']';
                      } catch (e) { resp = '[unreadable response]'; }
                      var rh = {};
                      try { rh = headersOf(x.getAllResponseHeaders()); } catch (e) {}
                      send('xhr', {
                        url: q.u || absolute(x.responseURL || ''),
                        method: q.m || 'GET',
                        status: x.status || 0,
                        statusText: x.statusText || '',
                        duration: Date.now() - (q.t || Date.now()),
                        reqHeaders: q.h || {},
                        reqBody: q.b || '',
                        respHeaders: rh,
                        respBody: resp
                      });
                    });
                  } catch (e) {}
                  return XS.apply(this, arguments);
                };
              } catch (e) {}

              try {
                var F = window.fetch;
                if (F) {
                  window.fetch = function(input, init) {
                    var method = (init && init.method) || (input && input.method) || 'GET';
                    var url = '';
                    try { url = typeof input === 'string' ? input : ((input && input.url) || ''); } catch (e) {}
                    url = absolute(url);
                    var reqHeaders = headersOf(init && init.headers);
                    try {
                      var ih = headersOf(input && input.headers);
                      for (var k in ih) reqHeaders[k] = ih[k];
                    } catch (e) {}
                    var reqBody = bodyOf(init && init.body);
                    var start = Date.now();
                    var promise = F.apply(this, arguments);
                    if (promise && typeof promise.then === 'function') {
                      return promise.then(function(resp) {
                        try {
                          var respHeaders = {};
                          try {
                            if (resp.headers && resp.headers.forEach) {
                              resp.headers.forEach(function(v, k) { respHeaders[k] = cut(v, 2000); });
                            }
                          } catch (e) {}
                          var clone = resp.clone();
                          clone.text().then(function(text) {
                            send('fetch', {
                              url: url, method: method, status: resp.status,
                              statusText: resp.statusText, duration: Date.now() - start,
                              reqHeaders: reqHeaders, reqBody: reqBody,
                              respHeaders: respHeaders, respBody: cut(text, MAX)
                            });
                          }).catch(function() {
                            send('fetch', {
                              url: url, method: method, status: resp.status,
                              statusText: resp.statusText, duration: Date.now() - start,
                              reqHeaders: reqHeaders, reqBody: reqBody,
                              respHeaders: respHeaders, respBody: '[unreadable response]'
                            });
                          });
                        } catch (e) {}
                        return resp;
                      }, function(err) {
                        send('fetch', {
                          url: url, method: method, status: 0,
                          statusText: cut(err, 2000), duration: Date.now() - start,
                          reqHeaders: reqHeaders, reqBody: reqBody,
                          respHeaders: {}, respBody: ''
                        });
                        throw err;
                      });
                    }
                    return promise;
                  };
                }
              } catch (e) {}

              try {
                var SB = navigator.sendBeacon;
                if (SB) {
                  navigator.sendBeacon = function(url, data) {
                    try {
                      send('beacon', {
                        url: absolute(url), method: 'BEACON', status: 0,
                        statusText: '', duration: 0, reqHeaders: {},
                        reqBody: bodyOf(data), respHeaders: {}, respBody: ''
                      });
                    } catch (e) {}
                    return SB.apply(navigator, arguments);
                  };
                }
              } catch (e) {}

              function route() {
                send('route', { url: location.href, title: document.title });
              }
              try {
                var P = history.pushState;
                var R = history.replaceState;
                history.pushState = function() { var r = P.apply(this, arguments); setTimeout(route, 0); return r; };
                history.replaceState = function() { var r = R.apply(this, arguments); setTimeout(route, 0); return r; };
              } catch (e) {}
              window.addEventListener('popstate', route);
              window.addEventListener('hashchange', route);

              document.addEventListener('click', function(e) {
                try {
                  var el = e.target;
                  for (var i = 0; el && i < 6; i++) {
                    var tag = el.tagName;
                    if (tag === 'BUTTON' || tag === 'A' || tag === 'INPUT' || el.getAttribute('role') === 'button') break;
                    el = el.parentElement;
                  }
                  if (!el) return;
                  var text = (el.innerText || el.textContent || el.value || el.getAttribute('aria-label') || el.title || '').replace(/[\\r\\n\\t ]+/g, ' ').replace(/^\\s+|\\s+$/g, '');
                  if (!text) return;
                  send('click', {
                    text: cut(text, 300), tag: el.tagName || '',
                    id: cut(el.id || '', 100), cls: cut(String(el.className || ''), 300),
                    href: cut(el.href || '', 1000)
                  });
                } catch (e) {}
              }, true);

              window.addEventListener('error', function(e) {
                try {
                  if (!e || !e.message) return;
                  send('error', {
                    message: cut(e.message, 2000),
                    source: cut(e.filename || '', 500),
                    line: e.lineno || 0, col: e.colno || 0,
                    stack: cut(e.error && e.error.stack || '', 8000)
                  });
                } catch (x) {}
              });
              window.addEventListener('unhandledrejection', function(e) {
                try {
                  var r = e.reason;
                  var msg = r && r.message ? r.message : String(r);
                  send('error', {
                    message: 'unhandledrejection: ' + cut(msg, 2000),
                    source: 'promise', line: 0, col: 0,
                    stack: cut(r && r.stack || '', 8000)
                  });
                } catch (x) {}
              });

              try {
                if (document.readyState === 'loading') {
                  document.addEventListener('DOMContentLoaded', function() {
                    setTimeout(function() { snapshot(true); }, 100);
                  });
                } else {
                  setTimeout(function() { snapshot(true); }, 100);
                }
                setTimeout(function() { snapshot(true); }, 1800);
              } catch (e) {}

              // v3.64：米粒商城（wisdom-h5）的登录态是 sessionStorage.ssoId，
              // 而 sessionStorage 不跨页面共享。真实页面登录成功后，这里把它搬到
              // localStorage —— 同域、同账号（按 QWHDSSOJWT 前缀分桶），离屏页
              // 直接取来用，就不必再靠一次性的 h5token 去换。
              // 只在这个域生效，其它页面零开销。
              try {
                if (location.hostname.indexOf('digismart.lncmcc.com') >= 0) {
                  var moveSso = function() {
                    try {
                      var s = sessionStorage.getItem('ssoId');
                      if (!s || s.length < 16) return;
                      var jwt = String(localStorage.getItem('QWHDSSOJWT') || '');
                      // v3.80：13:36 日志实证 —— 页面登录成功后这里只写 _at、不写 _ph，
                      // 于是下次任务「只认本号桶」的扫描把这张刚拿到的有效票当成
                      // 无归属串号桶就地清掉，永远走不到复用登录态那一步。
                      // 归属取页面自己写的 loginMobile（和 __cmcc_phone 同形）。
                      var ph = '';
                      try { ph = String(sessionStorage.getItem('loginMobile') || '') } catch (e) {}
                      if (!ph) { try { ph = String(localStorage.getItem('__cmcc_phone') || '') } catch (e) {} }
                      if (!ph) { try { ph = String(window.__cmcc_phone || '') } catch (e) {} }
                      var old = '__cmcc_sso_' + jwt.slice(0, 12);
                      var k = ph ? '__cmcc_sso_' + ph + '_' + jwt.slice(0, 12) : old;
                      if (localStorage.getItem(k) !== s) {
                        localStorage.setItem(k, s);
                        localStorage.setItem(k + '_at', String(Date.now()));
                      }
                      if (ph) {
                        localStorage.setItem(k + '_ph', ph);
                        // 老版本写过的无归属同值桶就地清掉，别留两份。
                        if (k !== old && localStorage.getItem(old) === s) {
                          localStorage.removeItem(old);
                          localStorage.removeItem(old + '_at');
                          localStorage.removeItem(old + '_ph');
                        }
                      }
                    } catch (e) {}
                  };
                  moveSso();
                  setInterval(moveSso, 1500);
                }
              } catch (e) {}
            })();
            """;
}
