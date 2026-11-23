#!/usr/bin/env python3
"""
把上游 api-enhanced/module/*.js 转换成插件用的接口描述 ncm-apis.json。

用法：
  python3 tools/convert.py /tmp/api-enhanced/module src/main/resources/ncm-apis.json

规则：
- 只接受“结构完全可证明等价”的模块：可选的 query.x = 表达式、可选的 const data = {...}、
  一句 return request(`uri`, data, createOption(query, 'crypto', 'checkToken'))，
  或 const res = await request(...) 后接 avatarImgId_str 改名。其余一律标为 manual。
- 字段值只允许 ExprEval.java 支持的语法（两边语法保持一致）。
- 已有 json 中的 tier（等级）和 status=custom（手写）/ unsupported（不移植）会被保留；新增模块默认 tier=public，请人工复核。
"""
import json
import os
import re
import sys


# 和 ApiRegistryTest.MUTATING_SEGMENT 保持一致
MUTATING_SEGMENT = re.compile(
    r"^(submit|replace\w*|update\w*|delete\w*|del|remove\w*|create|upload\w*|trash|signin|kickoff|obtain|add|send|bind"
    r"|unbind|edit|modify|set|clear|reset|logout)$", re.I)


def strip_comments(s):
    out, i, n, q = [], 0, len(s), None
    while i < n:
        c = s[i]
        if q:
            out.append(c)
            if c == "\\" and i + 1 < n:
                out.append(s[i + 1]); i += 2; continue
            if c == q:
                q = None
            i += 1; continue
        if c in "'\"`":
            q = c; out.append(c); i += 1; continue
        if s.startswith("//", i):
            j = s.find("\n", i); i = n if j < 0 else j; continue
        if s.startswith("/*", i):
            j = s.find("*/", i + 2); i = n if j < 0 else j + 2; continue
        out.append(c); i += 1
    return "".join(out)


class P:
    """表达式语法校验器，与 ExprEval.java 一致。"""

    def __init__(s, t): s.s = t; s.p = 0

    def ws(s):
        while s.p < len(s.s) and s.s[s.p].isspace(): s.p += 1

    def peek(s, tok): s.ws(); return s.s.startswith(tok, s.p)

    def eat(s, tok):
        if s.peek(tok): s.p += len(tok); return True
        return False

    def eat_word(s, w):
        s.ws()
        if s.s.startswith(w, s.p):
            e = s.p + len(w)
            if e >= len(s.s) or not (s.s[e].isalnum() or s.s[e] in "_$"):
                s.p = e; return True
        return False

    def expect(s, tok):
        if not s.eat(tok): raise ValueError("expect " + tok + " at " + s.s[s.p:s.p + 30])

    def expr(s):
        s.orx()
        if s.peek("?") and not s.peek("?.") and not s.peek("??"):
            s.eat("?"); s.expr(); s.expect(":"); s.expr()

    def orx(s):
        s.andx()
        while s.eat("||") or s.eat("??"): s.andx()

    def andx(s):
        s.eq()
        while s.eat("&&"): s.eq()

    def eq(s):
        s.add()
        for op in ("===", "!==", "==", "!="):
            if s.eat(op): s.add(); break

    def add(s):
        s.mul()
        while True:
            s.ws()
            if s.p < len(s.s) and s.s[s.p] in "+-" and s.s[s.p:s.p + 2] not in ("++", "--"):
                s.p += 1; s.mul()
            else: break

    def mul(s):
        s.unary()
        while True:
            s.ws()
            if s.p < len(s.s) and s.s[s.p] in "*/": s.p += 1; s.unary()
            else: break

    def unary(s):
        if s.eat("!"): return s.unary()
        s.ws()
        if s.p < len(s.s) and s.s[s.p] == "-": s.p += 1; return s.unary()
        s.primary()

    def ident(s):
        s.ws(); st = s.p
        while s.p < len(s.s) and (s.s[s.p].isalnum() or s.s[s.p] in "_$"): s.p += 1
        if st == s.p: raise ValueError("ident")
        return s.s[st:s.p]

    def string(s, q):
        s.p += 1
        while s.p < len(s.s) and s.s[s.p] != q:
            if s.s[s.p] == "\\": s.p += 1
            s.p += 1
        if s.p >= len(s.s): raise ValueError("unterminated")
        s.p += 1

    def template(s):
        s.p += 1
        while s.p < len(s.s) and s.s[s.p] != "`":
            if s.s.startswith("${", s.p):
                s.p += 2; s.expr(); s.expect("}"); continue
            if s.s[s.p] == "\\": s.p += 1
            s.p += 1
        if s.p >= len(s.s): raise ValueError("unterminated tpl")
        s.p += 1

    def primary(s):
        s.ws()
        if s.p >= len(s.s): raise ValueError("eof")
        c = s.s[s.p]
        if s.eat("query."):
            s.ident()
            if s.peek(".") or s.peek("["): raise ValueError("member chain")
            return
        if c in "'\"": return s.string(c)
        if c == "`": return s.template()
        if c.isdigit():
            while s.p < len(s.s) and (s.s[s.p].isdigit() or s.s[s.p] == "."): s.p += 1
            return
        for w in ("true", "false", "null", "undefined"):
            if s.eat_word(w): return
        if s.eat("Date.now()"): return
        for fn in ("JSON.stringify(", "parseInt(", "Number(", "String(", "isNaN("):
            if s.eat(fn):
                s.expr()
                if fn == "parseInt(" and s.eat(","): s.expr()
                s.expect(")"); return
        if s.eat("resourceTypeMap["): s.expr(); s.expect("]"); return
        if s.eat("("): s.expr(); s.expect(")"); return
        if c == "{": return s.obj()
        if c == "[": return s.arr()
        raise ValueError("unsupported: " + s.s[s.p:s.p + 40])

    def key(s):
        s.ws(); c = s.s[s.p]
        if c in "'\"":
            st = s.p; s.string(c); return s.s[st + 1:s.p - 1]
        if c == "[": raise ValueError("computed key")
        if s.s.startswith("...", s.p): raise ValueError("spread")
        return s.ident()

    def obj(s):
        s.expect("{")
        while not s.peek("}"):
            k = s.key()
            if not s.eat(":"): raise ValueError("shorthand " + k)
            s.expr()
            if not s.eat(","): break
        s.expect("}")

    def arr(s):
        s.expect("[")
        while not s.peek("]"):
            s.expr()
            if not s.eat(","): break
        s.expect("]")


