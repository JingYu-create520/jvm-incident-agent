package dev.jingyu.jia;

import dev.jingyu.jia.analyze.AnalysisResult;
import dev.jingyu.jia.analyze.Engine;
import dev.jingyu.jia.analyze.Rule;
import dev.jingyu.jia.analyze.Rules;
import dev.jingyu.jia.llm.MockProvider;
import dev.jingyu.jia.llm.OpenAICompatProvider;
import dev.jingyu.jia.llm.Provider;
import dev.jingyu.jia.mcp.McpServer;
import dev.jingyu.jia.model.Config;
import dev.jingyu.jia.model.Severity;
import dev.jingyu.jia.model.Snapshot;
import dev.jingyu.jia.parse.ParseReport;
import dev.jingyu.jia.parse.SnapshotLoader;
import dev.jingyu.jia.report.JsonReport;
import dev.jingyu.jia.report.MarkdownReport;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;

/**
 * Command line surface.
 *
 * <p>Exit codes are part of the product: {@code 0} nothing high-severity, {@code 1} at least one
 * HIGH/CRITICAL finding (so CI can gate on it), {@code 2} the input could not be understood.
 */
@Command(
        name = "jia",
        mixinStandardHelpOptions = true,
        version = "jvm-incident-agent",
        description = "Analyze JVM incident artifacts offline and produce a report with an evidence chain.",
        subcommands = {Analyze.class, RulesCommand.class, Explain.class, Doctor.class, McpCommand.class})
public final class Cli {

    static final PrintStream ERR = System.err;

    private Cli() {
    }

    /**
     * The wired-up command line. Public because a reader of `jia analyze --help` and a reader of the
     * rule docs must see the same set of flags, and that is only checkable if both are reachable
     * from outside this class — see {@code OutputTest.knobsAreReachable}.
     */
    public static CommandLine command() {
        CommandLine cli = new CommandLine(new Cli());
        // The version is read from a Maven-filtered resource so it cannot drift from the pom, which
        // makes it not a compile-time constant — and an annotation's `version` has to be one. So the
        // annotation carries the name and this line the number. Picocli exposes it as a spec setter.
        cli.getCommandSpec().version("jvm-incident-agent " + Engine.VERSION);
        return cli.setCaseInsensitiveEnumValuesAllowed(true)
                .setExecutionExceptionHandler((ex, cmd, spec) -> {
                    cmd.getErr().println(cmd.getColorScheme().errorText(String.valueOf(ex)));
                    return 2;
                });
    }

    public static int run(String[] args) {
        return command().execute(args);
    }
}

@Command(name = "analyze", description = "Analyze a snapshot directory, or one or more artifact files.")
final class Analyze implements Callable<Integer> {

    @Parameters(arity = "0..*", paramLabel = "PATH",
            description = "Snapshot directory and/or artifact files. With no PATH, the current "
                    + "directory is analyzed — which is what the container form relies on.")
    private List<Path> paths = new ArrayList<>();

    @Option(names = {"-f", "--format"}, description = "md, json or both (default md to stdout).")
    private String format = "md";

    @Option(names = {"-o", "--out"},
            description = "Write report files here instead of stdout: a directory (existing, or named with a "
                    + "trailing separator, which is then created) receives report.md and/or report.json; "
                    + "a file name receives exactly one format.")
    private String out;

    @Option(names = "--llm", description = "Ask an OpenAI-compatible endpoint for the narrative section. "
            + "Findings are identical either way; only the prose differs.")
    private boolean llm;

    @Option(names = "--no-narrative", description = "Skip the narrative section entirely (pure rule output).")
    private boolean noNarrative;

    @Option(names = "--sla-ms", description = "Pause SLA in ms (default 200).")
    private Long slaMs;

    @Option(names = "--full-gc-per-min", description = "Full GCs per minute that counts as a storm (default 1).")
    private Double fullGcPerMin;

    @Option(names = "--thread-leak-threshold",
            description = "Threads in one name family before it counts as a leak (default 40).")
    private Integer threadLeak;

    @Option(names = "--lock-waiters",
            description = "Threads queued on one monitor before it counts as contention (default 3).")
    private Integer lockWaiters;

    @Option(names = "--stack-cluster",
            description = "Threads sharing one blocked stack before it is a hotspot (default 5).")
    private Integer stackCluster;

    @Option(names = "--exception-threshold",
            description = "Repeats of one exception stack before it is reported (default 5).")
    private Integer exceptionThreshold;

    @Option(names = "--histo-share",
            description = "Share of heap bytes one class must hold to be flagged (default 0.35).")
    private Double histoShare;

