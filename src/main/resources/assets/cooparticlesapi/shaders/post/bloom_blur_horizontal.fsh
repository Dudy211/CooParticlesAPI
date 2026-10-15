#version 330 core

// 1.20.1 移植补充文件。
// 上游 UsefulMagic 的 maskBloom pipeline 引用了 cooparticlesapi:post/bloom_blur_horizontal.fsh,
// 但上游 CooParticlesAPI 从未提供该文件(读不到时 GlslUtil 返回空串 → GLSL 编译失败 → 客户端崩溃)。
// 这里补一个方向性(水平)高斯模糊,与 bloom_blur_vertical.fsh 配对做两趟可分离卷积。
//
// uniform 约定(与 UsefulMagicShaderPipelines.maskBloom 的传参一致):
//   Input  输入颜色(通常是 bright_extract 的输出)
//   Sigma  高斯标准差(单位:纹理像素)
//   Range  采样半径上限(单位:纹理像素,内部钳到 1..16)

in vec2 screen_uv;
out vec4 FragColor;

uniform sampler2D Input;
uniform float Sigma = 4.0;
uniform float Range = 8.0;

void main() {
    vec2 texel = 1.0 / vec2(textureSize(Input, 0));
    vec2 step = vec2(texel.x, 0.0);

    float sigma = max(Sigma, 0.5);
    int taps = int(clamp(Range, 1.0, 16.0));
    float invTwoSigmaSq = 1.0 / (2.0 * sigma * sigma);

    vec4 sum = texture(Input, screen_uv);
    float weightSum = 1.0;
    for (int i = 1; i <= 16; i++) {
        if (i > taps) break;
        float offset = float(i);
        float weight = exp(-(offset * offset) * invTwoSigmaSq);
        sum += texture(Input, clamp(screen_uv + step * offset, vec2(0.001), vec2(0.999))) * weight;
        sum += texture(Input, clamp(screen_uv - step * offset, vec2(0.001), vec2(0.999))) * weight;
        weightSum += 2.0 * weight;
    }

    FragColor = sum / weightSum;
}