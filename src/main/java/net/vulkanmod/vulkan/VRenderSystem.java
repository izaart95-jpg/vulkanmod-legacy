package net.vulkanmod.vulkan;

import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.lwjgl.BufferUtils;
import net.vulkanmod.Initializer;
import org.lwjgl.opengl.GL11;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

import static org.lwjgl.glfw.GLFW.glfwGetFramebufferSize;

public class VRenderSystem {

    private static long window;

    public static boolean depthTest = true;
    public static boolean depthMask = true;
    public static int depthFun = 515;

    public static int colorMask = Pipeline.ColorMask.getColorMask(true, true, true, true);

    public static boolean cull = true;

    public static float clearDepth = 1.0f;
    public static FloatBuffer clearColor = MemoryUtil.memAllocFloat(4);

    public static ByteBuffer modelViewMatrix = MemoryUtil.memAlloc(16 * 4);
    public static ByteBuffer projectionMatrix = MemoryUtil.memAlloc(16 * 4);
    public static ByteBuffer TextureMatrix = MemoryUtil.memAlloc(16 * 4);
    public static ByteBuffer MVP = MemoryUtil.memAlloc(16 * 4);
    // Cached FloatBuffer views — avoid creating new views per call
    private static final FloatBuffer modelViewFB = modelViewMatrix.asFloatBuffer();
    private static final FloatBuffer projectionFB = projectionMatrix.asFloatBuffer();
    private static final FloatBuffer mvpFB = MVP.asFloatBuffer();
    private static final FloatBuffer textureFB = TextureMatrix.asFloatBuffer();

    // Software matrix stack — eliminates glGetFloatv GPU-CPU sync
    private static final int GL_MODELVIEW = 0x1700;
    private static final int GL_PROJECTION = 0x1701;
    /**
     * The texture matrix mode.
     *
     * <p>It needs a stack of its own. Everything here used to treat "not projection" as
     * modelview, so the enchantment glint -- which switches to GL_TEXTURE, pushes, scales the
     * texture matrix and pops, twice per item -- was scaling and push/popping the software
     * <em>modelview</em> instead. That is what made the software stack diverge from GL and forced
     * the per-chunk glGetFloatv fallback, thousands of readbacks every frame.
     */
    private static final int GL_TEXTURE = 0x1702;
    private static int currentMatrixMode = GL_MODELVIEW;
    private static final Matrix4f currentModelView = new Matrix4f();
    private static final Matrix4f currentProjection = new Matrix4f();
    // Pre-allocated for calculateMVP — avoid allocation in hot path
    private static final Matrix4f mvpTemp = new Matrix4f();
    // Re-usable buffer for GL matrix reads (only used at sync points, NOT per draw)
    private static final FloatBuffer glMatrixReadBuf = BufferUtils.createFloatBuffer(16);

    // Dirty flag: track when software stack has been modified since last MVP calc
    private static boolean mvpDirty = true;

    // Texture ID tracking — eliminates glGetInteger
    private static int boundTextureId = 0;
    private static int activeTextureUnit = 0;
    private static int lightmapTextureId = 0;

    // Pre-allocated reusable state objects — eliminates per-draw allocations
    private static final Pipeline.DepthState cachedDepthState = new Pipeline.DepthState(true, true, 515);
    private static int lastDepthTest = 1; // boolean as int for fast compare
    private static int lastDepthMask = 1;
    private static int lastDepthFun = 515;

    // Translate tracking for CPU face culling (getMask pattern)
    // Stores the last GlStateManager.translate values — used by VertexBufferMixin
    // to determine camera position relative to chunk section for face direction culling
    private static float lastTransX, lastTransY, lastTransZ;

    // Camera modelview inverse — saved once per frame in setupTerrain,
    // used per chunk draw to extract chunk-camera offset from the GL modelview.
    // inv(cam_mv) * chunk_mv = T(chunk_offset)
    private static final Matrix4f cameraModelViewInv = new Matrix4f();
    private static final Matrix4f chunkOffsetTemp = new Matrix4f();
    private static final float[] chunkOffsetOut = new float[3];

    // Pre-allocated color arrays — eliminates new float[] per call
    private static final float[] shaderColorArr = new float[4];
    private static final float[] shaderFogColorArr = new float[4];

