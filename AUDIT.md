# SW: Worldgen Core — code audit

Audit of the ocean generator, performed alongside the split of the 4401-line
`OceanChunkGenerator` into focused packages. 64 findings survived adversarial verification
(12 more were investigated and refuted — listed at the end so they are not re-raised).

All line references are to the **post-refactor** tree.

**Verification baseline.** The refactor is proven output-identical to commit `dce9cb6`:
`./gradlew worldgenChecks` digests both heightmaps, the biome classification and the full
384-block material column for ~27,000 columns across open ocean, the spawn island, a grid
island and a volcano, on three seeds, plus every spawn-beach, boulder-layout and locator
result. Every digest matched. Nothing in section 1 has been applied, precisely because those
fixes *do* change terrain.

---

## 1. Real terrain bugs — visible in game, fix changes world output

These are genuine artifacts, not stylistic complaints. **All of them change generated
terrain**, so applying them makes chunks generated afterwards disagree with chunks already on
disk (seams at chunk borders). Decide per item; on a world still in development, applying all
of them at once and regenerating is the cheap path.

### 1.1 Volcano islands end in a sheer, perfectly circular sea wall — CRITICAL

`worldgen/terrain/GridIslandField.java:149`

Cell selection hard-clips at the raw circle (`if (distSq >= radius * radius) continue;`). For
an ordinary island that is safe, because height is multiplied by the circular falloff, which
reaches 0 at exactly that radius. The volcano branch throws that guarantee away: it *replaces*
`t` with a domain-warped organic radius, and the outer plain term
(`islandShelf = 0.4 + shoreLift * 6.2 + inlandMask * rollingHills`) is a function of the
organic radius only — never multiplied by any circular falloff.

Since `outline` reaches 1.26 and the warp pushes inward for roughly half the azimuths, the
intended organic coastline has not been reached yet at `d = radius`, so `shoreLift` is still
saturated at 1.0 there.

Measured on the nearest volcano for 5 seeds, one block inside vs one block outside the clip
circle, fed through `computeFloor`: **the floor step is ≥ 4 blocks on 26–44 % of the
perimeter, mean positive step 5.3–9.8 blocks, worst case 29–35 blocks.** Outside the circle
the column falls all the way to the ocean floor. The same circle also flips `dirtLayers`
(3–8 → 0) and the seabed palette, so the underwater part shows a black disc ending on the
same machined arc.

**Fix.** Volcano-ness is a pure function of the cell, so evaluate it inside the selection
loop and give volcano cells a selection radius that covers the whole organic outline:

```java
double selection = isVolcano(cx, cz, centerX, centerZ) ? radius * 1.45 : radius;
if (distSq >= selection * selection) continue;
```

`1.45` covers max outline 1.26 plus max warp 0.17, and is still far below the ≥ 1536-block
minimum separation between island centres, so no cell can steal a neighbour's territory.
Additionally fade the constant shelf base so nothing survives past `organicT = 1`: multiply
`islandShelf` by `TerrainNoise.smoothstepClamped((1.0 - t) / 0.06)`. That also removes the
residual 0.4-block plateau (and its ~1-block floor step) outside the organic coast.

### 1.2 Circular unwalkable terrace around every caldera — HIGH

`worldgen/terrain/VolcanoGeometry.java:72`

`sealedCalderaHeight` fades its guaranteed height into the natural slope with a smoothstep,
but the outer `Math.max` then re-pins the result to `guaranteedHeight` at *every* radius in
the band, including the outermost. One step further out the raw height is returned unchanged.

Measured jump at the band edge: **mean 0.9–4.2 blocks, max 14, ≥ 3 blocks on 12–55 % of the
ring** (5 seeds). Because the radius is a pure unwarped function of the crater centre, it is a
geometrically perfect circle.

**Fix.** Only the inner part of the band has to be above the lava for the seal to hold:

```java
double keep = TerrainNoise.smoothstepClamped(
    (craterT - VOLCANO_LAVA_RADIUS)
        / (0.6 * (VOLCANO_RIM_OUTER_RADIUS - VOLCANO_LAVA_RADIUS)));
return Mth.lerp(keep, Math.max(guaranteedHeight, currentHeight), currentHeight);
```

