package com.lmall.orderservice.exception;

/**
 * Exception for TCC transaction failures
 *
 * Covers:
 * - Timeout during try phase
 * - Failed resource reservation
 * - Compensation failures
 */
public class TccTransactionException extends RuntimeException {

    private final String transactionId;
    private final String phase;  // TRY, CONFIRM, or CANCEL

    public TccTransactionException(String transactionId, String phase, String message) {
        super(String.format("TCC transaction failed [%s] in %s phase: %s",
                transactionId, phase, message));
        this.transactionId = transactionId;
        this.phase = phase;
    }

    public TccTransactionException(String transactionId, String phase, String message, Throwable cause) {
        super(String.format("TCC transaction failed [%s] in %s phase: %s",
                transactionId, phase, message), cause);
        this.transactionId = transactionId;
        this.phase = phase;
    }

    public String getTransactionId() {
        return transactionId;
    }

    public String getPhase() {
        return phase;
    }
}