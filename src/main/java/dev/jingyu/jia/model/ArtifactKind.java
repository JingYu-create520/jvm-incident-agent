package dev.jingyu.jia.model;

/** The four artifact families this tool understands. */
public enum ArtifactKind {
    THREAD_DUMP("thread dump"),
    GC_LOG("GC log"),
    HEAP_HISTO("heap histogram"),
    APP_LOG("application log"),
    UNKNOWN("unknown");

    private final String label;

    ArtifactKind(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
