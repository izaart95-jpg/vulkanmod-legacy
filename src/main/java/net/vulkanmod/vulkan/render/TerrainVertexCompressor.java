package net.vulkanmod.vulkan.render;

import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormatElement;

import java.util.Map;

/**
 * Repacks Minecraft's 28-byte chunk vertex into the 16-byte layout the terrain pipeline reads.
 *
 * <p>The work runs on the chunk worker threads, where the mesh is built, rather than on the
 * render thread where it is uploaded. Minecraft hands the finished mesh over as a ByteBuffer, so
 * results are parked here keyed by that buffer and collected at upload time.
 *
 * <p>Layout produced, matching core/terrain_compressed.vsh:
 * <pre>
 *   0 : int16 x, y, z   (block units * POS_SCALE, biased by POS_OFFSET)
 *   6 : int16 light     (skyLight &lt;&lt; 8 | blockLight)
 *   8 : uint16 u, v     (normalised texture coordinates)
 *  12 : uint8 r, g, b, a
 * </pre>
 */
public class TerrainVertexCompressor {

    public static final int STRIDE = 16;
    public static final float POS_SCALE = 2048.0f;
    public static final float POS_OFFSET = -4.0f;

    /** Minecraft's chunk vertex; anything else is left alone. */
    private static final int SOURCE_STRIDE = 28;

    /**
     * Element offsets per vertex format.
     *
     * <p>Cached per format rather than in shared fields: several chunk workers run at once, and
     * a single set of static offsets meant one thread could overwrite them between another
     * thread's check and its use -- which read past the end of the buffer and killed the worker.
     */
    private static final Map<VertexFormat, int[]> OFFSETS = new java.util.concurrent.ConcurrentHashMap<>();

    /** @return {pos, color, uv0, uv2} byte offsets, or null when the format is not compressible. */
    private static int[] offsetsFor(VertexFormat format) {
        if (format == null || format.getVertexSize() != SOURCE_STRIDE) return null;

        int[] cached = OFFSETS.get(format);
        if (cached != null) return cached.length == 4 ? cached : null;

        int pos = -1, color = -1, uv0 = -1, uv2 = -1;
        int offset = 0;
        // 1.8.9 hands out the same elements in more than one order
        // (POSITION,COLOR,UV,UV2 and POSITION,UV,COLOR,UV2), so these cannot be assumed.
        for (VertexFormatElement element : format.getElements()) {
            VertexFormatElement.Type type = element.getType();
            if (type == VertexFormatElement.Type.POSITION
                    && element.getFormat() == VertexFormatElement.Format.FLOAT) {
                pos = offset;
            } else if (type == VertexFormatElement.Type.COLOR) {
                color = offset;
            } else if (type == VertexFormatElement.Type.UV) {
                if (element.getFormat() == VertexFormatElement.Format.FLOAT) uv0 = offset;
                else if (element.getFormat() == VertexFormatElement.Format.SHORT) uv2 = offset;
            }
            offset += element.getSize();
        }

        int[] result = (pos >= 0 && color >= 0 && uv0 >= 0 && uv2 >= 0)
                ? new int[] { pos, color, uv0, uv2 }
                : new int[0];
        OFFSETS.put(format, result);
        return result.length == 4 ? result : null;
    }

    public static boolean canCompress(VertexFormat format) {
        return offsetsFor(format) != null;
    }

    private static short packPos(float v) {
        // (v - 4) * 2048 maps a section's [0,16] onto [-8192, 24576], leaving headroom at both
        // ends for geometry that pokes outside it. Biasing the other way overflows the signed
        // short above v = 12 and tears the mesh apart.
        int i = Math.round((v + POS_OFFSET) * POS_SCALE);
        if (i > Short.MAX_VALUE) i = Short.MAX_VALUE;
        else if (i < Short.MIN_VALUE) i = Short.MIN_VALUE;
        return (short) i;
    }

    private static short packUV(float v) {
        int i = Math.round(v * 65535.0f);
        if (i > 65535) i = 65535;
        else if (i < 0) i = 0;
        return (short) i;
    }


}
