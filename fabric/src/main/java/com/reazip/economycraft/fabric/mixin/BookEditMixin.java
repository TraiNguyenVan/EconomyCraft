package com.reazip.economycraft.fabric.mixin;

import com.reazip.economycraft.util.AmpersandFormat;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.Filterable;
import net.minecraft.server.network.FilteredText;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.WritableBookContent;
import net.minecraft.world.item.component.WrittenBookContent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * Translates {@code &x} Bedrock-style format codes into styled text when writing or
 * signing books (Book & Quill).
 *
 * <p>Supports both:
 * <ul>
 *   <li>Signed books ({@link WrittenBookContent}): pages become styled {@link Component}s
 *       and title becomes formatted text.</li>
 *   <li>Draft books ({@link WritableBookContent}): pages receive Bedrock-scoped legacy
 *       formatting codes for live preview.</li>
 * </ul>
 */
@Mixin(ServerGamePacketListenerImpl.class)
abstract class BookEditMixin {

    @Shadow
    public ServerPlayer player;

    @Inject(method = "signBook", at = @At("TAIL"))
    private void economycraft$colorizeSignedBook(FilteredText title, List<FilteredText> pages, int slot, CallbackInfo ci) {
        if (this.player == null) return;
        ItemStack stack = this.player.getInventory().getItem(slot);
        if (!stack.is(Items.WRITTEN_BOOK)) return;

        WrittenBookContent content = stack.get(DataComponents.WRITTEN_BOOK_CONTENT);
        if (content == null) return;

        boolean modified = false;
        List<Filterable<Component>> newPages = new ArrayList<>();
        for (Filterable<Component> page : content.pages()) {
            Component rawComp = page.raw();
            String text = rawComp.getString();
            if (AmpersandFormat.containsCodes(text)) {
                newPages.add(Filterable.passThrough(AmpersandFormat.parse(text)));
                modified = true;
            } else {
                newPages.add(page);
            }
        }

        Filterable<String> newTitle = content.title();
        if (AmpersandFormat.containsCodes(newTitle.raw())) {
            newTitle = Filterable.passThrough(AmpersandFormat.toLegacyString(newTitle.raw()));
            modified = true;
        }

        if (modified) {
            stack.set(DataComponents.WRITTEN_BOOK_CONTENT,
                    new WrittenBookContent(newTitle, content.author(), content.generation(), newPages, content.resolved()));
        }
    }

    @Inject(method = "updateBookContents", at = @At("TAIL"))
    private void economycraft$colorizeDraftBook(List<FilteredText> pages, int slot, CallbackInfo ci) {
        if (this.player == null) return;
        ItemStack stack = this.player.getInventory().getItem(slot);
        WritableBookContent content = stack.get(DataComponents.WRITABLE_BOOK_CONTENT);
        if (content == null) return;

        boolean modified = false;
        List<Filterable<String>> newPages = new ArrayList<>();
        for (Filterable<String> page : content.pages()) {
            String text = page.raw();
            if (AmpersandFormat.containsCodes(text)) {
                newPages.add(Filterable.passThrough(AmpersandFormat.toLegacyString(text)));
                modified = true;
            } else {
                newPages.add(page);
            }
        }

        if (modified) {
            stack.set(DataComponents.WRITABLE_BOOK_CONTENT, new WritableBookContent(newPages));
        }
    }
}
