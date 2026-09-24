package com.reazip.economycraft;

import com.reazip.economycraft.util.ClickKind;
import com.reazip.economycraft.util.CompatMenu;
import com.reazip.economycraft.util.ConfirmUi;
import com.reazip.economycraft.util.EconomyPermissions;
import com.reazip.economycraft.util.EconomyPermissions.Nodes;
import com.reazip.economycraft.util.EconomySounds;
import com.reazip.economycraft.util.MenuUiSupport;
import com.reazip.economycraft.util.NumberInputUi;
import com.reazip.economycraft.util.PlayerPickerUi;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.List;
import java.util.UUID;

/** Server-side toll menus; administrative overrides are deliberately confined to this UI. */
public final class TollUi {
    private TollUi() {}

    // Capture the block, not a fresh raycast at confirmation (the player may have moved).
    private record Target(String dimension, BlockPos pos) {
        static Target lookingAt(ServerPlayer player) {
            var hit = player.pick(5.0, 1.0f, false);
            if (!(hit instanceof BlockHitResult block) || hit.getType() != HitResult.Type.BLOCK) return null;
            return new Target(player.level().dimension().identifier().toString(), block.getBlockPos().immutable());
        }

        String location() { return dimension + " " + pos.toShortString(); }
    }

    public static void open(ServerPlayer player) {
        if (!allowed(player)) return;
        Target target = Target.lookingAt(player);
        if (target == null) {
            fail(player, "Look at a block within five blocks.");
            return;
        }
        open(player, target);
    }

    private static void open(ServerPlayer player, Target target) {
        if (!validTarget(player, target)) return;
        MenuUiSupport.openMenu(player, "Tolls", (id, inv) -> new TollMenu(id, inv, player, target));
    }

    private static boolean allowed(ServerPlayer player) {
        return MenuUiSupport.checkOrDeny(player, EconomyPermissions.checkCommand(player, Nodes.COMMAND_TOLL));
    }

    private static boolean validTarget(ServerPlayer player, Target target) {
        if (!allowed(player)) return false;
        if (!target.equals(Target.lookingAt(player))) {
            fail(player, "Look at the original toll block within five blocks, then reopen Tolls.");
            return false;
        }
        return true;
    }

    private static TollManager manager(ServerPlayer player) {
        return TollManager.of(player.level().getServer());
    }

    private static boolean fail(ServerPlayer player, String message) {
        EconomySounds.failure(player);
        player.sendSystemMessage(MenuUiSupport.line(message, ChatFormatting.RED));
        return false;
    }

    private static void success(ServerPlayer player, String message, Target target) {
        EconomySounds.success(player);
        player.sendSystemMessage(MenuUiSupport.line(message, ChatFormatting.GREEN));
        open(player, target);
    }

    private static String ownerName(ServerPlayer player, String owner) {
        String name = EconomyCraft.getManager(player.level().getServer()).getBestName(UUID.fromString(owner));
        return name == null || name.isBlank() ? owner : name;
    }

    private static ItemStack subject(ServerPlayer player, Target target, TollManager.Toll toll) {
        return MenuUiSupport.button(Items.OAK_FENCE_GATE, "Toll at " + target.pos().toShortString(), ChatFormatting.GOLD,
                MenuUiSupport.hint(target.dimension()),
                MenuUiSupport.hint(toll == null ? "No toll registered." : "Owner: " + ownerName(player, toll.owner)),
                MenuUiSupport.hint(toll == null ? "Choose Create to set a fee."
                        : "Net fee: " + EconomyCraft.formatMoney(toll.fee)));
    }

    // Owner is copied as a String: TollManager.transfer mutates the stored Toll instance.
    private static TollManager.Toll managedToll(ServerPlayer player, Target target, String expectedOwner, boolean admin) {
        if (!validTarget(player, target)) return null;
        if (admin && !MenuUiSupport.checkOrDeny(player, EconomyPermissions.hasAnyAdmin(player))) return null;
        TollManager.Toll current = manager(player).get(target.dimension(), target.pos());
        if (current == null || !current.owner.equals(expectedOwner)) {
            fail(player, "This toll was removed or its owner changed. Reopen Tolls.");
            return null;
        }
        if (!admin && !current.owner.equals(player.getUUID().toString())) {
            fail(player, "You do not own a toll at that block.");
            return null;
        }
        return current;
    }

