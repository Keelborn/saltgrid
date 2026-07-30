package com.jokerdayn.swworldgencore.worldgen.terrain;

import com.jokerdayn.swworldgencore.worldgen.noise.TerrainNoise;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import javax.imageio.ImageIO;

/**
 * Lightweight visual QA for mountain silhouettes. It intentionally renders the pure
 * height fields, so it needs neither a running client nor generated chunks.
 */
public final class TerrainMountainPreview {

    private static final int SEA_LEVEL = 63;
    private static final int SIZE = 420;
    private static final long[] SEEDS = { 1L, -7_493_821_045L, 5_916_308_533_714_060_029L };

    private TerrainMountainPreview() {}

    public static void main(String[] args) throws IOException {
        File output = new File(args.length == 0 ? "build/terrain-preview" : args[0]);
        if (!output.exists() && !output.mkdirs()) {
            throw new IOException("Cannot create preview directory: " + output);
        }

        for (long seed : SEEDS) {
            TerrainNoise noise = new TerrainNoise(seed);
            SpawnIslandField spawn = new SpawnIslandField(noise);
            render(
                new File(output, "spawn-" + seed + ".png"),
                (x, z) -> spawn.heightAt(x, z, spawn.distanceTo(x, z)),
                0,
                0,
                1.15
            );

            GridIslandField grid = new GridIslandField(noise, SEA_LEVEL);
            int[] mountain = nearestMountain(grid);
            render(
                new File(output, "grid-" + seed + ".png"),
                (x, z) -> {
                    GridIslandSample sample = new GridIslandSample();
                    grid.sample(x, z, sample);
                    return sample.volcano ? 0.0 : sample.height;
                },
                mountain[0],
                mountain[1],
                0.95
            );
        }

        System.out.println("Mountain previews written to " + output.getAbsolutePath());
    }

    private static int[] nearestMountain(GridIslandField grid) {
        long bestDistance = Long.MAX_VALUE;
        int[] best = null;
        for (int radius = 0; radius <= 16; radius++) {
            for (int cellX = -radius; cellX <= radius; cellX++) {
                for (int cellZ = -radius; cellZ <= radius; cellZ++) {
                    if (Math.max(Math.abs(cellX), Math.abs(cellZ)) != radius) continue;
                    if (!grid.hasIsland(cellX, cellZ) || !grid.isMountainIsland(cellX, cellZ)) {
                        continue;
                    }
                    int x = grid.islandCenterX(cellX, cellZ);
                    int z = grid.islandCenterZ(cellX, cellZ);
                    if (grid.isVolcano(cellX, cellZ, x, z)) continue;
                    long distance = (long) x * x + (long) z * z;
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        best = new int[] { x, z };
                    }
                }
            }
            if (best != null) return best;
        }
        throw new IllegalStateException("No mountain island found in preview search radius");
    }

    private static void render(
        File file,
        HeightField field,
        int centerX,
        int centerZ,
        double blocksPerPixel
    ) throws IOException {
        double[] heights = new double[SIZE * SIZE];
        double maxHeight = 0.0;
        for (int py = 0; py < SIZE; py++) {
            for (int px = 0; px < SIZE; px++) {
                int x = centerX + (int) Math.round((px - SIZE / 2.0) * blocksPerPixel);
                int z = centerZ + (int) Math.round((py - SIZE / 2.0) * blocksPerPixel);
                double height = Math.max(0.0, field.heightAt(x, z));
                heights[py * SIZE + px] = height;
                maxHeight = Math.max(maxHeight, height);
            }
        }

        BufferedImage image = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
        double maxStep = 0.0;
        for (int py = 0; py < SIZE; py++) {
            for (int px = 0; px < SIZE; px++) {
                int index = py * SIZE + px;
                double height = heights[index];
                int r;
                int g;
                int b;
                if (height < 0.45) {
                    r = 22;
                    g = 78;
                    b = 132;
                } else if (height < 3.0) {
                    r = 205;
                    g = 190;
                    b = 127;
                } else if (height < 25.0) {
                    double highland = (height - 3.0) / 22.0;
                    r = (int) (69 + highland * 42);
                    g = (int) (132 - highland * 29);
                    b = (int) (61 - highland * 9);
                } else {
                    double summit = Math.min(1.0, (height - 25.0) / Math.max(1.0, maxHeight - 25.0));
                    r = (int) (103 + summit * 84);
                    g = (int) (100 + summit * 81);
                    b = (int) (91 + summit * 80);
                }

                double west = heights[py * SIZE + Math.max(0, px - 1)];
                double east = heights[py * SIZE + Math.min(SIZE - 1, px + 1)];
                double north = heights[Math.max(0, py - 1) * SIZE + px];
                double south = heights[Math.min(SIZE - 1, py + 1) * SIZE + px];
                maxStep = Math.max(
                    maxStep,
                    Math.max(Math.abs(west - east), Math.abs(north - south)) * 0.5
                );
                double shade = Math.max(-38.0, Math.min(38.0, (west - east + north - south) * 5.0));
                r = clamp((int) (r + shade));
                g = clamp((int) (g + shade));
                b = clamp((int) (b + shade));
                image.setRGB(px, py, 0xFF000000 | (r << 16) | (g << 8) | b);
            }
        }
        ImageIO.write(image, "png", file);
        System.out.println(
            file.getName() +
                " maxHeight=" + Math.round(maxHeight * 10.0) / 10.0 +
                " maxStep=" + Math.round(maxStep * 10.0) / 10.0
        );
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(255, value));
    }

    @FunctionalInterface
    private interface HeightField {
        double heightAt(int x, int z);
    }
}
