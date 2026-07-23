package com.github.epsilon.managers.impl.network;

import com.github.epsilon.events.bus.Cancellable;
import com.github.epsilon.events.bus.EventBus;
import com.github.epsilon.events.bus.EventHandler;
import com.github.epsilon.events.bus.EventPriority;
import com.github.epsilon.events.impl.BlinkPacketEvent;
import com.github.epsilon.events.impl.BlinkPacketEvent.Action;
import com.github.epsilon.events.impl.BlinkPacketEvent.TransferOrigin;
import com.github.epsilon.events.impl.GameLeftEvent;
import com.github.epsilon.events.impl.LevelUpdateEvent;
import com.github.epsilon.events.impl.PacketEvent;
import com.github.epsilon.events.impl.PlayerTickEvent;
import com.github.epsilon.utils.network.PacketUtils;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket;
import net.minecraft.network.protocol.game.ServerboundChatPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.handshake.ClientIntentionPacket;
import net.minecraft.network.protocol.login.ServerboundHelloPacket;
import net.minecraft.world.phys.Vec3;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;
import java.util.function.Predicate;

import static com.github.epsilon.Constants.mc;

/**
 * Centralized packet-delay infrastructure (equivalent to LiquidBounce's BlinkManager).
 *
 * <p>Subscribes to {@link PacketEvent} at {@link EventPriority#LOWEST} (runs LAST
 * after all other modules). For each packet, fires a {@link BlinkPacketEvent}
 * asking modules to vote:
 * <ul>
 *   <li>{@link Action#QUEUE} — cancel original event, add packet to the shared queue</li>
 *   <li>{@link Action#PASS} — let the packet through, don't flush the queue</li>
 *   <li>{@link Action#FLUSH} — flush the queue, then let the packet through</li>
 * </ul>
 *
 * <p>Multiple modules can coexist — the action with the highest priority wins.
 * The shared queue is exposed via {@link #packetQueue} so modules can
 * inspect it for rendering, position tracking, etc.
 */
public class ServerboundPacketManager {

    public static final ServerboundPacketManager INSTANCE = new ServerboundPacketManager();

    private ServerboundPacketManager() {
        EventBus.INSTANCE.subscribe(this);
    }

    // -- Legacy fields (kept for potential external references) --

    /** Shared packet queue — read by modules for position tracking / ESP. */
    public final ConcurrentLinkedQueue<PacketSnapshot> packetQueue = new ConcurrentLinkedQueue<>();

    /** @deprecated Use {@link #isLagging()} instead. Kept for backward compat. */
    @Deprecated
    public boolean blinking = false;

    /** @deprecated Internal use only. */
    @Deprecated
    static boolean forceFlush;

    /** True while any packets are queued. */
    public boolean isLagging() {
        return !packetQueue.isEmpty();
    }

    // -- PacketEvent handlers (LOWEST priority = runs LAST) --

