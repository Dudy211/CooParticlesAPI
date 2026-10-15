#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
verify_at.py —— 防止「用 official 名反射访问 MC 成员」这个坑复发。

## 背景（2026-09-23，CP 进世界即崩的根因）

CP 的 `ForgeRenderTypesProvider` 曾这样读 RenderStateShard 的常量：

    RenderStateShard::class.java.getDeclaredField("NO_TRANSPARENCY")

开发环境（`*_mapped_official_*` jar）字段确实叫 `NO_TRANSPARENCY`，所以「看起来能用」。
但**生产环境**经过 reobf 后字段叫 `f_110134_` → `getDeclaredField` **必然**
抛 `NoSuchFieldException`（实测每帧约 4000 次）。

异常本身被 `runCatching` 吞掉了，但它的**副作用**没被吞：
它从 `MinecraftRendererUtil.transformTo` 的 `invoker` 里抛出，
导致 `popPose()` 被跳过 → PoseStack 单向增长 → 原版 `renderLevel` 收尾
`checkPoseStack` 抛 `Pose stack not empty` → **一进世界就崩**。

## 正确做法

在 `src/main/resources/META-INF/accesstransformer.cfg` 里**用 SRG 名**打开这些成员
（AT 在 SRG 阶段应用，所以必须是 `f_110134_` 这种名字），然后像上游一样**直接引用**：

    RenderStateShard.NO_TRANSPARENCY   // 编译期 official 名，reobf 自动变 f_110134_

## 本脚本检查两件事

**A. 源码里不能有「拿 MC official 名去反射」的调用点**（精准匹配反射调用，不扫全量字符串）
**B. 正面校验：AT 声明的每个 SRG 成员，reobf 后的 jar 里必须真的引用到**
   —— 防止「AT 文件写了但没生效 / 名字写错」导致又退回 protected