The continuous ring at `craterT <= 0.19` is still ≥ lava + 4, so the caldera stays sealed,
while the outer 40 % of the band joins the natural flank continuously.

### 1.3 Every volcano has a frozen lava scar pointing due west — CRITICAL (cosmetic but universal)

`worldgen/terrain/VolcanoGeometry.java:101-102`

`Hashing.frac` reads only the low 24 bits, and the flow parameters are taken with
**arithmetic** right shifts of `flow * 11 + 3` and `flow * 9 + 7`. For `flow == 4` those are
47 and 43 — past the safe limit of 40 — so 7 (resp. 3) of the 24 bits in the window are
sign-bit replicas. The result is bimodal: `frac` lands either in `[0, 0.008]` or in
`[0.992, 1.0]`, and `baseAngle = frac * 2π - π` maps *both* clusters to the same direction.

So flow 4's direction is hard-locked to −x on every volcano in every world, and its meander
phase is pinned near 0. Flow 4 is only used by the surface palette (it is not in
`ACTIVE_FLOWS`), so the symptom is a black frozen-lava scar running due west on every single
volcano. Separately, flow 2 draws its direction *and* its meander phase from the same 24-bit
window (both shifts are 25), so those two are perfectly correlated.

**Fix.** Stop slicing one hash; re-mix per flow. This repairs all three findings at once:

```java
long flowHash = Hashing.mix64(volcanoHash + flow * 0x9E3779B97F4A7C15L);
double baseAngle = Hashing.frac(flowHash) * Math.PI * 2.0 - Math.PI;
double phase = Hashing.frac(flowHash >>> 24) * Math.PI * 2.0;
```

`Hashing.fracLogical` already exists for the unbiased case, and `Hashing.frac`'s javadoc now
documents the shift ≤ 40 constraint. Audit any other `frac(h >> k)`: the remaining call sites
(boulder layout shifts 8/16/24, savanna 0/20/40, lake islands 5/20/35) are all within limit.

### 1.4 Volcanic ash strata step at every chunk boundary — MEDIUM

`worldgen/terrain/VolcanicPalette.java:33`

`strataShiftAt` hashes `x >> 4, z >> 4` — i.e. chunk coordinates — so the vertical phase of
the tuff bands jumps at every 16-block boundary. On inner-cone cliff faces (where the skin is
up to 24 blocks thick) the bands visibly step across chunk lines.

**Fix.** Use a continuous field, or better a per-volcano constant so the strata stay level
across the whole cone:

```java
// per-volcano: pass the centre in, or
return (int) (noise.hsh((int) centerX * 29, (int) centerZ * 31) * 5);
```

The refactor already routes both the chunk pass and `getBaseColumn` through this single
method, so unlike before there is only one place to change.

### 1.5 Hard gates cut small circular steps into volcano flanks — LOW

`worldgen/terrain/GridIslandField.java:341` (gullies) and `:399` (valleys)

Both carvers are switched on by a boolean `if` on a continuous quantity, so the depth jumps
from 0 to its full value at the gate. Measured: gully heads step up to 3.1–4.6 blocks (on
~2 % of the gate circle — a narrow notch at each of the 7 centrelines); the valley gates cut
two circular steps into the coastal plain.

**Fix.** Multiply by a fade instead of gating:

```java
// gullies — replace the craterT gate with
double head = TerrainNoise.smoothstepClamped(
    (craterT - (VOLCANO_RIM_OUTER_RADIUS + 0.03)) / 0.06);
// valleys — replace the t gate with
double band = TerrainNoise.smoothstepClamped((t - 0.52) / 0.08)
            * TerrainNoise.smoothstepClamped((0.88 - t) / 0.08);
```

The gully's `t < coneEdge` gate is redundant — `slopeBand`'s second factor already reaches 0
there — so it can go.

### 1.6 Tropics/savanna boundary is a hard line on ~16 % of seeds — LOW

`worldgen/terrain/GridIslandField.java:550`

