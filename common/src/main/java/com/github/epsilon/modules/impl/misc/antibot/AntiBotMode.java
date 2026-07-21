package com.github.epsilon.modules.impl.misc.antibot;

import net.minecraft.world.entity.player.Player;

/**
 * Base class for all anti-bot detection modes.
 * Each mode implements a different strategy for detecting fake players / bots.
 */
public abstract class AntiBotMode implements AntiBotPredicate {

    private final String name;

    protected AntiBotMode(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    @Override
    public String toString() {
        return name;
    }

    /**
     * Called when the module is disabled or the world changes.
     * Override to clear internal state.
     */
    public void reset() {
    }

    /**
     * Process incoming packets. Override to handle packet-based detection.
     */
    public void onPacketReceive(net.minecraft.network.protocol.Packet<?> packet) {
    }

    /**
     * Called each client tick. Override for tick-based detection.
     */
    public void onTick() {
    }

    /**
     * Called when the local player attacks an entity. Override for attack-based detection.
     */
    public void onAttack(int entityId) {
    }

    @Override
    public abstract boolean isBot(Player entity);
}
