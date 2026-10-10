package com.reazip.economycraft.fabric.mixin;

import com.reazip.economycraft.util.AmpersandFormat;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import java.util.function.UnaryOperator;

/**
 * Translates {@code &x} Bedrock-style format codes into styled {@link net.minecraft.network.chat.Component}s
 * when players edit signs (wall, standing, or hanging signs).
 *
 * <p><strong>Letter-budget preservation:</strong> Because {@link AmpersandFormat#parse} consumes the
 * {@code &x} tokens and applies them as component styles, the displayed text on the sign contains only
 * the actual characters typed — the format codes do not count toward the visual line budget.
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
        return signText -> {
            SignText updated = updater.apply(signText);
            return updated.asMutable().modifyLines(line -> {
                String str = line.getString();
                if (AmpersandFormat.containsCodes(str)) {
                    return AmpersandFormat.parse(str);
                }
                return line;
            }).asImmutable();
        };
    }
}
