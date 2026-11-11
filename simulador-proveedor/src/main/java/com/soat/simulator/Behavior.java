package com.soat.simulator;

public enum Behavior {
    OK("-OK"),
    REJECT("-REJECT"),
    NO_WEBHOOK("-NOWEBHOOK"),
    DUPLICATES("-DUP"),
    SERVER_ERROR("-5XX"),
    SLOW("-SLOW"),
    NEVER("-NEVER");

    private final String suffix;

    Behavior(String suffix) {
        this.suffix = suffix;
    }

    public static Behavior of(String reference) {
        for (Behavior behavior : values()) {
            if (reference.endsWith(behavior.suffix)) {
                return behavior;
            }
        }
        return OK;
    }
}