`isClearing` switches to the spawn island's clearing geometry at `spawnIslandDistance <= 1.0`,
whereas `computeFloor` treats land as existing out to `SPAWN_ISLAND_MAX_T` (≈ 1.706). Columns
in the feather ring are therefore sent to the *grid* island lookup, and get a clearing field
belonging to an island hundreds of blocks away. Measured over 200 seeds: a hard
TROPICS/SAVANNA line materialises at `st == 1.0` for ~16 % of them.

**Fix.** Use the same land test as `computeFloor`:
`if (spawnIslandDistance < IslandSettings.SPAWN_ISLAND_MAX_T)`.

### 1.7 Cross-chunk tree placement is order-dependent — HIGH

`worldgen/decor/SavannaTreeDecorator.java`, `worldgen/AcaciaGenerator.java`,
`worldgen/PalmGenerator.java`

Trees write into neighbouring chunks and preflight against live `level.getBlockState`. The
four `isReplaceable` sets are not reciprocal: `AcaciaGenerator` does not accept palm leaves or
this mod's own decoration blocks, and neither accepts the other's logs. So whether a tree
exists depends on which of two adjacent chunks was decorated first — the same seed can produce
different worlds, and re-generating a region gives a different result.

Note this is exactly the problem `BoulderDecorator` already solves correctly: it clips every
write to its own chunk and lets each chunk redraw its own slice.

**Fix (option 1, preferred).** Apply the boulder pattern: clip tree writes to
`[minX,maxX]×[minZ,maxZ]` and let each chunk redraw its slice. The tree shape is already a
pure function of `(baseX, baseY, baseZ, seed)`, so this works and removes the preflight
entirely. **(Option 2)** Keep cross-chunk writes but make the preflight purely generated
(`floorAt`/`isBeach`/hash only) and make the replaceable sets mutually inclusive.

### 1.8 `AcaciaGenerator` re-rolls rotation from a terrain-derived boolean — LOW

`worldgen/AcaciaGenerator.java:178`

`int variant = preferSmall ? 1 : rng.nextInt(ALL.length);` — when `preferSmall` is true the
variant draw is *not* consumed, so the RNG stream shifts and the rotation and vine pattern
change. `preferSmall` is derived from local slope, which means a tree's rotation depends on
terrain in a way nothing intends.

Also at `:173`: the seed is a raw XOR of coordinate multiples, never mixed.
`PalmGenerator.treeSeed` correctly runs its combination through `mix64` first.

**Fix.**

```java
int rolled = rng.nextInt(ALL.length);
int variant = preferSmall ? 1 : rolled;
int[][] blocks = ROTATED[variant][rng.nextInt(4)];
```

plus `Random rng = new Random(Hashing.mix64(cs));`.

### 1.9 All volcanic features are rank-correlated — LOW

`worldgen/decor/VolcanicFeatureDecorator.java:85`,
`worldgen/decor/VolcanicBiomeDecorator.java:77`

A single `pick` sample drives eight nested thresholds across both volcanic passes (tree roll
< 0.0014, plant < 0.16, dead bush < 0.06, levee < 0.55, fumarole < 0.012, chimney < 0.004,
bomb < 0.0035, obsidian variant < 0.18/0.30). Because they all read the same number, a column
that gets a fumarole *always* also passes every looser threshold. The features cluster instead
of being independent.

**Fix.** One salted sample per decision, e.g.
`hsh(x * 313 + floor * 3, z * 317 - floor * 5)`, or disjoint bit windows of one `rawHash` via
`frac(h)`, `frac(h >>> 24)`, `frac(h >>> 40)`.

---

## 2. Non-terrain bugs — fixed in this change

These were all applied; none affects generated blocks, which the digest test confirms.

