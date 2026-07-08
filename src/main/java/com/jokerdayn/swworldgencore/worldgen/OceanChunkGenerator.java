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
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

public class OceanChunkGenerator extends ChunkGenerator {

    private static final Logger log = LoggerFactory.getLogger("SWWorldgenCore");

    private static final LongAdder chunkCount     = new LongAdder();
    private static final LongAdder totalGenTimeNs = new LongAdder();
    private static final AtomicLong lastLogTime   = new AtomicLong(0);

    private static final int BASE_FLOOR = 25;

    private static final double SPAWN_ISLAND_RADIUS = 170.0;
    private static final double SPAWN_ISLAND_FEATHER = 120.0;
    private static final int SPAWN_ISLAND_MAX_HEIGHT = 18;

    // Часто используемые состояния
    private static final BlockState STONE_S   = Blocks.STONE.defaultBlockState();
    private static final BlockState WATER_S   = Blocks.WATER.defaultBlockState();
    private static final BlockState BEDROCK_S = Blocks.BEDROCK.defaultBlockState();

    // Кэш колонок: fillFromNoise -> applyBiomeDecoration
    private static final class ColumnCache {
        final int[]  floor = new int[256];
        final byte[] flags = new byte[256]; // bit0 = isIsland
    }
    private static final ConcurrentHashMap<Long, ColumnCache> DECOR_CACHE = new ConcurrentHashMap<>();
    private static final int DECOR_CACHE_MAX = 4096;

    public static final MapCodec<OceanChunkGenerator> CODEC = RecordCodecBuilder.mapCodec(instance ->
            instance.group(
                    BiomeSource.CODEC.fieldOf("biome_source").forGetter(g -> g.biomeSource),
                    Codec.LONG.fieldOf("seed").forGetter(g -> g.seed),
                    Codec.INT.fieldOf("sea_level").forGetter(g -> g.seaLevel)
            ).apply(instance, OceanChunkGenerator::new)
    );

    private volatile long seed;
    private final int seaLevel;
    private double[] seedOffsets;

    public OceanChunkGenerator(BiomeSource biomeSource, long seed, int seaLevel) {
        super(biomeSource);
        this.seed = seed;
        this.seaLevel = seaLevel;
        buildSeedOffsets();
        if (biomeSource instanceof OceanBiomeSource obs) obs.attachGenerator(this);
        log.info("[OceanChunkGenerator] Created with seed={}", seed);
    }

    public long getSeed() { return seed; }

    public void syncSeedFromLevel(WorldGenLevel level) {
        if (seed == 0 && level != null) {
            long worldSeed = level.getSeed();
            if (worldSeed != 0) {
                seed = worldSeed;
                buildSeedOffsets();
                log.info("[OceanChunkGenerator] Synced seed from world: {}", seed);
            }
        }
    }

    private void buildSeedOffsets() {
        seedOffsets = new double[256];
        for (int i = 0; i < 256; i++) {
            long h = rawHash(i, 0);
            seedOffsets[i] = ((h & 0xFFFF) / (double) 0xFFFF * 2.0 - 1.0);
        }
        BIOME_CACHE.clear();
        FLOOR_CACHE.clear();
    }

    private double seedOff(int salt, double scale) {
        return seedOffsets[salt & 0xFF] * scale;
    }

    // Прямая запись в секцию
    private static void setDirect(ChunkAccess chunk, int lx, int y, int lz, BlockState state) {
        chunk.getSection(chunk.getSectionIndex(y)).setBlockState(lx, y & 15, lz, state, false);
    }

    private static void fillYRange(ChunkAccess chunk, int lx, int lz, int fromY, int toY, BlockState state) {
        int y = fromY;
        while (y <= toY) {
            LevelChunkSection section = chunk.getSection(chunk.getSectionIndex(y));
            int end = Math.min(toY, y | 15);
            for (; y <= end; y++) {
                section.setBlockState(lx, y & 15, lz, state, false);
            }
        }
    }

    @Override
    protected MapCodec<? extends ChunkGenerator> codec() { return CODEC; }

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

    private int gradHash(int x, int z) { return (int) rawHash(x, z) & 7; }

    private static final int[][] GRAD2 = {
        {1, 0}, {-1, 0}, {0, 1}, {0, -1},
        {1, 1}, {-1, 1}, {1, -1}, {-1, -1}
    };

    private double dot2(int[] g, double x, double z) { return g[0] * x + g[1] * z; }

