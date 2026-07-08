package com.jokerdayn.swworldgencore.worldgen;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.QuartPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.Climate;

import java.util.stream.Stream;

public class OceanBiomeSource extends BiomeSource {

    public static final MapCodec<OceanBiomeSource> CODEC = RecordCodecBuilder.mapCodec(instance ->
            instance.group(
                    Biome.CODEC.fieldOf("ocean").forGetter(s -> s.ocean),
                    Biome.CODEC.fieldOf("deep_ocean").forGetter(s -> s.deepOcean),
                    Biome.CODEC.fieldOf("beach").forGetter(s -> s.beach),
                    Biome.CODEC.fieldOf("tropics").forGetter(s -> s.tropics),
                    Biome.CODEC.fieldOf("savanna").forGetter(s -> s.savanna)
            ).apply(instance, OceanBiomeSource::new));

    private final Holder<Biome> ocean;
    private final Holder<Biome> deepOcean;
    private final Holder<Biome> beach;
    private final Holder<Biome> tropics;
    private final Holder<Biome> savanna;

    private volatile OceanChunkGenerator generator;

    public OceanBiomeSource(Holder<Biome> ocean, Holder<Biome> deepOcean, Holder<Biome> beach,
                            Holder<Biome> tropics, Holder<Biome> savanna) {
        this.ocean = ocean;
        this.deepOcean = deepOcean;
        this.beach = beach;
        this.tropics = tropics;
        this.savanna = savanna;
    }

    public static OceanBiomeSource create(HolderGetter<Biome> biomes) {
        return new OceanBiomeSource(
                biomes.getOrThrow(Biomes.OCEAN),
                biomes.getOrThrow(Biomes.DEEP_OCEAN),
                biomes.getOrThrow(Biomes.BEACH),
                biomes.getOrThrow(Biomes.JUNGLE),
                biomes.getOrThrow(Biomes.SAVANNA));
    }

    void attachGenerator(OceanChunkGenerator gen) {
        this.generator = gen;
    }

    @Override
    protected MapCodec<? extends BiomeSource> codec() {
        return CODEC;
    }

    @Override
    protected Stream<Holder<Biome>> collectPossibleBiomes() {
        return Stream.of(ocean, deepOcean, beach, tropics, savanna);
    }

    @Override
    public Holder<Biome> getNoiseBiome(int qx, int qy, int qz, Climate.Sampler sampler) {
        OceanChunkGenerator gen = this.generator;
        if (gen == null) return ocean;
        int x = QuartPos.toBlock(qx) + 2;
        int z = QuartPos.toBlock(qz) + 2;
        return switch (gen.classifyBiome(x, z)) {
            case DEEP_OCEAN -> deepOcean;
            case BEACH      -> beach;
            case TROPICS    -> tropics;
            case SAVANNA    -> savanna;
            default         -> ocean;
        };
    }
}
