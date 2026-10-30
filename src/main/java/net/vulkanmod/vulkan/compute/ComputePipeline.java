package net.vulkanmod.vulkan.compute;

import net.vulkanmod.vulkan.ShaderSPIRVUtils;
import net.vulkanmod.vulkan.Vulkan;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Vulkan compute pipeline for GPU-driven workloads (occlusion culling, etc.).
 * Manages compute shader module, descriptor set layout, pipeline layout, and pipeline.
 */
public class ComputePipeline {
    private static final VkDevice device = Vulkan.getDevice();

    private long shaderModule;
    private long descriptorSetLayout;
    private long pipelineLayout;
    private long pipeline;
    private long descriptorPool;

    private final int maxDescriptorSets;

    public ComputePipeline(String shaderPath, DescriptorBinding[] bindings, int pushConstantSize, int maxSets) {
        this.maxDescriptorSets = maxSets;
        createShaderModule(shaderPath);
        createDescriptorSetLayout(bindings);
        createPipelineLayout(pushConstantSize);
        createPipeline();
        createDescriptorPool(bindings, maxSets);
    }

    private void createShaderModule(String shaderPath) {
        ShaderSPIRVUtils.SPIRV spirv = ShaderSPIRVUtils.compileShaderFile(
                shaderPath, ShaderSPIRVUtils.ShaderKind.COMPUTE_SHADER);

        try (MemoryStack stack = stackPush()) {
            VkShaderModuleCreateInfo createInfo = VkShaderModuleCreateInfo.callocStack(stack);
            createInfo.sType(VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO);
            createInfo.pCode(spirv.bytecode());

            LongBuffer pModule = stack.mallocLong(1);
            if (vkCreateShaderModule(device, createInfo, null, pModule) != VK_SUCCESS) {
                throw new RuntimeException("Failed to create compute shader module");
            }
            shaderModule = pModule.get(0);
        }
    }

