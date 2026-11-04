package com.qoder.cmcchook;

import android.os.Handler;
import android.os.Looper;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.WeakHashMap;

final class JsInjector {
    static final String BRIDGE_NAME = "__qoder_capture_7f3a";
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

    private static boolean rawOnMain(Object webView, String js) {
        try {
            Method method = findEvaluate(webView.getClass());
            if (method == null) {
                return false;
            }
            method.invoke(webView, js, null);
            return true;
        } catch (Throwable e) {
            HookLog.logcat("evaluateRaw failed: " + e);
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
              var B = '__qoder_capture_7f3a';
              if (window.__qoder_cap_v2) {
                try { if (window.__qoder_cap_snapshot) window.__qoder_cap_snapshot(document.readyState === 'complete'); } catch (e) {}
                return;
              }
              window.__qoder_cap_v2 = true;
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
              window.__qoder_cap_snapshot = snapshot;

              try {
                var XO = XMLHttpRequest.prototype.open;
                var XS = XMLHttpRequest.prototype.send;
                var XH = XMLHttpRequest.prototype.setRequestHeader;
                XMLHttpRequest.prototype.open = function(m, u) {
                  try { this.__qoder_req = { m: String(m || 'GET'), u: absolute(u), h: {}, t: Date.now() }; } catch (e) {}
                  return XO.apply(this, arguments);
                };
                XMLHttpRequest.prototype.setRequestHeader = function(k, v) {
                  try { if (this.__qoder_req) this.__qoder_req.h[String(k)] = cut(v, 2000); } catch (e) {}
                  return XH.apply(this, arguments);
                };
                XMLHttpRequest.prototype.send = function(b) {
                  var x = this;
                  try { if (x.__qoder_req) x.__qoder_req.b = bodyOf(b); } catch (e) {}
                  try {
                    x.addEventListener('loadend', function() {
                      var q = x.__qoder_req || {};
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
            })();
            """;
}
