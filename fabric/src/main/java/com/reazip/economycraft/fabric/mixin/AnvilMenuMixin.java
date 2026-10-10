package com.reazip.economycraft.fabric.mixin;

import com.reazip.economycraft.util.AmpersandFormat;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.ItemCombinerMenu;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Translates {@code &x} Bedrock-style format codes into styled {@link Component}s
 * when players rename items in an anvil.
 *
 * <p>Works for all items and blocks (furnaces, chests, tools, armor, shulkers, name tags, etc.).
 * Renamed blocks (such as furnaces) display their colored name in their container GUI when placed.
 */
@Mixin(AnvilMenu.class)
abstract class AnvilMenuMixin extends ItemCombinerMenu {

    private AnvilMenuMixin() {
        super(null, 0, null, null, null);
    }

    @Inject(method = "createResult", at = @At("TAIL"))
    private void economycraft$colorizeRenamedItem(CallbackInfo ci) {
        ItemStack result = this.resultSlots.getItem(0);
        if (result.isEmpty()) return;

        Component customName = result.get(DataComponents.CUSTOM_NAME);
        if (customName != null) {
            String raw = customName.getString();
            if (AmpersandFormat.containsCodes(raw)) {
                result.set(DataComponents.CUSTOM_NAME, AmpersandFormat.parse(raw));
            }
        }
    }
}
