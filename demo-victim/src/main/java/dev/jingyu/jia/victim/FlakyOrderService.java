package dev.jingyu.jia.victim;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Incident 5: recurring failures logged through three distinct call paths, so the application
 * log contains a cluster of at least three different exception types, every one of them with a
 * "Caused by:" chain, one stack repeated many times, and real framework noise frames
 * (catalina / DispatcherServlet / reflect) below the application frames.
 *
 * <p>The burst ends by letting one exception escape the controller on purpose. That makes
 * Tomcat emit its own ERROR entry ("Servlet.service() ... threw exception ... with root cause"),
 * which is how a genuine app log actually looks when a handler has no try/catch, and it is also
 * why this endpoint answers HTTP 500.</p>
 */
@Service
public class FlakyOrderService {

    private static final Logger log = LoggerFactory.getLogger(FlakyOrderService.class);

    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicLong chargeFailures = new AtomicLong();
    private final AtomicLong stockFailures = new AtomicLong();
    private final AtomicLong parseFailures = new AtomicLong();
    private final AtomicLong unhandled = new AtomicLong();

    /** Run {@code count} mixed failures through the recurring paths, then let one escape. */
    public Map<String, Object> burst(int count) {
        int n = Math.max(1, Math.min(count, 500));
        for (int i = 1; i <= n; i++) {
            String orderId = "ORD-" + (10_000 + i);
            switch (i % 4) {
                case 0 -> {
                    // the hot path: this exact stack is repeated ~n/4 times
                    chargeCard(orderId, 1999 + i);
                }
                case 1 -> reserveStock("SKU-" + (700 + i));
                default -> {
                    chargeCard(orderId, 499);
                    parseWebhookPayload(orderId);
                }
            }
            nap();
        }

        log.info("error burst finished charges={} stock={} parse={} unhandled-so-far={}",
                chargeFailures.get(), stockFailures.get(), parseFailures.get(), unhandled.get());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requested", n);
        body.put("chargeFailures", chargeFailures.get());
        body.put("stockFailures", stockFailures.get());
        body.put("parseFailures", parseFailures.get());
        body.put("unhandled", unhandled.get() + 1);
        body.put("note", "response is HTTP 500 on purpose: the last failure escapes the handler "
                + "so catalina logs it with framework frames");

        // deliberately uncaught -> 500 + container-level ERROR entry with framework frames
        handleRefund("REF-" + n);
        return body;
    }

    // ---- path 1: payment gateway timeout (the repeated one) --------------------

    private void chargeCard(String orderId, long amountCents) {
        try {
            callGateway(orderId, amountCents);
        } catch (PaymentGatewayException e) {
            chargeFailures.incrementAndGet();
            log.error("charge failed for order {} amount {} cents", orderId, amountCents, e);
        }
    }

    private void callGateway(String orderId, long amountCents) {
        try {
            readFromGateway(orderId);
        } catch (SocketTimeoutException e) {
            throw new PaymentGatewayException("gateway charge-01 read timeout for order " + orderId
                    + " amount " + amountCents, e);
        }
    }

    private void readFromGateway(String orderId) throws SocketTimeoutException {
        throw new SocketTimeoutException("Read timed out");
    }

    // ---- path 2: inventory reservation ---------------------------------------

    private void reserveStock(String sku) {
        try {
            checkStock(sku);
        } catch (TimeoutException e) {
            stockFailures.incrementAndGet();
            log.error("cannot reserve inventory for {}", sku,
                    new IllegalStateException("inventory reservation rejected for " + sku, e));
        }
    }

    private void checkStock(String sku) throws TimeoutException {
        throw new TimeoutException("stock-service lookup for " + sku + " exceeded 250ms");
    }

    // ---- path 3: webhook payload parse (real Jackson frames) ------------------

    private void parseWebhookPayload(String orderId) {
        try {
            readPayload(orderId);
        } catch (Exception e) {
            parseFailures.incrementAndGet();
            log.error("failed to parse inbound webhook payload for {}", orderId,
                    new IllegalArgumentException("webhook payload for " + orderId + " is not valid JSON", e));
        }
    }

    private void readPayload(String orderId) throws Exception {
        String truncated = "{\"orderId\":\"" + orderId + "\",\"events\":[{\"type\":\"capture\"";
        mapper.readTree(truncated);
    }

    // ---- path 4: escapes the handler -----------------------------------------

    private void handleRefund(String refundId) {
        log.warn("refund {} needs manual review", refundId);
        unhandled.incrementAndGet();
        try {
            readFromGateway(refundId);
        } catch (SocketTimeoutException e) {
            throw new IllegalStateException("refund " + refundId + " failed while talking to the gateway", e);
        }
    }

    public Map<String, Object> stats() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("chargeFailures", chargeFailures.get());
        body.put("stockFailures", stockFailures.get());
        body.put("parseFailures", parseFailures.get());
        body.put("unhandled", unhandled.get());
        return body;
    }

    private static void nap() {
        try {
            Thread.sleep(Duration.ofMillis(12).toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