    private static boolean canEditFee(ServerPlayer player, Target target, String owner) {
        if (owner == null) {
            if (!validTarget(player, target)) return false;
            if (manager(player).get(target.dimension(), target.pos()) != null)
                return fail(player, "That block is already registered as a toll.");
            int cap = EconomyConfig.get().maxActiveTollsPerPlayer;
            if (cap > 0 && manager(player).count(player.getUUID()) >= cap)
                return fail(player, "You have reached your active toll limit (" + cap + ").");
        } else if (managedToll(player, target, owner, false) == null) {
            return false;
        }
        return EconomyCommands.canModifyTollAt(player, target.pos())
                || fail(player, "You don't have permission to modify that block.");
    }

    private static void editFee(ServerPlayer player, Target target, String owner) {
        if (!canEditFee(player, target, owner)) return;
        TollManager.Toll toll = manager(player).get(target.dimension(), target.pos());
        NumberInputUi.openMoney(player, owner == null ? "Create toll" : "Change toll fee",
                subject(player, target, toll), "Net fee", toll == null ? 1 : toll.fee, 1, EconomyManager.MAX,
                owner == null ? "Create toll" : "Save fee",
                fee -> List.of(MenuUiSupport.hint(target.location()),
                        MenuUiSupport.hint("Net fee: " + EconomyCraft.formatMoney(fee)),
                        MenuUiSupport.hint("Visitors also pay the server's tax.")),
                (p, fee) -> {
                    if (!canEditFee(p, target, owner)) return;
                    if (fee < 1 || fee > EconomyManager.MAX) { fail(p, "Invalid toll fee."); return; }
                    manager(p).put(target.dimension(), target.pos(), p.getUUID(), fee);
                    success(p, "Toll " + (owner == null ? "created" : "fee set") + " for "
                            + EconomyCraft.formatMoney(fee) + " net.", target);
                }, p -> open(p, target));
    }

    private static void transfer(ServerPlayer player, Target target, String owner, boolean admin) {
        if (managedToll(player, target, owner, admin) == null) return;
        PlayerPickerUi.open(player, admin ? "Admin transfer to whom?" : "Transfer toll to whom?", admin,
                (p, recipient) -> {
                    TollManager.Toll toll = managedToll(p, target, owner, admin);
                    if (toll == null) return;
                    if (recipient.id().toString().equals(owner)) {
                        fail(p, "That player already owns this toll.");
                        transfer(p, target, owner, admin);
                        return;
                    }
                    ConfirmUi.open(p, admin ? "Admin transfer toll" : "Transfer toll", subject(p, target, toll),
                            admin ? "Confirm admin transfer" : "Confirm transfer",
                            List.of(MenuUiSupport.hint(target.location()),
                                    MenuUiSupport.hint("From: " + ownerName(p, owner)),
                                    MenuUiSupport.hint("To: " + recipient.displayName()),
                                    MenuUiSupport.hint("This gives away ownership of the toll.")),
                    actor -> {
                                if (managedToll(actor, target, owner, admin) == null) return;
                                if (!knownRecipient(actor, recipient)) {
                                    fail(actor, "That player account is no longer known. Reopen the player picker.");
                                    return;
                                }
                                int cap = EconomyConfig.get().maxActiveTollsPerPlayer;
                                if (cap > 0 && manager(actor).count(recipient.id()) >= cap) {
                                    fail(actor, "That player has reached their active toll limit (" + cap + ").");
                                    return;
                                }
                                if (!manager(actor).transfer(target.dimension(), target.pos(), UUID.fromString(owner), recipient.id())) {
                                    fail(actor, "Toll ownership could not be transferred.");
                                    return;
                                }
                                if (admin) notifyFormerOwner(actor, owner, "transferred your toll at "
                                        + target.location() + " to " + recipient.displayName() + ".");
                                success(actor, "Toll ownership transferred to " + recipient.displayName() + ".", target);
                            }, actor -> open(actor, target));
                }, p -> open(p, target));
    }

    private static boolean knownRecipient(ServerPlayer actor, PlayerPickerUi.Target recipient) {
        return actor.level().getServer().getPlayerList().getPlayer(recipient.id()) != null
                || EconomyCraft.getManager(actor.level().getServer()).getBalances().containsKey(recipient.id());
    }

