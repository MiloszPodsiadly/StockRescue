package com.stockrescue.access.application;

public class AccessFailure extends RuntimeException {
    public enum Kind { INVALID_INPUT, CONFLICT, INVITATION_UNAVAILABLE, RATE_LIMITED }
    private final Kind kind;
    public AccessFailure(Kind kind) { this.kind = kind; }
    public Kind kind() { return kind; }
}