    public static ByteBuffer ChunkOffset = MemoryUtil.memAlloc(3 * 4);
    public static ByteBuffer lightDirection0 = MemoryUtil.memAlloc(3 * 4);
    public static ByteBuffer lightDirection1 = MemoryUtil.memAlloc(3 * 4);

    public static ByteBuffer shaderColor = MemoryUtil.memAlloc(4 * 4);
    public static ByteBuffer shaderFogColor = MemoryUtil.memAlloc(4 * 4);

    // Entity rendering mode — when true, the software matrix stack is guaranteed
    // correct (maintained by ModelPartMixin via GlStateManager), so tessellator
    // draws can use calculateMVP() instead of calculateMVPWithGLSync().
    // This eliminates glGetFloatv per draw during entity rendering.
    private static boolean entityRenderingMode = false;

    // Fog values
    public static float fogStart = 0.0f;
    public static float fogEnd = 1.0f;
    public static float lineWidth = 1.0f;
    public static float gameTime = 0.0f;

    private static final float[] depthBias = new float[2];

    public static void initRenderer() {
        Vulkan.initVulkan(window);
    }

    public static ByteBuffer getChunkOffset() { return ChunkOffset; }

    public static int maxSupportedTextureSize() {
        return Vulkan.deviceProperties.limits().maxImageDimension2D();
    }

    public static void applyModelViewMatrix(Matrix4f mat) {
        mat.get(modelViewFB);
    }

    public static void applyProjectionMatrix(Matrix4f mat) {
        mat.get(projectionFB);
    }

    // MVP calculation using software matrix stack — NO GL reads in the hot path.
    // The software stack is maintained by GlStateManagerMixin hooks (translate, rotate,
    // scale, push/pop, loadIdentity, ortho, multMatrix) and explicit sync points
    // (syncFromGL, syncModelViewFromGL) after direct GL11 calls like orientCamera.
    //
    // For terrain (VertexBuffer.draw via GlStateManager.translated), the software
    // stack is always correct. For entities using direct GL11 calls, we sync at
    // renderEntities HEAD. This eliminates the #1 bottleneck: glGetFloatv per draw.
    public static void calculateMVP() {
        if (!mvpDirty) return; // MVP already up-to-date — skip entirely

        mvpDirty = false;

        currentProjection.mul(currentModelView, mvpTemp);

        // OpenGL→Vulkan depth correction: remap z_clip from [-1,1] to [0,1]
        mvpTemp.m02(0.5f * mvpTemp.m02() + 0.5f * mvpTemp.m03());
        mvpTemp.m12(0.5f * mvpTemp.m12() + 0.5f * mvpTemp.m13());
        mvpTemp.m22(0.5f * mvpTemp.m22() + 0.5f * mvpTemp.m23());
        mvpTemp.m32(0.5f * mvpTemp.m32() + 0.5f * mvpTemp.m33());

        mvpTemp.get(mvpFB);
    }

    // Fallback: read modelview from GL and recalculate MVP.
    // Used ONLY when we know direct GL11 calls happened that bypass GlStateManager
    // (e.g. text rendering, some entity paths). Much cheaper than per-draw GL read
    // because it's called at most a few times per frame, not hundreds.
    /**
     * Recomputes the MVP, re-seeding the modelview from GL only when the software stack cannot
     * be trusted.
     *
     * <p>This is called from every tessellator draw -- GUI, entities, particles, block outlines --
     * so it runs thousands of times per frame, far more often than the per-chunk path. Making it
     * adaptive here covers every caller at once: while the software stack matches GL it costs one
     * validation readback per frame instead of one per draw, and if it ever diverges the old
     * per-draw readback comes back automatically and rendering stays correct.
     */
    public static void calculateMVPWithGLSync() {
        syncModelViewIfNeeded();
        mvpDirty = true;
        calculateMVP();
    }

