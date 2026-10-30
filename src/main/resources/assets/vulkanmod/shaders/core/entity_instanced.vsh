#version 450

// Instanced entity vertex shader.
// Vertex data comes from binding 0 (shared geometry).
// Per-instance data (model matrix + color) comes from binding 1.

// Per-vertex attributes (binding 0, rate = vertex)
layout(location = 0) in vec3 Position;
layout(location = 1) in vec4 Color;
layout(location = 2) in vec2 UV0;
layout(location = 3) in ivec2 UV2;
layout(location = 4) in vec4 Normal;

// Per-instance attributes (binding 1, rate = instance)
layout(location = 5) in mat4 InstanceModelView;   // locations 5-8
layout(location = 9) in vec4 InstanceColor;        // location 9

// Uniforms
layout(binding = 0) uniform UniformBufferObject {
    mat4 Projection;
};

layout(binding = 1) uniform FragUBO {
    vec4 ColorModulator;
    vec4 FogColor;
    float FogStart;
    float FogEnd;
};

// Outputs
layout(location = 0) out vec4 vertexColor;
layout(location = 1) out vec2 texCoord0;
layout(location = 2) out vec2 texCoord2;
layout(location = 3) out float vertexDistance;

void main() {
    vec4 worldPos = InstanceModelView * vec4(Position, 1.0);
    gl_Position = Projection * worldPos;

    // Vulkan depth remap: [0, 1] instead of GL's [-1, 1]
    gl_Position.z = (gl_Position.z + gl_Position.w) * 0.5;

    vertexColor = Color * InstanceColor * ColorModulator;
    texCoord0 = UV0;
    texCoord2 = vec2(UV2) / 256.0;

    // Fog distance
    vertexDistance = length(worldPos.xyz);
}
