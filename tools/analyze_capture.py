#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
r20 离线分析器 —— 把模块导出的全量原始流（*.jsonl）变成可读的活动画像与描述草稿。

这是「录一次 → 离线分析 → 产出描述 → 下发执行」闭环里跑在 PC / 沙箱这一侧的工具：
上游作者能在 PC 上一遍遍重放请求、看完整响应、改一个参数再试，靠的就是这条
可重复的离线回路；真机日志做不到。本脚本把这份能力补上——吃一份导出的 JSONL，
**不发任何网络请求、不碰账号**，就能反复分析、diff、版本化。

用法：
    python3 tools/analyze_capture.py capture.jsonl
    python3 tools/analyze_capture.py capture.jsonl --json out.json
    python3 tools/analyze_capture.py capture.jsonl --draft miao_browse
    python3 tools/analyze_capture.py --selftest

输出：
    1) 接口清单     去重后的 method + path、出现次数、成功码分布、幂等候选
    2) 参数依赖图   响应下发的标量值 → 被后续哪个请求的 body/query/header 引用
                     （自动发现，不维护白名单）
    3) 密文标记     带签名头 / 加密域的请求，标「不可离线参数化，需真页上下文」
    4) 成功判据候选 从 code/resultCode/isFinish/remain 推断 okWhen
    5) 活动描述草稿 cmcc-activity/1，可直接粘进模块「导入描述」再人工核对

