#version 450

// Instanced entity fragment shader — same as position_color_tex_lightmap
// but receives per-instance color from vertex shader.

layout(location = 0) in vec4 vertexColor;
layout(location = 1) in vec2 texCoord0;
layout(location = 2) in vec2 texCoord2;
layout(location = 3) in float vertexDistance;

layout(binding = 1) uniform FragUBO {
    vec4 ColorModulator;
    vec4 FogColor;
    float FogStart;
    float FogEnd;
};

layout(set = 0, binding = 2) uniform sampler2D Sampler0;
layout(set = 0, binding = 3) uniform sampler2D Sampler2;

layout(location = 0) out vec4 fragColor;

void main() {
    vec4 color = texture(Sampler0, texCoord0) * vertexColor;

    if (color.a < 0.1) {
        discard;
    }

    vec4 lightColor = texture(Sampler2, texCoord2);
    color = color * lightColor;

    // Linear fog
    float fogFactor = smoothstep(FogStart, FogEnd, vertexDistance);
    color = mix(color, FogColor, fogFactor);

    fragColor = color;
}
