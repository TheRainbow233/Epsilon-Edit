package com.github.epsilon.kernel.gameplay.inventory

import com.github.epsilon.kernel.jvm.NonNullContext
import net.minecraft.core.component.DataComponents
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.ai.attributes.AttributeModifier
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.item.*
import net.minecraft.world.item.enchantment.Enchantments
import net.minecraft.world.level.block.*

/**
 * 打滑的 UwU
 */
object NavenInvUtils {

    val blacklistedBlocks: List<Block> = listOf(
        Blocks.AIR,
        Blocks.WATER,
        Blocks.LAVA,
        Blocks.ENCHANTING_TABLE,
        Blocks.GLASS_PANE,
        Blocks.GLASS_PANE,
        Blocks.IRON_BARS,
        Blocks.SNOW,
        Blocks.COAL_ORE,
        Blocks.DIAMOND_ORE,
        Blocks.EMERALD_ORE,
        Blocks.CHEST,
        Blocks.TRAPPED_CHEST,
        Blocks.TORCH,
        Blocks.ANVIL,
        Blocks.TRAPPED_CHEST,
        Blocks.NOTE_BLOCK,
        Blocks.JUKEBOX,
        Blocks.TNT,
        Blocks.GOLD_ORE,
        Blocks.IRON_ORE,
        Blocks.LAPIS_ORE,
        Blocks.STONE_PRESSURE_PLATE,
        Blocks.LIGHT_WEIGHTED_PRESSURE_PLATE,
        Blocks.HEAVY_WEIGHTED_PRESSURE_PLATE,
        Blocks.STONE_BUTTON,
        Blocks.LEVER,
        Blocks.TALL_GRASS,
        Blocks.TRIPWIRE,
        Blocks.TRIPWIRE_HOOK,
        Blocks.RAIL,
        Blocks.CORNFLOWER,
        Blocks.RED_MUSHROOM,
        Blocks.BROWN_MUSHROOM,
        Blocks.VINE,
        Blocks.SUNFLOWER,
        Blocks.LADDER,
        Blocks.FURNACE,
        Blocks.SAND,
        Blocks.CACTUS,
        Blocks.DISPENSER,
        Blocks.DROPPER,
        Blocks.CRAFTING_TABLE,
        Blocks.COBWEB,
        Blocks.PUMPKIN,
        Blocks.COBBLESTONE_WALL,
        Blocks.OAK_FENCE,
        Blocks.REDSTONE_TORCH,
        Blocks.FLOWER_POT,
    )

    private val SWORDS: Set<Item> = setOf(
        Items.WOODEN_SWORD,
        Items.STONE_SWORD,
        Items.COPPER_SWORD,
        Items.IRON_SWORD,
        Items.GOLDEN_SWORD,
        Items.DIAMOND_SWORD,
        Items.NETHERITE_SWORD,
    )

    private val PICKAXES: Set<Item> = setOf(
        Items.WOODEN_PICKAXE,
        Items.STONE_PICKAXE,
        Items.COPPER_PICKAXE,
        Items.IRON_PICKAXE,
        Items.GOLDEN_PICKAXE,
        Items.DIAMOND_PICKAXE,
        Items.NETHERITE_PICKAXE,
    )

    private val AXES: Set<Item> = setOf(
        Items.WOODEN_AXE,
        Items.STONE_AXE,
        Items.COPPER_AXE,
        Items.IRON_AXE,
        Items.GOLDEN_AXE,
        Items.DIAMOND_AXE,
        Items.NETHERITE_AXE,
    )

    private val SHOVELS: Set<Item> = setOf(
        Items.WOODEN_SHOVEL,
        Items.STONE_SHOVEL,
        Items.COPPER_SHOVEL,
        Items.IRON_SHOVEL,
        Items.GOLDEN_SHOVEL,
        Items.DIAMOND_SHOVEL,
        Items.NETHERITE_SHOVEL,
    )

    /**
     * 判断当前界面是否应禁用背包辅助功能。
     */
    fun NonNullContext.shouldDisableFeatures(): Boolean {
        return getAllItems().any { item ->
            if (item.isEmpty) {
                return@any false
            }

            val name = item.displayName.string
            name.contains("Click") ||
                    name.contains("点击") ||
                    name.contains("Right") ||
                    name.contains("Teleport") ||
                    name.contains("离开游戏") ||
                    name.contains("选择") ||
                    name.contains("再来")
        }
    }

    /**
     * 判断物品是否为带自定义名称的金头。
     */
    fun isGoldenHead(stack: ItemStack): Boolean {
        if (stack.isEmpty) {
            return false
        }

        val item = stack.item
        return item is BlockItem && item.block is SkullBlock
    }

