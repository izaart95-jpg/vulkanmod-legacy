/*
 * Minimal GLU helpers used by Minecraft 1.8.9.
 *
 * Only the two entry points the game actually calls are implemented:
 * gluErrorString (for diagnostics) and gluUnProject (camera picking, enchanting
 * table book). The maths is the standard GLU algorithm and needs no native
 * library, which keeps the mod independent of the launcher's LWJGL2 lwjgl_util
 * jar.
 */
package org.lwjgl.util.glu;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;

public final class GLU {

    private GLU() {
    }

    public static String gluErrorString(int error) {
        switch (error) {
            case 0:
                return "No error";
            case 1280:
                return "Invalid enum";
            case 1281:
                return "Invalid value";
            case 1282:
                return "Invalid operation";
            case 1283:
                return "Stack overflow";
            case 1284:
                return "Stack underflow";
            case 1285:
                return "Out of memory";
            default:
                return "Unknown error (0x" + Integer.toHexString(error) + ")";
        }
    }

    /**
     * Maps window coordinates back to object coordinates.
     *
     * @return true when the projection could be inverted and the result is valid
     */
    public static boolean gluUnProject(float winX, float winY, float winZ,
                                       FloatBuffer model, FloatBuffer proj,
                                       IntBuffer viewport, FloatBuffer obj) {
        if (model == null || proj == null || viewport == null || obj == null) {
            return false;
        }
        if (model.remaining() < 16 || proj.remaining() < 16 || viewport.remaining() < 4 || obj.remaining() < 3) {
            return false;
        }

        int modelPos = model.position();
        int projPos = proj.position();

        // modelview * projection, column-major.
        float[] m = new float[16];
        for (int col = 0; col < 4; col++) {
            for (int row = 0; row < 4; row++) {
                float sum = 0.0f;
                for (int k = 0; k < 4; k++) {
                    sum += model.get(modelPos + k * 4 + row) * proj.get(projPos + col * 4 + k);
                }
                m[col * 4 + row] = sum;
            }
        }

        if (!invert(m)) {
            return false;
        }

        float vx = viewport.get(viewport.position());
        float vy = viewport.get(viewport.position() + 1);
        float vw = viewport.get(viewport.position() + 2);
        float vh = viewport.get(viewport.position() + 3);
        if (vw == 0.0f || vh == 0.0f) {
            return false;
        }

        float[] in = {
                (winX - vx) * 2.0f / vw - 1.0f,
                (winY - vy) * 2.0f / vh - 1.0f,
                2.0f * winZ - 1.0f,
                1.0f
        };
        float[] out = new float[4];
        for (int row = 0; row < 4; row++) {
            out[row] = m[row] * in[0] + m[4 + row] * in[1] + m[8 + row] * in[2] + m[12 + row] * in[3];
        }
        if (out[3] == 0.0f) {
            return false;
        }

        int objPos = obj.position();
        obj.put(objPos, out[0] / out[3]);
        obj.put(objPos + 1, out[1] / out[3]);
        obj.put(objPos + 2, out[2] / out[3]);
        return true;
    }

    /** In-place inverse of a column-major 4x4 matrix. */
    private static boolean invert(float[] m) {
        float[] inv = new float[16];

        inv[0] = m[5] * m[10] * m[15] - m[5] * m[11] * m[14] - m[9] * m[6] * m[15]
                + m[9] * m[7] * m[14] + m[13] * m[6] * m[11] - m[13] * m[7] * m[10];
        inv[4] = -m[4] * m[10] * m[15] + m[4] * m[11] * m[14] + m[8] * m[6] * m[15]
                - m[8] * m[7] * m[14] - m[12] * m[6] * m[11] + m[12] * m[7] * m[10];
        inv[8] = m[4] * m[9] * m[15] - m[4] * m[11] * m[13] - m[8] * m[5] * m[15]
                + m[8] * m[7] * m[13] + m[12] * m[5] * m[11] - m[12] * m[7] * m[9];
        inv[12] = -m[4] * m[9] * m[14] + m[4] * m[10] * m[13] + m[8] * m[5] * m[14]
                - m[8] * m[6] * m[13] - m[12] * m[5] * m[10] + m[12] * m[6] * m[9];

        inv[1] = -m[1] * m[10] * m[15] + m[1] * m[11] * m[14] + m[9] * m[2] * m[15]
                - m[9] * m[3] * m[14] - m[13] * m[2] * m[11] + m[13] * m[3] * m[10];
        inv[5] = m[0] * m[10] * m[15] - m[0] * m[11] * m[14] - m[8] * m[2] * m[15]
                + m[8] * m[3] * m[14] + m[12] * m[2] * m[11] - m[12] * m[3] * m[10];
        inv[9] = -m[0] * m[9] * m[15] + m[0] * m[11] * m[13] + m[8] * m[1] * m[15]
                - m[8] * m[3] * m[13] - m[12] * m[1] * m[11] + m[12] * m[3] * m[9];
        inv[13] = m[0] * m[9] * m[14] - m[0] * m[10] * m[13] - m[8] * m[1] * m[14]
                + m[8] * m[2] * m[13] + m[12] * m[1] * m[10] - m[12] * m[2] * m[9];

        inv[2] = m[1] * m[6] * m[15] - m[1] * m[7] * m[14] - m[5] * m[2] * m[15]
                + m[5] * m[3] * m[14] + m[13] * m[2] * m[7] - m[13] * m[3] * m[6];
        inv[6] = -m[0] * m[6] * m[15] + m[0] * m[7] * m[14] + m[4] * m[2] * m[15]
                - m[4] * m[3] * m[14] - m[12] * m[2] * m[7] + m[12] * m[3] * m[6];
        inv[10] = m[0] * m[5] * m[15] - m[0] * m[7] * m[13] - m[4] * m[1] * m[15]
                + m[4] * m[3] * m[13] + m[12] * m[1] * m[7] - m[12] * m[3] * m[5];
        inv[14] = -m[0] * m[5] * m[14] + m[0] * m[6] * m[13] + m[4] * m[1] * m[14]
                - m[4] * m[2] * m[13] - m[12] * m[1] * m[6] + m[12] * m[2] * m[5];

        inv[3] = -m[1] * m[6] * m[11] + m[1] * m[7] * m[10] + m[5] * m[2] * m[11]
                - m[5] * m[3] * m[10] - m[9] * m[2] * m[7] + m[9] * m[3] * m[6];
        inv[7] = m[0] * m[6] * m[11] - m[0] * m[7] * m[10] - m[4] * m[2] * m[11]
                + m[4] * m[3] * m[10] + m[8] * m[2] * m[7] - m[8] * m[3] * m[6];
        inv[11] = -m[0] * m[5] * m[11] + m[0] * m[7] * m[9] + m[4] * m[1] * m[11]
                - m[4] * m[3] * m[9] - m[8] * m[1] * m[7] + m[8] * m[3] * m[5];
        inv[15] = m[0] * m[5] * m[10] - m[0] * m[6] * m[9] - m[4] * m[1] * m[10]
                + m[4] * m[2] * m[9] + m[8] * m[1] * m[6] - m[8] * m[2] * m[5];

        float det = m[0] * inv[0] + m[1] * inv[4] + m[2] * inv[8] + m[3] * inv[12];
        if (det == 0.0f) {
            return false;
        }

        float invDet = 1.0f / det;
        for (int i = 0; i < 16; i++) {
            m[i] = inv[i] * invDet;
        }
        return true;
    }
}
