package dev.jingyu.jia.victim;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Incident 2: an unbounded "session cache" that is a plain static-ish {@code List} with no
 * eviction, no size cap and no expiry. Every call appends {@code mb} megabytes of filled
 * {@code byte[]} plus the key strings that index them, so old-gen occupancy after every GC
 * climbs monotonically until the JVM is thrashing Full GC against a wall.
 *
 * <p>{@code DELETE /victim/leak} clears it, which is what lets the same JVM be reused for a
 * clean capture.</p>
 */
@Component
public class LeakyCache {

    private static final Logger log = LoggerFactory.getLogger(LeakyCache.class);
    private static final int CHUNK_BYTES = 1024 * 1024;

    /** The bug: entries are only ever added. */
    private final List<Entry> entries = new ArrayList<>();
    private final AtomicLong calls = new AtomicLong();

    /** A cache value shaped like a real serialized session blob plus its decoded rows. */
    private static final class Entry {
        final String key;
        final String sessionId;
        final byte[] payload;
        final List<CachedRow> rows;
        final long storedAtNanos;

        Entry(String key, String sessionId, byte[] payload, List<CachedRow> rows) {
            this.key = key;
            this.sessionId = sessionId;
            this.payload = payload;
            this.rows = rows;
            this.storedAtNanos = System.nanoTime();
        }
    }

    /** What the session blob decodes to: one small object graph per cached record. */
    private static final class CachedRow {
        final String id;
        final String value;
        final byte[] raw;
        final long seenAt;

        CachedRow(String id, String value, byte[] raw) {
            this.id = id;
            this.value = value;
            this.raw = raw;
            this.seenAt = System.currentTimeMillis();
        }
    }

    public synchronized Map<String, Object> put(int mb, int rowsPerChunk) {
        long call = calls.incrementAndGet();
        int chunks = Math.max(1, Math.min(mb, 512));
        long added = 0;
        long addedRows = 0;
        for (int i = 0; i < chunks; i++) {
            byte[] payload = new byte[CHUNK_BYTES];
            // touch every page so nothing is optimised away and the array really is live
            Arrays.fill(payload, (byte) (call + i));
            String key = "session:" + call + ":" + i + ":" + Long.toHexString(System.nanoTime());
            String sessionId = "sid-" + key.hashCode() + "-" + "x".repeat(48);
            List<CachedRow> rows = new ArrayList<>(rowsPerChunk);
            for (int r = 0; r < rowsPerChunk; r++) {
                rows.add(new CachedRow(key + "#" + r, "attr-" + r + '-' + sessionId,
                        new byte[64]));
            }
            entries.add(new Entry(key, sessionId, payload, rows));
            added += payload.length;
            addedRows += rows.size();
        }
        long total = entries.stream().mapToLong(e -> e.payload.length).sum();
        long liveRows = entries.stream().mapToLong(e -> e.rows.size()).sum();
        log.info("session cache grew to {} entries / {} MB payload / {} decoded rows after call {}",
                entries.size(), total / CHUNK_BYTES, liveRows, call);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("call", call);
        body.put("chunksAdded", chunks);
        body.put("bytesAdded", added);
        body.put("rowsAdded", addedRows);
        body.put("entries", entries.size());
        body.put("cachedMb", total / CHUNK_BYTES);
        return body;
    }

    public synchronized Map<String, Object> reset() {
        long before = entries.size();
        entries.clear();
        log.info("session cache reset, {} entries dropped", before);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("droppedEntries", before);
        body.put("entries", entries.size());
        return body;
    }

    public synchronized Map<String, Object> stats() {
        long total = entries.stream().mapToLong(e -> e.payload.length + 96L).sum();
        long rows = entries.stream().mapToLong(e -> e.rows.size()).sum();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("calls", calls.get());
        body.put("entries", entries.size());
        body.put("rows", rows);
        body.put("cachedMb", total / CHUNK_BYTES);
        return body;
    }
}
