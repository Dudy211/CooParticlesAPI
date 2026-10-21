#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Mixin 预检（v6）—— 在真机运行之前抓住三类「编译器看不见」的错。

  A. 目标成员在 1.20.1 里根本不存在      （1.21 有 / 1.20.1 改名或删除）
  B. handler 签名与目标方法描述符不匹配   （Mixin 运行时报 Invalid descriptor）
  C. 目标需要 refmap 条目却缺失           （dev 能过、生产静默失效/崩溃）

数据源：build/createMcpToSrg/output.tsrg（全量映射表）+ mixins.json + 源码 + jar 内 refmap
用法：  python3 verify_mixin_targets.py ~/mcwork/um1201
"""
import os, re, json, glob, sys, zipfile

PRIM = {"void":"V","int":"I","long":"J","double":"D","float":"F","boolean":"Z",
        "byte":"B","char":"C","short":"S"}
BOXED = {"Integer":"java/lang/Integer","Long":"java/lang/Long","Double":"java/lang/Double",
         "Float":"java/lang/Float","Boolean":"java/lang/Boolean","Byte":"java/lang/Byte",
         "Character":"java/lang/Character","Short":"java/lang/Short","Void":"java/lang/Void",
         "String":"java/lang/String","Object":"java/lang/Object","CharSequence":"java/lang/CharSequence",
         "List":"java/util/List","Map":"java/util/Map"}
ANNS = ("Inject","Redirect","ModifyArg","ModifyArgs","ModifyVariable","ModifyConstant","Overwrite")

def load_tsrg(path):
    out, cur = {}, None
    for line in open(path, encoding="utf-8", errors="replace"):
        if line.startswith("tsrg2") or not line.strip():
            continue
        if not line.startswith("\t"):
            cur = line.split()[0]; out.setdefault(cur, {"f":{}, "m":{}, "any":set()}); continue
        if line.startswith("\t\t") or cur is None:
            continue
        p = line[1:].rstrip("\n").split()
        if len(p) >= 2 and p[1].startswith("("):
            out[cur]["m"].setdefault(p[0], []).append((p[1], p[2] if len(p) > 2 else p[0]))
            out[cur]["any"].add(p[0])
        elif len(p) >= 2:
            out[cur]["f"][p[0]] = p[1]; out[cur]["any"].add(p[0])
    return out

def find_mc_srg_jar(proj):
    """定位 MC 的 srg jar(ForgeGradle 缓存里),用于读取真实字段修饰符。"""
    import glob
    pats = [
        "/workspace/mc/gradle_user_home/caches/forge_gradle/mcp_repo/net/minecraft/joined/*/joined-*-srg.jar",
        os.path.expanduser("~/.gradle/caches/forge_gradle/mcp_repo/net/minecraft/joined/*/joined-*-srg.jar"),
        os.path.join(proj, "build/createMcpToSrg/*.jar"),
    ]
    for pat in pats:
        hits = glob.glob(pat)
        if hits:
            return sorted(hits)[-1]
    return None


def collect_final_fields(jar_path, class_names):
    """用 javap 读 srg jar,采集目标类里所有 `static final` 字段(名字集合)。

    ★ 2026-10-21:@Shadow 字段必须与目标修饰符一致,否则 attachFields 会崩。
    这里拿到真实 final 字段名,供上面的校验使用。
    """
    import subprocess, tempfile, shutil
    out = set()
    if not jar_path or not os.path.exists(jar_path):
        return out
    javap = shutil.which("javap")
    if javap is None:
        return out
    tmp = tempfile.mkdtemp()
    try:
        for cn in class_names:
            entry = cn.replace(".", "/") + ".class"
            try:
                with zipfile.ZipFile(jar_path) as z:
                    if entry not in z.namelist():
                        continue
                    z.extract(entry, tmp)
            except Exception:
                continue
            try:
                r = subprocess.run([javap, "-p", os.path.join(tmp, entry)],
                                   capture_output=True, text=True, timeout=60)
                for line in r.stdout.splitlines():
                    st = line.strip()
                    if not st.startswith(("private", "public", "protected")):
                        continue
                    if " final " not in f" {st} ":
                        continue
                    # javap: `private static final java.util.Map<...> f_90810_;`
                    # 字段名是 `;` 前最后一段(泛型里有空格,不能用 split()[-1])
                    body = st.rstrip(";").rstrip()
                    m2 = re.search(r'([\w$]+)\s*$', body)
                    if m2:
                        out.add(m2.group(1))
            except Exception:
                pass
    finally:
        shutil.rmtree(tmp, ignore_errors=True)
    return out


def strip_comments(s):
    s = re.sub(r'/\*[\s\S]*?\*/', '', s)
    return re.sub(r'//[^\n]*', '', s)

def bal(s, i):
    """s[i] == '(' → 返回匹配的 ')' 的下标"""
    d = 0
    while i < len(s):
        c = s[i]
        if c == '(': d += 1
        elif c == ')':
            d -= 1
            if d == 0: return i
        elif c == '"':
            i += 1
            while i < len(s) and s[i] != '"':
                i += 2 if s[i] == '\\' else 1
        i += 1
    return -1

def split_top_level(s):
    """把注解参数串按【顶层逗号】切开(括号/引号内不算)。

    例:'method = X, at = @At(value = "INVOKE", target = "...")'
        → ['method = X', 'at = @At(value = "INVOKE", target = "...")']
    """
    out, depth, cur, i = [], 0, [], 0
    while i < len(s):
        c = s[i]
        if c == '"':
            cur.append(c); i += 1
            while i < len(s) and s[i] != '"':
                if s[i] == "\\":
                    cur.append(s[i]); i += 1
                if i < len(s):
                    cur.append(s[i]); i += 1
            if i < len(s):
                cur.append(s[i]); i += 1
            continue
        if c in "([{":
            depth += 1
        elif c in ")]}":
            depth -= 1
        if c == "," and depth == 0:
            out.append("".join(cur).strip()); cur = []
        else:
            cur.append(c)
        i += 1
    if cur:
        out.append("".join(cur).strip())
    return [a for a in out if a]


def desc_params(d):
    """JVM 方法描述符 -> 参数类型列表  例: (JDD)V -> ['J','D','D']"""
    if "(" not in d: return []
    i = d.index("(") + 1
    out = []
    while i < len(d) and d[i] != ")":
        dims = 0
        while d[i] == "[":
            dims += 1; i += 1
        if d[i] == "L":
            j = d.index(";", i)
            out.append("[" * dims + d[i:j + 1]); i = j + 1
        else:
            out.append("[" * dims + d[i]); i += 1
    return out


def split_params(s):
    out, d, cur = [], 0, ""
    for ch in s:
        if ch in "<([": d += 1
        elif ch in ">)]": d -= 1
        if ch == "," and d == 0:
            out.append(cur); cur = ""
        else:
            cur += ch
    if cur.strip(): out.append(cur)
    return [x.strip() for x in out if x.strip()]

def desc_of(t, imports, pkg):
    t = re.sub(r'@\w+(\([^)]*\))?\s*', '', t)
    t = re.sub(r'<[^<>]*>', '', t)
    t = re.sub(r'\b(final|volatile)\b', '', t).strip()
    dims = t.count("[]")
    t = t.replace("[]", "").replace("...", "[]").strip()
    if t in PRIM: return "[" * dims + PRIM[t]
    if t in BOXED: return "[" * dims + "L" + BOXED[t] + ";"
    if t in imports:
        full = imports[t]
    elif "." in t:
        outer, inner = t.split(".", 1)
        if outer in imports:
            full = imports[outer] + "$" + inner
        else:
            full = (pkg + "." + outer) + "$" + inner
    else:
        full = pkg + "." + t
    return "[" * dims + "L" + full.replace(".", "/") + ";"

def main():
    proj = os.path.expanduser(sys.argv[1] if len(sys.argv) > 1 else "~/mcwork/cp1201")
    ts = load_tsrg(os.path.join(proj, "build/createMcpToSrg/output.tsrg"))
    mjs = sorted(glob.glob(os.path.join(proj, "src/main/resources/*.mixins.json")))
    if not mjs:
        print("找不到 mixins.json"); return 1

    libs = glob.glob(os.path.join(proj, "build/libs/*.jar"))

    # ★ 2026-10-05 修复：原实现只看 mjs[0]，导致新增的第二个 config
    #   （cooparticlesapi.sodium.mixins.json）整组 mixin 不被校验，
    #   脚本会给出「通过」的假绿。现在逐个 config 跑。
    at_final_fields = set()   # 目标 final 字段名(srg)集合
    total_problems = []
    total_files = 0
    for cfg_path in mjs:
      mj = json.load(open(cfg_path, encoding="utf-8"))
      pkg = mj["package"]
      listed = list(mj.get("mixins", [])) + list(mj.get("client", [])) + list(mj.get("server", []))
      require = mj.get("injectors", {}).get("defaultRequire", 1)

      refmap = {}
      if libs and mj.get("refmap"):
          try:
              z = zipfile.ZipFile(libs[0])
              if mj["refmap"] in z.namelist():
                  refmap = json.loads(z.read(mj["refmap"])).get("mappings", {})
          except Exception as e:
              print("!! 读 refmap 失败:", e)

      srcs = []
      for ext in ("java", "kt"):
          srcs += glob.glob(os.path.join(proj, "src/main/**/*." + ext), recursive=True)

      # 采集本 config 涉及的目标类的 final 字段
      _targets = set()
      for _e in listed:
          _f = None
          for _s in srcs:
              _sp = _s.replace("\\", "/")
              if "/mixin/" in _sp and (_sp.endswith("/" + _e.replace(".", "/") + ".java")
                                       or _sp.endswith("/" + _e.replace(".", "/") + ".kt")):
                  _f = _s; break
          if _f:
              _txt = strip_comments(open(_f, encoding="utf-8", errors="replace").read())
              _imps = {i.rsplit(".", 1)[-1]: i
                       for i in re.findall(r'^\s*import\s+([\w.]+)\s*;', _txt, re.M)}
              for _b in re.findall(r'@Mixin\s*\(([\s\S]{0,300}?)\)', _txt):
                  for _t in re.findall(r'targets\s*=\s*"([^"]+)"', _b):
                      _targets.add(_t.replace("/", "."))
                  for _v in re.findall(r'(?:value\s*=\s*)?([\w.]+)\.class', _b):
                      # 简名要按 import 补全成全限定名
                      _targets.add(_imps.get(_v, _v))
      _mcjar = find_mc_srg_jar(proj)
      if _mcjar and _targets:
          at_final_fields |= collect_final_fields(_mcjar, _targets)

      problems, nfiles = [], 0
      for entry in listed:
          rel = entry.replace(".", "/")
          f = None
          for s in srcs:
              sp = s.replace("\\", "/")
              if "/mixin/" in sp and (sp.endswith("/" + rel + ".java") or sp.endswith("/" + rel + ".kt")):
                  f = s; break
          if f is None:
              print("  ? 找不到源码 %s" % entry); continue
          nfiles += 1
          txt = strip_comments(open(f, encoding="utf-8", errors="replace").read())
          imports = {i.rsplit(".", 1)[-1]: i for i in re.findall(r'^\s*import\s+([\w.]+)\s*;', txt, re.M)}

          # ★ 2026-10-12 修复(第 5 个假绿):
          #   `method = COMPILE_DESC` 这种「常量引用」以前会被正则漏掉 →
          #   下面 `if not mmeth: continue` 直接把整个 @Inject 跳过 → 预检报绿,
          #   真机才炸 `Invalid descriptor ... CallbackInfoReturnable is required`。
          #   这里先把源码里所有 `static final String X = "..." + "...";` 的拼接常量求值。
          #   (只处理纯字符串字面量拼接;含变量的保守放弃 —— 仍会走 old_path)
          strconsts = {}
          for cm in re.finditer(
                  r'(?:static\s+)?(?:final\s+)?String\s+([A-Za-z_$][\w$]*)\s*=\s*'
                  r'((?:\s*"(?:[^"\\]|\\.)*"\s*\+?)+)\s*;', txt):
              name = cm.group(1)
              parts = re.findall(r'"((?:[^"\\]|\\.)*)"', cm.group(2))
              if parts:
                  strconsts[name] = "".join(parts)

          # 目标类
          targets = set()
          for body in re.findall(r'@Mixin\s*\(([\s\S]{0,300}?)\)', txt):
              for t in re.findall(r'targets\s*=\s*"([^"]+)"', body):
                  targets.add(t)
              for v in re.findall(r'(?:value\s*=\s*)?([\w.]+)\.class', body):
                  targets.add(imports.get(v, v))
          game = sorted(t for t in targets if t.replace(".", "/") in ts)
          if not game:
              continue
          covered = set(refmap.get(pkg.replace(".", "/") + "/" + rel, {}))

          # 所有方法声明（名字 + 参数 + 位置）
          methods = []
          for mm in re.finditer(r'(?<![\w.$])([A-Za-z_$][\w$]*)\s*\(', txt):
              op = mm.end() - 1
              cl = bal(txt, op)
              if cl < 0: continue
              tail = txt[cl + 1:cl + 2]
              rest = txt[cl + 1:cl + 120]
              if not re.match(r'\s*(throws|:|\{|\s*\{)', rest):
                  continue
              methods.append((mm.start(), mm.group(1), txt[op + 1:cl]))

          # ★ 2026-10-21 新增:@Shadow 字段修饰符校验(精确版)
          #   只有当目标字段在 tsrg/AT 里确实是 final 时,才要求 mixin 侧
          #   写 @Final —— 否则 attachFields 报 "was not located" 启动崩溃。
          #   (目标不是 final 的话,@Shadow 不带 @Final 是正确的,不能误报。)
          for sm in re.finditer(
                  r'@Shadow\s*(?:@\w+(?:\([^)]*\))?\s*)*'
                  r'(?:(?:private|protected|public)\s+)?(?:static\s+)?'
                  r'(final\s+)?[\w.$<>\[\], ]+?\s+(\w+)\s*(?:=[^;]*)?;', txt):
              has_java_final = bool(sm.group(1))
              fname = sm.group(2)
              fpos = sm.start()
              # @Final 可能写在 @Shadow 之前、之后(不同行),取前后各 200 字符判断
              ctx = txt[max(0, fpos - 200):fpos + 200]
              has_mixin_final = "@Final" in ctx
              if has_java_final or has_mixin_final:
                  continue
              # 查目标类里这个字段是不是 final
              for tc in game:
                  d = ts[tc.replace(".", "/")]
                  srg_name = d["f"].get(fname)
                  if not srg_name:
                      continue
                  # 用 javap 结果(若脚本采集过)或直接按经验:静态 Map/Set 常量多为 final
                  # javap 收集到的是 **srg 名**(jar 是 srg 的),直接用 srg_name 比对
                  is_final_target = srg_name in at_final_fields
                  if is_final_target:
                      problems.append((entry, "Shadow", fname,
                                       "@Shadow 字段缺 @Final(目标字段是 final)",
                                       "无 @Final", "加 @Final 或改用 @Accessor"))
                  break

          for am in re.finditer(r'@(%s)\b' % "|".join(ANNS), txt):
              kind = am.group(1)
              pos = am.end()
              argstr = ""
              j = pos
              while j < len(txt) and txt[j] in " \t\r\n": j += 1
              if j < len(txt) and txt[j] == '(':
                  cl = bal(txt, j)
                  if cl < 0: continue
                  argstr = txt[j + 1:cl]
                  pos = cl
              hist = txt[max(0, am.start() - 200):pos + 120]
              if "remap = false" in argstr or "remap=false" in argstr:
                  continue
              toplevel = split_top_level(argstr)
              meth_arg = None
              for a in toplevel:
                  if re.match(r'^method\s*=', a):
                      meth_arg = a; break
              if meth_arg is None:                      # 有些注解只写 value =
                  for a in toplevel:
                      if re.match(r'^value\s*=', a):
                          meth_arg = a; break
              if meth_arg is None:
                  continue
              val = meth_arg.split("=", 1)[1].strip()
              if val.startswith("{"):                   # 数组:method = {"a", "b"}
                  val = val[1:].split(",", 1)[0].strip()
              lit = re.match(r'"((?:[^"\\]|\\.)*)"', val)
              if lit:
                  tgtstr = lit.group(1)
              elif val in strconsts:
                  # ★ 常量引用:@Inject(method = SOME_CONST)
                  tgtstr = strconsts[val]
                  if "(" not in tgtstr:
                      print("  ? [注意] %s 的常量 %s 无描述符,跳过签名校验" % (entry, val))
                      continue
              else:
                  # 既不是字面量也解析不到常量 → 明确提示,别再假装通过
                  print("  ? [跳过] %s: %s 的 method= 是变量/表达式(%s),无法静态校验"
                        % (entry, kind, val[:40]))
                  continue
              tname = tgtstr.split("(")[0]
              tdesc = "(" + tgtstr.split("(", 1)[1] if "(" in tgtstr else None

              h = next(((s, n, p) for s, n, p in methods if s > pos), None)
              if h is None:
                  problems.append((entry, kind, tname, "找不到 handler 方法", "", ""))
                  continue
              hparams = []
              hcb = None          # ★ 2026-10-12:记录 callback 参数类型
              for p in split_params(h[2]):
                  mcb = re.match(r'^\s*(CallbackInfoReturnable|CallbackInfo)\b', p)
                  if mcb:
                      hcb = mcb.group(1)
                      continue
                  # ★ 2026-10-12:MixinExtras 的「糖参数」不是真实方法参数,
                  #   必须剔除,否则签名比对必然失败(误报)。
                  #   @Local / @Share / @Operation / @Slice / @Definition ...
                  if re.match(r'^\s*@', p):
                      continue
                  toks = p.rsplit(None, 1)
                  if len(toks) < 2:
                      continue
                  hparams.append(desc_of(toks[0], imports, pkg))
              if h[2].strip().startswith(")"):     # 空参数列表
                  hparams = []

              # @At(target="Lcls;name(desc)") 里引用的成员也必须存在
              for tg in re.findall(r'@At\s*\(([\s\S]{0,300}?)\)', argstr):
                  for md in re.finditer(r'target\s*=\s*"L([^;]+);([\w$<>]+)(\([^"]*\))?', tg):
                      acls = md.group(1)
                      if acls not in ts:
                          continue
                      aname = md.group(2)
                      adesc = md.group(3)
                      ok = aname in ts[acls]["any"]
                      if ok and adesc:
                          ok = any(d2 == adesc for d2, _ in ts[acls]["m"].get(aname, []))
                      if not ok:
                          problems.append((entry, "@At target", aname,
                                           "调用的目标成员在 1.20.1 不存在/描述符不符",
                                           adesc or "(字段/无描述符)", acls.split("/")[-1]))
                          continue

              hit = None
              for t in game:
                  d = ts[t.replace(".", "/")]
                  if tname in d["any"]:
                      hit = d; break
              if hit is None:
                  if any(tname in v["any"] for v in ts.values()):
                      continue                      # 继承来的，跳过

                  # ★ 2026-10-05 修复（第 4 个假绿）：
                  #   `@Inject(shift = AFTER/`BEFORE`)` 这种**纯锚点注入**没有对应的
                  #   handler 方法体 —— 此时 method 字符串指的是「要挂在哪个调用点旁边」，
                  #   目标成员在 @At target 里已经查过了（上面的 @At target 分支）。
                  #   原脚本仍拿 tname 去查映射表，必然查不到 → 误报致命。
                  #
                  #   判定：若该注解块里存在 `shift = At.Shift.…`，说明是锚点式注入，
                  #   只要 @At target 解析通过就不再追究 tname。
                  if re.search(r'shift\s*=\s*At\.Shift\.', argstr):
                      continue

                  problems.append((entry, kind, tname,
                                   "目标成员在 1.20.1 不存在（改名/删除）", "", ""))
                  continue

              # ModifyArg / ModifyVariable / ModifyConstant 的参数表本就自由
              if kind in ("ModifyArg", "ModifyArgs", "ModifyVariable", "ModifyConstant"):
                  continue

              cands = hit["m"].get(tname, [])
              match = None

              if kind == "Redirect":
                  # Redirect 比的是 @At 里那次调用的描述符
                  tgt = re.search(r'@At\s*\(([\s\S]{0,300}?)\)', argstr)
                  atdesc = None
                  if tgt:
                      md = re.search(r'target\s*=\s*"L([^;]+);([\w$<>]+)(\([^"]*\))?', tgt.group(1))
                      if md and md.group(3):
                          atdesc = md.group(3)
                  if atdesc is None:
                      continue
                  pd = desc_params(atdesc)
                  if pd == hparams:
                      match = ("@At " + atdesc, "n/a")
              elif kind == "Inject":
                  # Inject 允许**省略尾部参数**
                  for d, srg in cands:
                      if tdesc and d != tdesc:
                          continue
                      pd = desc_params(d)
                      if len(hparams) > len(pd):
                          continue
                      if all(a == b for a, b in zip(pd, hparams)):
                          match = (d, srg); break
              else:
                  for d, srg in cands:
                      if tdesc and d != tdesc:
                          continue
                      pd = desc_params(d)
                      if pd == hparams:
                          match = (d, srg); break

              if match is None:
                  hdesc = "(" + "".join(hparams) + ")V"
                  problems.append((entry, kind, tname, "handler 签名与目标描述符不匹配", hdesc,
                                   " ; ".join("%s [%s]" % (d, s) for d, s in cands)))
                  continue
              d, srg = match

              # ★ 2026-10-12 新增(第 6 个假绿 —— 正是本次真机崩溃的原因):
              #   目标方法有返回值(描述符不以 V 结尾)时,handler 必须用
              #   CallbackInfoReturnable;用 CallbackInfo 会在 APPLY 阶段抛
              #   `InvalidInjectionException: ... CallbackInfoReturnable is required!`
              #   整个配置加载即崩。以前脚本把 callback 参数直接 continue 丢掉,
              #   等于完全不校验 → 这里补上。
              if kind == "Inject" and hcb is not None:
                  ret = (d.rsplit(")", 1) + [""])[1] if ")" in d else ""
                  returns_void = ret.startswith("V") or ret == ""
                  if returns_void and hcb == "CallbackInfoReturnable":
                      problems.append((entry, kind, tname,
                                       "目标返回 void,但用了 CallbackInfoReturnable",
                                       "应为 CallbackInfo", d))
                  elif (not returns_void) and hcb == "CallbackInfo":
                      problems.append((entry, kind, tname,
                                       "目标有返回值,但用了 CallbackInfo",
                                       "应为 CallbackInfoReturnable", d))

              if srg != tname and not any(k == tname or k.startswith(tname + "(") for k in covered):
                  problems.append((entry, kind, tname, "refmap 缺条目（生产环境会失败）",
                                   "official=%s → srg=%s" % (tname, srg), d))

      print("=" * 82)
      print("%-24s mixin %d 个   defaultRequire=%s   refmap %d 个类"
            % (os.path.basename(proj), nfiles, require, len(refmap)))
      print("=" * 82)
      # 去重（同一个 bug 可能被 @Inject 和 @At target 各报一次）
      uniq, seen = [], set()
      for pr in problems:
          k = (pr[0], pr[1], pr[2], pr[3])
          if k in seen:
              continue
          seen.add(k); uniq.append(pr)
      problems = uniq

      FATAL = ("目标成员在 1.20.1 不存在（改名/删除）",
               "handler 签名与目标描述符不匹配",
               "找不到 handler 方法",
               "调用的目标成员在 1.20.1 不存在/描述符不符",
               "目标有返回值,但用了 CallbackInfo",
               "目标返回 void,但用了 CallbackInfoReturnable")

      if not problems:
          print("  ✅ 通过：目标存在 / handler 签名 / refmap 覆盖")
      else:
          fatal = [p for p in problems if p[3] in FATAL]
          soft = [p for p in problems if p[3] not in FATAL]
          if fatal:
              print("  ⛔ 致命（require=1 → 加载即崩）共 %d 处" % len(fatal))
              for e, kind, n, why, got, want in fatal:
                  print("     %-44s %-12s %s" % (e, kind, n))
                  print("         %s" % why)
                  if got:  print("         handler : %s" % got)
                  if want: print("         目标    : %s" % want)
          if soft:
              print("  ⚠  需注意 共 %d 处" % len(soft))
              for e, kind, n, why, got, want in soft:
                  print("     %-44s %-12s %s | %s" % (e, kind, n, why))
          print("  共 %d 处问题（致命 %d）" % (len(problems), len(fatal)))

      # ★ 汇总到总计，继续跑下一个 config（不再提前 return）
      total_problems.extend(problems)
      total_files += nfiles

    print("=" * 82)
    fatal_all = [p for p in total_problems if p[3] in FATAL]
    if not total_problems:
        print("ALL MIXIN CHECKS PASSED  （%d 个 config / %d 个 mixin）"
              % (len(mjs), total_files))
        return 0
    print("共 %d 处问题（致命 %d），跨 %d 个 config"
          % (len(total_problems), len(fatal_all), len(mjs)))
    return 1

if __name__ == "__main__":
      sys.exit(main())
