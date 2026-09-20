package dev.jingyu.jia.victim;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Incident 1: a genuine JVM-level monitor deadlock plus a contended hot lock.
 *
 * <p>Two request-handling threads take the same two {@code synchronized} monitors in opposite
 * order, which is a cycle in the monitor ownership graph, so {@code jstack} prints
 * "Found one Java-level deadlock". ReentrantLock would NOT be detected by the VM's deadlock
 * detector, so plain intrinsic locks on {@code Object}s are used on purpose.</p>
 *
 * <p>A second, independent plant makes lock <em>contention</em> visible: one thread holds the
 * report monitor and then does slow "I/O" while holding it (parked, so it is not BLOCKED
 * itself) while eight peers sit BLOCKED on the same monitor object. That is the classic
 * "held-a-lock-while-doing-remote-work" bug and it gives the analyzer owner monitors and
 * several identical blocked-at frames to fingerprint.</p>
 *
 * <p>Nothing here is ever cleaned up: the incident is meant to survive until the operator
 * captures a thread dump.</p>
 */
@Component
public class DeadlockPlant {

    /** Number of extra threads parked behind the hot monitor. */
    public static final int CONTENDER_THREADS = 8;

    private static final Logger log = LoggerFactory.getLogger(DeadlockPlant.class);

    /** Shared resource: account balance mutex. */
    private final Object accountMutex = new Object();
    /** Shared resource: double-entry journal mutex. */
    private final Object ledgerMutex = new Object();
    /** Shared resource: the "generate monthly report" monitor that is held too long. */
    private final Object reportMutex = new Object();

    private final AtomicLong plants = new AtomicLong();
    private final CopyOnWriteArrayList<String> notes = new CopyOnWriteArrayList<>();

    /** Create the deadlock and the contention ring once per call; threads are never joined. */
    public Map<String, Object> plant() {
        int round = (int) plants.incrementAndGet();

        start("victim-ledger-poster", () -> {
            try {
                withdrawThenPost(round);
            } catch (Throwable t) {
                log.warn("ledger poster gave up", t);
            }
        });
        start("victim-statement-writer", () -> {
            try {
                statementThenAccount(round);
            } catch (Throwable t) {
                log.warn("statement writer gave up", t);
            }
        });

        start("victim-report-exporter", () -> {
            try {
                exportReportsHoldingTheLock(round);
            } catch (Throwable t) {
                log.warn("report exporter gave up", t);
            }
        });
        for (int i = 1; i <= CONTENDER_THREADS; i++) {
            int slot = i;
            start("victim-report-fetcher-" + slot, () -> {
                try {
                    fetchReport(slot);
                } catch (Throwable t) {
                    log.warn("report fetcher gave up", t);
                }
            });
        }

        log.warn("deadlock planted round={} threads=victim-ledger-poster/victim-statement-writer "
                + "hotLockWaiters={}", round, CONTENDER_THREADS);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("planted", round);
        body.put("deadlockThreads", List.of("victim-ledger-poster", "victim-statement-writer"));
        body.put("heldMonitors", Map.of("victim-ledger-poster", "account-mutex",
                "victim-statement-writer", "ledger-mutex"));
        body.put("contendedMonitor", "report-mutex");
        body.put("contendedWaiters", CONTENDER_THREADS);
        body.put("note", "threads are daemon threads that stay deadlocked/blocked until the JVM exits; "
                + "run scripts/capture.sh now");
        return body;
    }

    /** Lock order account -> ledger. Blocks forever on ledger while keeping account held. */
    private void withdrawThenPost(int round) {
        synchronized (accountMutex) {
            notes.add("victim-ledger-poster holds account-mutex and wants ledger-mutex");
            holdBriefly();
            synchronized (ledgerMutex) {
                postJournalEntries(round);
            }
        }
    }

    /** Lock order ledger -> account. Blocks forever on account while keeping ledger held. */
    private void statementThenAccount(int round) {
        synchronized (ledgerMutex) {
            notes.add("victim-statement-writer holds ledger-mutex and wants account-mutex");
            holdBriefly();
            synchronized (accountMutex) {
                renderStatements(round);
            }
        }
    }

    private void exportReportsHoldingTheLock(int round) {
        synchronized (reportMutex) {
            notes.add("victim-report-exporter holds report-mutex while waiting on remote storage");
            // "remote call" that nobody put a timeout on - the monitor is held while parked.
            for (int i = 0; i < 30; i++) {
                sleep(Duration.ofSeconds(60));
            }
            renderReports(round);
        }
    }

    private void fetchReport(int slot) {
        synchronized (reportMutex) {
            notes.add("victim-report-fetcher-" + slot + " got report-mutex");
            renderReports(slot);
        }
    }

    /** Unreachable while the deadlock holds; kept so the intended call depth is realistic. */
    private void postJournalEntries(int round) {
        for (int i = 0; i < round; i++) {
            log.debug("journal entry {}", i);
        }
    }

    private void renderStatements(int round) {
        StringBuilder sb = new StringBuilder(64);
        for (int i = 0; i < round; i++) {
            sb.append("stmt-").append(i).append(';');
        }
        log.debug("statements {}", sb);
    }

    private void renderReports(int round) {
        log.debug("report {}", round);
    }

    private void holdBriefly() {
        sleep(Duration.ofMillis(1500));
    }

    private static void sleep(Duration d) {
        try {
            Thread.sleep(d.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void start(String name, Runnable body) {
        Thread t = new Thread(body, name);
        t.setDaemon(true);
        t.start();
    }

    public List<String> notes() {
        return List.copyOf(notes);
    }

    public long plants() {
        return plants.get();
    }
}
