package com.jokerdayn.swworldgencore.worldgen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.TallSeagrassBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public class OceanChunkGenerator extends ChunkGenerator {

    private static final Logger log = LoggerFactory.getLogger("SWWorldgenCore");

    private static final int BASE_FLOOR = 25;

    private static final double SPAWN_ISLAND_RADIUS = 170.0;
    private static final double SPAWN_ISLAND_FEATHER = 60.0;
    private static final int SPAWN_ISLAND_MAX_HEIGHT = 18;

    public static final MapCodec<OceanChunkGenerator> CODEC = RecordCodecBuilder.mapCodec(instance ->
            instance.group(
                    BiomeSource.CODEC.fieldOf("biome_source").forGetter(g -> g.biomeSource),
                    Codec.LONG.fieldOf("seed").forGetter(g -> g.seed),
                    Codec.INT.fieldOf("sea_level").forGetter(g -> g.seaLevel)
            ).apply(instance, OceanChunkGenerator::new)
    );

    private final long seed;
    private final int seaLevel;

    public OceanChunkGenerator(BiomeSource biomeSource, long seed, int seaLevel) {
        super(biomeSource);
        this.seed = seed;
        this.seaLevel = seaLevel;
        com.jokerdayn.swworldgencore.SWWorldgenCore.setSeed(seed);
    }

    @Override
    protected MapCodec<? extends ChunkGenerator> codec() {
        return CODEC;
    }

    // хеш — xorshift, даёт псевдо-случайное [0,1] для координат
    private double hsh(int x, int z) {
        long n = (long) x * 73856093L ^ (long) z * 19349663L ^ seed;
        n = (n ^ (n >> 13)) * 1274126177L;
        n = n ^ (n >> 16);
        return (double) (n & 0x7FFFFFFFL) / (double) 0x7FFFFFFFL;
    }

    //价值 noise — билинейная интерполяция 4 углов
    private double vnoise(double x, double z) {
        int xi = (int) Math.floor(x), zi = (int) Math.floor(z);
        double xf = x - xi, zf = z - zi;
        double u = xf * xf * (3 - 2 * xf);
        double v = zf * zf * (3 - 2 * zf);

        double a = hsh(xi, zi), b = hsh(xi + 1, zi);
        double c = hsh(xi, zi + 1), d = hsh(xi + 1, zi + 1);
        return (a + u * (b - a)) + v * ((c + u * (d - c)) - (a + u * (b - a)));
    }

    // fbm — фрактальный шум, oct=кол-во октав, lac=lacunarity, g=gain
    private double fbm(double x, double z, int oct, double lac, double g) {
        double val = 0, amp = 1, freq = 1, max = 0;
        for (int i = 0; i < oct; i++) {
            val += amp * vnoise(x * freq, z * freq);
            max += amp;
            amp *= g;
            freq *= lac;
        }
        return val / max;
    }

    // высота дна в этой точке, с учётом спавн-острова
    private int flr(int x, int z) {
        double wx = x + fbm(x * 0.005, z * 0.005, 2, 2.0, 0.5) * 80;
        double wz = z + fbm(x * 0.005 + 31.7, z * 0.005 + 47.3, 2, 2.0, 0.5) * 80;

        double h = BASE_FLOOR
                + fbm(wx * 0.004, wz * 0.004, 4, 2.0, 0.45) * 25
                + fbm(wx * 0.016, wz * 0.016, 3, 2.0, 0.4) * 8
                + fbm(wx * 0.05, wz * 0.05, 3, 2.0, 0.35) * 4
                + fbm(wx * 0.12, wz * 0.12, 2, 2.0, 0.3) * 2;

        int floor = Math.max(-63, Math.min((int) h, seaLevel - 4));
        int sp = islandH(x, z);
        if (sp > 0) floor = Math.max(floor, seaLevel + sp);
        return floor;
    }

    private BlockState pickSurf(int x, int z, int fl, double sd) {
        if (sd < 1.0) {
            if (fl < seaLevel) return oceanFloor(x, z, fl);
            int above = fl - seaLevel;
            if (above <= 2) return Blocks.SAND.defaultBlockState();
            double bb = Mth.clamp((sd - 0.55) / 0.35, 0.0, 1.0);
            if (bb > 0.6) return Blocks.SAND.defaultBlockState();
            if (bb > 0.3) return Blocks.COARSE_DIRT.defaultBlockState();
            return Blocks.GRASS_BLOCK.defaultBlockState();
        }

        if (fl >= seaLevel) {
            return fl <= seaLevel + 2 ? Blocks.SAND.defaultBlockState() : Blocks.GRASS_BLOCK.defaultBlockState();
        }
        return oceanFloor(x, z, fl);
    }

    private BlockState oceanFloor(int x, int z, int fl) {
        int depth = seaLevel - fl;
        double dn = Mth.clamp((double)(depth - 4) / 50.0, 0.0, 1.0);

        double n = fbm(x * 0.016, z * 0.016, 3, 2.0, 0.5)
                + fbm(x * 0.08, z * 0.08, 2, 2.0, 0.5) * 0.2;

        double th = 0.7 - dn * 0.2;
        BlockState base = n > th ? Blocks.GRAVEL.defaultBlockState() : Blocks.SAND.defaultBlockState();

        double det = hsh(x * 7, z * 13);
        if (base.is(Blocks.GRAVEL) && det < 0.03) return Blocks.COBBLESTONE.defaultBlockState();
        if (base.is(Blocks.SAND) && det < 0.02) return Blocks.CLAY.defaultBlockState();

        return base;
    }

    private BlockState subSurf(int x, int z, int fl, double sd) {
        if (sd >= 1.0 || fl < seaLevel) return null;
        int above = fl - seaLevel;
        if (above <= 2) return Blocks.SAND.defaultBlockState();
        double bb = Mth.clamp((sd - 0.55) / 0.35, 0.0, 1.0);
        if (bb > 0.3) return Blocks.SAND.defaultBlockState();
        return Blocks.DIRT.defaultBlockState();
    }

    private BlockState slab(BlockState s) {
        if (s.is(Blocks.SAND)) return Blocks.SANDSTONE_SLAB.defaultBlockState();
        if (s.is(Blocks.GRAVEL) || s.is(Blocks.COBBLESTONE)) return Blocks.COBBLESTONE_SLAB.defaultBlockState();
        return Blocks.STONE_SLAB.defaultBlockState();
    }

    private boolean hasGrass(int x, int z, int fl) {
        int depth = seaLevel - fl;
        if (depth < 2 || depth > 40) return false;

        double ch;
        if (depth <= 5) ch = 0.7;
        else if (depth <= 10) ch = 0.5;
        else if (depth <= 20) ch = 0.3;
        else if (depth <= 30) ch = 0.1;
        else ch = 0.04;

        return fbm(x * 0.08, z * 0.08, 2, 2.0, 0.5) > 0.3 && hsh(x * 31, z * 37) < ch;
    }

    private boolean needsSlab(int x, int z) {
        int h = flr(x, z);
        return h < flr(x, z - 1) || h < flr(x, z + 1)
                || h < flr(x + 1, z) || h < flr(x - 1, z);
    }

    private boolean isIsland(int x, int z) {
        int cellX = Math.floorDiv(x, 2048), cellZ = Math.floorDiv(z, 2048);
        if (hsh(cellX * 11, cellZ * 13) > 0.6) return false;

        int cx = cellX * 2048 + 768 + (int)(hsh(cellX * 2, cellZ * 2) * 512);
        int cz = cellZ * 2048 + 768 + (int)(hsh(cellX * 2 + 1, cellZ * 2 + 1) * 512);
        return x == cx && z == cz;
    }

    // островная зона — возвращает [0,1], >1 = за пределами острова
    private double islandDist(int x, int z) {
        double dist = Math.sqrt((double) x * x + (double) z * z);

        double warpLarge = fbm(x * 0.004 + 7.3, z * 0.004 + 2.1, 4, 2.0, 0.55) * 55
                         + fbm(x * 0.004 + 91.7, z * 0.004 + 53.4, 3, 2.0, 0.5) * 35;

        double warpMid = fbm(x * 0.012 + 17.9, z * 0.012 + 83.1, 3, 2.0, 0.45) * 25
                       + fbm(x * 0.018 + 44.2, z * 0.018 + 11.6, 3, 2.0, 0.4) * 15;

        double warpFine = fbm(x * 0.04 + 33.5, z * 0.04 + 67.8, 2, 2.0, 0.38) * 8
                        + fbm(x * 0.07 + 5.5, z * 0.07 + 99.2, 2, 2.0, 0.35) * 4;

        double d = dist - (warpLarge + warpMid + warpFine);
        return Mth.clamp(d / SPAWN_ISLAND_RADIUS, 0.0, 1.0 + SPAWN_ISLAND_FEATHER / SPAWN_ISLAND_RADIUS);
    }

    private int islandH(int x, int z) {
        double t = islandDist(x, z);
        double maxT = 1.0 + SPAWN_ISLAND_FEATHER / SPAWN_ISLAND_RADIUS;
        if (t >= maxT) return 0;

        double edge = Mth.clamp(1.0 - t / maxT, 0.0, 1.0);
        double falloff = edge * edge * (3.0 - 2.0 * edge);

        if (falloff < 0.05) return 0;

        double hillLarge = (fbm(x * 0.006 + 13.0, z * 0.006 + 77.0, 4, 2.0, 0.5) - 0.3) * 1.4;
        hillLarge = Math.max(0, hillLarge);

        double hillMid = fbm(x * 0.018 + 55.0, z * 0.018 + 31.0, 3, 2.0, 0.48) * 0.5;

        double detail = fbm(x * 0.05 + 99.0, z * 0.05 + 12.0, 2, 2.0, 0.4) * 0.15;

        double raw = (hillLarge + hillMid + detail) * falloff * SPAWN_ISLAND_MAX_HEIGHT;
        return Math.max(1, (int) Math.round(raw));
    }

    @Override
    public CompletableFuture<ChunkAccess> fillFromNoise(Blender blender, RandomState randomState,
                                                         StructureManager structureManager, ChunkAccess chunk) {
        ChunkPos cp = chunk.getPos();
        int cx = cp.x, cz = cp.z;

        int slabCnt = 0, surfCnt = 0, grassCnt = 0;

        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int wx = cx * 16 + lx, wz = cz * 16 + lz;
                int fl = flr(wx, wz);
                boolean onSlope = needsSlab(wx, wz);
                boolean isCenter = isIsland(wx, wz);
                double st = islandDist(wx, wz);

                if (isCenter)
                    log.info("[Island] center at ({}, {})", wx, wz);

                int dirtLayers = 0;
                if (st < 1.0 && fl >= seaLevel) {
                    double bb = Mth.clamp((st - 0.55) / 0.35, 0.0, 1.0);
                    if (bb <= 0.3) dirtLayers = 3;
                    else if (bb <= 0.6) dirtLayers = 1;
                }

                int skip = 0;

                for (int y = chunk.getMinBuildHeight(); y < chunk.getMaxBuildHeight(); y++) {
                    if (skip > 0) { skip--; continue; }

                    BlockPos at = new BlockPos(wx, y, wz);

                    if (y == chunk.getMinBuildHeight()) {
                        chunk.setBlockState(at, Blocks.BEDROCK.defaultBlockState(), false);

                    } else if (y < fl - dirtLayers) {
                        chunk.setBlockState(at, Blocks.STONE.defaultBlockState(), false);

                    } else if (y < fl) {
                        BlockState sub = subSurf(wx, wz, fl, st);
                        chunk.setBlockState(at, sub != null ? sub : Blocks.STONE.defaultBlockState(), false);

                    } else if (y == fl) {
                        chunk.setBlockState(at, pickSurf(wx, wz, fl, st), false);
                        surfCnt++;

                    } else if (y == fl + 1) {
                        if (fl >= seaLevel) {
                        } else if (onSlope) {
                            BlockState sl = slab(pickSurf(wx, wz, fl, st))
                                    .setValue(SlabBlock.TYPE, SlabType.BOTTOM)
                                    .setValue(SlabBlock.WATERLOGGED, true);
                            chunk.setBlockState(at, sl, false);
                            slabCnt++;

                        } else if (hasGrass(wx, wz, fl)) {
                            boolean tall = hsh(wx * 17, wz * 23) < 0.3;
                            if (tall) {
                                chunk.setBlockState(at, Blocks.TALL_SEAGRASS.defaultBlockState()
                                        .setValue(TallSeagrassBlock.HALF, net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER), false);
                                chunk.setBlockState(new BlockPos(wx, y + 1, wz), Blocks.TALL_SEAGRASS.defaultBlockState()
                                        .setValue(TallSeagrassBlock.HALF, net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER), false);
                                skip = 1;
                            } else {
                                chunk.setBlockState(at, Blocks.SEAGRASS.defaultBlockState(), false);
                            }
                            grassCnt++;

                        } else {
                            chunk.setBlockState(at, Blocks.WATER.defaultBlockState(), false);
                        }

                    } else if (y < seaLevel && fl < seaLevel) {
                        chunk.setBlockState(at, Blocks.WATER.defaultBlockState(), false);
                    }
                }
            }
        }

        return CompletableFuture.completedFuture(chunk);
    }

    @Override
    public void buildSurface(WorldGenRegion region, StructureManager structureManager,
                             RandomState randomState, ChunkAccess chunk) {}

    @Override
    public void applyCarvers(WorldGenRegion region, long seed, RandomState randomState,
                             BiomeManager biomeManager, StructureManager structureManager,
                             ChunkAccess chunk, GenerationStep.Carving step) {}

    @Override
    public void applyBiomeDecoration(WorldGenLevel level, ChunkAccess chunk, StructureManager structureManager) {
        ChunkPos cp = chunk.getPos();
        int cx = cp.x, cz = cp.z;

        for (int lx = 2; lx < 14; lx++) {
            for (int lz = 2; lz < 14; lz++) {
                int wx = cx * 16 + lx, wz = cz * 16 + lz;
                int fl = flr(wx, wz);
                if (fl < seaLevel) continue;
                double st = islandDist(wx, wz);
                if (st >= 1.0) continue;
                if (hsh(wx * 41, wz * 43) > 0.003) continue;

                PalmGenerator.tryPlacePalm(level, wx, fl + 1, wz, hsh(wx * 53, wz * 59));
            }
        }
    }

    @Override
    public void spawnOriginalMobs(WorldGenRegion region) {}

    @Override
    public int getGenDepth() { return 384; }

    @Override
    public int getSeaLevel() { return seaLevel; }

    @Override
    public int getMinY() { return -64; }

    @Override
    public int getBaseHeight(int x, int z, Heightmap.Types heightmapType,
                             LevelHeightAccessor level, RandomState random) {
        int fl = flr(x, z);
        return needsSlab(x, z) ? fl + 2 : fl + 1;
    }

    @Override
    public NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor level, RandomState random) {
        int fl = flr(x, z);
        boolean onSlope = needsSlab(x, z);
        double st = islandDist(x, z);

        int dirtLayers = 0;
        if (st < 1.0 && fl >= seaLevel) {
            double beachBlend = Mth.clamp((st - 0.55) / 0.35, 0.0, 1.0);
            if (beachBlend <= 0.3) dirtLayers = 3;
            else if (beachBlend <= 0.6) dirtLayers = 1;
        }

        int minY = level.getMinBuildHeight(), maxY = level.getMaxBuildHeight();
        BlockState[] col = new BlockState[maxY - minY];

        int skip = 0;

        for (int y = minY; y < maxY; y++) {
            if (skip > 0) { skip--; continue; }

            if (y == minY) {
                col[y - minY] = Blocks.BEDROCK.defaultBlockState();

            } else if (y < fl - dirtLayers) {
                col[y - minY] = Blocks.STONE.defaultBlockState();

            } else if (y < fl) {
                BlockState sub = subSurf(x, z, fl, st);
                col[y - minY] = sub != null ? sub : Blocks.STONE.defaultBlockState();

            } else if (y == fl) {
                col[y - minY] = pickSurf(x, z, fl, st);

            } else if (y == fl + 1) {
                if (fl >= seaLevel) {
                    col[y - minY] = Blocks.AIR.defaultBlockState();
                } else if (onSlope) {
                    col[y - minY] = slab(pickSurf(x, z, fl, st))
                            .setValue(SlabBlock.TYPE, SlabType.BOTTOM)
                            .setValue(SlabBlock.WATERLOGGED, true);
                } else if (hasGrass(x, z, fl)) {
                    if (hsh(x * 17, z * 23) < 0.3) {
                        col[y - minY] = Blocks.TALL_SEAGRASS.defaultBlockState()
                                .setValue(TallSeagrassBlock.HALF, net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER);
                        if (y + 1 < maxY)
                            col[y + 1 - minY] = Blocks.TALL_SEAGRASS.defaultBlockState()
                                    .setValue(TallSeagrassBlock.HALF, net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER);
                        skip = 1;
                    } else {
                        col[y - minY] = Blocks.SEAGRASS.defaultBlockState();
                    }
                } else {
                    col[y - minY] = Blocks.WATER.defaultBlockState();
                }

            } else if (y < seaLevel && fl < seaLevel) {
                col[y - minY] = Blocks.WATER.defaultBlockState();
            } else {
                col[y - minY] = Blocks.AIR.defaultBlockState();
            }
        }
        return new NoiseColumn(minY, col);
    }

    @Override
    public void addDebugScreenInfo(List<String> info, RandomState random, BlockPos pos) {
        info.add("OceanChunkGenerator");
        info.add("seaLevel=" + seaLevel + " seed=" + seed);
    }
}