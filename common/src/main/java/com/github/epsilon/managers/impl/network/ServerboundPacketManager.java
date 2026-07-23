package com.github.epsilon.managers.impl.network;

import com.github.epsilon.events.bus.EventBus;
import com.github.epsilon.events.bus.EventHandler;
import com.github.epsilon.events.impl.BlinkPacketEvent.TransferOrigin;
import com.github.epsilon.events.impl.LevelUpdateEvent;
import com.github.epsilon.managers.Managers;
import net.minecraft.network.protocol.Packet;

import java.util.concurrent.LinkedBlockingQueue;

import static com.github.epsilon.Constants.mc;

/**
 * Legacy backward-compatibility wrapper around {@link BlinkManager}.
 *
 * @deprecated Use {@link BlinkManager} directly. This class exists only
 *             for code that still calls the old startBlinking/stopBlinking API.
 */
@Deprecated
public class ServerboundPacketManager {

    public ServerboundPacketManager() {
        EventBus.INSTANCE.subscribe(this);
    }

    /** @deprecated Use {@link BlinkManager#packetQueue} */
    @Deprecated
    public final LinkedBlockingQueue<Packet<?>> packets = new LinkedBlockingQueue<>();

    /** @deprecated Use {@link BlinkManager#isLagging()} */
    @Deprecated
    public boolean blinking = false;

    static boolean forceFlush;

    @EventHandler
    private void onLevelUpdate(LevelUpdateEvent event) {
        forceFlush = true;
        blinking = false;
        BlinkManager.INSTANCE.flush(TransferOrigin.OUTGOING);
    }

    /** @deprecated Use {@link BlinkManager#flush(TransferOrigin)} */
    @Deprecated
    public void flush() {
        BlinkManager.INSTANCE.flush(TransferOrigin.OUTGOING);
    }

    /** @deprecated Use {@link BlinkManager#isLagging()} */
    @Deprecated
    public void stopBlinking() {
        blinking = false;
    }

    /** @deprecated Listen to BlinkPacketEvent and set QUEUE instead */
    @Deprecated
    public void startBlinking() {
        blinking = true;
    }

    /**
     * Legacy hook. When blinking, captures the packet before PacketEvent fires.
     * Still supported for backward compat but prefer BlinkPacketEvent.
     */
    public boolean onPacketSend(Packet<?> packet) {
        if (forceFlush) {
            BlinkManager.INSTANCE.flush(TransferOrigin.OUTGOING);
            forceFlush = false;
            return false;
        }
        if (!blinking) return false;
        if (mc.player == null || mc.level == null) return false;

        BlinkManager.INSTANCE.packetQueue.add(
                new BlinkManager.PacketSnapshot(packet, TransferOrigin.OUTGOING,
                        System.currentTimeMillis())
        );
        return true;
    }
}
