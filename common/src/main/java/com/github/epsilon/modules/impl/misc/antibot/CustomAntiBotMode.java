package com.github.epsilon.modules.impl.misc.antibot;

import com.github.epsilon.modules.impl.misc.AntiBot;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.game.ClientboundAnimatePacket;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateAttributesPacket;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Comprehensive custom anti-bot detection with multiple configurable checks.
 * Ported from LiquidBounce's CustomAntiBotMode.
 */
public class CustomAntiBotMode extends AntiBotMode {

    // --- Armor material predicates ---
    private enum ArmorPredicate {
        NOTHING("Nothing", ItemStack::isEmpty),
        LEATHER("Leather",
                Items.LEATHER_HELMET, Items.LEATHER_CHESTPLATE,
                Items.LEATHER_LEGGINGS, Items.LEATHER_BOOTS),
        CHAIN("Chain",
                Items.CHAINMAIL_HELMET, Items.CHAINMAIL_CHESTPLATE,
                Items.CHAINMAIL_LEGGINGS, Items.CHAINMAIL_BOOTS),
        IRON("Iron",
                Items.IRON_HELMET, Items.IRON_CHESTPLATE,
                Items.IRON_LEGGINGS, Items.IRON_BOOTS),
        GOLD("Gold",
                Items.GOLDEN_HELMET, Items.GOLDEN_CHESTPLATE,
                Items.GOLDEN_LEGGINGS, Items.GOLDEN_BOOTS),
        DIAMOND("Diamond",
                Items.DIAMOND_HELMET, Items.DIAMOND_CHESTPLATE,
                Items.DIAMOND_LEGGINGS, Items.DIAMOND_BOOTS),
        NETHERITE("Netherite",
                Items.NETHERITE_HELMET, Items.NETHERITE_CHESTPLATE,
                Items.NETHERITE_LEGGINGS, Items.NETHERITE_BOOTS),
        ELYTRA("Elytra", Items.ELYTRA),
        TURTLE_SCUTE("TurtleScute", Items.TURTLE_HELMET),
        PUMPKIN("Pumpkin", Items.CARVED_PUMPKIN),
        SKULL("Skull",
                Items.SKELETON_SKULL, Items.WITHER_SKELETON_SKULL,
                Items.PLAYER_HEAD, Items.ZOMBIE_HEAD, Items.CREEPER_HEAD);

        final String tag;
        final Predicate<ItemStack> predicate;

        ArmorPredicate(String tag, Predicate<ItemStack> predicate) {
            this.tag = tag;
            this.predicate = predicate;
        }

        ArmorPredicate(String tag, net.minecraft.world.item.Item... items) {
            this.tag = tag;
            var set = Set.of(items);
            this.predicate = stack -> set.contains(stack.getItem());
        }
    }

    private static final EnumSet<ArmorPredicate> BASE_PREDICATES = EnumSet.of(
            ArmorPredicate.NOTHING, ArmorPredicate.LEATHER,
            ArmorPredicate.CHAIN, ArmorPredicate.IRON,
            ArmorPredicate.GOLD, ArmorPredicate.DIAMOND,
            ArmorPredicate.NETHERITE);

    private static final EnumSet<ArmorPredicate> HELMET_PREDICATES = EnumSet.of(
            ArmorPredicate.NOTHING, ArmorPredicate.LEATHER,
            ArmorPredicate.CHAIN, ArmorPredicate.IRON,
            ArmorPredicate.GOLD, ArmorPredicate.DIAMOND,
            ArmorPredicate.NETHERITE, ArmorPredicate.TURTLE_SCUTE,
            ArmorPredicate.PUMPKIN, ArmorPredicate.SKULL);

    private static final EnumSet<ArmorPredicate> CHESTPLATE_PREDICATES = EnumSet.of(
            ArmorPredicate.NOTHING, ArmorPredicate.LEATHER,
            ArmorPredicate.CHAIN, ArmorPredicate.IRON,
            ArmorPredicate.GOLD, ArmorPredicate.DIAMOND,
            ArmorPredicate.NETHERITE, ArmorPredicate.ELYTRA);

    private static final EnumMap<EquipmentSlot, EnumSet<ArmorPredicate>> ALLOWED_ARMOR = new EnumMap<>(EquipmentSlot.class);

    static {
        ALLOWED_ARMOR.put(EquipmentSlot.HEAD, EnumSet.copyOf(HELMET_PREDICATES));
        ALLOWED_ARMOR.put(EquipmentSlot.CHEST, EnumSet.copyOf(CHESTPLATE_PREDICATES));
        ALLOWED_ARMOR.put(EquipmentSlot.LEGS, EnumSet.copyOf(BASE_PREDICATES));
        ALLOWED_ARMOR.put(EquipmentSlot.FEET, EnumSet.copyOf(BASE_PREDICATES));
    }

