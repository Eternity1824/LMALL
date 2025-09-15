package com.lmall.orderservice.enums;

/**
 * TCC Transaction States
 *
 * TRYING: Resources reserved, waiting for confirmation
 * CONFIRMED: Transaction completed successfully
 * CANCELLED: Transaction rolled back
 */
public enum TccState {
    TRYING,
    CONFIRMED,
    CANCELLED
}