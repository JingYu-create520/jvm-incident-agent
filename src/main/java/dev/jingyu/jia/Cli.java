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
        version = "jvm-incident-agent " + Engine.VERSION,
        description = "Analyze JVM incident artifacts offline and produce a report with an evidence chain.",
        subcommands = {Analyze.class, RulesCommand.class, Explain.class, Doctor.class, McpCommand.class})
public final class Cli {

    static final PrintStream ERR = System.err;

    private Cli() {
    }

    public static int run(String[] args) {
        return new CommandLine(new Cli())
                .setCaseInsensitiveEnumValuesAllowed(true)
                .setExecutionExceptionHandler((ex, cmd, spec) -> {
                    cmd.getErr().println(cmd.getColorScheme().errorText(String.valueOf(ex)));
                    return 2;
                })
                .execute(args);
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
            description = "Write report files here instead of stdout (a directory, or a file for one format).")
    private Path out;

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

    @Override
    public Integer call() throws IOException {
        if (paths.isEmpty()) {
            // `docker run -v ./incident:/input jia` should need no argument: WORKDIR is /input.
            paths = List.of(Path.of("."));
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
        return result.hasHighRisk() ? 1 : 0;
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
        String name = out.toString().replace('\\', '/');
        boolean trailingSlash = name.endsWith("/");
        Path target = trailingSlash ? Path.of(name.substring(0, name.length() - 1)) : out;
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
        from.gcLog().ifPresent(into::gcLog);
        from.histo().ifPresent(into::histo);
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
