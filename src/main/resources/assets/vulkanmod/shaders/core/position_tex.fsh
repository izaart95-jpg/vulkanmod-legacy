#version 450

layout(binding = 2) uniform sampler2D Sampler0;

layout(binding = 1) uniform UBO{
    vec4 ColorModulator;
    vec4 OverlayColor;
};

layout(location = 0) in vec2 texCoord0;

layout(location = 0) out vec4 fragColor;

void main() {
    vec4 color = texture(Sampler0, texCoord0) * ColorModulator;
    if (color.a == 0.0) {
        discard;
    }

    vec3 lit = color.rgb;
    // 1.8.9 applies the hurt flash (and the creeper fuse flash) through the lightmap
    // texture-environment constant. Entity models do not sample a lightmap in this renderer,
    // but the tint is still the same GL_INTERPOLATE(constant, previous, ONE_MINUS_SRC_ALPHA):
    // a hurt entity is (1, 0, 0, 0.3), giving 70% red over the texture; the creeper passes white
    // with a fuse-dependent alpha. At alpha 0 this is a no-op.
    if (OverlayColor.a > 0.0) {
        lit = OverlayColor.rgb * (1.0 - OverlayColor.a) + color.rgb * OverlayColor.a;
    }
    fragColor = vec4(lit, color.a);
}
