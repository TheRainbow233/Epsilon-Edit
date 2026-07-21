package com.github.epsilon.modules.impl.misc;

import com.github.epsilon.events.bus.EventHandler;
import com.github.epsilon.events.impl.AttackEntityEvent;
import com.github.epsilon.events.impl.GameJoinedEvent;
import com.github.epsilon.events.impl.GameLeftEvent;
import com.github.epsilon.events.impl.PacketEvent;
import com.github.epsilon.events.impl.PlayerTickEvent;
import com.github.epsilon.modules.Category;
import com.github.epsilon.modules.Module;
import com.github.epsilon.modules.impl.misc.antibot.AntiBotMode;
import com.github.epsilon.modules.impl.misc.antibot.CustomAntiBotMode;
import com.github.epsilon.modules.impl.misc.antibot.HorizonAntiBotMode;
import com.github.epsilon.modules.impl.misc.antibot.IntaveHeavyAntiBotMode;
import com.github.epsilon.modules.impl.misc.antibot.MatrixAntiBotMode;
import com.github.epsilon.settings.SettingGroup;
import com.github.epsilon.settings.impl.BoolSetting;
import com.github.epsilon.settings.impl.EnumSetting;
import com.github.epsilon.settings.impl.IntSetting;
import com.github.epsilon.settings.impl.MultiEnumSetting;
import com.mojang.authlib.GameProfile;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * AntiBot module — detects and ignores fake/bot players using multiple detection strategies.
 * Ported from LiquidBounce (CCBlueX/LiquidBounce).
 */
public class AntiBot extends Module {

    // Must be before INSTANCE — static init order
    private static final Set<CustomAntiBotMode.CustomCondition> DEFAULT_CONDITIONS =
            Set.of(
                    CustomAntiBotMode.CustomCondition.NO_GAME_MODE,
                    CustomAntiBotMode.CustomCondition.ILLEGAL_PITCH,
                    CustomAntiBotMode.CustomCondition.FAKE_ENTITY_ID
            );

    public static final AntiBot INSTANCE = new AntiBot();

    // --- Mode enum ---
    public enum Mode {
        Custom,
        Horizon,
        IntaveHeavy,
        Matrix
    }

    // --- Mode instances (must be before settings for onChanged callbacks) ---
    private final CustomAntiBotMode customMode = new CustomAntiBotMode();
    private final HorizonAntiBotMode horizonMode = new HorizonAntiBotMode();
    private final IntaveHeavyAntiBotMode intaveHeavyMode = new IntaveHeavyAntiBotMode();
    private final MatrixAntiBotMode matrixMode = new MatrixAntiBotMode();

    // --- Mode selection ---
    private final EnumSetting<Mode> mode = enumSetting("Mode", Mode.Custom);

    // --- General checks ---
    private final BoolSetting literalNPC = boolSetting("Literal NPC", false);
    private final BoolSetting notInTabList = boolSetting("Not In Tab List", false);

    // --- Custom mode: InvalidGround ---
    private final BoolSetting invalidGround = boolSetting("Invalid Ground", true,
            () -> mode.is(Mode.Custom), v -> { syncCustomModeSettings(); });
    private final IntSetting invalidGroundVL = intSetting("Invalid Ground VL", 10, 1, 50, 1,
            () -> mode.is(Mode.Custom) && invalidGround.getValue(), v -> { syncCustomModeSettings(); });

    // --- Custom mode: AlwaysInRadius ---
    private final BoolSetting alwaysInRadius = boolSetting("Always In Radius", false,
            () -> mode.is(Mode.Custom), v -> { syncCustomModeSettings(); });
    private final IntSetting alwaysInRadiusRange = intSetting("Radius Range", 20, 5, 30, 1,
            () -> mode.is(Mode.Custom) && alwaysInRadius.getValue(), v -> { syncCustomModeSettings(); });

    // --- Custom mode: Age ---
    private final BoolSetting ageCheck = boolSetting("Age Check", false,
            () -> mode.is(Mode.Custom), v -> { syncCustomModeSettings(); });
    private final IntSetting ageMinimum = intSetting("Age Minimum", 20, 0, 120, 1,
            () -> mode.is(Mode.Custom) && ageCheck.getValue(), v -> { syncCustomModeSettings(); });

    // --- Custom mode: Armor ---
    private final BoolSetting armorCheck = boolSetting("Armor Check", false,
            () -> mode.is(Mode.Custom), v -> { syncCustomModeSettings(); });

    // --- Custom mode: Name ---
    private final BoolSetting nameCheck = boolSetting("Name Check", true,
            () -> mode.is(Mode.Custom), v -> { syncCustomModeSettings(); });
    private final SettingGroup nameCheckList = settingGroup("Name Setting");
    private final IntSetting nameMinLength = intSetting("Name Min Length", 3, 1, 32, 1,
            () -> mode.is(Mode.Custom) && nameCheck.getValue(), v -> { syncCustomModeSettings(); }).group(nameCheckList);
    private final IntSetting nameMaxLength = intSetting("Name Max Length", 16, 1, 32, 1,
            () -> mode.is(Mode.Custom) && nameCheck.getValue(), v -> { syncCustomModeSettings(); }).group(nameCheckList);

