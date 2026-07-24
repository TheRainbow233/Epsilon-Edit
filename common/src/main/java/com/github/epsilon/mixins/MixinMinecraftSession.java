package com.github.epsilon.mixins;

import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;


@Mixin(Minecraft.class)
public interface MixinMinecraftSession {

    @Accessor("user")
    User epsilon$getUser();

    @Accessor("user")
    void epsilon$setUser(User user);
}
