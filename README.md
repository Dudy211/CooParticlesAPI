# CooParticlesAPI · Forge 1.20.1 移植

> 上游:[CooStack/CooParticlesAPI](https://github.com/CooStack/CooParticlesAPI)(Fabric / NeoForge, MC 1.21.1)
> 本分支(`forge-1.20.1`):Forge 1.20.1 移植版

## 构建

```bash
./gradlew build
```

## 修复记录

- **A** UM 编译依赖锁死旧 CP(`VertexConsumerPoseShimKt` vs `VertexBuilderCompatKt`)→ 换依赖重编
- **B** `maskBloom` 引用 `post/bloom_blur_{horizontal,vertical}.fsh`,上游 CP 从未提供 → 补齐两个方向性高斯模糊 shader
- **C** `RebuildTaskMixin` 对有返回值的目标用了 `CallbackInfo` → 改 `CallbackInfoReturnable<Object>`
- **D** 运行期 `isModLoaded("sodium")` 漏判 Embeddium(modid=`embeddium`)→ 改 sodium/embeddium/rubidium 任一
- **E** `irisLoaded` 漏判 Oculus(modid=`oculus`)→ 改 iris/oculus 任一
- **F** `GlslUtil` 纹理读取的 `!!` → 加安全兜底(1x1 透明占位图)
- **G** `KeyMappingMixin`/`KeyMappingAccessor` 未声明 → 补进 mixins.json

## License

GPL-3.0(与上游一致)
