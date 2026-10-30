/*
 * Minimal GLU projection helper for the LWJGL3 compatibility layer.
 *
 * Minecraft 1.8.9 calls Project.gluPerspective() directly. The normal launcher
 * supplies that class through LWJGL2's lwjgl_util jar, but this mod replaces
 * LWJGL2 and should not rely on files that a launcher may or may not put on the
 * classpath. The implementation below follows the GLU specification and only
 * uses the LWJGL3 GL11 entry point this mod already provides.
 */
package org.lwjgl.util.glu;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import java.nio.FloatBuffer;

public final class Project {

    private Project() {
    }

    /**
     * Multiplies the current matrix by a perspective projection matrix.
     *
     * @param fovy   field of view in degrees, in the y direction
     * @param aspect aspect ratio (width / height)
     * @param zNear  distance to the near clipping plane
     * @param zFar   distance to the far clipping plane
     */
    public static void gluPerspective(float fovy, float aspect, float zNear, float zFar) {
        float radians = (float) (fovy * Math.PI / 360.0);
        float sine = (float) Math.sin(radians);
        float deltaZ = zFar - zNear;

        if (deltaZ == 0.0f || sine == 0.0f || aspect == 0.0f) {
            return;
        }

        float cotangent = (float) Math.cos(radians) / sine;
        float[] m = new float[16];

        // Column-major, exactly like the classic GLU implementation.
        m[0] = cotangent / aspect;
        m[5] = cotangent;
        m[10] = -(zFar + zNear) / deltaZ;
        m[11] = -1.0f;
        m[14] = -2.0f * zNear * zFar / deltaZ;

        FloatBuffer buffer = BufferUtils.createFloatBuffer(16);
        buffer.put(m).flip();
        GL11.glMultMatrixf(buffer);
    }
}