    // --- Custom mode: Conditions ---
    private final MultiEnumSetting<CustomAntiBotMode.CustomCondition> customConditions = multiEnumSetting(
            "Conditions", DEFAULT_CONDITIONS, () -> mode.is(Mode.Custom),
            v -> syncCustomModeSettings());

    private AntiBot() {
        super("Anti Bot", Category.MISC);
    }

    // --- Public API ---

    /**
     * Check if an entity is detected as a bot.
     * Used by other modules (KillAura, etc.) to filter targets.
     */
    public boolean isBot(Entity entity) {
        if (!isEnabled()) return false;
        if (entity == mc.player) return false;
        if (!(entity instanceof Player player)) return false;

        return checkBot(player);
    }

    private boolean checkBot(Player player) {
        // LiteralNPC: entities not in the online player ID list
        if (literalNPC.getValue()) {
            if (mc.getConnection() != null
                    && !mc.getConnection().getOnlinePlayerIds().contains(player.getUUID())) {
                return true;
            }
        }

        // NotInTabList: entities missing from the tab list
        if (notInTabList.getValue()) {
            if (isMissingFromTabList(player)) {
                return true;
            }
        }

        // Delegate to active mode
        return getActiveMode().isBot(player);
    }

    private AntiBotMode getActiveMode() {
        return switch (mode.getValue()) {
            case Horizon -> horizonMode;
            case IntaveHeavy -> intaveHeavyMode;
            case Matrix -> matrixMode;
            default -> customMode;
        };
    }

    // --- Static helpers (used by detection modes) ---

    /**
     * Returns true if exactly one online player has the same name but different UUID.
     */
    public static boolean isADuplicate(GameProfile profile) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() == null || mc.level == null) return false;

        int count = 0;
        for (Player player : mc.level.players()) {
            GameProfile p = player.getGameProfile();
            if (Objects.equals(p.name(), profile.name())
                    && !p.id().equals(profile.id())) {
                count++;
            }
        }
        return count == 1;
    }

    /**
     * Returns true if exactly one online player matches both name and UUID.
     */
    public static boolean isGameProfileUnique(GameProfile profile) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() == null || mc.level == null) return false;

        int count = 0;
        for (Player player : mc.level.players()) {
            GameProfile p = player.getGameProfile();
            if (p.id().equals(profile.id())
                    && Objects.equals(p.name(), profile.name())) {
                count++;
            }
        }
        return count == 1;
    }

    /**
     * Checks if a player is missing from the tab list.
     */
    private boolean isMissingFromTabList(Player player) {
        if (mc.getConnection() == null) return false;

        UUID uuid = player.getUUID();
        for (var info : mc.getConnection().getListedOnlinePlayers()) {
            if (info.getProfile().id().equals(uuid)) {
                return false;
            }
        }
        return true;
    }

    // --- Event handlers ---

    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        if (nullCheck()) return;
        var packet = event.getPacket();
        getActiveMode().onPacketReceive(packet);
    }

    @EventHandler
    private void onTick(PlayerTickEvent.Pre event) {
        if (nullCheck()) return;

        // Custom mode needs tick processing for AlwaysInRadius + Armor validation
        if (mode.is(Mode.Custom)) {
            customMode.onTick();
        }
        // Matrix mode needs tick processing for armor change detection
        if (mode.is(Mode.Matrix)) {
            matrixMode.onTick();
        }
    }

    @EventHandler
    private void onAttack(AttackEntityEvent event) {
        if (nullCheck()) return;
        // Custom mode tracks hits for SWUNG/CRITTED conditions
        if (mode.is(Mode.Custom) && event.getEntity() != null) {
            customMode.onAttack(event.getEntity().getId());
        }
    }

    @EventHandler
    private void onGameJoined(GameJoinedEvent event) {
        resetModes();
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        resetModes();
    }

    // --- Lifecycle ---

    @Override
    protected void onDisable() {
        resetModes();
    }

    private void resetModes() {
        customMode.reset();
        horizonMode.reset();
        intaveHeavyMode.reset();
        matrixMode.reset();
    }

    @Override
    protected void resetCustomState() {
        resetModes();
    }

    // --- Config sync: push settings to Custom mode when they change ---

    @Override
    protected void onEnable() {
        syncCustomModeSettings();
    }

    private void syncCustomModeSettings() {
        customMode.setInvalidGroundEnabled(invalidGround.getValue());
        customMode.setVlToConsiderAsBot(invalidGroundVL.getValue());
        customMode.setAlwaysInRadiusEnabled(alwaysInRadius.getValue());
        customMode.setAlwaysInRadiusRange(alwaysInRadiusRange.getValue());
        customMode.setAgeEnabled(ageCheck.getValue());
        customMode.setAgeMinimum(ageMinimum.getValue());
        customMode.setArmorEnabled(armorCheck.getValue());
        customMode.setNameEnabled(nameCheck.getValue());
        customMode.setNameMinLength(nameMinLength.getValue());
        customMode.setNameMaxLength(nameMaxLength.getValue());

        // Sync custom conditions from MultiEnumSetting
        customMode.getCustomConditions().clear();
        customMode.getCustomConditions().addAll(customConditions.getValue());
    }

    // --- Getters for settings (used by GUI) ---
    // (The settings are already accessible via the module's settings list)
}