| # | Problem | Where it was | What changed |
|---|---------|--------------|--------------|
| 2.1 | **Section lock leak.** The `LevelChunkSection.acquire()` loop and the heightmap creation sat *outside* the `try/finally` that releases. A throw part-way through left `PalettedContainer`s permanently locked — an unrecoverable chunk-system deadlock. | `fillFromNoise` | `ChunkTerrainBuilder.writeChunk` tracks `acquiredThrough` and releases exactly what it took, from inside the `try`. |
| 2.2 | **Seed publication race.** `seed` (volatile) and `seedOffsets` (volatile) were two independent writes, and the five caches were cleared as a third step. A reader could see the new offset table beside the old hash seed, or store a hybrid-seed value into a just-cleared cache. | `syncSeedFromLevel` | `OceanGeneratorPipeline` bundles the noise, all fields, all caches and all decorators. A seed change builds a whole new pipeline and publishes it with **one** volatile write, so the swap is indivisible. |
| 2.3 | **`ThreadLocal<double[8]>` aliasing.** The grid-island out-parameter was shared through one static `ThreadLocal`, and `floorAt` overwrote it. Correct only by convention — any caller holding the array across a nested `floorAt` read clobbered data. | `GRID_SAMPLE_SCRATCH` | Replaced by `GridIslandSample`, a named struct. Each consumer owns its own instance; `floorAt`, `classify` and `ColumnProbe` each have a private one that never escapes. |
| 2.4 | **F3 overlay tanked the client.** `addDebugScreenInfo` → `compactStatus` → `findingCount` → `findings()` → a full MXBean sweep (memory pools, all GC beans, buffer pools, every thread id) plus 38 histogram percentile scans — **every frame** while F3 was open. | `compactStatus` | `compactStatus` is now cheap by contract (documented); the expensive diagnosis moved behind `/oceangen benchmark diagnose`. |
| 2.5 | **Two JMX native calls per `getBaseHeight`.** `begin`/`end` called `getCurrentThreadCpuTime()` and `getThreadAllocatedBytes()` unconditionally. `getBaseHeight` runs thousands of times per chunk (structure placement, spawn searches), so ~1 µs × 4 per call dominated the work being measured. | `begin`/`end` | `Stage.detailed()` gates the probes to the coarse once-per-chunk stages. `BASE_HEIGHT`/`BASE_COLUMN` no longer pay for them. |
| 2.6 | **18 wasted noise octaves per column, everywhere.** `islandDist` ran three domain-warp octave pairs for every column in the world, although its result is provably clamped past ~511 blocks from origin. | `islandDist` | `SpawnIslandField.distanceTo` returns the clamp immediately beyond 512 blocks. The bound is derived in `IslandSettings.SPAWN_ISLAND_SKIP_DISTANCE`, so the shortcut is bit-identical — confirmed by the digest test. **This is the single largest win: open-ocean `floorAt` drops from ~30 to ~12 `pnoise` calls.** |
| 2.7 | **324 column floors computed then discarded** each chunk, then recomputed by the shoreline search moments later. | `fillFromNoise` | `sampleTerrain` primes this thread's memo tier via `ColumnCaches.primeFloor` — allocation-free, no shared state. |
| 2.8 | **~27 KB of garbage per chunk.** Sixteen arrays plus a sample struct allocated per `fillFromNoise`. At a few hundred chunks/s that is tens of MB/s of pure churn. | `fillFromNoise` | `ChunkTerrainScratch`, one per generation thread. Every array is fully rewritten each chunk (documented contract) so nothing can leak between chunks. |
| 2.9 | **`ConcurrentHashMap.size()` on every cache insert.** `size()` sums per-CPU `@Contended` counter cells; this ran on the hottest path millions of times. | `trimCache` | Amortised to one check in 64 inserts. Eviction can never affect output — every cached value is a pure function of `(x, z, seed)`. |
| 2.10 | **Redundant boulder re-validation.** `placeBoulder` re-ran the full site check (including an `isBeach` scan) that the cached layout had already proved with a *strictly stronger* predicate — once per chunk each boulder touches. | `placeBoulder` | Removed, with a comment explaining why it is safe. |
| 2.11 | **Block states rebuilt inside loops.** `Blocks.TALL_SEAGRASS.defaultBlockState().setValue(HALF, …)` walked the state table twice per plant; `DeferredHolder.get()` ran 256×/chunk. | write & decor loops | `TerrainBlocks` holds the pre-resolved states; decorators hoist `get()` out of the loop. |
| 2.12 | **Two wasted `fbm` calls per volcano column.** `grove` and `groveEdge` were computed for every volcano column but only read inside a `pick < 0.0014` branch. | volcanic decoration | Moved inside the branch. |
| 2.13 | **Diagnosis reported permanent false positives.** The invariant checks compared independent `LongAdder`s sampled at different instants, in the wrong order — so `requiredPerParent` and `terrain counter mismatch` fired constantly during any concurrent generation. | `findings()` | Every comparison now samples the side written *last* first, so a concurrent producer can only make an invariant look better. Documented. |
| 2.14 | **Allocation-per-call divided by the wrong denominator.** `avg(totalAllocatedBytes, calls)` under-reported whenever some calls had no allocation sample — which, after 2.5, is most of them. | `findings()` | Uses `allocationSamples`. |
| 2.15 | **CSV row splitting.** `csv()` quoted on `,`, `"` and `\n` but not `\r`. A CR in a thread name or detail string broke row alignment on read-back. | `csv()` | Added, with the reason. |
| 2.16 | **Latent wrong-layer water probe.** The login handler probed `Level.getSeaLevel()`, which is hard-coded to 63 in vanilla, not the dimension's configured `sea_level`. Correct today only because the JSON happens to say 63. | `hasActualOceanAtDistance` | Uses `ChunkGenerator.getSeaLevel()`. |
| 2.17 | **`spawnDebugLogged` never reset** on a seed change, so the one spawn diagnostic could describe a different noise field than the world. | | Reset inside the pipeline swap. |
| 2.18 | **`pickOre(long boulderHash)` ignored its argument** and always returned bronze — a hard-coded constant that looked seeded. | | Removed. |
| 2.19 | **Dead assignments.** The volcano branch recomputed `edge`/`falloff` and never read them. | `gridIslandSample` | Removed, with a note on why the plain-island falloff must *not* be reused there. |
| 2.20 | **`.randomTicks()` on a block that declines to tick.** `PALM_LEAF` requested random ticks while `PalmLeafBlock.isRandomlyTicking` returns false. | `ModBlocks` | Dropped. |
| 2.21 | **No way to turn instrumentation off.** Diagnostics defaulted to on with no config key, so every player paid for it permanently. | | New `generatorBenchmark` config option (default `true`, so behaviour is unchanged), applied at `LevelEvent.Load`. **Set it to `false` on a production server.** |
| 2.22 | **`/island` reported failure via `sendSuccess`.** | | `sendFailure`, via the shared `CommandSupport.generatorFor`. |