    /**
     * 判断物品是否可装备到护甲槽位。
     */
    fun isArmor(stack: ItemStack?): Boolean {
        return getArmorSlot(stack) != null
    }

    /**
     * 获取物品对应的护甲装备槽位。
     */
    fun getArmorSlot(stack: ItemStack?): EquipmentSlot? {
        if (stack == null || stack.isEmpty) {
            return null
        }

        // ViaFabric 等协议转换可能把旧版本物品的默认组件剥掉，回退到物品原型的默认 EQUIPPABLE。
        val equippable = stack.get(DataComponents.EQUIPPABLE)
            ?: stack.item.defaultInstance.get(DataComponents.EQUIPPABLE)
            ?: return null
        val slot = equippable.slot()
        return if (slot.isArmor && slot != EquipmentSlot.BODY) slot else null
    }

    /**
     * 判断物品是否为剑。
     */
    fun isSword(stack: ItemStack?): Boolean {
        return stack != null && !stack.isEmpty && SWORDS.contains(stack.item)
    }

    /**
     * 判断物品是否为镐。
     */
    fun isPickaxe(stack: ItemStack?): Boolean {
        return stack != null && !stack.isEmpty && PICKAXES.contains(stack.item)
    }

    /**
     * 判断物品是否为斧。
     *
     * 26.3 移除了 `AxeItem`，工具类型改为由 `Item.Properties#axe` 写入数据组件，
     * 因此这里与剑、镐保持一致，使用固定物品集合判定。
     */
    fun isAxe(stack: ItemStack?): Boolean {
        return stack != null && !stack.isEmpty && AXES.contains(stack.item)
    }

    /**
     * 判断物品是否为锹。
     *
     * 26.3 与斧一样移除了 `ShovelItem`，因此使用固定物品集合判定。
     */
    fun isShovel(stack: ItemStack?): Boolean {
        return stack != null && !stack.isEmpty && SHOVELS.contains(stack.item)
    }

    /**
     * 获取指定普通背包槽位中的物品堆。
     */
    fun NonNullContext.getInventoryStack(slot: Int): ItemStack {
        val items = player.inventory.nonEquipmentItems

        if (slot < 0 || slot >= items.size) {
            return ItemStack.EMPTY
        }

        return items[slot]
    }

    /**
     * 获取指定护甲槽位中的物品堆。
     */
    fun NonNullContext.getArmorStack(slot: EquipmentSlot): ItemStack {
        return player.inventory.getItem(slot.getIndex(36))
    }

    /**
     * 获取副手槽位中的物品堆。
     */
    fun NonNullContext.getOffhandStack(): ItemStack {
        return player.inventory.getItem(Inventory.SLOT_OFFHAND)
    }

    /**
     * 判断物品是否为高等级锋利斧。
     */
    fun isSharpnessAxe(stack: ItemStack): Boolean {
        return !stack.isEmpty &&
                isAxe(stack) &&
                EnchantmentLookup.getEnchantmentLevel(stack, Enchantments.SHARPNESS) >= 8 &&
                EnchantmentLookup.getEnchantmentLevel(stack, Enchantments.SHARPNESS) < 50
    }

    /**
     * 判断物品是否为特殊高伤害金斧。
     */
    fun isGodAxe(stack: ItemStack): Boolean {
        return !stack.isEmpty &&
                stack.item === Items.GOLDEN_AXE &&
                EnchantmentLookup.getEnchantmentLevel(stack, Enchantments.SHARPNESS) > 100
    }

    /**
     * 判断物品是否为附魔金苹果。
     */
    fun isEnchantedGApple(stack: ItemStack): Boolean {
        return !stack.isEmpty && stack.item === Items.ENCHANTED_GOLDEN_APPLE
    }

    /**
     * 判断物品是否为末影水晶。
     */
    fun isEndCrystal(stack: ItemStack): Boolean {
        return !stack.isEmpty && stack.item === Items.END_CRYSTAL
    }

    /**
     * 判断物品是否为高击退黏液球。
     */
    fun isKBBall(stack: ItemStack): Boolean {
        return !stack.isEmpty &&
                stack.item === Items.SLIME_BALL &&
                EnchantmentLookup.getEnchantmentLevel(stack, Enchantments.KNOCKBACK) > 1
    }

    /**
     * 判断物品是否为高击退木棍。
     */
    fun isKBStick(stack: ItemStack): Boolean {
        return !stack.isEmpty &&
                stack.item === Items.STICK &&
                EnchantmentLookup.getEnchantmentLevel(stack, Enchantments.KNOCKBACK) > 1
    }