    private void createDescriptorSetLayout(DescriptorBinding[] bindings) {
        try (MemoryStack stack = stackPush()) {
            VkDescriptorSetLayoutBinding.Buffer layoutBindings =
                    VkDescriptorSetLayoutBinding.callocStack(bindings.length, stack);

            for (int i = 0; i < bindings.length; i++) {
                VkDescriptorSetLayoutBinding binding = layoutBindings.get(i);
                binding.binding(bindings[i].binding);
                binding.descriptorType(bindings[i].type);
                binding.descriptorCount(1);
                binding.stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            }

            VkDescriptorSetLayoutCreateInfo layoutInfo = VkDescriptorSetLayoutCreateInfo.callocStack(stack);
            layoutInfo.sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO);
            layoutInfo.pBindings(layoutBindings);

            LongBuffer pLayout = stack.mallocLong(1);
            if (vkCreateDescriptorSetLayout(device, layoutInfo, null, pLayout) != VK_SUCCESS) {
                throw new RuntimeException("Failed to create compute descriptor set layout");
            }
            descriptorSetLayout = pLayout.get(0);
        }
    }

    private void createPipelineLayout(int pushConstantSize) {
        try (MemoryStack stack = stackPush()) {
            VkPipelineLayoutCreateInfo layoutInfo = VkPipelineLayoutCreateInfo.callocStack(stack);
            layoutInfo.sType(VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO);
            layoutInfo.pSetLayouts(stack.longs(descriptorSetLayout));

            if (pushConstantSize > 0) {
                VkPushConstantRange.Buffer pcRange = VkPushConstantRange.callocStack(1, stack);
                pcRange.stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
                pcRange.offset(0);
                pcRange.size(pushConstantSize);
                layoutInfo.pPushConstantRanges(pcRange);
            }

            LongBuffer pLayout = stack.mallocLong(1);
            if (vkCreatePipelineLayout(device, layoutInfo, null, pLayout) != VK_SUCCESS) {
                throw new RuntimeException("Failed to create compute pipeline layout");
            }
            pipelineLayout = pLayout.get(0);
        }
    }

    private void createPipeline() {
        try (MemoryStack stack = stackPush()) {
            ByteBuffer entryPoint = stack.UTF8("main");

            VkPipelineShaderStageCreateInfo shaderStage = VkPipelineShaderStageCreateInfo.callocStack(stack);
            shaderStage.sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO);
            shaderStage.stage(VK_SHADER_STAGE_COMPUTE_BIT);
            shaderStage.module(shaderModule);
            shaderStage.pName(entryPoint);

            VkComputePipelineCreateInfo.Buffer pipelineInfo = VkComputePipelineCreateInfo.callocStack(1, stack);
            pipelineInfo.sType(VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO);
            pipelineInfo.stage(shaderStage);
            pipelineInfo.layout(pipelineLayout);

            LongBuffer pPipeline = stack.mallocLong(1);
            if (vkCreateComputePipelines(device, VK_NULL_HANDLE, pipelineInfo, null, pPipeline) != VK_SUCCESS) {
                throw new RuntimeException("Failed to create compute pipeline");
            }
            pipeline = pPipeline.get(0);
        }
    }

    private void createDescriptorPool(DescriptorBinding[] bindings, int maxSets) {
        try (MemoryStack stack = stackPush()) {
            // Count descriptor types
            int storageCount = 0;
            int uniformCount = 0;
            int imageCount = 0;
            for (DescriptorBinding b : bindings) {
                if (b.type == VK_DESCRIPTOR_TYPE_STORAGE_BUFFER) storageCount++;
                else if (b.type == VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER) uniformCount++;
                else if (b.type == VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER ||
                         b.type == VK_DESCRIPTOR_TYPE_STORAGE_IMAGE) imageCount++;
            }

            int poolSizeCount = 0;
            if (storageCount > 0) poolSizeCount++;
            if (uniformCount > 0) poolSizeCount++;
            if (imageCount > 0) poolSizeCount++;

            VkDescriptorPoolSize.Buffer poolSizes = VkDescriptorPoolSize.callocStack(
                    Math.max(poolSizeCount, 1), stack);
            int idx = 0;
            if (storageCount > 0) {
                poolSizes.get(idx).type(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER);
                poolSizes.get(idx).descriptorCount(storageCount * maxSets);
                idx++;
            }
            if (uniformCount > 0) {
                poolSizes.get(idx).type(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER);
                poolSizes.get(idx).descriptorCount(uniformCount * maxSets);
                idx++;
            }
            if (imageCount > 0) {
                poolSizes.get(idx).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER);
                poolSizes.get(idx).descriptorCount(imageCount * maxSets);
                idx++;
            }

            VkDescriptorPoolCreateInfo poolInfo = VkDescriptorPoolCreateInfo.callocStack(stack);
            poolInfo.sType(VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO);
            poolInfo.pPoolSizes(poolSizes);
            poolInfo.maxSets(maxSets);

            LongBuffer pPool = stack.mallocLong(1);
            if (vkCreateDescriptorPool(device, poolInfo, null, pPool) != VK_SUCCESS) {
                throw new RuntimeException("Failed to create compute descriptor pool");
            }
            descriptorPool = pPool.get(0);
        }
    }

    public long allocateDescriptorSet() {
        try (MemoryStack stack = stackPush()) {
            VkDescriptorSetAllocateInfo allocInfo = VkDescriptorSetAllocateInfo.callocStack(stack);
            allocInfo.sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO);
            allocInfo.descriptorPool(descriptorPool);
            allocInfo.pSetLayouts(stack.longs(descriptorSetLayout));

            LongBuffer pDS = stack.mallocLong(1);
            if (vkAllocateDescriptorSets(device, allocInfo, pDS) != VK_SUCCESS) {
                throw new RuntimeException("Failed to allocate compute descriptor set");
            }
            return pDS.get(0);
        }
    }

    public void resetDescriptorPool() {
        vkResetDescriptorPool(device, descriptorPool, 0);
    }

    public long getPipeline() { return pipeline; }
    public long getPipelineLayout() { return pipelineLayout; }
    public long getDescriptorSetLayout() { return descriptorSetLayout; }

    public void cleanUp() {
        vkDestroyPipeline(device, pipeline, null);
        vkDestroyPipelineLayout(device, pipelineLayout, null);
        vkDestroyDescriptorSetLayout(device, descriptorSetLayout, null);
        vkDestroyDescriptorPool(device, descriptorPool, null);
        vkDestroyShaderModule(device, shaderModule, null);
    }

    public static class DescriptorBinding {
        public final int binding;
        public final int type; // VK_DESCRIPTOR_TYPE_*

        public DescriptorBinding(int binding, int type) {
            this.binding = binding;
            this.type = type;
        }
    }
}
