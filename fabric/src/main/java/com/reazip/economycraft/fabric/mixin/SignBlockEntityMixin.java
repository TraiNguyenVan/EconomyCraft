package com.reazip.economycraft.fabric.mixin;

import com.reazip.economycraft.util.AmpersandFormat;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import java.util.function.UnaryOperator;

/**
 * Translates {@code &x} format codes into styled {@link Component}s when players edit signs
 * (wall, standing, or hanging signs).
 *
 * <p><strong>Features:</strong>
 * <ul>
 *   <li><strong>Character budget preservation:</strong> Consumes the {@code &x} prefix and
 *       applies it as component style, so format codes do not count toward the visual letter count.</li>
 *   <li><strong>Re-edit style preservation:</strong> When players re-open an existing colored sign
 *       to fix a typo or add text without typing new {@code &x} codes, the line's existing color/style
 *       is automatically preserved instead of reverting to plain text.</li>
 * </ul>
 */
@Mixin(SignBlockEntity.class)
abstract class SignBlockEntityMixin {

    @ModifyArg(
            method = "updateSignText",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/block/entity/SignBlockEntity;updateText(Ljava/util/function/UnaryOperator;Lnet/minecraft/world/level/block/entity/SignTextSlot;)Z"
            ),
            index = 0
    )
    private UnaryOperator<SignText> economycraft$colorizeSignLines(UnaryOperator<SignText> updater) {
        return oldSignText -> {
            SignText updated = updater.apply(oldSignText);
            SignText.Mutable mutable = updated.asMutable();

            for (int i = 0; i < 4; i++) {
                Component newLine = updated.getMessages(false).get(i);
                String newStr = newLine.getString();

                if (AmpersandFormat.containsCodes(newStr)) {
                    // Player explicitly typed new &x codes on this line
                    mutable.setLine(i, AmpersandFormat.parse(newStr));
                } else if (!newStr.isEmpty() && oldSignText != null && i < oldSignText.getMessages(false).size()) {
                    // Player re-edited the line without adding &x codes: preserve existing style!
                    Component oldLine = oldSignText.getMessages(false).get(i);
                    Style preservedStyle = findDominantStyle(oldLine);
                    if (preservedStyle != null && !preservedStyle.isEmpty()) {
                        mutable.setLine(i, Component.literal(newStr).setStyle(preservedStyle));
                    }
                }
            }

            return mutable.asImmutable();
        };
    }

    private static Style findDominantStyle(Component comp) {
        if (comp == null) return null;
        Style s = comp.getStyle();
        if (s != null && hasActiveFormatting(s)) {
            return s;
        }
        for (Component sibling : comp.toFlatList()) {
            Style sub = sibling.getStyle();
            if (sub != null && hasActiveFormatting(sub)) {
                return sub;
            }
        }
        return null;
    }

    private static boolean hasActiveFormatting(Style s) {
        return s.getColor() != null
                || s.isBold()
                || s.isItalic()
                || s.isUnderlined()
                || s.isStrikethrough()
                || s.isObfuscated();
    }
}
