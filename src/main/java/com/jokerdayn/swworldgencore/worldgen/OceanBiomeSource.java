package com.jokerdayn.swworldgencore.worldgen;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Optional;
import java.util.stream.Stream;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.QuartPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;

public class OceanBiomeSource extends BiomeSource {

    public static final MapCodec<OceanBiomeSource> CODEC =
        RecordCodecBuilder.mapCodec(instance ->
            instance
                .group(
                    Biome.CODEC.fieldOf("ocean").forGetter(source -> source.ocean),
                    Biome.CODEC.fieldOf("deep_ocean").forGetter(
                        source -> source.deepOcean
                    ),
                    Biome.CODEC.fieldOf("beach").forGetter(source -> source.beach),
                    Biome.CODEC.fieldOf("tropics").forGetter(source -> source.tropics),
                    Biome.CODEC.fieldOf("savanna").forGetter(source -> source.savanna),
                    Biome.CODEC.optionalFieldOf("volcano").forGetter(
                        source -> Optional.of(source.volcano)
                    )
                )
                .apply(instance, OceanBiomeSource::fromCodec)
        );

    private final Holder<Biome> ocean;
    private final Holder<Biome> deepOcean;
    private final Holder<Biome> beach;
    private final Holder<Biome> tropics;
    private final Holder<Biome> savanna;
    private final Holder<Biome> volcano;

    private volatile OceanChunkGenerator generator;

    public OceanBiomeSource(
        Holder<Biome> ocean,
        Holder<Biome> deepOcean,
        Holder<Biome> beach,
        Holder<Biome> tropics,
        Holder<Biome> savanna,
        Holder<Biome> volcano
    ) {
        this.ocean = ocean;
        this.deepOcean = deepOcean;
        this.beach = beach;
        this.tropics = tropics;
        this.savanna = savanna;
        this.volcano = volcano;
    }

    private static OceanBiomeSource fromCodec(
        Holder<Biome> ocean,
        Holder<Biome> deepOcean,
        Holder<Biome> beach,
        Holder<Biome> tropics,
        Holder<Biome> savanna,
        Optional<Holder<Biome>> volcano
    ) {
        return new OceanBiomeSource(
            ocean,
            deepOcean,
            beach,
            tropics,
            savanna,
            volcano.orElse(savanna)
        );
    }

    public static OceanBiomeSource create(HolderGetter<Biome> biomes) {
        return new OceanBiomeSource(
            biomes.getOrThrow(ModBiomes.OCEAN),
            biomes.getOrThrow(ModBiomes.DEEP_OCEAN),
            biomes.getOrThrow(ModBiomes.BEACH),
            biomes.getOrThrow(ModBiomes.TROPICS),
            biomes.getOrThrow(ModBiomes.SAVANNA),
            biomes.getOrThrow(ModBiomes.VOLCANO)
        );
    }

    void attachGenerator(OceanChunkGenerator generator) {
        this.generator = generator;
    }

    @Override
    protected MapCodec<? extends BiomeSource> codec() {
        return CODEC;
    }

    @Override
    protected Stream<Holder<Biome>> collectPossibleBiomes() {
        return Stream.of(ocean, deepOcean, beach, tropics, savanna, volcano);
    }

    @Override
    public Holder<Biome> getNoiseBiome(
        int quartX,
        int quartY,
        int quartZ,
        Climate.Sampler sampler
    ) {
        OceanChunkGenerator attachedGenerator = this.generator;
        if (attachedGenerator == null) return ocean;

        int x = QuartPos.toBlock(quartX) + 2;
        int z = QuartPos.toBlock(quartZ) + 2;
        return switch (attachedGenerator.classifyBiome(x, z)) {
            case DEEP_OCEAN -> deepOcean;
            case BEACH -> beach;
            case TROPICS -> tropics;
            case SAVANNA -> savanna;
            case VOLCANO -> volcano;
            default -> ocean;
        };
    }
}
