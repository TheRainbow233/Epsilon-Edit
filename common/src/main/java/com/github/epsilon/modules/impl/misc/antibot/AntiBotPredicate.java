package com.github.epsilon.modules.impl.misc.antibot;

import net.minecraft.world.entity.player.Player;

@FunctionalInterface
public interface AntiBotPredicate {
    boolean isBot(Player entity);
}
