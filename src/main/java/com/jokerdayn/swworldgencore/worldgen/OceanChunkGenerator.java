package com.jokerdayn.swworldgencore.worldgen;

import com.jokerdayn.swworldgencore.SWWorldgenCore;
import com.jokerdayn.swworldgencore.block.GroundDecorationBlock;
import com.jokerdayn.swworldgencore.block.ShellBlock;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.jokerdayn.swworldgencore.OceanGeneratorDiagnostics;
import com.mojang.brigadier.arguments.BoolArgumentType;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
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
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.TallSeagrassBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@EventBusSubscriber(
    modid = SWWorldgenCore.MODID
)
public class OceanChunkGenerator extends ChunkGenerator {

    private static final Logger log = LoggerFactory.getLogger("SWWorldgenCore");

    private final OceanGeneratorDiagnostics diagnostics =
        new OceanGeneratorDiagnostics();
    private final AtomicBoolean spawnDebugLogged =
        new AtomicBoolean();

    private static final int BASE_FLOOR = 25;
    private static final int GEN_MIN_Y = -64;
    private static final int GEN_DEPTH = 384;
    private static final int GEN_MAX_Y = GEN_MIN_Y + GEN_DEPTH - 1;
    // Оставляем запас под кальдеру и лавовое озеро над уровнем моря.
    private static final int MAX_SEA_LEVEL = GEN_MAX_Y - 64;
    private static final int LOCAL_SAMPLE_SIZE = 18;
    private static final int LOCAL_SAMPLE_AREA =
        LOCAL_SAMPLE_SIZE * LOCAL_SAMPLE_SIZE;
    private static final int VOLCANO_COMMAND_SEARCH_RADIUS = 64;
    private static final int VOLCANO_COMMAND_SAFE_RADIUS = 10;

    private static final double SPAWN_ISLAND_RADIUS = 170.0;
    private static final double SPAWN_ISLAND_FEATHER = 120.0;
    private static final int SPAWN_ISLAND_MAX_HEIGHT = 18;
    private static final int SPAWN_BEACH_SEARCH_DIRECTIONS = 128;
    private static final int SPAWN_BEACH_SEARCH_OUTER_RADIUS = 520;
    private static final int SPAWN_BEACH_SEARCH_INNER_RADIUS = 64;
    private static final int SPAWN_BEACH_SEARCH_STEP = 4;
    private static final int SPAWN_BEACH_MIN_INLAND_DEPTH = 8;
    private static final int SPAWN_BEACH_TANGENT_HALF_WIDTH = 6;

    // =========================================================================
    // НАСТРОЙКИ ВАЛУНОВ  —  всё, что можно крутить, лежит здесь
    // =========================================================================
    /** Общий множитель размера валунов. 1.0 = обычный, 2.0 = вдвое крупнее и т.д. */
    private static final double BOULDER_SIZE = 1.0;
    /** Мин. базовый радиус валуна в блоках (до умножения на BOULDER_SIZE). */
    private static final double BOULDER_MIN_RADIUS = 4.0;
    /** Макс. базовый радиус валуна в блоках (до умножения на BOULDER_SIZE). */
    private static final double BOULDER_MAX_RADIUS = 5.5;
    /** Насколько валун приплюснут: 1.0 = высокий как ширина, 0.5 = низкий и широкий. */
    private static final double BOULDER_HEIGHT_RATIO = 0.7;
    /** Мин. количество валунов на один остров. */
    private static final int BOULDER_MIN_COUNT = 3;
    /** Макс. количество валунов на один остров. */
    private static final int BOULDER_MAX_COUNT = 7;
    /** Сколько детерминированных кандидатов проверить, чтобы набрать нужное число валунов. */
    private static final int BOULDER_PLACEMENT_TRIES = 96;
    /** Минимальная дистанция между центрами валунов в их суммарных радиусах. */
    private static final double BOULDER_SEPARATION = 2.50;
    /** Отступ от края острова как доля радиуса острова (0 = у воды, 0.4 = только вглубь). */
    private static final double BOULDER_EDGE_MARGIN = 0.30;
    /** Макс. перепад высоты земли под валуном — защита от склонов/обрывов. */
    private static final int BOULDER_MAX_SLOPE = 2;
    /** Макс. высота земли над уровнем моря, где допускаются валуны (отсекает горы). */
    private static final int BOULDER_MAX_GROUND_H = 22;
    /** На сколько блоков утопить основание валуна в землю (чтобы не висел/не торчал). */
    private static final int BOULDER_EMBED = 1;
    /** Доля руды у минимального валуна; итоговое количество масштабируется его объёмом. */
    private static final double BOULDER_MIN_ORE_FRACTION = 0.115;
    /** Доля руды у максимального валуна. */
    private static final double BOULDER_MAX_ORE_FRACTION = 0.155;

    // Саванна: кандидаты идут по сетке, а низкочастотный шум собирает их в рощи.
    private static final int SAVANNA_TREE_CELL = 11;
    private static final int SAVANNA_MAX_SLOPE = 3;
    private static final double SAVANNA_GROVE_THRESHOLD = 0.43;
    private static final int[][] SAVANNA_SLOPE_SAMPLES = {
        { 3, 0 }, { -3, 0 }, { 0, 3 }, { 0, -3 },
    };

    // Часто используемые состояния
    private static final BlockState STONE_S = Blocks.STONE.defaultBlockState();
    private static final BlockState WATER_S = Blocks.WATER.defaultBlockState();
    private static final BlockState BEDROCK_S =
        Blocks.BEDROCK.defaultBlockState();

    // Кэш колонок: fillFromNoise -> applyBiomeDecoration
    private static final class ColumnCache {

        final int[] floor = new int[256];
        final byte[] flags = new byte[256]; // bit0=island, bit1=volcano
    }

    /** Неизменяемая раскладка валунов одного острова, общая для всех его чанков. */
    private static final class BoulderLayout {

        final int[] x = new int[BOULDER_MAX_COUNT];
        final int[] z = new int[BOULDER_MAX_COUNT];
        final double[] radius = new double[BOULDER_MAX_COUNT];
        final long[] hash = new long[BOULDER_MAX_COUNT];
        int count;
    }

    private final ConcurrentHashMap<Long, ColumnCache> DECOR_CACHE =
        new ConcurrentHashMap<>();
    private static final int DECOR_CACHE_MAX = 4096;
    private final ConcurrentHashMap<Long, BoulderLayout> BOULDER_CACHE =
        new ConcurrentHashMap<>();
    private static final int BOULDER_CACHE_MAX = 2048;
    /** Безопасный scratch: значение всегда считывается до вложенного floorAt(). */
    private static final ThreadLocal<double[]> GRID_SAMPLE_SCRATCH =
        ThreadLocal.withInitial(() -> new double[8]);
    private static final ShellBlock.Variation[] SHELL_VARIATIONS =
        ShellBlock.Variation.values();
    private static final GroundDecorationBlock.Type[] GROUND_DECORATION_TYPES =
        GroundDecorationBlock.Type.values();
    private static final int[][][] BUSH_TEMPLATES = {
        {
            { 0, 0, 0 }, { 1, 0, 0 }, { -1, 0, 0 }, { 0, 0, 1 },
            { 0, 0, -1 }, { 1, 0, 1 }, { -1, 0, -1 }, { 0, 1, 0 },
            { 1, 1, 0 }, { 0, 1, 1 },
        },
        {
            { 0, 0, 0 }, { 1, 0, 0 }, { -1, 0, 0 }, { 0, 0, 1 },
            { 0, 0, -1 }, { 1, 0, -1 }, { -1, 0, 1 }, { 0, 1, 0 },
            { -1, 1, 0 }, { 0, 1, -1 }, { 1, 1, 1 },
        },
        {
            { 0, 0, 0 }, { 2, 0, 0 }, { -1, 0, 0 }, { 0, 0, 1 },
            { 0, 0, -2 }, { 1, 0, 1 }, { -1, 0, -1 }, { 0, 1, 0 },
            { 1, 1, 0 }, { 0, 1, 1 }, { -1, 1, 0 }, { 0, 2, 0 },
        },
    };

    public static final MapCodec<OceanChunkGenerator> CODEC =
        RecordCodecBuilder.mapCodec(instance ->
            instance
                .group(
                    BiomeSource.CODEC.fieldOf("biome_source").forGetter(
                        g -> g.biomeSource
                    ),
                    Codec.LONG.fieldOf("seed").forGetter(g -> g.seed),
                    Codec.intRange(GEN_MIN_Y + 1, MAX_SEA_LEVEL)
                        .fieldOf("sea_level")
                        .forGetter(g -> g.seaLevel)
                )
                .apply(instance, OceanChunkGenerator::new)
        );

    private volatile long seed;
    private final int seaLevel;
    private volatile double[] seedOffsets;

    public OceanChunkGenerator(
        BiomeSource biomeSource,
        long seed,
        int seaLevel
    ) {
        super(biomeSource);
        if (seaLevel <= GEN_MIN_Y || seaLevel > MAX_SEA_LEVEL) {
            throw new IllegalArgumentException(
                "seaLevel must be in [" + (GEN_MIN_Y + 1) + ", " + MAX_SEA_LEVEL + "]"
            );
        }
        this.seed = seed;
        this.seaLevel = seaLevel;
        buildSeedOffsets();
        if (biomeSource instanceof OceanBiomeSource obs) obs.attachGenerator(
            this
        );
        log.info("[OceanChunkGenerator] Created with seed={}", seed);
    }

    public long getSeed() {
        return seed;
    }

    public void syncSeedFromLevel(WorldGenLevel level) {
        if (level == null || seed != 0) return;
        long worldSeed = level.getSeed();
        if (worldSeed == 0) return;
        synchronized (this) {
            if (seed != 0) return;
            seedOffsets = createSeedOffsets(worldSeed);
            diagnostics.seedReset();
            diagnostics.cacheTrim(OceanGeneratorDiagnostics.Cache.BIOME, BIOME_CACHE.size());
            diagnostics.cacheTrim(OceanGeneratorDiagnostics.Cache.FLOOR, FLOOR_CACHE.size());
            diagnostics.cacheTrim(OceanGeneratorDiagnostics.Cache.BEACH, BEACH_CACHE.size());
            diagnostics.cacheTrim(OceanGeneratorDiagnostics.Cache.DECOR, DECOR_CACHE.size());
            BIOME_CACHE.clear();
            FLOOR_CACHE.clear();
            BEACH_CACHE.clear();
            DECOR_CACHE.clear();
            BOULDER_CACHE.clear();
            updateDiagnosticCacheSizes();
            // volatile seed публикуется последним: читатель нового seed уже видит offsets.
            seed = worldSeed;
        }
        log.info("[OceanChunkGenerator] Synced seed from world: {}", worldSeed);
    }

    private static double[] createSeedOffsets(long sourceSeed) {
        double[] offsets = new double[256];
        for (int i = 0; i < offsets.length; i++) {
            long n = ((long) i * 73856093L) ^ sourceSeed;
            n = (n ^ (n >> 13)) * 1274126177L;
            long h = n ^ (n >> 16);
            offsets[i] = ((h & 0xFFFF) / (double) 0xFFFF) * 2.0 - 1.0;
        }
        return offsets;
    }

    private void buildSeedOffsets() {
        seedOffsets = createSeedOffsets(seed);
        diagnostics.seedReset();
        diagnostics.cacheTrim(OceanGeneratorDiagnostics.Cache.BIOME, BIOME_CACHE.size());
        diagnostics.cacheTrim(OceanGeneratorDiagnostics.Cache.FLOOR, FLOOR_CACHE.size());
        diagnostics.cacheTrim(OceanGeneratorDiagnostics.Cache.BEACH, BEACH_CACHE.size());
        diagnostics.cacheTrim(OceanGeneratorDiagnostics.Cache.DECOR, DECOR_CACHE.size());
        BIOME_CACHE.clear();
        FLOOR_CACHE.clear();
        BEACH_CACHE.clear();
        DECOR_CACHE.clear();
        BOULDER_CACHE.clear();
        updateDiagnosticCacheSizes();
    }

    private double seedOff(int salt, double scale) {
        return seedOffsets[salt & 0xFF] * scale;
    }

    // Прямая з  пись в секцию
    private static void setDirect(
        ChunkAccess chunk,
        int lx,
        int y,
        int lz,
        BlockState state
    ) {
        chunk
            .getSection(chunk.getSectionIndex(y))
            .setBlockState(lx, y & 15, lz, state, false);
    }

    private static void fillYRange(
        ChunkAccess chunk,
        int lx,
        int lz,
        int fromY,
        int toY,
        BlockState state
    ) {
        int y = fromY;
        while (y <= toY) {
            LevelChunkSection section = chunk.getSection(
                chunk.getSectionIndex(y)
            );
            int end = Math.min(toY, y | 15);
            for (; y <= end; y++) {
                section.setBlockState(lx, y & 15, lz, state, false);
            }
        }
    }

    @Override
    protected MapCodec<? extends ChunkGenerator> codec() {
        return CODEC;
    }

    // -------------------------------------------------------------------------
    // Шумовые утилиты
    // -------------------------------------------------------------------------

    private long rawHash(int x, int z) {
        long n = ((long) x * 73856093L) ^ ((long) z * 19349663L) ^ seed;
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
        { 1, 0 },
        { -1, 0 },
        { 0, 1 },
        { 0, -1 },
        { 1, 1 },
        { -1, 1 },
        { 1, -1 },
        { -1, -1 },
    };

    private double dot2(int[] g, double x, double z) {
        return g[0] * x + g[1] * z;
    }

    private double pnoise(double x, double z) {
        int xi = (int) Math.floor(x),
            zi = (int) Math.floor(z);
        double xf = x - xi,
            zf = z - zi;
        double u = xf * xf * xf * (xf * (xf * 6 - 15) + 10);
        double v = zf * zf * zf * (zf * (zf * 6 - 15) + 10);
        int g00 = gradHash(xi, zi),
            g10 = gradHash(xi + 1, zi);
        int g01 = gradHash(xi, zi + 1),
            g11 = gradHash(xi + 1, zi + 1);
        double n00 = dot2(GRAD2[g00], xf, zf),
            n10 = dot2(GRAD2[g10], xf - 1, zf);
        double n01 = dot2(GRAD2[g01], xf, zf - 1),
            n11 = dot2(GRAD2[g11], xf - 1, zf - 1);
        double x0 = n00 + u * (n10 - n00),
            x1 = n01 + u * (n11 - n01);
        return Mth.clamp((x0 + v * (x1 - x0)) * 0.7071 + 0.5, 0.0, 1.0);
    }

    private double fbm(double x, double z, int oct, double lac, double g) {
        double val = 0,
            amp = 1,
            freq = 1,
            max = 0;
        for (int i = 0; i < oct; i++) {
            val += amp * pnoise(x * freq, z * freq);
            max += amp;
            amp *= g;
            freq *= lac;
        }
        return val / max;
    }

    private double ridgeNoise(
        double x,
        double z,
        int oct,
        double lac,
        double g
    ) {
        double val = 0,
            amp = 1,
            freq = 1,
            max = 0,
            prev = 1.0;
        for (int i = 0; i < oct; i++) {
            double n = 1.0 - Math.abs(pnoise(x * freq, z * freq) * 2 - 1);
            n = Mth.clamp(n * n * prev, 0.0, 1.0);
            prev = n;
            val += amp * n;
            max += amp;
            amp *= g;
            freq *= lac;
        }
        return val / max;
    }

    // -------------------------------------------------------------------------
    // Grid-острова
    // -------------------------------------------------------------------------

    private static final int CELL = 2048;

    // Редкие дальние grid-острова становятся активными вулканами.
    private static final double VOLCANO_CHANCE = 0.085;
    private static final double VOLCANO_MIN_DISTANCE = 700.0;
    private static final double VOLCANO_CONE_HEIGHT = 74.0;
    /** Компактная кальдера на вершине широкого конуса. */
    private static final double VOLCANO_CRATER_RADIUS = 0.165;
    private static final double VOLCANO_LAVA_RADIUS = 0.137;
    /** Узкий, но непрерывный каменный борт вокруг лавового озера. */
    private static final double VOLCANO_RIM_OUTER_RADIUS = 0.225;
    private static final int VOLCANO_RIM_CLEARANCE = 4;
    /** Лава заметно ниже вершины: вулкан не превращается в высокий цилиндр. */
    private static final int VOLCANO_LAVA_ABOVE_SEA = 39;
    /** Паразитические конусы на склонах: как у Этны — маленькие боковые кратеры. */
    private static final int VOLCANO_PARASITIC_CONES = 2;
    private static final double VOLCANO_PARASITIC_HEIGHT = 13.0;
    /** Эрозионные овраги (барранкосы), сбегающие по склону от кромки к подножию. */
    private static final int VOLCANO_GULLY_COUNT = 7;
    private static final double VOLCANO_GULLY_DEPTH = 4.5;
    private static final BlockState VOLCANO_REWARD_BLOCK =
        Blocks.DIAMOND_BLOCK.defaultBlockState(); // TODO: заменить на блок «раскалённой магмы».

    private static final int FLAG_ISLAND = 1;
    private static final int FLAG_VOLCANO = 2;
    private static final int FLAG_CRATER = 4;

    /**
     * Находит безопасную точку на внешней кромке ближайшего вулкана.
     * Метод только перебирает детерминированные grid-ячейки и не загружает чанки.
     */
    public int[] findNearestVolcano(int px, int pz, int maxCellRadius) {
        int originCellX = Math.floorDiv(px, CELL);
        int originCellZ = Math.floorDiv(pz, CELL);
        int[] best = null;
        double bestDistanceSq = Double.MAX_VALUE;
        int limit = Math.max(1, maxCellRadius);

        for (int ring = 0; ring <= limit; ring++) {
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    if (ring > 0 && Math.abs(dx) != ring && Math.abs(dz) != ring) continue;

                    int cellX = originCellX + dx;
                    int cellZ = originCellZ + dz;
                    double radiusHash = hsh(cellX * 11, cellZ * 13);
                    if (radiusHash > 0.6) continue;

                    double radius = 80.0 + radiusHash * 120.0;
                    int centerX =
                        cellX * CELL +
                        768 +
                        (int) (hsh(cellX * 2, cellZ * 2) * 512);
                    int centerZ =
                        cellZ * CELL +
                        768 +
                        (int) (hsh(cellX * 2 + 1, cellZ * 2 + 1) * 512);
                    double worldDistance = Math.sqrt(
                        (double) centerX * centerX + (double) centerZ * centerZ
                    );
                    if (worldDistance < VOLCANO_MIN_DISTANCE) continue;
                    if (hsh(cellX * 71 + 19, cellZ * 73 - 23) >= VOLCANO_CHANCE) continue;

                    double distanceSq =
                        (double) (centerX - px) * (centerX - px) +
                        (double) (centerZ - pz) * (centerZ - pz);
                    if (distanceSq >= bestDistanceSq) continue;

                    // Точка чуть снаружи кальдеры: вид на лавовое озеро без появления в лаве.
                    double approachAngle = hsh(cellX * 131 + 7, cellZ * 137 - 11) * Math.PI * 2.0;
                    double approachRadius = radius * (VOLCANO_CRATER_RADIUS + 0.105);
                    int targetX = centerX + (int) Math.round(Math.cos(approachAngle) * approachRadius);
                    int targetZ = centerZ + (int) Math.round(Math.sin(approachAngle) * approachRadius);

                    bestDistanceSq = distanceSq;
                    best = new int[] { targetX, targetZ, centerX, centerZ };
                }
            }

