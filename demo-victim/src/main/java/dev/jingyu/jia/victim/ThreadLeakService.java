package dev.jingyu.jia.victim;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Incident 4: a thread leak. Every call creates {@code count} raw threads with the repeating
 * name {@code victim-worker-<n>} (n only ever increases) and stores them in a list that is never
 * cleaned. They are never interrupted and never joined; each one just parks for a minute and
 * loops, so they stay alive in the thread dump as TIMED_WAITING (parking) with the same
 * top-of-stack frames - which is exactly the fingerprint a thread-leak rule looks for.
 *
 * <p>This models "a ScheduledExecutor / Thread is created per request and never shutdown()".
 * The threads do not block on monitors, so only the thread-leak rule should fire here.</p>
 */
@Component
public class ThreadLeakService {

    /** The recognisable prefix the analyzer corpus TRUTH files refer to. */
    public static final String PREFIX = "victim-worker-";

    private static final Logger log = LoggerFactory.getLogger(ThreadLeakService.class);

    private final AtomicInteger serial = new AtomicInteger();
    private final CopyOnWriteArrayList<Thread> leaked = new CopyOnWriteArrayList<>();

    public Map<String, Object> leak(int count) {
        int n = Math.max(1, Math.min(count, 400));
        for (int i = 0; i < n; i++) {
            int id = serial.incrementAndGet();
            Thread t = new Thread(() -> {
                while (!Thread.currentThread().isInterrupted()) {
                    try {
                        Thread.sleep(Duration.ofMinutes(1).toMillis());
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }, PREFIX + id);
            t.setDaemon(true);
            t.setUncaughtExceptionHandler((th, ex) -> log.warn("leaked worker died", ex));
            leaked.add(t);
            t.start();
        }
        long alive = leaked.stream().filter(Thread::isAlive).count();
        log.warn("spawned {} more victim-worker threads, {} leaked so far", n, alive);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("spawned", n);
        body.put("leakedTotal", alive);
        body.put("namePrefix", PREFIX);
        body.put("note", "no shutdown anywhere; threads survive until JVM exit");
        return body;
    }

    public Map<String, Object> stats() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("leakedTotal", leaked.size());
        body.put("alive", leaked.stream().filter(Thread::isAlive).count());
        body.put("liveThreadsNow", Thread.getAllStackTraces().size());
        return body;
    }
}