    /**
     * 查找普通背包区域中的空槽位。
     */
    fun NonNullContext.findEmptyInventory(): Int {
        val size = player.inventory.nonEquipmentItems.size

        for (i in 9 until size) {
            if (getInventoryStack(i).isEmpty) {
                return i
            }
        }

        return -1
    }

    /**
     * 查找快捷栏中的空槽位。
     */
    fun NonNullContext.findEmptySlot(): Int {
        for (i in 0 until 9) {
            if (getInventoryStack(i).isEmpty) {
                return i
            }
        }

        return -1
    }

    /**
     * 在快捷栏中查找指定物品。
     *
     * @return 匹配槽位；未找到时返回 null
     */
    fun NonNullContext.findItemHotbar(item: Item): Int? {
        for (i in 0 until 9) {
            if (getInventoryStack(i).item === item) {
                return i
            }
        }

        return null
    }

    /**
     * 获取弓的冲击附魔等级。
     */
    fun getPunchLevel(stack: ItemStack): Int {
        return EnchantmentLookup.getEnchantmentLevel(stack, Enchantments.PUNCH)
    }

    /**
     * 获取弓的力量附魔等级。
     */
    fun getPowerLevel(stack: ItemStack): Int {
        return EnchantmentLookup.getEnchantmentLevel(stack, Enchantments.POWER)
    }

    /**
     * 获取玩家背包中的全部物品堆。
     */
    fun NonNullContext.getAllItems(): List<ItemStack> {
        val list = ArrayList<ItemStack>()
        val inventory = player.inventory

        for (i in 0 until inventory.containerSize) {
            list.add(inventory.getItem(i))
        }

        return list
    }

    /**
     * 获取指定护甲槽位可用物品的最高评分。
     */
    fun NonNullContext.getBestArmorScore(slot: EquipmentSlot): Float {
        return getAllItems()
            .filter { item -> !item.isEmpty && getArmorSlot(item) == slot }
            .map { item -> getProtection(item) }
            .maxOrNull() ?: 0.0F
    }

    /**
     * 获取指定护甲槽位当前装备的评分。
     */
    fun NonNullContext.getCurrentArmorScore(slot: EquipmentSlot): Float {
        return getProtection(getArmorStack(slot))
    }

    /**
     * 获取背包内剑的最高伤害评分。
     */
    fun NonNullContext.getBestSwordDamage(): Float {
        return getAllItems()
            .filter { item -> isSword(item) }
            .map { item -> getSwordDamage(item) }
            .maxOrNull() ?: 0.0F
    }

    /**
     * 获取背包内伤害评分最高的剑。
     *
     * @return 评分最高的剑；不存在时返回 null
     */
    fun NonNullContext.getBestSword(): ItemStack? {
        return getAllItems()
            .filter { item -> isSword(item) }
            .maxByOrNull { item -> (getWeaponScore(item) * 100.0F).toInt() }
    }

    /** 背包内剑类武器的最高综合评分（含附魔、耐久）。 */
    fun NonNullContext.getBestSwordValue(): Float {
        return getAllItems()
            .filter { item -> isSword(item) }
            .maxOfOrNull { item -> getWeaponScore(item) } ?: 0.0F
    }

    /**
     * 按对象引用查找物品堆所在的背包槽位。
     */
    fun NonNullContext.getItemStackSlot(stack: ItemStack?): Int {
        if (stack == null) {
            return -1
        }
        val inventory = player.inventory

        for (i in 0 until inventory.containerSize) {
            if (inventory.getItem(i) === stack) {
                return i
            }
        }

        return -1
    }

    /**
     * 判断物品是否适合背包辅助逻辑处理。
     */
    fun isItemValid(stack: ItemStack): Boolean {
        if (stack.isEmpty) {
            return true
        }

        if (stack.item is PlayerHeadItem) {
            return false
        }

        val name = stack.displayName.string
        return !name.contains("Click") &&
                !name.contains("Right") &&
                !name.contains("点击") &&
                !name.contains("Teleport") &&
                !name.contains("使用") &&
                !name.contains("传送") &&
                !name.contains("再来")
    }

    /**
     * 在普通背包槽位中查找指定物品。
     */
    fun NonNullContext.getItemSlot(item: Item): Int {
        val size = player.inventory.nonEquipmentItems.size

        for (i in 0 until size) {
            if (getInventoryStack(i).item === item) {
                return i
            }
        }

        return -1
    }

