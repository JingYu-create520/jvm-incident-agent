package dev.jingyu.jia;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/**
 * Entry point for the shaded jar and for {@code java -cp … dev.jingyu.jia.Main}.
 *
 * <p>Streams are re-wrapped as UTF-8 before anything is printed. The report contains box
 * characters and arrows; on a Windows console with a GBK default charset those would either
 * turn to {@code ?} or throw, which is a bad first impression for a tool whose whole job is
 * readable evidence.
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        useUtf8Stdio();
        System.exit(Cli.run(args));
    }

    static void useUtf8Stdio() {
        System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8));
    }
}