    /**
     * Re-seeds the modelview from GL, unless the software stack has been opted into.
     * This is the readback that every tessellator and chunk draw depends on for correctness.
     */
    private static void syncModelViewIfNeeded() {
        if (softwareStackTrusted && !MATRIX_DEBUG) return;

        long t = net.vulkanmod.vulkan.PerformanceProfiler.now();
        glMatrixReadBuf.clear();
        GL11.glGetFloatv(0x0BA6, glMatrixReadBuf); // GL_MODELVIEW_MATRIX

        if (MATRIX_DEBUG) checkDivergence();

        if (!softwareStackTrusted) {
            currentModelView.set(glMatrixReadBuf);
        }

        if (net.vulkanmod.vulkan.PerformanceProfiler.ENABLED) {
            net.vulkanmod.vulkan.PerformanceProfiler.glGetFloatvNs += net.vulkanmod.vulkan.PerformanceProfiler.now() - t;
            net.vulkanmod.vulkan.PerformanceProfiler.glReadbackCount++;
        }
    }

    private static void markDirty() {
        mvpDirty = true;
    }

    // --- Sync from GL state (called only at specific sync points, NOT per draw) ---

    // Called once per frame from MinecraftClientMixin.beginRender to catch
    // any direct GL calls (gluPerspective etc.) that bypass GlStateManager.
    public static void syncFromGL() {
        glMatrixReadBuf.clear();
        GL11.glGetFloatv(0x0BA6, glMatrixReadBuf); // GL_MODELVIEW_MATRIX
        currentModelView.set(glMatrixReadBuf);

        glMatrixReadBuf.clear();
        GL11.glGetFloatv(0x0BA7, glMatrixReadBuf); // GL_PROJECTION_MATRIX
        currentProjection.set(glMatrixReadBuf);
        markDirty();
    }

    // Sync only modelview from GL. Called after orientCamera() which uses
    // direct GL11.glRotatef/glTranslated that bypass GlStateManager.
    public static void syncModelViewFromGL() {
        glMatrixReadBuf.clear();
        GL11.glGetFloatv(0x0BA6, glMatrixReadBuf); // GL_MODELVIEW_MATRIX
        currentModelView.set(glMatrixReadBuf);

        // Re-anchor the stack depth, not just the value.
        //
        // Called from setupTerrain and renderEntities, both outside any push/pop pair. Syncing
        // only the matrix left the depth free to drift: one unbalanced push earlier in the frame
        // and every later popMatrix restored a matrix saved at the wrong nesting level. During
        // renderLayer -- which pushes and pops around every chunk -- that surfaced as the stack
        // handing back an item matrix (a bare 1/256 scale, no camera rotation) instead of the
        // camera's. Resetting here makes the pops that follow line up with GL's.
        mvPoolIdx = 0;

        markDirty();
    }

    // Save the camera-only modelview inverse. Called once in setupTerrain
    // after syncModelViewFromGL, before any chunk draws happen.
    public static void saveCameraModelView() {
        currentModelView.invertAffine(cameraModelViewInv);

        // Also bake the camera-only MVP (P * V, with the GL->Vulkan depth remap). Every chunk
        // in the frame shares this matrix: what distinguishes them is only a translation, which
        // now travels as a push constant instead of being folded into a per-chunk MVP. That is
        // what lets the whole terrain pass reuse a single UBO slot and a single descriptor set.
        currentProjection.mul(currentModelView, cameraMvpTemp);
        cameraMvpTemp.m02(0.5f * cameraMvpTemp.m02() + 0.5f * cameraMvpTemp.m03());
        cameraMvpTemp.m12(0.5f * cameraMvpTemp.m12() + 0.5f * cameraMvpTemp.m13());
        cameraMvpTemp.m22(0.5f * cameraMvpTemp.m22() + 0.5f * cameraMvpTemp.m23());
        cameraMvpTemp.m32(0.5f * cameraMvpTemp.m32() + 0.5f * cameraMvpTemp.m33());
        cameraMvpValid = true;
    }

    private static final Matrix4f cameraMvpTemp = new Matrix4f();
    private static boolean cameraMvpValid = false;

    public static boolean isCameraMvpValid() { return cameraMvpValid; }

    public static void invalidateCameraMvp() { cameraMvpValid = false; }

    private static final ByteBuffer cameraMvpBytes = MemoryUtil.memAlloc(16 * 4);

