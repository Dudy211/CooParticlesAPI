# CooParticlesAPI · Forge 1.20.1 移植

> 上游:[CooStack/CooParticlesAPI](https://github.com/CooStack/CooParticlesAPI)(Fabric / NeoForge, MC 1.21.1)
> 本分支(`forge-1.20.1`):**Forge 1.20.1 纯移植版**(不含 workshop 改造祭坛)

## 构建

```bash
./gradlew build
```

> ⚠️ ForgeGradle 的 reobf 需要跑两遍(`build` 两次),否则会出现"静默空转"
> (@Shadow 字段名没被重写 → 客户端崩)。首次构建后建议再执行一次。

## 已修复问题

| # | 问题 | 根因 | 修复 |
|---|---|---|---|
| A | 召唤 boss 闪退 | UM 编译依赖锁死旧 CP(`VertexConsumerPoseShimKt` vs `VertexBuilderCompatKt`) | 换依赖重编 |
| B | 爆裂法杖闪退 | `maskBloom` 引用 `post/bloom_blur_{horizontal,vertical}.fsh`,上游 CP 从未提供 | 补齐两个方向性高斯模糊 shader |
| C | 进世界闪退 | `RebuildTaskMixin` 对有返回值的目标用了 `CallbackInfo` | 改 `CallbackInfoReturnable<Object>` |
| D | 装 Embeddium 后 boss 看不见 + 卡顿 | 运行期 `isModLoaded("sodium")` 漏判 Embeddium(modid=`embeddium`) | `isSodiumFamilyLoaded()`:sodium / embeddium / rubidium 任一 |
| E | Oculus 光影兼容层完全失效 | `irisLoaded` 漏判 Oculus(modid=`oculus`) | `isIrisFamilyLoaded()`:iris / oculus 任一 |
| F | 纹理缺失时 NPE 崩客户端 | `GlslUtil` 里的 `!!` | 记 warn + 回退 1x1 透明占位图 |
| G | `KeyMappingMixin` 不生效 | 从未被 mixins.json 声明 | 补进 client 列表 |

## License

GPL-3.0(与上游一致)
