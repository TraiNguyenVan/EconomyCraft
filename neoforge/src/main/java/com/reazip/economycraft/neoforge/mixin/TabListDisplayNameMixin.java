package com.reazip.economycraft.neoforge.mixin;

import com.reazip.economycraft.EconomyCraft;
import com.reazip.economycraft.EconomyManager;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Feeds the tab list row for a player (P3-T1).
 *
 * <p>{@code ServerPlayer#getTabListDisplayName()} is a vanilla stub that returns {@code null}, and
 * {@code PlayerTabOverlay#getNameForDisplay} falls back to the profile name when it is null. Returning a
 * component here is therefore the whole trick — no packet of our own, no client mod (§1 rule 1). The change is
 * pushed with {@code UPDATE_DISPLAY_NAME} by {@code TagDisplayService#pushTabUpdate}, so a player sees a new tag
 * without relogging.
 *
 * <p><strong>Thin on purpose.</strong> D2 puts the logic in {@code common} and keeps mixins as per-loader
 * adapters; this class contains no decision-making, only the hook. The null guards are not paranoia — the client
 * never loads this mod, so this only runs server-side, but the tab row is read from packet-handling code where a
 * thrown exception disconnects a player rather than degrading one row, and a manager that is null (a tag read
 * before the server finished starting) must be inert.
 */
@Mixin(ServerPlayer.class)
abstract class TabListDisplayNameMixin {

    @Inject(method = "getTabListDisplayName", at = @At("RETURN"), cancellable = true)
    private void economycraft$tagTabRow(CallbackInfoReturnable<Component> callback) {
        var level = ((Player) (Object) this).level();
        if (level == null || level.getServer() == null) return;
        EconomyManager manager = EconomyCraft.getManager(level.getServer());
        if (manager == null) return;
        callback.setReturnValue(manager.getTagDisplay().tabDisplayName((ServerPlayer) (Object) this));
    }
}