    @Option(names = "--throughput", description = "Minimum acceptable GC throughput (default 0.97).")
    private Double throughput;

    @Option(names = "--histo-top-min-leader-mb", description = "absolute MB a single class must hold before HIS001 calls the heap dominated (default 16)")
    private Integer histoTopMinLeaderMb;

    @Option(names = "--histo-top-min-bag-mb", description = "absolute MB a byte[]/char[]/Integer[] bag must hold before HIS001 reports it (default 32)")
    private Integer histoTopMinBagMb;

    @Option(names = "--mat-min-mb", description = "absolute MB an application class must hold before HIS002 names it (default 8)")
    private Integer matMinMb;

    @Option(names = "--mat-share", description = "share of counted bytes HIS002 requires (default 0.10 — the heap-leak corpus sits at 0.0932, which is why that folder has no HIS002)")
    private Double matShare;

    @Option(names = "--container-min-instances", description = "instance floor for HIS003; it also scales with the heap as totalInstances/20, the larger wins (default 50000)")
    private Long containerMinInstances;

    @Option(names = "--cpu-min-cores", description = "cores a thread must gain between two dumps before TDA006 calls it hot (default 0.5)")
    private Double cpuMinCores;

    @Option(names = "--cpu-min-elapsed-sec", description = "minimum uptime before a single dump may be read as CPU load (default 1.0)")
    private Double cpuMinElapsedSec;

    @Option(names = "--pool-max-size", description = "above this member count a name family is a thread leak, not a starved pool (default 100)")
    private Integer poolMaxSize;

    @Option(names = "--burst-min", description = "occurrences inside one bucket before EXC003 calls it a burst (default 10)")
    private Integer burstMin;

    @Option(names = "--burst-bucket-sec", description = "width of the EXC003 time bucket in seconds (default 60)")
    private Integer burstBucketSec;

    @Option(names = "--leak-pinned-share", description = "how full the heap must be for a dip-free plateau to be GCA003 without a climb (default 0.85)")
    private Double leakPinnedShare;

    @Option(names = "--leak-stagnant-share", description = "a major collection reclaiming less than this counts as stagnant for GCA003 (default 0.02)")
    private Double leakStagnantShare;

    @Option(names = "--leak-stalled-floor", description = "how full the heap must be for a dip-free stagnant floor to be a finding at all (default 0.40)")
    private Double leakStalledFloor;

    @Option(names = "--stall-min", description = "allocation stalls before GCA007 calls it a pattern rather than a hiccup (default 3)")
    private Integer stallMin;

    @Option(names = "--gc-settle-sec", description = "uptime below which a Metadata-GC-Threshold Full GC is startup noise and not storm evidence (default 60)")
    private Double gcSettleSec;

    @Option(names = "--gc-window", description = "burst window in seconds that GCA001 measures Full GC rate over (default 300)")
    private Long gcWindow;

    @Option(names = "--heap-leak-rise", description = "rise in the post-GC live set, as a fraction of the first sample, that GCA003 calls a leak (default 0.10)")
    private Double heapLeakRise;

    @Option(names = "--heap-leak-min-collections", description = "major collections GCA003 needs before it will describe a floor at all (default 3)")
    private Integer heapLeakMinCollections;

    @Option(names = "--thread-leak-growth", description = "how much a named thread family must grow between two dumps for TDA003 (default 1.25x)")
    private Double threadLeakGrowth;

    @Option(names = "--pool-starve-min-size", description = "smallest name family TDA005 will consider a pool (default 4)")
    private Integer poolStarveMinSize;

    @Option(names = "--pool-starve-same-frame", description = "share of a pool that must be busy and in one frame for TDA005 (default 0.8)")
    private Double poolStarveSameFrame;

    @Option(names = "--fail-on", paramLabel = "SEVERITY",
            description = "Lowest severity that makes the process exit 1: never, info, low, medium, "
                    + "high (default, the historical behaviour) or critical. Use critical in a CI gate "
                    + "so an advisory finding cannot break a build.")
    private String failOn = "high";

