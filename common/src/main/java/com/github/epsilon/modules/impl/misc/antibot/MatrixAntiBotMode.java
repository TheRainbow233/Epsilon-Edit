package com.github.epsilon.modules.impl.misc.antibot;

import com.github.epsilon.modules.impl.misc.AntiBot;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Detects Matrix anti-cheat bots.
 * Matrix spawns bots with empty profile properties and manipulates armor
 * by instantly replacing it, which can be detected with a 1-tick delay.
 */
public class MatrixAntiBotMode extends AntiBotMode {

    private static final EquipmentSlot[] ARMOR_SLOTS = {
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
    };

    private final Set<UUID> suspectList = new HashSet<>();
    private final Set<UUID> botList = new HashSet<>();

    // Track armor from previous tick for suspects
    private final java.util.Map<UUID, ItemStack[]> prevArmorMap = new java.util.HashMap<>();

    public MatrixAntiBotMode() {
        super("Matrix");
    }

    public void onPacketReceive(net.minecraft.network.protocol.Packet<?> packet) {
        if (packet instanceof ClientboundPlayerInfoUpdatePacket updatePacket) {
            // Check if this is an ADD_PLAYER action
            boolean isAddPlayer = false;
            for (ClientboundPlayerInfoUpdatePacket.Action action : updatePacket.actions()) {
                if (action == ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER) {
                    isAddPlayer = true;
                    break;
                }
            }

            if (!isAddPlayer) return;

            Minecraft.getInstance().execute(() -> {
                for (ClientboundPlayerInfoUpdatePacket.Entry entry : updatePacket.entries()) {
                    if (entry.profile() == null) continue;

                    if (entry.latency() < 2
                            || !entry.profile().properties().isEmpty()
                            || AntiBot.isGameProfileUnique(entry.profile())) {
                        continue;
                    }

                    if (AntiBot.isADuplicate(entry.profile())) {
                        botList.add(entry.profileId());
                        continue;
                    }

                    suspectList.add(entry.profileId());
                }
            });

        } else if (packet instanceof ClientboundPlayerInfoRemovePacket removePacket) {
            Minecraft.getInstance().execute(() -> {
                for (UUID uuid : removePacket.profileIds()) {
                    suspectList.remove(uuid);
                    botList.remove(uuid);
                    prevArmorMap.remove(uuid);
                }
            });
        }
    }

    /**
     * Called every tick to check suspects' armor status.
     * Matrix spawns bots with random armor then instantly swaps it.
     */
    public void onTick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || suspectList.isEmpty()) return;

        for (Player entity : mc.level.players()) {
            UUID uuid = entity.getUUID();
            if (!suspectList.contains(uuid)) continue;

            ItemStack[] prevArmor = prevArmorMap.get(uuid);

            if (prevArmor == null) {
                // First check: snapshot armor and wait 1 tick
                prevArmorMap.put(uuid, getArmorSlots(entity));
                continue;
            }

            // Second check after 1 tick: see if armor changed or is fully enchanted
            boolean armorChanged = !armorEquals(prevArmor, getArmorSlots(entity));

            if ((isFullyArmored(entity) || armorChanged)
                    && entity.getGameProfile().properties().isEmpty()) {
                botList.add(uuid);
            }

            suspectList.remove(uuid);
            prevArmorMap.remove(uuid);
        }
    }

    private ItemStack[] getArmorSlots(Player entity) {
        ItemStack[] armor = new ItemStack[4];
        for (int i = 0; i < ARMOR_SLOTS.length; i++) {
            armor[i] = entity.getItemBySlot(ARMOR_SLOTS[i]);
        }
        return armor;
    }

    private boolean armorEquals(ItemStack[] a, ItemStack[] b) {
        if (a.length != b.length) return false;
        for (int i = 0; i < a.length; i++) {
            if (!ItemStack.isSameItemSameComponents(a[i], b[i])) return false;
        }
        return true;
    }

    private boolean isFullyArmored(Player entity) {
        for (EquipmentSlot slot : ARMOR_SLOTS) {
            ItemStack stack = entity.getItemBySlot(slot);
            if (!stack.isEnchanted()) return false;
        }
        return true;
    }

    @Override
    public boolean isBot(Player entity) {
        return botList.contains(entity.getUUID());
    }

    @Override
    public void reset() {
        suspectList.clear();
        botList.clear();
        prevArmorMap.clear();
    }
}
