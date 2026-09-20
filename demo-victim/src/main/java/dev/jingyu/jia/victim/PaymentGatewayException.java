package dev.jingyu.jia.victim;

/** Custom checked-ish failure used by the payment path so the log has a named app exception. */
public class PaymentGatewayException extends RuntimeException {

    public PaymentGatewayException(String message) {
        super(message);
    }

    public PaymentGatewayException(String message, Throwable cause) {
        super(message, cause);
    }
}
