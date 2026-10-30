package net.vulkanmod.vulkan;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.vulkanmod.interfaces.VertexFormatMixed;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormatElement;
import net.minecraft.util.Identifier;
import net.minecraft.util.JsonHelper;
import net.vulkanmod.vulkan.memory.MemoryManager;
import net.vulkanmod.vulkan.memory.UniformBuffers;
import net.vulkanmod.vulkan.shader.Field;
import net.vulkanmod.vulkan.shader.PushConstant;
import net.vulkanmod.vulkan.shader.UBO;
import net.vulkanmod.vulkan.texture.VTextureSelector;
import net.vulkanmod.vulkan.texture.VulkanImage;
import net.vulkanmod.vulkan.util.LongLongMap;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.function.Consumer;

import static net.vulkanmod.vulkan.ShaderSPIRVUtils.*;
import static net.vulkanmod.vulkan.Vulkan.getSwapChainImages;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

public class Pipeline {
    // GL blend factor constants
    public static final int GL_ZERO = 0;
    public static final int GL_ONE = 1;
    public static final int GL_SRC_COLOR = 768;
    public static final int GL_ONE_MINUS_SRC_COLOR = 769;
    public static final int GL_SRC_ALPHA = 770;
    public static final int GL_ONE_MINUS_SRC_ALPHA = 771;
    public static final int GL_DST_ALPHA = 772;
    public static final int GL_ONE_MINUS_DST_ALPHA = 773;
    public static final int GL_DST_COLOR = 774;
    public static final int GL_ONE_MINUS_DST_COLOR = 775;

    public static final BlendState DEFAULT_BLEND_STATE = defaultBlend();
    public static final DepthState DEFAULT_DEPTH_STATE = defaultDepthState();
    public static final LogicOpState DEFAULT_LOGICOP_STATE = new LogicOpState(false, 0);
    public static final ColorMask DEFAULT_COLORMASK = new ColorMask(true, true, true, true);
    public static final BlendState NO_BLEND_STATE = new BlendState(false, 0, 0, 0, 0);

    private static final VkDevice device = Vulkan.getDevice();
    private static final long pipelineCache = createPipelineCache();
    private static final int imagesSize = getSwapChainImages().size();

    // Every live pipeline, so descriptor sets can be invalidated globally when the
    // resources they point at (UBO buffers, texture image views) are replaced.
    private static final List<Pipeline> ALL_PIPELINES = new ArrayList<>();

    private String path;
    private long descriptorSetLayout;
    private long pipelineLayout;
    private Map<PipelineState, Long> graphicsPipelines = new HashMap<>();
    private VertexFormat vertexFormat;

    private final long[] descriptorPools = new long[imagesSize];
    private int descriptorCount = 256;

    private final List<UBO> UBOs = new ArrayList<>();
    private final List<String> samplers = new ArrayList<>();
    private final PushConstant pushConstant = new PushConstant();

    private Consumer<Integer> resetDescriptorPoolFun = defaultResetDPFun();

    // Descriptor set cache keyed by (UBO buffer id, bound texture image views).
    //
    // These sets are written once and never mutated: the UBO bindings are DYNAMIC and get
    // their offset at vkCmdBindDescriptorSets time, and the sampler bindings point at image
    // views that live as long as the texture. So a set stays valid across frames, and it is
    // legal for several in-flight frames to bind the same one. The cache is therefore
    // persistent -- it is only dropped when a UBO buffer or a texture is actually replaced.
    private final LongLongMap[] frameDSCache = new LongLongMap[imagesSize];
    {
        for (int i = 0; i < imagesSize; i++) frameDSCache[i] = new LongLongMap();
    }

    /**
     * When set, the pipeline carries a second, instance-rate vertex binding holding the chunk
     * translation. An indirect multi-draw cannot change a push constant between its commands,
     * so this is how each command in a batched terrain pass gets its own offset -- addressed
     * through firstInstance.
     */
    private boolean instancedChunkOffset = false;

    /** Fixed 16-byte terrain layout, described by hand rather than from a VertexFormat. */
    private boolean compressedTerrain = false;

    public boolean isCompressedTerrain() { return compressedTerrain; }

    /** Byte stride of the compressed terrain vertex. */
    public static final int COMPRESSED_TERRAIN_STRIDE = 16;

    public boolean hasInstancedChunkOffset() { return instancedChunkOffset; }

    /** Byte stride of the instance-rate chunk offset binding (vec3, padded to 16). */
    public static final int CHUNK_OFFSET_STRIDE = 16;

    private long vertShaderModule = 0;
    private long fragShaderModule = 0;

    public Pipeline(VertexFormat vertexFormat, String path) {
        this.path = path;

        createDescriptorSetLayout(path);
        createPipelineLayout();
        graphicsPipelines.computeIfAbsent(new PipelineState(DEFAULT_BLEND_STATE, DEFAULT_DEPTH_STATE, DEFAULT_LOGICOP_STATE, DEFAULT_COLORMASK),
                (pipelineState) -> createGraphicsPipeline(vertexFormat, pipelineState));
        createDescriptorPool(descriptorCount);

        ALL_PIPELINES.add(this);
    }

    private long createGraphicsPipeline(VertexFormat vertexFormat, PipelineState state) {
        this.vertexFormat = vertexFormat;

        try(MemoryStack stack = stackPush()) {

            if(vertShaderModule == 0 || fragShaderModule == 0) {
                SPIRV vertShaderSPIRV = compileShaderFile(path + ".vsh", ShaderKind.VERTEX_SHADER);
                SPIRV fragShaderSPIRV = compileShaderFile(path + ".fsh", ShaderKind.FRAGMENT_SHADER);

                vertShaderModule = createShaderModule(vertShaderSPIRV.bytecode());
                fragShaderModule = createShaderModule(fragShaderSPIRV.bytecode());
            }

            ByteBuffer entryPoint = stack.UTF8("main");

            VkPipelineShaderStageCreateInfo.Buffer shaderStages = VkPipelineShaderStageCreateInfo.callocStack(2, stack);

            VkPipelineShaderStageCreateInfo vertShaderStageInfo = shaderStages.get(0);
            vertShaderStageInfo.sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO);
            vertShaderStageInfo.stage(VK_SHADER_STAGE_VERTEX_BIT);
            vertShaderStageInfo.module(vertShaderModule);
            vertShaderStageInfo.pName(entryPoint);

            VkPipelineShaderStageCreateInfo fragShaderStageInfo = shaderStages.get(1);
            fragShaderStageInfo.sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO);
            fragShaderStageInfo.stage(VK_SHADER_STAGE_FRAGMENT_BIT);
            fragShaderStageInfo.module(fragShaderModule);
            fragShaderStageInfo.pName(entryPoint);

            // ===> VERTEX STAGE <===
            VkPipelineVertexInputStateCreateInfo vertexInputInfo = VkPipelineVertexInputStateCreateInfo.callocStack(stack);
            vertexInputInfo.sType(VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO);
            if (this.compressedTerrain) {
                vertexInputInfo.pVertexBindingDescriptions(
                        compressedTerrainBindings(this.instancedChunkOffset));
                vertexInputInfo.pVertexAttributeDescriptions(
                        compressedTerrainAttributes(this.instancedChunkOffset));
            } else {
                vertexInputInfo.pVertexBindingDescriptions(getBindingDescription(vertexFormat, this.instancedChunkOffset));
                vertexInputInfo.pVertexAttributeDescriptions(getAttributeDescriptions(vertexFormat, this.instancedChunkOffset));
            }

