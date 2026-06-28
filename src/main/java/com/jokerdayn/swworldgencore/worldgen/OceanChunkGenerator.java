package com.jokerdayn.swworldgencore.worldgen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
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

    private static final int[][] GRAD2 = {
        {1,0},{-1,0},{0,1},{0,-1},{1,1},{-1,1},{1,-1},{-1,-1}
    };

    private double dot2(int[] g, double x, double z) {
        return g[0] * x + g[1] * z;
    }

    private int gradHash(int x, int z) {
        long n = (long) x * 73856093L ^ (long) z * 19349663L ^ seed;
        n = (n ^ (n >> 13)) * 1274126177L;
        return (int) (n ^ (n >> 16)) & 7;
    }

    // Perlin gradient noise 2D
    private double pnoise(double x, double z) {
        int xi = (int) Math.floor(x), zi = (int) Math.floor(z);
        double xf = x - xi, zf = z - zi;

        double u = xf * xf * xf * (xf * (xf * 6 - 15) + 10);
        double v = zf * zf * zf * (zf * (zf * 6 - 15) + 10);

        int g00 = gradHash(xi, zi);
        int g10 = gradHash(xi + 1, zi);
        int g01 = gradHash(xi, zi + 1);
        int g11 = gradHash(xi + 1, zi + 1);

        double n00 = dot2(GRAD2[g00], xf, zf);
        double n10 = dot2(GRAD2[g10], xf - 1, zf);
        double n01 = dot2(GRAD2[g01], xf, zf - 1);
        double n11 = dot2(GRAD2[g11], xf - 1, zf - 1);

        double x0 = n00 + u * (n10 - n00);
        double x1 = n01 + u * (n11 - n01);
        return (x0 + v * (x1 - x0)) * 0.7071 + 0.5;
    }

    private double fbm(double x, double z, int oct, double lac, double g) {
        double val = 0, amp = 1, freq = 1, max = 0;
        for (int i = 0; i < oct; i++) {
            val += amp * pnoise(x * freq, z * freq);
            max += amp;
            amp *= g;
            freq *= lac;
        }
        return val / max;
    }

    // ridge noise — острые гребни вместо куполов
    private double ridgeNoise(double x, double z, int oct, double lac, double g) {
        double val = 0, amp = 1, freq = 1, max = 0;
        double prev = 1.0;
        for (int i = 0; i < oct; i++) {
            double n = 1.0 - Math.abs(pnoise(x * freq, z * freq) * 2 - 1);
            n = n * n * prev;
            prev = n;
            val += amp * n;
            max += amp;
            amp *= g;
            freq *= lac;
        }
        return val / max;
    }

    // береговая линия — domain warp + вычитание из расстояния
    private double islandDist(int x, int z) {
        double dist = Math.sqrt((double) x * x + (double) z * z);

        // варп координат для заливов
        double wx1 = x + fbm(x * 0.004 + 7.3, z * 0.004 + 2.1, 4, 2.0, 0.55) * 70;
        double wz1 = z + fbm(x * 0.004 + 91.7, z * 0.004 + 53.4, 4, 2.0, 0.55) * 70;

        double wx2 = wx1 + fbm(wx1 * 0.012 + 17.9, wz1 * 0.012 + 83.1, 3, 2.0, 0.45) * 30;
        double wz2 = wz1 + fbm(wx1 * 0.012 + 44.2, wz1 * 0.012 + 11.6, 3, 2.0, 0.45) * 30;

        double wx3 = wx2 + fbm(wx2 * 0.04 + 33.5, wz2 * 0.04 + 67.8, 2, 2.0, 0.4) * 10;
        double wz3 = wz2 + fbm(wx2 * 0.04 + 5.5, wz2 * 0.04 + 99.2, 2, 2.0, 0.4) * 10;

        // компенсация: варп увеличивает dist на ~90 блоков в среднем — вычитаем
        double warpX = wx3 - x, warpZ = wz3 - z;
        double warpShift = (warpX * x + warpZ * z) / (dist + 0.001);
        dist = dist - warpShift - 65;

        return Mth.clamp(dist / SPAWN_ISLAND_RADIUS, 0.0, 1.0 + SPAWN_ISLAND_FEATHER / SPAWN_ISLAND_RADIUS);
    }

    // горные константы
    private static final double MTN_CENTER_Z = -60.0;
    private static final double MTN_RX = 95.0;
    private static final double MTN_RZ = 50.0;
    private static final int MTN_HEIGHT = 80;

    // высота острова — принимает уже вычисленный t = islandDist
    private int islandH(int x, int z, double t) {
        double maxT = 1.0 + SPAWN_ISLAND_FEATHER / SPAWN_ISLAND_RADIUS;
        if (t >= maxT) return 0;

        double edge = Mth.clamp(1.0 - t / maxT, 0.0, 1.0);
        double falloff = edge * edge * (3.0 - 2.0 * edge);
        if (falloff < 0.05) return 0;

        // холмы — smoothstep вместо hard clamp
        double rawHill = fbm(x * 0.006 + 13.0, z * 0.006 + 77.0, 3, 2.0, 0.55);
        double hillLarge = Mth.clamp((rawHill - 0.2) / 0.7, 0.0, 1.0);
        hillLarge = hillLarge * hillLarge * (3.0 - 2.0 * hillLarge);
        double hillMid = fbm(x * 0.018 + 55.0, z * 0.018 + 31.0, 2, 2.0, 0.45) * 0.3;
        double raw = (hillLarge + hillMid) * falloff * SPAWN_ISLAND_MAX_HEIGHT;
        int base = Math.max(1, (int) Math.round(raw));

        // гора — ridge noise + domain warp формы
        double wx = x + fbm(x * 0.015 + 101.3, z * 0.015 + 57.9, 3, 2.0, 0.5) * 40;
        double wz = z + fbm(x * 0.015 + 33.7, z * 0.015 + 88.2, 3, 2.0, 0.5) * 30;

        double ex = wx / MTN_RX;
        double ez = (wz + MTN_CENTER_Z) / MTN_RZ;
        double eDist = Math.sqrt(ex * ex + ez * ez);
        if (eDist > 1.3) return base;

        double eMask = Mth.clamp(1.0 - eDist / 1.3, 0.0, 1.0);
        eMask = eMask * eMask * (3.0 - 2.0 * eMask);

        double ridge = ridgeNoise(wx * 0.018 + 200.0, wz * 0.022 + 150.0, 5, 2.0, 0.55);
        ridge = Math.pow(ridge, 0.6);
        double mtnDetail = fbm(wx * 0.06 + 300.0, wz * 0.06 + 250.0, 3, 2.0, 0.45) * 0.3;
        double combined = ridge * 0.75 + mtnDetail * 0.25;

        int mtn = (int) Math.round(MTN_HEIGHT * combined * eMask * falloff);
        return base + Math.max(0, mtn);
    }

    // высота дна — принимает готовый sp
    private int flr(int x, int z, int sp) {
        double wx = x + fbm(x * 0.005, z * 0.005, 2, 2.0, 0.5) * 50;
        double wz = z + fbm(x * 0.005 + 31.7, z * 0.005 + 47.3, 2, 2.0, 0.5) * 50;

        double h = BASE_FLOOR
                + fbm(wx * 0.004, wz * 0.004, 4, 2.0, 0.55) * 30
                + fbm(wx * 0.016, wz * 0.016, 2, 2.0, 0.45) * 4
                + fbm(wx * 0.05, wz * 0.05, 2, 2.0, 0.4) * 1.5;

        int floor = Math.max(-63, Math.min((int) h, seaLevel - 4));
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

    private boolean isIsland(int x, int z) {
        int cellX = Math.floorDiv(x, 2048), cellZ = Math.floorDiv(z, 2048);
        if (hsh(cellX * 11, cellZ * 13) > 0.6) return false;

        int cx = cellX * 2048 + 768 + (int)(hsh(cellX * 2, cellZ * 2) * 512);
        int cz = cellZ * 2048 + 768 + (int)(hsh(cellX * 2 + 1, cellZ * 2 + 1) * 512);
        return x == cx && z == cz;
    }

    @Override
    public CompletableFuture<ChunkAccess> fillFromNoise(Blender blender, RandomState randomState,
                                                         StructureManager structureManager, ChunkAccess chunk) {
        ChunkPos cp = chunk.getPos();
        int cx = cp.x, cz = cp.z;

        // кэш высот: 18x18 (чанк 16x16 + по 1 блоку с каждой стороны для проверки склонов)
        int[][] heights = new int[18][18];
        double[][] dists = new double[18][18];
        int[][] islands = new int[18][18];

        for (int lx = -1; lx <= 16; lx++) {
            for (int lz = -1; lz <= 16; lz++) {
                int wx = cx * 16 + lx, wz = cz * 16 + lz;
                double st = islandDist(wx, wz);
                dists[lx + 1][lz + 1] = st;
                int sp = islandH(wx, wz, st);
                islands[lx + 1][lz + 1] = sp;
                heights[lx + 1][lz + 1] = flr(wx, wz, sp);
            }
        }

        int slabCnt = 0, surfCnt = 0, grassCnt = 0;

        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int wx = cx * 16 + lx, wz = cz * 16 + lz;
                int fl = heights[lx + 1][lz + 1];
                double st = dists[lx + 1][lz + 1];
                int sp = islands[lx + 1][lz + 1];

                boolean onSlope = fl < heights[lx][lz + 1] || fl < heights[lx + 2][lz + 1]
                               || fl < heights[lx + 1][lz] || fl < heights[lx + 1][lz + 2];

                boolean isCenter = isIsland(wx, wz);

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

    private static final java.util.Set<Long> placedTrees = java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<Long, Boolean>());

    private static long treeKey(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    private static boolean nearTree(WorldGenLevel level, int x, int y, int z, int radius) {
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx * dx + dz * dz > radius * radius) continue;
                BlockPos check = new BlockPos(x + dx, y, z + dz);
                if (level.getBlockState(check).is(Blocks.OAK_WOOD)) return true;
            }
        }
        return false;
    }

    @Override
    public void applyBiomeDecoration(WorldGenLevel level, ChunkAccess chunk, StructureManager structureManager) {
        ChunkPos cp = chunk.getPos();
        int cx = cp.x, cz = cp.z;

        for (int lx = 2; lx < 14; lx++) {
            for (int lz = 2; lz < 14; lz++) {
                int wx = cx * 16 + lx, wz = cz * 16 + lz;
                double st = islandDist(wx, wz);
                int sp = islandH(wx, wz, st);
                int fl = flr(wx, wz, sp);
                if (fl < seaLevel) continue;
                if (st >= 1.0) continue;

                BlockPos surface = new BlockPos(wx, fl, wz);
                boolean onSand = level.getBlockState(surface).is(Blocks.SAND);
                if (onSand && hsh(wx * 41, wz * 43) < 0.003) {
                    PalmGenerator.tryPlacePalm(level, wx, fl + 1, wz, hsh(wx * 53, wz * 59));
                    continue;
                }

                if (!level.getBlockState(surface).is(Blocks.GRASS_BLOCK)) continue;

                double r = hsh(wx * 17, wz * 29);
                BlockPos above = new BlockPos(wx, fl + 1, wz);

                if (r < 0.04) {
                    if (level.getBlockState(above).is(Blocks.AIR) && !nearTree(level, wx, fl + 1, wz, 5)) {
                        RainTreeGenerator.tryPlace(level, wx, fl, wz, hsh(wx * 53, wz * 67));
                        placedTrees.add(treeKey(wx, wz));
                    }
                }
                else if (r < 0.45) {
                    if (level.getBlockState(above).is(Blocks.AIR)) {
                        level.setBlock(above, Blocks.SHORT_GRASS.defaultBlockState(), 2);
                    }
                }
                else if (r < 0.49) {
                    if (level.getBlockState(above).is(Blocks.AIR)) {
                        double flr = hsh(wx * 23, wz * 37);
                        BlockState flower;
                        if (flr < 0.4) flower = Blocks.POPPY.defaultBlockState();
                        else if (flr < 0.7) flower = Blocks.DANDELION.defaultBlockState();
                        else flower = Blocks.OXEYE_DAISY.defaultBlockState();
                        level.setBlock(above, flower, 2);
                    }
                }
                else if (r < 0.52) {
                    if (level.getBlockState(above).is(Blocks.AIR)) {
                        level.setBlock(above, Blocks.DEAD_BUSH.defaultBlockState(), 2);
                    }
                }
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
        double st = islandDist(x, z);
        int sp = islandH(x, z, st);
        int fl = flr(x, z, sp);
        boolean onSlope = fl < flr(x, z - 1, islandH(x, z - 1, islandDist(x, z - 1)))
                       || fl < flr(x, z + 1, islandH(x, z + 1, islandDist(x, z + 1)))
                       || fl < flr(x + 1, z, islandH(x + 1, z, islandDist(x + 1, z)))
                       || fl < flr(x - 1, z, islandH(x - 1, z, islandDist(x - 1, z)));
        return onSlope ? fl + 2 : fl + 1;
    }

    @Override
    public NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor level, RandomState random) {
        double st = islandDist(x, z);
        int sp = islandH(x, z, st);
        int fl = flr(x, z, sp);

        boolean onSlope = fl < flr(x, z - 1, islandH(x, z - 1, islandDist(x, z - 1)))
                       || fl < flr(x, z + 1, islandH(x, z + 1, islandDist(x, z + 1)))
                       || fl < flr(x + 1, z, islandH(x + 1, z, islandDist(x + 1, z)))
                       || fl < flr(x - 1, z, islandH(x - 1, z, islandDist(x - 1, z)));

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