---

## 3. Performance problems left open — with estimates

Not applied because each is an algorithmic change with a design decision attached.

**3.1 Player login stalls the server thread — HIGH.**
`event/OceanSpawnHandler.java:134`. Up to 24 attempts × a 128-direction × ~114-step ray march,
plus up to 48 blocking `level.getChunk()` calls per attempt at scattered coastal positions.
The first login on a fresh world therefore forces synchronous full-status chunk generation on
the main thread. Finding 2.6 roughly halves the ray-march cost, but the blocking chunk loads
remain. Options: pre-warm the search asynchronously at `LevelEvent.Load`; cache the accepted
beach per seed; or drop `SEARCH_ATTEMPTS` to ~4 and widen the acceptance test.

**3.2 The shoreline scan is the dominant terrain cost — HIGH.**
`worldgen/terrain/TerrainColumnSampler.java:196-210`. Up to ~120 `floorAt` lookups per
uncached beach column (8 directions × ~15 samples). The "far edge first" trick already helps,
and 2.7 now serves the in-chunk part from the memo tier, but the fundamental shape is a
per-column ray march. A chamfer/BFS distance-to-water field over a padded 96×96 grid computed
once per chunk would replace it — at the cost of restructuring the pass, and it would change
output at the margins.

**3.3 Boxed cache keys — MEDIUM.**
`worldgen/terrain/ColumnCaches.java`. The shared tiers are
`ConcurrentHashMap<Long, …>`: every probe that misses the per-thread tier boxes a key, and at
the configured limits (262 144 + 131 072 + 131 072 entries) the maps retain roughly **33 MB**.
The clean fix is a primitive open-addressed cache; doing it *safely* under concurrency needs
either per-thread ownership (losing cross-thread reuse) or packing key-fingerprint and value
into a single `long` with `VarHandle` access. Deliberately left alone — a torn read here would
mean wrong terrain, and that is not a risk worth taking inside a refactor commit.

