package com.github.epsilon.events.impl;

import net.minecraft.network.protocol.Packet;

/**
 * Fired by {@link com.github.epsilon.managers.impl.network.BlinkManager} to ask modules
 * what should happen to a packet. Modules call {@link #setAction(Action)} to vote.
 *
 * <p>Action priority: QUEUE &gt; PASS &gt; FLUSH.
 * Once a higher-priority action is set, lower-priority overrides are silently ignored.
 * The default action is FLUSH (packet passes through, queue is flushed).
 *
 * <p>Periodically, BlinkManager fires a null-packet event (getPacket() == null)
 * so modules can check time-based conditions (e.g. isAboveTime).
 */
public class BlinkPacketEvent {

    private final Packet<?> packet;
    private final TransferOrigin origin;
    private Action action = Action.FLUSH;

    public BlinkPacketEvent(Packet<?> packet, TransferOrigin origin) {
        this.packet = packet;
        this.origin = origin;
    }

    /** The packet being considered. Null during periodic time-check ticks. */
    public Packet<?> getPacket() {
        return packet;
    }

    /** Whether this is an inbound or outbound packet. */
    public TransferOrigin getOrigin() {
        return origin;
    }

    /** The current voted action (may change as multiple modules set their preference). */
    public Action getAction() {
        return action;
    }

    /**
     * Set the desired action for this packet. A higher-priority action
     * cannot be overridden by a lower-priority one.
     * Priority order: QUEUE(2) &gt; PASS(1) &gt; FLUSH(0).
     */
    public void setAction(Action action) {
        if (this.action == action || this.action.priority >= action.priority) {
            return;
        }
        this.action = action;
    }

    // -- Inner types --

    public enum TransferOrigin {
        INCOMING,
        OUTGOING
    }

    public enum Action {
        /** Flush the queue and let the current packet through. Lowest priority (default). */
        FLUSH(0),
        /** Let the current packet through without flushing the queue. */
        PASS(1),
        /** Cancel the current packet and add it to the queue. Highest priority. */
        QUEUE(2);

        final int priority;

        Action(int priority) {
            this.priority = priority;
        }

        public int getPriority() {
            return priority;
        }
    }
}
