package dev.jingyu.jia.model;

import java.util.Locale;

/** One {@code at com.Foo.bar(Foo.java:42)} line, plus where it came from. */
public record Frame(String declaringClass,
                    String methodName,
                    String sourceFile,
                    Integer sourceLine,
                    boolean nativeMethod,
                    int line) {

    public static final String JDK_PREFIX_JAVA = "java.";
    public static final String JDK_PREFIX_JAX = "javax.";
    public static final String JDK_PREFIX_JDK = "jdk.";
    public static final String JDK_PREFIX_SUN = "sun.";
    public static final String JDK_PREFIX_COM_ORACLE = "com.oracle.";

    /** Stable identity for fingerprinting: no line numbers, so a rebuild does not split a cluster. */
    public String id() {
        return declaringClass + "#" + methodName;
    }

    public String display() {
        if (nativeMethod) {
            return "at " + declaringClass + "." + methodName + " (Native Method)";
        }
        if (sourceFile == null) {
            return "at " + declaringClass + "." + methodName + " (Source File Unknown)";
        }
        return "at " + declaringClass + "." + methodName
                + (sourceLine == null ? "(" + sourceFile + ")" : "(" + sourceFile + ":" + sourceLine + ")");
    }

    public boolean isJdk() {
        String c = declaringClass.toLowerCase(Locale.ROOT);
        return c.startsWith(JDK_PREFIX_JAVA)
                || c.startsWith(JDK_PREFIX_JAX)
                || c.startsWith(JDK_PREFIX_JDK)
                || c.startsWith(JDK_PREFIX_SUN)
                || c.startsWith(JDK_PREFIX_COM_ORACLE)
                || c.startsWith("java/")
                || c.startsWith("jdk/")
                || c.startsWith("javax/");
    }

    /** Third-party noise a root cause should not be attributed to. */
    public boolean isFramework() {
        String c = declaringClass.toLowerCase(Locale.ROOT);
        return c.startsWith("org.springframework.")
                || c.startsWith("org.apache.")
                || c.startsWith("org.eclipse.")
                || c.startsWith("io.netty.")
                || c.startsWith("io.undertow.")
                || c.startsWith("org.hibernate.")
                || c.startsWith("com.zaxxer.")
                || c.startsWith("org.junit.")
                || c.startsWith("io.grpc.")
                || c.startsWith("reactor.")
                || c.startsWith("org.apache.catalina.")
                || c.startsWith("org.apache.tomcat.");
    }

    public boolean isBusiness() {
        return !isJdk() && !isFramework();
    }
}
