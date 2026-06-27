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

    // радиус острова в блоках — от центра до начала пляжа
    private static final double SPAWN_ISLAND_RADIUS = 170.0;
    // ширина переходной зоны (пляж + склон под воду)
    private static final double SPAWN_ISLAND_FEATHER = 60.0;
    // максимальная высота холмов над seaLevel
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

    private double hash(int x, int z) {
        long n = (long) x * 73856093L ^ (long) z * 19349663L ^ seed;
        n = (n ^ (n >> 13)) * 1274126177L;
        n = n ^ (n >> 16);
        return (double) (n & 0x7FFFFFFFL) / (double) 0x7FFFFFFFL;
    }

    private double vnoise(double x, double z) {
        int xi = (int) Math.floor(x), zi = (int) Math.floor(z);
        double xf = x - xi, zf = z - zi;
        double u = xf * xf * (3 - 2 * xf);
        double v = zf * zf * (3 - 2 * zf);

        double a = hash(xi, zi), b = hash(xi + 1, zi);
        double c = hash(xi, zi + 1), d = hash(xi + 1, zi + 1);
        return (a + u * (b - a)) + v * ((c + u * (d - c)) - (a + u * (b - a)));
    }

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

    private int floorAt(int x, int z) {
        double wx = x + fbm(x * 0.005, z * 0.005, 2, 2.0, 0.5) * 80;
        double wz = z + fbm(x * 0.005 + 31.7, z * 0.005 + 47.3, 2, 2.0, 0.5) * 80;

        double h = BASE_FLOOR
                + fbm(wx * 0.004, wz * 0.004, 4, 2.0, 0.45) * 25
                + fbm(wx * 0.016, wz * 0.016, 3, 2.0, 0.4) * 8
                + fbm(wx * 0.05, wz * 0.05, 3, 2.0, 0.35) * 4
                + fbm(wx * 0.12, wz * 0.12, 2, 2.0, 0.3) * 2;

        int floor = Math.max(-63, Math.min((int) h, seaLevel - 4));
        int sp = spawnIslandHeight(x, z);
        if (sp > 0) floor = Math.max(floor, seaLevel + sp);
        return floor;
    }

    // тропическая поверхность: песок → coarse dirt → грунт → трава
    // для спавн-острова pickSurface получает spawnT и использует его для выбора слоя
    private BlockState pickSurface(int x, int z, int fl, double spawnDist) {
        // спавн-остров: определяем зону по spawnT
        if (spawnDist < 1.0) {
            if (fl < seaLevel) {
                // под водой — как обычно (sand/gravel)
                return pickOceanFloor(x, z, fl);
            }
            int aboveSea = fl - seaLevel;
            if (aboveSea <= 2) {
                // пляжная кромка — песок
                return Blocks.SAND.defaultBlockState();
            }
            // остров выше пляжа: coarse dirt ближе к берегу, дальше — grass
            // переход определяется spawnDist: ближе к 0 — трава, ближе к 1 — песок/coarse dirt
            double beachBlend = Mth.clamp((spawnDist - 0.55) / 0.35, 0.0, 1.0);
            if (beachBlend > 0.6) return Blocks.SAND.defaultBlockState();
            if (beachBlend > 0.3) return Blocks.COARSE_DIRT.defaultBlockState();
            return Blocks.GRASS_BLOCK.defaultBlockState();
        }

        // обычный океанский пол
        if (fl >= seaLevel) {
            return fl <= seaLevel + 2 ? Blocks.SAND.defaultBlockState() : Blocks.GRASS_BLOCK.defaultBlockState();
        }
        return pickOceanFloor(x, z, fl);
    }

    // вынесено из pickSurface — океанский пол без изменений
    private BlockState pickOceanFloor(int x, int z, int fl) {
        int depth = seaLevel - fl;
        double depthNorm = Mth.clamp((double)(depth - 4) / 50.0, 0.0, 1.0);

        double n = fbm(x * 0.016, z * 0.016, 3, 2.0, 0.5)
                + fbm(x * 0.08, z * 0.08, 2, 2.0, 0.5) * 0.2;

        double thresh = 0.7 - depthNorm * 0.2;
        BlockState base = n > thresh ? Blocks.GRAVEL.defaultBlockState() : Blocks.SAND.defaultBlockState();

        double det = hash(x * 7, z * 13);
        if (base.is(Blocks.GRAVEL) && det < 0.03) return Blocks.COBBLESTONE.defaultBlockState();
        if (base.is(Blocks.SAND) && det < 0.02) return Blocks.CLAY.defaultBlockState();

        return base;
    }

    // возвращает подповерхностный блок спавн-острова (под grass/coarse dirt)
    private BlockState pickSubSurface(int x, int z, int fl, double spawnDist) {
        if (spawnDist >= 1.0 || fl < seaLevel) return null;
        int aboveSea = fl - seaLevel;
        if (aboveSea <= 2) return Blocks.SAND.defaultBlockState(); // под пляжем — песок
        double beachBlend = Mth.clamp((spawnDist - 0.55) / 0.35, 0.0, 1.0);
        if (beachBlend > 0.3) return Blocks.SAND.defaultBlockState();
        return Blocks.DIRT.defaultBlockState(); // под травой — земля
    }

    private BlockState pickSlab(BlockState s) {
        if (s.is(Blocks.SAND)) return Blocks.SANDSTONE_SLAB.defaultBlockState();
        if (s.is(Blocks.GRAVEL) || s.is(Blocks.COBBLESTONE)) return Blocks.COBBLESTONE_SLAB.defaultBlockState();
        return Blocks.STONE_SLAB.defaultBlockState();
    }

    private boolean wantsSeagrass(int x, int z, int fl) {
        int depth = seaLevel - fl;
        if (depth < 2 || depth > 40) return false;

        double ch;
        if (depth <= 5) ch = 0.7;
        else if (depth <= 10) ch = 0.5;
        else if (depth <= 20) ch = 0.3;
        else if (depth <= 30) ch = 0.1;
        else ch = 0.04;

        return fbm(x * 0.08, z * 0.08, 2, 2.0, 0.5) > 0.3 && hash(x * 31, z * 37) < ch;
    }

    private boolean slopeNeedsSlab(int x, int z) {
        int h = floorAt(x, z);
        return h < floorAt(x, z - 1) || h < floorAt(x, z + 1)
                || h < floorAt(x + 1, z) || h < floorAt(x - 1, z);
    }

    private boolean isIslandCenter(int x, int z) {
        int cellX = Math.floorDiv(x, 2048), cellZ = Math.floorDiv(z, 2048);
        if (hash(cellX * 11, cellZ * 13) > 0.6) return false;

        int cx = cellX * 2048 + 768 + (int)(hash(cellX * 2, cellZ * 2) * 512);
        int cz = cellZ * 2048 + 768 + (int)(hash(cellX * 2 + 1, cellZ * 2 + 1) * 512);
        return x == cx && z == cz;
    }

    // нормализованное расстояние от центра спавн-острова [0,1], >1 = за пределами
    // многослойная деформация: крупные бухты + средние изгибы + мелкие зазубрины
    private double spawnT(int x, int z) {
        double dist = Math.sqrt((double) x * x + (double) z * z);

        // крупные бухты и полуострова — низкочастотный warp
        double warpLarge = fbm(x * 0.004 + 7.3, z * 0.004 + 2.1, 4, 2.0, 0.55) * 55
                         + fbm(x * 0.004 + 91.7, z * 0.004 + 53.4, 3, 2.0, 0.5) * 35;

        // средние изгибы береговой линии
        double warpMid = fbm(x * 0.012 + 17.9, z * 0.012 + 83.1, 3, 2.0, 0.45) * 25
                       + fbm(x * 0.018 + 44.2, z * 0.018 + 11.6, 3, 2.0, 0.4) * 15;

        // мелкие зазубрины и неровности берега
        double warpFine = fbm(x * 0.04 + 33.5, z * 0.04 + 67.8, 2, 2.0, 0.38) * 8
                        + fbm(x * 0.07 + 5.5, z * 0.07 + 99.2, 2, 2.0, 0.35) * 4;

        double d = dist - (warpLarge + warpMid + warpFine);
        return Mth.clamp(d / SPAWN_ISLAND_RADIUS, 0.0, 1.0 + SPAWN_ISLAND_FEATHER / SPAWN_ISLAND_RADIUS);
    }

    // высота острова над seaLevel: многослойный рельеф с холмами
    private int spawnIslandHeight(int x, int z) {
        double t = spawnT(x, z);
        // feather zone: остров плавно уходит под воду на расстоянии SPAWN_ISLAND_FEATHER
        double maxT = 1.0 + SPAWN_ISLAND_FEATHER / SPAWN_ISLAND_RADIUS;
        if (t >= maxT) return 0;

        // edge falloff: 1.0 в центре, 0.0 на границе воды
        // smoothstep для плавности
        double edge = Mth.clamp(1.0 - t / maxT, 0.0, 1.0);
        double falloff = edge * edge * (3.0 - 2.0 * edge);

        // если falloff слишком мал — остров ещё под водой, не генерировать
        if (falloff < 0.05) return 0;

        // крупные холмы (1-2 холма на остров)
        double hillLarge = (fbm(x * 0.006 + 13.0, z * 0.006 + 77.0, 4, 2.0, 0.5) - 0.3) * 1.4;
        hillLarge = Math.max(0, hillLarge);

        // средние перепады рельефа
        double hillMid = fbm(x * 0.018 + 55.0, z * 0.018 + 31.0, 3, 2.0, 0.48) * 0.5
                       + fbm(x * 0.03 + 88.0, z * 0.03 + 22.0, 3, 2.0, 0.44) * 0.3;

        // мелкая рябь поверхности
        double detail = fbm(x * 0.07 + 4.4, z * 0.07 + 61.0, 2, 2.0, 0.42) * 0.15
                      + fbm(x * 0.12, z * 0.12, 2, 2.0, 0.35) * 0.07;

        // итоговый рельеф: большие холмы давят на маску сильнее в центре
        double relief = hillLarge * 0.6 + hillMid + detail;

        // применяем falloff: у берега рельеф прижат к нулю, в центре — полный
        double h = relief * falloff * SPAWN_ISLAND_MAX_HEIGHT;

        // гарантируем минимум 1 блок над водой там где остров есть
        return (int) Math.max(1, h);
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
                int fl = floorAt(wx, wz);
                boolean onSlope = slopeNeedsSlab(wx, wz);
                boolean isCenter = isIslandCenter(wx, wz);
                double st = spawnT(wx, wz);

                if (isCenter)
                    log.info("[Island] center at ({}, {})", wx, wz);

                // кол-во dirt-слоёв под поверхностью на спавн-острове
                // на пляже — 0 (там песок до камня), во внутренних зонах — 2-3
                int dirtLayers = 0;
                if (st < 1.0 && fl >= seaLevel) {
                    double beachBlend = Mth.clamp((st - 0.55) / 0.35, 0.0, 1.0);
                    if (beachBlend <= 0.3) dirtLayers = 3;
                    else if (beachBlend <= 0.6) dirtLayers = 1;
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
                        // dirt-прослойка под поверхностью (только на спавн-острове, выше воды)
                        BlockState sub = pickSubSurface(wx, wz, fl, st);
                        chunk.setBlockState(at, sub != null ? sub : Blocks.STONE.defaultBlockState(), false);

                    } else if (y == fl) {
                        chunk.setBlockState(at, pickSurface(wx, wz, fl, st), false);
                        surfCnt++;

                    } else if (y == fl + 1) {
                        if (fl >= seaLevel) {
                            // воздух над сушей
                        } else if (onSlope) {
                            BlockState sl = pickSlab(pickSurface(wx, wz, fl, st))
                                    .setValue(SlabBlock.TYPE, SlabType.BOTTOM)
                                    .setValue(SlabBlock.WATERLOGGED, true);
                            chunk.setBlockState(at, sl, false);
                            slabCnt++;

                        } else if (wantsSeagrass(wx, wz, fl)) {
                            boolean tall = hash(wx * 17, wz * 23) < 0.3;
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
    public void applyBiomeDecoration(WorldGenLevel level, ChunkAccess chunk, StructureManager structureManager) {}

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
        int fl = floorAt(x, z);
        return slopeNeedsSlab(x, z) ? fl + 2 : fl + 1;
    }

    @Override
    public NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor level, RandomState random) {
        int fl = floorAt(x, z);
        boolean onSlope = slopeNeedsSlab(x, z);
        double st = spawnT(x, z);

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
                BlockState sub = pickSubSurface(x, z, fl, st);
                col[y - minY] = sub != null ? sub : Blocks.STONE.defaultBlockState();

            } else if (y == fl) {
                col[y - minY] = pickSurface(x, z, fl, st);

            } else if (y == fl + 1) {
                if (fl >= seaLevel) {
                    col[y - minY] = Blocks.AIR.defaultBlockState();
                } else if (onSlope) {
                    col[y - minY] = pickSlab(pickSurface(x, z, fl, st))
                            .setValue(SlabBlock.TYPE, SlabType.BOTTOM)
                            .setValue(SlabBlock.WATERLOGGED, true);
                } else if (wantsSeagrass(x, z, fl)) {
                    if (hash(x * 17, z * 23) < 0.3) {
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