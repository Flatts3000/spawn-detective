package com.flatts.spawndoctor.audit;

/**
 * The overlay's three-state answer for one block.
 *
 * <p>The distinction that matters is <b>permanent versus situational</b>. A player
 * lighting up a base wants to know which blocks are still a spawn risk once they
 * walk away; a block that is only safe because they are standing next to it is not
 * safe. So {@link #BLOCKED_NOW} is deliberately its own colour rather than being
 * folded into "safe" - collapsing the two is the single most common way a light
 * overlay lies to its user.
 */
public enum SpawnGrade {
    /** Nothing to draw - not a candidate position at all. */
    NONE(0, 0x00000000),
    /** A mob can spawn here right now. */
    SPAWNABLE(1, 0x80FF3B30),
    /**
     * Physically valid, but currently prevented by something that moves or changes:
     * player proximity, a full mob cap, weather, or a borderline light roll.
     */
    BLOCKED_NOW(2, 0x80FFCC00),
    /** Permanently safe: the shape of the world here rejects every mob. */
    SAFE(3, 0x4034C759);

    private final byte id;
    private final int argb;

    SpawnGrade(int id, int argb) {
        this.id = (byte) id;
        this.argb = argb;
    }

    public byte id() {
        return this.id;
    }

    /** Overlay fill colour, ARGB. Red reads as danger, which is the intent. */
    public int argb() {
        return this.argb;
    }

    private static final SpawnGrade[] BY_ID = values();

    public static SpawnGrade byId(byte id) {
        return id >= 0 && id < BY_ID.length ? BY_ID[id] : NONE;
    }
}
