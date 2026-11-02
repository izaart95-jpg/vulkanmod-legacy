#version 450

vec4 linear_fog(vec4 inColor, float vertexDistance, float fogStart, float fogEnd, vec4 fogColor) {
    if (vertexDistance <= fogStart) {
        return inColor;
    }

    float fogValue = vertexDistance < fogEnd ? smoothstep(fogStart, fogEnd, vertexDistance) : 1.0;
    return vec4(mix(inColor.rgb, fogColor.rgb, fogValue * fogColor.a), inColor.a);
}

layout(binding = 2) uniform sampler2D Sampler0;
layout(binding = 3) uniform sampler2D Sampler2;

layout(binding = 1) uniform UBO{
    vec4 ColorModulator;
    vec4 FogColor;
    float FogStart;
    float FogEnd;
    vec4 OverlayColor;
};

layout(location = 0) in vec4 vertexColor;
layout(location = 1) in vec2 texCoord0;
layout(location = 2) in vec2 texCoord2;
layout(location = 3) in float vertexDistance;

layout(location = 0) out vec4 fragColor;

void main() {
    vec4 color = texture(Sampler0, texCoord0) * vertexColor * ColorModulator;
    if (color.a < 0.1) {
        discard;
    }

    vec3 lit = color.rgb * texture(Sampler2, texCoord2).rgb;

    // 1.8.9 applies the hurt flash (and the creeper fuse flash) by replacing the lightmap
    // combine with GL_INTERPOLATE(constant, previous, ONE_MINUS_SRC_ALPHA). The constant is
    // (1, 0, 0, 0.3) for a hurt entity, so the result is 70% red and 30% of the unlit texture;
    // the creeper passes white with a fuse-dependent alpha. At alpha 0 this is a no-op.
    if (OverlayColor.a > 0.0) {
        lit = OverlayColor.rgb * (1.0 - OverlayColor.a) + color.rgb * OverlayColor.a;
    }

    fragColor = linear_fog(vec4(lit, color.a), vertexDistance, FogStart, FogEnd, FogColor);
}