    // --- Tracking state ---
    private final Int2IntOpenHashMap flyingSet = new Int2IntOpenHashMap();
    private final IntOpenHashSet hitSet = new IntOpenHashSet();
    private final IntOpenHashSet notAlwaysInRadiusSet = new IntOpenHashSet();
    private final IntOpenHashSet swungSet = new IntOpenHashSet();
    private final IntOpenHashSet crittedSet = new IntOpenHashSet();
    private final IntOpenHashSet attributesSet = new IntOpenHashSet();
    private final IntOpenHashSet armorSet = new IntOpenHashSet();

    // --- Configurable thresholds ---
    private boolean invalidGroundEnabled = true;
    private int vlToConsiderAsBot = 10;

    private boolean alwaysInRadiusEnabled = false;
    private float alwaysInRadiusRange = 20.0f;

    private boolean armorEnabled = false;

    private boolean ageEnabled = false;
    private int ageMinimum = 20;

    private boolean nameEnabled = true;
    private int nameMinLength = 3;
    private int nameMaxLength = 16;
    private boolean nameValidateChars = true;

    private final Set<CustomCondition> customConditions = EnumSet.noneOf(CustomCondition.class);

    public CustomAntiBotMode() {
        super("Custom");
    }

    // --- Config setters ---

    public void setInvalidGroundEnabled(boolean v) { this.invalidGroundEnabled = v; }
    public void setVlToConsiderAsBot(int v) { this.vlToConsiderAsBot = v; }
    public void setAlwaysInRadiusEnabled(boolean v) { this.alwaysInRadiusEnabled = v; }
    public void setAlwaysInRadiusRange(float v) { this.alwaysInRadiusRange = v; }
    public void setArmorEnabled(boolean v) { this.armorEnabled = v; }
    public void setAgeEnabled(boolean v) { this.ageEnabled = v; }
    public void setAgeMinimum(int v) { this.ageMinimum = v; }
    public void setNameEnabled(boolean v) { this.nameEnabled = v; }
    public void setNameMinLength(int v) { this.nameMinLength = v; }
    public void setNameMaxLength(int v) { this.nameMaxLength = v; }
    public void setNameValidateChars(boolean v) { this.nameValidateChars = v; }
    public void setCustomConditions(EnumSet<CustomCondition> s) {
        this.customConditions.clear();
        this.customConditions.addAll(s);
    }
    public Set<CustomCondition> getCustomConditions() { return customConditions; }

    // --- Packet handling ---

    @Override
    public void onPacketReceive(net.minecraft.network.protocol.Packet<?> packet) {
        if (packet instanceof ClientboundMoveEntityPacket movePacket) {
            if (!movePacket.hasPosition() || !invalidGroundEnabled) return;

            var entity = movePacket.getEntity(Minecraft.getInstance().level);
            if (entity == null) return;

            int id = entity.getId();
            int currentVL = flyingSet.getOrDefault(id, 0);

            // Only flag when onGround AND the entity actually moved (y changed)
            if (entity.onGround() && entity.yo != entity.getY()) {
                flyingSet.put(id, currentVL + 1);
            } else if (!entity.onGround() && currentVL > 0) {
                // VL decay: halve on non-ground ticks
                int newVL = currentVL / 2;
                if (newVL <= 0) {
                    flyingSet.remove(id);
                } else {
                    flyingSet.put(id, newVL);
                }
            }
        } else if (packet instanceof ClientboundUpdateAttributesPacket attrPacket) {
            attributesSet.add(attrPacket.getEntityId());
        } else if (packet instanceof ClientboundAnimatePacket animPacket) {
            int action = animPacket.getAction();
            if (action == ClientboundAnimatePacket.SWING_MAIN_HAND
                    || action == ClientboundAnimatePacket.SWING_OFF_HAND) {
                swungSet.add(animPacket.getId());
            } else if (action == ClientboundAnimatePacket.CRITICAL_HIT
                    || action == ClientboundAnimatePacket.MAGIC_CRITICAL_HIT) {
                crittedSet.add(animPacket.getId());
            }
        } else if (packet instanceof ClientboundRemoveEntitiesPacket removePacket) {
            for (int id : removePacket.getEntityIds()) {
                attributesSet.remove(id);
                flyingSet.remove(id);
                hitSet.remove(id);
                notAlwaysInRadiusSet.remove(id);
                armorSet.remove(id);
            }
        }
    }

    @Override
    public void onAttack(int entityId) {
        hitSet.add(entityId);
    }

    @Override
    public void onTick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        double rangeSq = alwaysInRadiusRange * alwaysInRadiusRange;

        for (Player entity : mc.level.players()) {
            if (entity == mc.player) continue;

            int id = entity.getId();

            // AlwaysInRadius tracking
            if (alwaysInRadiusEnabled) {
                if (mc.player.distanceToSqr(entity) > rangeSq) {
                    notAlwaysInRadiusSet.add(id);
                }
            }

            // Armor validation
            if (armorEnabled) {
                if (!isValidArmor(entity)) {
                    armorSet.add(id);
                }
            }
        }

