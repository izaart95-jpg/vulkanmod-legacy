# VulkanMod for Minecraft 1.8.9

This branch is the **Fabric 1.8.9 backport of VulkanMod**. It keeps the goal of the
original mod — replacing Minecraft's OpenGL renderer with a Vulkan renderer and
optimising world/entity rendering — while using the rendering hooks and legacy
LWJGL2-facing APIs that Minecraft 1.8.9 actually has.

## Features

- **Vulkan rendering backend** for terrain, entities, particles, the HUD and GUI.
- **LWJGL3 migration layer** that keeps 1.8.9's LWJGL2-style calls working on top of
  GLFW/LWJGL3 (input, display, OpenAL and the legacy `GL11` convenience methods).
- **Terrain optimisations**: asynchronous chunk meshing, visible-first rebuild order,
  frustum culling, indirect draws and HZB/occlusion culling.
- **Entity rendering optimisations**: instanced entity rendering and reduced draw
  submission overhead.
- **Chunk-building optimisations**: compressed terrain vertices, chunk arenas and
  staging-buffer uploads.
- **In-game settings menu** (Right Shift) for renderer/performance/HUD options.
- **Native Wayland support on Linux**, plus X11 and the normal Windows/macOS GLFW
  backends.
- **Universal natives**: the release jar bundles LWJGL3 natives for Windows (x64,
  x86 and ARM64), Linux (x64, ARM64 and ARM32) and macOS (Intel and Apple Silicon).
  No platform-specific jar is needed.
- Works on Java 8 and newer (the jar is compiled to Java 8 bytecode).

## Status and performance

The 1.8.9 backport is now considered complete and playable on desktop Fabric,
MJLauncher and Android/Pojav. Mob hurt/death flashes and the creeper fuse flash
match vanilla 1.8.9 again.

The FPS gain is real but modest rather than dramatic. Normal scenes are still
partly GPU-bound, and further optimisation of mesh upload, draw submission and
culling is possible. That work is deliberately left for a future pass; for now
this branch is finished and playable.

## Requirements

- Minecraft **1.8.9**
- **Fabric Loader** 0.14.9 or newer
- A Vulkan-capable GPU and driver:
  - Windows/Linux: the vendor Vulkan driver (or Mesa `lavapipe` for software rendering)
  - macOS: MoltenVK is bundled in the jar

No Fabric API is required.

## Installation

1. Install Fabric Loader for Minecraft 1.8.9.
2. Put the jar for your launcher into `.minecraft/mods`:
   - desktop Fabric: `VulkanMod-0.6.8+1.8.9.jar`
   - MJLauncher: `VulkanMod-0.6.8+1.8.9-mjlauncher.jar`
   - Android/Pojav: `VulkanMod-0.6.8+1.8.9-android.jar`
3. Start the game with the Fabric profile.

If Vulkan is unavailable, the renderer logs a clear diagnostic and the game keeps
running with the normal OpenGL path.

## Building

A JDK 17 is needed to run the legacy Gradle/Loom toolchain. The produced mod itself
targets Java 8.

```bash
./gradlew build
```

`build` depends on the shadow/remap tasks, so no separate `build.sh` invocation
is needed. It produces three installable jars:

| Jar | Target |
| --- | --- |
| `VulkanMod-0.6.8+1.8.9.jar` | Desktop Fabric; self-contained with universal natives |
| `VulkanMod-0.6.8+1.8.9-mjlauncher.jar` | MJLauncher |
| `VulkanMod-0.6.8+1.8.9-android.jar` | Android / Pojav |

The renderer is identical in all three; only the bundled LWJGL/native layer
differs to match the launcher.

## Differences from the 1.21 version

The 1.21 renderer is built around modern Minecraft classes such as
`SectionRenderDispatcher`, `RenderPipeline` and the Fabric renderer API. Those do
not exist in 1.8.9, so the backport uses the equivalent 1.8.9 hooks:

| 1.21 feature | 1.8.9 backport |
| --- | --- |
| `LevelRenderer` / section dispatcher | `WorldRenderer`, `VertexBuffer` and the 1.8.9 chunk rebuild queue |
| `RenderPipeline` / `RenderType` | `Tessellator`, `BufferBuilder`, `VertexFormat` and the Vulkan `Pipeline` cache |
| Fabric renderer API | Direct 1.8.9 block/entity renderer mixins |
| `Window` / GLFW 3.4 window modes | Legacy `Display` shim on top of GLFW 3.3 |
| Modern config screen API | A dedicated 1.8.9 settings screen opened with Right Shift |

Gameplay-facing features are intentionally unchanged: this is still a client-side
rendering/performance mod and does not alter world generation or server behaviour.

## Credits

- Original VulkanMod by Collateral and contributors (LGPL-3.0).
- Legacy LWJGL3 compatibility layer by Zarzelcow.
- 1.8.9 Vulkan backport work by hopccne and contributors.

See `NOTICE` for details about the upstream backport this branch is based on.