    @EventHandler(priority = EventPriority.LOWEST)
    private void onPacketSend(PacketEvent.Send event) {
        if (event.isCancelled()) return;
        processPacket(event.getPacket(), TransferOrigin.OUTGOING, event);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    private void onPacketReceive(PacketEvent.Receive event) {
        if (event.isCancelled()) return;
        processPacket(event.getPacket(), TransferOrigin.INCOMING, event);
    }

    private void processPacket(Packet<?> packet, TransferOrigin origin, Cancellable event) {
        // Never delay handshake / login
        if (packet instanceof ClientIntentionPacket
                || packet instanceof ServerboundHelloPacket) {
            return;
        }

        // Never delay chat
        if (packet instanceof ServerboundChatPacket
                || packet instanceof ServerboundChatCommandPacket) {
            return;
        }

        // Ask all modules what to do via BlinkPacketEvent
        Action result = fire(packet, origin).getAction();

        if (result == Action.FLUSH) {
            flush(origin);
            return;
        }

        if (result == Action.PASS) {
            return;
        }

        // QUEUE: cancel the original event, add packet to the shared queue
        event.cancel();
        packetQueue.add(new PacketSnapshot(packet, origin, System.currentTimeMillis()));
    }

    // -- Periodic time-check tick (null-packet BlinkPacketEvent) --

    @EventHandler
    private void onTick(PlayerTickEvent.Pre event) {
        if (mc.player == null || mc.getConnection() == null) return;
        if (mc.getConnection().getConnection() == null
                || !mc.getConnection().getConnection().isConnected()) {
            packetQueue.clear();
            return;
        }
        // A null packet lets modules check time-based conditions (e.g. isAboveTime)
        if (fire(null, TransferOrigin.OUTGOING).getAction() == Action.FLUSH) {
            flush(TransferOrigin.OUTGOING);
        }
    }

    @EventHandler
    private void onLevelUpdate(LevelUpdateEvent event) {
        forceFlush = true;
        blinking = false;
        flush(TransferOrigin.OUTGOING);
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        packetQueue.clear();
    }

    // -- Legacy MixinConnection hook --

    /**
     * Legacy hook called from {@code MixinConnection} before {@link PacketEvent.Send} fires.
     * If {@link #blinking} is true, captures the packet directly.
     * Prefer using {@link BlinkPacketEvent} in new code.
     */
    public boolean onPacketSend(Packet<?> packet) {
        if (forceFlush) {
            flush(TransferOrigin.OUTGOING);
            forceFlush = false;
            return false;
        }
        if (!blinking) return false;
        if (mc.player == null || mc.level == null) return false;

        packetQueue.add(new PacketSnapshot(packet, TransferOrigin.OUTGOING,
                System.currentTimeMillis()));
        return true;
    }

    // -- Flush API --

    /** Flush all packets of the given origin. */
    public void flush(TransferOrigin origin) {
        flush(snapshot -> snapshot.origin == origin);
    }

    /** Flush packets matching a predicate. */
    public void flush(Predicate<PacketSnapshot> predicate) {
        packetQueue.removeIf(snapshot -> {
            if (predicate.test(snapshot)) {
                sendSnapshot(snapshot);
                return true;
            }
            return false;
        });
    }

    /**
     * Flush up to {@code count} position-carrying movement packets
     * (plus any non-movement packets preceding them in the queue).
     */
    public void flush(int count) {
        int counter = 0;
        var it = packetQueue.iterator();
        while (it.hasNext()) {
            var snapshot = it.next();
            if (snapshot.packet instanceof ServerboundMovePlayerPacket mp
                    && mp.hasPosition()) {
                counter++;
            }
            sendSnapshot(snapshot);
            it.remove();
            if (counter >= count) break;
        }
    }

    /** @deprecated Use {@link #flush(TransferOrigin)} with OUTGOING instead. */
    @Deprecated
    public void flush() {
        flush(TransferOrigin.OUTGOING);
    }

    /**
     * Cancel all queued movement packets (teleport player back to first
     * blink position), flush non-movement packets.
     */
    public void cancel() {
        double x = mc.player.getX(), y = mc.player.getY(), z = mc.player.getZ();
        boolean restored = false;
        for (var snapshot : packetQueue) {
            if (snapshot.packet instanceof ServerboundMovePlayerPacket mp && mp.hasPosition()) {
                x = mp.getX(x);
                y = mp.getY(y);
                z = mp.getZ(z);
                if (!restored) {
                    mc.player.setPos(new Vec3(x, y, z));
                    restored = true;
                }
            }
        }
        packetQueue.removeIf(snapshot -> {
            if (snapshot.packet instanceof ServerboundMovePlayerPacket) {
                return true;
            }
            sendSnapshot(snapshot);
            return true;
        });
    }

    /**
     * Check whether the oldest queued packet has waited at least {@code delay} ms.
     */
    public boolean isAboveTime(long delay) {
        var first = packetQueue.peek();
        if (first == null) return false;
        return System.currentTimeMillis() - first.timestamp >= delay;
    }

    /**
     * Apply a mutation to all queued packets of a specific type in-place.
     */
    @SuppressWarnings("unchecked")
    public <T extends Packet<?>> void rewrite(Class<T> type, Consumer<T> action) {
        for (var snapshot : packetQueue) {
            if (type.isInstance(snapshot.packet)) {
                action.accept((T) snapshot.packet);
            }
        }
    }

    /** @deprecated Use {@link #isLagging()} instead. */
    @Deprecated
    public void stopBlinking() {
        blinking = false;
    }

    /** @deprecated Listen to {@link BlinkPacketEvent} and set {@link Action#QUEUE} instead. */
    @Deprecated
    public void startBlinking() {
        blinking = true;
    }

    // -- Internal --

    private BlinkPacketEvent fire(Packet<?> packet, TransferOrigin origin) {
        return EventBus.INSTANCE.post(new BlinkPacketEvent(packet, origin));
    }

    private void sendSnapshot(PacketSnapshot snapshot) {
        if (snapshot.origin == TransferOrigin.OUTGOING) {
            PacketUtils.sendSilently(snapshot.packet);
        }
    }

    // -- Types --

    public record PacketSnapshot(Packet<?> packet, TransferOrigin origin, long timestamp) {}
}