        // Remove entities that are no longer valid bots (armor now valid)
        armorSet.removeIf(id -> {
            var e = mc.level.getEntity(id);
            return !(e instanceof Player player) || isValidArmor(player);
        });
    }

    // --- Core detection ---

    @Override
    public boolean isBot(Player entity) {
        int id = entity.getId();

        if (invalidGroundEnabled && hasInvalidGround(id)) return true;
        if (alwaysInRadiusEnabled && !notAlwaysInRadiusSet.contains(id)) return true;
        if (ageEnabled && entity.tickCount < ageMinimum) return true;
        if (armorEnabled && armorSet.contains(id)) return true;
        if (nameEnabled && hasInvalidName(entity)) return true;

        // Custom conditions
        for (CustomCondition c : customConditions) {
            if (c.check(entity, this)) return true;
        }
        return false;
    }

    private boolean hasInvalidGround(int entityId) {
        return flyingSet.getOrDefault(entityId, 0) >= vlToConsiderAsBot;
    }

    private boolean hasInvalidName(Player entity) {
        String name = entity.getScoreboardName();
        if (name == null) return true;

        int len = name.length();
        if (len < nameMinLength || len > nameMaxLength) return true;

        if (nameValidateChars) {
            for (char c : name.toCharArray()) {
                if (!isVanillaChar(c)) return true;
            }
        }
        return false;
    }

    private static boolean isVanillaChar(char c) {
        return (c >= '0' && c <= '9')
                || (c >= 'a' && c <= 'z')
                || (c >= 'A' && c <= 'Z')
                || c == '_';
    }

    private boolean isValidArmor(Player entity) {
        for (EquipmentSlot slot : ALLOWED_ARMOR.keySet()) {
            ItemStack armor = entity.getItemBySlot(slot);
            // Skip if no predicates for this slot (shouldn't happen)
            if (!ALLOWED_ARMOR.containsKey(slot)) continue;
            // Check if any allowed predicate matches
            boolean matched = false;
            for (ArmorPredicate p : ALLOWED_ARMOR.get(slot)) {
                if (p.predicate.test(armor)) {
                    matched = true;
                    break;
                }
            }
            if (!matched) return false;
        }
        return true;
    }

    @Override
    public void reset() {
        flyingSet.clear();
        notAlwaysInRadiusSet.clear();
        hitSet.clear();
        swungSet.clear();
        crittedSet.clear();
        attributesSet.clear();
        armorSet.clear();
    }

    // --- Accessors ---

    public IntOpenHashSet getHitSet() { return hitSet; }
    public IntOpenHashSet getSwungSet() { return swungSet; }
    public IntOpenHashSet getCrittedSet() { return crittedSet; }
    public IntOpenHashSet getAttributesSet() { return attributesSet; }

    // --- Custom condition enum ---

    public enum CustomCondition {
        DUPLICATE {
            @Override
            boolean check(Player entity, CustomAntiBotMode mode) {
                return AntiBot.isADuplicate(entity.getGameProfile());
            }
        },
        NO_GAME_MODE {
            @Override
            boolean check(Player entity, CustomAntiBotMode mode) {
                Minecraft mc = Minecraft.getInstance();
                if (mc.getConnection() == null) return false;
                var info = mc.getConnection().getPlayerInfo(entity.getUUID());
                return info != null && info.getGameMode() == null;
            }
        },
        ILLEGAL_PITCH {
            @Override
            boolean check(Player entity, CustomAntiBotMode mode) {
                return Math.abs(entity.getXRot()) > 90.0f;
            }
        },
        FAKE_ENTITY_ID {
            @Override
            boolean check(Player entity, CustomAntiBotMode mode) {
                int id = entity.getId();
                return id < 0 || id > 1_000_000_000;
            }
        },
        NEED_HIT {
            @Override
            boolean check(Player entity, CustomAntiBotMode mode) {
                return !mode.getHitSet().contains(entity.getId());
            }
        },
        ILLEGAL_HEALTH {
            @Override
            boolean check(Player entity, CustomAntiBotMode mode) {
                Minecraft mc = Minecraft.getInstance();
                if (mc.player == null) return false;
                return entity.getHealth() > mc.player.getMaxHealth();
            }
        },
        SWUNG {
            @Override
            boolean check(Player entity, CustomAntiBotMode mode) {
                return !mode.getSwungSet().contains(entity.getId());
            }
        },
        CRITTED {
            @Override
            boolean check(Player entity, CustomAntiBotMode mode) {
                return !mode.getCrittedSet().contains(entity.getId());
            }
        },
        ATTRIBUTES {
            @Override
            boolean check(Player entity, CustomAntiBotMode mode) {
                return !mode.getAttributesSet().contains(entity.getId());
            }
        },
        ILLEGAL_SCALE {
            @Override
            boolean check(Player entity, CustomAntiBotMode mode) {
                return entity.getAttributeValue(Attributes.SCALE) != 1.0;
            }
        };

        abstract boolean check(Player entity, CustomAntiBotMode mode);
    }
}
