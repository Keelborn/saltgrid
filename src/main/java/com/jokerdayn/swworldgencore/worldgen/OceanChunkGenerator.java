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
import net.minecraft.world.level.block.SeagrassBlock;
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

    private static final Logger LOGGER = LoggerFactory.getLogger("SWWorldgenCore");

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

    @Override
    protected MapCodec<? extends ChunkGenerator> codec() {
        return CODEC;
    }

    // Хеш-функция: даёт псевдо-случайное число [0, 1] для координат (x, z)
    // Использует seed для воспроизводимости, но паттерн нерегулярный
    private double hash(int x, int z) {
        long n = (long) x * 73856093L ^ (long) z * 19349663L ^ seed;
        n = (n ^ (n >> 13)) * 1274126177L;
        n = n ^ (n >> 16);
        return (double) (n & 0x7FFFFFFFL) / (double) 0x7FFFFFFFL;
    }

    // Шум: интерполяция между хеш-значениями в узлах сетки
    // координаты умножаются на scale — чем больше, тем крупнее "острова"
    private double noise2D(double x, double z) {
        int xi = (int) Math.floor(x);
        int zi = (int) Math.floor(z);
        double xf = x - xi;
        double zf = z - zi;

        // Сглаживание (smoothstep)
        double u = xf * xf * (3 - 2 * xf);
        double v = zf * zf * (3 - 2 * zf);

        // Билинейная интерполяция 4 угловых значений
        double v00 = hash(xi, zi);
        double v10 = hash(xi + 1, zi);
        double v01 = hash(xi, zi + 1);
        double v11 = hash(xi + 1, zi + 1);

        double top = v00 + u * (v10 - v00);
        double bot = v01 + u * (v11 - v01);
        return top + v * (bot - top);
    }

    // Фрактальный шум (fbm) — сумма нескольких октав с уменьшающейся амплитудой
    // Каждая октава — более мелкие детали, поверх крупных
    private double fbm(double x, double z, int octaves, double lacunarity, double gain) {
        double value = 0;
        double amplitude = 1;
        double frequency = 1;
        double maxValue = 0;
        for (int i = 0; i < octaves; i++) {
            value += amplitude * noise2D(x * frequency, z * frequency);
            maxValue += amplitude;
            amplitude *= gain;
            frequency *= lacunarity;
        }
        return value / maxValue;
    }

    private int getOceanFloorHeight(int x, int z) {
        int baseHeight = 25;

        // Domain warping: искажаем координаты шумом для рваных форм
        double warpX = fbm(x * 0.005, z * 0.005, 2, 2.0, 0.5) * 80;
        double warpZ = fbm(x * 0.005 + 31.7, z * 0.005 + 47.3, 2, 2.0, 0.5) * 80;
        double wx = x + warpX;
        double wz = z + warpZ;

        // Крупные формы: холмы с искажениями (~250 блоков)
        double large = fbm(wx * 0.004, wz * 0.004, 4, 2.0, 0.45);

        // Средние формы: волны (~60 блоков)
        double medium = fbm(wx * 0.016, wz * 0.016, 3, 2.0, 0.4);

        // Мелкие детали: неровности (~20 блоков)
        double small = fbm(wx * 0.05, wz * 0.05, 3, 2.0, 0.35);

        // Очень мелкие: резкие перепады (~8 блоков)
        double micro = fbm(wx * 0.12, wz * 0.12, 2, 2.0, 0.3);

        // Крупные: ±25, средние ±8, мелкие ±4, микро ±2
        double height = baseHeight + large * 25 + medium * 8 + small * 4 + micro * 2;

        // Холмы минимум 4 блока под поверхностью океана
        return Math.max(-63, Math.min((int) height, seaLevel - 4));
    }

    // Реалистичное дно: песок основной, гравий редкие вкрапления
    private BlockState getSurfaceBlock(int x, int z, int floorHeight) {
        int depth = seaLevel - floorHeight;
        double depthNorm = Mth.clamp((double)(depth - 4) / 50.0, 0.0, 1.0);

        // Шум для пятен гравия (~60 блоков)
        double gravelNoise = fbm(x * 0.016, z * 0.016, 3, 2.0, 0.5);

        // Рваные края — искажение высокочастотным шумом
        double edgeWarp = fbm(x * 0.08, z * 0.08, 2, 2.0, 0.5) * 0.2;

        double combined = gravelNoise + edgeWarp;

        // Гравий только в явных пятнах, чем глубже — тем больше гравия
        double gravelThreshold = 0.7 - depthNorm * 0.2; // На мелководье 0.7, на глубине 0.5

        BlockState base;
        if (combined > gravelThreshold) {
            base = Blocks.GRAVEL.defaultBlockState();
        } else {
            base = Blocks.SAND.defaultBlockState();
        }

        // Редкие детали
        double detailRoll = hash(x * 7, z * 13);

        // Одиночные камни в гравии (3%)
        if (base.is(Blocks.GRAVEL) && detailRoll < 0.03) {
            return Blocks.COBBLESTONE.defaultBlockState();
        }

        // Пятна глины в песке (2%)
        if (base.is(Blocks.SAND) && detailRoll < 0.02) {
            return Blocks.CLAY.defaultBlockState();
        }

        return base;
    }

    // Получить подходящий слэб для блока поверхности
    private BlockState getSlabForSurface(BlockState surface) {
        if (surface.is(Blocks.SAND)) {
            return Blocks.SANDSTONE_SLAB.defaultBlockState();
        } else if (surface.is(Blocks.GRAVEL)) {
            return Blocks.COBBLESTONE_SLAB.defaultBlockState();
        } else if (surface.is(Blocks.COBBLESTONE)) {
            return Blocks.COBBLESTONE_SLAB.defaultBlockState();
        } else if (surface.is(Blocks.CLAY)) {
            return Blocks.STONE_SLAB.defaultBlockState();
        }
        return Blocks.STONE_SLAB.defaultBlockState();
    }

    // Определяет, нужно ли ставить водоросли (seagrass) на этом блоке
    private boolean shouldPlaceSeagrass(int x, int z, int floorHeight) {
        int depth = seaLevel - floorHeight;

        // Только на мелководье (до 20 блоков глубины)
        if (depth > 20 || depth < 2) return false;

        // Процент покрытия по глубине: 0-5м = 70%, 5-10м = 50%, 10-20м = 30%
        double coverageChance;
        if (depth <= 5) {
            coverageChance = 0.7;
        } else if (depth <= 10) {
            coverageChance = 0.5;
        } else {
            coverageChance = 0.3;
        }

        // Шум для пятнистости (не сплошной ковёр)
        double patchNoise = fbm(x * 0.08, z * 0.08, 2, 2.0, 0.5);

        // Хеш для одиночных пропусков
        double roll = hash(x * 31, z * 37);

        return patchNoise > 0.3 && roll < coverageChance;
    }

    // Проверяет, есть ли перепад высоты с соседями
    // Если текущий блок ниже соседа — ставим слэб для сглаживания
    private boolean needsSlab(int x, int z) {
        int h = getOceanFloorHeight(x, z);
        int north = getOceanFloorHeight(x, z - 1);
        int south = getOceanFloorHeight(x, z + 1);
        int east = getOceanFloorHeight(x + 1, z);
        int west = getOceanFloorHeight(x - 1, z);

        // Если хотя бы один сосед выше — нужен слэб
        return h < north || h < south || h < east || h < west;
    }

    @Override
    public CompletableFuture<ChunkAccess> fillFromNoise(Blender blender, RandomState randomState,
                                                         StructureManager structureManager, ChunkAccess chunk) {
        ChunkPos pos = chunk.getPos();
        int chunkX = pos.x;
        int chunkZ = pos.z;

        int slabCount = 0;
        int surfaceCount = 0;

        for (int localX = 0; localX < 16; localX++) {
            for (int localZ = 0; localZ < 16; localZ++) {
                int worldX = chunkX * 16 + localX;
                int worldZ = chunkZ * 16 + localZ;
                int floorHeight = getOceanFloorHeight(worldX, worldZ);
                boolean slab = needsSlab(worldX, worldZ);

                if (localX == 8 && localZ == 8) {
                    LOGGER.info("[OceanGen] Center block ({}, {}): floorHeight={}, needsSlab={}", worldX, worldZ, floorHeight, slab);
                }

                for (int y = chunk.getMinBuildHeight(); y < chunk.getMaxBuildHeight(); y++) {
                    BlockPos blockPos = new BlockPos(worldX, y, worldZ);

                    if (y == chunk.getMinBuildHeight()) {
                        chunk.setBlockState(blockPos, Blocks.BEDROCK.defaultBlockState(), false);
                    } else if (y < floorHeight) {
                        chunk.setBlockState(blockPos, Blocks.STONE.defaultBlockState(), false);
                    } else if (y == floorHeight) {
                        chunk.setBlockState(blockPos, Blocks.STONE.defaultBlockState(), false);
                    } else if (y == floorHeight + 1) {
                        BlockState surface = getSurfaceBlock(worldX, worldZ, floorHeight);
                        if (slab) {
                            chunk.setBlockState(blockPos, surface, false);
                            surfaceCount++;
                        } else {
                            chunk.setBlockState(blockPos, surface, false);
                            surfaceCount++;
                        }
                    } else if (y == floorHeight + 2) {
                        if (slab) {
                            BlockState surface = getSurfaceBlock(worldX, worldZ, floorHeight);
                            BlockState slabState = getSlabForSurface(surface)
                                    .setValue(SlabBlock.TYPE, SlabType.BOTTOM)
                                    .setValue(SlabBlock.WATERLOGGED, true);
                            chunk.setBlockState(blockPos, slabState, false);
                            slabCount++;
                            if (localX == 8 && localZ == 8) {
                                LOGGER.info("[OceanGen] Placed SLAB at ({}, {}, {}): {}", worldX, y, worldZ, slabState);
                            }
                        } else if (shouldPlaceSeagrass(worldX, worldZ, floorHeight)) {
                            // Высота водорослей: 1-3 блока
                            double heightRoll = hash(worldX * 17, worldZ * 23);
                            if (heightRoll < 0.3) {
                                chunk.setBlockState(blockPos, Blocks.TALL_SEAGRASS.defaultBlockState()
                                        .setValue(TallSeagrassBlock.HALF, net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER), false);
                                // Верхняя часть TallSeagrass
                                BlockPos upperPos = new BlockPos(worldX, y + 1, worldZ);
                                chunk.setBlockState(upperPos, Blocks.TALL_SEAGRASS.defaultBlockState()
                                        .setValue(TallSeagrassBlock.HALF, net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER), false);
                            } else {
                                chunk.setBlockState(blockPos, Blocks.SEAGRASS.defaultBlockState(), false);
                            }
                        }
                    } else if (y < seaLevel) {
                        chunk.setBlockState(blockPos, Blocks.WATER.defaultBlockState(), false);
                    }
                }

            }
        }

        if (chunkX % 4 == 0 && chunkZ % 4 == 0) {
            LOGGER.info("[OceanGen] Chunk ({}, {}): {} slabs, {} surface, floor range {}-{}",
                    chunkX, chunkZ, slabCount, surfaceCount,
                    getOceanFloorHeight(chunkX * 16, chunkZ * 16),
                    getOceanFloorHeight(chunkX * 16 + 15, chunkZ * 16 + 15));
        }

        return CompletableFuture.completedFuture(chunk);
    }

    @Override
    public void buildSurface(WorldGenRegion region, StructureManager structureManager,
                             RandomState randomState, ChunkAccess chunk) {
    }

    @Override
    public void applyCarvers(WorldGenRegion region, long seed, RandomState randomState,
                             BiomeManager biomeManager, StructureManager structureManager,
                             ChunkAccess chunk, GenerationStep.Carving step) {
    }

    @Override
    public void applyBiomeDecoration(WorldGenLevel level, ChunkAccess chunk, StructureManager structureManager) {
    }

    @Override
    public void spawnOriginalMobs(WorldGenRegion region) {
    }

    @Override
    public int getGenDepth() {
        return 384;
    }

    @Override
    public int getSeaLevel() {
        return seaLevel;
    }

    @Override
    public int getMinY() {
        return -64;
    }

    @Override
    public int getBaseHeight(int x, int z, Heightmap.Types heightmapType,
                             LevelHeightAccessor level, RandomState random) {
        return getOceanFloorHeight(x, z);
    }

    @Override
    public NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor level, RandomState random) {
        int height = getOceanFloorHeight(x, z);
        int minY = level.getMinBuildHeight();
        int maxY = level.getMaxBuildHeight();
        BlockState[] states = new BlockState[maxY - minY];
        for (int y = minY; y < maxY; y++) {
            if (y <= height) {
                states[y - minY] = Blocks.STONE.defaultBlockState();
            } else if (y == height + 1) {
                BlockState surface = getSurfaceBlock(x, z, height);
                states[y - minY] = surface;
            } else if (y == height + 2 && needsSlab(x, z)) {
                BlockState surface = getSurfaceBlock(x, z, height);
                states[y - minY] = getSlabForSurface(surface)
                        .setValue(SlabBlock.TYPE, SlabType.BOTTOM)
                        .setValue(SlabBlock.WATERLOGGED, true);
            } else if (y < seaLevel) {
                states[y - minY] = Blocks.WATER.defaultBlockState();
            } else {
                states[y - minY] = Blocks.AIR.defaultBlockState();
            }
        }
        return new NoiseColumn(minY, states);
    }

    @Override
    public void addDebugScreenInfo(List<String> info, RandomState random, BlockPos pos) {
        info.add("Ocean ChunkGenerator");
        info.add("Sea level: " + seaLevel);
        info.add("Seed: " + seed);
    }
}
