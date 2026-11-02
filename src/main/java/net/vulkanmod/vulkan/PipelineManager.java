package net.vulkanmod.vulkan;

import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormatElement;
import net.vulkanmod.Initializer;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;

public class PipelineManager {
    private static PipelineManager INSTANCE;

    private final Map<String, Pipeline> formatToPipeline = new HashMap<>();
    private final Set<String> failedFormats = new HashSet<>();
    // Identity cache: VertexFormat instance -> Pipeline (avoids string key generation per draw)
    private final IdentityHashMap<VertexFormat, Pipeline> formatCache = new IdentityHashMap<>();
    private final IdentityHashMap<VertexFormat, Boolean> formatCacheMiss = new IdentityHashMap<>();

    private PipelineManager() {}

    public static PipelineManager getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new PipelineManager();
        }
        return INSTANCE;
    }

    /** -Dvulkanmod.compressvertices=false falls back to Minecraft's 28-byte BLOCK layout. */
    public static final boolean COMPRESS_TERRAIN =
            !"false".equalsIgnoreCase(System.getProperty("vulkanmod.compressvertices"));

    private Pipeline compressedTerrainPipeline;
    private boolean compressedTerrainFailed = false;
    private Pipeline compressedTerrainOpaquePipeline;
    private boolean compressedTerrainOpaqueFailed = false;

    /**
     * Pipeline for terrain rebuilt into the 16-byte layout. Terrain is the biggest vertex stream
     * in the frame, and Minecraft's BLOCK format spends 28 bytes on what fits in 16.
     */
    public Pipeline getCompressedTerrainPipeline() {
        if (!COMPRESS_TERRAIN || compressedTerrainFailed) return null;
        if (compressedTerrainPipeline != null) return compressedTerrainPipeline;

        try {
            compressedTerrainPipeline = new Pipeline(null, "core/terrain_compressed");
            Initializer.LOGGER.info("Created compressed terrain pipeline (16-byte vertices)");
        } catch (Exception e) {
            compressedTerrainFailed = true;
            Initializer.LOGGER.error("Failed to create the compressed terrain pipeline; "
                    + "falling back to Minecraft's vertex layout", e);
        }
        return compressedTerrainPipeline;
    }

    /**
     * SOLID terrain variant: no alpha cutout, so the fragment shader can declare early fragment
     * tests and the GPU rejects hidden blocks before sampling the texture and lightmap.
     */
    public Pipeline getCompressedTerrainOpaquePipeline() {
        if (!COMPRESS_TERRAIN || compressedTerrainOpaqueFailed) {
            return getCompressedTerrainPipeline();
        }
        if (compressedTerrainOpaquePipeline != null) return compressedTerrainOpaquePipeline;

        try {
            compressedTerrainOpaquePipeline = new Pipeline(null, "core/terrain_compressed_opaque");
            Initializer.LOGGER.info("Created opaque compressed terrain pipeline (early depth tests)");
        } catch (Exception e) {
            compressedTerrainOpaqueFailed = true;
            Initializer.LOGGER.error("Failed to create the opaque terrain pipeline; "
                    + "falling back to alpha-tested terrain", e);
        }
        return compressedTerrainOpaquePipeline != null
                ? compressedTerrainOpaquePipeline
                : getCompressedTerrainPipeline();
    }

    public Pipeline getPipelineForFormat(VertexFormat format) {
        // Fast path: identity lookup (no string generation, no hashing)
        Pipeline cached = formatCache.get(format);
        if (cached != null) return cached;
        if (formatCacheMiss.containsKey(format)) return null;

        // Slow path: first time seeing this format instance
        String key = getFormatKey(format);
        Pipeline pipeline = formatToPipeline.get(key);
        if (pipeline != null) {
            formatCache.put(format, pipeline);
            return pipeline;
        }
        if (failedFormats.contains(key)) {
            formatCacheMiss.put(format, Boolean.TRUE);
            return null;
        }

        String shaderPath = getShaderForFormat(format);
        if (shaderPath == null) {
            formatCacheMiss.put(format, Boolean.TRUE);
            return null;
        }

        try {
            pipeline = new Pipeline(format, shaderPath);
            formatToPipeline.put(key, pipeline);
            formatCache.put(format, pipeline);
            Initializer.LOGGER.info("Created Vulkan pipeline for format: " + key + " -> " + shaderPath);
            return pipeline;
        } catch (Exception e) {
            Initializer.LOGGER.error("Failed to create pipeline for format: " + key + " (" + shaderPath + ")", e);
            failedFormats.add(key);
            formatCacheMiss.put(format, Boolean.TRUE);
            return null;
        }
    }

    private String getFormatKey(VertexFormat format) {
        StringBuilder sb = new StringBuilder();
        List<VertexFormatElement> elements = format.getElements();
        for (VertexFormatElement element : elements) {
            sb.append(element.getType().name());
            sb.append("_");
            sb.append(element.getFormat().name());
            sb.append(";");
        }
        return sb.toString();
    }

    private String getShaderForFormat(VertexFormat format) {
        List<VertexFormatElement> elements = format.getElements();

        boolean hasPosition = false;
        boolean hasColor = false;
        boolean hasTexture = false;
        boolean hasLightmap = false;
        boolean hasNormal = false;

        for (VertexFormatElement element : elements) {
            VertexFormatElement.Type type = element.getType();
            if (type == VertexFormatElement.Type.POSITION) hasPosition = true;
            else if (type == VertexFormatElement.Type.COLOR) hasColor = true;
            else if (type == VertexFormatElement.Type.UV) {
                if (element.getIndex() == 0) hasTexture = true;
                else if (element.getIndex() == 1) hasLightmap = true;
            }
            else if (type == VertexFormatElement.Type.NORMAL) hasNormal = true;
        }

        if (hasPosition && hasColor && hasTexture && hasLightmap) {
            return "core/position_color_tex_lightmap";
        } else if (hasPosition && hasTexture && hasColor && hasNormal) {
            return "core/position_tex_color_normal";
        } else if (hasPosition && hasTexture && hasColor) {
            return "core/position_tex_color";
        } else if (hasPosition && hasColor && !hasTexture) {
            return "core/position_color";
        } else if (hasPosition && hasTexture) {
            return "core/position_tex";
        } else if (hasPosition && !hasColor && !hasTexture && !hasLightmap && !hasNormal) {
            return "core/position";
        }

        Initializer.LOGGER.warn("[PipelineManager] No shader for format: pos={} col={} tex={} light={} norm={}",
                hasPosition, hasColor, hasTexture, hasLightmap, hasNormal);
        return null;
    }

    public void cleanUp() {
        for (Pipeline pipeline : formatToPipeline.values()) {
            pipeline.cleanUp();
        }
        formatToPipeline.clear();
        formatCache.clear();
        formatCacheMiss.clear();
    }
}
