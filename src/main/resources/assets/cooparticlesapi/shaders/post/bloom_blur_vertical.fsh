#version 330 core

// 1.20.1 移植补充文件。见 bloom_blur_horizontal.fsh 的说明。
// 方向性(垂直)高斯模糊,与 bloom_blur_horizontal.fsh 配对做两趟可分离卷积。

in vec2 screen_uv;
out vec4 FragColor;

uniform sampler2D Input;
uniform float Sigma = 4.0;
uniform float Range = 8.0;

void main() {
    vec2 texel = 1.0 / vec2(textureSize(Input, 0));
    vec2 step = vec2(0.0, texel.y);

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