    /**
     * 获取数量最多的可用投掷物。
     */
    fun NonNullContext.getBestProjectile(): ItemStack? {
        return getAllItems()
            .filter { item ->
                !item.isEmpty && (item.item === Items.EGG || item.item === Items.SNOWBALL) && isItemValid(
                    item
                )
            }
            .maxByOrNull { item -> item.count }
    }

    /**
     * 获取任意可用钓鱼竿。
     */
    fun NonNullContext.getFishingRod(): ItemStack? {
        return getAllItems()
            .firstOrNull { item -> !item.isEmpty && item.item is FishingRodItem && isItemValid(item) }
    }

    /**
     * 统计背包内可放置方块的总数。
     */
    fun NonNullContext.getBlockCountInInventory(): Int {
        return getAllItems()
            .filter { item -> !item.isEmpty && item.item is BlockItem && isValidStack(item) && isItemValid(item) }
            .sumOf { item -> item.count }
    }

    /**
     * 获取数量最少的投掷物堆。
     */
    fun NonNullContext.getWorstProjectile(): ItemStack? {
        return getAllItems()
            .filter { item -> !item.isEmpty && (item.item === Items.EGG || item.item === Items.SNOWBALL) }
            .minByOrNull { item -> item.count }
    }

    /**
     * 获取数量最少的可用箭矢堆。
     */
    fun NonNullContext.getWorstArrow(): ItemStack? {
        return getAllItems()
            .filter { item -> !item.isEmpty && item.item is ArrowItem && isItemValid(item) }
            .minByOrNull { item -> item.count }
    }

    /**
     * 获取数量最少的可用方块堆。
     */
    fun NonNullContext.getWorstBlock(): ItemStack? {
        return getAllItems()
            .filter { item -> !item.isEmpty && item.item is BlockItem && isValidStack(item) && isItemValid(item) }
            .minByOrNull { item -> item.count }
    }

    /**
     * 获取数量最多的可用方块堆。
     */
    fun NonNullContext.getBestBlock(): ItemStack? {
        return getAllItems()
            .filter { item -> !item.isEmpty && item.item is BlockItem && isValidStack(item) && isItemValid(item) }
            .maxByOrNull { item -> item.count }
    }

    /**
     * 获取背包内镐的最高工具评分。
     */
    fun NonNullContext.getBestPickaxeScore(): Float {
        return getAllItems()
            .filter { item -> isPickaxe(item) && isItemValid(item) }
            .map { item -> getToolScore(item) }
            .maxOrNull() ?: 0.0F
    }

    /**
     * 获取背包内评分最高的镐。
     */
    fun NonNullContext.getBestPickaxe(): ItemStack? {
        return getAllItems()
            .filter { item -> isPickaxe(item) && isItemValid(item) }
            .maxByOrNull { item -> (getToolScore(item) * 100.0F).toInt() }
    }

    /**
     * 获取背包内普通斧的最高工具评分。
     */
    fun NonNullContext.getBestAxeScore(): Float {
        return getAllItems()
            .filter { item -> !item.isEmpty && isAxe(item) && !isSharpnessAxe(item) && isItemValid(item) }
            .map { item -> getToolScore(item) }
            .maxOrNull() ?: 0.0F
    }

    /**
     * 获取背包内评分最高的普通斧。
     */
    fun NonNullContext.getBestAxe(): ItemStack? {
        return getAllItems()
            .filter { item -> !item.isEmpty && isAxe(item) && !isSharpnessAxe(item) && isItemValid(item) }
            .maxByOrNull { item -> (getToolScore(item) * 100.0F).toInt() }
    }

    /**
     * 获取背包内伤害评分最高的锋利斧。
     */
    fun NonNullContext.getBestShapeAxe(): ItemStack? {
        return getAllItems()
            .filter { item ->
                !item.isEmpty && isAxe(item) && isSharpnessAxe(item) && isItemValid(item) && !isGodAxe(item)
            }
            .maxByOrNull { item -> (getWeaponScore(item) * 100.0F).toInt() }
    }

    /**
     * 获取背包内锹的最高工具评分。
     */
    fun NonNullContext.getBestShovelScore(): Float {
        return getAllItems()
            .filter { item -> !item.isEmpty && isShovel(item) && isItemValid(item) }
            .map { item -> getToolScore(item) }
            .maxOrNull() ?: 0.0F
    }

    /**
     * 获取背包内评分最高的锹。
     */
    fun NonNullContext.getBestShovel(): ItemStack? {
        return getAllItems()
            .filter { item -> !item.isEmpty && isShovel(item) && isItemValid(item) }
            .maxByOrNull { item -> (getToolScore(item) * 100.0F).toInt() }
    }