            // Следующее кольцо начинается как минимум в CELL блоках дальше.
            // После дополнительного кольца текущий лучший кандидат уже не может проиграть.
            if (best != null) {
                double nextRingMin = minimumDistanceToUnsearchedCells(
                    px,
                    pz,
                    originCellX,
                    originCellZ,
                    ring
                );
                if (nextRingMin * nextRingMin > bestDistanceSq) break;
            }
        }
        return best;
    }

    public int[] findNearestIslandCenter(int px, int pz) {
        int originCellX = Math.floorDiv(px, CELL);
        int originCellZ = Math.floorDiv(pz, CELL);
        int[] best = null;
        double bestDistSq = Double.MAX_VALUE;
        for (int ring = 0; ring <= VOLCANO_COMMAND_SEARCH_RADIUS; ring++) {
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    if (ring > 0 && Math.abs(dx) != ring && Math.abs(dz) != ring) continue;
                    int cx = originCellX + dx;
                    int cz = originCellZ + dz;
                    if (hsh(cx * 11, cz * 13) > 0.6) continue;
                    int ix = cx * CELL + 768 + (int) (hsh(cx * 2, cz * 2) * 512);
                    int iz =
                        cz * CELL + 768 + (int) (hsh(cx * 2 + 1, cz * 2 + 1) * 512);
                    double dSq =
                        (double) (ix - px) * (ix - px) +
                        (double) (iz - pz) * (iz - pz);
                    if (dSq < bestDistSq) {
                        bestDistSq = dSq;
                        best = new int[] { ix, iz };
                    }
                }
            }

            if (best != null) {
                double nextRingMin = minimumDistanceToUnsearchedCells(
                    px,
                    pz,
                    originCellX,
                    originCellZ,
                    ring
                );
                if (nextRingMin * nextRingMin > bestDistSq) break;
            }
        }
        return best != null ? best : new int[] { 0, 0 };
    }

    private static double minimumDistanceToUnsearchedCells(
        int px,
        int pz,
        int originCellX,
        int originCellZ,
        int searchedRing
    ) {
        long next = (long) searchedRing + 1L;
        double positiveX =
            ((long) originCellX + next) * CELL + 768.0 - px;
        double negativeX =
            px - (((long) originCellX - next) * CELL + 1280.0);
        double positiveZ =
            ((long) originCellZ + next) * CELL + 768.0 - pz;
        double negativeZ =
            pz - (((long) originCellZ - next) * CELL + 1280.0);
        return Math.max(
            0.0,
            Math.min(Math.min(positiveX, negativeX), Math.min(positiveZ, negativeZ))
        );
    }

    private void gridIslandSample(int x, int z, double[] out) {
        int cellX = Math.floorDiv(x, CELL),
            cellZ = Math.floorDiv(z, CELL);
        double bestDistSq = Double.MAX_VALUE;
        double bestRadius = 0,
            bestMaxHeight = 0,
            bestMtnChance = 1;
        int bestCx = 0,
            bestCz = 0,
            bestIx = 0,
            bestIz = 0;

        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                int cx = cellX + dx,
                    cz = cellZ + dz;
                double rHash = hsh(cx * 11, cz * 13);
                if (rHash > 0.6) continue;
                double radius = 80 + rHash * 120;
                int ix = cx * CELL + 768 + (int) (hsh(cx * 2, cz * 2) * 512);
                int iz =
                    cz * CELL + 768 + (int) (hsh(cx * 2 + 1, cz * 2 + 1) * 512);
                double dSq =
                    (double) (x - ix) * (x - ix) + (double) (z - iz) * (z - iz);
                if (dSq >= bestDistSq) continue;
                if (dSq >= radius * radius) continue;
                bestDistSq = dSq;
                bestRadius = radius;
                bestMaxHeight = 4 + hsh(cx * 17, cz * 19) * 16;
                bestMtnChance = hsh(cx * 23, cz * 29);
                bestCx = cx;
                bestCz = cz;
                bestIx = ix;
                bestIz = iz;
            }
        }

        if (bestRadius < 1) {
            out[0] = 2.0;
            out[1] = 1.0;
            out[2] = 0.0;
            if (out.length > 3) {
                out[3] = 0.0;
                out[4] = 0.0;
                out[5] = 0.0;
                out[6] = 0.0;
                out[7] = 0.0;
            }
            return;
        }

        double d = Math.sqrt(bestDistSq),
            t = d / bestRadius;
        double edge = 1.0 - t,
            falloff = edge * edge * (3.0 - 2.0 * edge);
        double hill = fbm(
            x * 0.008 + bestCx * 31.0 + seedOff(bestCx * 7 + bestCz, 1.5),
            z * 0.008 + bestCz * 47.0 + seedOff(bestCx * 11 + bestCz, 0.8),
            3,
            2.0,
            0.55
        );
        hill = Mth.clamp((hill - 0.15) / 0.7, 0.0, 1.0);
        hill = hill * hill * (3.0 - 2.0 * hill);
        double h = hill * bestMaxHeight * falloff;

        if (bestMtnChance < 0.06) {
            double mtnR = Math.sqrt(
                ridgeNoise(
                    x * 0.012 +
                        bestCx * 53.0 +
                        seedOff(bestCx * 13 + bestCz, 2.0),
                    z * 0.014 +
                        bestCz * 67.0 +
                        seedOff(bestCx * 17 + bestCz, 1.3),
                    4,
                    2.0,
                    0.55
                )
            );
            double mtnMask = Mth.clamp(1.0 - d / (bestRadius * 0.6), 0.0, 1.0);
            h += bestMaxHeight * 1.5 * mtnR * (mtnMask * mtnMask) * falloff;
        }

        double centerDistance = Math.sqrt((double) bestIx * bestIx + (double) bestIz * bestIz);
        boolean volcano =
            centerDistance >= VOLCANO_MIN_DISTANCE &&
            hsh(bestCx * 71 + 19, bestCz * 73 - 23) < VOLCANO_CHANCE;
        boolean crater = false;

        if (volcano) {
            double localX = x - bestIx;
            double localZ = z - bestIz;
            double warpX = (
                fbm(
                    x * 0.006 + bestCx * 43.0,
                    z * 0.006 - bestCz * 37.0,
                    3,
                    2.0,
                    0.52
                ) - 0.5
            ) * bestRadius * 0.24;
            double warpZ = (
                fbm(
                    x * 0.006 - bestCx * 31.0 + 419.0,
                    z * 0.006 + bestCz * 47.0 - 271.0,
                    3,
                    2.0,
                    0.52
                ) - 0.5
            ) * bestRadius * 0.24;
            double warpedX = localX + warpX;
            double warpedZ = localZ + warpZ;
            double angle = Math.atan2(warpedZ, warpedX);
            double outline =
                1.0 +
                0.13 * Math.sin(angle * 3.0 + bestCx * 1.9) +
                0.08 * Math.sin(angle * 5.0 - bestCz * 2.1) +
                0.05 * Math.sin(angle * 8.0 + bestCx - bestCz);
            double organicT = Math.sqrt(warpedX * warpedX + warpedZ * warpedZ) /
                (bestRadius * outline);
            t = organicT;
            edge = Mth.clamp(1.0 - t, 0.0, 1.0);
            falloff = edge * edge * (3.0 - 2.0 * edge);

            double ribs = Math.pow(
                0.5 + 0.5 * Math.sin(angle * 9.0 + bestCx * 1.7 - bestCz * 2.3),
                3.0
            );
            double broken = fbm(
                x * 0.018 + bestCx * 41.0,
                z * 0.018 - bestCz * 37.0,
                4,
                2.0,
                0.52
            );
            // Внешняя часть острова — самостоятельная проходимая равнина, а не
            // продолжение вулканического конуса. Низкочастотный шум даёт мягкие
            // холмы, но не бесконечные ступени по всей окружности.
            double shelfNoise = fbm(
                x * 0.010 + bestCx * 307.0,
                z * 0.010 - bestCz * 313.0,
                3,
                2.0,
                0.50
            );
            double broadNoise = fbm(
                x * 0.004 - bestCx * 149.0,
                z * 0.004 + bestCz * 157.0,
                2,
                2.0,
                0.50
            );
            // Широкий прибрежный подъём формирует настоящий берег, после которого
            // равнина переходит в мягкие холмы высотой до нескольких блоков.
            double shoreRaw = Mth.clamp((1.0 - t) / 0.18, 0.0, 1.0);
            double shoreLift = shoreRaw * shoreRaw * (3.0 - 2.0 * shoreRaw);
            double rollingHills =
                Math.pow(Mth.clamp(broadNoise, 0.0, 1.0), 1.35) * 5.0 +
                (shelfNoise - 0.5) * 3.0;
            double inlandMask = Mth.clamp((0.88 - t) / 0.18, 0.0, 1.0);
            inlandMask = inlandMask * inlandMask * (3.0 - 2.0 * inlandMask);
            double islandShelf =
                0.4 +
                shoreLift * 6.2 +
                inlandMask * rollingHills;

            // Настоящий вулкан занимает только внутреннюю часть острова.
            // smoothstep создаёт широкое предгорье вместо резкой стены.
            double coneEdge = 0.68 + (broken - 0.5) * 0.09;
            double coneBlendRaw = Mth.clamp((coneEdge - t) / 0.27, 0.0, 1.0);
            double coneBlend = coneBlendRaw * coneBlendRaw * (3.0 - 2.0 * coneBlendRaw);
            double coneLocalT = Mth.clamp(t / Math.max(0.01, coneEdge), 0.0, 1.0);
            // Вогнутый профиль настоящего стратовулкана (Фудзи, Майон): крутая
            // вершина резко набирает высоту, а склон выполаживается в длинный
            // пологий шлейф у подножия. Смесь двух степеней даёт именно эту
            // классическую «трубу Гаусса» вместо прямого щита или башни.
            double cone =
                0.60 * Math.pow(1.0 - coneLocalT, 3.1) +
                0.40 * Math.pow(1.0 - coneLocalT, 1.25);
            double ribStrength = (ribs * 0.075 + (broken - 0.5) * 0.07) * coneBlend;
            double coneHeight =
                islandShelf +
                VOLCANO_CONE_HEIGHT * cone * (0.94 + ribStrength);
            double volcanicH = Mth.lerp(coneBlend, islandShelf, coneHeight);

            double craterTEarly = Math.sqrt(localX * localX + localZ * localZ) /
                bestRadius;

            // Барранкосы — радиальные эрозионные овраги, прорезанные дождями и
            // пирокластикой. Начинаются чуть ниже кромки и постепенно затухают
            // к подножию, придавая склону характерную «рёберную» текстуру.
            if (craterTEarly > VOLCANO_RIM_OUTER_RADIUS + 0.03 && t < coneEdge) {
                double gullyMask = 0.0;
                for (int gully = 0; gully < VOLCANO_GULLY_COUNT; gully++) {
                    double gullyAngle =
                        hsh(bestCx * 331 + gully * 43, bestCz * 337 - gully * 47) *
                        Math.PI * 2.0;
                    double gullyMeander =
                        Math.sin(craterTEarly * 14.0 + gully * 1.9) * 0.055;
                    double gDelta = Math.abs(Math.atan2(
                        Math.sin(angle - gullyAngle - gullyMeander),
                        Math.cos(angle - gullyAngle - gullyMeander)
                    ));
                    double gWidth =
                        0.045 +
                        hsh(bestCx * 347 + gully, bestCz * 349 - gully) * 0.035;
                    double gCut = 1.0 - Mth.clamp(gDelta / gWidth, 0.0, 1.0);
                    gullyMask = Math.max(gullyMask, gCut * gCut);
                }
                // Овраг глубже на крутой средней части склона и сходит на нет
                // и у кромки, и у берега.
                double slopeBand = Mth.clamp((t - 0.16) / 0.14, 0.0, 1.0) *
                    Mth.clamp((coneEdge - t) / 0.20, 0.0, 1.0);
                volcanicH -= gullyMask * VOLCANO_GULLY_DEPTH * slopeBand *
                    (0.7 + broken * 0.6);
            }

            // Паразитические конусы: маленькие боковые кратеры на середине
            // склона, как у Этны. Гауссовы бугры с собственной мини-воронкой.
            for (int pc = 0; pc < VOLCANO_PARASITIC_CONES; pc++) {
                double pcAngle =
                    hsh(bestCx * 401 + pc * 61, bestCz * 409 - pc * 67) *
                    Math.PI * 2.0;
                double pcDist = bestRadius *
                    (0.38 + hsh(bestCx * 419 + pc, bestCz * 421 - pc) * 0.14);
                double pcX = bestIx + Math.cos(pcAngle) * pcDist;
                double pcZ = bestIz + Math.sin(pcAngle) * pcDist;
                double pdx = x - pcX;
                double pdz = z - pcZ;
                double pcRadius = bestRadius * 0.085;
                double pcD = Math.sqrt(pdx * pdx + pdz * pdz) / pcRadius;
                if (pcD < 1.6) {
                    double bump = Math.exp(-pcD * pcD * 1.6) *
                        VOLCANO_PARASITIC_HEIGHT;
                    // Мини-воронка на вершине бокового конуса (без лавы).
                    if (pcD < 0.30) {
                        bump -= (1.0 - pcD / 0.30) * 4.0;
                    }
                    volcanicH += bump;
                }
            }

            // Неглубокие долины существуют в равнине как маршруты, но их амплитуда
            // мала: игрок может идти по земле без постоянных прыжков на 3–5 блоков.
            if (t > 0.52 && t < 0.88) {
                double valleyMask = 0.0;
                for (int valley = 0; valley < 4; valley++) {
                    double valleyAngle =
                        hsh(bestCx * 211 + valley * 31, bestCz * 223 - valley * 37) *
                        Math.PI * 2.0;
                    double meander = Math.sin(t * 10.0 + valley * 2.4) * 0.12;
                    double delta = Math.abs(Math.atan2(
                        Math.sin(angle - valleyAngle - meander),
                        Math.cos(angle - valleyAngle - meander)
                    ));
                    double width = 0.14 + hsh(bestCx * 227 + valley, bestCz * 229 - valley) * 0.08;
                    valleyMask = Math.max(
                        valleyMask,
                        1.0 - Mth.clamp(delta / width, 0.0, 1.0)
                    );
                }
                volcanicH -= valleyMask * (1.0 + shelfNoise * 1.4);
            }

            // Кратер остаётся читаемой чашей; произвольность относится к форме
            // острова и его подножия, а не к лавовому озеру.
            double craterT = craterTEarly;
            double rimCenter = VOLCANO_CRATER_RADIUS;
            double rim = Math.exp(-Math.pow((craterT - rimCenter) / 0.060, 2.0));
            // Асимметричная кромка: у реальных вулканов подветренная сторона
            // заметно выше из-за накопления тефры. Герметичность гарантирует
            // sealedCalderaHeight, поэтому асимметрия чисто силуэтная.
            double windwardAngle =
                hsh(bestCx * 503, bestCz * 509) * Math.PI * 2.0;
            double rimAsymmetry =
                0.8 + 0.45 * (0.5 + 0.5 * Math.cos(angle - windwardAngle));
            volcanicH += rim * (7.0 + ribs * 2.6 + broken * 1.8) * rimAsymmetry;

            crater = craterT < VOLCANO_LAVA_RADIUS;
            if (crater) {
                double inner = craterT / VOLCANO_LAVA_RADIUS;
                double innerRough = fbm(x * 0.045 + 901.0, z * 0.045 - 607.0, 3, 2.0, 0.5);
                // Дно чаши всегда ниже озера, а внутренняя стенка входит прямо в лаву.
                volcanicH = VOLCANO_LAVA_ABOVE_SEA - 8.0 + inner * inner * 7.0 + innerRough;
            }

            // В отличие от декоративных пиков, этот пояс существует в каждой колонке
            // полного круга. Поэтому даже минимум шума не может открыть лаве проход.
            volcanicH = sealedCalderaHeight(craterT, volcanicH);

            // Не сохраняем исходный круглый grid-остров под вулканом: вся суша,
            // включая пляж и травяное подножие, следует organicT.
            h = volcanicH;
        }

        out[0] = t;
        out[1] = bestRadius;
        out[2] = h;
        if (out.length > 3) {
            out[3] = volcano ? 1.0 : 0.0;
            out[4] = crater ? 1.0 : 0.0;
            out[5] = bestIx;
            out[6] = bestIz;
            out[7] = volcano ? seaLevel + VOLCANO_LAVA_ABOVE_SEA : 0.0;
        }
    }

    /**
     * Сплошной пояс между лавовым озером и внешним склоном. В этой зоне шум может
     * только поднять рельеф: опустить его ниже безопасной высоты нельзя.
     */
    private boolean isCalderaBarrier(double craterT) {
        return craterT >= VOLCANO_LAVA_RADIUS &&
            craterT <= VOLCANO_RIM_OUTER_RADIUS;
    }

    private double sealedCalderaHeight(double craterT, double currentHeight) {
        if (!isCalderaBarrier(craterT)) return currentHeight;

        double middle = (VOLCANO_LAVA_RADIUS + VOLCANO_RIM_OUTER_RADIUS) * 0.5;
        double halfWidth = (VOLCANO_RIM_OUTER_RADIUS - VOLCANO_LAVA_RADIUS) * 0.5;
        double crown = 1.0 - Math.abs(craterT - middle) / Math.max(0.001, halfWidth);
        crown = Mth.clamp(crown, 0.0, 1.0);
        // Внутренний край начинается всего на четыре блока выше лавы. К внешнему
        // краю пояс плавно подхватывает естественный склон. Это сохраняет герметичность,
        // но не позволяет высокой центральной части конуса превратиться в цилиндр.
        double guaranteedHeight =
            VOLCANO_LAVA_ABOVE_SEA +
            VOLCANO_RIM_CLEARANCE +
            crown * 5.0;
        double outward = Mth.clamp(
            (craterT - VOLCANO_LAVA_RADIUS) /
                (VOLCANO_RIM_OUTER_RADIUS - VOLCANO_LAVA_RADIUS),
            0.0,
            1.0
        );
        outward = outward * outward * (3.0 - 2.0 * outward);
        return Math.max(
            guaranteedHeight,
            Mth.lerp(outward, guaranteedHeight, currentHeight)
        );
    }

    private double gridIslandH(int x, int z) {
        double[] out = GRID_SAMPLE_SCRATCH.get();
        gridIslandSample(x, z, out);
        return out[2];
    }

    private double gridIslandDist(int x, int z) {
        double[] out = GRID_SAMPLE_SCRATCH.get();
        gridIslandSample(x, z, out);
        return out[0];
    }

    // -------------------------------------------------------------------------
    // Форма острова (спавн)
    // -------------------------------------------------------------------------

    private double islandDist(int x, int z) {
        double dist = Math.sqrt((double) x * x + (double) z * z);
        double wx1 =
            x +
            fbm(
                x * 0.004 + 7.3 + seedOff(41, 1.0),
                z * 0.004 + 2.1 + seedOff(42, 0.7),
                4,
                2.0,
                0.55
            ) *
                70;
        double wz1 =
            z +
            fbm(
                x * 0.004 + 91.7 + seedOff(43, 1.3),
                z * 0.004 + 53.4 + seedOff(44, 0.5),
                4,
                2.0,
                0.55
            ) *
                70;
        double wx2 =
            wx1 +
            fbm(
                wx1 * 0.012 + 17.9 + seedOff(45, 2.1),
                wz1 * 0.012 + 83.1 + seedOff(46, 0.9),
                3,
                2.0,
                0.45
            ) *
                30;
        double wz2 =
            wz1 +
            fbm(
                wx1 * 0.012 + 44.2 + seedOff(47, 0.6),
                wz1 * 0.012 + 11.6 + seedOff(48, 1.7),
                3,
                2.0,
                0.45
            ) *
                30;
        double wx3 =
            wx2 +
            fbm(
                wx2 * 0.04 + 33.5 + seedOff(49, 3.0),
                wz2 * 0.04 + 67.8 + seedOff(50, 1.1),
                2,
                2.0,
                0.4
            ) *
                10;
        double wz3 =
            wz2 +
            fbm(
                wx2 * 0.04 + 5.5 + seedOff(51, 0.8),
                wz2 * 0.04 + 99.2 + seedOff(52, 2.5),
                2,
                2.0,
                0.4
            ) *
                10;
        double warpX = wx3 - x,
            warpZ = wz3 - z;
        double warpShift = (warpX * x + warpZ * z) / (dist + 0.001);
        dist = dist - warpShift - 65;
        return Mth.clamp(
            dist / SPAWN_ISLAND_RADIUS,
            0.0,
            1.0 + SPAWN_ISLAND_FEATHER / SPAWN_ISLAND_RADIUS
        );
    }

    private static final int MTN_HEIGHT = 80;

    private double islandH(int x, int z, double t) {
        double maxT = 1.0 + SPAWN_ISLAND_FEATHER / SPAWN_ISLAND_RADIUS;
        if (t >= maxT) return 0.0;
        double edge = Mth.clamp(1.0 - t / maxT, 0.0, 1.0);
        double falloff = edge * edge * (3.0 - 2.0 * edge);
        if (falloff < 0.001) return 0.0;
        double rawHill = fbm(
            x * 0.006 + 13.0 + seedOff(61, 1.5),
            z * 0.006 + 77.0 + seedOff(62, 0.8),
            3,
            2.0,
            0.55
        );
        double hillLarge = Mth.clamp((rawHill - 0.2) / 0.7, 0.0, 1.0);
        hillLarge = hillLarge * hillLarge * (3.0 - 2.0 * hillLarge);
        double hillMid =
            fbm(
                x * 0.018 + 55.0 + seedOff(63, 2.0),
                z * 0.018 + 31.0 + seedOff(64, 1.2),
                2,
                2.0,
                0.45
            ) * 0.3;
        double raw = (hillLarge + hillMid) * falloff * SPAWN_ISLAND_MAX_HEIGHT;
        double wx =
            x +
            fbm(
                x * 0.015 + 101.3 + seedOff(65, 3.0),
                z * 0.015 + 57.9 + seedOff(66, 1.5),
                3,
                2.0,
                0.5
            ) *
                40;
        double wz =
            z +
            fbm(
                x * 0.015 + 33.7 + seedOff(67, 1.0),
                z * 0.015 + 88.2 + seedOff(68, 2.2),
                3,
                2.0,
                0.5
            ) *
                30;
        double interior = Mth.clamp(1.0 - t * 2.5, 0.0, 1.0);
        interior = interior * interior * (3.0 - 2.0 * interior);
        if (interior > 0.01) {
            double ridge = Math.pow(
                ridgeNoise(
                    wx * 0.018 + 200.0 + seedOff(69, 5.0),
                    wz * 0.022 + 150.0 + seedOff(70, 3.0),
                    5,
                    2.0,
                    0.55
                ),
                0.6
            );
            double mtnDetail =
                fbm(
                    wx * 0.06 + 300.0 + seedOff(71, 4.0),
                    wz * 0.06 + 250.0 + seedOff(72, 2.0),
                    3,
                    2.0,
                    0.45
                ) * 0.3;
            raw +=
                MTN_HEIGHT *
                (ridge * 0.75 + mtnDetail * 0.25) *
                interior *
                falloff;
        }
        return raw;
    }

    // -------------------------------------------------------------------------
    // Высота колонки
    // -------------------------------------------------------------------------

    private int computeFloor(
        int x,
        int z,
        double t,
        double spRaw,
        double gridH
    ) {
        double wx =
            x +
            fbm(
                x * 0.005 + seedOff(81, 1.0),
                z * 0.005 + seedOff(82, 0.5),
                2,
                2.0,
                0.5
            ) *
                50;
        double wz =
            z +
            fbm(
                x * 0.005 + 31.7 + seedOff(83, 0.8),
                z * 0.005 + 47.3 + seedOff(84, 1.2),
                2,
                2.0,
                0.5
            ) *
                50;
        double h =
            BASE_FLOOR +
            fbm(
                wx * 0.004 + seedOff(85, 2.0),
                wz * 0.004 + seedOff(86, 1.5),
                4,
                2.0,
                0.55
            ) *
                30 +
            fbm(
                wx * 0.016 + seedOff(87, 0.7),
                wz * 0.016 + seedOff(88, 0.9),
                2,
                2.0,
                0.45
            ) *
                4 +
            fbm(
                wx * 0.05 + seedOff(89, 3.0),
                wz * 0.05 + seedOff(90, 2.0),
                2,
                2.0,
                0.4
            ) *
                1.5;
        int oceanFloor = Math.max(-63, Math.min((int) h, seaLevel - 4));
        double maxT = 1.0 + SPAWN_ISLAND_FEATHER / SPAWN_ISLAND_RADIUS;
        if (t < maxT) {
            int islandFloor = Math.max(
                oceanFloor,
                seaLevel + (int) Math.round(spRaw)
            );
            if (t <= 1.0) return clampFloor(islandFloor);
            double norm = (t - 1.0) / (maxT - 1.0);
            double blend = norm * norm * (3.0 - 2.0 * norm);
            return clampFloor((int) Math.round(
                islandFloor + (oceanFloor - islandFloor) * blend
            ));
        }
        if (gridH > 0.01) {
            int gridFloor = Math.max(
                oceanFloor,
                seaLevel + (int) Math.round(gridH)
            );
            double blend = Mth.clamp(gridH / 3.0, 0.0, 1.0);
            blend = blend * blend * (3.0 - 2.0 * blend);
            return clampFloor((int) Math.round(
                oceanFloor + (gridFloor - oceanFloor) * blend
            ));
        }
        return clampFloor(oceanFloor);
    }

    private static int clampFloor(int floor) {
        return Mth.clamp(floor, GEN_MIN_Y + 1, GEN_MAX_Y - 2);
    }

    // -------------------------------------------------------------------------
    // Поверхностные блоки
    // -------------------------------------------------------------------------

    private BlockState pickSurf(
        int x,
        int z,
        int fl,
        double t,
        double gridH,
        double gridDi,
        boolean beach
    ) {
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
        double n =
            fbm(x * 0.016, z * 0.016, 3, 2.0, 0.5) +
            fbm(x * 0.08, z * 0.08, 2, 2.0, 0.5) * 0.2;
        double th = 0.7 - dn * 0.2;
        BlockState base =
            n > th
                ? Blocks.GRAVEL.defaultBlockState()
                : Blocks.SAND.defaultBlockState();
        double det = hsh(x * 7, z * 13);
        if (
            base.is(Blocks.GRAVEL) && det < 0.03
        ) return Blocks.COBBLESTONE.defaultBlockState();
        if (
            base.is(Blocks.SAND) && det < 0.02
        ) return Blocks.CLAY.defaultBlockState();
        return base;
    }

    // -------------------------------------------------------------------------
    // Пляжи (по расстоянию до РЕАЛЬНОЙ воды) и классификация биомов
    // -------------------------------------------------------------------------

    public enum BiomeCategory {
        OCEAN,
        DEEP_OCEAN,
        BEACH,
        TROPICS,
        SAVANNA,
        VOLCANO,
    }

    private final ConcurrentHashMap<Long, BiomeCategory> BIOME_CACHE =
        new ConcurrentHashMap<>();
    private static final int BIOME_CACHE_MAX = 131072;

    private final ConcurrentHashMap<Long, Integer> FLOOR_CACHE =
        new ConcurrentHashMap<>();
    private static final int FLOOR_CACHE_MAX = 262144;
    private static final int HOT_CACHE_SIZE = 8192;
    private static final int HOT_CACHE_MASK = HOT_CACHE_SIZE - 1;
    private final ThreadLocal<FloorHotCache> FLOOR_HOT_CACHE =
        ThreadLocal.withInitial(FloorHotCache::new);
    private final ConcurrentHashMap<Long, Boolean> BEACH_CACHE =
        new ConcurrentHashMap<>();
    private static final int BEACH_CACHE_MAX = 131072;
    private final ThreadLocal<BooleanHotCache> BEACH_HOT_CACHE =
        ThreadLocal.withInitial(BooleanHotCache::new);
    private static final int CACHE_TRIM_BATCH = 256;
    private static final CacheTrimResult NO_CACHE_TRIM =
        new CacheTrimResult(0, 0L);

    private record CacheTrimResult(int removed, long elapsedNs) {}

    private static int hotCacheIndex(long key) {
        long mixed = key;
        mixed ^= mixed >>> 33;
        mixed *= 0xff51afd7ed558ccdl;
        mixed ^= mixed >>> 33;
        return (int) mixed & HOT_CACHE_MASK;
    }

    private static final class FloorHotCache {
        private long seed = Long.MIN_VALUE;
        private boolean initialized;
        private long[] keys;
        private int[] values;
        private boolean[] occupied;

        int get(long key, long currentSeed) {
            ensureSeed(currentSeed);
            int index = hotCacheIndex(key);
            return occupied[index] && keys[index] == key
                ? values[index]
                : Integer.MIN_VALUE;
        }

        void put(long key, int value, long currentSeed) {
            ensureSeed(currentSeed);
            int index = hotCacheIndex(key);
            keys[index] = key;
            values[index] = value;
            occupied[index] = true;
        }

        private void ensureSeed(long currentSeed) {
            if (initialized && seed == currentSeed) return;
            initialized = true;
            seed = currentSeed;
            keys = new long[HOT_CACHE_SIZE];
            values = new int[HOT_CACHE_SIZE];
            occupied = new boolean[HOT_CACHE_SIZE];
        }
    }

    private static final class BooleanHotCache {
        private long seed = Long.MIN_VALUE;
        private boolean initialized;
        private long[] keys;
        private byte[] values;

        int get(long key, long currentSeed) {
            ensureSeed(currentSeed);
            int index = hotCacheIndex(key);
            if (values[index] == 0 || keys[index] != key) return -1;
            return values[index] == 2 ? 1 : 0;
        }

        void put(long key, boolean value, long currentSeed) {
            ensureSeed(currentSeed);
            int index = hotCacheIndex(key);
            keys[index] = key;
            values[index] = (byte) (value ? 2 : 1);
        }

        private void ensureSeed(long currentSeed) {
            if (initialized && seed == currentSeed) return;
            initialized = true;
            seed = currentSeed;
            keys = new long[HOT_CACHE_SIZE];
            values = new byte[HOT_CACHE_SIZE];
        }
    }

    /**
     * Incremental bounded eviction. The previous implementation removed about
     * 40k entries synchronously after crossing 110% and produced visible
     * worldgen stalls. Small batches keep the map near its limit without one
     * long pause on the generation thread.
     */
    private static CacheTrimResult trimCache(
        ConcurrentHashMap<Long, ?> cache,
        int maxSize
    ) {
        int size = cache.size();
        if (size <= maxSize) return NO_CACHE_TRIM;
        long started = System.nanoTime();
        int removed = 0;
        int overflow = Math.max(1, size - maxSize);
        int toRemove = Math.min(
            size,
            Math.min(CACHE_TRIM_BATCH, overflow + CACHE_TRIM_BATCH - 1)
        );
        var iterator = cache.keySet().iterator();
        while (removed < toRemove && iterator.hasNext()) {
            if (cache.remove(iterator.next()) != null) removed++;
        }
        return new CacheTrimResult(
            removed,
            Math.max(0L, System.nanoTime() - started)
        );
    }

    private void updateDiagnosticCacheSizes() {
        diagnostics.cacheState(OceanGeneratorDiagnostics.Cache.FLOOR, FLOOR_CACHE.size(), FLOOR_CACHE_MAX);
        diagnostics.cacheState(OceanGeneratorDiagnostics.Cache.BIOME, BIOME_CACHE.size(), BIOME_CACHE_MAX);
        diagnostics.cacheState(OceanGeneratorDiagnostics.Cache.BEACH, BEACH_CACHE.size(), BEACH_CACHE_MAX);
        diagnostics.cacheState(OceanGeneratorDiagnostics.Cache.DECOR, DECOR_CACHE.size(), DECOR_CACHE_MAX);
        diagnostics.cacheState(OceanGeneratorDiagnostics.Cache.BOULDER, BOULDER_CACHE.size(), BOULDER_CACHE_MAX);
    }

    private static long colKey(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
    }

    /** Высота пола колонки с кэшем — нужна для поиска воды рядом с берегом. */
    private int floorAt(int x, int z) {
        long key = colKey(x, z);
        FloorHotCache hotCache = FLOOR_HOT_CACHE.get();
        int hot = hotCache.get(key, seed);
        if (hot != Integer.MIN_VALUE) {
            diagnostics.cacheAccess(OceanGeneratorDiagnostics.Cache.FLOOR, true);
            return hot;
        }
        Integer cached = FLOOR_CACHE.get(key);
        diagnostics.cacheAccess(OceanGeneratorDiagnostics.Cache.FLOOR, cached != null);
        if (cached != null) {
            hotCache.put(key, cached, seed);
            return cached;
        }
        double st = islandDist(x, z);
        double spRaw = islandH(x, z, st);
        double[] gridSample = GRID_SAMPLE_SCRATCH.get();
        gridIslandSample(x, z, gridSample);
        int fl = computeFloor(x, z, st, spRaw, gridSample[2]);
        FLOOR_CACHE.put(key, fl);
        hotCache.put(key, fl, seed);
        CacheTrimResult trim = trimCache(FLOOR_CACHE, FLOOR_CACHE_MAX);
        if (trim.removed() > 0) diagnostics.cacheTrim(
            OceanGeneratorDiagnostics.Cache.FLOOR,
            trim.removed(),
            trim.elapsedNs()
        );
        return fl;
    }

    /** Ширина пляжа в блоках, плавно меняется вдоль берега (12..40). */
    private double beachWidthAt(int x, int z) {
        double n = fbm(
            x * 0.015 + seedOff(120, 2.0),
            z * 0.015 + seedOff(121, 1.5),
            3,
            2.0,
            0.5
        );
        return 12.0 + n * 28.0;
    }

    private static final int[][] BEACH_DIRS = {
        { 1, 0 },
        { -1, 0 },
        { 0, 1 },
        { 0, -1 },
        { 1, 1 },
        { 1, -1 },
        { -1, 1 },
        { -1, -1 },
    };

    /**
     * Пляж = близость к реальной воде. Сканируем 8 направлений: если в пределах
     * beachWidth есть колонка с fl &lt; seaLevel — это пляж. Колонка у самой кромки
     * всегда находит воду на расстоянии 1, поэтому песок гарантированно доходит
     * до океана без разрывов. Высота используется только чтобы убрать песок
     * с береговых обрывов (fl &gt; seaLevel + 5).
     */
    private boolean isBeach(int x, int z, int fl) {
        if (fl < seaLevel || fl > seaLevel + 5) return false;
        long key = colKey(x, z);
        BooleanHotCache hotCache = BEACH_HOT_CACHE.get();
        int hot = hotCache.get(key, seed);
        if (hot >= 0) {
            diagnostics.cacheAccess(OceanGeneratorDiagnostics.Cache.BEACH, true);
            return hot == 1;
        }
        Boolean cached = BEACH_CACHE.get(key);
        diagnostics.cacheAccess(OceanGeneratorDiagnostics.Cache.BEACH, cached != null);
        if (cached != null) {
            hotCache.put(key, cached, seed);
            return cached;
        }

        int width = (int) beachWidthAt(x, z);
        int farthestSample = 1;
        for (int d = 1; d <= width; d += d < 4 ? 1 : 3) {
            farthestSample = d;
        }
        boolean beach = false;
        int floorSamples = 0;
        search:
        for (int[] dir : BEACH_DIRS) {
            // Most eligible low columns are close to the coast. Testing the
            // far edge first proves "water within width" with one lookup in
            // the common case while preserving the original near scan as a
            // fallback for coves and narrow channels.
            floorSamples++;
            if (
                floorAt(
                    x + dir[0] * farthestSample,
                    z + dir[1] * farthestSample
                ) < seaLevel
            ) {
                beach = true;
                break;
            }
            for (int d = 1; d <= width; d += d < 4 ? 1 : 3) {
                if (d == farthestSample) continue;
                floorSamples++;
                if (floorAt(x + dir[0] * d, z + dir[1] * d) < seaLevel) {
                    beach = true;
                    break search;
                }
            }
        }
        diagnostics.add(OceanGeneratorDiagnostics.Counter.BEACH_SEARCHES, 1L);
        diagnostics.add(
            OceanGeneratorDiagnostics.Counter.BEACH_FLOOR_SAMPLES,
            floorSamples
        );
        BEACH_CACHE.put(key, beach);
        hotCache.put(key, beach, seed);
        CacheTrimResult trim = trimCache(BEACH_CACHE, BEACH_CACHE_MAX);
        if (trim.removed() > 0) diagnostics.cacheTrim(
            OceanGeneratorDiagnostics.Cache.BEACH,
            trim.removed(),
            trim.elapsedNs()
        );
        return beach;
    }

    /**
     * A generated, decoration-independent spawn target on the main island.
     *
     * <p>The returned position is the player's feet position. The caller must
     * still load the chunk and verify that later decoration or player changes
     * did not obstruct the two blocks above the sand.</p>
     */
    public record SpawnBeachPosition(BlockPos feet, int oceanDistance) {}

    /**
     * Finds a broad, mostly flat beach on the spawn island. The search begins
     * in the ocean and walks inward, so inland ponds cannot be mistaken for
     * the outer coast. {@code preferredOceanDistance} is clamped to 1..3.
     *
     * @return a predicted beach spawn, or {@code null} if no suitable coast
     *         exists for this terrain seed
     */
    public SpawnBeachPosition findSpawnBeachPosition(
        int preferredOceanDistance,
        long searchSalt
    ) {
        int oceanDistance = Mth.clamp(preferredOceanDistance, 1, 3);
        long mixedSalt = mixSpawnSalt(searchSalt ^ seed);
        int firstDirection = Math.floorMod(
            (int) (mixedSalt ^ (mixedSalt >>> 32)),
            SPAWN_BEACH_SEARCH_DIRECTIONS
        );
        int directionStep = (mixedSalt & 1L) == 0L ? 1 : -1;

        for (int attempt = 0; attempt < SPAWN_BEACH_SEARCH_DIRECTIONS; attempt++) {
            int directionIndex = Math.floorMod(
                firstDirection + attempt * directionStep,
                SPAWN_BEACH_SEARCH_DIRECTIONS
            );
            double angle =
                directionIndex * (Math.PI * 2.0 / SPAWN_BEACH_SEARCH_DIRECTIONS);
            double outwardX = Math.cos(angle);
            double outwardZ = Math.sin(angle);

            int previousRadius = SPAWN_BEACH_SEARCH_OUTER_RADIUS;
            int previousX = (int) Math.round(outwardX * previousRadius);
            int previousZ = (int) Math.round(outwardZ * previousRadius);
            if (floorAt(previousX, previousZ) >= seaLevel) continue;

            for (
                int radius = previousRadius - SPAWN_BEACH_SEARCH_STEP;
                radius >= SPAWN_BEACH_SEARCH_INNER_RADIUS;
                radius -= SPAWN_BEACH_SEARCH_STEP
            ) {
                int x = (int) Math.round(outwardX * radius);
                int z = (int) Math.round(outwardZ * radius);
                if (floorAt(x, z) < seaLevel) {
                    previousRadius = radius;
                    previousX = x;
                    previousZ = z;
                    continue;
                }

                int coastX = x;
                int coastZ = z;
                for (
                    int fineRadius = previousRadius - 1;
                    fineRadius >= radius;
                    fineRadius--
                ) {
                    int fineX = (int) Math.round(outwardX * fineRadius);
                    int fineZ = (int) Math.round(outwardZ * fineRadius);
                    if (floorAt(fineX, fineZ) >= seaLevel) {
                        coastX = fineX;
                        coastZ = fineZ;
                        break;
                    }
                    previousX = fineX;
                    previousZ = fineZ;
                }

                int[] oceanDirection = adjacentOceanDirection(
                    coastX,
                    coastZ,
                    outwardX,
                    outwardZ,
                    previousX,
                    previousZ
                );
                if (oceanDirection == null) break;

                int targetX =
                    coastX - oceanDirection[0] * (oceanDistance - 1);
                int targetZ =
                    coastZ - oceanDirection[1] * (oceanDistance - 1);
                if (
                    nearestOceanDistance(targetX, targetZ, 3) != oceanDistance ||
                    !isPredictedSpawnSand(targetX, targetZ) ||
                    !isFlatSpawnColumn(targetX, targetZ)
                ) {
                    break;
                }

                SpawnBeachPosition candidate = new SpawnBeachPosition(
                    new BlockPos(targetX, floorAt(targetX, targetZ) + 1, targetZ),
                    oceanDistance
                );
                if (
                    isBroadSpawnBeach(
                        coastX,
                        coastZ,
                        oceanDirection[0],
                        oceanDirection[1]
                    )
                ) {
                    return candidate;
                }
                break;
            }
        }
        return null;
    }

    private int[] adjacentOceanDirection(
        int coastX,
        int coastZ,
        double expectedOutwardX,
        double expectedOutwardZ,
        int previousOceanX,
        int previousOceanZ
    ) {
        int[] best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (int[] direction : BEACH_DIRS) {
            if (
                floorAt(
                    coastX + direction[0],
                    coastZ + direction[1]
                ) >= seaLevel
            ) continue;

            double score =
                direction[0] * expectedOutwardX +
                direction[1] * expectedOutwardZ;
            if (
                coastX + direction[0] == previousOceanX &&
                coastZ + direction[1] == previousOceanZ
            ) {
                score += 0.25;
            }
            if (score > bestScore) {
                bestScore = score;
                best = direction;
            }
        }
        return best;
    }

    private int nearestOceanDistance(int x, int z, int maxDistance) {
        for (int distance = 1; distance <= maxDistance; distance++) {
            for (int dx = -distance; dx <= distance; dx++) {
                for (int dz = -distance; dz <= distance; dz++) {
                    if (
                        Math.max(Math.abs(dx), Math.abs(dz)) != distance
                    ) continue;
                    if (floorAt(x + dx, z + dz) < seaLevel) return distance;
                }
            }
        }
        return -1;
    }

    private boolean isPredictedSpawnSand(int x, int z) {
        int floor = floorAt(x, z);
        if (floor < seaLevel || floor > seaLevel + 5) return false;
        double spawnIslandDistance = islandDist(x, z);
        double maxDistance =
            1.0 + SPAWN_ISLAND_FEATHER / SPAWN_ISLAND_RADIUS;
        return spawnIslandDistance < maxDistance && isBeach(x, z, floor);
    }

    private boolean isFlatSpawnColumn(int x, int z) {
        int centerFloor = floorAt(x, z);
        int landNeighbors = 0;
        for (int i = 0; i < 4; i++) {
            int[] direction = BEACH_DIRS[i];
            int neighborFloor = floorAt(
                x + direction[0],
                z + direction[1]
            );
            if (neighborFloor < seaLevel) continue;
            landNeighbors++;
            if (Math.abs(neighborFloor - centerFloor) > 1) return false;
        }
        return landNeighbors >= 2;
    }

    private boolean isBroadSpawnBeach(
        int coastX,
        int coastZ,
        int oceanDirectionX,
        int oceanDirectionZ
    ) {
        int coastFloor = floorAt(coastX, coastZ);
        int inlandDepth = 0;
        for (int step = 0; step < SPAWN_BEACH_MIN_INLAND_DEPTH; step++) {
            int x = coastX - oceanDirectionX * step;
            int z = coastZ - oceanDirectionZ * step;
            int floor = floorAt(x, z);
            if (
                !isPredictedSpawnSand(x, z) ||
                Math.abs(floor - coastFloor) > 2
            ) break;
            inlandDepth++;
        }
        if (inlandDepth < SPAWN_BEACH_MIN_INLAND_DEPTH) return false;

        int tangentX = -oceanDirectionZ;
        int tangentZ = oceanDirectionX;
        int tangentCenterX = coastX - oceanDirectionX * 2;
        int tangentCenterZ = coastZ - oceanDirectionZ * 2;
        int validTangentColumns = 0;
        for (
            int step = -SPAWN_BEACH_TANGENT_HALF_WIDTH;
            step <= SPAWN_BEACH_TANGENT_HALF_WIDTH;
            step++
        ) {
            int x = tangentCenterX + tangentX * step;
            int z = tangentCenterZ + tangentZ * step;
            int floor = floorAt(x, z);
            if (
                isPredictedSpawnSand(x, z) &&
                Math.abs(floor - coastFloor) <= 2
            ) {
                validTangentColumns++;
            }
        }
        return validTangentColumns >= SPAWN_BEACH_TANGENT_HALF_WIDTH * 2;
    }

    private static long mixSpawnSalt(long value) {
        value = (value ^ (value >>> 30)) * 0xBF58476D1CE4E5B9L;
        value = (value ^ (value >>> 27)) * 0x94D049BB133111EBL;
        return value ^ (value >>> 31);
    }

    /**
     * Определяет, является ли позиция "проплешиной" (tropics) на острове.
     * Генерирует МАКСИМУМ 2 центра проплешин на основе хеша центра острова.
     * Проплешины большие: 35-60% от радиуса острова.
     */
    private boolean isIslandClearing(int x, int z) {
        int ix, iz;
        double radius;
        long islandHash;

        // Проверяем спавн-остров (отдельная система от grid)
        double st = islandDist(x, z);
        if (st <= 1.0) {
            // На спавн-острове — центр в (0, 0), радиус SPAWN_ISLAND_RADIUS
            ix = 0;
            iz = 0;
            radius = SPAWN_ISLAND_RADIUS;
            islandHash = rawHash(0, 0); // фиксированный хеш для спавна
        } else {
            // Ищем grid-остров
            int cellX = Math.floorDiv(x, CELL),
                cellZ = Math.floorDiv(z, CELL);
            int bestCx = 0,
                bestCz = 0;
            double bestDistSq = Double.MAX_VALUE;
            double bestRadius = 0;

            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    int cx = cellX + dx,
                        cz = cellZ + dz;
                    double rHash = hsh(cx * 11, cz * 13);
                    if (rHash > 0.6) continue;
                    double r = 80 + rHash * 120;
                    int cix =
                        cx * CELL + 768 + (int) (hsh(cx * 2, cz * 2) * 512);
                    int ciz =
                        cz * CELL +
                        768 +
                        (int) (hsh(cx * 2 + 1, cz * 2 + 1) * 512);
                    double dSq =
                        (double) (x - cix) * (x - cix) +
                        (double) (z - ciz) * (z - ciz);
                    if (dSq < bestDistSq) {
                        bestDistSq = dSq;
                        bestCx = cx;
                        bestCz = cz;
                        bestRadius = r;
                    }
                }
            }

            if (bestRadius < 1) return false;

            ix =
                bestCx * CELL + 768 + (int) (hsh(bestCx * 2, bestCz * 2) * 512);
            iz =
                bestCz * CELL +
                768 +
                (int) (hsh(bestCx * 2 + 1, bestCz * 2 + 1) * 512);
            radius = bestRadius;
            islandHash = rawHash(bestCx * 37, bestCz * 41);
        }

        // Относительная позиция от центра (-1..+1)
        double relX = (x - ix) / radius;
        double relZ = (z - iz) / radius;

        // Генерируем 2 центра проплешин на основе хеша острова
        for (int i = 0; i < 2; i++) {
            long h = rawHash(
                (int) (islandHash) +i * 1000,
                (int) (islandHash >> 32) + i * 1000
            );
            double clearRelX = ((h & 0xFFFF) / (double) 0xFFFF - 0.5) * 1.2;
            double clearRelZ =
                (((h >> 16) & 0xFFFF) / (double) 0xFFFF - 0.5) * 1.2;
            double clearRadius = 0.35 + (((h >> 32) & 0xFF) / 255.0) * 0.25;

            double distSq =
                (relX - clearRelX) * (relX - clearRelX) +
                (relZ - clearRelZ) * (relZ - clearRelZ);
            if (distSq < clearRadius * clearRadius) {
                return true;
            }
        }
        return false;
    }

    /** Классификация биома для колонки. Используется OceanBiomeSource (F3) и генерацией. */
    public BiomeCategory classifyBiome(int x, int z) {
        long key = colKey(x, z);
        BiomeCategory cached = BIOME_CACHE.get(key);
        diagnostics.cacheAccess(OceanGeneratorDiagnostics.Cache.BIOME, cached != null);
        if (cached != null) return cached;

        int fl = floorAt(x, z);

        BiomeCategory result;
        double[] gridSample = GRID_SAMPLE_SCRATCH.get();
        gridIslandSample(x, z, gridSample);
        boolean volcanicLand = gridSample[3] > 0.5 && fl >= seaLevel;
        if (volcanicLand) {
            result = BiomeCategory.VOLCANO;
        } else if (fl < seaLevel) {
            result =
                seaLevel - fl > 28
                    ? BiomeCategory.DEEP_OCEAN
                    : BiomeCategory.OCEAN;
        } else if (isBeach(x, z, fl)) {
            result = BiomeCategory.BEACH;
        } else {
            boolean clearing = isIslandClearing(x, z);
            result = clearing ? BiomeCategory.TROPICS : BiomeCategory.SAVANNA;
        }

        BIOME_CACHE.put(key, result);
        CacheTrimResult trim = trimCache(BIOME_CACHE, BIOME_CACHE_MAX);
        if (trim.removed() > 0) diagnostics.cacheTrim(
            OceanGeneratorDiagnostics.Cache.BIOME,
            trim.removed(),
            trim.elapsedNs()
        );
        return result;
    }

    /**
     * Угловое расстояние до ближайшего широкого лавового языка. Поле зависит только
     * от мировых координат и центра конкретного вулкана, поэтому бесшовно между чанками.
     * result[0] — расстояние, result[1] — индекс ближайшего потока.
     */
    private double volcanicFlowDistance(
        int x,
        int z,
        double islandT,
        double centerX,
        double centerZ,
        long volcanoHash,
        int[] nearestIndexOut
    ) {
        double angle = Math.atan2(z - centerZ, x - centerX);
        double nearest = Math.PI;
        int nearestIndex = -1;
        for (int flow = 0; flow < 5; flow++) {
            double baseAngle = frac(volcanoHash >> (flow * 11 + 3)) * Math.PI * 2.0 - Math.PI;
            double phase = frac(volcanoHash >> (flow * 9 + 7)) * Math.PI * 2.0;
            double meander =
                Math.sin(islandT * 15.0 + phase) * 0.040 +
                Math.sin(islandT * 31.0 - phase * 0.7) * 0.016;
            double delta = Math.abs(Math.atan2(
                Math.sin(angle - baseAngle - meander),
                Math.cos(angle - baseAngle - meander)
            ));
            if (delta < nearest) {
                nearest = delta;
                nearestIndex = flow;
            }
        }
        if (nearestIndexOut != null) nearestIndexOut[0] = nearestIndex;
        return nearest;
    }

    /**
     * Цельная гавайская палитра с высотной зональностью, как на реальном
     * вулканическом острове: чёрный пляж → зелёное живое подножие → пепловые
     * поля из туфа → тёмный базальтовый конус → раскалённая кальдера.
     * Сквозь все пояса к океану прорезаются застывшие чёрные лавовые потоки.
     */
    private BlockState volcanicBiomeSurface(
        int x,
        int z,
        int floor,
        double islandT,
        boolean crater,
        int lavaLevel,
        double centerX,
        double centerZ
    ) {
        double broad = fbm(x * 0.012 + 503.0, z * 0.012 - 277.0, 3, 2.0, 0.52);
        double grain = hsh(x * 43 + floor * 7, z * 47 - floor * 11);
        long volcanoHash = rawHash((int) centerX * 17, (int) centerZ * 19);
        double flowDistance = volcanicFlowDistance(
            x,
            z,
            islandT,
            centerX,
            centerZ,
            volcanoHash,
            null
        );

        double flowWidth = 0.040 + Mth.clamp((islandT - 0.20) / 0.70, 0.0, 1.0) * 0.070;
        double flowEdge = flowDistance / flowWidth;
        boolean frozenFlow = islandT > 0.21 && islandT < 0.97 && flowEdge < 1.0;
        double beachEdge = 0.91 + (broad - 0.5) * 0.045;

        if (islandT > beachEdge || floor <= seaLevel + 2) {
            // Чёрный вулканический пляж: тёмный «песок» из гравия с выходами
            // базальта, как на побережьях Исландии и Гавайев.
            if (grain < 0.66) return Blocks.GRAVEL.defaultBlockState();
            return grain < 0.86
                ? Blocks.BASALT.defaultBlockState()
                : Blocks.BLACKSTONE.defaultBlockState();
        }

        if (crater && floor <= lavaLevel + 1 && grain < 0.16) {
            return Blocks.MAGMA_BLOCK.defaultBlockState();
        }

        if (frozenFlow) {
            // Внутри каждого языка блоки перемешаны мелко, но края потока остаются ясными.
            double flowMix = grain + (broad - 0.5) * 0.24;
            if (flowEdge < 0.34 && flowMix < 0.58) {
                return Blocks.SMOOTH_BASALT.defaultBlockState();
            }
            return flowMix < 0.52
                ? Blocks.BASALT.defaultBlockState()
                : Blocks.BLACKSTONE.defaultBlockState();
        }

        // Зелёный пояс подножия: густая растительная кайма, через которую
        // потоки лавы читаются как чёрные шрамы. Именно этот контраст делает
        // остров «живым» — как молодые склоны Килауэа.
        double greenEdge = 0.665 + (broad - 0.5) * 0.06;
        if (islandT > greenEdge) {
            double soil = grain + (broad - 0.5) * 0.20;
            if (soil < 0.80) return Blocks.GRASS_BLOCK.defaultBlockState();
            return soil < 0.92
                ? Blocks.COARSE_DIRT.defaultBlockState()
                : Blocks.BLACKSTONE.defaultBlockState();
        }

        // Пепловые поля средней части склона: туф (спрессованный вулканический
        // пепел) с пятнами гравия-шлака и выходами базальта.
        double ashEdge = 0.36 + (broad - 0.5) * 0.05;
        if (islandT > ashEdge) {
            double ashBlend = Mth.clamp(
                (islandT - ashEdge) / Math.max(0.01, greenEdge - ashEdge),
                0.0,
                1.0
            );
            double ashMix = grain + (broad - 0.5) * 0.26;
            // Чем ближе к зелёному поясу, тем больше пепла и шлака; чем выше,
            // тем чаще пробивается голый тёмный камень.
            if (ashMix < 0.30 + ashBlend * 0.28) {
                return Blocks.TUFF.defaultBlockState();
            }
            if (ashMix < 0.46 + ashBlend * 0.28) {
                return Blocks.GRAVEL.defaultBlockState();
            }
            return ashMix < 0.78
                ? Blocks.BASALT.defaultBlockState()
                : Blocks.BLACKSTONE.defaultBlockState();
        }

        // Верхний конус: тёмная спёкшаяся порода. Blackstone и basalt
        // смешиваются на масштабе блоков, у самой кальдеры больше гладкого
        // базальта — свежие остывшие излияния.
        double slopeMix = grain + (broad - 0.5) * 0.30;
        double summitness = Mth.clamp(1.0 - islandT / Math.max(0.01, ashEdge), 0.0, 1.0);
        if (slopeMix < 0.14 + summitness * 0.18) {
            return Blocks.SMOOTH_BASALT.defaultBlockState();
        }
        double blackstoneChance = 0.56 + summitness * 0.10;
        return slopeMix < blackstoneChance
            ? Blocks.BLACKSTONE.defaultBlockState()
            : Blocks.BASALT.defaultBlockState();
    }

    /** Одна и та же вулканическая толща для чанка и запросов getBaseColumn(). */
    private BlockState volcanicSubsurface(
        int x,
        int y,
        int z,
        double islandT,
        boolean crater,
        int lavaLevel,
        double fissure,
        int strataShift
    ) {
        double rock = hsh(x * 43 + y, z * 47 - y);
        boolean ashStrata = Math.floorMod(y + strataShift, 6) < 2;
        if (crater && y <= lavaLevel + 1 && rock < 0.18) {
            return Blocks.MAGMA_BLOCK.defaultBlockState();
        }
        if (crater && y <= lavaLevel && rock < 0.30) {
            return Blocks.OBSIDIAN.defaultBlockState();
        }
        if (fissure > 0.91 && rock < 0.22) {
            return Blocks.MAGMA_BLOCK.defaultBlockState();
        }
        if (rock < 0.16) return Blocks.BLACKSTONE.defaultBlockState();
        if (ashStrata && rock < 0.52) return Blocks.TUFF.defaultBlockState();
        if (rock < 0.34) return Blocks.SMOOTH_BASALT.defaultBlockState();
        return islandT > 0.58 && rock < 0.62
            ? Blocks.BLACKSTONE.defaultBlockState()
            : Blocks.BASALT.defaultBlockState();
    }

    private BlockState subSurf(
        int x,
        int z,
        int fl,
        double t,
        double spRaw,
        double gridH,
        boolean beach
    ) {
        if (beach) return Blocks.SAND.defaultBlockState();
        if (fl >= seaLevel) {
            boolean isIsland = spRaw > 0.0 || gridH > 0.5;
            return isIsland
                ? Blocks.DIRT.defaultBlockState()
                : Blocks.SAND.defaultBlockState();
        }
        double maxT = 1.0 + SPAWN_ISLAND_FEATHER / SPAWN_ISLAND_RADIUS;
        if (t < maxT || gridH > 0.01) {
            return hsh(x * 11, z * 17) < 0.6
                ? Blocks.SAND.defaultBlockState()
                : Blocks.GRAVEL.defaultBlockState();
        }
        return null;
    }

    private BlockState slab(BlockState s) {
        if (
            s.is(Blocks.SAND) || s.is(Blocks.CLAY)
        ) return Blocks.SANDSTONE_SLAB.defaultBlockState();
        if (
            s.is(Blocks.GRAVEL) || s.is(Blocks.COBBLESTONE)
        ) return Blocks.COBBLESTONE_SLAB.defaultBlockState();
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
        return (
            fbm(x * 0.08, z * 0.08, 2, 2.0, 0.5) > 0.3 &&
            hsh(x * 31, z * 37) < ch
        );
    }

    // -------------------------------------------------------------------------
    // Генерация чанка
    // -------------------------------------------------------------------------

    @Override
    public CompletableFuture<ChunkAccess> fillFromNoise(
        Blender blender,
        RandomState randomState,
        StructureManager structureManager,
        ChunkAccess chunk
    ) {
        if (chunk.getLevel() instanceof WorldGenLevel wgl) syncSeedFromLevel(
            wgl
        );

        ChunkPos cp = chunk.getPos();
        int cx = cp.x,
            cz = cp.z;

        OceanGeneratorDiagnostics.Token benchmark = diagnostics.begin(
            OceanGeneratorDiagnostics.Stage.FILL_NOISE,
            cp.toLong(),
            seed
        );
        Throwable benchmarkError = null;
        long sampleNs = 0L;
        long cacheNs = 0L;
        long classifyNs = 0L;
        long prepareNs = 0L;
        long writeNs = 0L;
        int landColumns = 0;
        int oceanColumns = 0;
        int spawnIslandColumns = 0;
        int gridIslandColumns = 0;
        int volcanoColumns = 0;
        int craterColumns = 0;
        int beachColumns = 0;
        int slopeColumns = 0;
        int underwaterSlabs = 0;
        int seagrassBlocks = 0;
        long solidBlockWrites = 0L;
        long waterBlockWrites = 0L;
        long lavaBlockWrites = 0L;
        try {

        if (cx == 0 && cz == 0 && spawnDebugLogged.compareAndSet(false, true)) {
            double st0 = islandDist(0, 0);
            double sp0 = islandH(0, 0, st0);
            double gH0 = gridIslandH(0, 0);
            double gDist0 = gridIslandDist(0, 0);
            int fl0 = computeFloor(0, 0, st0, sp0, gH0);
            log.info(
                "[DEBUG] seed={} spawnDist={} spawnH={} gridH={} gridDist={} floor={}",
                seed,
                String.format("%.4f", st0),
                String.format("%.4f", sp0),
                String.format("%.4f", gH0),
                String.format("%.4f", gDist0),
                fl0
            );
        }

        long sampleStarted = System.nanoTime();
        // Плоские массивы убирают сотни короткоживущих объектов на каждый чанк.
        int[] heights = new int[LOCAL_SAMPLE_AREA];
        double[] dists = new double[LOCAL_SAMPLE_AREA];
        double[] islands = new double[LOCAL_SAMPLE_AREA];
        double[] grids = new double[LOCAL_SAMPLE_AREA];
        double[] gridDi = new double[LOCAL_SAMPLE_AREA];
        double[] volcanoCenterX = new double[LOCAL_SAMPLE_AREA];
        double[] volcanoCenterZ = new double[LOCAL_SAMPLE_AREA];
        byte[] terrainFlags = new byte[LOCAL_SAMPLE_AREA];
        int[] lavaLevels = new int[LOCAL_SAMPLE_AREA];
        double[] grid = new double[8];

        ColumnCache colCache = new ColumnCache();
        int maxFl = Integer.MIN_VALUE;
        int maxLavaLevel = seaLevel;

        for (int lx = -1; lx <= 16; lx++) {
            for (int lz = -1; lz <= 16; lz++) {
                int wx = cx * 16 + lx,
                    wz = cz * 16 + lz;
                double st = islandDist(wx, wz);
                double spRaw = islandH(wx, wz, st);
                gridIslandSample(wx, wz, grid);
                double gridD = grid[0];
                double gridH = grid[2];
                int fl = computeFloor(wx, wz, st, spRaw, gridH);
                int sampleIndex =
                    (lx + 1) * LOCAL_SAMPLE_SIZE + lz + 1;

                dists[sampleIndex] = st;
                islands[sampleIndex] = spRaw;
                grids[sampleIndex] = gridH;
                gridDi[sampleIndex] = gridD;
                volcanoCenterX[sampleIndex] = grid[5];
                volcanoCenterZ[sampleIndex] = grid[6];
                int terrainFlag = grid[3] > 0.5 ? FLAG_VOLCANO : 0;
                if (grid[4] > 0.5) {
                    terrainFlag |= FLAG_CRATER;
                    maxLavaLevel = Math.max(
                        maxLavaLevel,
                        (int) Math.round(grid[7])
                    );
                }
                terrainFlags[sampleIndex] = (byte) terrainFlag;
                lavaLevels[sampleIndex] = (int) Math.round(grid[7]);
                heights[sampleIndex] = fl;

                if (fl > maxFl) maxFl = fl;

                if (lx >= 0 && lx < 16 && lz >= 0 && lz < 16) {
                    int idx = (lx << 4) | lz;
                    colCache.floor[idx] = fl;
                    int flags = spRaw > 0.0 || gridH > 0.5 ? FLAG_ISLAND : 0;
                    if (grid[3] > 0.5) flags |= FLAG_VOLCANO;
                    colCache.flags[idx] = (byte) flags;
                }
            }
        }
        sampleNs = System.nanoTime() - sampleStarted;
        diagnostics.phase(
            benchmark,
            OceanGeneratorDiagnostics.Phase.FILL_SAMPLE_TERRAIN,
            sampleNs
        );

        long cacheStarted = System.nanoTime();
        DECOR_CACHE.put(cp.toLong(), colCache);
        CacheTrimResult decorTrim = trimCache(DECOR_CACHE, DECOR_CACHE_MAX);
        if (decorTrim.removed() > 0) diagnostics.cacheTrim(
            OceanGeneratorDiagnostics.Cache.DECOR,
            decorTrim.removed(),
            decorTrim.elapsedNs()
        );
        updateDiagnosticCacheSizes();
        cacheNs = System.nanoTime() - cacheStarted;
        diagnostics.phase(
            benchmark,
            OceanGeneratorDiagnostics.Phase.FILL_PUBLISH_CACHE,
            cacheNs
        );

        long classifyStarted = System.nanoTime();
        int[] dirtLayerCounts = new int[256];
        int[] volcanicStrataShifts = new int[256];
        byte[] underwaterPlants = new byte[256];
        boolean[] underwaterSlabColumns = new boolean[256];
        BlockState[] surfaceStates = new BlockState[256];
        BlockState[] subsurfaceStates = new BlockState[256];
        double[] volcanicFissures = new double[256];
        double maxT = 1.0 + SPAWN_ISLAND_FEATHER / SPAWN_ISLAND_RADIUS;

        // Surface classification is intentionally separate from section writes.
        // It contains shoreline searches and material selection, so expensive
        // terrain math can no longer masquerade as slow block I/O in reports.
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int wx = cx * 16 + lx;
                int wz = cz * 16 + lz;
                int columnIndex = (lx << 4) | lz;
                int sampleIndex = (lx + 1) * LOCAL_SAMPLE_SIZE + lz + 1;
                int fl = heights[sampleIndex];
                double st = dists[sampleIndex];
                double spRaw = islands[sampleIndex];
                double gridHi = grids[sampleIndex];
                double gridDist = gridDi[sampleIndex];
                boolean volcano = (terrainFlags[sampleIndex] & FLAG_VOLCANO) != 0;
                boolean crater = (terrainFlags[sampleIndex] & FLAG_CRATER) != 0;
                int lavaLevel = lavaLevels[sampleIndex];
                boolean onSlope =
                    fl < heights[sampleIndex - LOCAL_SAMPLE_SIZE] ||
                    fl < heights[sampleIndex + LOCAL_SAMPLE_SIZE] ||
                    fl < heights[sampleIndex - 1] ||
                    fl < heights[sampleIndex + 1];
                boolean onIsland = spRaw > 0.0 || gridHi > 0.5;

                if (fl < seaLevel) oceanColumns++;
                else landColumns++;
                if (spRaw > 0.0) spawnIslandColumns++;
                if (gridHi > 0.5) gridIslandColumns++;
                if (volcano) volcanoColumns++;
                if (crater) craterColumns++;
                if (onSlope) slopeColumns++;

                int minNeighbor = Math.min(
                    Math.min(
                        heights[sampleIndex - LOCAL_SAMPLE_SIZE],
                        heights[sampleIndex + LOCAL_SAMPLE_SIZE]
                    ),
                    Math.min(
                        heights[sampleIndex - 1],
                        heights[sampleIndex + 1]
                    )
                );
                int cliffDrop = Math.max(0, fl - minNeighbor);
                int dirtLayers;
                if (volcano) {
                    dirtLayers = gridDist > 0.70
                        ? Math.max(3, Math.min(cliffDrop + 2, 8))
                        : Math.max(3, Math.min(cliffDrop + 2, 24));
                } else if (fl >= seaLevel && onIsland) {
                    dirtLayers = 3;
                } else if (st < maxT) {
                    dirtLayers = 2;
                } else {
                    dirtLayers = 0;
                }
                dirtLayerCounts[columnIndex] = dirtLayers;

                boolean beach = isBeach(wx, wz, fl);
                if (beach) beachColumns++;
                BlockState surface = volcano
                    ? volcanicBiomeSurface(
                        wx,
                        wz,
                        fl,
                        gridDist,
                        crater,
                        lavaLevel,
                        volcanoCenterX[sampleIndex],
                        volcanoCenterZ[sampleIndex]
                    )
                    : pickSurf(wx, wz, fl, st, gridHi, gridDist, beach);
                surfaceStates[columnIndex] = surface;

                if (dirtLayers > 0) {
                    if (volcano && gridDist <= 0.70) {
                        volcanicFissures[columnIndex] = ridgeNoise(
                            wx * 0.055 + 811.0,
                            wz * 0.055 - 419.0,
                            2,
                            2.0,
                            0.5
                        );
                        volcanicStrataShifts[columnIndex] =
                            (int) (hsh(wx >> 4, wz >> 4) * 5);
                    } else {
                        subsurfaceStates[columnIndex] = volcano
                            ? (hsh(wx * 181, wz * 191) < 0.72
                                ? Blocks.DIRT.defaultBlockState()
                                : Blocks.BASALT.defaultBlockState())
                            : subSurf(wx, wz, fl, st, spRaw, gridHi, beach);
                    }
                }

                if (fl < seaLevel) {
                    if (onSlope && onIsland) {
                        underwaterSlabColumns[columnIndex] = true;
                    } else if (hasGrass(wx, wz, fl)) {
                        underwaterPlants[columnIndex] = (byte) (
                            hsh(wx * 17, wz * 23) < 0.3 ? 2 : 1
                        );
                    }
                }
            }
        }
        classifyNs = System.nanoTime() - classifyStarted;
        diagnostics.phase(
            benchmark,
            OceanGeneratorDiagnostics.Phase.FILL_CLASSIFY_SURFACE,
            classifyNs
        );

        long prepareStarted = System.nanoTime();
        int minY = chunk.getMinBuildHeight();
        int maxWorkY = Math.max(maxFl + 2, maxLavaLevel);

        int minSec = chunk.getSectionIndex(minY);
        int maxSec = chunk.getSectionIndex(maxWorkY);
        for (int i = minSec; i <= maxSec; i++) chunk.getSection(i).acquire();

        Heightmap hmOcean = chunk.getOrCreateHeightmapUnprimed(
            Heightmap.Types.OCEAN_FLOOR_WG
        );
        Heightmap hmSurface = chunk.getOrCreateHeightmapUnprimed(
            Heightmap.Types.WORLD_SURFACE_WG
        );
        prepareNs = System.nanoTime() - prepareStarted;
        diagnostics.phase(
            benchmark,
            OceanGeneratorDiagnostics.Phase.FILL_PREPARE_SECTIONS,
            prepareNs
        );

        long writeStarted = System.nanoTime();
        try {
            for (int lx = 0; lx < 16; lx++) {
                for (int lz = 0; lz < 16; lz++) {
                    int wx = cx * 16 + lx,
                        wz = cz * 16 + lz;
                    int sampleIndex =
                        (lx + 1) * LOCAL_SAMPLE_SIZE + lz + 1;
                    int columnIndex = (lx << 4) | lz;
                    int fl = heights[sampleIndex];
                    double gridDist = gridDi[sampleIndex];
                    boolean volcano =
                        (terrainFlags[sampleIndex] & FLAG_VOLCANO) != 0;
                    boolean crater =
                        (terrainFlags[sampleIndex] & FLAG_CRATER) != 0;
                    int lavaLevel = lavaLevels[sampleIndex];
                    int dirtLayers = dirtLayerCounts[columnIndex];
                    BlockState surf = surfaceStates[columnIndex];
                    solidBlockWrites += fl - (long) minY + 1L;

                    setDirect(chunk, lx, minY, lz, BEDROCK_S);
                    fillYRange(
                        chunk,
                        lx,
                        lz,
                        minY + 1,
                        fl - dirtLayers - 1,
                        STONE_S
                    );

                    if (dirtLayers > 0) {
                        if (volcano && gridDist <= 0.70) {
                            // Каждый блок кожи получает свою породу по своему Y:
                            // стенки обрывов выглядят как настоящий слоёный
                            // стратовулкан, а не как вертикальная копия поверхности.
                            double layerFissure = volcanicFissures[columnIndex];
                            int strataShift = volcanicStrataShifts[columnIndex];
                            for (int depth = 1; depth <= dirtLayers; depth++) {
                                int by = fl - depth;
                                BlockState skin = volcanicSubsurface(
                                    wx,
                                    by,
                                    wz,
                                    gridDist,
                                    crater,
                                    lavaLevel,
                                    layerFissure,
                                    strataShift
                                );
                                setDirect(chunk, lx, by, lz, skin);
                            }
                        } else {
                            // На внешнем зелёном поясе вулкана под травой лежит
                            // тонкий плодородный слой на вулканическом базальте —
                            // молодая почва, наросшая на застывшей лаве.
                            BlockState sub = subsurfaceStates[columnIndex];
                            fillYRange(
                                chunk,
                                lx,
                                lz,
                                fl - dirtLayers,
                                fl - 1,
                                sub != null ? sub : STONE_S
                            );
                        }
                    }

                    setDirect(chunk, lx, fl, lz, surf);
                    hmOcean.update(lx, fl, lz, surf);
                    hmSurface.update(lx, fl, lz, surf);

                    if (crater && fl < lavaLevel) {
                        lavaBlockWrites += lavaLevel - (long) fl;
                        fillYRange(
                            chunk,
                            lx,
                            lz,
                            fl + 1,
                            lavaLevel,
                            Blocks.LAVA.defaultBlockState()
                        );
                        hmSurface.update(
                            lx,
                            lavaLevel,
                            lz,
                            Blocks.LAVA.defaultBlockState()
                        );
                    }

                    if (fl < seaLevel) {
                        int waterFrom = fl + 1;

                        if (underwaterSlabColumns[columnIndex]) {
                            underwaterSlabs++;
                            solidBlockWrites++;
                            BlockState sl = slab(surf)
                                .setValue(SlabBlock.TYPE, SlabType.BOTTOM)
                                .setValue(SlabBlock.WATERLOGGED, true);
                            setDirect(chunk, lx, fl + 1, lz, sl);
                            hmOcean.update(lx, fl + 1, lz, sl);
                            hmSurface.update(lx, fl + 1, lz, sl);
                            waterFrom = fl + 2;
                        } else if (underwaterPlants[columnIndex] != 0) {
                            if (underwaterPlants[columnIndex] == 2) {
                                seagrassBlocks += 2;
                                BlockState lower =
                                    Blocks.TALL_SEAGRASS.defaultBlockState().setValue(
                                        TallSeagrassBlock.HALF,
                                        net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER
                                    );
                                BlockState upper =
                                    Blocks.TALL_SEAGRASS.defaultBlockState().setValue(
                                        TallSeagrassBlock.HALF,
                                        net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER
                                    );
                                setDirect(chunk, lx, fl + 1, lz, lower);
                                setDirect(chunk, lx, fl + 2, lz, upper);
                                hmSurface.update(lx, fl + 2, lz, upper);
                                waterFrom = fl + 3;
                            } else {
                                seagrassBlocks++;
                                BlockState sg =
                                    Blocks.SEAGRASS.defaultBlockState();
                                setDirect(chunk, lx, fl + 1, lz, sg);
                                hmSurface.update(lx, fl + 1, lz, sg);
                                waterFrom = fl + 2;
                            }
                        }

                        if (waterFrom <= seaLevel) {
                            waterBlockWrites += seaLevel - (long) waterFrom + 1L;
                            fillYRange(
                                chunk,
                                lx,
                                lz,
                                waterFrom,
                                seaLevel,
                                WATER_S
                            );
                            hmSurface.update(lx, seaLevel, lz, WATER_S);
                        }
                    }
                }
            }
        } finally {
            for (int i = minSec; i <= maxSec; i++) chunk
                .getSection(i)
                .release();
        }
        writeNs = System.nanoTime() - writeStarted;
        diagnostics.phase(
            benchmark,
            OceanGeneratorDiagnostics.Phase.FILL_WRITE_SECTIONS,
            writeNs
        );
        diagnostics.add(benchmark, OceanGeneratorDiagnostics.Counter.COLUMNS_TOTAL, 256L);
        diagnostics.add(benchmark, OceanGeneratorDiagnostics.Counter.COLUMNS_LAND, landColumns);
        diagnostics.add(benchmark, OceanGeneratorDiagnostics.Counter.COLUMNS_OCEAN, oceanColumns);
        diagnostics.add(benchmark, OceanGeneratorDiagnostics.Counter.COLUMNS_SPAWN_ISLAND, spawnIslandColumns);
        diagnostics.add(benchmark, OceanGeneratorDiagnostics.Counter.COLUMNS_GRID_ISLAND, gridIslandColumns);
        diagnostics.add(benchmark, OceanGeneratorDiagnostics.Counter.COLUMNS_VOLCANO, volcanoColumns);
        diagnostics.add(benchmark, OceanGeneratorDiagnostics.Counter.COLUMNS_CRATER, craterColumns);
        diagnostics.add(benchmark, OceanGeneratorDiagnostics.Counter.COLUMNS_BEACH, beachColumns);
        diagnostics.add(benchmark, OceanGeneratorDiagnostics.Counter.COLUMNS_SLOPE, slopeColumns);
        diagnostics.add(benchmark, OceanGeneratorDiagnostics.Counter.SOLID_BLOCK_WRITES, solidBlockWrites);
        diagnostics.add(benchmark, OceanGeneratorDiagnostics.Counter.WATER_BLOCK_WRITES, waterBlockWrites);
        diagnostics.add(benchmark, OceanGeneratorDiagnostics.Counter.LAVA_BLOCK_WRITES, lavaBlockWrites);
        diagnostics.add(benchmark, OceanGeneratorDiagnostics.Counter.UNDERWATER_SLABS, underwaterSlabs);
        diagnostics.add(benchmark, OceanGeneratorDiagnostics.Counter.SEAGRASS_BLOCKS, seagrassBlocks);

        return CompletableFuture.completedFuture(chunk);
        } catch (RuntimeException | Error error) {
            benchmarkError = error;
            throw error;
        } finally {
            String detail = null;
            if (
                System.nanoTime() - benchmark.startedNs() >
                    OceanGeneratorDiagnostics.Stage.FILL_NOISE.budgetNs()
            ) {
                detail =
                    "sampleMs=" + sampleNs / 1_000_000.0 +
                    ",cacheMs=" + cacheNs / 1_000_000.0 +
                    ",classifyMs=" + classifyNs / 1_000_000.0 +
                    ",prepareMs=" + prepareNs / 1_000_000.0 +
                    ",writeMs=" + writeNs / 1_000_000.0 +
                    ",land=" + landColumns +
                    ",ocean=" + oceanColumns +
                    ",volcano=" + volcanoColumns +
                    ",crater=" + craterColumns +
                    ",slopes=" + slopeColumns;
            }
            diagnostics.end(benchmark, seed, benchmarkError, detail);
        }
    }

    @Override
    public void buildSurface(
        WorldGenRegion region,
        StructureManager structureManager,
        RandomState randomState,
        ChunkAccess chunk
    ) {}

    @Override
    public void applyCarvers(
        WorldGenRegion region,
        long seed,
        RandomState randomState,
        BiomeManager biomeManager,
        StructureManager structureManager,
        ChunkAccess chunk,
        GenerationStep.Carving step
    ) {}

    private void recordPalmPlacement(
        OceanGeneratorDiagnostics.Token benchmark,
        PalmGenerator.PlacementResult result
    ) {
        diagnostics.phase(
            benchmark,
            OceanGeneratorDiagnostics.Phase.TREE_PALM_PREFLIGHT,
            result.preflightNs()
        );
        if (result.writeNs() > 0L) {
            diagnostics.phase(
                benchmark,
                OceanGeneratorDiagnostics.Phase.TREE_PALM_WRITE,
                result.writeNs()
            );
        }
        diagnostics.add(
            benchmark,
            OceanGeneratorDiagnostics.Counter.PALM_BLOCK_WRITES,
            result.blocksWritten()
        );
        if (!result.placed()) diagnostics.add(
            benchmark,
            OceanGeneratorDiagnostics.Counter.TREE_PREFLIGHT_FAILURES,
            1L
        );
    }

    private void recordAcaciaPlacement(
        OceanGeneratorDiagnostics.Token benchmark,
        AcaciaGenerator.PlacementResult result
    ) {
        diagnostics.phase(
            benchmark,
            OceanGeneratorDiagnostics.Phase.TREE_ACACIA_PREFLIGHT,
            result.preflightNs()
        );
        if (result.writeNs() > 0L) {
            diagnostics.phase(
                benchmark,
                OceanGeneratorDiagnostics.Phase.TREE_ACACIA_WRITE,
                result.writeNs()
            );
        }
        diagnostics.add(
            benchmark,
            OceanGeneratorDiagnostics.Counter.ACACIA_BLOCK_WRITES,
            result.blocksWritten()
        );
        if (!result.placed()) diagnostics.add(
            benchmark,
            OceanGeneratorDiagnostics.Counter.TREE_PREFLIGHT_FAILURES,
            1L
        );
    }

    // -------------------------------------------------------------------------
    // Декорации биома
    // -------------------------------------------------------------------------

    @Override
    public void applyBiomeDecoration(
        WorldGenLevel level,
        ChunkAccess chunk,
        StructureManager structureManager
    ) {
        syncSeedFromLevel(level);

        ChunkPos cp = chunk.getPos();
        int cx = cp.x,
            cz = cp.z;

        OceanGeneratorDiagnostics.Token benchmark = diagnostics.begin(
            OceanGeneratorDiagnostics.Stage.DECORATION, cp.toLong(), seed
        );
        Throwable benchmarkError = null;
        long decorCacheNs = 0L;
        long volcanicBiomeNs = 0L;
        long volcanicActiveNs = 0L;
        long boulderNs = 0L;
        long savannaTreeNs = 0L;
        long smallFeatureNs = 0L;
        int palmAttempts = 0;
        int palmsPlaced = 0;
        int shellsPlaced = 0;
        int groundDecorationsPlaced = 0;
        int shortGrassPlaced = 0;
        int flowersPlaced = 0;
        int bushesPlaced = 0;
        int bushLeavesPlaced = 0;
        try {

        long phaseStarted = System.nanoTime();
        ColumnCache cache = DECOR_CACHE.remove(cp.toLong());
        diagnostics.cacheAccess(OceanGeneratorDiagnostics.Cache.DECOR, cache != null);
        diagnostics.cacheState(OceanGeneratorDiagnostics.Cache.DECOR, DECOR_CACHE.size(), DECOR_CACHE_MAX);
        decorCacheNs = System.nanoTime() - phaseStarted;
        diagnostics.phase(
            benchmark,
            OceanGeneratorDiagnostics.Phase.DECOR_READ_CACHE,
            decorCacheNs
        );

        // Живое подножие, геотермальные пятна и вулканический декор контролируются
        // отдельными проходами и не смешиваются с декором саванны.
        phaseStarted = System.nanoTime();
        generateVolcanicBiomeFeatures(level, cx, cz, cache, benchmark);
        volcanicBiomeNs = System.nanoTime() - phaseStarted;
        diagnostics.phase(
            benchmark,
            OceanGeneratorDiagnostics.Phase.DECOR_VOLCANIC_BIOME,
            volcanicBiomeNs
        );
        phaseStarted = System.nanoTime();
        generateVolcanicFeatures(level, cx, cz, cache, benchmark);
        volcanicActiveNs = System.nanoTime() - phaseStarted;
        diagnostics.phase(
            benchmark,
            OceanGeneratorDiagnostics.Phase.DECOR_VOLCANIC_ACTIVE,
            volcanicActiveNs
        );
        // Валуны кладём до мелкого декора: трава/цветы не полезут сквозь камень.
        phaseStarted = System.nanoTime();
        generateBoulders(level, cx, cz, benchmark);
        boulderNs = System.nanoTime() - phaseStarted;
        diagnostics.phase(
            benchmark,
            OceanGeneratorDiagnostics.Phase.DECOR_BOULDERS,
            boulderNs
        );
        // Деревья генерируются отдельным детерминированным проходом: сначала рощи,
        // затем мелкий декор заполняет оставшиеся свободные места.
        phaseStarted = System.nanoTime();
        generateSavannaTrees(level, cx, cz, benchmark);
        savannaTreeNs = System.nanoTime() - phaseStarted;
        diagnostics.phase(
            benchmark,
            OceanGeneratorDiagnostics.Phase.DECOR_SAVANNA_TREES,
            savannaTreeNs
        );

        long smallFeatureStarted = System.nanoTime();
        double[] fallbackGrid = cache == null ? new double[8] : null;
        BlockPos.MutableBlockPos surface = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos above = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos bushPos = new BlockPos.MutableBlockPos();

        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int wx = cx * 16 + lx,
                    wz = cz * 16 + lz;

                int fl;
                boolean isIsland;
                boolean isVolcano;
                if (cache != null) {
                    int idx = (lx << 4) | lz;
                    fl = cache.floor[idx];
                    isIsland = (cache.flags[idx] & FLAG_ISLAND) != 0;
                    isVolcano = (cache.flags[idx] & FLAG_VOLCANO) != 0;
                } else {
                    double st = islandDist(wx, wz);
                    double spRaw = islandH(wx, wz, st);
                    gridIslandSample(wx, wz, fallbackGrid);
                    double gridH = fallbackGrid[2];
                    fl = computeFloor(wx, wz, st, spRaw, gridH);
                    isIsland = spRaw > 0.0 || gridH > 0.5;
                    isVolcano = fallbackGrid[3] > 0.5;
                }

                if (fl < seaLevel) continue;
                if (!isIsland || isVolcano) continue;

                surface.set(wx, fl, wz);
                above.set(wx, fl + 1, wz);
                boolean onSand = level.getBlockState(surface).is(Blocks.SAND);

                // Пляж классифицируется как BEACH, поэтому прежняя проверка TROPICS
                // делала эту ветку недостижимой. Пальмы ставятся на ровном песке,
                // а PalmGenerator дополнительно проверяет свободное место под крону.
                if (
                    onSand &&
                    hsh(wx * 41, wz * 43) < 0.0045 &&
                    classifyBiome(wx, wz) == BiomeCategory.BEACH &&
                    Math.abs(floorAt(wx + 2, wz) - fl) <= 1 &&
                    Math.abs(floorAt(wx - 2, wz) - fl) <= 1 &&
                    Math.abs(floorAt(wx, wz + 2) - fl) <= 1 &&
                    Math.abs(floorAt(wx, wz - 2) - fl) <= 1 &&
                    level.getBlockState(above).isAir()
                ) {
                    palmAttempts++;
                    PalmGenerator.PlacementResult result =
                        PalmGenerator.tryPlacePalmDetailed(
                        level,
                        wx,
                        fl + 1,
                        wz,
                        hsh(wx * 53, wz * 59)
                    );
                    recordPalmPlacement(benchmark, result);
                    if (result.placed()) {
                        palmsPlaced++;
                        continue;
                    }
                }

                if (onSand && hsh(wx * 31, wz * 37) < 0.06) {
                    if (level.getBlockState(above).isAir()) {
                        ShellBlock.Variation v = SHELL_VARIATIONS[
                            (int) (hsh(wx * 79, wz * 83) * SHELL_VARIATIONS.length) %
                                SHELL_VARIATIONS.length
                        ];
                        level.setBlock(
                            above,
                            SWWorldgenCore.SHELL.get()
                                .defaultBlockState()
                                .setValue(ShellBlock.VARIANT, v),
                            2
                        );
                        shellsPlaced++;
                    }
                    continue;
                }

                if (hsh(wx * 61, wz * 67) < 0.01) {
                    if (level.getBlockState(above).isAir()) {
                        GroundDecorationBlock.Type t = GROUND_DECORATION_TYPES[
                            (int) (hsh(wx * 89, wz * 91) * GROUND_DECORATION_TYPES.length) %
                                GROUND_DECORATION_TYPES.length
                        ];
                        level.setBlock(
                            above,
                            SWWorldgenCore.GROUND_DECO.get()
                                .defaultBlockState()
                                .setValue(GroundDecorationBlock.VARIANT, t),
                            2
                        );
                        groundDecorationsPlaced++;
                    }
                }

                if (
                    !level.getBlockState(surface).is(Blocks.GRASS_BLOCK)
                ) continue;

                double r = hsh(wx * 17, wz * 29);

                if (r < 0.45) {
                    if (level.getBlockState(above).isAir()) {
                        level.setBlock(
                            above,
                            Blocks.SHORT_GRASS.defaultBlockState(),
                            2
                        );
                        shortGrassPlaced++;
                    }
                } else if (r < 0.49) {
                    if (level.getBlockState(above).isAir()) {
                        double flr = hsh(wx * 23, wz * 37);
                        BlockState flower;
                        if (flr < 0.4) flower =
                            Blocks.POPPY.defaultBlockState();
                        else if (flr < 0.7) flower =
                            Blocks.DANDELION.defaultBlockState();
                        else flower = Blocks.OXEYE_DAISY.defaultBlockState();
                        level.setBlock(above, flower, 2);
                        flowersPlaced++;
                    }
                } else if (r < 0.52) {
                    if (level.getBlockState(above).isAir()) {
                        BlockState leaf =
                            Blocks.JUNGLE_LEAVES.defaultBlockState()
                                .setValue(LeavesBlock.PERSISTENT, true);
                        double bushR = hsh(wx * 97, wz * 83);
                        int[][] bush = BUSH_TEMPLATES[
                            bushR < 0.33 ? 0 : bushR < 0.66 ? 1 : 2
                        ];
                        int leavesPlaced = 0;
                        for (int[] b : bush) {
                            bushPos.set(
                                wx + b[0],
                                fl + 1 + b[1],
                                wz + b[2]
                            );
                            if (level.getBlockState(bushPos).isAir()) {
                                level.setBlock(bushPos, leaf, 2);
                                leavesPlaced++;
                            }
                        }
                        if (leavesPlaced > 0) bushesPlaced++;
                        bushLeavesPlaced += leavesPlaced;
                    }
                }
            }
        }
        smallFeatureNs = System.nanoTime() - smallFeatureStarted;
        diagnostics.phase(
            benchmark,
            OceanGeneratorDiagnostics.Phase.DECOR_SMALL_FEATURES,
            smallFeatureNs
        );
        diagnostics.add(benchmark, OceanGeneratorDiagnostics.Counter.PALM_ATTEMPTS, palmAttempts);
        diagnostics.add(benchmark, OceanGeneratorDiagnostics.Counter.PALMS_PLACED, palmsPlaced);
        diagnostics.add(benchmark, OceanGeneratorDiagnostics.Counter.SHELLS_PLACED, shellsPlaced);
        diagnostics.add(
            benchmark,
            OceanGeneratorDiagnostics.Counter.GROUND_DECORATIONS_PLACED,
            groundDecorationsPlaced
        );
        diagnostics.add(benchmark, OceanGeneratorDiagnostics.Counter.SHORT_GRASS_PLACED, shortGrassPlaced);
        diagnostics.add(benchmark, OceanGeneratorDiagnostics.Counter.FLOWERS_PLACED, flowersPlaced);
        diagnostics.add(benchmark, OceanGeneratorDiagnostics.Counter.BUSHES_PLACED, bushesPlaced);
        diagnostics.add(benchmark, OceanGeneratorDiagnostics.Counter.BUSH_LEAVES_PLACED, bushLeavesPlaced);
        } catch (RuntimeException | Error error) {
            benchmarkError = error;
            throw error;
        } finally {
            String detail = null;
            if (
                System.nanoTime() - benchmark.startedNs() >
                    OceanGeneratorDiagnostics.Stage.DECORATION.budgetNs()
            ) {
                detail =
                    "decorCacheMs=" + decorCacheNs / 1_000_000.0 +
                    ",volcanicBiomeMs=" + volcanicBiomeNs / 1_000_000.0 +
                    ",volcanicActiveMs=" + volcanicActiveNs / 1_000_000.0 +
                    ",bouldersMs=" + boulderNs / 1_000_000.0 +
                    ",savannaTreesMs=" + savannaTreeNs / 1_000_000.0 +
                    ",smallFeaturesMs=" + smallFeatureNs / 1_000_000.0 +
                    ",palms=" + palmsPlaced + '/' + palmAttempts +
                    ",bushLeaves=" + bushLeavesPlaced;
            }
            diagnostics.end(benchmark, seed, benchmarkError, detail);
        }
    }

    // -------------------------------------------------------------------------
    // Активный вулкан: лавовые русла, вторичные жерла и базальтовые шпили
    // -------------------------------------------------------------------------

    private void generateVolcanicBiomeFeatures(
        WorldGenLevel level,
        int chunkX,
        int chunkZ,
        ColumnCache cache,
        OceanGeneratorDiagnostics.Token benchmark
    ) {
        int minX = chunkX * 16;
        int minZ = chunkZ * 16;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        double[] sample = new double[8];
        int palmAttempts = 0;
        int palmsPlaced = 0;
        int acaciaAttempts = 0;
        int acaciasPlaced = 0;
        int featureWrites = 0;

        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int x = minX + lx;
                int z = minZ + lz;
                int columnIndex = (lx << 4) | lz;
                if (
                    cache != null &&
                    (cache.flags[columnIndex] & FLAG_VOLCANO) == 0
                ) continue;
                gridIslandSample(x, z, sample);
                if (sample[3] < 0.5 || sample[4] > 0.5) continue;

                double t = sample[0];
                int floor = cache != null
                    ? cache.floor[columnIndex]
                    : floorAt(x, z);
                if (floor < seaLevel) continue;
                pos.set(x, floor, z);
                BlockState ground = level.getBlockState(pos);
                double grove = fbm(x * 0.010 + 307.0, z * 0.010 - 613.0, 3, 2.0, 0.52);
                double groveEdge = fbm(x * 0.027 - 173.0, z * 0.027 + 419.0, 2, 2.0, 0.5);
                double pick = hsh(x * 197 + floor, z * 199 - floor);
                if (pick < 0.0014) {
                    boolean palmCandidate =
                        t > 0.87 &&
                        t < 0.94 &&
                        grove > 0.61 &&
                        (ground.is(Blocks.SAND) || ground.is(Blocks.GRASS_BLOCK));
                    boolean acaciaCandidate =
                        t > 0.73 &&
                        t < 0.86 &&
                        grove > 0.68 &&
                        groveEdge > 0.46 &&
                        ground.is(Blocks.GRASS_BLOCK);
                    if (palmCandidate || acaciaCandidate) {
                        boolean stable =
                            Math.abs(floorAt(x + 3, z) - floor) <= 2 &&
                            Math.abs(floorAt(x - 3, z) - floor) <= 2 &&
                            Math.abs(floorAt(x, z + 3) - floor) <= 2 &&
                            Math.abs(floorAt(x, z - 3) - floor) <= 2;
                        if (stable && palmCandidate) {
                            palmAttempts++;
                            PalmGenerator.PlacementResult result =
                                PalmGenerator.tryPlacePalmDetailed(
                                level,
                                x,
                                floor + 1,
                                z,
                                hsh(x * 211, z * 223)
                            );
                            recordPalmPlacement(benchmark, result);
                            if (result.placed()) {
                                palmsPlaced++;
                                continue;
                            }
                        }
                        if (stable && acaciaCandidate) {
                            acaciaAttempts++;
                            AcaciaGenerator.PlacementResult result =
                                AcaciaGenerator.tryPlaceDetailed(
                                level,
                                x,
                                floor + 1,
                                z,
                                hsh(x * 227, z * 229),
                                false
                            );
                            recordAcaciaPlacement(benchmark, result);
                            if (result.placed()) {
                                acaciasPlaced++;
                                continue;
                            }
                        }
                    }
                }

                pos.set(x, floor + 1, z);
                if (!level.getBlockState(pos).isAir()) continue;

                // Живой зелёный пояс: трава с папоротниками и редкими цветами.
                // На чёрных потоках декора нет — контраст остаётся резким.
                if (
                    t > 0.64 &&
                    t < 0.94 &&
                    ground.is(Blocks.GRASS_BLOCK) &&
                    pick < 0.16
                ) {
                    double plant = hsh(x * 269, z * 271);
                    BlockState deco;
                    if (plant < 0.62) {
                        deco = Blocks.SHORT_GRASS.defaultBlockState();
                    } else if (plant < 0.90) {
                        deco = Blocks.FERN.defaultBlockState();
                    } else if (plant < 0.96) {
                        deco = Blocks.POPPY.defaultBlockState();
                    } else {
                        deco = Blocks.OXEYE_DAISY.defaultBlockState();
                    }
                    level.setBlock(pos, deco, 2);
                    featureWrites++;
                    continue;
                }

                // Сухие кусты на пятнах утоптанной земли — переход от зелени
                // к пепловым полям выглядит естественно выжженным.
                if (
                    t > 0.55 &&
                    ground.is(Blocks.COARSE_DIRT) &&
                    pick < 0.06
                ) {
                    level.setBlock(pos, Blocks.DEAD_BUSH.defaultBlockState(), 2);
                    featureWrites++;
                }
            }
        }
        diagnostics.add(benchmark, OceanGeneratorDiagnostics.Counter.PALM_ATTEMPTS, palmAttempts);
        diagnostics.add(benchmark, OceanGeneratorDiagnostics.Counter.PALMS_PLACED, palmsPlaced);
        diagnostics.add(benchmark, OceanGeneratorDiagnostics.Counter.ACACIA_ATTEMPTS, acaciaAttempts);
        diagnostics.add(benchmark, OceanGeneratorDiagnostics.Counter.ACACIAS_PLACED, acaciasPlaced);
        diagnostics.add(benchmark, OceanGeneratorDiagnostics.Counter.VOLCANIC_FEATURE_WRITES, featureWrites);
    }

    private void generateVolcanicFeatures(
        WorldGenLevel level,
        int chunkX,
        int chunkZ,
        ColumnCache cache,
        OceanGeneratorDiagnostics.Token benchmark
    ) {
        int minX = chunkX * 16;
        int minZ = chunkZ * 16;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        double[] sample = new double[8];
        int[] nearestFlowIndexOut = new int[1];
        int featureWrites = 0;

        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int wx = minX + lx;
                int wz = minZ + lz;
                int columnIndex = (lx << 4) | lz;
                if (
                    cache != null &&
                    (cache.flags[columnIndex] & FLAG_VOLCANO) == 0
                ) continue;
                gridIslandSample(wx, wz, sample);
                if (sample[3] < 0.5) continue;

                double t = sample[0];
                double craterT = Math.sqrt(
                    (wx - sample[5]) * (wx - sample[5]) +
                    (wz - sample[6]) * (wz - sample[6])
                ) / sample[1];
                int floor = cache != null
                    ? cache.floor[columnIndex]
                    : floorAt(wx, wz);
                long volcanoHash = rawHash((int) sample[5] * 17, (int) sample[6] * 19);
                int lavaLevel = (int) Math.round(sample[7]);

                double pick = hsh(wx * 197 + floor, wz * 199 - floor);

                // Три обсидиановых островка-купола в кальдере. Ступенчатый купол
                // с прожилками плачущего обсидиана; на вершине центрального
                // выступа — placeholder «раскалённой магмы» (награда для create).
                boolean rewardIsland = false;
                for (int island = 0; island < 3; island++) {
                    double islandAngle = frac(volcanoHash >> (island * 15 + 5)) * Math.PI * 2.0;
                    double islandDistance = sample[1] * (0.052 + island * 0.018);
                    double islandX = sample[5] + Math.cos(islandAngle) * islandDistance;
                    double islandZ = sample[6] + Math.sin(islandAngle) * islandDistance;
                    double dx = wx - islandX;
                    double dz = wz - islandZ;
                    double distanceSq = dx * dx + dz * dz;
                    double platformRadius = island == 0 ? 3.2 : 2.4;
                    if (distanceSq <= platformRadius * platformRadius) {
                        double distance = Math.sqrt(distanceSq);
                        // Нижний ярус купола едва выступает из лавы.
                        pos.set(wx, lavaLevel + 1, wz);
                        level.setBlock(
                            pos,
                            pick < 0.18
                                ? Blocks.CRYING_OBSIDIAN.defaultBlockState()
                                : Blocks.OBSIDIAN.defaultBlockState(),
                            2
                        );
                        featureWrites++;
                        // Верхний ярус: маленькая обсидиановая площадка,
                        // на которой стоит блок награды.
                        if (distance < platformRadius * 0.48) {
                            pos.set(wx, lavaLevel + 2, wz);
                            level.setBlock(
                                pos,
                                distanceSq < 0.55
                                    ? VOLCANO_REWARD_BLOCK
                                    : (pick < 0.30
                                        ? Blocks.CRYING_OBSIDIAN.defaultBlockState()
                                        : Blocks.OBSIDIAN.defaultBlockState()),
                                2
                            );
                            featureWrites++;
                        }
                        rewardIsland = true;
                        break;
                    }
                }
                if (rewardIsland) continue;

                double flowDistance = volcanicFlowDistance(
                    wx,
                    wz,
                    t,
                    sample[5],
                    sample[6],
                    volcanoHash,
                    nearestFlowIndexOut
                );
                int nearestFlowIndex = nearestFlowIndexOut[0];
                // Активные языки текут от кромки до самого океана, как потоки
                // Килауэа: раскалённое русло наверху, корка магмы на середине
                // и полностью остывший чёрный камень у воды.
                double channelWidth = 0.017 + Mth.clamp((t - 0.24) / 0.40, 0.0, 1.0) * 0.018;
                boolean activeFlow = nearestFlowIndex == 0 || nearestFlowIndex == 3;
                boolean inFlow =
                    activeFlow &&
                    craterT > VOLCANO_RIM_OUTER_RADIUS + 0.025 &&
                    !isCalderaBarrier(craterT) &&
                    t > 0.25 &&
                    t < 0.985 &&
                    flowDistance < channelWidth;
                if (inFlow) {
                    pos.set(wx, floor, wz);
                    double edge = flowDistance / channelWidth;
                    BlockState channel;
                    if (floor <= seaLevel + 1) {
                        // Вход лавы в океан: магмовые блоки у кромки воды дают
                        // пар и пузырьковые колонны — «лавовый берег».
                        channel = edge < 0.6
                            ? Blocks.MAGMA_BLOCK.defaultBlockState()
                            : Blocks.BASALT.defaultBlockState();
                    } else if (edge < 0.34 && t < 0.52) {
                        channel = Blocks.LAVA.defaultBlockState();
                    } else if (edge < 0.60 && t < 0.70) {
                        channel = Blocks.MAGMA_BLOCK.defaultBlockState();
                    } else if (edge < 0.67) {
                        channel = Blocks.SMOOTH_BASALT.defaultBlockState();
                    } else {
                        channel = Blocks.BLACKSTONE.defaultBlockState();
                    }
                    level.setBlock(pos, channel, 2);
                    featureWrites++;

                    // Застывшие борта (леве): невысокий валик блэкстоуна вдоль
                    // краёв активного русла, как у настоящих лавовых каналов.
                    if (
                        edge > 0.80 &&
                        t > 0.30 &&
                        t < 0.80 &&
                        floor > seaLevel + 2 &&
                        pick < 0.55
                    ) {
                        pos.set(wx, floor + 1, wz);
                        if (level.getBlockState(pos).isAir()) {
                            level.setBlock(
                                pos,
                                Blocks.BLACKSTONE.defaultBlockState(),
                                2
                            );
                            featureWrites++;
                        }
                    }
                    continue;
                }

                // Фумаролы: геотермальные жерла сразу за внешней кромкой.
                // Пятно магмы и иногда невысокая базальтовая «труба» рядом.
                if (
                    craterT > VOLCANO_RIM_OUTER_RADIUS + 0.02 &&
                    craterT < VOLCANO_RIM_OUTER_RADIUS + 0.12 &&
                    floor > seaLevel + 4 &&
                    pick < 0.012
                ) {
                    pos.set(wx, floor, wz);
                    level.setBlock(pos, Blocks.MAGMA_BLOCK.defaultBlockState(), 2);
                    featureWrites++;
                    if (pick < 0.004) {
                        int chimney = 2 + (int) (hsh(wx * 233, wz * 239) * 2);
                        for (int dy = 1; dy <= chimney; dy++) {
                            pos.set(wx + 1, floor + dy, wz);
                            if (level.getBlockState(pos).isAir()) {
                                level.setBlock(
                                    pos,
                                    Blocks.BASALT.defaultBlockState(),
                                    2
                                );
                                featureWrites++;
                            }
                        }
                    }
                    continue;
                }

                // Вулканические бомбы: одиночные глыбы, выброшенные извержением
                // и застрявшие в пепловых полях средней части склона.
                if (
                    t > 0.36 &&
                    t < 0.66 &&
                    floor > seaLevel + 3 &&
                    pick < 0.0035
                ) {
                    pos.set(wx, floor + 1, wz);
                    if (level.getBlockState(pos).isAir()) {
                        level.setBlock(
                            pos,
                            hsh(wx * 241, wz * 251) < 0.5
                                ? Blocks.BLACKSTONE.defaultBlockState()
                                : Blocks.SMOOTH_BASALT.defaultBlockState(),
                            2
                        );
                        featureWrites++;
                        if (hsh(wx * 257, wz * 263) < 0.3) {
                            pos.set(wx, floor + 2, wz);
                            if (level.getBlockState(pos).isAir()) {
                                level.setBlock(
                                    pos,
                                    Blocks.BLACKSTONE.defaultBlockState(),
                                    2
                                );
                                featureWrites++;
                            }
                        }
                    }
                    continue;
                }

                // Отдельные шпили и случайный огонь здесь намеренно не создаются:
                // силуэт формируют сам широкий конус и большие потоки.
            }
        }
        diagnostics.add(
            benchmark,
            OceanGeneratorDiagnostics.Counter.VOLCANIC_FEATURE_WRITES,
            featureWrites
        );
    }

    // -------------------------------------------------------------------------
    // Саванна: рощи и открытые поля
    // -------------------------------------------------------------------------

    private void generateSavannaTrees(
        WorldGenLevel level,
        int chunkX,
        int chunkZ,
        OceanGeneratorDiagnostics.Token benchmark
    ) {
        int minX = chunkX * 16,
            minZ = chunkZ * 16;
        int maxX = minX + 15,
            maxZ = minZ + 15;
        int minCellX = Math.floorDiv(minX, SAVANNA_TREE_CELL);
        int maxCellX = Math.floorDiv(maxX, SAVANNA_TREE_CELL);
        int minCellZ = Math.floorDiv(minZ, SAVANNA_TREE_CELL);
        int maxCellZ = Math.floorDiv(maxZ, SAVANNA_TREE_CELL);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int attempts = 0;
        int placed = 0;

        for (int cellX = minCellX; cellX <= maxCellX; cellX++) {
            for (int cellZ = minCellZ; cellZ <= maxCellZ; cellZ++) {
                long candidateHash = rawHash(cellX * 92821, cellZ * 68917);
                int wx =
                    cellX * SAVANNA_TREE_CELL +
                    (int) (frac(candidateHash) * SAVANNA_TREE_CELL);
                int wz =
                    cellZ * SAVANNA_TREE_CELL +
                    (int) (frac(candidateHash >> 20) * SAVANNA_TREE_CELL);
                if (wx < minX || wx > maxX || wz < minZ || wz > maxZ) continue;
                if (classifyBiome(wx, wz) != BiomeCategory.SAVANNA) continue;

                int floor = floorAt(wx, wz);
                if (floor <= seaLevel || isBeach(wx, wz, floor)) continue;
                pos.set(wx, floor, wz);
                if (!level.getBlockState(pos).is(Blocks.GRASS_BLOCK)) continue;

                int minFloor = floor,
                    maxFloor = floor;
                for (int[] sample : SAVANNA_SLOPE_SAMPLES) {
                    int sampleFloor = floorAt(wx + sample[0], wz + sample[1]);
                    minFloor = Math.min(minFloor, sampleFloor);
                    maxFloor = Math.max(maxFloor, sampleFloor);
                }
                int slope = maxFloor - minFloor;
                if (slope > SAVANNA_MAX_SLOPE) continue;

                // Низкочастотное поле создаёт выразительные группы деревьев и поляны.
                double grove = fbm(
                    wx * 0.012 + 311.0,
                    wz * 0.012 - 173.0,
                    3,
                    2.0,
                    0.5
                );
                double mountainBonus = Mth.clamp(
                    (floor - seaLevel - 8) / 28.0,
                    0.0,
                    0.16
                );
                if (grove + mountainBonus < SAVANNA_GROVE_THRESHOLD) continue;

                // Внутри рощи остаются небольшие естественные просветы.
                double density = Mth.clamp(
                    (grove - SAVANNA_GROVE_THRESHOLD) * 2.2 +
                        0.58 +
                        mountainBonus,
                    0.0,
                    0.92
                );
                if (frac(candidateHash >> 40) > density) continue;

                boolean preferSmall = slope >= 2 || floor > seaLevel + 28;
                attempts++;
                AcaciaGenerator.PlacementResult result =
                    AcaciaGenerator.tryPlaceDetailed(
                    level,
                    wx,
                    floor + 1,
                    wz,
                    frac(candidateHash ^ seed),
                    preferSmall
                );
                recordAcaciaPlacement(benchmark, result);
                if (result.placed()) placed++;
            }
        }
        diagnostics.add(benchmark, OceanGeneratorDiagnostics.Counter.ACACIA_ATTEMPTS, attempts);
        diagnostics.add(benchmark, OceanGeneratorDiagnostics.Counter.ACACIAS_PLACED, placed);
    }

    // -------------------------------------------------------------------------
    // Валуны с рудой
    // -------------------------------------------------------------------------

    /** Дробная часть хеша в [0,1). */
    private static double frac(long h) {
        return (h & 0xFFFFFFL) / (double) 0x1000000;
    }

    /** Хеш, зависящий от 3 координат — для ранд  ма руды внутри валуна. */
    private long hash3(int x, int y, int z) {
        return rawHash(x * 31 + y * 17, z * 13 - y * 7);
    }

    /** Выбор типа руды для конкретного валуна: медь / железо / уголь. */
    private BlockState pickOre(long boulderHash) {
        double f = frac(boulderHash >> 12);
        if (f < 0.34) return Blocks.COPPER_ORE.defaultBlockState();
        if (f < 0.67) return Blocks.IRON_ORE.defaultBlockState();
        return Blocks.COAL_ORE.defaultBlockState();
    }

    /**
     * Собирает острова, задевающие текущий чанк, детерминированно раскидывает по
     * каждому 3..7 валунов и просит {@link #placeBoulder} положить их часть,
     * попадающую в этот чанк. Каждый чанк независимо рисует свою «дольку» валуна,
     * поэтому камни бесшовно проходят сквозь границы чанков без записи в соседей.
     */
    private void generateBoulders(
        WorldGenLevel level,
        int chunkX,
        int chunkZ,
        OceanGeneratorDiagnostics.Token benchmark
    ) {
        int minX = chunkX * 16,
            minZ = chunkZ * 16;
        int maxX = minX + 15,
            maxZ = minZ + 15;
        double reach = BOULDER_MAX_RADIUS * BOULDER_SIZE + 2.0;
        double centerX = minX + 8,
            centerZ = minZ + 8;

        int cellX = Math.floorDiv((int) centerX, CELL);
        int cellZ = Math.floorDiv((int) centerZ, CELL);

        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                int gcx = cellX + dx,
                    gcz = cellZ + dz;
                double rHash = hsh(gcx * 11, gcz * 13);
                if (rHash > 0.6) continue; // тут острова нет
                if (hsh(gcx * 23, gcz * 29) < 0.06) continue; // это гора-остров — пропускаем целиком

                double radius = 80 + rHash * 120;
                int ix = gcx * CELL + 768 + (int) (hsh(gcx * 2, gcz * 2) * 512);
                int iz =
                    gcz * CELL +
                    768 +
                    (int) (hsh(gcx * 2 + 1, gcz * 2 + 1) * 512);
                double centerDistance = Math.sqrt((double) ix * ix + (double) iz * iz);
                if (
                    centerDistance >= VOLCANO_MIN_DISTANCE &&
                    hsh(gcx * 71 + 19, gcz * 73 - 23) < VOLCANO_CHANCE
                ) continue; // вулкан получает собственные потоки и шпили
                if (
                    diskTouchesChunk(
                        ix,
                        iz,
                        radius + reach,
                        minX,
                        minZ,
                        maxX,
                        maxZ
                    )
                ) {
                    spawnIslandBoulders(
                        level,
                        ix,
                        iz,
                        radius,
                        rawHash(gcx * 37, gcz * 41),
                        minX,
                        minZ,
                        maxX,
                        maxZ,
                        benchmark
                    );
                }
            }
        }

        // Спавн-остров (отдельная система). Его гора отсекается проверкой высоты в placeBoulder.
        if (
            diskTouchesChunk(
                0,
                0,
                SPAWN_ISLAND_RADIUS + reach,
                minX,
                minZ,
                maxX,
                maxZ
            )
        ) {
            spawnIslandBoulders(
                level,
                0,
                0,
                SPAWN_ISLAND_RADIUS,
                rawHash(0, 0),
                minX,
                minZ,
                maxX,
                maxZ,
                benchmark
            );
        }
    }

    private static boolean diskTouchesChunk(
        int ix,
        int iz,
        double r,
        int minX,
        int minZ,
        int maxX,
        int maxZ
    ) {
        double nx = Mth.clamp((double) ix, minX, maxX);
        double nz = Mth.clamp((double) iz, minZ, maxZ);
        double ddx = ix - nx,
            ddz = iz - nz;
        return ddx * ddx + ddz * ddz <= r * r;
    }

    private void spawnIslandBoulders(
        WorldGenLevel level,
        int ix,
        int iz,
        double radius,
        long islandHash,
        int minX,
        int minZ,
        int maxX,
        int maxZ,
        OceanGeneratorDiagnostics.Token benchmark
    ) {
        BoulderLayout layout = boulderLayout(ix, iz, radius, islandHash, benchmark);

        // Каждый чанк получает тот же список и рисует только свою часть.
        for (int i = 0; i < layout.count; i++) {
            double rad = layout.radius[i];
            if (
                layout.x[i] + rad < minX ||
                layout.x[i] - rad > maxX ||
                layout.z[i] + rad < minZ ||
                layout.z[i] - rad > maxZ
            ) continue;
            placeBoulder(
                level,
                layout.x[i],
                layout.z[i],
                rad,
                layout.hash[i],
                minX,
                minZ,
                maxX,
                maxZ,
                benchmark
            );
        }
    }

    private BoulderLayout boulderLayout(
        int ix,
        int iz,
        double radius,
        long islandHash
    ) {
        return boulderLayout(ix, iz, radius, islandHash, null);
    }

    private BoulderLayout boulderLayout(
        int ix,
        int iz,
        double radius,
        long islandHash,
        OceanGeneratorDiagnostics.Token benchmark
    ) {
        long key = colKey(ix, iz);
        BoulderLayout cached = BOULDER_CACHE.get(key);
        diagnostics.cacheAccess(
            OceanGeneratorDiagnostics.Cache.BOULDER,
            cached != null
        );
        if (cached != null) return cached;

        BoulderLayout created = createBoulderLayout(ix, iz, radius, islandHash);
        BoulderLayout raced = BOULDER_CACHE.putIfAbsent(key, created);
        BoulderLayout result = raced != null ? raced : created;
        if (raced == null) diagnostics.add(
            benchmark,
            OceanGeneratorDiagnostics.Counter.BOULDER_LAYOUTS_BUILT,
            1L
        );
        CacheTrimResult trim = trimCache(BOULDER_CACHE, BOULDER_CACHE_MAX);
        if (trim.removed() > 0) diagnostics.cacheTrim(
            OceanGeneratorDiagnostics.Cache.BOULDER,
            trim.removed(),
            trim.elapsedNs()
        );
        diagnostics.cacheState(
            OceanGeneratorDiagnostics.Cache.BOULDER,
            BOULDER_CACHE.size(),
            BOULDER_CACHE_MAX
        );
        return result;
    }

    private BoulderLayout createBoulderLayout(
        int ix,
        int iz,
        double radius,
        long islandHash
    ) {
        int span = BOULDER_MAX_COUNT - BOULDER_MIN_COUNT + 1;
        int targetCount = BOULDER_MIN_COUNT + (int) (frac(islandHash) * span);
        double usableR = radius * (1.0 - BOULDER_EDGE_MARGIN);
        BoulderLayout result = new BoulderLayout();

        for (
            int attempt = 0;
            attempt < BOULDER_PLACEMENT_TRIES && result.count < targetCount;
            attempt++
        ) {
            long bh = rawHash(
                (int) (islandHash + attempt * 9176L),
                (int) ((islandHash >> 21) + attempt * 7919L)
            );
            double ang = frac(bh >> 8) * Math.PI * 2.0;
            double dr = Math.sqrt(frac(bh >> 16)) * usableR;
            int bx = ix + (int) Math.round(Math.cos(ang) * dr);
            int bz = iz + (int) Math.round(Math.sin(ang) * dr);
            double rad =
                (BOULDER_MIN_RADIUS +
                    frac(bh >> 24) *
                        (BOULDER_MAX_RADIUS - BOULDER_MIN_RADIUS)) *
                BOULDER_SIZE;

            if (!isValidBoulderSite(bx, bz, rad)) continue;

            boolean overlaps = false;
            for (int i = 0; i < result.count; i++) {
                double dx = bx - result.x[i];
                double dz = bz - result.z[i];
                double minDistance =
                    (rad + result.radius[i]) * BOULDER_SEPARATION;
                if (dx * dx + dz * dz < minDistance * minDistance) {
                    overlaps = true;
                    break;
                }
            }
            if (overlaps) continue;

            int index = result.count++;
            result.x[index] = bx;
            result.z[index] = bz;
            result.radius[index] = rad;
            result.hash[index] = bh;
        }
        return result;
    }

    /** Проверка позиции не зависит от состояния/порядка генерации чанков. */
    private boolean isValidBoulderSite(int bx, int bz, double rad) {
        int groundC = floorAt(bx, bz);
        if (
            groundC < seaLevel + 1 || groundC > seaLevel + BOULDER_MAX_GROUND_H
        ) return false;
        if (isBeach(bx, bz, groundC)) return false;

        int r = (int) Math.ceil(rad);
        int minG = groundC,
            maxG = groundC;
        for (int[] d : BEACH_DIRS) {
            int g = floorAt(bx + d[0] * r, bz + d[1] * r);
            if (
                g < seaLevel + 1 || isBeach(bx + d[0] * r, bz + d[1] * r, g)
            ) return false;
            minG = Math.min(minG, g);
            maxG = Math.max(maxG, g);
        }
        return maxG - minG <= BOULDER_MAX_SLOPE;
    }

    /**
     * Возвращает все валидные позиции валунов на спавн-острове (детерминировано).
     * Реплицирует логику spawnIslandBoulders без привязки к чанку.
     * Каждый элемент — int[]{bx, bz}.
     */
    public int[][] getSpawnBoulderPositions() {
        long islandHash = rawHash(0, 0);
        BoulderLayout layout = boulderLayout(
            0,
            0,
            SPAWN_ISLAND_RADIUS,
            islandHash
        );
        int[][] result = new int[layout.count][2];
        for (int i = 0; i < layout.count; i++) {
            result[i][0] = layout.x[i];
            result[i][1] = layout.z[i];
        }
        return result;
    }

    /**
     * Кладёт один валун, обрезая запись строго в гран  цах текущего чанка.
     * Форма — приплюснутый эллипсоид с шумовой асимметрией; каждая колонка тянется
     * от (земл   - BOULDER_EMBED) до макушки купола, поэтому камень всегда прирос к
     * земле: нет висячих блоков, дыр и «торчащих из-под земли» уродцев.
     */
    private void placeBoulder(
        WorldGenLevel level,
        int bx,
        int bz,
        double rad,
        long bh,
        int minX,
        int minZ,
        int maxX,
        int maxZ,
        OceanGeneratorDiagnostics.Token benchmark
    ) {
        int groundC = floorAt(bx, bz);

        // Центр должен стоять на суше острова, не в воде и не на пляже.
        if (groundC < seaLevel + 1) return;
        if (groundC > seaLevel + BOULDER_MAX_GROUND_H) return; // гора — не место для валунов
        if (isBeach(bx, bz, groundC)) return;

        // Проверка ровности площадки по кольцу вокруг центра.
        int r = (int) Math.ceil(rad);
        int minG = groundC,
            maxG = groundC;
        for (int[] d : BEACH_DIRS) {
            int g = floorAt(bx + d[0] * r, bz + d[1] * r);
            if (g < seaLevel + 1) return; // край свисал бы над водой
            if (g < minG) minG = g;
            if (g > maxG) maxG = g;
        }
        if (maxG - minG > BOULDER_MAX_SLOPE) return; // склон/обрыв — пропускаем

        double radY = rad * BOULDER_HEIGHT_RATIO;
        int centerY = groundC;
        BlockState ore = pickOre(bh);
        double nOff = frac(bh) * 1000.0; // индивидуальный сдвиг шума
        int ri = (int) Math.ceil(rad) + 1;
        double sizeT = Mth.clamp(
            (rad - BOULDER_MIN_RADIUS * BOULDER_SIZE) /
                ((BOULDER_MAX_RADIUS - BOULDER_MIN_RADIUS) * BOULDER_SIZE),
            0.0,
            1.0
        );
        double oreFraction = Mth.lerp(
            sizeT,
            BOULDER_MIN_ORE_FRACTION,
            BOULDER_MAX_ORE_FRACTION
        );

        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        int writtenBlocks = 0;
        int writtenOreBlocks = 0;

        for (int dx = -ri; dx <= ri; dx++) {
            for (int dz = -ri; dz <= ri; dz++) {
                int wx = bx + dx,
                    wz = bz + dz;
                if (wx < minX || wx > maxX || wz < minZ || wz > maxZ) continue; // только этот чанк

                double dh = Math.sqrt(dx * (double) dx + dz * (double) dz);

                // Асимметричный радиус: разный в разные стороны -> валун не «шарик».
                double eff =
                    rad *
                    (0.72 +
                        0.55 *
                            fbm(
                                wx * 0.35 + nOff,
                                wz * 0.35 + nOff,
                                2,
                                2.0,
                                0.5
                            ));
                if (dh > eff) continue;

                double norm = dh / eff;
                double dome =
                    radY * Math.sqrt(Math.max(0.0, 1.0 - norm * norm));
                dome *=
                    0.78 +
                    0.44 * fbm(wx * 0.55 + nOff, wz * 0.55 - nOff, 2, 2.0, 0.5); // бугристость

                int localGround = floorAt(wx, wz);
                if (localGround < seaLevel + 1) continue; // не висеть над водой
                if (localGround > groundC + BOULDER_MAX_SLOPE) continue; // резкая ступень

                int topY = centerY + (int) Math.round(dome);
                if (topY < localGround) continue; // край ниже земли — пропуск
                int baseY = localGround - BOULDER_EMBED;

                for (int y = baseY; y <= topY; y++) {
                    boolean interior = y < topY && y > baseY && dh < eff - 1.0;

                    // Руды больше нет в фиксированном центре. Доля зависит от радиуса,
                    // поэтому общий объём добычи естественно растёт вместе с валуном.
                    // Крупный 3D-шум собирает равномерно доступную руду в прожилки по
                    // всему телу. Hash разбивает их края, сохраняя решение бесшовным.
                    double veinNoise = fbm(
                        wx * 0.24 + nOff + y * 0.07,
                        wz * 0.24 - nOff - y * 0.09,
                        3,
                        2.0,
                        0.5
                    );
                    double veinWeight = 0.30 + veinNoise * 1.45;
                    boolean shell = !interior && y > baseY;
                    double visibilityWeight = shell ? 0.58 : 1.0;
                    double oreChance = Mth.clamp(
                        oreFraction * veinWeight * visibilityWeight,
                        0.0,
                        0.72
                    );
                    boolean oreBlock =
                        y > baseY && frac(hash3(wx, y, wz) ^ bh) < oreChance;
                    BlockState block = oreBlock ? ore : STONE_S;

                    p.set(wx, y, wz);
                    BlockState cur = level.getBlockState(p);
                    // Пишем только по грунту/траве/песку/воздуху — не портим постройки и деревья.
                    if (
                        cur.isAir() ||
                        cur.is(Blocks.GRASS_BLOCK) ||
                        cur.is(Blocks.DIRT) ||
                        cur.is(Blocks.SAND) ||
                        cur.is(Blocks.GRAVEL) ||
                        cur.is(Blocks.STONE)
                    ) {
                        level.setBlock(p, block, 2);
                        writtenBlocks++;
                        if (oreBlock) writtenOreBlocks++;
                    }
                }
            }
        }
        if (writtenBlocks > 0) {
            diagnostics.add(benchmark, OceanGeneratorDiagnostics.Counter.BOULDER_FRAGMENTS, 1);
            diagnostics.add(
                benchmark,
                OceanGeneratorDiagnostics.Counter.BOULDER_BLOCK_WRITES,
                writtenBlocks
            );
            diagnostics.add(
                benchmark,
                OceanGeneratorDiagnostics.Counter.BOULDER_ORE_WRITES,
                writtenOreBlocks
            );
        }
    }

    @Override
    public void spawnOriginalMobs(WorldGenRegion region) {}

    @Override
    public int getGenDepth() {
        return GEN_DEPTH;
    }

    @Override
    public int getSeaLevel() {
        return seaLevel;
    }

    @Override
    public int getMinY() {
        return GEN_MIN_Y;
    }

    @Override
    public int getBaseHeight(
        int x,
        int z,
        Heightmap.Types heightmapType,
        LevelHeightAccessor level,
        RandomState random
    ) {
        if (level instanceof WorldGenLevel wgl) syncSeedFromLevel(wgl);
        OceanGeneratorDiagnostics.Token benchmark = diagnostics.begin(
            OceanGeneratorDiagnostics.Stage.BASE_HEIGHT, Long.MIN_VALUE, seed
        );
        Throwable benchmarkError = null;
        try {
        double st = islandDist(x, z);
        double spRaw = islandH(x, z, st);
        double[] grid = GRID_SAMPLE_SCRATCH.get();
        gridIslandSample(x, z, grid);
        double gridHeight = grid[2];
        boolean crater = grid[4] > 0.5;
        int lavaLevel = (int) Math.round(grid[7]);
        int floor = computeFloor(x, z, st, spRaw, gridHeight);
        boolean oceanFloor =
            heightmapType == Heightmap.Types.OCEAN_FLOOR ||
            heightmapType == Heightmap.Types.OCEAN_FLOOR_WG;
        if (oceanFloor) {
            boolean onIsland = spRaw > 0.0 || gridHeight > 0.5;
            if (
                floor < seaLevel &&
                onIsland &&
                (
                    floor < floorAt(x - 1, z) ||
                    floor < floorAt(x + 1, z) ||
                    floor < floorAt(x, z - 1) ||
                    floor < floorAt(x, z + 1)
                )
            ) return floor + 2;
            return floor + 1;
        }

        int surface = floor;
        if (floor < seaLevel) surface = seaLevel;
        if (crater) surface = Math.max(surface, lavaLevel);
        return surface + 1;
        } catch (RuntimeException | Error error) {
            benchmarkError = error;
            throw error;
        } finally {
            String detail = System.nanoTime() - benchmark.startedNs() >
                OceanGeneratorDiagnostics.Stage.BASE_HEIGHT.budgetNs()
                ? "block=[" + x + ',' + z + "], heightmap=" + heightmapType
                : null;
            diagnostics.end(benchmark, seed, benchmarkError, detail);
        }
    }

    @Override
    public NoiseColumn getBaseColumn(
        int x,
        int z,
        LevelHeightAccessor level,
        RandomState random
    ) {
        if (level instanceof WorldGenLevel wgl) syncSeedFromLevel(wgl);
        OceanGeneratorDiagnostics.Token benchmark = diagnostics.begin(
            OceanGeneratorDiagnostics.Stage.BASE_COLUMN, Long.MIN_VALUE, seed
        );
        Throwable benchmarkError = null;
        try {
        double st = islandDist(x, z);
        double spRaw = islandH(x, z, st);
        double[] grid = GRID_SAMPLE_SCRATCH.get();
        gridIslandSample(x, z, grid);
        double gridDi = grid[0],
            gridHi = grid[2];
        boolean volcano = grid[3] > 0.5;
        boolean crater = grid[4] > 0.5;
        double volcanoCenterX = grid[5];
        double volcanoCenterZ = grid[6];
        int lavaLevel = (int) Math.round(grid[7]);
        int fl = computeFloor(x, z, st, spRaw, gridHi);
        double maxT = 1.0 + SPAWN_ISLAND_FEATHER / SPAWN_ISLAND_RADIUS;
        boolean onIsl = spRaw > 0 || gridHi > 0.5;
        int minNeighbor = fl;
        boolean onSlope = false;
        if (volcano || (fl < seaLevel && onIsl)) {
            int west = floorAt(x - 1, z);
            int east = floorAt(x + 1, z);
            int north = floorAt(x, z - 1);
            int south = floorAt(x, z + 1);
            minNeighbor = Math.min(
                Math.min(west, east),
                Math.min(north, south)
            );
            onSlope = fl < west || fl < east || fl < north || fl < south;
        }

        int dirtLayers;
        if (volcano) {
            int cliffDrop = Math.max(0, fl - minNeighbor);
            dirtLayers = gridDi > 0.70
                ? Math.max(3, Math.min(cliffDrop + 2, 8))
                : Math.max(3, Math.min(cliffDrop + 2, 24));
        } else if (fl >= seaLevel && onIsl) dirtLayers = 3;
        else if (st < maxT) dirtLayers = 2;
        else dirtLayers = 0;
        boolean beach = isBeach(x, z, fl);
        int minY = level.getMinBuildHeight(),
            maxY = level.getMaxBuildHeight();
        BlockState[] col = new BlockState[maxY - minY];
        double columnFissure = volcano && gridDi <= 0.70
            ? ridgeNoise(x * 0.055 + 811.0, z * 0.055 - 419.0, 2, 2.0, 0.5)
            : 0.0;
        int strataShift = volcano && gridDi <= 0.70
            ? (int) (hsh(x >> 4, z >> 4) * 5)
            : 0;
        BlockState surface = volcano
            ? volcanicBiomeSurface(
                x,
                z,
                fl,
                gridDi,
                crater,
                lavaLevel,
                volcanoCenterX,
                volcanoCenterZ
            )
            : pickSurf(x, z, fl, st, gridHi, gridDi, beach);
        boolean underwaterSlab = fl < seaLevel && onSlope && onIsl;
        int underwaterPlant = 0;
        if (fl < seaLevel && !underwaterSlab && hasGrass(x, z, fl)) {
            underwaterPlant = hsh(x * 17, z * 23) < 0.3 ? 2 : 1;
        }
        for (int y = minY; y < maxY; y++) {
            if (y == minY) {
                col[y - minY] = BEDROCK_S;
            } else if (y < fl - dirtLayers) {
                col[y - minY] = STONE_S;
            } else if (y < fl) {
                BlockState sub;
                if (volcano && gridDi <= 0.70) {
                    sub = volcanicSubsurface(
                        x,
                        y,
                        z,
                        gridDi,
                        crater,
                        lavaLevel,
                        columnFissure,
                        strataShift
                    );
                } else {
                    sub = volcano
                        ? (hsh(x * 181, z * 191) < 0.72
                            ? Blocks.DIRT.defaultBlockState()
                            : Blocks.BASALT.defaultBlockState())
                        : subSurf(x, z, fl, st, spRaw, gridHi, beach);
                }
                col[y - minY] =
                    sub != null ? sub : Blocks.STONE.defaultBlockState();
            } else if (y == fl) {
                col[y - minY] = surface;
            } else if (crater && y <= lavaLevel) {
                col[y - minY] = Blocks.LAVA.defaultBlockState();
            } else if (y <= seaLevel && fl < seaLevel) {
                if (y == fl + 1 && underwaterSlab) {
                    col[y - minY] = slab(surface)
                        .setValue(SlabBlock.TYPE, SlabType.BOTTOM)
                        .setValue(SlabBlock.WATERLOGGED, true);
                } else if (y == fl + 1 && underwaterPlant == 1) {
                    col[y - minY] = Blocks.SEAGRASS.defaultBlockState();
                } else if (y == fl + 1 && underwaterPlant == 2) {
                    col[y - minY] = Blocks.TALL_SEAGRASS.defaultBlockState()
                        .setValue(
                            TallSeagrassBlock.HALF,
                            net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER
                        );
                } else if (y == fl + 2 && underwaterPlant == 2) {
                    col[y - minY] = Blocks.TALL_SEAGRASS.defaultBlockState()
                        .setValue(
                            TallSeagrassBlock.HALF,
                            net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER
                        );
                } else {
                    col[y - minY] = WATER_S;
                }
            } else {
                col[y - minY] = Blocks.AIR.defaultBlockState();
            }
        }
        return new NoiseColumn(minY, col);
        } catch (RuntimeException | Error error) {
            benchmarkError = error;
            throw error;
        } finally {
            String detail = System.nanoTime() - benchmark.startedNs() >
                OceanGeneratorDiagnostics.Stage.BASE_COLUMN.budgetNs()
                ? "block=[" + x + ',' + z + ']'
                : null;
            diagnostics.end(benchmark, seed, benchmarkError, detail);
        }
    }

    // -------------------------------------------------------------------------
    // NeoForge-команда /volcano (OP level 2)
    // -------------------------------------------------------------------------

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(
            Commands.literal("volcano")
                .requires(source -> source.hasPermission(2))
                .executes(OceanChunkGenerator::teleportToNearestVolcano)
        );
        dispatcher.register(
            Commands.literal("oceangen")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("benchmark")
                    .executes(ctx -> benchmarkStatus(ctx.getSource()))
                    .then(Commands.literal("status").executes(ctx -> benchmarkStatus(ctx.getSource())))
                    .then(Commands.literal("report").executes(ctx -> benchmarkReport(ctx.getSource())))
                    .then(Commands.literal("phases").executes(ctx -> benchmarkPhases(ctx.getSource())))
                    .then(Commands.literal("terrain").executes(ctx -> benchmarkTerrain(ctx.getSource())))
                    .then(Commands.literal("caches").executes(ctx -> benchmarkCaches(ctx.getSource())))
                    .then(Commands.literal("threads").executes(ctx -> benchmarkThreads(ctx.getSource())))
                    .then(Commands.literal("runtime").executes(ctx -> benchmarkRuntime(ctx.getSource())))
                    .then(Commands.literal("active").executes(ctx -> benchmarkActive(ctx.getSource())))
                    .then(Commands.literal("slow").executes(ctx -> benchmarkSlow(ctx.getSource())))
                    .then(Commands.literal("diagnose").executes(ctx -> benchmarkDiagnose(ctx.getSource())))
                    .then(Commands.literal("histograms").executes(ctx -> benchmarkHistograms(ctx.getSource())))
                    .then(Commands.literal("reset").executes(ctx -> benchmarkReset(ctx.getSource())))
                    .then(Commands.literal("export").executes(ctx -> benchmarkExport(ctx.getSource())))
                    .then(Commands.literal("enabled")
                        .then(Commands.argument("value", BoolArgumentType.bool())
                            .executes(ctx -> benchmarkEnabled(
                                ctx.getSource(), BoolArgumentType.getBool(ctx, "value")
                            )))
                    )
                    .then(Commands.literal("verbose")
                        .then(Commands.argument("enabled", BoolArgumentType.bool())
                            .executes(ctx -> benchmarkVerbose(
                                ctx.getSource(), BoolArgumentType.getBool(ctx, "enabled")
                            )))
                    )
                )
        );
    }

    private static int teleportToNearestVolcano(
        CommandContext<CommandSourceStack> context
    ) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = source.getPlayerOrException();
        ServerLevel level = player.serverLevel();

        if (!(level.getChunkSource().getGenerator() instanceof OceanChunkGenerator generator)) {
            source.sendFailure(Component.literal(
                "Эта команда работает только в мире с OceanChunkGenerator."
            ));
            return 0;
        }

        generator.syncSeedFromLevel(level);
        int[] volcano = generator.findNearestVolcano(
            player.getBlockX(),
            player.getBlockZ(),
            VOLCANO_COMMAND_SEARCH_RADIUS
        );
        if (volcano == null) {
            source.sendFailure(Component.literal(
                "Вулкан не найден в пределах " +
                (VOLCANO_COMMAND_SEARCH_RADIUS * CELL) +
                " блоков."
            ));
            return 0;
        }

        int targetX = volcano[0];
        int targetZ = volcano[1];
        level.getChunk(targetX >> 4, targetZ >> 4);
        BlockPos safe = findVolcanoTeleportPosition(level, targetX, targetZ);
        if (safe == null) {
            source.sendFailure(Component.literal(
                "Вулкан найден, но безопасная точка телепортации не определена."
            ));
            return 0;
        }

        player.teleportTo(
            level,
            safe.getX() + 0.5,
            safe.getY(),
            safe.getZ() + 0.5,
            player.getYRot(),
            player.getXRot()
        );
        source.sendSuccess(
            () -> Component.literal(
                "Вулкан найден у X=" + volcano[2] +
                ", Z=" + volcano[3] +
                ". Телепортация на безопасную кромку."
            ),
            true
        );
        return 1;
    }

    private static OceanChunkGenerator benchmarkGenerator(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        if (level.getChunkSource().getGenerator() instanceof OceanChunkGenerator generator) {
            generator.syncSeedFromLevel(level);
            return generator;
        }
        source.sendFailure(Component.literal(
            "Команда работает только в мире с OceanChunkGenerator."
        ));
        return null;
    }

    private static int benchmarkStatus(CommandSourceStack source) {
        OceanChunkGenerator generator = benchmarkGenerator(source);
        if (generator == null) return 0;
        generator.updateDiagnosticCacheSizes();
        source.sendSuccess(() -> Component.literal(
            generator.diagnostics.compactStatus(generator.seed)
        ), false);
        return 1;
    }

    private static int benchmarkReport(CommandSourceStack source) {
        OceanChunkGenerator generator = benchmarkGenerator(source);
        if (generator == null) return 0;
        generator.updateDiagnosticCacheSizes();
        generator.diagnostics.forceReport(generator.seed);
        source.sendSuccess(() -> Component.literal(
            generator.diagnostics.compactStatus(generator.seed)
                + " | полный отчёт записан в latest.log"
        ), false);
        return 1;
    }

    private static int benchmarkPhases(CommandSourceStack source) {
        OceanChunkGenerator generator = benchmarkGenerator(source);
        if (generator == null) return 0;
        String report = generator.diagnostics.phaseStatus();
        log.info("\n{}", report);
        source.sendSuccess(() -> Component.literal(report), false);
        return 1;
    }

    private static int benchmarkTerrain(CommandSourceStack source) {
        OceanChunkGenerator generator = benchmarkGenerator(source);
        if (generator == null) return 0;
        String report = generator.diagnostics.counterStatus();
        log.info("\n{}", report);
        source.sendSuccess(() -> Component.literal(report), false);
        return 1;
    }

    private static int benchmarkCaches(CommandSourceStack source) {
        OceanChunkGenerator generator = benchmarkGenerator(source);
        if (generator == null) return 0;
        generator.updateDiagnosticCacheSizes();
        String report = generator.diagnostics.cacheStatus();
        log.info("\n{}", report);
        source.sendSuccess(() -> Component.literal(report), false);
        return 1;
    }

    private static int benchmarkThreads(CommandSourceStack source) {
        OceanChunkGenerator generator = benchmarkGenerator(source);
        if (generator == null) return 0;
        String report = generator.diagnostics.threadStatus();
        log.info("\n{}", report);
        source.sendSuccess(() -> Component.literal(report), false);
        return 1;
    }

    private static int benchmarkRuntime(CommandSourceStack source) {
        OceanChunkGenerator generator = benchmarkGenerator(source);
        if (generator == null) return 0;
        String report = generator.diagnostics.runtimeStatus();
        log.info("\n{}", report);
        source.sendSuccess(() -> Component.literal(report), false);
        return 1;
    }

    private static int benchmarkActive(CommandSourceStack source) {
        OceanChunkGenerator generator = benchmarkGenerator(source);
        if (generator == null) return 0;
        String report = generator.diagnostics.activeStatus();
        log.info("\n{}", report);
        source.sendSuccess(() -> Component.literal(report), false);
        return 1;
    }

    private static int benchmarkSlow(CommandSourceStack source) {
        OceanChunkGenerator generator = benchmarkGenerator(source);
        if (generator == null) return 0;
        String report = generator.diagnostics.slowStatus();
        log.info("\n{}", report);
        source.sendSuccess(() -> Component.literal(report), false);
        return 1;
    }

    private static int benchmarkDiagnose(CommandSourceStack source) {
        OceanChunkGenerator generator = benchmarkGenerator(source);
        if (generator == null) return 0;
        generator.updateDiagnosticCacheSizes();
        String report = generator.diagnostics.diagnosis();
        log.info("\n{}", report);
        source.sendSuccess(() -> Component.literal(report), false);
        return 1;
    }

    private static int benchmarkHistograms(CommandSourceStack source) {
        OceanChunkGenerator generator = benchmarkGenerator(source);
        if (generator == null) return 0;
        String report = generator.diagnostics.histogramStatus();
        log.info("\n{}", report);
        source.sendSuccess(() -> Component.literal(
            "OceanGen histograms записаны в latest.log"
        ), false);
        return 1;
    }

    private static int benchmarkReset(CommandSourceStack source) {
        OceanChunkGenerator generator = benchmarkGenerator(source);
        if (generator == null) return 0;
        generator.diagnostics.reset();
        generator.updateDiagnosticCacheSizes();
        source.sendSuccess(() -> Component.literal(
            "OceanGen benchmark сброшен; seed=" + generator.seed
        ), true);
        return 1;
    }

    private static int benchmarkExport(CommandSourceStack source) {
        OceanChunkGenerator generator = benchmarkGenerator(source);
        if (generator == null) return 0;
        generator.updateDiagnosticCacheSizes();
        try {
            OceanGeneratorDiagnostics.ExportResult result = generator.diagnostics.export(
                FMLPaths.GAMEDIR.get().resolve("logs").resolve("oceangen"),
                generator.seed
            );
            source.sendSuccess(() -> Component.literal(
                "OceanGen benchmark экспортирован:\n" +
                result.reportPath() + "\n" + result.csvPath()
            ), false);
            return 1;
        } catch (IOException error) {
            log.error("Failed to export OceanGen benchmark", error);
            source.sendFailure(Component.literal(
                "Не удалось экспортировать benchmark: " + error.getMessage()
            ));
            return 0;
        }
    }

    private static int benchmarkVerbose(CommandSourceStack source, boolean enabled) {
        OceanChunkGenerator generator = benchmarkGenerator(source);
        if (generator == null) return 0;
        generator.diagnostics.setVerbose(enabled);
        source.sendSuccess(() -> Component.literal(
            "OceanGen verbose=" + enabled
        ), false);
        return 1;
    }

    private static int benchmarkEnabled(CommandSourceStack source, boolean enabled) {
        OceanChunkGenerator generator = benchmarkGenerator(source);
        if (generator == null) return 0;
        boolean changed = generator.diagnostics.enabled() != enabled;
        generator.diagnostics.setEnabled(enabled);
        if (enabled && changed) {
            generator.diagnostics.reset();
            generator.updateDiagnosticCacheSizes();
        }
        source.sendSuccess(() -> Component.literal(
            "OceanGen benchmark enabled=" + enabled +
            (enabled && changed ? "; начато новое окно" : "")
        ), true);
        return 1;
    }

    private static BlockPos findVolcanoTeleportPosition(
        ServerLevel level,
        int originX,
        int originZ
    ) {
        for (int radius = 0; radius <= VOLCANO_COMMAND_SAFE_RADIUS; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (
                        radius > 0 &&
                        Math.abs(dx) != radius &&
                        Math.abs(dz) != radius
                    ) continue;

                    int x = originX + dx;
                    int z = originZ + dz;
                    int y = level.getHeight(
                        Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                        x,
                        z
                    );
                    BlockPos feet = new BlockPos(x, y, z);
                    BlockState ground = level.getBlockState(feet.below());
                    if (!isSafeVolcanoGround(ground)) continue;
                    if (!level.getBlockState(feet).isAir()) continue;
                    if (!level.getBlockState(feet.above()).isAir()) continue;
                    return feet;
                }
            }
        }
        return null;
    }

    private static boolean isSafeVolcanoGround(BlockState state) {
        return !state.isAir() &&
            state.getFluidState().isEmpty() &&
            !state.is(Blocks.MAGMA_BLOCK) &&
            !state.is(Blocks.FIRE) &&
            !state.is(Blocks.SOUL_FIRE) &&
            !state.is(Blocks.CAMPFIRE) &&
            !state.is(Blocks.SOUL_CAMPFIRE);
    }

    @Override
    public void addDebugScreenInfo(
        List<String> info,
        RandomState random,
        BlockPos pos
    ) {
        info.add("OceanChunkGenerator");
        info.add("seaLevel=" + seaLevel + "  seed=" + seed);
        double st = islandDist(pos.getX(), pos.getZ());
        double spRaw = islandH(pos.getX(), pos.getZ(), st);
        double gridH = gridIslandH(pos.getX(), pos.getZ());
        info.add(
            "spawn: dist=" +
                String.format("%.2f", st) +
                "  h=" +
                String.format("%.2f", spRaw)
        );
        info.add("grid:  h=" + String.format("%.2f", gridH));
        info.add(diagnostics.compactStatus(seed));
    }
}
