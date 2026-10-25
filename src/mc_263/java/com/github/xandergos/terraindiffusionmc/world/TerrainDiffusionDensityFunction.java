package com.github.xandergos.terraindiffusionmc.world;

import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider.HeightmapData;
import com.mojang.serialization.MapCodec;
import net.minecraft.util.Interval;
import net.minecraft.world.level.levelgen.densityfunction.DensityBuffer;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunction;
import net.minecraft.world.level.levelgen.densityfunction.DensitySampler;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;
import net.minecraft.world.level.levelgen.densityfunction.DfRewriteRule;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;

/**
 * Terrain-diffusion density function for Minecraft 26.3.
 *
 * <p>26.3 rewrote the density-function system (package {@code levelgen.densityfunction}): a
 * function now compiles to a {@link DensitySampler} that fills whole volumes of float densities,
 * and the value range is a single {@link Interval}. The behaviour is unchanged from the legacy
 * implementation: density = target height of the column minus y, so the terrain surface sits at
 * the height the diffusion model produced. The build selects this source directory
 * ({@code src/mc_263}) for the 26.3 target; 26.1/26.2 use {@code src/mc_legacy}.
 */
public class TerrainDiffusionDensityFunction implements DensityFunction {
    public static final MapCodec<TerrainDiffusionDensityFunction> CODEC =
            MapCodec.unit(TerrainDiffusionDensityFunction::new);

    @Override
    public DensitySampler compileSampler(DensityFunction.CompileContext context) {
        return new Sampler();
    }

    @Override
    public DensityFunction rewriteChildren(DfRewriteRule rule) {
        return this; // leaf function, no children
    }

    @Override
    public Interval range() {
        // density = targetHeight - y. targetHeight comes from the model and depends on the world
        // scale (HeightConverter does not clamp it), so there is no tight bound to declare.
        // INFINITE is the honest answer; a too narrow range could let the compiler fold wrongly.
        return Interval.INFINITE;
    }

    @Override
    public int domainAxes() {
        return DensityFunction.ALL_AXES; // depends on x, z (column height) and y
    }

    @Override
    public MapCodec<? extends DensityFunction> codec() {
        return CODEC;
    }

    /** Remembers the heightmap tile of the last column, so a volume fetches each tile once. */
    private static final class TileCursor {
        int blockStartX, blockStartZ, blockEndX, blockEndZ;
        boolean valid;
        HeightmapData data;

        /** Target surface height of column (x, z), or {@code null} if no heightmap is available. */
        Integer heightAt(int x, int z) {
            if (!valid || x < blockStartX || x >= blockEndX || z < blockStartZ || z >= blockEndZ) {
                int tileSize = TerrainDiffusionConfig.tileSize();
                int tileShift = Integer.numberOfTrailingZeros(tileSize);
                blockStartX = (x >> tileShift) << tileShift;
                blockStartZ = (z >> tileShift) << tileShift;
                blockEndX = blockStartX + tileSize;
                blockEndZ = blockStartZ + tileSize;
                data = LocalTerrainProvider.getInstance().fetchHeightmap(blockStartZ, blockStartX, blockEndZ, blockEndX);
                valid = true;
            }
            if (data == null || data.heightmap == null) return null;
            int localX = Math.max(0, Math.min(data.width - 1, x - blockStartX));
            int localZ = Math.max(0, Math.min(data.height - 1, z - blockStartZ));
            return HeightConverter.convertToMinecraftHeight(data.heightmap[localZ][localX]);
        }
    }

    private static final class Sampler implements DensitySampler {
        @Override
        public float sampleValue(SamplerContext context, int x, int y, int z) {
            Integer height = new TileCursor().heightAt(x, z);
            return height == null ? -y : height - y;
        }

        @Override
        public void sampleVolume(SamplerContext context, DensityBuffer buffer, DensityVolume volume) {
            TileCursor cursor = new TileCursor();
            for (int iz = 0; iz < volume.sizeZ(); iz++) {
                int z = volume.blockZ(iz);
                for (int ix = 0; ix < volume.sizeX(); ix++) {
                    // One heightmap lookup per column, then fill the whole y run.
                    Integer height = cursor.heightAt(volume.blockX(ix), z);
                    for (int iy = 0; iy < volume.sizeY(); iy++) {
                        int y = volume.blockY(iy);
                        buffer.set(volume.indexUnchecked(ix, iy, iz), height == null ? -y : height - y);
                    }
                }
            }
        }
    }
}