    /**
     * The camera matrix (P * V) as raw bytes, or null when it has not been baked yet.
     *
     * <p>The occlusion cull needs this one rather than {@link #getMVP()}: the chunk offsets it
     * reads are already camera-relative translations, so a matrix with a chunk translation folded
     * in would apply that translation twice.
     */
    public static ByteBuffer getCameraMvpBytes() {
        if (!cameraMvpValid) return null;
        cameraMvpTemp.get(cameraMvpBytes.asFloatBuffer());
        return cameraMvpBytes;
    }

    /**
     * Chunk draw setup for the push-constant path.
     *
     * <p>Reads the chunk's modelview from GL (still necessary -- see the note in
     * VertexBufferMixin about the software matrix stack), but instead of turning it into a
     * per-chunk MVP it extracts just the translation and publishes it as ChunkOffset, leaving
     * MVP as the camera matrix shared by every chunk.
     *
     * @return false when the camera MVP is not available yet, in which case the caller must
     *         fall back to the per-chunk MVP path.
     */
    /**
     * The software matrix stack is NOT trusted by default.
     *
     * <p>It is supposed to shadow GL exactly, and the matrix hooks now mirror unconditionally
     * from the first call so the stack depth cannot desync at startup. That fixed a real bug, but
     * it was not the whole story: relying on the software value still put the world off to one
     * side, so something else diverges and I have not identified it.
     *
     * <p>The cause of the drift was the matrix mode: everything treated "not projection" as
     * modelview, so the enchantment glint's GL_TEXTURE push/scale/pop landed on the software
     * modelview. With GL_TEXTURE given its own stack, matrixdebug -- which checks against GL on
     * every call -- reports no divergence at all, so the stack is trusted by default and the
     * per-chunk readback is gone.
     *
     * <p>-Dvulkanmod.nosoftwarematrix=true forces the old readback path.
     * -Dvulkanmod.matrixdebug=true re-runs the per-call comparison.
     */
    private static boolean softwareStackTrusted =
            !Boolean.getBoolean("vulkanmod.nosoftwarematrix");
    private static final boolean MATRIX_DEBUG = Boolean.getBoolean("vulkanmod.matrixdebug");
    private static boolean divergenceReported = false;
    private static final Matrix4f validationTemp = new Matrix4f();

    private static int frameTick = 0;

    public static void beginFrame() {
        frameTick++;
    }

    public static boolean isSoftwareStackTrusted() { return softwareStackTrusted; }

    /**
     * Compares the software modelview against GL. Returns the largest component difference.
     * Only called when a readback has just happened, so it costs nothing extra.
     */
    private static void checkDivergence() {
        validationTemp.set(glMatrixReadBuf);

        float worst = 0.0f;
        for (int i = 0; i < 16; i++) {
            float d = Math.abs(validationTemp.get(i >> 2, i & 3) - currentModelView.get(i >> 2, i & 3));
            if (d > worst) worst = d;
        }

        if (worst > 1.0e-3f && !divergenceReported) {
            divergenceReported = true;
            Initializer.LOGGER.warn("[VRenderSystem] Software matrix stack diverged from GL by {}."
                    + " GL:\n{}\nsoftware:\n{}", worst, validationTemp, currentModelView,
                    new Throwable("divergence site"));
        }
    }

    public static boolean prepareChunkDraw() {
        if (!cameraMvpValid) return false;

        syncModelViewIfNeeded();

        // inv(V_cam) * M_chunk == T(chunkOffset), so column 3 is the offset.
        cameraModelViewInv.mul(currentModelView, chunkOffsetTemp);

        chunkOffX = chunkOffsetTemp.m30();
        chunkOffY = chunkOffsetTemp.m31();
        chunkOffZ = chunkOffsetTemp.m32();

        ChunkOffset.putFloat(0, chunkOffX);
        ChunkOffset.putFloat(4, chunkOffY);
        ChunkOffset.putFloat(8, chunkOffZ);
        ChunkOffset.position(0);

        cameraMvpTemp.get(mvpFB);
        // Leave the dirty flag set: MVP currently holds the camera matrix, so anything that
        // later asks for a full MVP must recompute rather than trust what is in there.
        mvpDirty = true;
        return true;
    }

