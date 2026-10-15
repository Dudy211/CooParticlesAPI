#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""打包后必跑：校验 jar 的「四件套」+ 资源完整性。

踩过的坑（每一个都让游戏完全起不来）：
  1. 缺 MANIFEST 的 MixinConfigs   → 所有 mixin 静默不加载
  2. 缺 META-INF/mods.toml         → FML 不把这个 jar 当 mod
                                     （报 "xxx is not installed"）
  3. 缺 xxx.refmap.json            → 生产环境 mixin 全部失效
                                     （报 "@Shadow field xxx was not located ... No refMap loaded"）
  4. 缺 xxx.mixins.json            → mixin 无配置
  5. **资源缺失**                   → 物品渲染成黑紫块（缺 models/item/*.json）、
                                     名字显示成原始 key（缺 lang/*.json）、
                                     方块不显示（缺 blockstates/*.json）

第 5 条是 2026-09-22 踩的：上游把 26 个模型放在 `neoforge/src/generated/`（datagen 产物），
移植时只搬了 `common/` 的手写模型 → 大量物品黑紫块。

重打 jar 之后一定要跑这个。

★ 2026-10-05 修复：JARS 曾经是硬编码的**无后缀旧包名**清单 —— 结果
  `-FIX9-viewfinal` / `-FIX-damagetype` 这些真正在交付的包**根本没被检查**，
  脚本给了"ALL CHECKS PASSED"的假绿。现改为**自动发现**：
  扫目录里所有 jar → 读 META-INF/mods.toml → 取 modId → 推导 mixins/refmap/入口类。
  包名怎么变都能检到；可以通过 --only <modId> 只检某一个。

  用法:
    python3 verify_jar.py <目录>                  # 扫目录下所有 mod jar
    python3 verify_jar.py <目录> --only cp1201    # 只检指定 modId
    python3 verify_jar.py <jar 文件路径>           # 单个 jar
"""
import zipfile, json, sys, os, glob, re

# 已知 mod 的补充信息：有些 jar 不能被自动识别（比如入口类路径不规律），
# 或需要额外的资源白名单。能自动发现的就不要再往这里加。
MOD_HINTS = {
    "usefulmagic": {
        "entry": "cn/coostack/usefulmagic/UsefulMagicForge.class",
        "loader": "kotlinforforge",
    },
    "cooparticlesapi": {
        "entry": "cn/coostack/api/../CooParticlesAPIForge.class",
        "loader": "kotlinforforge",
    },
    "sevencurses": {
        "entry": "com/yuneko/sevencurses/SevenCursesMod.class",
        "loader": "javafml",
    },
}

# 上游就没有对应物品的贴图（历史遗留），不算缺失
UNUSED_TEXTURES = {
    "usefulmagic": {"lighting_cloud_wand"},   # 上游无此物品，贴图是残留
}


def discover_jars(base):
    """返回 [(path, mod_id, mixins_json, refmap, entry, loader), ...]"""
    out = []
    if os.path.isfile(base):
        paths = [base]
    else:
        paths = sorted(glob.glob(os.path.join(base, "*.jar")))

    for p in paths:
        try:
            z = zipfile.ZipFile(p)
        except Exception:
            continue
        names = z.namelist()
        if "META-INF/mods.toml" not in names:
            continue          # 不是 mod（可能是依赖库），跳过

        # ★ 只检「本项目产物」——排除第三方依赖库（geckolib / mixinextras /
        #   kotlinforforge 等）。判定依据：本项目产物的 mods.toml 里没有
        #   第三方痕迹，且 jar 名以 -forge- 开头。简单可靠的办法是白名单：
        #   只检 MOD_HINTS 里登记过的，或 jar 名匹配 OUR_JAR_RE 的。
        base = os.path.basename(p)
        if SKIP_JAR_RE.search(base):
            continue          # sources/javadoc 包不算交付产物
        if not OUR_JAR_RE.search(base):
            continue

        mt = z.read("META-INF/mods.toml").decode("utf-8", "replace")
        m = re.search(r'modId\s*=\s*"([^"]+)"', mt)
        if not m:
            continue
        mod_id = m.group(1)
        mixins_json = "%s.mixins.json" % mod_id
        refmap = "%s.refmap.json" % mod_id

        # 入口类：从 mods.toml 找不到，就用提示表；再不行就扫 class 名含 Mod/Forge 的
        hint = MOD_HINTS.get(mod_id, {})
        entry = hint.get("entry", "")
        if not entry or entry not in names:
            # 兜底：找顶层包下第一个 *Mod.class / *Forge.class
            cands = [n for n in names
                     if n.endswith(".class") and re.search(r"(Mod|Forge|Main)\.class$", n)
                     and "$" not in n]
            entry = cands[0] if cands else ""

        loader = hint.get("loader")
        if loader is None:
            lm = re.search(r'modLoader\s*=\s*"([^"]+)"', mt)
            loader = lm.group(1) if lm else ""

        out.append((p, mod_id, mixins_json, refmap, entry, loader))

    out.sort(key=lambda t: os.path.basename(t[0]))
    return out


# 本项目的产物 jar：必须同时满足「含项目名」+「-forge-」。
# 用来排除 geckolib / mixinextras / kotlinforforge 这些依赖库。
OUR_JAR_RE = re.compile(r"^(usefulmagic|cooparticlesapi|sevencurses)-forge-", re.I)

# 但 **-sources.jar 要排除**：它是源码包，故意不含 refmap / mixins。
SKIP_JAR_RE = re.compile(r"-(sources|javadoc)\.jar$", re.I)


BASE = sys.argv[1] if len(sys.argv) > 1 else "."
ONLY = None
if "--only" in sys.argv:
    i = sys.argv.index("--only")
    ONLY = sys.argv[i + 1] if i + 1 < len(sys.argv) else None

fails = 0


def check_resources(z, mod_id):
    """资源完整性：贴图有但模型缺 → 黑紫块；没有 lang → 显示原始 key。"""
    global fails
    prefix = "assets/%s/" % mod_id
    names = set(n for n in z.namelist() if n.startswith(prefix))
    if not names:
        return

    models = set(n[len(prefix + "models/item/"):-5] for n in names
                 if n.startswith(prefix + "models/item/") and n.endswith(".json"))
    tex = set(n[len(prefix + "textures/item/"):-4] for n in names
              if n.startswith(prefix + "textures/item/") and n.endswith(".png"))
    bstates = set(n[len(prefix + "blockstates/"):-5] for n in names
                  if n.startswith(prefix + "blockstates/") and n.endswith(".json"))
    langs = sorted(n for n in names if "/lang/" in n and n.endswith(".json"))

    print("  [..]   资源：item 模型 %d / item 贴图 %d / blockstate %d / lang %d"
          % (len(models), len(tex), len(bstates), len(langs)))

    # 一张贴图算「被使用」的条件（任一满足即可）：
    #   a) 有同名 item 模型；b) 被某个模型（含父模型链/layer0/layer1…）引用
    used_tex = set()
    for n in names:
        if not (n.startswith(prefix + "models/") and n.endswith(".json")):
            continue
        try:
            body = json.loads(z.read(n))
        except Exception:
            continue
        stack = [body]
        while stack:
            obj = stack.pop()
            if isinstance(obj, dict):
                for k, v in obj.items():
                    if k in ("parent", "model"):
                        continue
                    stack.append(v)
            elif isinstance(obj, list):
                stack.extend(obj)
            elif isinstance(obj, str) and obj.startswith(mod_id + ":item/"):
                used_tex.add(obj[len(mod_id) + len(":item/"):])
    allowed = UNUSED_TEXTURES.get(mod_id, set())
    orphan = sorted(t for t in tex
                    if t not in models and t not in used_tex and t not in allowed)
    if orphan:
        print("  [FAIL] 有贴图但缺 item 模型（会渲染成黑紫块）: %d 个" % len(orphan))
        for o in orphan[:12]:
            print("           items/%s" % o)
        fails += 1

    if not langs:
        print("  [FAIL] 没有 lang 文件（物品/方块名会显示成原始 key）")
        fails += 1
    else:
        # 语言完整性：有 item 模型就该有对应翻译键，否则游戏里显示成原始 key
        keys = set()
        for ln in langs:
            try:
                keys |= set(json.loads(z.read(ln)))
            except Exception:
                print("  [FAIL] lang 解析失败: " + ln)
                fails += 1
        # 排除「子模型」：被别的模型引用为 parent / overrides.model 的，
        # 以及 _数字 结尾的 provider 变体（如 large_mana_revive_1）。
        import re as _re
        sub = set()
        for n in names:
            if not (n.startswith(prefix + "models/") and n.endswith(".json")):
                continue
            try:
                body = json.loads(z.read(n))
            except Exception:
                continue
            refs = []
            if isinstance(body.get("parent"), str):
                refs.append(body["parent"])
            for ov in body.get("overrides", []) or []:
                if isinstance(ov, dict) and isinstance(ov.get("model"), str):
                    refs.append(ov["model"])
            for r in refs:
                if ":" in r:
                    ns, path = r.split(":", 1)
                else:
                    ns, path = "minecraft", r
                if ns == mod_id and path.startswith("item/"):
                    sub.add(path[len("item/"):])
        real = [m for m in models if m not in sub and not _re.search(r"_\d+$", m)]
        no_lang = []
        for m in sorted(real):
            # 方块物品用 block.<mod>.<x>，普通物品用 item.<mod>.<x> —— 两者任一即可
            if ("item.%s.%s" % (mod_id, m)) in keys:
                continue
            if ("block.%s.%s" % (mod_id, m)) in keys:
                continue
            no_lang.append(m)
        if no_lang:
            print("  [FAIL] 有模型但没有翻译（会显示原始 key）: %d 个" % len(no_lang))
            for m in no_lang[:15]:
                print("           %s  (item/block.%s.%s)" % (m, mod_id, m))
            fails += 1


for jar_path, mod_id, mixins_json, refmap, entry, loader in discover_jars(BASE):
    jar = os.path.basename(jar_path)
    p = jar_path
    print("=" * 60)
    print("%s   [modId=%s]" % (jar, mod_id))
    if ONLY and mod_id != ONLY:
        print("  [--]   跳过（--only %s）" % ONLY)
        continue
    z = zipfile.ZipFile(p)
    names = z.namelist()

    # ── 资源完整性 ──
    check_resources(z, mod_id)

    def chk(cond, label, extra=""):
        global fails
        if cond:
            print("  [OK]   %s %s" % (label, extra))
        else:
            print("  [FAIL] %s %s" % (label, extra))
            fails += 1

    chk(z.testzip() is None, "zip 完整性")
    chk("META-INF/mods.toml" in names, "META-INF/mods.toml",
        "@%s" % names.index("META-INF/mods.toml") if "META-INF/mods.toml" in names else "")

    if "META-INF/mods.toml" in names:
        mt = z.read("META-INF/mods.toml").decode()
        chk('modId="%s"' % mod_id in mt, "mods.toml 里的 modId", mod_id)
        if loader:
            chk('modLoader="%s"' % loader in mt, "modLoader", loader)

    chk("META-INF/MANIFEST.MF" in names, "META-INF/MANIFEST.MF")
    if "META-INF/MANIFEST.MF" in names:
        mf = z.read("META-INF/MANIFEST.MF").decode()
        chk("MixinConfigs" in mf, "MANIFEST MixinConfigs",
            "(在 MANIFEST.MF 里声明同样有效)")

    chk(mixins_json in names, mixins_json)
    if mixins_json in names:
        mj = json.loads(z.read(mixins_json))
        chk(mj.get("refmap") == refmap, "mixins.json refmap", str(mj.get("refmap")))
        chk(bool(mj.get("minVersion")), "mixins.json minVersion", str(mj.get("minVersion")))

    chk(refmap in names, refmap)
    if refmap in names:
        rm = json.loads(z.read(refmap)).get("mappings", {})
        total = sum(len(v) for v in rm.values())
        chk(total > 0, "refmap 映射数", "%d mixin / %d 条" % (len(rm), total))
    else:
        # ★ 2026-09-22：jar 里没有 refmap ≈ 这个 jar 不是 `gradlew build` 打的
        #   （processResources 才会把 refmap 打进 jar），极可能整个没 reobf。
        print("  [HINT] jar 内没有任何 refmap 文件 → 这个 jar 很可能**没走 reobfJar**，")
        print("         即用 `gradlew jar` 手工打的包。症状：客户端一进就崩")
        print("         `@Shadow field xxx was not located in the target class`。")
        print("         正确姿势：`bash port/build_release.sh`（不要手工 jar）。")
        print("         并配合 `python3 port/verify_reobf.py <dir>` 交叉确认。")

    if entry:
        chk(entry in names, "入口类", entry.split("/")[-1])
    else:
        print("  [WARN] 未能自动推断入口类（模组类名不含 Mod/Forge/Main？）")
    print()

print("=" * 60)
if not ONLY:
    found = [t[1] for t in discover_jars(BASE)]
    print("已检查 modId: %s" % (", ".join(found) if found else "(无)"))
print("FAILED" if fails else "ALL CHECKS PASSED")
sys.exit(1 if fails else 0)