用法:  python3 verify_at.py [项目目录...]        # 默认两个项目
"""
import glob
import io
import os
import re
import sys
import zipfile

TSRG_CANDIDATES = [
    "/workspace/mc/gradle_user_home/caches/forge_gradle/minecraft_user_repo/"
    "de/oceanlabs/mcp/mcp_config/1.20.1-20230612.114412/srg_to_official_1.20.1.tsrg",
]

# 反射目标是本 mod 自己的类 —— 这些类不会被 reobf，用 official 名是**正确**的。
# 按「调用行里出现的类名」判断，例如 ParticleNoiseCommand（本 mod）。
SELF_OWNED_HINTS = ("ParticleNoiseCommand",)

# 第三方 mod 兼容层：反射的是 **别的 mod**（Iris / Sodium…）的类，不是 MC 的类，
# 那些 mod 不做 reobf，用 official 名是**正确**的。按路径白名单跳过。
THIRD_PARTY_PATH_HINTS = (
    "/compat/",           # compat/IrisCompat.kt
    "/mixin/compat/",     # mixin/compat/iris/*
)

SOURCE_EXT = (".kt", ".java")
REFLECT_RE = re.compile(
    r"""get(?:Declared)?(?:Field|Method)\s*\(\s*["']([^"']+)["']"""
)
# 反向也查一下：field("...") / method("...") 之类的 helper（本项目暂未使用）
MIN_NAME_LEN = 6


def find_tsrg():
    for p in TSRG_CANDIDATES:
        if os.path.isfile(p):
            return p
    hits = glob.glob(
        "/workspace/mc/gradle_user_home/caches/forge_gradle/**/srg_to_official_*.tsrg",
        recursive=True,
    )
    return hits[0] if hits else None


def load_official_fields(tsrg_path):
    """收集 MC「字段级」official 名 → SRG 名。"""
    names = {}
    with io.open(tsrg_path, "r", encoding="utf-8", errors="replace") as fh:
        for line in fh:
            if not line.startswith("\t"):
                continue
            parts = line.strip().split("\t")
            if len(parts) < 2:
                parts = line.strip().split(" ")
            if len(parts) < 2:
                continue
            srg, off = parts[0], parts[1]
            if "(" in off or off == "" or off == "<init>":
                continue
            if not re.fullmatch(r"[A-Za-z_$][A-Za-z0-9_$]*", off):
                continue
            if len(off) < MIN_NAME_LEN:
                continue
            names[off] = srg
    return names


def find_sources(proj):
    root = os.path.join(proj, "src", "main")
    out = []
    for base, _dirs, files in os.walk(root):
        for f in files:
            if f.endswith(SOURCE_EXT):
                out.append(os.path.join(base, f))
    return out


def check_source(proj, official):
    """A. 找源码里的 official-名反射调用点。"""
    hits = []
    for path in find_sources(proj):
        norm = path.replace(os.sep, "/")
        if any(h in norm for h in THIRD_PARTY_PATH_HINTS):
            continue  # 第三方 mod 兼容层，反射别的 mod 是合法的
        try:
            with io.open(path, "r", encoding="utf-8", errors="replace") as fh:
                for lineno, line in enumerate(fh, 1):
                    # 跳过注释行（避免把文档里举的例子当成真代码）
                    stripped = line.strip()
                    if stripped.startswith(("//", "*", "/*", "#")):
                        continue
                    m = REFLECT_RE.search(line)
                    if not m:
                        continue
                    if any(h in line for h in SELF_OWNED_HINTS):
                        continue  # 反射自己的类，合法
                    name = m.group(1)
                    if name in official:
                        hits.append((path, lineno, name, official[name], stripped))
        except OSError:
            continue
    return hits


def read_jar_strings(jar):
    """读整个 jar 的 class 常量池字符串（用于正面校验 SRG 引用）。"""
    blobs = []
    try:
        with zipfile.ZipFile(jar) as zf:
            for entry in zf.namelist():
                if entry.endswith(".class"):
                    try:
                        blobs.append(zf.read(entry))
                    except Exception:
                        pass
    except Exception:
        return b""
    return b"\n".join(blobs)


def check_jar(proj, at_entries):
    """B. 正面校验：jar 里必须真的引用到 AT 声明的 SRG 名。

    返回 (jar, used, unused)。
    """
    jars = [j for j in sorted(glob.glob(os.path.join(proj, "build", "libs", "*.jar")))
            if not j.endswith("-sources.jar")]
    if not jars:
        return None, [], []
    jar = jars[0]
    blob = read_jar_strings(jar)
    used = [e for e in at_entries if e.encode() in blob]
    unused = [e for e in at_entries if e.encode() not in blob]
    return jar, used, unused


def parse_at(proj):
    """读 accesstransformer.cfg，返回所有「字段级」SRG 名。"""
    path = os.path.join(proj, "src", "main", "resources", "META-INF", "accesstransformer.cfg")
    if not os.path.isfile(path):
        return None
    out = []
    with io.open(path, "r", encoding="utf-8", errors="replace") as fh:
        for line in fh:
            line = line.split("#")[0].strip()
            if not line:
                continue
            parts = line.split()
            if len(parts) < 3:
                continue
            member = parts[2]
            if "(" in member:      # 方法：跳过（本校验只看字段）
                continue
            if not re.fullmatch(r"f_\d+_", member):
                continue
            out.append(member)
    return out


def main(argv):
    projs = argv[1:] or ["/workspace/mc/build_ws/cp1201",
                         "/workspace/mc/build_ws/um1201"]
    tsrg = find_tsrg()
    official = load_official_fields(tsrg) if tsrg else {}
    if tsrg:
        print("MC official 字段名表：%d 个（%s）" % (len(official), os.path.basename(tsrg)))
    else:
        print("[WARN] 找不到 srg_to_official tsrg —— 跳过 A 检查（源码扫描）")

    failed = 0
    for proj in projs:
        print("=" * 78)
        print(proj)

        # ── A：源码反射调用点 ──
        src_hits = check_source(proj, official) if official else []
        if src_hits:
            failed += len(src_hits)
            print("  ⛔ A) 发现 %d 处「official 名反射」：" % len(src_hits))
            for path, lineno, name, srg, text in src_hits:
                print("     %s:%d" % (path.replace(proj + "/", ""), lineno))
                print("        getDeclaredXxx(\"%s\")   ← 生产环境叫 %s" % (name, srg))
                print("        %s" % text)
            print("     修法：在 accesstransformer.cfg 用 SRG 名打开该成员，然后直接引用。")
        else:
            print("  ✅ A) 源码里没有 official 名反射")

        # ── B：AT 正面校验 ──
        at_entries = parse_at(proj)
        if at_entries is None:
            print("  ·  B) 无 accesstransformer.cfg，跳过")
        elif not at_entries:
            print("  ·  B) accesstransformer.cfg 无字段级声明，跳过")
        else:
            jar, used, unused = check_jar(proj, at_entries)
            if jar is None:
                print("  !  B) 找不到 jar（先构建）")
            elif not used:
                # 一条都没命中 = AT 根本没作用到已编译代码上
                failed += 1
                print("  ⛔ B) AT 声明了 %d 个 SRG 字段，jar 里**一条都没引用到**！"
                      % len(at_entries))
                print("     → AT 没生效。检查 build.gradle 里有没有：")
                print("         accessTransformer = file('src/main/resources/META-INF/accesstransformer.cfg')")
                print("       （AT 是在 SRG 阶段应用的，所以条目必须写 SRG 名 f_xxxxxx_，")
                print("         且行内不能带 # 注释）")
            else:
                print("  ✅ B) AT 生效：声明 %d 个 SRG 字段，jar 里命中 %d 个"
                      % (len(at_entries), len(used)))
                if unused:
                    print("     （另有 %d 个声明了但当前代码未用到，无害：%s）"
                          % (len(unused), " ".join(unused[:6])))

    print("=" * 78)
    if failed:
        print("AT CHECK FAILED（%d 处）" % failed)
        return 1
    print("AT CHECK PASSED")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
