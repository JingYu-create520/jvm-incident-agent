package dev.jingyu.jia.victim;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Ordinary background activity so the JVM looks like a live service in a thread dump instead
 * of an empty one. These threads are created once at startup, in every mode, including the
 * healthy one, and they never block on a monitor and never multiply.
 *
 * <ul>
 *   <li>{@code victim-heartbeat-1..2} - sleep, emit one INFO line, repeat.</li>
 *   <li>{@code victim-request-worker-1..4} - parked on a work queue that is fed one item per
 *       incident call (WAITING (parking) with a queue frame, like any real pool thread).</li>
 *   <li>{@code victim-idle-scanner} - short CPU burst every 3 seconds, so a dump contains at
 *       least one legitimately RUNNABLE application thread.</li>
 * </ul>
 */
@Component
public class ResidentWorkers {

    private static final Logger log = LoggerFactory.getLogger(ResidentWorkers.class);

    private final LinkedBlockingQueue<String> queue = new LinkedBlockingQueue<>();
    private final AtomicLong handled = new AtomicLong();
    private final CopyOnWriteArrayList<Thread> threads = new CopyOnWriteArrayList<>();
    private volatile boolean stopping;

    @PostConstruct
    public void start() {
        for (int i = 1; i <= 2; i++) {
            spawn("victim-heartbeat-" + i, () -> {
                while (!stopping) {
                    parkFor(Duration.ofSeconds(10));
                    if (!stopping) {
                        log.info("heartbeat from {} heapUsedMb={} uptimeMs={}", Thread.currentThread().getName(),
                                heapUsedMb(), ManagementFactory.getRuntimeMXBean().getUptime());
                    }
                }
            });
        }
        for (int i = 1; i <= 4; i++) {
            spawn("victim-request-worker-" + i, () -> {
                while (!stopping) {
                    try {
                        String job = queue.poll(5, TimeUnit.SECONDS);
                        if (job != null) {
                            handled.incrementAndGet();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            });
        }
        spawn("victim-idle-scanner", () -> {
            long acc = 0;
            while (!stopping) {
                long until = System.nanoTime() + 40_000_000L;
                while (System.nanoTime() < until) {
                    acc = 31 * acc + Integer.hashCode(queue.size());
                }
                parkFor(Duration.ofSeconds(3));
            }
            if (acc == Long.MIN_VALUE) {
                log.trace("{}", acc);
            }
        });
        log.info("resident workers started: {} threads", threads.size());
    }

    /** Hand a token of work to the pool so the request workers look used, not orphaned. */
    public void submit(String job) {
        queue.offer(job);
    }

    public long handled() {
        return handled.get();
    }

    public int threadCount() {
        return threads.size();
    }

    @PreDestroy
    public void stop() {
        stopping = true;
        for (Thread t : threads) {
            t.interrupt();
        }
    }

    private void spawn(String name, Runnable body) {
        Thread t = new Thread(body, name);
        t.setDaemon(true);
        threads.add(t);
        t.start();
    }

    private static void parkFor(Duration d) {
        try {
            Thread.sleep(d.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static long heapUsedMb() {
        var mem = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        return Math.max(0, mem.getUsed() / (1024 * 1024));
    }
}