**3.4 `getNoiseBiome` asks 1536 times per chunk for 16 distinct columns — MEDIUM.**
`worldgen/OceanBiomeSource.java:110`. Biomes are sampled per quart cell in 3D, so the same
`(x, z)` is classified once per y layer. The biome cache absorbs it, but each of the 1536 calls
still boxes a `Long` key. A small per-thread `(x,z) → BiomeCategory` front cache would remove
96× of the work.

**3.5 `PalmGenerator` allocates ~25 KB per attempt — LOW.**
`worldgen/PalmGenerator.java:88`. `LinkedHashMap<LocalPos, PlannedBlock>` with record keys
allocates two records plus a map node per planned block (200–400 blocks), *even when the
preflight fails on the first block*. A `long`-keyed map (packed x/y/z) or a flat array over the
bounding box would remove nearly all of it. `java.util.Random` should also become
`SplittableRandom` or `RandomSource` — `nextGaussian()` is `synchronized`.

**3.6 Per-thread hot cache is smaller than the working set — LOW.**
`worldgen/terrain/HotColumnCache.java`. 8192 direct-mapped slots vs the ~9216 columns one
chunk's beach searches can touch, so it partially self-evicts within a single chunk. Raising it
to 16384 costs ~210 KB per generation thread.

**3.7 `cacheAccess` fires a `LongAdder` CAS on every `floorAt`/`isBeach`/`classify` — LOW.**
Mitigated by 2.21 (turn the benchmark off in production). If it should be cheap even when on,
sample it (e.g. 1 in 64) rather than counting every access.

---

## 4. Structure

`OceanChunkGenerator` went from 4401 lines to 396 — now only the `ChunkGenerator` contract and
the seed lifecycle. `OceanGeneratorDiagnostics` went from 1398 lines to a 6-file package.

```
diagnostics/          Stage Phase CacheId Counter Token SlowSample ExportResult
                      TimingStats CacheStats ThreadStats RuntimeSample
                      RuntimeProbe      — all JVM introspection, in one place
                      GeneratorDiagnostics — recording only, lock-free hot path
                      DiagnosticsReporter  — text/CSV/auto-diagnosis (allowed to be slow)
                      DiagnosticsFormat
registry/             ModBlocks ModItems ModCreativeTabs
command/              ModCommands IslandCommands VolcanoCommand BenchmarkCommand
                      CommandSupport   — commands were split across two event subscribers
event/                OceanSpawnHandler PlayerDataKeys
worldgen/
  GenSettings         vertical geometry, kept in sync with dimension_type/ocean.json
  OceanGeneratorPipeline   the unit of atomic seed publication
  noise/              Hashing TerrainNoise      — immutable, seeded
  terrain/            IslandSettings            — every shape knob
                      SpawnIslandField GridIslandField GridIslandSample VolcanoGeometry
                      TerrainColumnSampler ColumnCaches HotColumnCache
                      BiomeClassifier BiomeCategory
                      SurfacePalette VolcanicPalette TerrainBlocks
                      TerrainContext            — everything for one seed
  chunk/              ChunkTerrainBuilder ChunkTerrainScratch ChunkColumnCache
                      ChunkHandoffCache ColumnFlags ColumnProbe
  decor/              ChunkDecorator + Boulder/SavannaTree/VolcanicBiome/VolcanicFeature/
                      SmallFeature decorators, BoulderLayout, DecorSettings, TreePlacements
  spawn/              SpawnBeachFinder
```

The most valuable structural outcome is not the file count: it is that per-cell island
derivations (existence, radius, centre, mountain flag, volcano flag, island hash) were
copy-pasted across **five** call sites and now live once in `GridIslandField`. Those five
copies were free to drift, and drift there means the terrain shaper, the biome classifier, the
boulder placer and the `/island` and `/volcano` commands silently disagree about where the
islands are. Likewise `dirtLayersFor` is now shared between the chunk pass and
`getBaseColumn`, which previously duplicated the layering rules.

