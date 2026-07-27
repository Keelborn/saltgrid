package com.jokerdayn.swworldgencore.worldgen;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.fml.loading.LoadingModList;

/**
 * Golden-digest regression guard for generated terrain.
 *
 * <p>Run with {@code ./gradlew terrainDigestTest}. It walks tens of thousands of columns
 * across open ocean, the spawn island, a grid island and a volcano for three seeds, and
 * checksums every floor height, biome classification and full material column — plus the
 * spawn-beach, boulder-layout and locator results. Any change to the terrain maths shows up
 * as a digest mismatch.</p>
 *
 * <p>This is the single most useful test for a world generator, because the failure mode that
 * matters is not a crash but a <em>silent</em> change: existing worlds develop seams at chunk
 * borders generated before and after the change. When a change to the output is intended,
 * re-run with {@code -Pupdate} (or read the printed actual values) and replace
 * {@link #EXPECTED}.</p>
 *
 * <p>It deliberately drives only the public generator API, so the same expectations remain
 * valid across internal refactors.</p>
 */
public final class OceanTerrainDigestTest {

    private static final long[] SEEDS = { 1L, -7_493_821_045L, 5_916_308_533_714_060_029L };

    /** Matches {@code data/swworldgencore/dimension/ocean.json}. */
    private static final int SEA_LEVEL = 63;

    /**
     * Captured from commit {@code dce9cb6}, before the package split. Verified identical
     * before and after that refactor.
     */
    private static final String[] EXPECTED = {
        "seed=1",
        "island=1071,987",
        "volcano=-968,-3192,-943,-3213",
        "boulders=7 -71:-75 -52:-34 -90:-31 -28:-96 -101:52 -26:-66 -11:-82",
        "spawn[1,0]=271,64,-112/1",
        "spawn[1,1]=328,64,0/1",
        "spawn[1,2]=31,64,318/1",
        "spawn[2,0]=270,64,-111/2",
        "spawn[2,1]=327,64,16/2",
        "spawn[2,2]=31,64,317/2",
        "spawn[3,0]=269,64,-110/3",
        "spawn[3,1]=326,64,16/3",
        "spawn[3,2]=31,64,316/3",
        "region spawnIsland columns=2500 hashed=833 heights=506950e3be624d65"
            + " biomes=a82253db8baf4a6d blocks=2b4fbb0d77db8007",
        "region gridIsland columns=2500 hashed=833 heights=9614be4cbcdf51ab"
            + " biomes=f8da34c14f815a27 blocks=83fad90694faa9b2",
        "region volcano columns=3136 hashed=1046 heights=dc8245551be7e9e4"
            + " biomes=21528f09d8c831d2 blocks=aad13386af33bbb3",
        "region openOcean columns=900 hashed=300 heights=7e88982c3c2e91e8"
            + " biomes=7ca465c04ba22a5d blocks=880796b178ad3ad5",
        "seed=-7493821045",
        "island=1067,-1036",
        "volcano=-3090,-1220,-3104,-1186",
        "boulders=6 -69:-77 -33:-107 -91:27 -114:-21 -99:-47 57:-103",
        "spawn[1,0]=-104,64,-127/1",
        "spawn[1,1]=31,65,313/1",
        "spawn[1,2]=-170,64,207/1",
        "spawn[2,0]=-103,64,-126/2",
        "spawn[2,1]=31,65,312/2",
        "spawn[2,2]=-169,64,206/2",
        "spawn[3,0]=-102,64,-125/3",
        "spawn[3,1]=31,65,311/3",
        "spawn[3,2]=-168,64,205/3",
        "region spawnIsland columns=2500 hashed=833 heights=54f9ec5f921a7305"
            + " biomes=fe7d631a2eec42db blocks=f1690ff697ad6d54",
        "region gridIsland columns=2500 hashed=834 heights=253c7e854c45633e"
            + " biomes=1fbb955f5770cf77 blocks=be96d1f3baa8c97b",
        "region volcano columns=3136 hashed=1046 heights=10cf420d869d412e"
            + " biomes=2da98d6b50d4f6e2 blocks=c3cb08c458f1bd61",
        "region openOcean columns=900 hashed=300 heights=eec39f42788d31bd"
            + " biomes=d9717492a52020d5 blocks=5de34b38f5aabf81",
        "seed=5916308533714060029",
        "island=812,848",
        "volcano=3276,3008,3251,2987",
        "boulders=3 -43:-84 -94:-19 -67:-36",
        "spawn[1,0]=-207,64,52/1",
        "spawn[1,1]=-74,64,295/1",
        "spawn[1,2]=192,64,-174/1",
        "spawn[2,0]=-206,64,52/2",
        "spawn[2,1]=-74,65,294/2",
        "spawn[2,2]=191,64,-173/2",
        "spawn[3,0]=-205,64,52/3",
        "spawn[3,1]=-74,65,293/3",
        "spawn[3,2]=190,64,-172/3",
        "region spawnIsland columns=2500 hashed=833 heights=ef12d923f9909fa5"
            + " biomes=1f9a18a4cfd3add3 blocks=cff93b8187271997",
        "region gridIsland columns=2500 hashed=834 heights=60f25805fdc421e1"
            + " biomes=b5275238d643dd44 blocks=7ca12b083e7e98c5",
        "region volcano columns=3136 hashed=1045 heights=90567c7c1a375af7"
            + " biomes=1fa2bccddc6dc9d blocks=d247333a5e336e61",
        "region openOcean columns=900 hashed=300 heights=5b3bac38c14adb6e"
            + " biomes=d9717492a52020d5 blocks=c4f4df4239b0813e",
    };

    private static final LevelHeightAccessor HEIGHT = new LevelHeightAccessor() {
        @Override
        public int getHeight() {
            return 384;
        }

        @Override
        public int getMinBuildHeight() {
            return -64;
        }
    };

