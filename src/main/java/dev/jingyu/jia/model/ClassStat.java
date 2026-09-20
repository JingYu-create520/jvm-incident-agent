package dev.jingyu.jia.model;

/** One row of {@code jmap -histo}: class name, instance count, shallow bytes, source line. */
public record ClassStat(String className, long instances, long bytes, int line) {

    /** {@code [B} = byte[], {@code [C} = char[], {@code [I} = int[], and so on. */
    public boolean isPrimitiveArray() {
        return className.length() == 2
                && className.charAt(0) == '['
                && "BCSIJFDZ".indexOf(className.charAt(1)) >= 0;
    }

    /** {@code [Ljava.lang.String;} — an array of objects rather than raw bytes. */
    public boolean isObjectArray() {
        return className.startsWith("[L");
    }

    public boolean isArray() {
        return className.startsWith("[");
    }

    public double avgBytes() {
        return instances == 0 ? 0.0 : (double) bytes / instances;
    }

    /** Element class for arrays, otherwise the class itself. */
    public String baseClass() {
        String c = className;
        while (c.startsWith("[")) {
            c = c.substring(1);
        }
        if (c.startsWith("L") && c.endsWith(";")) {
            c = c.substring(1, c.length() - 1);
        }
        return c;
    }

    public boolean isJdk() {
        String c = baseClass();
        return c.startsWith("java.")
                || c.startsWith("javax.")
                || c.startsWith("jdk.")
                || c.startsWith("sun.")
                || c.startsWith("com.oracle.")
                || c.length() == 1;
    }
}
