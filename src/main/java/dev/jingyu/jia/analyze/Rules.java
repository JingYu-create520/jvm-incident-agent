package dev.jingyu.jia.analyze;

import dev.jingyu.jia.analyze.rules.exc.CausalChainRule;
import dev.jingyu.jia.analyze.rules.exc.ExceptionBurstRule;
import dev.jingyu.jia.analyze.rules.exc.ExceptionClusterRule;
import dev.jingyu.jia.analyze.rules.gca.FullGcFrequencyRule;
import dev.jingyu.jia.analyze.rules.gca.GcThroughputRule;
import dev.jingyu.jia.analyze.rules.gca.HeapLeakFingerprintRule;
import dev.jingyu.jia.analyze.rules.gca.LongPauseRule;
import dev.jingyu.jia.analyze.rules.gca.PrematurePromotionRule;
import dev.jingyu.jia.analyze.rules.gca.VmConfigSmellRule;
import dev.jingyu.jia.analyze.rules.his.ContainerCountRule;
import dev.jingyu.jia.analyze.rules.his.MatWorthyRule;
import dev.jingyu.jia.analyze.rules.his.TopConsumerRule;
import dev.jingyu.jia.analyze.rules.tda.BlockedStackRule;
import dev.jingyu.jia.analyze.rules.tda.CpuHotThreadRule;
import dev.jingyu.jia.analyze.rules.tda.DeadlockRule;
import dev.jingyu.jia.analyze.rules.tda.LockContentionRule;
import dev.jingyu.jia.analyze.rules.tda.PoolStarvationRule;
import dev.jingyu.jia.analyze.rules.tda.ThreadLeakRule;

import java.util.Comparator;
import java.util.List;

/** The rule registry. Order here is the order reported when ids tie. */
public final class Rules {

    private static final List<Rule> ALL = List.of(
            new DeadlockRule(),
            new LockContentionRule(),
            new ThreadLeakRule(),
            new BlockedStackRule(),
            new PoolStarvationRule(),
            new CpuHotThreadRule(),
            new FullGcFrequencyRule(),
            new LongPauseRule(),
            new HeapLeakFingerprintRule(),
            new PrematurePromotionRule(),
            new VmConfigSmellRule(),
            new GcThroughputRule(),
            new TopConsumerRule(),
            new MatWorthyRule(),
            new ContainerCountRule(),
            new ExceptionClusterRule(),
            new CausalChainRule(),
            new ExceptionBurstRule());

    private Rules() {
    }

    public static List<Rule> all() {
        return ALL;
    }

    public static List<Rule> sorted() {
        return ALL.stream()
                .sorted(Comparator.comparing(Rule::id))
                .toList();
    }

    public static java.util.Optional<Rule> byId(String id) {
        return ALL.stream().filter(r -> r.id().equalsIgnoreCase(id)).findFirst();
    }
}