    private double pnoise(double x, double z) {
        int xi = (int) Math.floor(x), zi = (int) Math.floor(z);
        double xf = x - xi, zf = z - zi;
        double u = xf * xf * xf * (xf * (xf * 6 - 15) + 10);
        double v = zf * zf * zf * (zf * (zf * 6 - 15) + 10);
        int g00 = gradHash(xi,     zi    ), g10 = gradHash(xi + 1, zi    );
        int g01 = gradHash(xi,     zi + 1), g11 = gradHash(xi + 1, zi + 1);
        double n00 = dot2(GRAD2[g00], xf, zf), n10 = dot2(GRAD2[g10], xf - 1, zf);
        double n01 = dot2(GRAD2[g01], xf, zf - 1), n11 = dot2(GRAD2[g11], xf - 1, zf - 1);
        double x0 = n00 + u * (n10 - n00), x1 = n01 + u * (n11 - n01);
        return Mth.clamp((x0 + v * (x1 - x0)) * 0.7071 + 0.5, 0.0, 1.0);
    }

    private double fbm(double x, double z, int oct, double lac, double g) {
        double val = 0, amp = 1, freq = 1, max = 0;
        for (int i = 0; i < oct; i++) {
            val += amp * pnoise(x * freq, z * freq);
            max += amp; amp *= g; freq *= lac;
        }
        return val / max;
    }

    private double ridgeNoise(double x, double z, int oct, double lac, double g) {
        double val = 0, amp = 1, freq = 1, max = 0, prev = 1.0;
        for (int i = 0; i < oct; i++) {
            double n = 1.0 - Math.abs(pnoise(x * freq, z * freq) * 2 - 1);
            n = Mth.clamp(n * n * prev, 0.0, 1.0); prev = n;
            val += amp * n; max += amp; amp *= g; freq *= lac;
        }
        return val / max;
    }

    private double biomeNoise(int x, int z) {
        double wx = x + fbm(x * 0.005 + seedOff(201, 8.0), z * 0.005 + seedOff(202, 8.0), 3, 2.0, 0.5) * 150;
        double wz = z + fbm(x * 0.005 + seedOff(203, 8.0), z * 0.005 + seedOff(204, 8.0), 3, 2.0, 0.5) * 150;
        return fbm(wx * 0.006, wz * 0.006, 3, 2.0, 0.5);
    }

    // -------------------------------------------------------------------------
    // Grid-острова
    // -------------------------------------------------------------------------

    private static final int CELL = 2048;

