package dev.jingyu.jia.model;

import java.util.List;
import java.util.Optional;

/** One throwable block lifted out of an application log. */
public record ExceptionOccurrence(TextSource source,
                                  List<CauseNode> chain,
                                  Long epochMillis,
                                  String timestampRaw,
                                  String logLevel,
                                  int startLine,
                                  int endLine) {

    /** One link of the "Caused by:" chain. */
    public record CauseNode(String className, String message, List<Frame> frames) {
        public String simpleName() {
            int i = className.lastIndexOf('.');
            return i < 0 ? className : className.substring(i + 1);
        }
    }

    public CauseNode outermost() {
        return chain.get(0);
    }

    public CauseNode rootCause() {
        return chain.get(chain.size() - 1);
    }

    public boolean hasCauseChain() {
        return chain.size() > 1;
    }

    public Optional<Frame> rootBusinessFrame() {
        return rootCause().frames().stream().filter(Frame::isBusiness).findFirst();
    }

    /**
     * Fingerprint = exception class + the first few frames of the root cause.
     * Line numbers are excluded on purpose: a rebuild must not split a cluster.
     */
    public String fingerprint(int depth) {
        StringBuilder sb = new StringBuilder(rootCause().className()).append('|');
        List<Frame> frames = rootCause().frames();
        int n = Math.min(frames.size(), Math.max(1, depth));
        for (int i = 0; i < n; i++) {
            sb.append(frames.get(i).id()).append(';');
        }
        return sb.toString();
    }
}