### Verification

```bash
./gradlew worldgenChecks
```

- `spawnSearchSmokeTest` — 48 spawn-beach assertions across 4 seeds, now driving the public
  API instead of reflecting into private methods, so a broken contract fails at compile time.
- `terrainDigestTest` — golden digest over ~27 000 columns × 3 seeds. **Fails if terrain output
  changes.** That is the check that matters for a world generator: the dangerous failure is not
  a crash but a silent change, which shows up as seams in worlds already on disk. When a change
  is intended (anything in section 1), update `EXPECTED` from the printed actual values.

Compiles clean under `-Xlint:all` (now enabled in `build.gradle`).

---

## 5. Smaller findings not yet applied

- **`bronze_ingot` is unobtainable** — registered, tabbed and localised, but no recipe exists;
  the smelting recipe for `bronze_q` is also missing. Note the 1.21 datapack folder is
  `recipe/`, not `recipes/`.
- **`shell` and `ground_decoration` loot tables drop a bare item**, so 2 of 3 shell colours
  and 6 of 7 decoration shapes cannot be re-placed by a player. Needs
  `copy_state`/`copy_components` on the variant property.
- **`bronze_ore` loot table ignores Silk Touch and Fortune**, unlike every vanilla ore.
- **Palm leaf item icon is untinted** while the placed block is tinted `0x399013` — register
  an item colour handler alongside the block one in `SWWorldgenCoreClient`.
- **Seashell item icon renders the whole 16×16 atlas**, including its opaque black filler.
- **All six biomes have empty monster spawner lists**, so no hostile mob spawns naturally.
  Intentional or not, worth confirming.
- **`RegisterColorHandlersEvent` registers a tint for vanilla `GRASS_BLOCK` and `SHORT_GRASS`
  globally** — it applies in every dimension and every other mod's world, not just the ocean
  dimension. `SWWorldgenCoreClient.grassColor` guards on `Level`, but `BlockAndTintGetter` is a
  render-region type, not a `Level`, so the dimension-specific savanna/tropics colours are
  effectively dead code *and* the fallback path still overrides vanilla grass colour everywhere.
- **Login handler re-teleports established players** whenever their respawn point is not in the
  ocean dimension, overwriting it. Intended for a dimension-locked modpack; surprising if the
  mod is ever used alongside others.
- **`ChunkAccess.getLevel()` is null for a `ProtoChunk`**, so the defensive seed sync inside
  `fillFromNoise` never fires. Harmless — `LevelEvent.Load` is the real path — but it is dead
  code that looks load-bearing.
- **`export()` has a TOCTOU** on its filename search and then writes with `TRUNCATE_EXISTING`.
- **`percentile()` returns the bucket upper bound**, biasing every reported percentile high by
  up to 18 % and inflating the "unstable tail" finding.
- **`TREE_*` phases are nested inside `DECOR_*` phases** but share the same parent stage, so
  the reported `parentShare` double-counts and can sum past 100 %.
- **`maybeReport` builds the full 16 KB report synchronously on a generation worker thread**
  when verbose is on.
- **`data/swworldgencore/structure/stone_ore1..4.nbt` are shipped but referenced by nothing** —
  either wire them up or drop them. The `accesstransformer.cfg` entry widening
  `StructureTemplate.palettes` is presumably for them and is likewise unused.
- **`textures/block/palm_leaf_candidate_v2.png` is unused** — the model points at
  `minecraft:block/jungle_leaves`.

---

## 6. Investigated and refuted

Recorded so they are not raised again. Each was checked against the actual code and found not
to be a defect: caldera off-centre from the summit; `ridgeNoise` octaves 3+ being wasted;
column-major section writes thrashing cache lines; `Level.getSeaLevel()` (a real latent issue,
but not a live bug — fixed anyway as 2.16); `PalmLeafBlock` churning `DISTANCE` state; caches
not being seed-stamped; `palm_sapling.png.mcmeta` structure; biome `grass_color` contradicting
the client tint; four "orphan" resources; the access transformer being unused; no biome
belonging to a biome tag; dead config translation keys.
