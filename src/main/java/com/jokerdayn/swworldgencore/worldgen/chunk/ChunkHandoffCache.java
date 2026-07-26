package com.jokerdayn.swworldgencore.worldgen.chunk;

import com.jokerdayn.swworldgencore.diagnostics.CacheId;
import com.jokerdayn.swworldgencore.diagnostics.GeneratorDiagnostics;
import java.util.Iterator;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Carries a {@link ChunkColumnCache} from the terrain pass to the decoration pass.
 *
 * <p>The two passes run at different chunk statuses and possibly on different threads, so
 * the summary has to be parked somewhere in between. Entries are removed on consumption;
 * the bound only exists to cap chunks that are filled but never decorated (a shutdown
 * mid-generation, or a chunk generated purely to answer a heightmap query).</p>
 */
public final class ChunkHandoffCache {

    static final int LIMIT = 4096;
    private static final int TRIM_BATCH = 256;

    private final GeneratorDiagnostics diagnostics;
    private final ConcurrentHashMap<Long, ChunkColumnCache> entries = new ConcurrentHashMap<>();

    public ChunkHandoffCache(GeneratorDiagnostics diagnostics) {
        this.diagnostics = diagnostics;
    }

    public void publish(long chunkKey, ChunkColumnCache columns) {
        entries.put(chunkKey, columns);
        trim();
        diagnostics.cacheState(CacheId.DECOR, entries.size(), LIMIT);
    }

    /** @return the summary for this chunk, or {@code null} if it was never published */
    public ChunkColumnCache consume(long chunkKey) {
        ChunkColumnCache columns = entries.remove(chunkKey);
        diagnostics.cacheAccess(CacheId.DECOR, columns != null);
        diagnostics.cacheState(CacheId.DECOR, entries.size(), LIMIT);
        return columns;
    }

    public int size() {
        return entries.size();
    }

    private void trim() {
        int size = entries.size();
        if (size <= LIMIT) return;
        long started = System.nanoTime();
        int toRemove = Math.min(size, Math.max(1, size - LIMIT) + TRIM_BATCH - 1);
        toRemove = Math.min(toRemove, TRIM_BATCH);
        int removed = 0;
        Iterator<Long> iterator = entries.keySet().iterator();
        while (removed < toRemove && iterator.hasNext()) {
            if (entries.remove(iterator.next()) != null) removed++;
        }
        if (removed > 0) {
            diagnostics.cacheTrim(CacheId.DECOR, removed, Math.max(0L, System.nanoTime() - started));
        }
    }
}