    /**
     * 获取背包内弩的最高附魔评分。
     */
    fun NonNullContext.getBestCrossbowScore(): Float {
        return getAllItems()
            .filter { item -> !item.isEmpty && item.item is CrossbowItem && isItemValid(item) }
            .map { item -> getCrossbowScore(item) }
            .maxOrNull() ?: 0.0F
    }

    /**
     * 获取背包内评分最高的弩。
     */
    fun NonNullContext.getBestCrossbow(): ItemStack? {
        return getAllItems()
            .filter { item -> !item.isEmpty && item.item is CrossbowItem && isItemValid(item) }
            .maxByOrNull { item -> (getCrossbowScore(item) * 100.0F).toInt() }
    }

    /**
     * 获取背包内冲击弓的最高评分。
     */
    fun NonNullContext.getBestPunchBowScore(): Float {
        return getAllItems()
            .filter { item -> !item.isEmpty && item.item is BowItem && isItemValid(item) }
            .map { item -> getPunchBowScore(item) }
            .maxOrNull() ?: 0.0F
    }

    /**
     * 获取背包内评分最高的冲击弓。
     */
    fun NonNullContext.getBestPunchBow(): ItemStack? {
        return getAllItems()
            .filter { item -> !item.isEmpty && item.item is BowItem && isItemValid(item) }
            .maxByOrNull { item -> (getPunchBowScore(item) * 100.0F).toInt() }
    }

    /**
     * 获取背包内力量弓的最高评分。
     */
    fun NonNullContext.getBestPowerBowScore(): Float {
        return getAllItems()
            .filter { item -> !item.isEmpty && item.item is BowItem && isItemValid(item) }
            .map { item -> getPowerBowScore(item) }
            .maxOrNull() ?: 0.0F
    }

    /**
     * 获取背包内评分最高的力量弓。
     */
    fun NonNullContext.getBestPowerBow(): ItemStack? {
        return getAllItems()
            .filter { item -> !item.isEmpty && item.item is BowItem && isItemValid(item) }
            .maxByOrNull { item -> (getPowerBowScore(item) * 100.0F).toInt() }
    }

    /**
     * 判断物品是否为可用的冲击弓。
     */
    fun isPunchBow(stack: ItemStack): Boolean {
        return getPunchBowScore(stack) > 10.0F && isItemValid(stack)
    }

    /**
     * 判断物品是否为可用的力量弓。
     */
    fun isPowerBow(stack: ItemStack): Boolean {
        return getPowerBowScore(stack) > 10.0F && isItemValid(stack)
    }

    /**
     * 判断背包中是否含有指定物品。
     */
    fun NonNullContext.hasItem(item: Item): Boolean {
        return getAllItems().any { stack -> !stack.isEmpty && stack.item === item }
    }

    /**
     * 统计背包中指定物品的总数。
     */
    fun NonNullContext.getItemCount(item: Item): Int {
        return getAllItems()
            .filter { stack -> !stack.isEmpty && stack.item === item }
            .sumOf { stack -> stack.count }
    }

    /**
     * 计算弓偏向冲击用途的评分。
     */
    fun getPunchBowScore(stack: ItemStack?): Float {
        if (stack == null || stack.isEmpty || stack.item !is BowItem) {
            return 0.0F
        }

        var valence = 10.0F
        valence += EnchantmentLookup.getEnchantmentLevel(stack, Enchantments.PUNCH)
        valence += EnchantmentLookup.getEnchantmentLevel(stack, Enchantments.INFINITY)
        valence += EnchantmentLookup.getEnchantmentLevel(stack, Enchantments.FLAME)
        valence += EnchantmentLookup.getEnchantmentLevel(stack, Enchantments.POWER) / 10.0F
        return valence +
                getDurabilityRatio(stack) +
                EnchantmentLookup.getEnchantmentLevel(stack, Enchantments.UNBREAKING) * 0.3F +
                EnchantmentLookup.getEnchantmentLevel(stack, Enchantments.MENDING) * 0.5F
    }

    /**
     * 计算弓偏向伤害用途的评分。
     */
    fun getPowerBowScore(stack: ItemStack?): Float {
        if (stack == null || stack.isEmpty || stack.item !is BowItem) {
            return 0.0F
        }

        var valence = 10.0F
        valence += EnchantmentLookup.getEnchantmentLevel(stack, Enchantments.PUNCH) / 10.0F
        valence += EnchantmentLookup.getEnchantmentLevel(stack, Enchantments.INFINITY)
        valence += EnchantmentLookup.getEnchantmentLevel(stack, Enchantments.FLAME)
        valence += EnchantmentLookup.getEnchantmentLevel(stack, Enchantments.POWER)
        return valence +
                getDurabilityRatio(stack) +
                EnchantmentLookup.getEnchantmentLevel(stack, Enchantments.UNBREAKING) * 0.3F +
                EnchantmentLookup.getEnchantmentLevel(stack, Enchantments.MENDING) * 0.5F
    }

