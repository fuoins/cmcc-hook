package com.qoder.cmcchook;

import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

final class LogEntry {
    final long time;
    final String tag;
    final String summary;
    final String detail;
    boolean expanded;

    LogEntry(long time, String tag, String summary, String detail) {
        this.time = time;
        this.tag = tag == null || tag.isEmpty() ? "LOG" : tag;
        this.summary = summary == null ? "" : summary;
        this.detail = detail == null ? "" : detail;
    }

    String key() {
        return time + "|" + tag + "|" + summary.hashCode();
    }

    JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("ts", time);
        o.put("tag", tag);
        o.put("summary", summary);
        o.put("detail", detail);
        return o;
    }

    static LogEntry fromJson(JSONObject o) {
        return new LogEntry(
                o.optLong("ts", System.currentTimeMillis()),
                o.optString("tag", "LOG"),
                o.optString("summary", ""),
                o.optString("detail", ""));
    }

    String formatTime() {
        return new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(new Date(time));
    }

    String formatFullTime() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date(time));
    }
}
