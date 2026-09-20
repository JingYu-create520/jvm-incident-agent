package dev.jingyu.jia.victim;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * demo-victim - a deliberately buggy Spring Boot application.
 *
 * <p>Every {@code /victim/*} endpoint except {@code /victim/health} and
 * {@code /victim/healthy} plants exactly one production-shaped JVM incident so that the
 * four capture artifacts (thread dump, heap histogram, GC log, application log) contain
 * real, machine-detectable evidence for one analyzer rule family.</p>
 *
 * <p>Run it with a small heap and GC logging, e.g.</p>
 * <pre>
 * java -Xms256m -Xmx256m -XX:+UseG1GC -Xlog:gc*:file=gc.log:time,uptime,level,tags \
 *      -Dfile.encoding=UTF-8 -jar demo-victim.jar
 * </pre>
 */
@SpringBootApplication
public class VictimApplication {

    public static void main(String[] args) {
        SpringApplication.run(VictimApplication.class, args);
    }
}