    /**
     * 根据挖掘速度和附魔计算工具评分。
     */
    fun getToolScore(stack: ItemStack?): Float {
        if (stack == null || stack.isEmpty || isGodItem(stack) || isSharpnessAxe(stack)) {
            return 0.0F
        }

        val state = when {
            isPickaxe(stack) -> Blocks.STONE.defaultBlockState()
            isAxe(stack) -> Blocks.OAK_LOG.defaultBlockState()
            isShovel(stack) -> Blocks.DIRT.defaultBlockState()
            else -> return 0.0F
        }

        var valence = stack.getDestroySpeed(state)
        // ViaFabric 等协议转换可能丢失 TOOL 组件，回退到物品原型的默认挖掘速度。
        if (valence <= 0.0F) {
            valence = stack.item.defaultInstance.getDestroySpeed(state)
        }

        val efficiency = EnchantmentLookup.getEnchantmentLevel(stack, Enchantments.EFFICIENCY)
        if (efficiency > 0) {
            valence += (efficiency * efficiency + 1).toFloat()
        }
        valence += EnchantmentLookup.getEnchantmentLevel(stack, Enchantments.FORTUNE) * 0.5F
        valence += EnchantmentLookup.getEnchantmentLevel(stack, Enchantments.SILK_TOUCH) * 0.5F
        valence += EnchantmentLookup.getEnchantmentLevel(stack, Enchantments.UNBREAKING) * 0.3F
        valence += EnchantmentLookup.getEnchantmentLevel(stack, Enchantments.MENDING) * 0.5F
        return valence + getDurabilityRatio(stack)
    }

    private fun getMainhandAttackDamage(stack: ItemStack?): Float {
        if (stack == null || stack.isEmpty) {
            return 0.0F
        }

        var damage = 0.0F
        stack.forEachModifier(EquipmentSlot.MAINHAND) { attribute, modifier ->
            if (attribute === Attributes.ATTACK_DAMAGE) {
                damage += getAttributeModifierAmount(modifier)
            }
        }
        // 旧版本物品经 ViaFabric 转换时可能没有默认 attribute_modifiers，回退到物品原型。
        if (damage == 0.0F) {
            val component = stack.get(DataComponents.ATTRIBUTE_MODIFIERS)
            if (component == null || component.modifiers.isEmpty()) {
                stack.item.defaultInstance.forEachModifier(EquipmentSlot.MAINHAND) { attribute, modifier ->
                    if (attribute === Attributes.ATTACK_DAMAGE) {
                        damage += getAttributeModifierAmount(modifier)
                    }
                }
            }
        }
        return damage
    }

    private fun getSharpnessBonus(stack: ItemStack): Float {
        val level = EnchantmentLookup.getEnchantmentLevel(stack, Enchantments.SHARPNESS)
        return if (level > 0) 0.5F * level + 0.5F else 0.0F
    }

    private fun getAttributeModifierAmount(modifier: AttributeModifier): Float {
        return modifier.amount().toFloat()
    }

    /**
     * 计算斧的主手攻击伤害评分。
     */
    fun getAxeDamage(stack: ItemStack?): Float {
        if (stack == null || stack.isEmpty || !isAxe(stack)) {
            return 0.0F
        }

        return getMainhandAttackDamage(stack) + getSharpnessBonus(stack)
    }

    /**
     * 计算剑的主手攻击伤害评分。
     */
    fun getSwordDamage(stack: ItemStack?): Float {
        if (!isSword(stack)) {
            return 0.0F
        }

        val item = stack ?: return 0.0F
        return getMainhandAttackDamage(item) + getSharpnessBonus(item)
    }

