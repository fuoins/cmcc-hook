package com.qoder.cmcchook;

import java.util.Locale;

final class UrlFilter {
    private UrlFilter() {
    }

    static boolean interesting(String url) {
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
