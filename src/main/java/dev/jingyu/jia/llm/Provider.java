package dev.jingyu.jia.llm;

import dev.jingyu.jia.analyze.AnalysisResult;

/**
 * The only place a language model is allowed to speak.
 *
 * <p>A provider receives the finished {@link AnalysisResult} and returns prose. It cannot
 * add, remove, reweight or reword a finding — {@code findings} is already built by the time
 * this is called, and a test asserts the two paths (with and without {@code --llm}) produce
 * byte-identical finding lists.
 */
public interface Provider {

    String name();

    /** Prose for the Verdict section: what happened, in order, and what to do first. */
    String narrate(AnalysisResult result);

    /** True when this provider talks to a network endpoint. */
    default boolean remote() {
        return false;
    }
}