    /**
     * 近战武器（剑/斧）的综合评分：基础伤害、Sharpness、功能性附魔和剩余耐久。
     * 与 [getSwordDamage] 相比，这个分数用于“哪把武器更好”的比较，而不是纯伤害。
     */
    fun getWeaponScore(stack: ItemStack?): Float {
        if (stack == null || stack.isEmpty || (!isSword(stack) && !isAxe(stack))) {
            return 0.0F
        }

        var score = getMainhandAttackDamage(stack) + getSharpnessBonus(stack)
        score += EnchantmentLookup.getEnchantmentLevel(stack, Enchantments.FIRE_ASPECT) * 1.0F
        score += EnchantmentLookup.getEnchantmentLevel(stack, Enchantments.KNOCKBACK) * 0.5F
        score += EnchantmentLookup.getEnchantmentLevel(stack, Enchantments.LOOTING) * 0.5F
        score += EnchantmentLookup.getEnchantmentLevel(stack, Enchantments.SWEEPING_EDGE) * 0.3F
        score += (EnchantmentLookup.getEnchantmentLevel(stack, Enchantments.SMITE) +
                EnchantmentLookup.getEnchantmentLevel(stack, Enchantments.BANE_OF_ARTHROPODS)) * 0.2F
        score += EnchantmentLookup.getEnchantmentLevel(stack, Enchantments.UNBREAKING) * 0.3F
        score += EnchantmentLookup.getEnchantmentLevel(stack, Enchantments.MENDING) * 0.5F
        return score + getDurabilityRatio(stack)
    }

    /**
     * 根据护甲、韧性和附魔计算防具评分。
     */
    fun getProtection(stack: ItemStack?): Float {
        if (stack == null || stack.isEmpty) {
            return 0.0F
        }

        val slot = getArmorSlot(stack) ?: return 0.0F
        val (armor, toughness) = getArmorAttributes(stack, slot)

        return armor * 100.0F +
                toughness * 10.0F +
                EnchantmentLookup.getEnchantmentLevel(stack, Enchantments.PROTECTION)
    }

    /**
     * 玩家视角的护甲综合评分：护甲点、韧性、保护类附魔、功能附魔和剩余耐久。
     *
     * 1 点护甲和 1 EPF 都约等于 4% 减伤，所以统一乘 4 后再和附魔比较；
     * 这样“铁甲 Protection IV”会高于“钻石甲无附魔”，更接近实际收益。
     */
    fun getArmorScore(stack: ItemStack?): Float {
        if (stack == null || stack.isEmpty) {
            return 0.0F
        }

        val slot = getArmorSlot(stack) ?: return 0.0F
        val (armor, toughness) = getArmorAttributes(stack, slot)

        val protectionEpf = EnchantmentLookup.getEnchantmentLevel(stack, Enchantments.PROTECTION) +
                (EnchantmentLookup.getEnchantmentLevel(stack, Enchantments.FIRE_PROTECTION) +
                        EnchantmentLookup.getEnchantmentLevel(stack, Enchantments.BLAST_PROTECTION) +
                        EnchantmentLookup.getEnchantmentLevel(stack, Enchantments.PROJECTILE_PROTECTION) +
                        EnchantmentLookup.getEnchantmentLevel(stack, Enchantments.FEATHER_FALLING)) * 2

        val utility = (EnchantmentLookup.getEnchantmentLevel(stack, Enchantments.RESPIRATION) +
                EnchantmentLookup.getEnchantmentLevel(stack, Enchantments.AQUA_AFFINITY) +
                EnchantmentLookup.getEnchantmentLevel(stack, Enchantments.DEPTH_STRIDER) +
                EnchantmentLookup.getEnchantmentLevel(stack, Enchantments.FROST_WALKER) +
                EnchantmentLookup.getEnchantmentLevel(stack, Enchantments.SOUL_SPEED) +
                EnchantmentLookup.getEnchantmentLevel(stack, Enchantments.SWIFT_SNEAK)) * 0.5F +
                EnchantmentLookup.getEnchantmentLevel(stack, Enchantments.UNBREAKING) * 0.5F +
                EnchantmentLookup.getEnchantmentLevel(stack, Enchantments.MENDING) +
                EnchantmentLookup.getEnchantmentLevel(stack, Enchantments.THORNS) * 0.5F

        val durability = if (stack.maxDamage > 0) {
            (stack.maxDamage - stack.damageValue).toFloat() / stack.maxDamage
        } else {
            1.0F
        }

        return armor * 4.0F +
                toughness * 2.0F +
                protectionEpf * 4.0F +
                utility +
                durability
    }

    /** 背包（含已穿戴）中指定护甲槽的最高综合评分。 */
    fun NonNullContext.getBestArmorValue(slot: EquipmentSlot): Float {
        return getAllItems()
            .filter { item -> !item.isEmpty && getArmorSlot(item) == slot }
            .maxOfOrNull { item -> getArmorScore(item) } ?: 0.0F
    }