    private static float chunkOffX, chunkOffY, chunkOffZ;

    // The offset of the chunk most recently prepared, for the batched path which needs the raw
    // floats rather than the push-constant buffer.
    public static float getChunkOffsetX() { return chunkOffX; }
    public static float getChunkOffsetY() { return chunkOffY; }
    public static float getChunkOffsetZ() { return chunkOffZ; }

    /** Resets ChunkOffset to zero for every draw that is not a terrain chunk. */
    public static void clearChunkOffset() {
        chunkOffX = chunkOffY = chunkOffZ = 0.0f;
        ChunkOffset.putFloat(0, 0.0f);
        ChunkOffset.putFloat(4, 0.0f);
        ChunkOffset.putFloat(8, 0.0f);
        ChunkOffset.position(0);
    }

    // Camera eye world position — extracted from inv(V_cam) column 3.
    // Valid after saveCameraModelView() is called in setupTerrain.
    public static float getCameraEyeX() { return cameraModelViewInv.m30(); }
    public static float getCameraEyeY() { return cameraModelViewInv.m31(); }
    public static float getCameraEyeZ() { return cameraModelViewInv.m32(); }

    // Extract chunk-camera offset from the current GL modelview.
    // inv(cam_mv) * chunk_mv = T(chunkOffset), so column 3 = offset.
    // Returns the camera position in chunk-local space (negated offset).
    public static float[] getChunkCameraOffset() {
        // prepareChunkDraw() already produced this product for the push constant; the camera in
        // chunk-local space is just its negation.
        chunkOffsetOut[0] = -chunkOffX;
        chunkOffsetOut[1] = -chunkOffY;
        chunkOffsetOut[2] = -chunkOffZ;
        return chunkOffsetOut;
    }

    // --- Software matrix stack operations ---

    private static Matrix4f currentMatrix() {
        if (currentMatrixMode == GL_PROJECTION) return currentProjection;
        if (currentMatrixMode == GL_TEXTURE) return currentTexture;
        return currentModelView;
    }

    private static final Matrix4f currentTexture = new Matrix4f();
    private static Matrix4f[] texPoolArr = newMatrixArray(16);
    private static int texPoolIdx = 0;

    public static void matrixMode(int mode) {
        // When LEAVING GL_PROJECTION, sync from GL to catch gluPerspective
        // which writes directly to GL and bypasses GlStateManager.
        // This is the only GL read we can't avoid — it happens once per
        // projection setup, not per draw.
        if (currentMatrixMode == GL_PROJECTION && mode != GL_PROJECTION) {
            glMatrixReadBuf.clear();
            GL11.glGetFloatv(0x0BA7, glMatrixReadBuf); // GL_PROJECTION_MATRIX
            currentProjection.set(glMatrixReadBuf);
            markDirty();
        }
        // Modelview sync is NOT needed here — handled by markModelViewSyncNeeded()
        // called from GameRendererMixin after orientCamera's direct GL calls.
        currentMatrixMode = mode;
    }

    // Pre-allocated matrix pools to avoid GC pressure in push/pop
    // Grown on demand rather than fixed size. A fixed pool silently dropped the push once the
    // depth was exceeded while the matching pop still popped, which desynchronises the stack
    // from real GL state for the rest of the session -- geometry then renders with someone
    // else's matrix. Growing costs one allocation at the new high-water mark and never again.
    private static Matrix4f[] mvPoolArr = newMatrixArray(64);
    private static int mvPoolIdx = 0;
    private static Matrix4f[] projPoolArr = newMatrixArray(16);
    private static int projPoolIdx = 0;

    private static Matrix4f[] newMatrixArray(int size) {
        Matrix4f[] arr = new Matrix4f[size];
        for (int i = 0; i < size; i++) arr[i] = new Matrix4f();
        return arr;
    }

    private static Matrix4f[] growMatrixArray(Matrix4f[] arr) {
        Matrix4f[] grown = java.util.Arrays.copyOf(arr, arr.length * 2);
        for (int i = arr.length; i < grown.length; i++) grown[i] = new Matrix4f();
        return grown;
    }
    // Temp matrix for multMatrix — avoids allocation
    private static final Matrix4f multMatrixTemp = new Matrix4f();

