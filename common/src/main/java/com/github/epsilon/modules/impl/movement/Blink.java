package com.github.epsilon.modules.impl.movement;

import com.github.epsilon.events.bus.EventHandler;
import com.github.epsilon.events.impl.BlinkPacketEvent;
import com.github.epsilon.events.impl.BlinkPacketEvent.Action;
import com.github.epsilon.events.impl.BlinkPacketEvent.TransferOrigin;
import com.github.epsilon.events.impl.Render3DEvent;
import com.github.epsilon.events.impl.SendPositionEvent;
import com.github.epsilon.managers.impl.network.BlinkManager;
import com.github.epsilon.modules.Category;
import com.github.epsilon.modules.Module;
import com.github.epsilon.settings.impl.BoolSetting;
import com.github.epsilon.settings.impl.DoubleSetting;
import com.mojang.authlib.GameProfile;
import net.minecraft.client.gui.screens.RecoverWorldDataScreen;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.entity.Entity;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

public class Blink extends Module {

    public static final Blink INSTANCE = new Blink();

    public Blink() {
        super("Blink", Category.MOVEMENT);
    }

    private final DoubleSetting tick = doubleSetting("Tick", 30, 5, 200, 1);
    private final BoolSetting SlowRelease = boolSetting("Slow Release", true);
    private final BoolSetting slowMove = boolSetting("Slow Move", false);
    private final DoubleSetting slowMoveTick = doubleSetting("Slow Move Tick", 5, 2, 5, 1, slowMove::getValue);
    private final BoolSetting fakePlayer = boolSetting("Fake Player", false);

    public RemotePlayer localPlayer;

    // Server-side position (accumulated from queued movement packets in BlinkManager)
    private double serverX, serverY, serverZ;
    private double prevServerX, prevServerY, prevServerZ;
    private float serverYRot, serverXRot, serverYHeadRot;
    private float prevServerYRot, prevServerXRot, prevServerYHeadRot;
    private double lastLerpX, lastLerpY, lastLerpZ;

    // -- BlinkPacketEvent: vote QUEUE for every outgoing packet when enabled --

    @EventHandler
    private void onBlinkPacket(BlinkPacketEvent event) {
        if (nullCheck()) return;
        if (event.getOrigin() != TransferOrigin.OUTGOING) return;
        if (event.getPacket() == null) return; // skip periodic null-packet ticks
        event.setAction(Action.QUEUE);
    }

    @Override
    public void onEnable() {
        if (nullCheck()) return;
        resetServerPosition();
        if (fakePlayer.getValue()) {
            localPlayer = new RemotePlayer(mc.level,
                    new GameProfile(UUID.nameUUIDFromBytes("".getBytes(StandardCharsets.UTF_8)), ""));
            localPlayer.setId(-1337);
            localPlayer.copyPosition(mc.player);
            localPlayer.setYRot(mc.player.getYRot());
            localPlayer.setXRot(mc.player.getXRot());
            localPlayer.setYHeadRot(mc.player.getYHeadRot());
            localPlayer.setHealth(mc.player.getHealth());
            localPlayer.setAbsorptionAmount(mc.player.getAbsorptionAmount());
            mc.level.addEntity(localPlayer);
        }
    }

    @Override
    public void onDisable() {
        if (nullCheck()) return;
        if (fakePlayer.getValue() && localPlayer != null) {
            mc.level.removeEntity(localPlayer.getId(), Entity.RemovalReason.DISCARDED);
        }
        releaseAll();
    }

    // -- Position tracking from BlinkManager queue --

    private void resetServerPosition() {
        serverX = prevServerX = mc.player.getX();
        serverY = prevServerY = mc.player.getY();
        serverZ = prevServerZ = mc.player.getZ();
        serverYRot = prevServerYRot = mc.player.getYRot();
        serverXRot = prevServerXRot = mc.player.getXRot();
        serverYHeadRot = prevServerYHeadRot = mc.player.getYHeadRot();
    }