    /** 当前已穿戴护甲槽的综合评分。 */
    fun NonNullContext.getCurrentArmorValue(slot: EquipmentSlot): Float {
        return getArmorScore(getArmorStack(slot))
    }

    /** 剩余耐久比例；不可损坏物品视为满耐久。 */
    private fun getDurabilityRatio(stack: ItemStack): Float {
        val max = stack.maxDamage
        return if (max > 0) (max - stack.damageValue).toFloat() / max else 1.0F
    }

    /** 读取护甲点/韧性；组件为空时回退到物品原型默认值（兼容 ViaFabric 旧版本转换）。 */
    private fun getArmorAttributes(stack: ItemStack, slot: EquipmentSlot): Pair<Float, Float> {
        var armor = 0.0F
        var toughness = 0.0F

        fun accumulate(source: ItemStack) {
            source.forEachModifier(slot) { attribute, modifier ->
                if (attribute === Attributes.ARMOR) {
                    armor += getAttributeModifierAmount(modifier)
                } else if (attribute === Attributes.ARMOR_TOUGHNESS) {
                    toughness += getAttributeModifierAmount(modifier)
                }
            }
        }

        accumulate(stack)
        if (armor == 0.0F && toughness == 0.0F) {
            val component = stack.get(DataComponents.ATTRIBUTE_MODIFIERS)
            if (component == null || component.modifiers.isEmpty()) {
                accumulate(stack.item.defaultInstance)
            }
        }
        return armor to toughness
    }

    /**
     * 根据附魔计算弩的评分。
     */
    fun getCrossbowScore(stack: ItemStack?): Float {
        if (stack == null || stack.isEmpty || stack.item !is CrossbowItem) {
            return 0.0F
        }

        var valence = 0
        valence += EnchantmentLookup.getEnchantmentLevel(stack, Enchantments.QUICK_CHARGE)
        valence += EnchantmentLookup.getEnchantmentLevel(stack, Enchantments.MULTISHOT)
        valence += EnchantmentLookup.getEnchantmentLevel(stack, Enchantments.PIERCING)
        return valence.toFloat() +
                getDurabilityRatio(stack) +
                EnchantmentLookup.getEnchantmentLevel(stack, Enchantments.UNBREAKING) * 0.3F +
                EnchantmentLookup.getEnchantmentLevel(stack, Enchantments.MENDING) * 0.5F
    }

    /**
     * 判断物品是否属于需要保留的特殊强力物品。
     */
    fun isGodItem(stack: ItemStack): Boolean {
        if (stack.isEmpty) {
            return false
        }

        if (isAxe(stack) &&
            stack.item === Items.GOLDEN_AXE &&
            EnchantmentLookup.getEnchantmentLevel(stack, Enchantments.SHARPNESS) > 100
        ) {
            return true
        }

        if (stack.item === Items.SLIME_BALL && EnchantmentLookup.getEnchantmentLevel(
                stack,
                Enchantments.KNOCKBACK
            ) > 1
        ) {
            return true
        }

        return stack.item === Items.TOTEM_OF_UNDYING || stack.item === Items.END_CRYSTAL
    }

    /**
     * 判断普通物品是否适合保留。
     */
    fun isCommonItemUseful(stack: ItemStack): Boolean {
        if (stack.isEmpty) {
            return true
        }

        val item = stack.item
        if (item is BlockItem) {
            return item.block !== Blocks.ENCHANTING_TABLE && item.block !== Blocks.COBWEB
        }

        if (item is WritableBookItem || item is WrittenBookItem || item is KnowledgeBookItem) {
            return false
        }

        if (item is ExperienceBottleItem || item is FireworkRocketItem) {
            return false
        }

        if (item === Items.WHEAT_SEEDS || item === Items.BEETROOT_SEEDS || item === Items.MELON_SEEDS || item === Items.PUMPKIN_SEEDS) {
            return false
        }

        return item !== Items.FLINT_AND_STEEL
    }

    /**
     * 判断物品堆是否为可用于放置的有效方块堆。
     */
    fun isValidStack(stack: ItemStack?): Boolean {
        if (stack == null || stack.isEmpty || stack.item !is BlockItem) {
            return false
        }

        if (!isItemValid(stack)) {
            return false
        }

        val name = stack.displayName.string
        if (name.contains("Click") || name.contains("点击")) {
            return false
        }

        if (stack.item is StandingAndWallBlockItem) {
            return false
        }

        val block = (stack.item as BlockItem).block
        return !(block is FlowerBlock || block is BushBlock || block is NetherFungusBlock || block is CropBlock) && block !is SlabBlock && !blacklistedBlocks.contains(
            block
        )
    }

}