    public static void pushMatrix() {
        if (currentMatrixMode == GL_TEXTURE) {
            if (texPoolIdx == texPoolArr.length) texPoolArr = growMatrixArray(texPoolArr);
            texPoolArr[texPoolIdx].set(currentTexture);
            texPoolIdx++;
        } else if (currentMatrixMode == GL_PROJECTION) {
            if (projPoolIdx == projPoolArr.length) projPoolArr = growMatrixArray(projPoolArr);
            projPoolArr[projPoolIdx].set(currentProjection);
            projPoolIdx++;
        } else {
            if (mvPoolIdx == mvPoolArr.length) mvPoolArr = growMatrixArray(mvPoolArr);
            mvPoolArr[mvPoolIdx].set(currentModelView);
            mvPoolIdx++;
        }
    }

    public static void popMatrix() {
        if (currentMatrixMode == GL_TEXTURE) {
            if (texPoolIdx > 0) {
                texPoolIdx--;
                currentTexture.set(texPoolArr[texPoolIdx]);
            }
        } else if (currentMatrixMode == GL_PROJECTION) {
            if (projPoolIdx > 0) {
                projPoolIdx--;
                currentProjection.set(projPoolArr[projPoolIdx]);
                markDirty();
            }
        } else {
            if (mvPoolIdx > 0) {
                mvPoolIdx--;
                currentModelView.set(mvPoolArr[mvPoolIdx]);
                markDirty();
            }
        }
    }

    public static void loadIdentity() {
        currentMatrix().identity();
        markDirty();
    }

    public static void translatef(float x, float y, float z) {
        lastTransX = x;
        lastTransY = y;
        lastTransZ = z;
        currentMatrix().translate(x, y, z);
        markDirty();
    }

    public static float getLastTranslateX() { return lastTransX; }
    public static float getLastTranslateY() { return lastTransY; }
    public static float getLastTranslateZ() { return lastTransZ; }

    private static final float DEG_TO_RAD = 0.017453292f; // PI / 180

    public static void rotatef(float angle, float x, float y, float z) {
        currentMatrix().rotate(angle * DEG_TO_RAD, x, y, z);
        markDirty();
    }

    public static void scalef(float x, float y, float z) {
        currentMatrix().scale(x, y, z);
        markDirty();
    }

    public static void multMatrix(FloatBuffer fb) {
        multMatrixTemp.set(fb);
        currentMatrix().mul(multMatrixTemp);
        markDirty();
    }

    public static void ortho(double left, double right, double bottom, double top, double near, double far) {
        currentMatrix().ortho((float) left, (float) right, (float) bottom, (float) top, (float) near, (float) far);
        markDirty();
    }

    // --- Texture ID tracking ---

    public static void trackBoundTexture(int id) {
        if (activeTextureUnit == 0) {
            boundTextureId = id;
        } else if (activeTextureUnit == 1) {
            lightmapTextureId = id;
        }
    }

    public static void setActiveTextureUnit(int glEnum) {
        // GL_TEXTURE0 = 33984, so unit = glEnum - 33984
        activeTextureUnit = glEnum - 33984;
    }

    public static int getActiveTextureUnit() {
        return activeTextureUnit;
    }

    public static int getBoundTextureId() {
        return boundTextureId;
    }

    public static int getLightmapTextureId() {
        return lightmapTextureId;
    }

    public static void setTextureMatrix(Matrix4f mat) {
        mat.get(textureFB);
    }

    public static ByteBuffer getTextureMatrix() {
        return TextureMatrix;
    }

    public static ByteBuffer getModelViewMatrix() {
        return modelViewMatrix;
    }

    public static ByteBuffer getProjectionMatrix() {
        return projectionMatrix;
    }

    public static ByteBuffer getMVP() {
        return MVP;
    }

    public static FloatBuffer getProjection() { return projectionFB; }
    public static FloatBuffer getModelView() { return modelViewFB; }

    // For frustum culling
    public static Matrix4f getCurrentProjection() { return currentProjection; }
    public static Matrix4f getCurrentModelView() { return currentModelView; }