    @Override
    public Integer call() throws IOException {
        Severity gate;
        if (failOn.equalsIgnoreCase("never")) {
            gate = null;
        } else {
            try {
                gate = Severity.valueOf(failOn.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException badChoice) {
                Cli.ERR.println("--fail-on expects never|info|low|medium|high|critical, was: " + failOn);
                return 2;
            }
        }
        if (paths.isEmpty()) {
            // `docker run -v ./incident:/input jia` should need no argument: WORKDIR is /input.
            paths = List.of(Path.of("."));
        }
        String badOutput = outputProblem();
        if (badOutput != null) {
            Cli.ERR.println(badOutput);
            return 2;
        }
        Config cfg = configOverrides();
        Snapshot.Builder merged = Snapshot.builder();
        List<String> notes = new ArrayList<>();
        for (Path p : paths) {
            if (!Files.exists(p)) {
                Cli.ERR.println("not found: " + p);
                return 2;
            }
            ParseReport<Snapshot> r = SnapshotLoader.load(p);
            notes.addAll(r.notes());
            mergeInto(merged, r.value());
        }
        Snapshot snapshot = merged.build();
        if (snapshot.threadDumps().isEmpty() && snapshot.gcLog().isEmpty()
                && snapshot.histo().isEmpty() && snapshot.exceptions().isEmpty()) {
            Cli.ERR.println("Nothing recognised in " + paths + ".");
            snapshot.unparsed().forEach(u -> Cli.ERR.println("  " + u.file() + ": " + u.reason()));
            notes.forEach(n -> Cli.ERR.println("  " + n));
            return 2;
        }

        AnalysisResult result = new Engine().analyze(snapshot, cfg);
        Provider provider = chooseProvider();
        String narrative = noNarrative ? "" : narrate(provider, result);

        String md = new MarkdownReport().render(result, narrative);
        String js = new JsonReport().render(result, noNarrative ? null : narrative);
        emit(md, js);
        report(result, provider);
        return gate != null && result.countAtLeast(gate) > 0 ? 1 : 0;
    }

    /** A remote provider must never be able to fail an analysis run. */
    private String narrate(Provider provider, AnalysisResult result) {
        if (!provider.remote()) {
            return provider.narrate(result);
        }
        try {
            return provider.narrate(result);
        } catch (RuntimeException e) {
            Cli.ERR.println("--llm failed (" + e.getMessage() + ") — using the offline narrative instead.");
            return new MockProvider().narrate(result);
        }
    }

    private Config configOverrides() {
        Config.Builder b = Config.builder();
        if (slaMs != null) {
            b.slaPauseMs(slaMs);
        }
        if (fullGcPerMin != null) {
            b.fullGcPerMinute(fullGcPerMin);
        }
        if (threadLeak != null) {
            b.threadLeakThreshold(threadLeak);
        }
        if (lockWaiters != null) {
            b.lockWaiterThreshold(lockWaiters);
        }
        if (stackCluster != null) {
            b.stackClusterThreshold(stackCluster);
        }
        if (exceptionThreshold != null) {
            b.exceptionClusterThreshold(exceptionThreshold);
        }
        if (histoShare != null) {
            b.histoDominanceRatio(histoShare);
        }
        if (throughput != null) {
            b.throughputFloor(throughput);
        }
        if (histoTopMinLeaderMb != null) {
            b.histoTopMinLeaderBytes(histoTopMinLeaderMb * 1024L * 1024L);
        }
        if (histoTopMinBagMb != null) {
            b.histoTopMinBagBytes(histoTopMinBagMb * 1024L * 1024L);
        }
        if (matMinMb != null) {
            b.histoMatMinBytes(matMinMb * 1024L * 1024L);
        }
        if (matShare != null) {
            b.histoMatMinShare(matShare);
        }
        if (containerMinInstances != null) {
            b.containerMinInstances(containerMinInstances);
        }
        if (cpuMinCores != null) {
            b.cpuHotMinCores(cpuMinCores);
        }
        if (cpuMinElapsedSec != null) {
            b.cpuHotMinElapsedSec(cpuMinElapsedSec);
        }
        if (poolMaxSize != null) {
            b.poolMaxPlausibleSize(poolMaxSize);
        }
        if (burstMin != null) {
            b.burstMinPerBucket(burstMin);
        }
        if (burstBucketSec != null) {
            b.burstBucketMillis(burstBucketSec * 1000L);
        }
        if (leakPinnedShare != null) {
            b.leakSaturatedShare(leakPinnedShare);
        }
        if (leakStagnantShare != null) {
            b.leakStagnantShare(leakStagnantShare);
        }
        if (leakStalledFloor != null) {
            b.leakStalledFloorShare(leakStalledFloor);
        }
        if (stallMin != null) {
            b.stallMinCount(stallMin);
        }
        if (gcSettleSec != null) {
            b.gcSettleSec(gcSettleSec);
        }
        if (gcWindow != null) {
            b.fullGcWindowSec(gcWindow);
        }
        if (heapLeakRise != null) {
            b.heapLeakRiseRatio(heapLeakRise);
        }
        if (heapLeakMinCollections != null) {
            b.heapLeakMinFullGc(heapLeakMinCollections);
        }
        if (threadLeakGrowth != null) {
            b.threadLeakGrowthRatio(threadLeakGrowth);
        }
        if (poolStarveMinSize != null) {
            b.poolStarveMinSize(poolStarveMinSize);
        }
        if (poolStarveSameFrame != null) {
            b.poolStarveSameFrameRatio(poolStarveSameFrame);
        }
        return b.build();
    }

    private Provider chooseProvider() {
        if (!llm) {
            return new MockProvider();
        }
        try {
            return new OpenAICompatProvider();
        } catch (OpenAICompatProvider.LlmConfigException e) {
            Cli.ERR.println("--llm: " + e.getMessage() + " — falling back to the offline narrative.");
            return new MockProvider();
        }
    }

    /**
     * Reject an output request that cannot be honoured, before spending a run on it. Two ways to lose
     * half a report quietly: {@code -f both} into a file name (only one of the two would ever be
     * written), and a {@code -f} value that is not a format at all (nothing matched, so nothing was
     * written, and the exit code said the analysis was clean).
     */
    private String outputProblem() {
        boolean md = format.equalsIgnoreCase("md") || format.equalsIgnoreCase("both");
        boolean json = format.equalsIgnoreCase("json") || format.equalsIgnoreCase("both");
        if (!md && !json) {
            return "-f expects md, json or both, was: " + format;
        }
        if (out == null || !(md && json)) {
            return null;
        }
        String name = out.replace('\\', '/');
        boolean directory = name.endsWith("/") || Files.isDirectory(Path.of(out));
        return directory ? null
                : "-f both needs a directory: name --out with a trailing separator and it will be created, "
                        + "or ask for one format to write to \"" + out + "\"";
    }

    private void emit(String md, String js) throws IOException {
        boolean wantMd = format.equalsIgnoreCase("md") || format.equalsIgnoreCase("both");
        boolean wantJson = format.equalsIgnoreCase("json") || format.equalsIgnoreCase("both");
        PrintStream stdout = System.out;
        if (out == null) {
            if (wantMd && wantJson) {
                stdout.print(md);
                stdout.writeBytes(js.getBytes(StandardCharsets.UTF_8));
            } else if (wantJson) {
                stdout.writeBytes(js.getBytes(StandardCharsets.UTF_8));
            } else {
                stdout.print(md);
            }
            stdout.flush();
            return;
        }
        // Kept as the typed string on purpose: java.nio.Path drops a trailing separator on Windows,
        // and "-o results/" is the README's own shape. Without that separator the fresh-directory
        // case used to write the Markdown into a *file* called "results" and lose the JSON.
        String name = out.replace('\\', '/');
        boolean trailingSlash = name.endsWith("/");
        Path target = trailingSlash ? Path.of(name.substring(0, name.length() - 1)) : Path.of(name);
        if (trailingSlash || Files.isDirectory(target)) {
            Files.createDirectories(target);
            write(wantMd, target.resolve("report.md"), md);
            write(wantJson, target.resolve("report.json"), js);
            return;
        }
        Path parent = target.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        write(true, target, wantJson && !wantMd ? js : md);
    }

    private static void write(boolean enabled, Path target, String body) throws IOException {
        if (enabled) {
            Files.writeString(target, body, StandardCharsets.UTF_8);
            Cli.ERR.println("wrote " + target.toAbsolutePath());
        }
    }

    private void report(AnalysisResult r, Provider provider) {
        StringBuilder sb = new StringBuilder();
        List<String> bits = new ArrayList<>();
        r.census().forEach((sev, n) -> {
            if (n > 0) {
                bits.add(n + " " + sev);
            }
        });
        sb.append("findings: ").append(bits.isEmpty() ? "none" : String.join(", ", bits)).append('\n');
        if (!r.hypotheses().isEmpty()) {
            sb.append("top hypothesis: ").append(r.hypotheses().get(0).claim()).append('\n');
        }
        sb.append("narrative: ").append(provider.name())
                .append(provider.remote() ? " (findings were computed locally before it was called)" : " (offline)")
                .append('\n');
        sb.append(String.format(Locale.ROOT, "%d findings, %d hypotheses, %d ms, exit %d%n",
                r.findings().size(), r.hypotheses().size(), r.elapsedMillis(),
                r.hasHighRisk() ? 1 : 0));
        Cli.ERR.print(sb);
    }

    private static void mergeInto(Snapshot.Builder into, Snapshot from) {
        from.threadDumps().forEach(into::addDump);
        // What a folder already disclosed stays disclosed when its snapshot is merged into the run.
        from.skipped().forEach(into::skip);
        // A snapshot carries one GC log and one histogram, so the second of either has to be dropped.
        // Dropping it silently is how half an incident window presents as the whole of one, and
        // overwriting is worse still: the file the reader was never told about would win. Keep the
        // first — the order the paths are passed in is the order of preference — and name what was lost.
        if (from.gcLog().isPresent()) {
            if (into.hasGcLog()) {
                into.skip("GC log", into.gcLogValue().get().source().name(), from.gcLog().get().source().name(),
                        Snapshot.Skipped.GC_LOG_ADVICE);
            } else {
                into.gcLog(from.gcLog().get());
            }
        }
        if (from.histo().isPresent()) {
            if (into.hasHisto()) {
                into.skip("heap histogram", into.histoValue().get().source().name(),
                        from.histo().get().source().name(), Snapshot.Skipped.HISTO_ADVICE);
            } else {
                into.histo(from.histo().get());
            }
        }
        from.exceptions().forEach(into::addException);
        from.unparsed().forEach(into::addUnparsed);
        from.filesSeen().forEach(into::fileSeen);
        from.root().ifPresent(into::root);
    }
}

@Command(name = "rules", description = "List every rule, or dump the catalogue as JSON.")
final class RulesCommand implements Callable<Integer> {