            // ===> ASSEMBLY STAGE <===
            VkPipelineInputAssemblyStateCreateInfo inputAssembly = VkPipelineInputAssemblyStateCreateInfo.callocStack(stack);
            inputAssembly.sType(VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO);
            inputAssembly.topology(state.topology);
            inputAssembly.primitiveRestartEnable(false);

            // ===> VIEWPORT & SCISSOR
            VkPipelineViewportStateCreateInfo viewportState = VkPipelineViewportStateCreateInfo.callocStack(stack);
            viewportState.sType(VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO);
            viewportState.viewportCount(1);
            viewportState.scissorCount(1);

            // ===> RASTERIZATION STAGE <===
            VkPipelineRasterizationStateCreateInfo rasterizer = VkPipelineRasterizationStateCreateInfo.callocStack(stack);
            rasterizer.sType(VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO);
            rasterizer.depthClampEnable(false);
            rasterizer.rasterizerDiscardEnable(false);
            rasterizer.polygonMode(VK_POLYGON_MODE_FILL);
            rasterizer.lineWidth(1.0f);

            if(state.cullState) rasterizer.cullMode(VK_CULL_MODE_BACK_BIT);
            else rasterizer.cullMode(VK_CULL_MODE_NONE);

            rasterizer.frontFace(VK_FRONT_FACE_COUNTER_CLOCKWISE);
            rasterizer.depthBiasEnable(true);

            // ===> MULTISAMPLING <===
            VkPipelineMultisampleStateCreateInfo multisampling = VkPipelineMultisampleStateCreateInfo.callocStack(stack);
            multisampling.sType(VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO);
            multisampling.sampleShadingEnable(false);
            multisampling.rasterizationSamples(VK_SAMPLE_COUNT_1_BIT);

            // ===> DEPTH TEST <===
            VkPipelineDepthStencilStateCreateInfo depthStencil = VkPipelineDepthStencilStateCreateInfo.callocStack(stack);
            depthStencil.sType(VK_STRUCTURE_TYPE_PIPELINE_DEPTH_STENCIL_STATE_CREATE_INFO);
            depthStencil.depthTestEnable(state.depthState.depthTest);
            depthStencil.depthWriteEnable(state.depthState.depthMask);
            depthStencil.depthCompareOp(state.depthState.function);
            depthStencil.depthBoundsTestEnable(false);
            depthStencil.minDepthBounds(0.0f);
            depthStencil.maxDepthBounds(1.0f);
            depthStencil.stencilTestEnable(false);

            // ===> COLOR BLENDING <===
            VkPipelineColorBlendAttachmentState.Buffer colorBlendAttachment = VkPipelineColorBlendAttachmentState.callocStack(1, stack);
            colorBlendAttachment.colorWriteMask(state.colorMask);

            if(state.blendState.enabled) {
                colorBlendAttachment.blendEnable(true);
                colorBlendAttachment.srcColorBlendFactor(state.blendState.srcRgbFactor);
                colorBlendAttachment.dstColorBlendFactor(state.blendState.dstRgbFactor);
                colorBlendAttachment.colorBlendOp(VK_BLEND_OP_ADD);
                colorBlendAttachment.srcAlphaBlendFactor(state.blendState.srcAlphaFactor);
                colorBlendAttachment.dstAlphaBlendFactor(state.blendState.dstAlphaFactor);
                colorBlendAttachment.alphaBlendOp(VK_BLEND_OP_ADD);
            } else {
                colorBlendAttachment.blendEnable(false);
            }

            VkPipelineColorBlendStateCreateInfo colorBlending = VkPipelineColorBlendStateCreateInfo.callocStack(stack);
            colorBlending.sType(VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO);
            colorBlending.logicOpEnable(state.logicOpState.enabled);
            colorBlending.logicOp(state.logicOpState.getLogicOp());
            colorBlending.pAttachments(colorBlendAttachment);
            colorBlending.blendConstants(stack.floats(0.0f, 0.0f, 0.0f, 0.0f));

            // ===> DYNAMIC STATES <===
            VkPipelineDynamicStateCreateInfo dynamicStates = VkPipelineDynamicStateCreateInfo.callocStack(stack);
            dynamicStates.sType(VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO);
            boolean lineTopology = state.topology == VK_PRIMITIVE_TOPOLOGY_LINE_LIST
                    || state.topology == VK_PRIMITIVE_TOPOLOGY_LINE_STRIP;
            dynamicStates.pDynamicStates(lineTopology
                    ? stack.ints(VK_DYNAMIC_STATE_DEPTH_BIAS, VK_DYNAMIC_STATE_VIEWPORT,
                                 VK_DYNAMIC_STATE_SCISSOR, VK_DYNAMIC_STATE_LINE_WIDTH)
                    : stack.ints(VK_DYNAMIC_STATE_DEPTH_BIAS, VK_DYNAMIC_STATE_VIEWPORT,
                                 VK_DYNAMIC_STATE_SCISSOR));

            VkGraphicsPipelineCreateInfo.Buffer pipelineInfo = VkGraphicsPipelineCreateInfo.callocStack(1, stack);
            pipelineInfo.sType(VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO);
            pipelineInfo.pStages(shaderStages);
            pipelineInfo.pVertexInputState(vertexInputInfo);
            pipelineInfo.pInputAssemblyState(inputAssembly);
            pipelineInfo.pViewportState(viewportState);
            pipelineInfo.pRasterizationState(rasterizer);
            pipelineInfo.pMultisampleState(multisampling);
            pipelineInfo.pDepthStencilState(depthStencil);
            pipelineInfo.pColorBlendState(colorBlending);
            pipelineInfo.pDynamicState(dynamicStates);
            pipelineInfo.layout(pipelineLayout);
            pipelineInfo.renderPass(Vulkan.getRenderPass());
            pipelineInfo.subpass(0);
            pipelineInfo.basePipelineHandle(VK_NULL_HANDLE);
            pipelineInfo.basePipelineIndex(-1);

            LongBuffer pGraphicsPipeline = stack.mallocLong(1);

            if(vkCreateGraphicsPipelines(device, pipelineCache, pipelineInfo, null, pGraphicsPipeline) != VK_SUCCESS) {
                throw new RuntimeException("Failed to create graphics pipeline");
            }

