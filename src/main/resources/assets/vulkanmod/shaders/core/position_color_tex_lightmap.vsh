#version 450

layout(location = 0) in vec3 Position;
layout(location = 1) in vec4 Color;
layout(location = 2) in vec2 UV0;
layout(location = 3) in ivec2 UV2;

// Per-instance chunk translation, used by the batched (indirect multi-draw) terrain path,
// where a push constant cannot vary between commands. Exactly one of the two offsets below is
// non-zero for any given draw; the other is a bound zero buffer / zeroed push constant.
layout(location = 5) in vec3 ChunkOffsetInstance;

layout(binding = 0) uniform UniformBufferObject {
   mat4 MVP;
};

// Per-chunk world translation. MVP is the camera matrix (P * V) shared by the whole terrain
// pass, so what used to be a per-chunk MVP upload is now 12 bytes of push constant. Non-terrain
// geometry drawn with this pipeline pushes (0,0,0) and keeps a full MVP, which is identical to
// the previous behaviour.
layout(push_constant) uniform PushConstants {
    vec3 ChunkOffset;
};

layout(location = 0) out vec4 vertexColor;
layout(location = 1) out vec2 texCoord0;
layout(location = 2) out vec2 texCoord2;
layout(location = 3) out float vertexDistance;

void main() {
    gl_Position = MVP * vec4(Position + ChunkOffset + ChunkOffsetInstance, 1.0);

    vertexColor = Color;
    texCoord0 = UV0;
    texCoord2 = vec2(UV2) / 256.0;
    vertexDistance = gl_Position.w;
}