    @Option(names = {"-f", "--format"}, description = "text or json (default text).")
    private String format = "text";

    @Override
    public Integer call() {
        if (format.equalsIgnoreCase("json")) {
            System.out.print(new JsonReport().rulesJson());
            return 0;
        }
        System.out.printf("%-8s %-17s %s%n", "ID", "ARTIFACT", "TITLE");
        for (Rule r : Rules.sorted()) {
            System.out.printf("%-8s %-17s %s%n", r.id(), r.artifact().label(), r.title());
        }
        System.out.println();
        System.out.println("Run `jia explain <ID>` for what a rule detects, how, and where it can be wrong.");
        return 0;
    }
}

@Command(name = "explain", description = "Print the documentation for one rule id.")
final class Explain implements Callable<Integer> {

    @Parameters(paramLabel = "RULE_ID", description = "e.g. TDA001, GCA003, HIS001, EXC002")
    private String ruleId;

    @Override
    public Integer call() {
        return Rules.byId(ruleId)
                .map(r -> {
                    System.out.println(r.doc());
                    return 0;
                })
                .orElseGet(() -> {
                    Cli.ERR.println("No rule '" + ruleId + "'. Try `jia rules`.");
                    return 2;
                });
    }
}

@Command(name = "doctor", description = "Report what this JVM and environment can do.")
final class Doctor implements Callable<Integer> {

