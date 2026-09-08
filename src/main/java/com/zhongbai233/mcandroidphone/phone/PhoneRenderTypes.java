package com.zhongbai233.mcandroidphone.phone;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.client.event.RegisterRenderPipelinesEvent;

/** Phone-owned unlit, opaque pipelines. No media player or codec runtime is loaded. */
final class PhoneRenderTypes {
    static final RenderPipeline NV12=pipeline("nv12");
    static final RenderPipeline RGBA=pipeline("rgba");
    private static final java.util.Map<Identifier,RenderType> RGBA_TYPES=new java.util.HashMap<>();
    private record Planes(Identifier y,Identifier uv) {}
    private static final java.util.Map<Planes,RenderType> NV12_TYPES=new java.util.HashMap<>();
    private static Identifier id(String path) { return Identifier.fromNamespaceAndPath("mcandroidphone",path); }
    private static RenderPipeline pipeline(String name) {
        return RenderPipeline.builder(RenderPipelines.ENTITY_EMISSIVE_SNIPPET)
            .withLocation(id("pipeline/"+name)).withFragmentShader(id("core/"+name))
            .withShaderDefine("NO_OVERLAY").withShaderDefine("NO_CARDINAL_LIGHTING")
            .withCull(false).withSampler("Sampler0").withSampler("Sampler1").build();
    }
    static void register(RegisterRenderPipelinesEvent event) {
        event.registerPipeline(NV12);event.registerPipeline(RGBA);
    }
    static RenderType nv12Entity(Identifier y,Identifier uv,Identifier unused) {
        return NV12_TYPES.computeIfAbsent(new Planes(y,uv),key->RenderType.create("androidphone_nv12",RenderSetup.builder(NV12)
            .withTexture("Sampler0",key.y()).withTexture("Sampler1",key.uv()).createRenderSetup()));
    }
    static RenderType videoRgbaEntity(Identifier rgba) {
        return RGBA_TYPES.computeIfAbsent(rgba,key->RenderType.create("androidphone_rgba",RenderSetup.builder(RGBA)
            .withTexture("Sampler0",key).withTexture("Sampler1",key).createRenderSetup()));
    }
}