脱敏说明：模块默认导出的流里敏感值已换成 <redacted:指纹 len=N> 占位符，
同一个值在任何位置占位符一致，所以依赖链仍然连得上；本脚本不需要真实值。
"""

import argparse
import json
import re
import sys
from collections import OrderedDict, defaultdict

SCHEMA = "cmcc-activity/1"
CIPHER_HINTS = ("/lyh/", "encrypt", "wasmparams", "getappkey", "websocket")
SIGN_HEADERS = ("x-sign", "x-nonce", "wasparams", "encryptdata", "x-token")
CODE_KEYS = ("code", "resultCode", "retCode", "rtnCode", "status")
STATE_KEYS = ("isFinish", "remain", "drawTimes", "finish", "remainingTimes")
PHONE_RE = re.compile(r"(?<!\d)1[3-9]\d{9}(?!\d)")

# r22：埋点/监控域名。11-13 的录制流 877 条里 606 条（69%）是极光推送统计、
# APM 监控和 H5 日志上报，纯噪音会把接口清单和成功判据候选淹掉。模块侧
# RawCapture.wanted() 已默认不录；这里再兜一层，老文件也能得到干净结果。
NOISE_URLS = ("jiguang", "apm.app.coc", "newsendlogforaes", "sensorsdata",
              "umeng", "giodata", "/gio/", "talkingdata", "bugly", "beacon.")


def is_noise(url):
    u = (url or "").lower()
    return any(n in u for n in NOISE_URLS)


# ----------------------------------------------------------------- 载入

def load_records(path):
    """逐行读 JSONL，坏行跳过但不丢，返回 (records, 跳过数)。"""
    recs, bad = [], 0
    with open(path, "r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            try:
                o = json.loads(line)
                if isinstance(o, dict):
                    recs.append(o)
                else:
                    bad += 1
            except (ValueError, TypeError):
                bad += 1
    recs.sort(key=lambda r: (r.get("ts", 0), r.get("seq", 0)))
    return recs, bad


def path_of(url):
    if not url:
        return ""
    p = url.split("?", 1)[0]
    if "://" in p:
        rest = p.split("://", 1)[1]
        slash = rest.find("/")
        return rest[slash:] if slash >= 0 else "/"
    return p


def host_of(url):
    if "://" not in url:
        return "unknown"
    rest = url.split("://", 1)[1]
    return rest.split("/", 1)[0] or rest


def try_json(text):
    if not text or not isinstance(text, str):
        return None
    t = text.strip()
    if not (t.startswith("{") or t.startswith("[")):
        return None
    try:
        return json.loads(t)
    except (ValueError, TypeError):
        return None


# ----------------------------------------------------------------- 1) 接口清单

def interfaces(recs):
    table = OrderedDict()
    for r in recs:
        url = r.get("url", "")
        method = (r.get("method") or "GET").upper()
        key = (method, path_of(url))
        row = table.setdefault(key, {
            "method": method, "path": key[1], "host": host_of(url),
            "count": 0, "status": defaultdict(int), "codes": defaultdict(int),
            "acts": set(), "sampleUrl": url[:160],
        })
        row["count"] += 1
        st = r.get("status", 0)
        row["status"][str(st)] += 1
        act = r.get("act", "")
        if act:
            row["acts"].add(act)
        body = try_json(r.get("respBody", ""))
        if isinstance(body, dict):
            for k in CODE_KEYS:
                if k in body:
                    row["codes"][str(body[k])] += 1
                    break
    out = []
    for row in table.values():
        # 幂等候选：GET 且没有明显「消耗」语义的路径，供人工确认
        p = row["path"].lower()
        idempotent = row["method"] == "GET" and not any(
            w in p for w in ("draw", "lottery", "exchange", "receive", "claim", "finish", "do"))
        out.append({
            "method": row["method"],
            "path": row["path"],
            "host": row["host"],
            "count": row["count"],
            "status": dict(row["status"]),
            "codes": dict(row["codes"]),
            "acts": sorted(row["acts"]),
            "idempotentCandidate": idempotent,
            "sampleUrl": row["sampleUrl"],
        })
    return out


# ----------------------------------------------------------------- 2) 参数依赖

def _walk(obj, prefix, seq, url, sink, depth=0):
    """收集响应里的标量值：值 -> (字段名, json path, 下发处 seq/url, ts)。"""
    if depth > 3 or len(sink) >= 6000:
        return
    if isinstance(obj, dict):
        for i, (k, v) in enumerate(obj.items()):
            if i >= 120:
                break
            path = f"{prefix}.{k}" if prefix else k
            if isinstance(v, (dict, list)):
                _walk(v, path, seq, url, sink, depth + 1)
            else:
                _index(v, path, k, seq, url, sink)
    elif isinstance(obj, list):
        for i, v in enumerate(obj[:12]):
            path = f"{prefix}.{i}"
            if isinstance(v, (dict, list)):
                _walk(v, path, seq, url, sink, depth + 1)
            else:
                _index(v, path, prefix.split(".")[-1], seq, url, sink)


def _index(v, path, field, seq, url, sink):
    if v is None or isinstance(v, bool):
        return
    s = str(v)
    if not (4 <= len(s) <= 160):
        return
    if s in ("0", "1", "SUCCESS", "FAIL"):
        return
    if s not in sink:
        sink[s] = {"field": field, "path": path, "seq": seq, "url": url}


def dependencies(recs, cap=400):
    """响应标量值 → 后续请求引用。只认「先下发、后引用」，天然排除巧合。"""
    src = {}
    for r in recs:
        body = try_json(r.get("respBody", ""))
        if body is not None:
            _walk(body, "", r.get("seq", 0), r.get("url", ""), src)
    out = []
    for r in recs:
        if len(out) >= cap:
            break
        blob = " ".join(str(r.get(k, "") or "")
                        for k in ("reqBody", "reqHeaders", "url"))
        if len(blob) < 8:
            continue
        seq = r.get("seq", 0)
        for val, meta in src.items():
            if meta["seq"] >= seq:
                continue
            if val in blob:
                out.append({
                    "name": meta["field"],
                    "valueLen": len(val),
                    "fromSeq": meta["seq"],
                    "fromUrl": meta["url"],
                    "fromPath": meta["path"],
                    "usedInSeq": seq,
                    "usedInUrl": r.get("url", ""),
                    "usedInPath": path_of(r.get("url", "")),
                })
                break
    return out


# ----------------------------------------------------------------- 3) 密文标记

def encrypted(recs):
    out = []
    for r in recs:
        url = (r.get("url", "") or "").lower()
        heads = (" ".join(str(r.get(k, "") or "")
                          for k in ("reqHeaders", "respHeaders"))).lower()
        reasons = []
        if any(h in url for h in CIPHER_HINTS):
            reasons.append("加密域 URL")
        hit = [h for h in SIGN_HEADERS if h in heads]
        if hit:
            reasons.append("签名/风控头: " + ",".join(hit))
        if reasons:
            out.append({
                "seq": r.get("seq", 0),
                "url": r.get("url", ""),
                "path": path_of(r.get("url", "")),
                "reasons": reasons,
                "note": "参数页面加密或每次现算签名，离线重放不可行；"
                        "只能页面内执行或保留 DOM 点击兜底",
            })
    return out


# ----------------------------------------------------------------- 4) 成功判据候选

def ok_candidates(recs):
    out = []
    for r in recs:
        body = try_json(r.get("respBody", ""))
        if not isinstance(body, dict):
            continue
        code = next((body[k] for k in CODE_KEYS if k in body), None)
        if code is None:
            continue
        cand = {
            "seq": r.get("seq", 0),
            "path": path_of(r.get("url", "")),
            "code": str(code),
            "msg": next((str(body[k]) for k in ("msg", "message", "resultDesc", "retMsg")
                         if k in body), ""),
            "suggestedOkWhen": {"path": "$.code", "eq": str(code)},
        }
        data = body.get("data")
        if isinstance(data, dict):
            states = {k: data[k] for k in STATE_KEYS if k in data}
            if states:
                cand["serverStates"] = states
                # 状态量比 code 更硬：优先建议用 isFinish 之类判成功
                key = "isFinish" if "isFinish" in states else next(iter(states))
                cand["suggestedOkWhen"] = {"path": f"$.data.{key}", "eq": states[key]}
        out.append(cand)
    return out


# ----------------------------------------------------------------- 5) 描述草稿

def draft(recs, deps, ifaces, okc, enc, act_id):
    enc_paths = {e["path"] for e in enc}
    steps = []
    entry = ""
    for r in recs:
        url = r.get("url", "")
        if not url or path_of(url) in enc_paths:
            continue
        if not entry:
            entry = url
        if len(steps) >= 24:
            break
        step = {
            "kind": "http",
            "id": "s%d" % r.get("seq", len(steps) + 1),
            "method": (r.get("method") or "GET").upper(),
            "path": path_of(url),
        }
        body = r.get("reqBody", "") or ""
        if body:
            step["body"] = body if len(body) <= 400 else "<见 jsonl 原文>"
        steps.append(step)
    ok = {"hint": "见 okWhenCandidates，人工确认后填 path+eq"}
    if okc:
        ok = okc[-1].get("suggestedOkWhen", ok)
    return {
        "schema": SCHEMA,
        "id": act_id,
        "cmd": act_id,
        "name": "<按活动名补>",
        "enabled": False,
        "entry": entry,
        "budgetMs": 60000,
        "requires": ["phone"],
        "idempotent": "day",
        "steps": steps,
        "okWhen": ok,
        "note": "由 analyze_capture.py 从录制流自动生成的草稿，未验证；"
                "核对 jsonl 原文、参数依赖与成功判据后再把 enabled 改 true 下发。",
        "_deps": deps,
        "_interfaceCount": len(ifaces),
        "_encryptedCount": len(enc),
    }


# ----------------------------------------------------------------- 报告

def analyze(path, act_id="unnamed", keep_noise=False):
    recs, bad = load_records(path)
    total = len(recs)
    if not keep_noise:
        recs = [r for r in recs if not is_noise(r.get("url"))]
    ifaces = interfaces(recs)
    deps = dependencies(recs)
    enc = encrypted(recs)
    okc = ok_candidates(recs)
    result = {
        "schema": "cmcc-capture-analysis/1",
        "source": path,
        "stats": {
            "records": len(recs), "badLines": bad, "noise": total - len(recs),
            "interfaces": len(ifaces), "dependencies": len(deps),
            "encrypted": len(enc), "okCandidates": len(okc),
            "acts": len({r.get("act", "") for r in recs if r.get("act", "")}),
        },
        "interfaces": ifaces,
        "dependencies": deps,
        "encrypted": enc,
        "okWhenCandidates": okc,
        "descriptorDraft": draft(recs, deps, ifaces, okc, enc, act_id),
    }
    return result


def render(result, max_rows=40):
    s = result["stats"]
    lines = [
        "=" * 68,
        "中国移动 · 录制流离线分析（r20）",
        "=" * 68,
        f"源文件      : {result['source']}",
        f"请求记录    : {s['records']} 条（坏行 {s['badLines']}，已过滤埋点 {s.get('noise', 0)} 条）",
        f"接口        : {s['interfaces']} 个  |  参数依赖 {s['dependencies']} 条",
        f"密文域      : {s['encrypted']} 个  |  成功判据候选 {s['okCandidates']} 个",
        f"归属活动    : {s['acts']} 个",
        "",
        "── 接口清单（去重，按出现顺序）──",
    ]
    for i, it in enumerate(result["interfaces"][:max_rows]):
        codes = ",".join(f"{k}×{v}" for k, v in list(it["codes"].items())[:3]) or "-"
        flag = "幂等?" if it["idempotentCandidate"] else "      "
        lines.append(f"  {flag} {it['method']:4} {it['path'][:52]:52} "
                     f"×{it['count']:<3} code={codes}")
    if len(result["interfaces"]) > max_rows:
        lines.append(f"  … 另有 {len(result['interfaces']) - max_rows} 个接口（见 --json）")

    lines += ["", "── 参数依赖（响应下发 → 后续请求引用，自动发现）──"]
    if not result["dependencies"]:
        lines.append("  （没发现跨请求的值引用：可能全是固定参数，或参数页面加密）")
    for d in result["dependencies"][:max_rows]:
        lines.append(f"  {d['name']:<18} len={d['valueLen']:<3} "
                     f"seq{d['fromSeq']} {d['fromPath'][:26]:26} → seq{d['usedInSeq']} "
                     f"{d['usedInPath'][:34]}")

    lines += ["", "── 密文 / 不可离线参数化 ──"]
    if not result["encrypted"]:
        lines.append("  （无）")
    for e in result["encrypted"][:15]:
        lines.append(f"  seq{e['seq']} {e['path'][:44]:44} {'; '.join(e['reasons'])}")

    lines += ["", "── 成功判据候选 ──"]
    if not result["okWhenCandidates"]:
        lines.append("  （没读到带 code 的 JSON 响应）")
    for c in result["okWhenCandidates"][:12]:
        lines.append(f"  seq{c['seq']} {c['path'][:40]:40} code={c['code']:<12} "
                     f"→ okWhen={json.dumps(c['suggestedOkWhen'], ensure_ascii=False)}")

    d = result["descriptorDraft"]
    lines += [
        "",
        "── 活动描述草稿（cmcc-activity/1）──",
        f"  id={d['id']}  enabled={d['enabled']}  steps={len(d['steps'])}  "
        f"budget={d['budgetMs']}ms",
        f"  entry={d['entry'][:64]}",
        f"  okWhen={json.dumps(d['okWhen'], ensure_ascii=False)}",
        "  ⚠ 草稿默认 enabled=false；核对 jsonl 原文与判据后再启用，避免假 ✓。",
        "",
        "下一步：",
        "  python3 tools/analyze_capture.py <file> --draft <id>   # 只输出描述 JSON",
        "  把描述粘进模块「描述清单 / 导入」→ 核对 → enabled=true → 跑",
        "=" * 68,
    ]
    return "\n".join(lines)


# ----------------------------------------------------------------- 自验（假数据，不发请求）

SELFTEST_DATA = [
    {"ts": 1000, "seq": 1, "url": "https://wx.10086.cn/coc3/getGrowthTaskList",
     "method": "POST", "act": "miao", "status": 200,
     "reqBody": "{\"taskListId\":55}",
     "respBody": json.dumps({"resultCode": 0, "data": [
         {"taskId": "T-9001", "unfinishRedirectUrl": "https://x/online/live88"}]})},
    {"ts": 2000, "seq": 2, "url": "https://wx.10086.cn/coc3/visit",
     "method": "POST", "act": "miao", "status": 200,
     "reqBody": json.dumps({"taskId": "T-9001", "visitUrl": "/online/live88"}),
     "respBody": json.dumps({"resultCode": 0, "data": {"isFinish": 1}})},
    {"ts": 3000, "seq": 3, "url": "https://lyh.10086.cn/api/doTask?actId=55",
     "method": "POST", "act": "egg", "status": 200,
     "reqHeaders": "X-Sign: abc\nX-Nonce: def",
     "reqBody": "{\"encryptData\":\"<cipher>\"}",
     "respBody": json.dumps({"code": "SUCCESS"})},
]


def selftest():
    import tempfile, os
    fails, total = [], 0
    fd, path = tempfile.mkstemp(suffix=".jsonl", prefix="cmcc-selftest-")
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            for r in SELFTEST_DATA:
                f.write(json.dumps(r, ensure_ascii=False) + "\n")
            f.write("THIS-IS-A-BAD-LINE\n")

        def check(cond, msg):
            nonlocal total
            total += 1
            if not cond:
                fails.append(msg)

        r = analyze(path, "miao_browse")
        check(r["stats"]["records"] == 3, "记录数应为 3")
        check(r["stats"]["badLines"] == 1, "坏行应被跳过且计数")
        check(r["stats"]["interfaces"] == 3, f"接口数错: {r['stats']['interfaces']}")
        check(r["stats"]["dependencies"] >= 1, "没发现 taskId 依赖")
        dep = next((d for d in r["dependencies"] if d["name"] == "taskId"), None)
        check(dep is not None and dep["usedInSeq"] == 2, "taskId 依赖没指向 seq2")
        check(r["stats"]["encrypted"] == 1, "密文域没被标记")
        check(r["encrypted"][0]["path"].startswith("/api/doTask") or
              "doTask" in r["encrypted"][0]["path"], "密文路径错")
        check(r["stats"]["okCandidates"] == 3, "成功判据候选数错")
        # 状态量优先
        state_ok = [c for c in r["okWhenCandidates"] if "isFinish" in c.get("serverStates", {})]
        check(len(state_ok) == 1 and state_ok[0]["suggestedOkWhen"]["path"] == "$.data.isFinish",
              "应优先用 isFinish 状态量做 okWhen")
        d = r["descriptorDraft"]
        check(d["schema"] == SCHEMA, "草稿 schema 错")
        check(d["enabled"] is False, "草稿不该默认启用")
        check(len(d["steps"]) == 2, f"草稿应排除密文步骤，实得 {len(d['steps'])}")
        check(all("doTask" not in s["path"] for s in d["steps"]), "密文步骤混进草稿")
        # 确定性脱敏：同值占位符一致，依赖仍连得上
        masked = [dict(x) for x in SELFTEST_DATA]
        ph = "<redacted:1a2b3c4d len=6>"
        masked[0]["respBody"] = masked[0]["respBody"].replace("T-9001", ph)
        masked[1]["reqBody"] = masked[1]["reqBody"].replace("T-9001", ph)
        fd2, p2 = tempfile.mkstemp(suffix=".jsonl", prefix="cmcc-selftest-mask-")
        with os.fdopen(fd2, "w", encoding="utf-8") as f:
            for x in masked:
                f.write(json.dumps(x, ensure_ascii=False) + "\n")
        r2 = analyze(p2, "masked")
        check(r2["stats"]["dependencies"] >= 1, "脱敏后依赖链断了（占位符不一致）")
        os.unlink(p2)
        # 渲染不炸
        check("接口清单" in render(r), "报告渲染异常")
        # 描述草稿能过基本字段检查（与 Java 侧 schema 对齐）
        check(isinstance(d["steps"], list) and 1000 <= d["budgetMs"] <= 600000,
              "草稿 budgetMs 越界")
    finally:
        os.unlink(path)

    if fails:
        return "FAIL %d/%d\n  - %s" % (len(fails), total, "\n  - ".join(fails)), 1
    return "PASS %d/%d · 离线分析器 载入/去重/依赖/密文/判据/草稿/脱敏链/渲染" % (total, total), 0


def main():
    ap = argparse.ArgumentParser(description="r20 录制流离线分析器（不发请求）")
    ap.add_argument("file", nargs="?", help="模块导出的 *.jsonl")
    ap.add_argument("--json", dest="json_out", help="把完整结果写成 JSON 文件")
    ap.add_argument("--draft", help="只输出某 id 的活动描述草稿 JSON")
    ap.add_argument("--selftest", action="store_true", help="用内置假数据自验")
    ap.add_argument("--keep-noise", action="store_true",
                    help="保留极光/APM 等埋点（默认过滤）")
    args = ap.parse_args()

    if args.selftest:
        text, code = selftest()
        print(text)
        return code
    if not args.file:
        ap.error("给一个 *.jsonl，或用 --selftest")

    try:
        r = analyze(args.file, args.draft or "unnamed", keep_noise=args.keep_noise)
    except OSError as e:
        print("读取失败: %s" % e, file=sys.stderr)
        return 2

    if args.draft:
        d = dict(r["descriptorDraft"])
        d.pop("_deps", None)
        d.pop("_interfaceCount", None)
        d.pop("_encryptedCount", None)
        print(json.dumps(d, ensure_ascii=False, indent=2))
    else:
        print(render(r))
    if args.json_out:
        with open(args.json_out, "w", encoding="utf-8") as f:
            json.dump(r, f, ensure_ascii=False, indent=2)
        print("\n完整结果已写入 " + args.json_out)
    return 0


if __name__ == "__main__":
    sys.exit(main())