    private static void remove(ServerPlayer player, Target target, String owner, boolean admin) {
        TollManager.Toll toll = managedToll(player, target, owner, admin);
        if (toll == null) return;
        ConfirmUi.open(player, admin ? "Admin remove toll" : "Remove toll", subject(player, target, toll),
                admin ? "Confirm admin remove" : "Confirm removal",
                List.of(MenuUiSupport.hint(target.location()), MenuUiSupport.hint("Owner: " + ownerName(player, owner)),
                        MenuUiSupport.hint("The block stays; its toll is removed.")),
                p -> {
                    if (managedToll(p, target, owner, admin) == null) return;
                    if (!manager(p).remove(target.dimension(), target.pos(), UUID.fromString(owner))) {
                        fail(p, "Toll could not be removed.");
                        return;
                    }
                    if (admin) notifyFormerOwner(p, owner, "removed your toll at " + target.location() + ".");
                    success(p, "Toll removed.", target);
                }, p -> open(p, target));
    }

    private static void notifyFormerOwner(ServerPlayer actor, String owner, String action) {
        ServerPlayer online = actor.level().getServer().getPlayerList().getPlayer(UUID.fromString(owner));
        if (online != null) online.sendSystemMessage(MenuUiSupport.line(
                "Admin " + actor.getName().getString() + " " + action, ChatFormatting.YELLOW));
    }

    private static final class TollMenu extends CompatMenu {
        private final ServerPlayer viewer;
        private final Target target;
        private final String owner;
        private final boolean admin;
        private final SimpleContainer container = new SimpleContainer(27);

        TollMenu(int id, Inventory inv, ServerPlayer viewer, Target target) {
            super(MenuType.GENERIC_9x3, id);
            this.viewer = viewer;
            this.target = target;
            TollManager.Toll toll = manager(viewer).get(target.dimension(), target.pos());
            this.owner = toll == null ? null : toll.owner;
            boolean own = owner != null && owner.equals(viewer.getUUID().toString());
            this.admin = owner != null && !own && EconomyPermissions.hasAnyAdmin(viewer);
            container.setItem(4, subject(viewer, target, toll));
            if (toll == null) {
                container.setItem(13, MenuUiSupport.button(Items.EMERALD, "Create", ChatFormatting.GREEN));
            } else {
                container.setItem(10, MenuUiSupport.button(Items.BOOK, "Info", ChatFormatting.AQUA));
                if (own) container.setItem(12, MenuUiSupport.button(Items.GOLD_INGOT, "Change fee", ChatFormatting.GOLD));
                if (own || admin) {
                    container.setItem(14, MenuUiSupport.button(Items.PLAYER_HEAD,
                            admin ? "Admin transfer" : "Transfer", ChatFormatting.YELLOW));
                    container.setItem(16, MenuUiSupport.button(Items.BARRIER,
                            admin ? "Admin remove" : "Remove", ChatFormatting.RED));
                }
            }
            container.setItem(18, MenuUiSupport.backButton());
            MenuUiSupport.fillBackground(container);
            for (Slot slot : MenuUiSupport.readOnlyGridSlots(container, 27)) addSlot(slot);
            for (Slot slot : MenuUiSupport.playerInventorySlots(inv, 86)) addSlot(slot);
        }

        @Override
        protected boolean onClick(int slot, int dragType, ClickKind kind, Player player) {
            if (slot < 0 || slot >= 27) return false;
            if (kind != ClickKind.PICKUP && kind != ClickKind.QUICK_MOVE) return true;
            if (slot == 18) { HubUi.open(viewer); return true; }
            if (!validTarget(viewer, target)) { viewer.closeContainer(); return true; }
            boolean own = owner != null && owner.equals(viewer.getUUID().toString());
            switch (slot) {
                case 10 -> {
                    TollManager.Toll toll = manager(viewer).get(target.dimension(), target.pos());
                    if (toll == null) { fail(viewer, "There is no toll at that block."); break; }
                    viewer.sendSystemMessage(MenuUiSupport.line("Toll at " + target.location() + "; net fee: "
                            + EconomyCraft.formatMoney(toll.fee) + "; owner: " + ownerName(viewer, toll.owner), ChatFormatting.YELLOW));
                    open(viewer, target);
                }
                case 13 -> { if (owner == null) editFee(viewer, target, null); }
                case 12 -> { if (own) editFee(viewer, target, owner); }
                case 14 -> { if (own || admin) transfer(viewer, target, owner, admin); }
                case 16 -> { if (own || admin) remove(viewer, target, owner, admin); }
                default -> { }
            }
            return true;
        }
    }
}
