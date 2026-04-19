package com.splitmanager.app.model;

/**
 * Holds only the parsed, sanitized fields from a payment message.
 * The raw SMS/notification text is intentionally NOT stored here
 * to prevent PII from persisting in memory longer than needed.
 */
public class PaymentEvent {
    public enum PaymentMethod { UPI, CREDIT_CARD, DEBIT_CARD, NET_BANKING, WALLET, UNKNOWN }
    public enum Status { PENDING_SPLIT, SPLIT_DONE, IGNORED, PERSONAL }

    private long id;
    private double amount;
    private String merchant;
    private String currency;
    private PaymentMethod method;
    private String referenceId;
    private long timestamp;
    private Status status;
    private String splitwiseExpenseId;
    private String groupName;
    private String source;

    public PaymentEvent() {
        this.currency  = "INR";
        this.timestamp = System.currentTimeMillis();
        this.status    = Status.PENDING_SPLIT;
    }

    public long getId() { return id; }
    public void setId(long id) { this.id = id; }

    public double getAmount() { return amount; }
    public void setAmount(double amount) { this.amount = amount; }

    public String getMerchant() { return merchant; }
    public void setMerchant(String merchant) { this.merchant = merchant; }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }

    public PaymentMethod getMethod() { return method; }
    public void setMethod(PaymentMethod method) { this.method = method; }

    public String getReferenceId() { return referenceId; }
    public void setReferenceId(String referenceId) { this.referenceId = referenceId; }

    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }

    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }

    public String getSplitwiseExpenseId() { return splitwiseExpenseId; }
    public void setSplitwiseExpenseId(String id) { this.splitwiseExpenseId = id; }

    public String getGroupName() { return groupName; }
    public void setGroupName(String groupName) { this.groupName = groupName; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public String getFormattedAmount() { return String.format("\u20B9%.0f", amount); }

    public String getMethodLabel() {
        if (method == null) return "Payment";
        switch (method) {
            case UPI:         return "UPI";
            case CREDIT_CARD: return "Credit Card";
            case DEBIT_CARD:  return "Debit Card";
            case NET_BANKING: return "Net Banking";
            case WALLET:      return "Wallet";
            default:          return "Payment";
        }
    }
}
