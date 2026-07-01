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
import com.jokerdayn.swworldgencore.SWWorldgenCore;
import com.jokerdayn.swworldgencore.block.ShellBlock;
import com.jokerdayn.swworldgencore.block.GroundDecorationBlock;
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

    // Бенчмарк: счётчик чанков и суммарное время генерации
    private static long chunkCount = 0;
    private static long totalGenTimeNs = 0;
    private static long lastLogTime = 0;

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
    }

    public long getSeed() {
        return seed;
    }

    @Override
    protected MapCodec<? extends ChunkGenerator> codec() {
        return CODEC;
    }

    // -------------------------------------------------------------------------
    // Шумовые утилиты
    // -------------------------------------------------------------------------

    private long rawHash(int x, int z) {
        long n = (long) x * 73856093L ^ (long) z * 19349663L ^ seed;
        n = (n ^ (n >> 13)) * 1274126177L;
        return n ^ (n >> 16);
    }

    private double hsh(int x, int z) {
        return (double) (rawHash(x, z) & 0x7FFFFFFFL) / (double) 0x7FFFFFFFL;
    }

    private int gradHash(int x, int z) {
        return (int) rawHash(x, z) & 7;
    }

    private static final int[][] GRAD2 = {
        {1, 0}, {-1, 0}, {0, 1}, {0, -1},
        {1, 1}, {-1, 1}, {1, -1}, {-1, -1}
    };

    private double dot2(int[] g, double x, double z) {
        return g[0] * x + g[1] * z;
    }

    private double pnoise(double x, double z) {
        int xi = (int) Math.floor(x), zi = (int) Math.floor(z);
        double xf = x - xi, zf = z - zi;

        double u = xf * xf * xf * (xf * (xf * 6 - 15) + 10);
        double v = zf * zf * zf * (zf * (zf * 6 - 15) + 10);

        int g00 = gradHash(xi,     zi    );
        int g10 = gradHash(xi + 1, zi    );
        int g01 = gradHash(xi,     zi + 1);
        int g11 = gradHash(xi + 1, zi + 1);

        double n00 = dot2(GRAD2[g00], xf,     zf    );
        double n10 = dot2(GRAD2[g10], xf - 1, zf    );
        double n01 = dot2(GRAD2[g01], xf,     zf - 1);
        double n11 = dot2(GRAD2[g11], xf - 1, zf - 1);

        double x0 = n00 + u * (n10 - n00);
        double x1 = n01 + u * (n11 - n01);
        return Mth.clamp((x0 + v * (x1 - x0)) * 0.7071 + 0.5, 0.0, 1.0);
    }

    private double fbm(double x, double z, int oct, double lac, double g) {
        double val = 0, amp = 1, freq = 1, max = 0;
        for (int i = 0; i < oct; i++) {
            val += amp * pnoise(x * freq, z * freq);
            max += amp;
            amp  *= g;
            freq *= lac;
        }
        return val / max;
    }

    private double ridgeNoise(double x, double z, int oct, double lac, double g) {
        double val = 0, amp = 1, freq = 1, max = 0;
        double prev = 1.0;
        for (int i = 0; i < oct; i++) {
            double n = 1.0 - Math.abs(pnoise(x * freq, z * freq) * 2 - 1);
            n    = Mth.clamp(n * n * prev, 0.0, 1.0);
            prev = n;
            val += amp * n;
            max += amp;
            amp  *= g;
            freq *= lac;
        }
        return val / max;
    }

    // -------------------------------------------------------------------------
    // Grid-острова: параметры ячейки
    // -------------------------------------------------------------------------

    private static final int CELL = 2048;
    private static final int CELL_HALF = CELL / 2;

    // расстояние от (x,z) до ближайшего grid-острова (нормализовано 0..1)
    // 0 = центр острова, 1 = край, >1 = за пределами
    private double gridIslandDist(int x, int z) {
        int cellX = Math.floorDiv(x, CELL);
        int cellZ = Math.floorDiv(z, CELL);

        double bestDistSq = Double.MAX_VALUE;
        double bestRadius = 0;

        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                int cx = cellX + dx, cz = cellZ + dz;
                if (hsh(cx * 11, cz * 13) > 0.6) continue;

                // Инлайн gridCellParams — без аллокации double[]
                double rHash = hsh(cx * 11, cz * 13);
                double radius = 80 + rHash * 120;

                int ix = cx * CELL + 768 + (int)(hsh(cx * 2, cz * 2) * 512);
                int iz = cz * CELL + 768 + (int)(hsh(cx * 2 + 1, cz * 2 + 1) * 512);

                double dxSq = (double)(x - ix) * (x - ix);
                double dzSq = (double)(z - iz) * (z - iz);
                double dSq = dxSq + dzSq;
                if (dSq < bestDistSq) {
                    bestDistSq = dSq;
                    bestRadius = radius;
                }
            }
        }

        if (bestRadius < 1) return 2.0;
        return Math.sqrt(bestDistSq) / bestRadius;
    }

    // высота grid-острова (над oceanFloor)
    private double gridIslandH(int x, int z) {
        int cellX = Math.floorDiv(x, CELL);
        int cellZ = Math.floorDiv(z, CELL);

        double bestVal = 0;
        double bestDistSq = Double.MAX_VALUE;

        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                int cx = cellX + dx, cz = cellZ + dz;
                if (hsh(cx * 11, cz * 13) > 0.6) continue;

                // Инлайн gridCellParams — без аллокации double[]
                double rHash = hsh(cx * 11, cz * 13);
                double hHash = hsh(cx * 17, cz * 19);
                double mHash = hsh(cx * 23, cz * 29);
                double radius = 80 + rHash * 120;
                double maxHeight = 4 + hHash * 16;
                double mtnChance = mHash;

                int ix = cx * CELL + 768 + (int)(hsh(cx * 2, cz * 2) * 512);
                int iz = cz * CELL + 768 + (int)(hsh(cx * 2 + 1, cz * 2 + 1) * 512);

                double dxSq = (double)(x - ix) * (x - ix);
                double dzSq = (double)(z - iz) * (z - iz);
                double dSq = dxSq + dzSq;
                double radiusLimSq = radius * 1.3;
                if (dSq > radiusLimSq * radiusLimSq) continue;

                double d = Math.sqrt(dSq);
                double t = d / radius;
                if (t >= 1.0) continue;

                // edge falloff — smoothstep
                double edge = Mth.clamp(1.0 - t, 0.0, 1.0);
                double falloff = edge * edge * (3.0 - 2.0 * edge);

                // холмистый рельеф
                double hill = fbm(x * 0.008 + cx * 31.0, z * 0.008 + cz * 47.0, 3, 2.0, 0.55);
                hill = Mth.clamp((hill - 0.15) / 0.7, 0.0, 1.0);
                hill = hill * hill * (3.0 - 2.0 * hill);

                double h = hill * maxHeight * falloff;

                // гора (ridgeline) для некоторых ячеек
                if (mtnChance < 0.25) {
                    double mtnR = ridgeNoise(x * 0.012 + cx * 53.0, z * 0.014 + cz * 67.0, 4, 2.0, 0.55);
                    mtnR = Math.pow(mtnR, 0.5);
                    double mtnMask = Mth.clamp(1.0 - d / (radius * 0.6), 0.0, 1.0);
                    mtnMask = mtnMask * mtnMask;
                    h += maxHeight * 2.5 * mtnR * mtnMask * falloff;
                }

                if (dSq < bestDistSq) {
                    bestDistSq = dSq;
                    bestVal = h;
                }
            }
        }

        return bestVal;
    }

    // -------------------------------------------------------------------------
    // Форма острова (спавн)
    // -------------------------------------------------------------------------

    private double islandDist(int x, int z) {
        double dist = Math.sqrt((double) x * x + (double) z * z);

        // seed-dependent offsets — каждый мир получает уникальную форму береговой линии
        double s = seed * 0.001;

        double wx1 = x + fbm(x * 0.004 + 7.3 + s,     z * 0.004 + 2.1 + s * 0.7,  4, 2.0, 0.55) * 70;
        double wz1 = z + fbm(x * 0.004 + 91.7 - s * 1.3, z * 0.004 + 53.4 + s * 0.5, 4, 2.0, 0.55) * 70;

        double wx2 = wx1 + fbm(wx1 * 0.012 + 17.9 + s * 2.1, wz1 * 0.012 + 83.1 - s * 0.9, 3, 2.0, 0.45) * 30;
        double wz2 = wz1 + fbm(wx1 * 0.012 + 44.2 - s * 0.6, wz1 * 0.012 + 11.6 + s * 1.7, 3, 2.0, 0.45) * 30;

        double wx3 = wx2 + fbm(wx2 * 0.04 + 33.5 + s * 3.0, wz2 * 0.04 + 67.8 - s * 1.1, 2, 2.0, 0.4) * 10;
        double wz3 = wz2 + fbm(wx2 * 0.04 + 5.5 - s * 0.8,  wz2 * 0.04 + 99.2 + s * 2.5, 2, 2.0, 0.4) * 10;

        double warpX = wx3 - x, warpZ = wz3 - z;
        double warpShift = (warpX * x + warpZ * z) / (dist + 0.001);
        dist = dist - warpShift - 65;

        return Mth.clamp(dist / SPAWN_ISLAND_RADIUS, 0.0, 1.0 + SPAWN_ISLAND_FEATHER / SPAWN_ISLAND_RADIUS);
    }

    private static final double MTN_CENTER_Z = -60.0;
    private static final double MTN_RX       = 95.0;
    private static final double MTN_RZ       = 50.0;
    private static final int    MTN_HEIGHT   = 80;

    private double islandH(int x, int z, double t) {
        double maxT = 1.0 + SPAWN_ISLAND_FEATHER / SPAWN_ISLAND_RADIUS;
        if (t >= maxT) return 0.0;

        double edge    = Mth.clamp(1.0 - t / maxT, 0.0, 1.0);
        double falloff = edge * edge * (3.0 - 2.0 * edge);
        if (falloff < 0.001) return 0.0;

        double s = seed * 0.001;

        double rawHill  = fbm(x * 0.006 + 13.0 + s * 1.5, z * 0.006 + 77.0 - s * 0.8, 3, 2.0, 0.55);
        double hillLarge = Mth.clamp((rawHill - 0.2) / 0.7, 0.0, 1.0);
        hillLarge = hillLarge * hillLarge * (3.0 - 2.0 * hillLarge);
        double hillMid  = fbm(x * 0.018 + 55.0 - s * 2.0, z * 0.018 + 31.0 + s * 1.2, 2, 2.0, 0.45) * 0.3;
        double raw      = (hillLarge + hillMid) * falloff * SPAWN_ISLAND_MAX_HEIGHT;

        double wx = x + fbm(x * 0.015 + 101.3 + s * 3.0, z * 0.015 + 57.9 - s * 1.5, 3, 2.0, 0.5) * 40;
        double wz = z + fbm(x * 0.015 + 33.7 - s * 1.0,  z * 0.015 + 88.2 + s * 2.2, 3, 2.0, 0.5) * 30;

        // гора: центр смещается от seed
        double mtnX = wx / MTN_RX;
        double mtnZ = (wz + MTN_CENTER_Z + s * 30.0) / MTN_RZ;
        double eDist = Math.sqrt(mtnX * mtnX + mtnZ * mtnZ);
        if (eDist <= 1.3) {
            double eMask = Mth.clamp(1.0 - eDist / 1.3, 0.0, 1.0);
            eMask = eMask * eMask * (3.0 - 2.0 * eMask);

            double ridge     = Math.pow(ridgeNoise(wx * 0.018 + 200.0 + s * 5.0, wz * 0.022 + 150.0 - s * 3.0, 5, 2.0, 0.55), 0.6);
            double mtnDetail = fbm(wx * 0.06 + 300.0 - s * 4.0, wz * 0.06 + 250.0 + s * 2.0, 3, 2.0, 0.45) * 0.3;
            double combined  = ridge * 0.75 + mtnDetail * 0.25;

            raw += MTN_HEIGHT * combined * eMask * falloff;
        }

        return raw;
    }

    // -------------------------------------------------------------------------
    // Высота колонки
    // -------------------------------------------------------------------------

    private int computeFloor(int x, int z, double t, double spRaw) {
        double s = seed * 0.001;
        double wx = x + fbm(x * 0.005 + s * 1.0,        z * 0.005 - s * 0.5,        2, 2.0, 0.5) * 50;
        double wz = z + fbm(x * 0.005 + 31.7 - s * 0.8, z * 0.005 + 47.3 + s * 1.2, 2, 2.0, 0.5) * 50;

        double h = BASE_FLOOR
                + fbm(wx * 0.004 + s * 2.0, wz * 0.004 - s * 1.5, 4, 2.0, 0.55) * 30
                + fbm(wx * 0.016 - s * 0.7, wz * 0.016 + s * 0.9, 2, 2.0, 0.45) * 4
                + fbm(wx * 0.05 + s * 3.0,  wz * 0.05 - s * 2.0,  2, 2.0, 0.4 ) * 1.5;

        int oceanFloor = Math.max(-63, Math.min((int) h, seaLevel - 4));

        // --- спавн-остров ---
        double maxT = 1.0 + SPAWN_ISLAND_FEATHER / SPAWN_ISLAND_RADIUS;

        if (t < maxT) {
            int islandFloor = Math.max(oceanFloor, seaLevel + (int) Math.round(spRaw));
            if (t <= 1.0) return islandFloor;
            double norm = (t - 1.0) / (maxT - 1.0);
            double blend = norm * norm * (3.0 - 2.0 * norm);
            return (int) Math.round(islandFloor + (oceanFloor - islandFloor) * blend);
        }

        // --- grid-острова ---
        double gridH = gridIslandH(x, z);
        if (gridH > 0.5) {
            int gridFloor = Math.max(oceanFloor, seaLevel + (int) Math.round(gridH));
            return gridFloor;
        }

        return oceanFloor;
    }

    // -------------------------------------------------------------------------
    // Поверхностные блоки
    // -------------------------------------------------------------------------

    private BlockState pickSurf(int x, int z, int fl, double t, double spRaw, double gridH) {
        if (spRaw >= 4.0) return Blocks.GRASS_BLOCK.defaultBlockState();

        double maxT = 1.0 + SPAWN_ISLAND_FEATHER / SPAWN_ISLAND_RADIUS;
        if (t < maxT) {
            double norm = (t - 1.0) / (maxT - 1.0);
            double sandChance = 1.0 - norm;
            if (hsh(x * 7, z * 13) < sandChance) return Blocks.SAND.defaultBlockState();
            return oceanFloor(x, z, fl);
        }

        // grid-острова: высота > 4 = трава, иначе песок
        if (gridH >= 4.0) return Blocks.GRASS_BLOCK.defaultBlockState();
        if (gridH > 0.5) {
            if (hsh(x * 7, z * 13) < 0.7) return Blocks.SAND.defaultBlockState();
            return oceanFloor(x, z, fl);
        }

        return oceanFloor(x, z, fl);
    }

    private BlockState oceanFloor(int x, int z, int fl) {
        int    depth = seaLevel - fl;
        double dn    = Mth.clamp((double) (depth - 4) / 50.0, 0.0, 1.0);

        double n = fbm(x * 0.016, z * 0.016, 3, 2.0, 0.5)
                 + fbm(x * 0.08,  z * 0.08,  2, 2.0, 0.5) * 0.2;

        double     th   = 0.7 - dn * 0.2;
        BlockState base = n > th ? Blocks.GRAVEL.defaultBlockState()
                                 : Blocks.SAND.defaultBlockState();

        double det = hsh(x * 7, z * 13);
        if (base.is(Blocks.GRAVEL) && det < 0.03) return Blocks.COBBLESTONE.defaultBlockState();
        if (base.is(Blocks.SAND)   && det < 0.02) return Blocks.CLAY.defaultBlockState();

        return base;
    }

    private BlockState subSurf(int x, int z, int fl, double t, double spRaw, double gridH) {
        if (fl >= seaLevel) {
            boolean isIsland = spRaw >= 4.0 || gridH >= 4.0;
            return isIsland ? Blocks.DIRT.defaultBlockState() : Blocks.SAND.defaultBlockState();
        }
        double maxT = 1.0 + SPAWN_ISLAND_FEATHER / SPAWN_ISLAND_RADIUS;
        if (spRaw > 0.0 || t < maxT) {
            return hsh(x * 11, z * 17) < 0.6
                    ? Blocks.SAND.defaultBlockState()
                    : Blocks.GRAVEL.defaultBlockState();
        }
        // grid-острова под водой
        if (gridH > 0.5) {
            return hsh(x * 11, z * 17) < 0.6
                    ? Blocks.SAND.defaultBlockState()
                    : Blocks.GRAVEL.defaultBlockState();
        }
        return null;
    }

    private BlockState slab(BlockState s) {
        if (s.is(Blocks.SAND) || s.is(Blocks.CLAY))                        return Blocks.SANDSTONE_SLAB.defaultBlockState();
        if (s.is(Blocks.GRAVEL) || s.is(Blocks.COBBLESTONE))               return Blocks.COBBLESTONE_SLAB.defaultBlockState();
        return Blocks.STONE_SLAB.defaultBlockState();
    }

    // -------------------------------------------------------------------------
    // Морская трава
    // -------------------------------------------------------------------------

    private boolean hasGrass(int x, int z, int fl) {
        int depth = seaLevel - fl;
        if (depth < 2 || depth > 40) return false;

        double ch;
        if      (depth <= 5 ) ch = 0.7;
        else if (depth <= 10) ch = 0.5;
        else if (depth <= 20) ch = 0.3;
        else if (depth <= 30) ch = 0.1;
        else                  ch = 0.04;

        return fbm(x * 0.08, z * 0.08, 2, 2.0, 0.5) > 0.3
            && hsh(x * 31, z * 37) < ch;
    }

    // -------------------------------------------------------------------------
    // Генерация чанка
    // -------------------------------------------------------------------------

    @Override
    public CompletableFuture<ChunkAccess> fillFromNoise(Blender blender, RandomState randomState,
                                                        StructureManager structureManager, ChunkAccess chunk) {
        long startTime = System.nanoTime();

        ChunkPos cp = chunk.getPos();
        int cx = cp.x, cz = cp.z;

        // Кэш 18×18 (16 блоков чанка + 1 с каждой стороны для проверки склонов)
        int[][]    heights = new int[18][18];
        double[][] dists   = new double[18][18];
        double[][] islands = new double[18][18];

        for (int lx = -1; lx <= 16; lx++) {
            for (int lz = -1; lz <= 16; lz++) {
                int    wx    = cx * 16 + lx, wz = cz * 16 + lz;
                double st    = islandDist(wx, wz);
                double spRaw = islandH(wx, wz, st);
                dists  [lx + 1][lz + 1] = st;
                islands[lx + 1][lz + 1] = spRaw;
                heights[lx + 1][lz + 1] = computeFloor(wx, wz, st, spRaw);
            }
        }

        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();

        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int    wx    = cx * 16 + lx, wz = cz * 16 + lz;
                int    fl    = heights[lx + 1][lz + 1];
                double st    = dists  [lx + 1][lz + 1];
                double spRaw = islands[lx + 1][lz + 1];

                boolean onSlope = fl < heights[lx    ][lz + 1]
                               || fl < heights[lx + 2][lz + 1]
                               || fl < heights[lx + 1][lz    ]
                               || fl < heights[lx + 1][lz + 2];

                double maxT = 1.0 + SPAWN_ISLAND_FEATHER / SPAWN_ISLAND_RADIUS;
                double gridHi = gridIslandH(wx, wz);
                boolean onIsland = spRaw > 0 || gridHi > 0.5;
                int dirtLayers;
                if (fl >= seaLevel && onIsland) dirtLayers = 3;
                else if (st < maxT) dirtLayers = 2;
                else dirtLayers = 0;

                int skip = 0;

                for (int y = chunk.getMinBuildHeight(); y < chunk.getMaxBuildHeight(); y++) {
                    if (skip > 0) { skip--; continue; }

                    at.set(wx, y, wz);

                    if (y == chunk.getMinBuildHeight()) {
                        chunk.setBlockState(at, Blocks.BEDROCK.defaultBlockState(), false);

                    } else if (y < fl - dirtLayers) {
                        chunk.setBlockState(at, Blocks.STONE.defaultBlockState(), false);

                    } else if (y < fl) {
                        BlockState sub = subSurf(wx, wz, fl, st, spRaw, gridHi);
                        chunk.setBlockState(at, sub != null ? sub : Blocks.STONE.defaultBlockState(), false);

                    } else if (y == fl) {
                        chunk.setBlockState(at, pickSurf(wx, wz, fl, st, spRaw, gridHi), false);

                    } else if (y == fl + 1) {
                        if (fl >= seaLevel) {
                            // Над сушей — воздух, декорация добавится в applyBiomeDecoration
                        } else if (onSlope && onIsland) {
                            BlockState sl = slab(pickSurf(wx, wz, fl, st, spRaw, gridHi))
                                    .setValue(SlabBlock.TYPE,        SlabType.BOTTOM)
                                    .setValue(SlabBlock.WATERLOGGED, true);
                            chunk.setBlockState(at, sl, false);

                        } else if (hasGrass(wx, wz, fl)) {
                            boolean tall = hsh(wx * 17, wz * 23) < 0.3;
                            if (tall) {
                                chunk.setBlockState(at,
                                        Blocks.TALL_SEAGRASS.defaultBlockState()
                                              .setValue(TallSeagrassBlock.HALF,
                                                        net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER),
                                        false);
                                at.set(wx, y + 1, wz);
                                chunk.setBlockState(at,
                                        Blocks.TALL_SEAGRASS.defaultBlockState()
                                              .setValue(TallSeagrassBlock.HALF,
                                                        net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER),
                                        false);
                                skip = 1;
                            } else {
                                chunk.setBlockState(at, Blocks.SEAGRASS.defaultBlockState(), false);
                            }

                        } else {
                            chunk.setBlockState(at, Blocks.WATER.defaultBlockState(), false);
                        }

                    } else if (y < seaLevel && fl < seaLevel) {
                        chunk.setBlockState(at, Blocks.WATER.defaultBlockState(), false);
                    }
                }
            }
        }

        // Бенчмарк: логируем каждые 100 чанков
        long elapsed = System.nanoTime() - startTime;
        synchronized (OceanChunkGenerator.class) {
            chunkCount++;
            totalGenTimeNs += elapsed;
            long now = System.currentTimeMillis();
            if (now - lastLogTime > 30000) { // каждые 30 секунд
                double avgUs = (totalGenTimeNs / 1000.0) / chunkCount;
                double totalSec = totalGenTimeNs / 1_000_000_000.0;
                log.info("[Benchmark] chunks={} avg={}us/chunk total={}s",
                        chunkCount, String.format("%.1f", avgUs), String.format("%.2f", totalSec));
                lastLogTime = now;
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

    // -------------------------------------------------------------------------
    // Декорации биома (деревья, цветы, ракушки)
    // -------------------------------------------------------------------------

    private static boolean nearTree(WorldGenLevel level, int x, int y, int z, int radius) {
        BlockPos.MutableBlockPos check = new BlockPos.MutableBlockPos();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx * dx + dz * dz > radius * radius) continue;
                check.set(x + dx, y, z + dz);
                if (level.getBlockState(check).is(Blocks.OAK_WOOD)) return true;
            }
        }
        return false;
    }

    @Override
    public void applyBiomeDecoration(WorldGenLevel level, ChunkAccess chunk, StructureManager structureManager) {
        ChunkPos cp = chunk.getPos();
        int cx = cp.x, cz = cp.z;

        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int    wx    = cx * 16 + lx, wz = cz * 16 + lz;
                double st    = islandDist(wx, wz);
                double spRaw = islandH(wx, wz, st);
                double gridH = gridIslandH(wx, wz);
                int    fl    = computeFloor(wx, wz, st, spRaw);

                if (fl < seaLevel)  continue;
                boolean isIsland = spRaw > 0.0 || gridH > 0.5;
                if (!isIsland)     continue;

                BlockPos surface = new BlockPos(wx, fl, wz);
                boolean  onSand  = level.getBlockState(surface).is(Blocks.SAND);

                // Пальма на песке
                if (onSand && hsh(wx * 41, wz * 43) < 0.003) {
                    PalmGenerator.tryPlacePalm(level, wx, fl + 1, wz, hsh(wx * 53, wz * 59));
                    continue;
                }

                // Ракушка на песке
                if (onSand && hsh(wx * 31, wz * 37) < 0.06) {
                    BlockPos above = new BlockPos(wx, fl + 1, wz);
                    if (level.getBlockState(above).isAir()) {
                        ShellBlock.Variation[] vars = ShellBlock.Variation.values();
                        ShellBlock.Variation   v    = vars[(int) (hsh(wx * 79, wz * 83) * 3) % 3];
                        level.setBlock(above,
                                SWWorldgenCore.SHELL.get().defaultBlockState()
                                              .setValue(ShellBlock.VARIANT, v), 2);
                    }
                    continue;
                }

                // Наземная декорация на любом блоке суши
                if (hsh(wx * 61, wz * 67) < 0.02) {
                    BlockPos above = new BlockPos(wx, fl + 1, wz);
                    if (level.getBlockState(above).isAir()) {
                        GroundDecorationBlock.Type[] types = GroundDecorationBlock.Type.values();
                        GroundDecorationBlock.Type t = types[(int)(hsh(wx * 89, wz * 91) * types.length) % types.length];
                        level.setBlock(above,
                                SWWorldgenCore.GROUND_DECO.get().defaultBlockState()
                                              .setValue(GroundDecorationBlock.VARIANT, t), 2);
                    }
                }

                if (!level.getBlockState(surface).is(Blocks.GRASS_BLOCK)) continue;

                double   r     = hsh(wx * 17, wz * 29);
                BlockPos above = new BlockPos(wx, fl + 1, wz);

                if (r < 0.04) {
                    // Дерево
                    if (level.getBlockState(above).isAir()
                            && !nearTree(level, wx, fl + 1, wz, 5)) {
                        RainTreeGenerator.tryPlace(level, wx, fl, wz, hsh(wx * 53, wz * 67));
                    }
                } else if (r < 0.45) {
                    // Трава
                    if (level.getBlockState(above).isAir()) {
                        level.setBlock(above, Blocks.SHORT_GRASS.defaultBlockState(), 2);
                    }
                } else if (r < 0.49) {
                    // Цветок
                    if (level.getBlockState(above).isAir()) {
                        double     flr    = hsh(wx * 23, wz * 37);
                        BlockState flower;
                        if      (flr < 0.4) flower = Blocks.POPPY.defaultBlockState();
                        else if (flr < 0.7) flower = Blocks.DANDELION.defaultBlockState();
                        else                flower = Blocks.OXEYE_DAISY.defaultBlockState();
                        level.setBlock(above, flower, 2);
                    }
                } else if (r < 0.52) {
                    // Куст из листьев
                    if (level.getBlockState(above).isAir()) {
                        BlockState leaf  = Blocks.JUNGLE_LEAVES.defaultBlockState();
                        double     bushR = hsh(wx * 97, wz * 83);
                        int[][]    bush;
                        if (bushR < 0.33) {
                            bush = new int[][]{
                                {0,0,0},{1,0,0},{-1,0,0},{0,0,1},{0,0,-1},
                                {1,0,1},{-1,0,-1},{0,1,0},{1,1,0},{0,1,1}
                            };
                        } else if (bushR < 0.66) {
                            bush = new int[][]{
                                {0,0,0},{1,0,0},{-1,0,0},{0,0,1},{0,0,-1},
                                {1,0,-1},{-1,0,1},{0,1,0},{-1,1,0},{0,1,-1},{1,1,1}
                            };
                        } else {
                            bush = new int[][]{
                                {0,0,0},{2,0,0},{-1,0,0},{0,0,1},{0,0,-2},
                                {1,0,1},{-1,0,-1},{0,1,0},{1,1,0},{0,1,1},{-1,1,0},{0,2,0}
                            };
                        }
                        BlockPos.MutableBlockPos bp = new BlockPos.MutableBlockPos();
                        for (int[] b : bush) {
                            bp.set(wx + b[0], fl + b[1], wz + b[2]);
                            if (level.getBlockState(bp).isAir()) {
                                level.setBlock(bp, leaf, 2);
                            }
                        }
                    }
                }
            }
        }
    }

    @Override
    public void spawnOriginalMobs(WorldGenRegion region) {}

    // -------------------------------------------------------------------------
    // Технические методы
    // -------------------------------------------------------------------------

    @Override
    public int getGenDepth() { return 384; }

    @Override
    public int getSeaLevel() { return seaLevel; }

    @Override
    public int getMinY() { return -64; }

    @Override
    public int getBaseHeight(int x, int z, Heightmap.Types heightmapType,
                             LevelHeightAccessor level, RandomState random) {
        // Упрощено: без проверки склонов (экономит 4 вызова computeFloor на блок)
        // Слоупы обрабатываются в fillFromNoise через кэш 18x18
        double st    = islandDist(x, z);
        double spRaw = islandH(x, z, st);
        return computeFloor(x, z, st, spRaw) + 1;
    }

    @Override
    public NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor level, RandomState random) {
        double st    = islandDist(x, z);
        double spRaw = islandH(x, z, st);
        int    fl    = computeFloor(x, z, st, spRaw);

        double maxT = 1.0 + SPAWN_ISLAND_FEATHER / SPAWN_ISLAND_RADIUS;
        double gridHi = gridIslandH(x, z);
        boolean onIsl = spRaw > 0 || gridHi > 0.5;
        int dirtLayers;
        if (fl >= seaLevel && onIsl) dirtLayers = 3;
        else if (st < maxT) dirtLayers = 2;
        else dirtLayers = 0;

        int          minY = level.getMinBuildHeight();
        int          maxY = level.getMaxBuildHeight();
        BlockState[] col  = new BlockState[maxY - minY];

        // Слоупы не проверяются в getBaseColumn (оптимизация)
        // Они обрабатываются в fillFromNoise через кэш 18x18
        boolean onSlope = false;
        int skip = 0;

        for (int y = minY; y < maxY; y++) {
            if (skip > 0) {
                col[y - minY] = Blocks.AIR.defaultBlockState();
                skip--;
                continue;
            }

            if (y == minY) {
                col[y - minY] = Blocks.BEDROCK.defaultBlockState();

            } else if (y < fl - dirtLayers) {
                col[y - minY] = Blocks.STONE.defaultBlockState();

            } else if (y < fl) {
                BlockState sub = subSurf(x, z, fl, st, spRaw, gridHi);
                col[y - minY] = sub != null ? sub : Blocks.STONE.defaultBlockState();

            } else if (y == fl) {
                col[y - minY] = pickSurf(x, z, fl, st, spRaw, gridHi);

            } else if (y == fl + 1) {
                if (fl >= seaLevel) {
                    col[y - minY] = Blocks.AIR.defaultBlockState();
                } else if (onSlope && onIsl) {
                    col[y - minY] = slab(pickSurf(x, z, fl, st, spRaw, gridHi))
                            .setValue(SlabBlock.TYPE,        SlabType.BOTTOM)
                            .setValue(SlabBlock.WATERLOGGED, true);
                } else if (hasGrass(x, z, fl)) {
                    if (hsh(x * 17, z * 23) < 0.3) {
                        col[y - minY] = Blocks.TALL_SEAGRASS.defaultBlockState()
                                .setValue(TallSeagrassBlock.HALF,
                                          net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER);
                        if (y + 1 < maxY) {
                            col[y + 1 - minY] = Blocks.TALL_SEAGRASS.defaultBlockState()
                                    .setValue(TallSeagrassBlock.HALF,
                                              net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER);
                        }
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
        info.add("seaLevel=" + seaLevel + "  seed=" + seed);
        double st    = islandDist(pos.getX(), pos.getZ());
        double spRaw = islandH(pos.getX(), pos.getZ(), st);
        double gridH = gridIslandH(pos.getX(), pos.getZ());
        info.add("spawn: dist=" + String.format("%.2f", st) + "  h=" + String.format("%.2f", spRaw));
        info.add("grid:  h=" + String.format("%.2f", gridH));
        // Бенчмарк
        synchronized (OceanChunkGenerator.class) {
            if (chunkCount > 0) {
                double avgUs = (totalGenTimeNs / 1000.0) / chunkCount;
                info.add("perf: chunks=" + chunkCount + " avg=" + String.format("%.1f", avgUs) + "us");
            }
        }
    }
}