    /**
     * Recompute the server-side position by accumulating all queued movement
     * packets from the BlinkManager queue.
     */
    private void updateServerPosition() {
        prevServerX = serverX;
        prevServerY = serverY;
        prevServerZ = serverZ;
        prevServerYRot = serverYRot;
        prevServerXRot = serverXRot;
        prevServerYHeadRot = serverYHeadRot;

        double x = mc.player.getX();
        double y = mc.player.getY();
        double z = mc.player.getZ();
        float yRot = mc.player.getYRot();
        float xRot = mc.player.getXRot();
        float yHeadRot = mc.player.getYHeadRot();

        for (var snapshot : BlinkManager.INSTANCE.packetQueue) {
            if (snapshot.packet() instanceof ServerboundMovePlayerPacket mp) {
                x = mp.getX(x);
                y = mp.getY(y);
                z = mp.getZ(z);
                yRot = mp.getYRot(yRot);
                xRot = mp.getXRot(xRot);
                if (mp.hasRotation()) {
                    yHeadRot = mp.getYRot(yHeadRot);
                }
            }
        }

        serverX = x;
        serverY = y;
        serverZ = z;
        serverYRot = yRot;
        serverXRot = xRot;
        serverYHeadRot = yHeadRot;
    }

    /** Count queued position-carrying movement packets. */
    private int getBlinkTicks() {
        int count = 0;
        for (var snapshot : BlinkManager.INSTANCE.packetQueue) {
            if (snapshot.packet() instanceof ServerboundMovePlayerPacket) {
                count++;
            }
        }
        return count;
    }

    // -- Release via BlinkManager --

    private void releaseTick() {
        updateServerPosition();
        BlinkManager.INSTANCE.flush(1);
    }

    private void releaseAll() {
        BlinkManager.INSTANCE.flush(TransferOrigin.OUTGOING);
    }

    // -- Render fake player at server position --

    @EventHandler
    public void onRender(Render3DEvent event) {
        if (nullCheck()) return;
        if (localPlayer != null && fakePlayer.getValue()) {
            updateServerPosition();
            float pt = mc.getDeltaTracker().getGameTimeDeltaPartialTick(true);
            double lerpX = prevServerX + (serverX - prevServerX) * pt;
            double lerpY = prevServerY + (serverY - prevServerY) * pt;
            double lerpZ = prevServerZ + (serverZ - prevServerZ) * pt;
            localPlayer.xOld = lastLerpX;
            localPlayer.yOld = lastLerpY;
            localPlayer.zOld = lastLerpZ;
            localPlayer.setPos(lerpX, lerpY, lerpZ);
            lastLerpX = lerpX;
            lastLerpY = lerpY;
            lastLerpZ = lerpZ;

            float lerpYRot = prevServerYRot + (serverYRot - prevServerYRot) * pt;
            float lerpXRot = prevServerXRot + (serverXRot - prevServerXRot) * pt;
            float lerpYHeadRot = prevServerYHeadRot + (serverYHeadRot - prevServerYHeadRot) * pt;
            localPlayer.setYRot(lerpYRot);
            localPlayer.setXRot(lerpXRot);
            localPlayer.setYHeadRot(lerpYHeadRot);
            localPlayer.setYBodyRot(lerpYRot);
        }
    }

    // -- Release scheduling --

    @EventHandler
    public void onMotion(SendPositionEvent event) {
        if (mc.gui.screen() instanceof RecoverWorldDataScreen && this.isEnabled()) {
            this.setEnabled(false);
        }
        if (nullCheck()) return;

        if (SlowRelease.getValue()) {
            if (getBlinkTicks() > tick.getValue()) {
                releaseTick();
            }
        } else {
            if (getBlinkTicks() > tick.getValue()) {
                releaseAll();
            }
        }
        if (slowMove.getValue()) {
            if (mc.player.tickCount % slowMoveTick.getValue().intValue() == 0) {
                releaseTick();
            }
        }
    }
}
