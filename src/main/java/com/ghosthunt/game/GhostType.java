package com.ghosthunt.game;

public enum GhostType {
    BASIC("基本鬼"),
    NIGHTMARE("夢魘");

    public final String label;

    GhostType(String label) {
        this.label = label;
    }
}