    private OceanTerrainDigestTest() {}

    public static void main(String[] args) {
        LoadingModList.of(List.of(), List.of(), List.of(), List.of(), Map.of());
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();

        List<String> actual = collect();

        int mismatches = 0;
        int limit = Math.max(actual.size(), EXPECTED.length);
        for (int i = 0; i < limit; i++) {
            String expected = i < EXPECTED.length ? EXPECTED[i] : "<missing>";
            String observed = i < actual.size() ? actual.get(i) : "<missing>";
            if (expected.equals(observed)) continue;
            mismatches++;
            System.out.println("MISMATCH at line " + i);
            System.out.println("  expected: " + expected);
            System.out.println("  actual:   " + observed);
        }

        if (mismatches > 0) {
            System.out.println();
            System.out.println("Full actual digest (paste into EXPECTED if intended):");
            for (String line : actual) System.out.println("        \"" + line + "\",");
            throw new IllegalStateException(
                "Terrain output changed: " + mismatches + " digest line(s) differ. "
                    + "If this was intentional, update OceanTerrainDigestTest.EXPECTED — but "
                    + "be aware that existing worlds will develop seams at chunk borders."
            );
        }
        System.out.println(
            "Terrain digest test passed: " + actual.size() + " digest lines match across "
                + SEEDS.length + " seeds"
        );
    }

    private static List<String> collect() {
        List<String> lines = new ArrayList<>();
        for (long seed : SEEDS) {
            // Null biome holders are fine: the digest never resolves a Biome, only the
            // generator's own classification enum.
            OceanBiomeSource biomes = new OceanBiomeSource(null, null, null, null, null, null);
            OceanChunkGenerator generator = new OceanChunkGenerator(biomes, seed, SEA_LEVEL);
            generator.diagnostics().setEnabled(false);

            lines.add("seed=" + seed);

            int[] island = generator.findNearestIslandCenter(0, 0);
            lines.add("island=" + island[0] + "," + island[1]);

            int[] volcano = generator.findNearestVolcano(0, 0, 64);
            lines.add("volcano=" + (volcano == null
                ? "none"
                : volcano[0] + "," + volcano[1] + "," + volcano[2] + "," + volcano[3]));

            StringBuilder boulders = new StringBuilder();
            int[][] positions = generator.getSpawnBoulderPositions();
            boulders.append("boulders=").append(positions.length);
            for (int[] boulder : positions) {
                boulders.append(' ').append(boulder[0]).append(':').append(boulder[1]);
            }
            lines.add(boulders.toString());

            for (int distance = 1; distance <= 3; distance++) {
                for (long salt = 0; salt < 3; salt++) {
                    var spawn = generator.findSpawnBeachPosition(distance, salt);
                    lines.add("spawn[" + distance + "," + salt + "]=" + (spawn == null
                        ? "null"
                        : spawn.feet().getX() + "," + spawn.feet().getY() + ","
                            + spawn.feet().getZ() + "/" + spawn.oceanDistance()));
                }
            }

            lines.add(region(generator, "spawnIsland", 0, 0, 100, 4));
            lines.add(region(generator, "gridIsland", island[0], island[1], 100, 4));
            if (volcano != null) {
                lines.add(region(generator, "volcano", volcano[2], volcano[3], 140, 5));
            }
            lines.add(region(generator, "openOcean", 1_000_003, -2_000_007, 60, 4));
        }
        return lines;
    }

    /** Digests one square region: both heightmaps, the biome, and a subsampled full column. */
    private static String region(
        OceanChunkGenerator generator,
        String name,
        int centerX,
        int centerZ,
        int half,
        int step
    ) {
        long heights = FNV_OFFSET;
        long biomes = FNV_OFFSET;
        long blocks = FNV_OFFSET;
        int columns = 0;
        int hashed = 0;

        for (int x = centerX - half; x < centerX + half; x += step) {
            for (int z = centerZ - half; z < centerZ + half; z += step) {
                heights = mixLong(heights, generator.getBaseHeight(
                    x, z, Heightmap.Types.OCEAN_FLOOR_WG, HEIGHT, null));
                heights = mixLong(heights, generator.getBaseHeight(
                    x, z, Heightmap.Types.WORLD_SURFACE_WG, HEIGHT, null));
                biomes = mixText(biomes, generator.classifyBiome(x, z).toString());
                columns++;

                // Full material column on every third position: enough to catch any change
                // to layering, palettes, seagrass, slabs or lava fill without hashing 3.5M
                // block states per region.
                if (((x / step) + (z / step)) % 3 == 0) {
                    NoiseColumn column = generator.getBaseColumn(x, z, HEIGHT, null);
                    for (int y = -64; y < 320; y++) {
                        blocks = mixText(blocks, column.getBlock(y).getBlock().getDescriptionId());
                    }
                    hashed++;
                }
            }
        }
        return "region " + name
            + " columns=" + columns
            + " hashed=" + hashed
            + " heights=" + Long.toHexString(heights)
            + " biomes=" + Long.toHexString(biomes)
            + " blocks=" + Long.toHexString(blocks);
    }

    private static final long FNV_OFFSET = 0xcbf29ce484222325L;
    private static final long FNV_PRIME = 0x100000001b3L;

    private static long mixLong(long digest, long value) {
        for (int i = 0; i < 8; i++) {
            digest ^= (value >>> (i * 8)) & 0xFF;
            digest *= FNV_PRIME;
        }
        return digest;
    }

    private static long mixText(long digest, String value) {
        for (int i = 0; i < value.length(); i++) {
            digest ^= value.charAt(i);
            digest *= FNV_PRIME;
        }
        return digest;
    }
}