def parse_expr_ok(t):
    p = P(t); p.expr(); p.ws(); return p.p == len(p.s)


def split_object(t):
    p = P(t); p.expect("{"); fields = {}
    while not p.peek("}"):
        k = p.key()
        if not p.eat(":"): raise ValueError("shorthand " + k)
        p.ws(); st = p.p; p.expr(); fields[k] = t[st:p.p].strip()
        if not p.eat(","): break
    p.expect("}"); p.ws()
    return fields, p.p


FN_RE = re.compile(r"module\.exports\s*=\s*(?:async\s*)?\(\s*query\s*(?:,\s*request\s*)?\)\s*=>\s*\{(.*)\}\s*;?\s*$", re.S)
OPT_RE = re.compile(r"createOption\(\s*query\s*(?:,\s*(['\"])(\w*)\1)?(?:\s*,\s*(['\"])(\w+)\3)?\s*,?\s*\)")
AVATAR_POST = re.compile(r"const\s+(\w+)\s*=\s*JSON\.stringify\(\s*(\w+)\s*\)\.replace\(\s*/avatarImgId_str/g\s*,\s*['\"]avatarImgIdStr['\"]\s*,?\s*\)\s*;?\s*return\s+JSON\.parse\(\s*\1\s*\)\s*;?\s*$", re.S)


