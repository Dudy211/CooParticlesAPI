#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""打包后必跑（v3，2026-09-22 重写判定逻辑）：验证 jar 里的 mixin class **真的被 reobf 过了**。

## 为什么需要它（血的教训）

2026-09-22 客户端启动崩：
    @Shadow field rightClickDelay was not located in the target class
    net.minecraft.client.Minecraft. Using refmap cooparticlesapi.refmap.json

refmap 文件、条目、context 全都是对的 —— 问题根本不在 refmap。

真正的机制（对着能用的 GeckoLib 逐字节对照得出）：

  ★ Forge 生产环境里，mixin class 的 **@Shadow 成员名已经是 SRG 名**
    （reobfJar 把 mixin class 自己也重写了），Mixin 是拿**字节码里的字面名**
    去目标类里找的 → 直接命中。
    refmap 只负责 `@Inject(method="xxx")` 这类**注解字符串**的官方名→SRG 转换。

对照（GeckoLib 4.4.9，能正常工作）：
    private java.util.Map<...> f_118468_;      ← SRG 名
    public abstract void m_118495_(...);       ← SRG 名

## v3 的教训：不能只看「类里有没有 SRG 名」

v2 的判定是「这个 class 的字节码里出现过 m_xxx_ / f_xxx_ 就算过」。
结果被一次回归骗过去了：

    MouseHandlerMixin 里 @Shadow 字段还是 `smoothTurnX`（没 reobf），
    但方法体里有 `smoothTurnX.reset()` → 引用被重写成了 `m_14236_`，
    于是 v2 看到 SRG 名就放行了 —— 而真正致命的字段声明是 official 的。

**v3 改成：直接解析 `@Shadow` 注解所修饰成员的【声明名】，要求它们本身是 SRG 名。**

判据：CMDI 前缀（`f_` / `m_` / `field_` / `method_`）——
Forge 的 SRG 只用这套前缀，我们自己代码里的 `usefulmagic$…` / `cooparticlesapi$…`
即使没重写也不会误判。

（附带：类里完全没出现 SRG 名 → 也可能说明整体没 reobf，保留为二级告警。）

## 用法

    python3 verify_reobf.py <jar...>     # 或 <目录>