    @Override
    public Integer call() {
        System.out.println("jvm-incident-agent " + Engine.VERSION);
        System.out.println("java            " + System.getProperty("java.version")
                + " (" + System.getProperty("java.vendor") + ")");
        System.out.println("home            " + System.getProperty("java.home"));
        System.out.println("default charset " + java.nio.charset.Charset.defaultCharset());
        System.out.println("rules           " + Rules.all().size());
        Path bin = Path.of(System.getProperty("java.home"), "bin");
        for (String tool : List.of("jstack", "jmap", "jps", "jcmd")) {
            Path exe = bin.resolve(tool + (isWindows() ? ".exe" : ""));
            System.out.printf("%-15s %s%n", tool, Files.isExecutable(exe) ? exe
                    : "NOT FOUND — capture with your own JDK; analyzing existing files still works");
        }
        String base = System.getenv(OpenAICompatProvider.ENV_BASE_URL);
        System.out.println("llm             " + (base == null || base.isBlank()
                ? "not configured (--llm falls back to the offline narrative)" : base));
        System.out.println("privacy         nothing leaves this process unless you pass --llm, and then only "
                + "finding summaries — never a raw dump, log or histogram");
        System.out.println("severities      " + java.util.Arrays.toString(Severity.values()));
        return 0;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
    }
}

@Command(name = "mcp", description = "Run as an MCP server on stdio (JSON-RPC 2.0, one message per line).")
final class McpCommand implements Callable<Integer> {

    @Override
    public Integer call() throws IOException {
        Cli.ERR.println("jvm-incident-agent " + Engine.VERSION + " MCP server on stdio "
                + "(initialize, tools/list, tools/call).");
        new McpServer().serve();
        return 0;
    }
}