def convert_body(body):
    data_fields = None
    dm = re.match(r"const\s+data\s*=\s*", body)
    if dm:
        rest = body[dm.end():]
        data_fields, end = split_object(rest)
        body = rest[end:].lstrip(";").strip()
    rm = re.match(r"return\s+request\(\s*", body)
    if not rm: raise ValueError("not-return-request")
    p = P(body); p.p = rm.end(); p.ws()
    c = body[p.p]
    if c not in "`'\"": raise ValueError("uri-not-literal")
    st = p.p
    p.template() if c == "`" else p.string(c)
    uri = body[st + 1:p.p - 1]
    if c == "`" and re.search(r"\$\{(?!\s*query\.\w+\s*\})", uri): raise ValueError("uri-complex")
    p.expect(","); p.ws()
    if body.startswith("data", p.p) and not body[p.p + 4].isalnum():
        if data_fields is None: raise ValueError("data-undefined")
        p.p += 4; fields = data_fields
    elif body[p.p] == "{":
        fields, end = split_object(body[p.p:]); p.p += end
    else:
        raise ValueError("data-arg")
    p.expect(","); p.ws()
    om = OPT_RE.match(body, p.p)
    if not om: raise ValueError("option")
    p.p = om.end(); p.eat(","); p.expect(")"); p.eat(";"); p.ws()
    if p.p != len(body): raise ValueError("trailing")
    for v in fields.values():
        if not parse_expr_ok(v): raise ValueError("expr")
    return dict(uri=uri, crypto=om.group(2) or "default", checkToken=om.group(4) or "", fields=fields)


def convert(src):
    m = FN_RE.search(strip_comments(src))
    if not m: raise ValueError("no-fn")
    body = m.group(1).strip()
    pre = []
    while True:
        am = re.match(r"query\.(\w+)\s*=(?!=)\s*", body)
        if not am: break
        p = P(body); p.p = am.end(); p.ws(); st = p.p; p.expr()
        expr = body[st:p.p].strip()
        if not parse_expr_ok(expr): raise ValueError("pre")
        pre.append([am.group(1), expr]); p.eat(";"); body = body[p.p:].strip()
    post = None
    am = re.search(r"const\s+(\w+)\s*=\s*await\s+request\(", body)
    if am:
        i, depth, q = am.end() - 1, 0, None
        while i < len(body):
            c = body[i]
            if q:
                if c == "\\": i += 2; continue
                if c == q: q = None
            elif c in "'\"`": q = c
            elif c == "(": depth += 1
            elif c == ")":
                depth -= 1
                if depth == 0: break
            i += 1
        call = body[am.end() - len("request("):i + 1]
        tail = body[i + 1:].lstrip(";").strip()
        pm = AVATAR_POST.match(tail)
        if pm and pm.group(2) == am.group(1): post = "renameAvatarImgId"
        elif not re.fullmatch(r"return\s+" + am.group(1) + r"\s*;?", tail): raise ValueError("await-post")
        body = body[:am.start()] + "return " + call
    d = convert_body(body)
    if pre: d["pre"] = pre
    if post: d["post"] = post
    return d


def main(module_dir, out_path):
    old = json.load(open(out_path, encoding="utf-8")) if os.path.exists(out_path) else {}
    new, manual = {}, []
    for f in sorted(os.listdir(module_dir)):
        if not f.endswith(".js"): continue
        name = f[:-3]
        prev = old.get(name, {})
        e = {"tier": prev.get("tier", "public")}
        if prev.get("status") in ("custom", "unsupported"):
            e = dict(prev)
        else:
            try:
                d = convert(open(os.path.join(module_dir, f), encoding="utf-8").read())
                e["status"] = "ready"
                e.update(uri=d["uri"], crypto=d["crypto"], fields=d["fields"])
                for k in ("checkToken", "pre", "post"):
                    if d.get(k): e[k] = d[k]
            except Exception as ex:
                e["status"] = "manual"; e["reason"] = str(ex)[:80]; manual.append(name)
        if name not in old:
            print("新增模块（请复核 tier）:", name)
        if e.get("tier") in ("public", "personal") and e.get("uri"):
            segs = [s for s in e["uri"].split("/") if s and "${" not in s]
            if any(MUTATING_SEGMENT.match(s) for s in segs):
                print("警告：地址像是修改操作，tier 应为 write（ApiRegistryTest 会失败）:", name, e["uri"])
        new[name] = e
    for name in old:
        if name not in new: print("上游已删除:", name)
    json.dump(new, open(out_path, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    ready = sum(1 for v in new.values() if v["status"] in ("ready", "custom"))
    print(f"共 {len(new)} 个，可执行 {ready} 个，待手写 {len(manual)} 个")


if __name__ == "__main__":
    if len(sys.argv) != 3:
        print(__doc__); sys.exit(1)
    main(sys.argv[1], sys.argv[2])
