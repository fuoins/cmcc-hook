package com.cmcchook;

import java.util.Locale;

final class UrlFilter {
    private UrlFilter() {
    }

    static boolean interesting(String url) {
        boolean ok = match(url);
        if (ok) {
            // 顺手给「App 启动就绪」一个网络信号：目标 App 自己发出的第一个请求，
            // 说明它的启动流程已经走到联网这一步了。这里只写两个 volatile 字段，
            // 每个请求都过一遍也不会有可感知的开销。
            AppReady.onNetwork(url);
        }
        return ok;
    }

    private static boolean match(String url) {
        if (url == null) {
            return false;
        }
        String value = url.trim();
        if (value.isEmpty()) {
            return false;
        }
        String lower = value.toLowerCase(Locale.US);
        if (lower.startsWith("about:") || lower.startsWith("data:")
                || lower.startsWith("javascript:") || lower.startsWith("blob:")) {
            return false;
        }
        if (!HookPrefs.only10086()) {
            return true;
        }
        return lower.contains("10086.cn")
                || lower.contains(".cmcc")
                || lower.contains("cmcc.")
                || lower.contains("chinamobile")
                || lower.contains("qwhdhub")
                || lower.contains("nrapigate");
    }

    static String host(String url) {
        if (url == null) {
            return "";
        }
        try {
            java.net.URI uri = java.net.URI.create(url);
            String host = uri.getHost();
            return host == null ? "" : host;
        } catch (Throwable e) {
            int scheme = url.indexOf("://");
            if (scheme < 0) {
                return url;
            }
            int start = scheme + 3;
            int end = url.length();
            int slash = url.indexOf('/', start);
            if (slash >= 0) {
                end = slash;
            }
            return url.substring(start, end);
        }
    }

    static String shortForTitle(String url) {
        if (url == null) {
            return "";
        }
        String value = url;
        int hash = value.indexOf('#');
        if (hash > 0) {
            value = value.substring(0, hash);
        }
        if (value.length() > 180) {
            value = value.substring(0, 180) + "…";
        }
        return value;
    }
}