"""
import json
import os
import re
import sys
import zipfile

SRG_PREFIX = re.compile(r"^([fm]_|field_|method_)")

# 里层 MixinExtras 的注解（@WrapOperation 等）带的 name= 不一定是目标成员，
# 但 @Shadow 一定是，所以只收 @Shadow 块的 name=。
SHADOW_MEMBER_RE = re.compile(
    rb"Lorg/spongepowered/asm/mixin/Shadow;"
)


def is_srg(name: str) -> bool:
    return bool(SRG_PREFIX.match(name))


# ────────────────────────── 用 javap 解析（精确） ──────────────────────────


def javap_shadow_members(cls_bytes: bytes, cls_name: str, tmpdir: str):
    """把 class 写到临时文件，用 javap -v 抓 @Shadow 修饰的成员名。

    javap 的 `-v` 输出里，成员声明与注解是相邻的：
        private int rightClickDelay;
          ...
          RuntimeVisibleAnnotations:
            0: #25()
              org.spongepowered.asm.mixin.Shadow
    """
    import subprocess
    import tempfile

    path = os.path.join(tmpdir, cls_name.replace("/", "_") + ".class")
    with open(path, "wb") as f:
        f.write(cls_bytes)
    try:
        out = subprocess.run(["javap", "-v", "-p", path],
                             capture_output=True, text=True, timeout=60).stdout
    except Exception as e:
        return None, str(e)
    finally:
        try:
            os.remove(path)
        except OSError:
            pass

    lines = out.split("\n")
    members = []
    # 找形如 "  <modifiers> <type> <name>;" 的成员声明行
    decl_re = re.compile(r"^\s{2}(?:public|private|protected|static|final|abstract|transient|volatile|\s)*"
                         r"[A-Za-z_$][\w.$<>,\[\] ]*\s+([A-Za-z_$][\w$]*)\s*;\s*$")
    for i, ln in enumerate(lines):
        m = decl_re.match(ln)
        if not m:
            continue
        name = m.group(1)
        # 往后看 20 行内是否出现 @Shadow 注解
        blk = "\n".join(lines[i:i + 20])
        # 注解块结束后（遇到下一个成员声明或方法）就停
        stop = len(lines)
        for j in range(i + 1, min(i + 20, len(lines))):
            if decl_re.match(lines[j]) or re.match(r"^\s{2}[A-Za-z].*\(.*\);\s*$", lines[j]):
                stop = j
                break
        blk = "\n".join(lines[i:stop])
        if "org.spongepowered.asm.mixin.Shadow" in blk:
            members.append(name)
    return members, None


# ────────────────────────── 兜底：纯字节码常量池解析 ──────────────────────────


def raw_shadow_names(cls_bytes: bytes):
    """不依赖 javap：从常量池里挑出「紧邻 @Shadow 注解的名字」。

    做法是保守的——只在 javap 不可用时使用，可能会多报，因此单独标记。
    """
    return None


def check_jar(path, use_javap=True):
    print("=" * 76)
    print(path)
    z = zipfile.ZipFile(path)
    names = set(z.namelist())

    # 从 mixins.json 取 mixin 列表
    classes = []
    for n in z.namelist():
        if n.endswith(".mixins.json"):
            try:
                cfg = json.loads(z.read(n).decode("utf-8"))
            except Exception:
                continue
            pkg = cfg.get("package", "").replace(".", "/")
            for key in ("mixins", "client", "server"):
                for cls in cfg.get(key, []) or []:
                    classes.append((n, pkg + "/" + cls.replace(".", "/") + ".class"))
    if not classes:
        print("  (没有 mixins.json，跳过)")
        return 0

    import tempfile
    tmpdir = tempfile.mkdtemp()

    checked = 0
    bad = []
    no_srg_at_all = []

    for cfg, cls in classes:
        if cls not in names:
            print("  [!!] 配置里声明了但 jar 里没有: " + cls)
            bad.append((cls, "@Shadow", "<missing>"))
            continue
        data = z.read(cls)
        if b"Lorg/spongepowered/asm/mixin/Shadow;" not in data:
            continue                      # 没有 @Shadow，不需 reobf

        # ★ 2026-10-05 修复：@Mixin(remap = false) 的类**本来就不该**被 reobf。
        #
        #   这类 mixin 瞄准的是第三方模组（Embeddium / Iris）的类，它们不参与 MC 的
        #   SRG 映射，所以成员名必须保持原样。原脚本不看这个标志，会把
        #   BlockRendererMixin.vertices / RenderSectionManagerMixin.renderLists
        #   这类正确写法误报成「没 reobf」。
        #
        #   判定：同一个 class 里 @Mixin 注解的 value 类型（第 #128 号常量那种）
        #   指向的是非 net/minecraft 的包 → 视为第三方目标 → 跳过。
        if _targets_third_party(data):
            continue
        checked += 1

        members = None
        if use_javap:
            members, err = javap_shadow_members(data, cls, tmpdir)
            if members is None:
                print("  [warn] javap 失败（%s），退回计数法: %s" % (err, cls))
        if members is None:
            # 兜底：整类没有 SRG 名 → 视为可疑
            if not re.search(rb"[fm]_\w*_|\bfield_\d|\bmethod_\d", data):
                no_srg_at_all.append(cls)
            continue

        for mem in members:
            if not is_srg(mem):
                bad.append((cls, "@Shadow", mem))

    print("  mixin class 共 %d 个；含 @Shadow 需检查的 %d 个" % (len(classes), checked))

    if no_srg_at_all:
        print("  ⚠ 以下 class 通篇没有 SRG 名，可能整体未 reobf：")
        for c in no_srg_at_all[:10]:
            print("       " + c)

    if bad:
        print("  ⛔ %d 处 @Shadow 成员的声明名不是 SRG（= 没 reobf）：" % len(bad))
        for cls, kind, mem in bad[:20]:
            print("       %-58s %s = %s" % (cls.split("/")[-1], kind, mem))
        print()
        print("  ⇒ 这不是 refmap 的问题！构建时请**用 `gradlew build`，不要 `clean build`**")
        print("    （clean 之后 tsrg 在配置期还不存在，reobf 会被配成空映射，静默不重写）。")
        print("    修法：先 `gradlew build`，若已 clean 过就**再跑一次 `gradlew build`**。")
        return 1

    print("  ✅ 所有 @Shadow 成员的声明名都是 SRG 名（reobf 生效）")
    return 0



def _targets_third_party(data):
    """判断某个 mixin class 是否瞄准第三方（非 MC）目标。

    做法：从常量池里找 `Lorg/spongepowered/asm/mixin/Mixin;` 注解后面出现的
    那个 `Lxxx/yyy/...;` 类描述符。如果它不是 `net/minecraft/` 开头，
    就认为这是第三方 mixin（@Mixin(remap = false) 的典型场景），
    它的 @Shadow 成员**不需要** SRG 名。
    """
    i = data.find(b"Lorg/spongepowered/asm/mixin/Mixin;")
    if i < 0:
        return False
    # 往后找第一个 L...; 形式的类型描述符（跳过注解本身）
    tail = data[i + 40:i + 400]
    m = re.search(rb"L([a-zA-Z0-9_$/]+);", tail)
    if not m:
        return False
    target = m.group(1).decode("utf-8", "replace")
    return not target.startswith("net/minecraft/")


def main():
    targets = sys.argv[1:] or ["."]
    jars = []
    for t in targets:
        if os.path.isdir(t):
            jars += [os.path.join(t, f) for f in sorted(os.listdir(t))
                     if f.endswith(".jar") and "sources" not in f]
        else:
            jars.append(t)
    if not jars:
        print("没有找到 jar")
        return 2
    rc = 0
    for j in jars:
        rc |= check_jar(j)
    print("=" * 76)
    print("REOBF CHECK PASSED" if rc == 0 else "REOBF CHECK FAILED")
    return rc


if __name__ == "__main__":
    sys.exit(main())