            return pGraphicsPipeline.get(0);
        }
    }

    private void createDescriptorSetLayout(String path) {
        JsonObject jsonObject;

        InputStream stream = Pipeline.class.getResourceAsStream("/assets/vulkanmod/shaders/" + path + ".json");
        jsonObject = new JsonParser().parse(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();

        JsonArray jsonUbos = JsonHelper.getArray(jsonObject, "UBOs", (JsonArray)null);
        JsonArray jsonSamplers = JsonHelper.getArray(jsonObject, "samplers", (JsonArray)null);
        JsonArray jsonPushConstants = JsonHelper.getArray(jsonObject, "PushConstants", (JsonArray)null);
        this.instancedChunkOffset = JsonHelper.getBoolean(jsonObject, "instancedChunkOffset", false);
        this.compressedTerrain = "compressed_terrain".equals(JsonHelper.getString(jsonObject, "vertexLayout", ""));

        int bindingsSize = 0;

        if (jsonUbos != null) {
            for (JsonElement jsonelement : jsonUbos) {
                this.parseUboNode(jsonelement);
            }
            bindingsSize = jsonUbos.size();
        }

        if(jsonSamplers != null) {
            for (JsonElement jsonelement : jsonSamplers) {
                this.parseSamplerNode(jsonelement);
            }
            bindingsSize += jsonSamplers.size();
        }

        if(jsonPushConstants != null) {
            this.parsePushConstantNode(jsonPushConstants);
        }

        try(MemoryStack stack = stackPush()) {
            VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.callocStack(bindingsSize, stack);

            int i = 0;
            for(UBO ubo : this.UBOs) {
                VkDescriptorSetLayoutBinding uboLayoutBinding = bindings.get(ubo.getBinding());
                uboLayoutBinding.binding(ubo.getBinding());
                uboLayoutBinding.descriptorCount(1);
                uboLayoutBinding.descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER_DYNAMIC);
                uboLayoutBinding.pImmutableSamplers(null);
                uboLayoutBinding.stageFlags(ubo.getFlags());
                ++i;
            }

            for(String s : this.samplers) {
                VkDescriptorSetLayoutBinding samplerLayoutBinding = bindings.get(i);
                samplerLayoutBinding.binding(i);
                samplerLayoutBinding.descriptorCount(1);
                samplerLayoutBinding.descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER);
                samplerLayoutBinding.pImmutableSamplers(null);
                samplerLayoutBinding.stageFlags(VK_SHADER_STAGE_ALL_GRAPHICS);
                ++i;
            }

            VkDescriptorSetLayoutCreateInfo layoutInfo = VkDescriptorSetLayoutCreateInfo.callocStack(stack);
            layoutInfo.sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO);
            layoutInfo.pBindings(bindings);

            LongBuffer pDescriptorSetLayout = stack.mallocLong(1);

            if(vkCreateDescriptorSetLayout(device, layoutInfo, null, pDescriptorSetLayout) != VK_SUCCESS) {
                throw new RuntimeException("Failed to create descriptor set layout");
            }
            descriptorSetLayout = pDescriptorSetLayout.get(0);
        }
    }

    private void createPipelineLayout() {
        try(MemoryStack stack = stackPush()) {
            VkPipelineLayoutCreateInfo pipelineLayoutInfo = VkPipelineLayoutCreateInfo.callocStack(stack);
            pipelineLayoutInfo.sType(VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO);
            pipelineLayoutInfo.pSetLayouts(stack.longs(descriptorSetLayout));

            if(pushConstant.getSize() > 0) {
                VkPushConstantRange.Buffer pushConstantRange = VkPushConstantRange.callocStack(1, stack);
                pushConstantRange.size(pushConstant.getSize());
                pushConstantRange.offset(0);
                pushConstantRange.stageFlags(VK_SHADER_STAGE_VERTEX_BIT);
                pipelineLayoutInfo.pPushConstantRanges(pushConstantRange);
            }

            LongBuffer pPipelineLayout = stack.longs(VK_NULL_HANDLE);

            if(vkCreatePipelineLayout(device, pipelineLayoutInfo, null, pPipelineLayout) != VK_SUCCESS) {
                throw new RuntimeException("Failed to create pipeline layout");
            }

            pipelineLayout = pPipelineLayout.get(0);
        }
    }

    private void createDescriptorPool(int descriptorCount) {
        try(MemoryStack stack = stackPush()) {
            int size = UBOs.size() + samplers.size();

            VkDescriptorPoolSize.Buffer poolSizes = VkDescriptorPoolSize.callocStack(size, stack);

            int i;
            for(i = 0; i < UBOs.size(); ++i) {
                VkDescriptorPoolSize uniformBufferPoolSize = poolSizes.get(i);
                uniformBufferPoolSize.type(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER_DYNAMIC);
                uniformBufferPoolSize.descriptorCount(descriptorCount);
            }

            for(; i < UBOs.size() + samplers.size(); ++i) {
                VkDescriptorPoolSize textureSamplerPoolSize = poolSizes.get(i);
                textureSamplerPoolSize.type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER);
                textureSamplerPoolSize.descriptorCount(descriptorCount);
            }

            VkDescriptorPoolCreateInfo poolInfo = VkDescriptorPoolCreateInfo.callocStack(stack);
            poolInfo.sType(VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO);
            poolInfo.pPoolSizes(poolSizes);
            poolInfo.maxSets(descriptorCount);

            LongBuffer pDescriptorPool = stack.callocLong(1);

            for(i = 0; i < imagesSize; ++i) {
                // Retire rather than overwrite. The previous pool owns descriptor sets that
                // in-flight command buffers may still be reading, so it cannot be destroyed
                // here; it is released at a frame boundary once the device is idle. (Before
                // this, the handle was simply dropped on the floor and the pool leaked.)
                if (descriptorPools[i] != VK_NULL_HANDLE) {
                    retiredPools.add(descriptorPools[i]);
                }

                if(vkCreateDescriptorPool(device, poolInfo, null, pDescriptorPool) != VK_SUCCESS) {
                    throw new RuntimeException("Failed to create descriptor pool");
                }
                descriptorPools[i] = pDescriptorPool.get(0);

                // The cached handles belong to the pool we just retired.
                frameDSCache[i].clear();
            }
        }

        if (!retiredPools.isEmpty()) requestDescriptorInvalidation();
    }

    private final List<Long> retiredPools = new ArrayList<>();

    private void destroyRetiredPools() {
        for (int i = 0, n = retiredPools.size(); i < n; i++) {
            vkDestroyDescriptorPool(device, retiredPools.get(i), null);
        }
        retiredPools.clear();
    }

    public void cleanUp() {
        vkDestroyShaderModule(device, vertShaderModule, null);
        vkDestroyShaderModule(device, fragShaderModule, null);

        for(long descriptorPool : descriptorPools)
            vkDestroyDescriptorPool(device, descriptorPool, null);

        this.resetDescriptorPoolFun = i -> {};

        graphicsPipelines.forEach((state, pipeline) -> {
            vkDestroyPipeline(device, pipeline, null);
        });
        graphicsPipelines.clear();

        vkDestroyPipelineLayout(device, pipelineLayout, null);
    }

    public long createDescriptorSets(VkCommandBuffer commandBuffer, int frame, UniformBuffers uniformBuffers) {
        DescriptorSetsUnit dsUnit = new DescriptorSetsUnit(commandBuffer, frame, uniformBuffers);
        return dsUnit.descriptorSet;
    }

    private static VkVertexInputBindingDescription.Buffer compressedTerrainBindings(boolean instanced) {
        VkVertexInputBindingDescription.Buffer b =
                VkVertexInputBindingDescription.callocStack(instanced ? 2 : 1);
        b.get(0).binding(0).stride(COMPRESSED_TERRAIN_STRIDE).inputRate(VK_VERTEX_INPUT_RATE_VERTEX);
        if (instanced) {
            b.get(1).binding(1).stride(CHUNK_OFFSET_STRIDE).inputRate(VK_VERTEX_INPUT_RATE_INSTANCE);
        }
        return b;
    }

    private static VkVertexInputAttributeDescription.Buffer compressedTerrainAttributes(boolean instanced) {
        VkVertexInputAttributeDescription.Buffer a =
                VkVertexInputAttributeDescription.callocStack(instanced ? 4 : 3);

        // xyz position plus the packed light, read as one ivec4.
        a.get(0).binding(0).location(0).format(VK_FORMAT_R16G16B16A16_SINT).offset(0);
        // Colour, straight from the four bytes Minecraft already had.
        a.get(1).binding(0).location(1).format(VK_FORMAT_R8G8B8A8_UNORM).offset(12);
        // Texture coordinates, normalised from uint16.
        a.get(2).binding(0).location(2).format(VK_FORMAT_R16G16_UNORM).offset(8);

        if (instanced) {
            a.get(3).binding(1).location(5).format(VK_FORMAT_R32G32B32_SFLOAT).offset(0);
        }
        return a.rewind();
    }

    private static VkVertexInputBindingDescription.Buffer getBindingDescription(VertexFormat vertexFormat,
                                                                                boolean instancedChunkOffset) {
        VkVertexInputBindingDescription.Buffer bindingDescription =
                VkVertexInputBindingDescription.callocStack(instancedChunkOffset ? 2 : 1);

        VkVertexInputBindingDescription vertexBinding = bindingDescription.get(0);
        vertexBinding.binding(0);
        vertexBinding.stride(vertexFormat.getVertexSize());
        vertexBinding.inputRate(VK_VERTEX_INPUT_RATE_VERTEX);

        if (instancedChunkOffset) {
            VkVertexInputBindingDescription instanceBinding = bindingDescription.get(1);
            instanceBinding.binding(1);
            instanceBinding.stride(CHUNK_OFFSET_STRIDE);
            instanceBinding.inputRate(VK_VERTEX_INPUT_RATE_INSTANCE);
        }

        return bindingDescription;
    }

    // Fixed semantic locations for all shaders:
    // Position=0, Color=1, UV0(float)=2, UV2(short/lightmap)=3, Normal=4
    private static VkVertexInputAttributeDescription.Buffer getAttributeDescriptions(VertexFormat vertexFormat,
                                                                                     boolean instancedChunkOffset) {
        List<VertexFormatElement> elements = vertexFormat.getElements();

        int size = elements.size();
        for (VertexFormatElement el : elements) {
            if (el.getType() == VertexFormatElement.Type.PADDING) {
                size--;
            }
        }
        if (instancedChunkOffset) size++;

        VkVertexInputAttributeDescription.Buffer attributeDescriptions =
                VkVertexInputAttributeDescription.callocStack(size);

        int offset = 0;
        int descIdx = 0;

        for(int i = 0; i < elements.size(); ++i) {
            VertexFormatElement element = elements.get(i);
            VertexFormatElement.Type type = element.getType();

            if (type == VertexFormatElement.Type.PADDING) {
                offset += 1;
                continue;
            }

            VkVertexInputAttributeDescription desc = attributeDescriptions.get(descIdx);
            desc.binding(0);

            // Assign SEMANTIC location based on element type
            if (type == VertexFormatElement.Type.POSITION) {
                desc.location(0);
                desc.format(VK_FORMAT_R32G32B32_SFLOAT);
                desc.offset(offset);
                offset += 12;
            } else if (type == VertexFormatElement.Type.COLOR) {
                desc.location(1);
                desc.format(VK_FORMAT_R8G8B8A8_UNORM);
                desc.offset(offset);
                offset += 4;
            } else if (type == VertexFormatElement.Type.UV) {
                if (element.getFormat() == VertexFormatElement.Format.FLOAT) {
                    desc.location(2); // UV0 (texture coords)
                    desc.format(VK_FORMAT_R32G32_SFLOAT);
                    desc.offset(offset);
                    offset += 8;
                } else if (element.getFormat() == VertexFormatElement.Format.SHORT) {
                    desc.location(3); // UV2 (lightmap)
                    desc.format(VK_FORMAT_R16G16_SINT);
                    desc.offset(offset);
                    offset += 4;
                }
            } else if (type == VertexFormatElement.Type.NORMAL) {
                desc.location(4);
                desc.format(VK_FORMAT_R8G8B8A8_SNORM);
                desc.offset(offset);
                offset += 3;
            } else {
                throw new RuntimeException("Unknown format:");
            }

            desc.offset(((VertexFormatMixed)(vertexFormat)).getOffset(descIdx));
            descIdx++;
        }

        if (instancedChunkOffset) {
            // Location 5: per-chunk translation, one per instance, from binding 1.
            // 4 is already taken by NORMAL.
            VkVertexInputAttributeDescription desc = attributeDescriptions.get(descIdx);
            desc.binding(1);
            desc.location(5);
            desc.format(VK_FORMAT_R32G32B32_SFLOAT);
            desc.offset(0);
        }

        return attributeDescriptions.rewind();
    }

    private void parseUboNode(JsonElement jsonelement) {
        JsonObject jsonobject = JsonHelper.asObject(jsonelement, "UBO");
        int binding = JsonHelper.getInt(jsonobject, "binding");
        int type = this.getTypeFromString(JsonHelper.getString(jsonobject, "type"));
        JsonArray fields = JsonHelper.getArray(jsonobject, "fields");

        UBO ubo = new UBO(binding, type);

        for (JsonElement jsonelement2 : fields) {
            JsonObject jsonobject2 = JsonHelper.asObject(jsonelement2, "uniform");
            String name = JsonHelper.getString(jsonobject2, "name");
            String type2 = JsonHelper.getString(jsonobject2, "type");
            int j = JsonHelper.getInt(jsonobject2, "count");

            ubo.addField(Field.createField(type2, name, j, ubo));
        }

        UBOs.add(ubo);
    }

    private void parseSamplerNode(JsonElement jsonelement) {
        JsonObject jsonobject = JsonHelper.asObject(jsonelement, "UBO");
        String name = JsonHelper.getString(jsonobject, "name");
        samplers.add(name);
    }

    private void parsePushConstantNode(JsonArray jsonArray) {
        for(JsonElement jsonelement : jsonArray) {
            JsonObject jsonobject2 = JsonHelper.asObject(jsonelement, "PC");
            String name = JsonHelper.getString(jsonobject2, "name");
            String type2 = JsonHelper.getString(jsonobject2, "type");
            int j = JsonHelper.getInt(jsonobject2, "count");
            pushConstant.addField(Field.createField(type2, name, j, pushConstant));
        }
        pushConstant.allocateBuffer();
    }

    private int getTypeFromString(String s) {
        if ("vertex".equals(s)) return VK_SHADER_STAGE_VERTEX_BIT;
        if ("fragment".equals(s)) return VK_SHADER_STAGE_FRAGMENT_BIT;
        if ("all".equals(s)) return VK_SHADER_STAGE_ALL_GRAPHICS;
        throw new RuntimeException("cannot identify type..");
    }

    private static long createShaderModule(ByteBuffer spirvCode) {
        try(MemoryStack stack = stackPush()) {
            VkShaderModuleCreateInfo createInfo = VkShaderModuleCreateInfo.callocStack(stack);
            createInfo.sType(VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO);
            createInfo.pCode(spirvCode);

            LongBuffer pShaderModule = stack.mallocLong(1);

            if(vkCreateShaderModule(device, createInfo, null, pShaderModule) != VK_SUCCESS) {
                throw new RuntimeException("Failed to create shader module");
            }

            return pShaderModule.get(0);
        }
    }

    private static final String PSO_CACHE_FILE = "vulkanmod_pipeline_cache.bin";

    private static long createPipelineCache() {
        try(MemoryStack stack = stackPush()) {
            VkPipelineCacheCreateInfo cacheCreateInfo = VkPipelineCacheCreateInfo.callocStack(stack);
            cacheCreateInfo.sType(VK_STRUCTURE_TYPE_PIPELINE_CACHE_CREATE_INFO);

            // Try to load pipeline cache from disk for faster startup
            ByteBuffer cacheData = loadPipelineCacheFromDisk();
            if (cacheData != null) {
                cacheCreateInfo.pInitialData(cacheData);
            }

            LongBuffer pPipelineCache = stack.mallocLong(1);

            if(vkCreatePipelineCache(device, cacheCreateInfo, null, pPipelineCache) != VK_SUCCESS) {
                throw new RuntimeException("Failed to create pipeline cache");
            }

            if (cacheData != null) {
                org.lwjgl.system.MemoryUtil.memFree(cacheData);
            }

            return pPipelineCache.get(0);
        }
    }

    public static void savePipelineCacheToDisk() {
        if (pipelineCache == 0) return;
        try (MemoryStack stack = stackPush()) {
            org.lwjgl.PointerBuffer pDataSize = stack.mallocPointer(1);

            // Query cache data size
            vkGetPipelineCacheData(device, pipelineCache, pDataSize, null);
            long dataSize = pDataSize.get(0);
            if (dataSize <= 0) return;

            ByteBuffer data = org.lwjgl.system.MemoryUtil.memAlloc((int) dataSize);
            vkGetPipelineCacheData(device, pipelineCache, pDataSize, data);

            Path cachePath = Paths.get(System.getProperty("user.dir"), PSO_CACHE_FILE);
            byte[] bytes = new byte[(int) dataSize];
            data.get(bytes);
            Files.write(cachePath, bytes);
            org.lwjgl.system.MemoryUtil.memFree(data);
        } catch (Exception e) {
            // Non-critical — just skip saving
        }
    }

    private static ByteBuffer loadPipelineCacheFromDisk() {
        try {
            Path cachePath = Paths.get(System.getProperty("user.dir"), PSO_CACHE_FILE);
            if (!Files.exists(cachePath)) return null;
            byte[] bytes = Files.readAllBytes(cachePath);
            if (bytes.length < 16) return null; // too small to be valid
            ByteBuffer buffer = org.lwjgl.system.MemoryUtil.memAlloc(bytes.length);
            buffer.put(bytes);
            buffer.flip();
            return buffer;
        } catch (Exception e) {
            return null;
        }
    }

    private Consumer<Integer> defaultResetDPFun() {
        Consumer<Integer> fun = (i) -> {
            if(vkResetDescriptorPool(device, descriptorPools[i], 0) != VK_SUCCESS) {
                throw new RuntimeException("Failed to reset descriptor pool");
            }
        };
        return fun;
    }

    /**
     * Per-frame hook. Deliberately does nothing now: the cached descriptor sets stay valid
     * across frames, so resetting the pool here would throw away every set and force a
     * vkAllocateDescriptorSets + vkUpdateDescriptorSets for each texture on every frame.
     * Use {@link #invalidateAllDescriptorSets()} when the underlying resources change.
     */
    public void resetDescriptorPool(int i) {
    }

    /** Drops every cached set and recycles the pools. Not safe while frames are in flight. */
    public void invalidateDescriptorSets() {
        destroyRetiredPools();
        for (int i = 0; i < imagesSize; i++) {
            this.resetDescriptorPoolFun.accept(i);
            frameDSCache[i].clear();
        }
    }

    private static boolean descriptorInvalidationPending = false;

    /**
     * Requests that every cached descriptor set be dropped. Deferred rather than immediate:
     * the sets are typically referenced by command buffers that are still in flight when a
     * texture is swapped or a UBO buffer is resized mid-frame.
     */
    public static void requestDescriptorInvalidation() {
        descriptorInvalidationPending = true;
    }

    /** Runs a pending invalidation. Called at frame start, where stalling the device is safe. */
    public static void processPendingDescriptorInvalidation() {
        if (!descriptorInvalidationPending) return;
        descriptorInvalidationPending = false;
        vkDeviceWaitIdle(device);
        invalidateAllDescriptorSets();
    }

    /**
     * Invalidates the descriptor sets of every pipeline. Callers must ensure the GPU is idle
     * (or at least that no in-flight command buffer still references the sets) beforehand.
     */
    public static void invalidateAllDescriptorSets() {
        for (int i = 0, n = ALL_PIPELINES.size(); i < n; i++) {
            ALL_PIPELINES.get(i).invalidateDescriptorSets();
        }
    }

    // Last-state cache: skip HashMap lookup when state hasn't changed between draws.
    // Uses PipelineState.cachedHash (recomputed on every update()) as the comparison key.
    private boolean hasLastQueried = false;
    private int lastQueriedHash;
    private long lastQueriedHandle;

    public long getHandle(PipelineState state) {
        // Fast path: same hash as last draw — skip HashMap entirely
        int h = state.hashCode();
        if (hasLastQueried && h == lastQueriedHash) {
            return lastQueriedHandle;
        }

        Long handle = graphicsPipelines.get(state);
        if (handle != null) {
            hasLastQueried = true;
            lastQueriedHash = h;
            lastQueriedHandle = handle;
            return handle;
        }
        // Rare path: create new pipeline variant for this state
        long newHandle = createGraphicsPipeline(this.vertexFormat, state);
        PipelineState newState = new PipelineState(state.blendState, state.depthState, state.logicOpState, new ColorMask(state.colorMask));
        graphicsPipelines.put(newState, newHandle);
        hasLastQueried = true;
        lastQueriedHash = h;
        lastQueriedHandle = newHandle;
        return newHandle;
    }

    public PushConstant getPushConstant() { return pushConstant; }

    public boolean hasPushConstant() { return pushConstant.getSize() > 0; }

    public List<String> getSamplers() { return samplers; }

    public long getLayout() { return pipelineLayout; }

    public List<UBO> getUBOs() { return UBOs; }

    /**
     * Get or create a descriptor set for this frame, cached by (texture, buffer) identity.
     * With dynamic UBO offsets, the descriptor set only needs to change when textures
     * or the UBO buffer handle change (resize). UBO data offset is set at bind time.
     */
    public long getOrCreateDescriptorSet(int frame, UniformBuffers uniformBuffers) {
        // Include buffer ID in key so resize invalidates cache entries
        long bufId = uniformBuffers.getId(frame);

        // Build texture key from bound textures
        long texKey = bufId;
        if (samplers.size() > 0) {
            VulkanImage tex = VTextureSelector.getBoundTexture();
            texKey = texKey * 31 + tex.getTextureImageView();
            if (samplers.size() > 1) {
                VulkanImage lightTex = VTextureSelector.getLightTexture();
                texKey = texKey * 31 + lightTex.getTextureImageView();
                if (samplers.size() > 2) {
                    VulkanImage overlayTex = VTextureSelector.getOverlayTexture();
                    texKey = texKey * 31 + overlayTex.getTextureImageView();
                }
            }
        }

        long cached = frameDSCache[frame].get(texKey);
        if (cached != LongLongMap.NOT_FOUND) {
            if (PerformanceProfiler.ENABLED) PerformanceProfiler.dsCacheHits++;
            // The set is reused as-is, so the layout transition that writeDescriptorSetForDynamic
            // would have done must still happen: a texture re-uploaded since the set was built is
            // back in TRANSFER_DST. readOnlyLayout() early-returns when the layout already matches.
            ensureBoundTexturesReadable();
            return cached;
        }

        if (PerformanceProfiler.ENABLED) PerformanceProfiler.dsCacheMisses++;

        // Allocate and write a new descriptor set (samplers + UBO bindings with offset=0)
        long ds = allocateNewDescriptorSet(frame);
        writeDescriptorSetForDynamic(ds, frame, uniformBuffers);
        frameDSCache[frame].put(texKey, ds);
        return ds;
    }

    private void ensureBoundTexturesReadable() {
        int n = samplers.size();
        if (n == 0) return;
        VTextureSelector.getBoundTexture().readOnlyLayout();
        if (n > 1) VTextureSelector.getLightTexture().readOnlyLayout();
        if (n > 2) VTextureSelector.getOverlayTexture().readOnlyLayout();
    }

    private long allocateNewDescriptorSet(int frame) {
        try (MemoryStack stack = stackPush()) {
            LongBuffer layouts = stack.longs(descriptorSetLayout);
            VkDescriptorSetAllocateInfo allocInfo = VkDescriptorSetAllocateInfo.callocStack(stack);
            allocInfo.sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO);
            allocInfo.descriptorPool(descriptorPools[frame]);
            allocInfo.pSetLayouts(layouts);

            LongBuffer pDescriptorSet = stack.mallocLong(1);
            int result = vkAllocateDescriptorSets(device, allocInfo, pDescriptorSet);
            if (result == -1000069000) {
                descriptorCount *= 2;
                createDescriptorPool(descriptorCount);
                return allocateNewDescriptorSet(frame);
            }
            if (result != VK_SUCCESS) {
                throw new RuntimeException("Failed to allocate descriptor sets. Result:" + result);
            }
            return pDescriptorSet.get(0);
        }
    }

    /**
     * Write descriptor set for dynamic UBO usage: UBO bindings use offset=0, range=uboSize.
     * The actual offset is provided at vkCmdBindDescriptorSets time via dynamic offsets.
     */
    private void writeDescriptorSetForDynamic(long descriptorSet, int frame, UniformBuffers uniformBuffers) {
        try (MemoryStack stack = stackPush()) {
            VkWriteDescriptorSet.Buffer descriptorWrites = VkWriteDescriptorSet.callocStack(UBOs.size() + samplers.size(), stack);
            VkDescriptorBufferInfo.Buffer[] bufferInfos = new VkDescriptorBufferInfo.Buffer[UBOs.size()];

            int i = 0;
            for (UBO ubo : UBOs) {
                bufferInfos[i] = VkDescriptorBufferInfo.callocStack(1, stack);
                bufferInfos[i].buffer(uniformBuffers.getId(frame));
                bufferInfos[i].offset(0); // base offset = 0; dynamic offset added at bind time
                bufferInfos[i].range(ubo.getSize());

                VkWriteDescriptorSet uboDescriptorWrite = descriptorWrites.get(i);
                uboDescriptorWrite.sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET);
                uboDescriptorWrite.dstSet(descriptorSet);
                uboDescriptorWrite.dstBinding(ubo.getBinding());
                uboDescriptorWrite.dstArrayElement(0);
                uboDescriptorWrite.descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER_DYNAMIC);
                uboDescriptorWrite.descriptorCount(1);
                uboDescriptorWrite.pBufferInfo(bufferInfos[i]);
                ++i;
            }

            VkDescriptorImageInfo.Buffer[] imageInfos = new VkDescriptorImageInfo.Buffer[samplers.size()];

            if (samplers.size() > 0) {
                VulkanImage texture = VTextureSelector.getBoundTexture();
                texture.readOnlyLayout();

                imageInfos[0] = VkDescriptorImageInfo.callocStack(1, stack);
                imageInfos[0].imageLayout(texture.getImageLayout());
                imageInfos[0].imageView(texture.getTextureImageView());
                imageInfos[0].sampler(texture.getTextureSampler());

                VkWriteDescriptorSet samplerDescriptorWrite = descriptorWrites.get(i);
                samplerDescriptorWrite.sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET);
                samplerDescriptorWrite.dstSet(descriptorSet);
                samplerDescriptorWrite.dstBinding(i);
                samplerDescriptorWrite.dstArrayElement(0);
                samplerDescriptorWrite.descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER);
                samplerDescriptorWrite.descriptorCount(1);
                samplerDescriptorWrite.pImageInfo(imageInfos[0]);
                ++i;

                if (samplers.size() > 1) {
                    VulkanImage lightTexture = VTextureSelector.getLightTexture();
                    lightTexture.readOnlyLayout();

                    imageInfos[1] = VkDescriptorImageInfo.callocStack(1, stack);
                    imageInfos[1].imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
                    imageInfos[1].imageView(lightTexture.getTextureImageView());
                    imageInfos[1].sampler(lightTexture.getTextureSampler());

                    VkWriteDescriptorSet samplerWrite = descriptorWrites.get(i);
                    samplerWrite.sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET);
                    samplerWrite.dstSet(descriptorSet);
                    samplerWrite.dstBinding(i);
                    samplerWrite.dstArrayElement(0);
                    samplerWrite.descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER);
                    samplerWrite.descriptorCount(1);
                    samplerWrite.pImageInfo(imageInfos[1]);
                    ++i;

                    if (samplers.size() > 2) {
                        VulkanImage overlayTexture = VTextureSelector.getOverlayTexture();
                        overlayTexture.readOnlyLayout();

                        imageInfos[2] = VkDescriptorImageInfo.callocStack(1, stack);
                        imageInfos[2].imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
                        imageInfos[2].imageView(overlayTexture.getTextureImageView());
                        imageInfos[2].sampler(overlayTexture.getTextureSampler());

                        VkWriteDescriptorSet overlaySamplerWrite = descriptorWrites.get(i);
                        overlaySamplerWrite.sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET);
                        overlaySamplerWrite.dstSet(descriptorSet);
                        overlaySamplerWrite.dstBinding(i);
                        overlaySamplerWrite.dstArrayElement(0);
                        overlaySamplerWrite.descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER);
                        overlaySamplerWrite.descriptorCount(1);
                        overlaySamplerWrite.pImageInfo(imageInfos[2]);
                    }
                }
            }

            vkUpdateDescriptorSets(device, descriptorWrites, null);
        }
    }

    private class DescriptorSetsUnit {
        private long descriptorSet;

        private DescriptorSetsUnit(VkCommandBuffer commandBuffer, int frame, UniformBuffers uniformBuffers) {
            allocateDescriptorSet(frame);
            updateDescriptorSet(commandBuffer, frame, uniformBuffers);
        }

        private void allocateDescriptorSet(int frame) {
            try(MemoryStack stack = stackPush()) {
                LongBuffer layouts = stack.mallocLong(1);
                for (int i = 0; i < layouts.capacity(); i++) {
                    layouts.put(i, descriptorSetLayout);
                }

                VkDescriptorSetAllocateInfo allocInfo = VkDescriptorSetAllocateInfo.callocStack(stack);
                allocInfo.sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO);
                allocInfo.descriptorPool(descriptorPools[frame]);
                allocInfo.pSetLayouts(layouts);

                LongBuffer pDescriptorSet = stack.mallocLong(1);

                int result = vkAllocateDescriptorSets(device, allocInfo, pDescriptorSet);
                if (result == -1000069000) {
                    descriptorCount *= 2;
                    createDescriptorPool(descriptorCount);
                    allocateDescriptorSet(frame);
                    System.out.println("resized DescriptorPool to: " + descriptorCount);
                    return;
                }
                if (result != VK_SUCCESS) {
                    throw new RuntimeException("Failed to allocate descriptor sets. Result:" + result);
                }

                descriptorSet = pDescriptorSet.get(0);
            }
        }

        private void updateDescriptorSet(VkCommandBuffer commandBuffer, int frame, UniformBuffers uniformBuffers) {
            try(MemoryStack stack = stackPush()) {
                VkWriteDescriptorSet.Buffer descriptorWrites = VkWriteDescriptorSet.callocStack(UBOs.size() + samplers.size(), stack);
                VkDescriptorBufferInfo.Buffer[] bufferInfos = new VkDescriptorBufferInfo.Buffer[UBOs.size()];

                int currentOffset = uniformBuffers.getUsedBytes();

                int i = 0;
                for(UBO ubo : UBOs) {
                    ubo.update();
                    uniformBuffers.uploadUBO(ubo.getBuffer(), currentOffset, frame);

                    bufferInfos[i] = VkDescriptorBufferInfo.callocStack(1, stack);
                    bufferInfos[i].offset(currentOffset);
                    bufferInfos[i].range(ubo.getSize());

                    VkWriteDescriptorSet uboDescriptorWrite = descriptorWrites.get(i);
                    uboDescriptorWrite.sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET);
                    uboDescriptorWrite.dstBinding(ubo.getBinding());
                    uboDescriptorWrite.dstArrayElement(0);
                    uboDescriptorWrite.descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER_DYNAMIC);
                    uboDescriptorWrite.descriptorCount(1);
                    uboDescriptorWrite.pBufferInfo(bufferInfos[i]);

                    currentOffset = uniformBuffers.getUsedBytes();
                    ++i;
                }

                VkDescriptorImageInfo.Buffer[] imageInfos = new VkDescriptorImageInfo.Buffer[samplers.size()];

                if(samplers.size() > 0) {
                    VulkanImage texture = VTextureSelector.getBoundTexture();
                    texture.readOnlyLayout();

                    imageInfos[0] = VkDescriptorImageInfo.callocStack(1, stack);
                    imageInfos[0].imageLayout(texture.getImageLayout());
                    imageInfos[0].imageView(texture.getTextureImageView());
                    imageInfos[0].sampler(texture.getTextureSampler());

                    VkWriteDescriptorSet samplerDescriptorWrite = descriptorWrites.get(i);
                    samplerDescriptorWrite.sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET);
                    samplerDescriptorWrite.dstBinding(i);
                    samplerDescriptorWrite.dstArrayElement(0);
                    samplerDescriptorWrite.descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER);
                    samplerDescriptorWrite.descriptorCount(1);
                    samplerDescriptorWrite.pImageInfo(imageInfos[0]);
                    ++i;

                    if (samplers.size() > 1) {
                        texture = VTextureSelector.getLightTexture();
                        texture.readOnlyLayout();

                        imageInfos[1] = VkDescriptorImageInfo.callocStack(1, stack);
                        imageInfos[1].imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
                        imageInfos[1].imageView(texture.getTextureImageView());
                        imageInfos[1].sampler(texture.getTextureSampler());

                        samplerDescriptorWrite = descriptorWrites.get(i);
                        samplerDescriptorWrite.sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET);
                        samplerDescriptorWrite.dstBinding(i);
                        samplerDescriptorWrite.dstArrayElement(0);
                        samplerDescriptorWrite.descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER);
                        samplerDescriptorWrite.descriptorCount(1);
                        samplerDescriptorWrite.pImageInfo(imageInfos[1]);
                        ++i;

                        if(samplers.size() > 2) {
                            texture = VTextureSelector.getOverlayTexture();
                            texture.readOnlyLayout();

                            imageInfos[2] = VkDescriptorImageInfo.callocStack(1, stack);
                            imageInfos[2].imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
                            imageInfos[2].imageView(texture.getTextureImageView());
                            imageInfos[2].sampler(texture.getTextureSampler());

                            samplerDescriptorWrite = descriptorWrites.get(i);
                            samplerDescriptorWrite.sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET);
                            samplerDescriptorWrite.dstBinding(i);
                            samplerDescriptorWrite.dstArrayElement(0);
                            samplerDescriptorWrite.descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER);
                            samplerDescriptorWrite.descriptorCount(1);
                            samplerDescriptorWrite.pImageInfo(imageInfos[2]);
                        }
                    }
                }

                for(i = 0; i < UBOs.size(); ++i) {
                    bufferInfos[i].buffer(uniformBuffers.getId(frame));
                    VkWriteDescriptorSet uboDescriptorWrite = descriptorWrites.get(i);
                    uboDescriptorWrite.dstSet(descriptorSet);
                }

                for(; i < UBOs.size() + samplers.size(); ++i) {
                    VkWriteDescriptorSet samplerDescriptorWrite = descriptorWrites.get(i);
                    samplerDescriptorWrite.dstSet(descriptorSet);
                }

                vkUpdateDescriptorSets(device, descriptorWrites, null);
            }
        }
    }

    // BlendState cache: eliminates ~200-500 small allocations per frame
    private static final HashMap<Long, BlendState> blendStateCache = new HashMap<>();

    public static BlendState getOrCreateBlendState(int srcRgb, int dstRgb, int srcAlpha, int dstAlpha) {
        long key = ((long) srcRgb << 48) | ((long) dstRgb << 32) | ((long) srcAlpha << 16) | (long) dstAlpha;
        BlendState cached = blendStateCache.get(key);
        if (cached != null) return cached;
        BlendState bs = new BlendState(srcRgb, dstRgb, srcAlpha, dstAlpha);
        blendStateCache.put(key, bs);
        return bs;
    }

    public static BlendState defaultBlend() {
        return new BlendState(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ZERO);
    }

    public static DepthState defaultDepthState() {
        return new DepthState(true, true, 515);
    }

    public static ColorMask defaultColorMask() { return new ColorMask(true, true, true, true); }

    public static class PipelineState {
        BlendState blendState;
        DepthState depthState;
        int colorMask;
        LogicOpState logicOpState;
        boolean cullState;
        // Topology is baked into a VkPipeline, so line draws (the block selection outline,
        // hitboxes, debug lines) need their own variant rather than a dynamic state toggle.
        int topology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;
        private int cachedHash;

        public PipelineState(BlendState blendState, DepthState depthState, LogicOpState logicOpState, ColorMask colorMask) {
            this.blendState = blendState;
            this.depthState = depthState;
            this.logicOpState = logicOpState;
            this.colorMask = colorMask.colorMask;
            this.cullState = VRenderSystem.cull;
            this.cachedHash = computeHash();
        }

        public void update(BlendState blendState, DepthState depthState, LogicOpState logicOpState, int colorMask) {
            update(blendState, depthState, logicOpState, colorMask, VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST);
        }

        public void update(BlendState blendState, DepthState depthState, LogicOpState logicOpState,
                           int colorMask, int topology) {
            this.blendState = blendState;
            this.depthState = depthState;
            this.logicOpState = logicOpState;
            this.colorMask = colorMask;
            this.cullState = VRenderSystem.cull;
            this.topology = topology;
            this.cachedHash = computeHash();
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            PipelineState that = (PipelineState) o;
            return cullState == that.cullState && colorMask == that.colorMask
                    && topology == that.topology
                    && blendState.equals(that.blendState) && depthState.equals(that.depthState)
                    && logicOpState.equals(that.logicOpState);
        }

        private int computeHash() {
            int h = blendState.hashCode();
            h = 31 * h + depthState.hashCode();
            h = 31 * h + logicOpState.hashCode();
            h = 31 * h + (cullState ? 1 : 0);
            h = 31 * h + colorMask;
            h = 31 * h + topology;
            return h;
        }

        @Override
        public int hashCode() {
            return cachedHash;
        }
    }

    public static class BlendState {
        public final boolean enabled;
        public final int srcRgbFactor;
        public final int dstRgbFactor;
        public final int srcAlphaFactor;
        public final int dstAlphaFactor;
        public final int blendOp = 0;

        public BlendState(int srcRgb, int dstRgb, int srcAlpha, int dstAlpha) {
            this(true, glBlendToVulkan(srcRgb), glBlendToVulkan(dstRgb), glBlendToVulkan(srcAlpha), glBlendToVulkan(dstAlpha));
        }

        protected BlendState(boolean enabled, int srcRgb, int dstRgb, int srcAlpha, int dstAlpha) {
            this.enabled = enabled;
            this.srcRgbFactor = srcRgb;
            this.dstRgbFactor = dstRgb;
            this.srcAlphaFactor = srcAlpha;
            this.dstAlphaFactor = dstAlpha;
        }

        private static int glBlendToVulkan(int value) {
            if (value == 1) return VK_BLEND_FACTOR_ONE;
            if (value == 0) return VK_BLEND_FACTOR_ZERO;
            if (value == 771) return VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA;
            if (value == 770) return VK_BLEND_FACTOR_SRC_ALPHA;
            if (value == 775) return VK_BLEND_FACTOR_ONE_MINUS_DST_COLOR;
            if (value == 769) return VK_BLEND_FACTOR_ONE_MINUS_SRC_COLOR;
            if (value == 774) return VK_BLEND_FACTOR_DST_COLOR;
            if (value == 768) return VK_BLEND_FACTOR_SRC_COLOR;
            if (value == 772) return VK_BLEND_FACTOR_DST_ALPHA;
            if (value == 773) return VK_BLEND_FACTOR_ONE_MINUS_DST_ALPHA;
            throw new RuntimeException("unknown blend factor: " + value);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            BlendState blendState = (BlendState) o;
            if(this.enabled != blendState.enabled) return false;
            return srcRgbFactor == blendState.srcRgbFactor && dstRgbFactor == blendState.dstRgbFactor && srcAlphaFactor == blendState.srcAlphaFactor && dstAlphaFactor == blendState.dstAlphaFactor && blendOp == blendState.blendOp;
        }

        @Override
        public int hashCode() {
            int h = srcRgbFactor;
            h = 31 * h + dstRgbFactor;
            h = 31 * h + srcAlphaFactor;
            h = 31 * h + dstAlphaFactor;
            h = 31 * h + blendOp;
            return h;
        }
    }

    public static class LogicOpState {
        public final boolean enabled;
        private int logicOp;

        public LogicOpState(boolean enable, int op) {
            this.enabled = enable;
            this.logicOp = op;
        }

        public void setLogicOp(int logicOp) {
            this.logicOp = logicOp;
        }

        public int getLogicOp() {
            return logicOp;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            LogicOpState logicOpState = (LogicOpState) o;
            if(this.enabled != logicOpState.enabled) return false;
            return logicOp == logicOpState.logicOp;
        }

        public int hashCode() {
            return (enabled ? 1 : 0) * 31 + logicOp;
        }
    }

    public static class ColorMask {
        public final int colorMask;

        public ColorMask(boolean r, boolean g, boolean b, boolean a) {
            this.colorMask = (r ? VK_COLOR_COMPONENT_R_BIT : 0) | (g ? VK_COLOR_COMPONENT_G_BIT : 0) | (b ? VK_COLOR_COMPONENT_B_BIT : 0) | (a ? VK_COLOR_COMPONENT_A_BIT : 0);
        }

        public ColorMask(int mask) {
            this.colorMask = mask;
        }

        public static int getColorMask(boolean r, boolean g, boolean b, boolean a) {
            return (r ? VK_COLOR_COMPONENT_R_BIT : 0) | (g ? VK_COLOR_COMPONENT_G_BIT : 0) | (b ? VK_COLOR_COMPONENT_B_BIT : 0) | (a ? VK_COLOR_COMPONENT_A_BIT : 0);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            ColorMask colorMask = (ColorMask) o;
            return this.colorMask == colorMask.colorMask;
        }
    }

    public static class DepthState {
        public boolean depthTest;
        public boolean depthMask;
        public int function;

        public DepthState(boolean depthTest, boolean depthMask, int function) {
            this.depthTest = depthTest;
            this.depthMask = depthMask;
            this.function = glDepthToVulkan(function);
        }

        public void update(boolean depthTest, boolean depthMask, int function) {
            this.depthTest = depthTest;
            this.depthMask = depthMask;
            this.function = glDepthToVulkan(function);
        }

        // Lookup table: GL depth func values 512-519 → Vulkan VK_COMPARE_OP values 0-7
        private static final int[] GL_TO_VK_DEPTH = {
            VK_COMPARE_OP_NEVER,            // 512
            VK_COMPARE_OP_LESS,             // 513
            VK_COMPARE_OP_EQUAL,            // 514
            VK_COMPARE_OP_LESS_OR_EQUAL,    // 515
            VK_COMPARE_OP_GREATER,          // 516
            VK_COMPARE_OP_NOT_EQUAL,        // 517
            VK_COMPARE_OP_GREATER_OR_EQUAL, // 518
            VK_COMPARE_OP_ALWAYS            // 519
        };

        private static int glDepthToVulkan(int value) {
            // GL depth functions are 512-519, mapping directly to indices 0-7
            if (value >= 512 && value <= 519) return GL_TO_VK_DEPTH[value - 512];
            // Already a Vulkan value (0-7)
            if (value >= 0 && value <= 7) return value;
            throw new RuntimeException("unknown depth function: " + value);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            DepthState that = (DepthState) o;
            return depthTest == that.depthTest && depthMask == that.depthMask && function == that.function;
        }

        @Override
        public int hashCode() {
            int h = depthTest ? 1 : 0;
            h = 31 * h + (depthMask ? 1 : 0);
            h = 31 * h + function;
            return h;
        }
    }
}