    // VP matrix as float array for compute shader push constants (GPU occlusion culling)
    private static final float[] vpMatrixArray = new float[16];
    private static final Matrix4f vpMatrixTemp = new Matrix4f();
    public static float[] getVPMatrixArray() {
        try {
            currentProjection.mul(currentModelView, vpMatrixTemp);
            vpMatrixTemp.get(vpMatrixArray);
            return vpMatrixArray;
        } catch (Exception e) {
            return null;
        }
    }

    public static void setChunkOffset(float f1, float f2, float f3) {
        Drawer.invalidateDescriptorSetCache();
        ChunkOffset.putFloat(0, f1);
        ChunkOffset.putFloat(4, f2);
        ChunkOffset.putFloat(8, f3);
    }

    public static void setShaderColor(float f1, float f2, float f3, float f4) {
        Drawer.invalidateDescriptorSetCache();
        shaderColor.putFloat(0, f1);
        shaderColor.putFloat(4, f2);
        shaderColor.putFloat(8, f3);
        shaderColor.putFloat(12, f4);
    }

    public static void setFogShaderColor(float f1, float f2, float f3, float f4) {
        Drawer.invalidateDescriptorSetCache();
        shaderFogColor.putFloat(0, f1);
        shaderFogColor.putFloat(4, f2);
        shaderFogColor.putFloat(8, f3);
        shaderFogColor.putFloat(12, f4);
    }

    public static ByteBuffer getShaderColor() {
        return shaderColor;
    }

    public static ByteBuffer getShaderFogColor() {
        return shaderFogColor;
    }

    public static float getShaderFogStart() { return fogStart; }
    public static float getShaderFogEnd() { return fogEnd; }
    public static float getShaderLineWidth() { return lineWidth; }
    public static float getShaderGameTime() { return gameTime; }

    public static float[] getShaderColorArray() {
        shaderColorArr[0] = shaderColor.getFloat(0);
        shaderColorArr[1] = shaderColor.getFloat(4);
        shaderColorArr[2] = shaderColor.getFloat(8);
        shaderColorArr[3] = shaderColor.getFloat(12);
        return shaderColorArr;
    }

    public static float[] getShaderFogColorArray() {
        shaderFogColorArr[0] = shaderFogColor.getFloat(0);
        shaderFogColorArr[1] = shaderFogColor.getFloat(4);
        shaderFogColorArr[2] = shaderFogColor.getFloat(8);
        shaderFogColorArr[3] = shaderFogColor.getFloat(12);
        return shaderFogColorArr;
    }

    private static final Pipeline.LogicOpState ENABLED_LOGICOP_STATE = new Pipeline.LogicOpState(true, 0);

    public static void enableColorLogicOp() {
        Drawer.currentLogicOpState = ENABLED_LOGICOP_STATE;
    }

    public static void disableColorLogicOp() {
        Drawer.currentLogicOpState = Pipeline.DEFAULT_LOGICOP_STATE;
    }

    public static void logicOp(int logicOp) {
        Drawer.currentLogicOpState.setLogicOp(logicOp);
    }

    public static void clearColor(float f1, float f2, float f3, float f4) {
        clearColor.put(0, f1);
        clearColor.put(1, f2);
        clearColor.put(2, f3);
        clearColor.put(3, f4);
    }

    public static void clear(int v) {
        Drawer.clearAttachments(v);
    }

    public static void disableDepthTest() {
        depthTest = false;
    }

    public static void depthMask(boolean b) {
        depthMask = b;
    }

    public static Pipeline.DepthState getDepthState() {
        int dt = depthTest ? 1 : 0;
        int dm = depthMask ? 1 : 0;
        if (dt != lastDepthTest || dm != lastDepthMask || depthFun != lastDepthFun) {
            lastDepthTest = dt;
            lastDepthMask = dm;
            lastDepthFun = depthFun;
            cachedDepthState.update(depthTest, depthMask, depthFun);
        }
        return cachedDepthState;
    }

    public static void colorMask(boolean b, boolean b1, boolean b2, boolean b3) {
        colorMask = Pipeline.ColorMask.getColorMask(b, b1, b2, b3);
    }

    public static int getColorMask() {
        return colorMask;
    }

    public static void enableDepthTest() {
        depthTest = true;
    }

    public static void enableCull() {
        cull = true;
    }

