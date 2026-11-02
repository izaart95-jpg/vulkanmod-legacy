#version 450

// Opaque variant of the compressed terrain vertex shader. The vertex format and outputs are
// identical; only the fragment shader differs (no alpha cutout, early depth tests).
//
// Compressed terrain vertex: 16 bytes instead of the 28 of Minecraft's BLOCK format.
//   offset 0 : int16 x, y, z   (block units * POS_SCALE, biased by POS_OFFSET)
//   offset 6 : int16 light     (skyLight << 8 | blockLight)
//   offset 8 : uint16 u, v     (normalised texture coordinates)
//   offset 12: uint8  r,g,b,a
layout(location = 0) in ivec4 PositionLight;
layout(location = 1) in vec4 Color;
layout(location = 2) in vec2 UV0;

layout(binding = 0) uniform UniformBufferObject {
   mat4 MVP;
};

layout(push_constant) uniform PushConstants {
    vec3 ChunkOffset;
};

layout(location = 5) in vec3 ChunkOffsetInstance;

layout(location = 0) out vec4 vertexColor;
layout(location = 1) out vec2 texCoord0;
layout(location = 2) out vec2 texCoord2;
layout(location = 3) out float vertexDistance;

const float POS_SCALE = 2048.0;
const float POS_OFFSET = -4.0;

void main() {
    vec3 position = vec3(PositionLight.xyz) / POS_SCALE - POS_OFFSET;
    gl_Position = MVP * vec4(position + ChunkOffset + ChunkOffsetInstance, 1.0);

    int light = PositionLight.w;
    float blockLight = float(light & 0xFF);
    float skyLight = float((light >> 8) & 0xFF);

    vertexColor = Color;
    texCoord0 = UV0;
    texCoord2 = vec2(blockLight, skyLight) / 256.0;
    vertexDistance = gl_Position.w;
}