    public int[] findNearestIslandCenter(int px, int pz) {
        int cellX = Math.floorDiv(px, CELL), cellZ = Math.floorDiv(pz, CELL);
        int[] best = {px, pz};
        double bestDistSq = Double.MAX_VALUE;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                int cx = cellX + dx, cz = cellZ + dz;
                if (hsh(cx * 11, cz * 13) > 0.6) continue;
                int ix = cx * CELL + 768 + (int)(hsh(cx * 2, cz * 2) * 512);
                int iz = cz * CELL + 768 + (int)(hsh(cx * 2 + 1, cz * 2 + 1) * 512);
                double dSq = (double)(ix - px) * (ix - px) + (double)(iz - pz) * (iz - pz);
                if (dSq < bestDistSq) { bestDistSq = dSq; best[0] = ix; best[1] = iz; }
            }
        }
        return best;
    }

    private void gridIslandSample(int x, int z, double[] out) {
        int cellX = Math.floorDiv(x, CELL), cellZ = Math.floorDiv(z, CELL);
        double bestDistSq = Double.MAX_VALUE;
        double bestRadius = 0, bestMaxHeight = 0, bestMtnChance = 1;
        int bestCx = 0, bestCz = 0;

        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                int cx = cellX + dx, cz = cellZ + dz;
                double rHash = hsh(cx * 11, cz * 13);
                if (rHash > 0.6) continue;
                double radius = 80 + rHash * 120;
                int ix = cx * CELL + 768 + (int)(hsh(cx * 2, cz * 2) * 512);
                int iz = cz * CELL + 768 + (int)(hsh(cx * 2 + 1, cz * 2 + 1) * 512);
                double dSq = (double)(x - ix) * (x - ix) + (double)(z - iz) * (z - iz);
                if (dSq >= bestDistSq) continue;
                double radiusLimSq = radius * 1.3;
                if (dSq > radiusLimSq * radiusLimSq) continue;
                if (dSq >= radius * radius) continue;
                bestDistSq = dSq; bestRadius = radius;
                bestMaxHeight = 4 + hsh(cx * 17, cz * 19) * 16;
                bestMtnChance = hsh(cx * 23, cz * 29);
                bestCx = cx; bestCz = cz;
            }
        }

        if (bestRadius < 1) { out[0] = 2.0; out[1] = 1.0; out[2] = 0.0; return; }

        double d = Math.sqrt(bestDistSq), t = d / bestRadius;
        double edge = 1.0 - t, falloff = edge * edge * (3.0 - 2.0 * edge);
        double hill = fbm(x * 0.008 + bestCx * 31.0 + seedOff(bestCx * 7 + bestCz, 1.5),
                          z * 0.008 + bestCz * 47.0 + seedOff(bestCx * 11 + bestCz, 0.8), 3, 2.0, 0.55);
        hill = Mth.clamp((hill - 0.15) / 0.7, 0.0, 1.0);
        hill = hill * hill * (3.0 - 2.0 * hill);
        double h = hill * bestMaxHeight * falloff;

        if (bestMtnChance < 0.06) {
            double mtnR = Math.sqrt(ridgeNoise(
                    x * 0.012 + bestCx * 53.0 + seedOff(bestCx * 13 + bestCz, 2.0),
                    z * 0.014 + bestCz * 67.0 + seedOff(bestCx * 17 + bestCz, 1.3), 4, 2.0, 0.55));
            double mtnMask = Mth.clamp(1.0 - d / (bestRadius * 0.6), 0.0, 1.0);
            h += bestMaxHeight * 1.5 * mtnR * (mtnMask * mtnMask) * falloff;
        }

        out[0] = t; out[1] = bestRadius; out[2] = h;
    }

    private double gridIslandH(int x, int z) {
        double[] out = new double[3]; gridIslandSample(x, z, out); return out[2];
    }

    private double gridIslandDist(int x, int z) {
        double[] out = new double[3]; gridIslandSample(x, z, out); return out[0];
    }

    // -------------------------------------------------------------------------
    // Форма острова (спавн)
    // -------------------------------------------------------------------------

    private double islandDist(int x, int z) {
        double dist = Math.sqrt((double) x * x + (double) z * z);
        double wx1 = x + fbm(x * 0.004 + 7.3 + seedOff(41, 1.0),  z * 0.004 + 2.1 + seedOff(42, 0.7),  4, 2.0, 0.55) * 70;
        double wz1 = z + fbm(x * 0.004 + 91.7 + seedOff(43, 1.3), z * 0.004 + 53.4 + seedOff(44, 0.5), 4, 2.0, 0.55) * 70;
        double wx2 = wx1 + fbm(wx1 * 0.012 + 17.9 + seedOff(45, 2.1), wz1 * 0.012 + 83.1 + seedOff(46, 0.9), 3, 2.0, 0.45) * 30;
        double wz2 = wz1 + fbm(wx1 * 0.012 + 44.2 + seedOff(47, 0.6), wz1 * 0.012 + 11.6 + seedOff(48, 1.7), 3, 2.0, 0.45) * 30;
        double wx3 = wx2 + fbm(wx2 * 0.04 + 33.5 + seedOff(49, 3.0), wz2 * 0.04 + 67.8 + seedOff(50, 1.1), 2, 2.0, 0.4) * 10;
        double wz3 = wz2 + fbm(wx2 * 0.04 + 5.5 + seedOff(51, 0.8),  wz2 * 0.04 + 99.2 + seedOff(52, 2.5), 2, 2.0, 0.4) * 10;
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
        double edge = Mth.clamp(1.0 - t / maxT, 0.0, 1.0);
        double falloff = edge * edge * (3.0 - 2.0 * edge);
        if (falloff < 0.001) return 0.0;
        double rawHill = fbm(x * 0.006 + 13.0 + seedOff(61, 1.5), z * 0.006 + 77.0 + seedOff(62, 0.8), 3, 2.0, 0.55);
        double hillLarge = Mth.clamp((rawHill - 0.2) / 0.7, 0.0, 1.0);
        hillLarge = hillLarge * hillLarge * (3.0 - 2.0 * hillLarge);
        double hillMid = fbm(x * 0.018 + 55.0 + seedOff(63, 2.0), z * 0.018 + 31.0 + seedOff(64, 1.2), 2, 2.0, 0.45) * 0.3;
        double raw = (hillLarge + hillMid) * falloff * SPAWN_ISLAND_MAX_HEIGHT;
        double wx = x + fbm(x * 0.015 + 101.3 + seedOff(65, 3.0), z * 0.015 + 57.9 + seedOff(66, 1.5), 3, 2.0, 0.5) * 40;
        double wz = z + fbm(x * 0.015 + 33.7 + seedOff(67, 1.0),  z * 0.015 + 88.2 + seedOff(68, 2.2), 3, 2.0, 0.5) * 30;
        double interior = Mth.clamp(1.0 - t * 2.5, 0.0, 1.0);
        interior = interior * interior * (3.0 - 2.0 * interior);
        if (interior > 0.01) {
            double ridge = Math.pow(ridgeNoise(wx * 0.018 + 200.0 + seedOff(69, 5.0), wz * 0.022 + 150.0 + seedOff(70, 3.0), 5, 2.0, 0.55), 0.6);
            double mtnDetail = fbm(wx * 0.06 + 300.0 + seedOff(71, 4.0), wz * 0.06 + 250.0 + seedOff(72, 2.0), 3, 2.0, 0.45) * 0.3;
            raw += MTN_HEIGHT * (ridge * 0.75 + mtnDetail * 0.25) * interior * falloff;
        }
        return raw;
    }

    // -------------------------------------------------------------------------
    // Высота колонки
    // -------------------------------------------------------------------------

    private int computeFloor(int x, int z, double t, double spRaw) {
        return computeFloor(x, z, t, spRaw, gridIslandH(x, z));
    }

    private int computeFloor(int x, int z, double t, double spRaw, double gridH) {
        double wx = x + fbm(x * 0.005 + seedOff(81, 1.0),        z * 0.005 + seedOff(82, 0.5),        2, 2.0, 0.5) * 50;
        double wz = z + fbm(x * 0.005 + 31.7 + seedOff(83, 0.8), z * 0.005 + 47.3 + seedOff(84, 1.2), 2, 2.0, 0.5) * 50;
        double h = BASE_FLOOR
                + fbm(wx * 0.004 + seedOff(85, 2.0), wz * 0.004 + seedOff(86, 1.5), 4, 2.0, 0.55) * 30
                + fbm(wx * 0.016 + seedOff(87, 0.7), wz * 0.016 + seedOff(88, 0.9), 2, 2.0, 0.45) * 4
                + fbm(wx * 0.05 + seedOff(89, 3.0),  wz * 0.05 + seedOff(90, 2.0),  2, 2.0, 0.4 ) * 1.5;
        int oceanFloor = Math.max(-63, Math.min((int) h, seaLevel - 4));
        double maxT = 1.0 + SPAWN_ISLAND_FEATHER / SPAWN_ISLAND_RADIUS;
        if (t < maxT) {
            int islandFloor = Math.max(oceanFloor, seaLevel + (int) Math.round(spRaw));
            if (t <= 1.0) return islandFloor;
            double norm = (t - 1.0) / (maxT - 1.0);
            double blend = norm * norm * (3.0 - 2.0 * norm);
            return (int) Math.round(islandFloor + (oceanFloor - islandFloor) * blend);
        }
        if (gridH > 0.01) {
            int gridFloor = Math.max(oceanFloor, seaLevel + (int) Math.round(gridH));
            double blend = Mth.clamp(gridH / 3.0, 0.0, 1.0);
            blend = blend * blend * (3.0 - 2.0 * blend);
            return (int) Math.round(oceanFloor + (gridFloor - oceanFloor) * blend);
        }
        return oceanFloor;
    }

    // -------------------------------------------------------------------------
    // Поверхностные блоки
    // -------------------------------------------------------------------------

    private BlockState pickSurf(int x, int z, int fl, double t, double gridH, double gridDi, boolean beach) {
        double maxT = 1.0 + SPAWN_ISLAND_FEATHER / SPAWN_ISLAND_RADIUS;
        boolean nearIsland = t < maxT || (gridDi <= 1.0 && gridH > 0.01);
        if (!nearIsland) return oceanFloor(x, z, fl);
        if (fl < seaLevel) {
            if (seaLevel - fl <= 6) return Blocks.SAND.defaultBlockState();
            return oceanFloor(x, z, fl);
        }
        if (beach) return Blocks.SAND.defaultBlockState();
        return Blocks.GRASS_BLOCK.defaultBlockState();
    }

    private BlockState oceanFloor(int x, int z, int fl) {
        int depth = seaLevel - fl;
        double dn = Mth.clamp((double) (depth - 4) / 50.0, 0.0, 1.0);
        double n = fbm(x * 0.016, z * 0.016, 3, 2.0, 0.5) + fbm(x * 0.08, z * 0.08, 2, 2.0, 0.5) * 0.2;
        double th = 0.7 - dn * 0.2;
        BlockState base = n > th ? Blocks.GRAVEL.defaultBlockState() : Blocks.SAND.defaultBlockState();
        double det = hsh(x * 7, z * 13);
        if (base.is(Blocks.GRAVEL) && det < 0.03) return Blocks.COBBLESTONE.defaultBlockState();
        if (base.is(Blocks.SAND)   && det < 0.02) return Blocks.CLAY.defaultBlockState();
        return base;
    }

    // -------------------------------------------------------------------------
    // Пляжи (по расстоянию до РЕАЛЬНОЙ воды) и классификация биомов
    // -------------------------------------------------------------------------

    public enum BiomeCategory { OCEAN, DEEP_OCEAN, BEACH, TROPICS, SAVANNA }

    private static final ConcurrentHashMap<Long, BiomeCategory> BIOME_CACHE = new ConcurrentHashMap<>();
    private static final int BIOME_CACHE_MAX = 65536;

    private static final ConcurrentHashMap<Long, Integer> FLOOR_CACHE = new ConcurrentHashMap<>();
    private static final int FLOOR_CACHE_MAX = 262144;

    private static long colKey(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
    }

    /** Высота пола колонки с кэшем — нужна для поиска воды рядом с берегом. */
    private int floorAt(int x, int z) {
        long key = colKey(x, z);
        Integer cached = FLOOR_CACHE.get(key);
        if (cached != null) return cached;
        double st = islandDist(x, z);
        double spRaw = islandH(x, z, st);
        int fl = computeFloor(x, z, st, spRaw);
        if (FLOOR_CACHE.size() > FLOOR_CACHE_MAX) FLOOR_CACHE.clear();
        FLOOR_CACHE.put(key, fl);
        return fl;
    }

    /** Ширина пляжа в блоках, плавно меняется вдоль берега (12..40). */
    private double beachWidthAt(int x, int z) {
        double n = fbm(x * 0.015 + seedOff(120, 2.0), z * 0.015 + seedOff(121, 1.5), 3, 2.0, 0.5);
        return 12.0 + n * 28.0;
    }

    private static final int[][] BEACH_DIRS = {
            {1, 0}, {-1, 0}, {0, 1}, {0, -1},
            {1, 1}, {1, -1}, {-1, 1}, {-1, -1}
    };

    /**
     * Пляж = близость к реальной воде. Сканируем 8 направлений: если в пределах
     * beachWidth есть колонка с fl < seaLevel — это пляж. Колонка у самой кромки
     * всегда находит воду на расстоянии 1, поэтому песок гарантированно доходит
     * до океана без разрывов. Высота используется только чтобы убрать песок
     * с береговых обрывов (fl > seaLevel + 5).
     */
    private boolean isBeach(int x, int z, int fl) {
        if (fl < seaLevel) return false;
        if (fl > seaLevel + 5) return false;
        int width = (int) beachWidthAt(x, z);
        for (int[] dir : BEACH_DIRS) {
            for (int d = 1; d <= width; d += (d < 4 ? 1 : 3)) {
                if (floorAt(x + dir[0] * d, z + dir[1] * d) < seaLevel) return true;
            }
        }
        return false;
    }

    /** Классификация биома для колонки. Используется OceanBiomeSource (F3) и генерацией. */
    public BiomeCategory classifyBiome(int x, int z) {
        long key = colKey(x, z);
        BiomeCategory cached = BIOME_CACHE.get(key);
        if (cached != null) return cached;

        int fl = floorAt(x, z);

        BiomeCategory result;
        if (fl < seaLevel) {
            result = (seaLevel - fl > 28) ? BiomeCategory.DEEP_OCEAN : BiomeCategory.OCEAN;
        } else if (isBeach(x, z, fl)) {
            result = BiomeCategory.BEACH;
        } else {
            result = biomeNoise(x, z) > 0.5 ? BiomeCategory.TROPICS : BiomeCategory.SAVANNA;
        }

        if (BIOME_CACHE.size() > BIOME_CACHE_MAX) BIOME_CACHE.clear();
        BIOME_CACHE.put(key, result);
        return result;
    }

    private BlockState subSurf(int x, int z, int fl, double t, double spRaw, double gridH, boolean beach) {
        if (beach) return Blocks.SAND.defaultBlockState();
        if (fl >= seaLevel) {
            boolean isIsland = spRaw > 0.0 || gridH > 0.5;
            return isIsland ? Blocks.DIRT.defaultBlockState() : Blocks.SAND.defaultBlockState();
        }
        double maxT = 1.0 + SPAWN_ISLAND_FEATHER / SPAWN_ISLAND_RADIUS;
        if (t < maxT || gridH > 0.01) {
            return hsh(x * 11, z * 17) < 0.6 ? Blocks.SAND.defaultBlockState() : Blocks.GRAVEL.defaultBlockState();
        }
        return null;
    }

    private BlockState slab(BlockState s) {
        if (s.is(Blocks.SAND) || s.is(Blocks.CLAY))            return Blocks.SANDSTONE_SLAB.defaultBlockState();
        if (s.is(Blocks.GRAVEL) || s.is(Blocks.COBBLESTONE))   return Blocks.COBBLESTONE_SLAB.defaultBlockState();
        return Blocks.STONE_SLAB.defaultBlockState();
    }

    private boolean hasGrass(int x, int z, int fl) {
        int depth = seaLevel - fl;
        if (depth < 2 || depth > 40) return false;
        double ch;
        if      (depth <= 5 ) ch = 0.7;
        else if (depth <= 10) ch = 0.5;
        else if (depth <= 20) ch = 0.3;
        else if (depth <= 30) ch = 0.1;
        else                  ch = 0.04;
        return fbm(x * 0.08, z * 0.08, 2, 2.0, 0.5) > 0.3 && hsh(x * 31, z * 37) < ch;
    }

    // -------------------------------------------------------------------------
    // Генерация чанка
    // -------------------------------------------------------------------------

    @Override
    public CompletableFuture<ChunkAccess> fillFromNoise(Blender blender, RandomState randomState,
                                                        StructureManager structureManager, ChunkAccess chunk) {
        long startTime = System.nanoTime();

        if (chunk.getLevel() instanceof WorldGenLevel wgl) syncSeedFromLevel(wgl);

        ChunkPos cp = chunk.getPos();
        int cx = cp.x, cz = cp.z;

        if (cx == 0 && cz == 0 && chunkCount.sum() == 0) {
            double st0 = islandDist(0, 0);
            double sp0 = islandH(0, 0, st0);
            double gH0 = gridIslandH(0, 0);
            double gDist0 = gridIslandDist(0, 0);
            int fl0 = computeFloor(0, 0, st0, sp0, gH0);
            log.info("[DEBUG] seed={} spawnDist={} spawnH={} gridH={} gridDist={} floor={}",
                    seed, String.format("%.4f", st0), String.format("%.4f", sp0),
                    String.format("%.4f", gH0), String.format("%.4f", gDist0), fl0);
        }

        int[][]    heights = new int[18][18];
        double[][] dists   = new double[18][18];
        double[][] islands = new double[18][18];
        double[][] grids   = new double[18][18];
        double[][] gridDi  = new double[18][18];
        double[][] gridRi  = new double[18][18];
        double[] grid = new double[3];

        ColumnCache colCache = new ColumnCache();
        int maxFl = Integer.MIN_VALUE;

        for (int lx = -1; lx <= 16; lx++) {
            for (int lz = -1; lz <= 16; lz++) {
                int    wx    = cx * 16 + lx, wz = cz * 16 + lz;
                double st    = islandDist(wx, wz);
                double spRaw = islandH(wx, wz, st);
                gridIslandSample(wx, wz, grid);
                double gridD = grid[0], gridR = grid[1], gridH = grid[2];
                int    fl    = computeFloor(wx, wz, st, spRaw, gridH);

                dists  [lx + 1][lz + 1] = st;
                islands[lx + 1][lz + 1] = spRaw;
                grids  [lx + 1][lz + 1] = gridH;
                gridDi [lx + 1][lz + 1] = gridD;
                gridRi [lx + 1][lz + 1] = gridR;
                heights[lx + 1][lz + 1] = fl;

                if (fl > maxFl) maxFl = fl;

                if (lx >= 0 && lx < 16 && lz >= 0 && lz < 16) {
                    int idx = (lx << 4) | lz;
                    colCache.floor[idx] = fl;
                    colCache.flags[idx] = (byte) ((spRaw > 0.0 || gridH > 0.5) ? 1 : 0);
                }
            }
        }

        if (DECOR_CACHE.size() > DECOR_CACHE_MAX) DECOR_CACHE.clear();
        DECOR_CACHE.put(cp.toLong(), colCache);

        int minY     = chunk.getMinBuildHeight();
        int maxWorkY = Math.max(maxFl + 2, seaLevel);

        int minSec = chunk.getSectionIndex(minY);
        int maxSec = chunk.getSectionIndex(maxWorkY);
        for (int i = minSec; i <= maxSec; i++) chunk.getSection(i).acquire();

        Heightmap hmOcean   = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.OCEAN_FLOOR_WG);
        Heightmap hmSurface = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);

        double maxT = 1.0 + SPAWN_ISLAND_FEATHER / SPAWN_ISLAND_RADIUS;

        try {
            for (int lx = 0; lx < 16; lx++) {
                for (int lz = 0; lz < 16; lz++) {
                    int    wx       = cx * 16 + lx, wz = cz * 16 + lz;
                    int    fl       = heights[lx + 1][lz + 1];
                    double st       = dists  [lx + 1][lz + 1];
                    double spRaw    = islands[lx + 1][lz + 1];
                    double gridHi   = grids  [lx + 1][lz + 1];
                    double gridDist = gridDi [lx + 1][lz + 1];
                    double gridRad  = gridRi [lx + 1][lz + 1];

                    boolean onSlope = fl < heights[lx    ][lz + 1]
                                   || fl < heights[lx + 2][lz + 1]
                                   || fl < heights[lx + 1][lz    ]
                                   || fl < heights[lx + 1][lz + 2];

                    boolean onIsland = spRaw > 0 || gridHi > 0.5;
                    int dirtLayers;
                    if (fl >= seaLevel && onIsland) dirtLayers = 3;
                    else if (st < maxT)             dirtLayers = 2;
                    else                            dirtLayers = 0;

                    boolean beach = isBeach(wx, wz, fl);

                    setDirect(chunk, lx, minY, lz, BEDROCK_S);
                    fillYRange(chunk, lx, lz, minY + 1, fl - dirtLayers - 1, STONE_S);

                    if (dirtLayers > 0) {
                        BlockState sub = subSurf(wx, wz, fl, st, spRaw, gridHi, beach);
                        fillYRange(chunk, lx, lz, fl - dirtLayers, fl - 1, sub != null ? sub : STONE_S);
                    }

                    BlockState surf = pickSurf(wx, wz, fl, st, gridHi, gridDist, beach);
                    setDirect(chunk, lx, fl, lz, surf);
                    hmOcean.update(lx, fl, lz, surf);
                    hmSurface.update(lx, fl, lz, surf);

                    if (fl < seaLevel) {
                        int waterFrom = fl + 1;

                        if (onSlope && onIsland) {
                            BlockState sl = slab(surf)
                                    .setValue(SlabBlock.TYPE,        SlabType.BOTTOM)
                                    .setValue(SlabBlock.WATERLOGGED, true);
                            setDirect(chunk, lx, fl + 1, lz, sl);
                            hmOcean.update(lx, fl + 1, lz, sl);
                            hmSurface.update(lx, fl + 1, lz, sl);
                            waterFrom = fl + 2;
                        } else if (hasGrass(wx, wz, fl)) {
                            if (hsh(wx * 17, wz * 23) < 0.3) {
                                BlockState lower = Blocks.TALL_SEAGRASS.defaultBlockState()
                                        .setValue(TallSeagrassBlock.HALF, net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER);
                                BlockState upper = Blocks.TALL_SEAGRASS.defaultBlockState()
                                        .setValue(TallSeagrassBlock.HALF, net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER);
                                setDirect(chunk, lx, fl + 1, lz, lower);
                                setDirect(chunk, lx, fl + 2, lz, upper);
                                hmSurface.update(lx, fl + 2, lz, upper);
                                waterFrom = fl + 3;
                            } else {
                                BlockState sg = Blocks.SEAGRASS.defaultBlockState();
                                setDirect(chunk, lx, fl + 1, lz, sg);
                                hmSurface.update(lx, fl + 1, lz, sg);
                                waterFrom = fl + 2;
                            }
                        }

                        if (waterFrom <= seaLevel) {
                            fillYRange(chunk, lx, lz, waterFrom, seaLevel, WATER_S);
                            hmSurface.update(lx, seaLevel, lz, WATER_S);
                        }
                    }
                }
            }
        } finally {
            for (int i = minSec; i <= maxSec; i++) chunk.getSection(i).release();
        }

        long elapsed = System.nanoTime() - startTime;
        chunkCount.increment();
        totalGenTimeNs.add(elapsed);
        long now  = System.currentTimeMillis();
        long last = lastLogTime.get();
        if (now - last > 5000 && lastLogTime.compareAndSet(last, now)) {
            long cnt = chunkCount.sum();
            long tot = totalGenTimeNs.sum();
            double avgUs = (tot / 1000.0) / Math.max(cnt, 1);
            log.info("[Benchmark] chunks={} avg={}us/chunk total={}s",
                    cnt, String.format("%.1f", avgUs), String.format("%.2f", tot / 1_000_000_000.0));
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
    // Декорации биома
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
        syncSeedFromLevel(level);

        ChunkPos cp = chunk.getPos();
        int cx = cp.x, cz = cp.z;

        ColumnCache cache = DECOR_CACHE.remove(cp.toLong());

        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int wx = cx * 16 + lx, wz = cz * 16 + lz;

                int     fl;
                boolean isIsland;
                if (cache != null) {
                    int idx = (lx << 4) | lz;
                    fl       = cache.floor[idx];
                    isIsland = (cache.flags[idx] & 1) != 0;
                } else {
                    double st    = islandDist(wx, wz);
                    double spRaw = islandH(wx, wz, st);
                    double gridH = gridIslandH(wx, wz);
                    fl       = computeFloor(wx, wz, st, spRaw, gridH);
                    isIsland = spRaw > 0.0 || gridH > 0.5;
                }

                if (fl < seaLevel) continue;
                if (!isIsland)     continue;

                BlockPos surface = new BlockPos(wx, fl, wz);
                boolean  onSand  = level.getBlockState(surface).is(Blocks.SAND);
                double biome = biomeNoise(wx, wz);
                boolean isBeachBiome = biome > 0.5;

                if (onSand && isBeachBiome && hsh(wx * 41, wz * 43) < 0.003) {
                    PalmGenerator.tryPlacePalm(level, wx, fl + 1, wz, hsh(wx * 53, wz * 59));
                    continue;
                }

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

                if (hsh(wx * 61, wz * 67) < 0.01) {
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

                if (!isBeachBiome && r < 0.04) {
                    if (level.getBlockState(above).isAir()
                            && !nearTree(level, wx, fl + 1, wz, 5)) {
                        AcaciaGenerator.tryPlace(level, wx, fl, wz, hsh(wx * 53, wz * 67));
                    }
                } else if (r < 0.45) {
                    if (level.getBlockState(above).isAir()) {
                        level.setBlock(above, Blocks.SHORT_GRASS.defaultBlockState(), 2);
                    }
                } else if (r < 0.49) {
                    if (level.getBlockState(above).isAir()) {
                        double     flr    = hsh(wx * 23, wz * 37);
                        BlockState flower;
                        if      (flr < 0.4) flower = Blocks.POPPY.defaultBlockState();
                        else if (flr < 0.7) flower = Blocks.DANDELION.defaultBlockState();
                        else                flower = Blocks.OXEYE_DAISY.defaultBlockState();
                        level.setBlock(above, flower, 2);
                    }
                } else if (r < 0.52) {
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

    @Override
    public int getGenDepth() { return 384; }

    @Override
    public int getSeaLevel() { return seaLevel; }

    @Override
    public int getMinY() { return -64; }

    @Override
    public int getBaseHeight(int x, int z, Heightmap.Types heightmapType,
                             LevelHeightAccessor level, RandomState random) {
        if (level instanceof WorldGenLevel wgl) syncSeedFromLevel(wgl);
        double st    = islandDist(x, z);
        double spRaw = islandH(x, z, st);
        double gridH = gridIslandH(x, z);
        return computeFloor(x, z, st, spRaw, gridH) + 1;
    }

    @Override
    public NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor level, RandomState random) {
        double st    = islandDist(x, z);
        double spRaw = islandH(x, z, st);
        double[] grid = new double[3];
        gridIslandSample(x, z, grid);
        double gridDi = grid[0], gridRi = grid[1], gridHi = grid[2];
        int    fl    = computeFloor(x, z, st, spRaw, gridHi);
        double maxT = 1.0 + SPAWN_ISLAND_FEATHER / SPAWN_ISLAND_RADIUS;
        boolean onIsl = spRaw > 0 || gridHi > 0.5;
        int dirtLayers;
        if (fl >= seaLevel && onIsl) dirtLayers = 3;
        else if (st < maxT) dirtLayers = 2;
        else dirtLayers = 0;
        boolean beach = isBeach(x, z, fl);
        int minY = level.getMinBuildHeight(), maxY = level.getMaxBuildHeight();
        BlockState[] col = new BlockState[maxY - minY];
        int skip = 0;
        for (int y = minY; y < maxY; y++) {
            if (skip > 0) { col[y - minY] = Blocks.AIR.defaultBlockState(); skip--; continue; }
            if (y == minY) {
                col[y - minY] = Blocks.BEDROCK.defaultBlockState();
            } else if (y < fl - dirtLayers) {
                col[y - minY] = Blocks.STONE.defaultBlockState();
            } else if (y < fl) {
                BlockState sub = subSurf(x, z, fl, st, spRaw, gridHi, beach);
                col[y - minY] = sub != null ? sub : Blocks.STONE.defaultBlockState();
            } else if (y == fl) {
                col[y - minY] = pickSurf(x, z, fl, st, gridHi, gridDi, beach);
            } else if (y == fl + 1) {
                if (fl >= seaLevel) {
                    col[y - minY] = Blocks.AIR.defaultBlockState();
                } else {
                    col[y - minY] = Blocks.WATER.defaultBlockState();
                }
            } else if (y <= seaLevel && fl < seaLevel) {
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
        long cnt = chunkCount.sum();
        if (cnt > 0) {
            double avgUs = (totalGenTimeNs.sum() / 1000.0) / cnt;
            info.add("perf: chunks=" + cnt + " avg=" + String.format("%.1f", avgUs) + "us");
        }
    }
}