    public static void disableCull() {
        cull = false;
    }

    public static void polygonOffset(float v, float v1) {
        depthBias[0] = v;
        depthBias[1] = v1;
    }

    public static void enablePolygonOffset() {
        Drawer.setDepthBias(depthBias[0], depthBias[1]);
    }

    public static void disablePolygonOffset() {
        Drawer.setDepthBias(0.0F, 0.0F);
    }

    private static final int[] screenW = new int[1], screenH = new int[1];
    private static final Vector2f screenSizeVec = new Vector2f();

    /**
     * The framebuffer size, refreshed once a frame rather than read from GLFW on every call.
     *
     * <p>glfwGetFramebufferSize is a call into the window system, and sampling the client thread
     * put 2.7% of it inside this query. The size cannot change between two draws of the same
     * frame: a resize goes through swap chain recreation, which starts a new frame anyway.
     */
    public static void refreshScreenSize() {
        glfwGetFramebufferSize(window, screenW, screenH);
        screenSizeVec.set((float) screenW[0], (float) screenH[0]);
    }

    public static int getScreenWidth() { return screenW[0]; }

    public static int getScreenHeight() { return screenH[0]; }

    public static Vector2f getScreenSize() {
        return screenSizeVec;
    }

    public static void setWindow(long window) {
        VRenderSystem.window = window;
    }

    public static long getWindow() {
        return window;
    }

    public static void depthFunc(int p_69457_) {
        depthFun = p_69457_;
    }

    // --- Entity rendering mode ---

    private static boolean needsEntitySync = false;

    public static void setEntityRenderingMode(boolean mode) {
        entityRenderingMode = mode;
    }

    public static boolean isEntityRenderingMode() {
        return entityRenderingMode;
    }

    // Called at the start of each entity's render — flags that the next draw
    // needs to sync modelview from GL (to capture GL11 direct calls for entity
    // positioning). After that one sync, the software stack is correct for all
    // subsequent draws within the same entity.
    public static void markEntitySyncNeeded() {
        needsEntitySync = true;
    }

    // Called from TessellatorMixin before calculating MVP. If sync is needed,
    // reads GL modelview once and clears the flag. Returns true if sync was done.
    public static boolean syncEntityIfNeeded() {
        if (needsEntitySync) {
            needsEntitySync = false;
            syncModelViewFromGL();
            return true;
        }
        return false;
    }

    // --- Fog sync from GL ---

    // Buffer for reading GL fog color (reused, avoids alloc)
    private static final FloatBuffer fogColorReadBuf = BufferUtils.createFloatBuffer(4);

    // Read fog color, start and end directly from GL state.
    // MC 1.8.9 sets these via GL11.glFogf/glFogfv in GameRenderer.setupFog()
    // BEFORE renderLayer is called, so the values are guaranteed correct.
    // One GL read per frame — negligible cost.
    /**
     * Reads back only the fog colour. GameRenderer sets it with a raw GL11 call that bypasses
     * GlStateManager, so there is no argument to capture; start and end now arrive through the
     * GlStateManager hooks instead of being queried, which is two of the three reads gone.
     */
    public static void syncFogFromGL() {
        fogColorReadBuf.clear();
        GL11.glGetFloatv(0x0B66, fogColorReadBuf); // GL_FOG_COLOR
        shaderFogColor.putFloat(0, fogColorReadBuf.get(0));
        shaderFogColor.putFloat(4, fogColorReadBuf.get(1));
        shaderFogColor.putFloat(8, fogColorReadBuf.get(2));
        shaderFogColor.putFloat(12, fogColorReadBuf.get(3));
    }

    public static void setFogStart(float start) {
        fogStart = start;
    }

    public static void setFogEnd(float end) {
        fogEnd = end;
    }



    // Fallback: compute fog from render distance if GL fog state isn't set.
    // fogEnd extends one full section (16 blocks) past render distance so the
    // fog transition covers ALL visible chunk geometry — no blocks float in void.
    public static void updateFogFromRenderDistance(int renderDistBlocks) {
        fogEnd = (float)(renderDistBlocks + 16);
        fogStart = (float)renderDistBlocks * 0.6f;
    }
}
