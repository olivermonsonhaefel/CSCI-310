package com.example.trojan_rideshare;

import java.math.BigDecimal;
import java.util.Date;
import java.util.Objects;
import java.util.UUID;

/** UML Expense data and its two User relationships: payer owes payee. */
@SuppressWarnings({"unused", "RedundantSuppression"})
public class Expense {
    private final String id;
    private final double amount;
    private final boolean paid;
    private final Date createdAt;
    private final User payer;
    private final User payee;

    public Expense(double amount, User payer, User payee) {
        this(UUID.randomUUID().toString(), amount, false, new Date(), payer, payee);
    }

    public Expense(String id, double amount, boolean paid, Date createdAt, User payer, User payee) {
        if (id == null || id.isEmpty() || id.contains("/")) {
            throw new IllegalArgumentException("Expense id is required and must be a document id");
        }
        if (Double.isNaN(amount) || Double.isInfinite(amount) || amount < 0) {
            throw new IllegalArgumentException("Amount must be a nonnegative US dollar amount");
        }
        try {
            BigDecimal.valueOf(amount).movePointRight(2).longValueExact();
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("Amount must fit in whole cents", e);
        }
        this.payer = Objects.requireNonNull(payer, "Payer is required");
        this.payee = Objects.requireNonNull(payee, "Payee is required");
        if (payer.getId() == null || payer.getId().isEmpty()
                || payee.getId() == null || payee.getId().isEmpty()
                || payer.getId().equals(payee.getId())) {
            throw new IllegalArgumentException("Payer and payee must be different identified users");
        }
        this.id = id;
        this.amount = amount;
        this.paid = paid;
        this.createdAt = new Date(Objects.requireNonNull(createdAt, "Creation date is required").getTime());
    }

    public String getId() { return id; }
    public double getAmount() { return amount; }
    public boolean isPaid() { return paid; }
    public Date getCreatedAt() { return new Date(createdAt.getTime()); }
    public User getPayer() { return payer; }
    public User getPayee() { return payee; }
}
