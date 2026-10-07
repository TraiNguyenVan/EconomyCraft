package com.reazip.economycraft;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.logging.LogUtils;
import com.reazip.economycraft.admin.AdminUi;
import com.reazip.economycraft.negotiation.NegotiationStore;
import com.reazip.economycraft.negotiation.OffersHubUi;
import com.reazip.economycraft.util.AsyncFileWriter;
import com.reazip.economycraft.util.EconomyPaths;
import com.reazip.economycraft.util.EconomySounds;
import com.reazip.economycraft.util.ExpirationUtil;
import com.reazip.economycraft.util.EconomyPermissions;
import com.reazip.economycraft.util.EconomyPermissions.Nodes;
import com.reazip.economycraft.util.IdentityCompat;
import com.reazip.economycraft.util.ItemArgumentCompat;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.Commands;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.commands.arguments.item.ItemArgument;
import net.minecraft.commands.arguments.item.ItemInput;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

import java.util.*;

import java.util.concurrent.CompletableFuture;
import com.reazip.economycraft.auction.AuctionListing;
import com.reazip.economycraft.auction.AuctionManager;
import com.reazip.economycraft.auction.AuctionUi;
import com.reazip.economycraft.shop.ShopUi;
import com.reazip.economycraft.orders.OrderFulfillment;
import com.reazip.economycraft.orders.OrderManager;
import com.reazip.economycraft.orders.OrderRequest;
import com.reazip.economycraft.orders.OrdersUi;
import com.reazip.economycraft.tag.TagDisplayService;
import com.reazip.economycraft.tag.TagStyle;
import com.reazip.economycraft.tag.TagUi;
import com.reazip.economycraft.faction.FactionId;
import com.reazip.economycraft.profession.ProfessionId;
import com.reazip.economycraft.tax.TaxPolicy;
import com.reazip.economycraft.tax.TaxQuote;
import com.reazip.economycraft.tax.TaxScope;
import com.reazip.economycraft.util.PermissionCompat;
import com.reazip.economycraft.gossip.GossipCategory;
import com.reazip.economycraft.gossip.VillagerGossipListener;
import com.reazip.economycraft.util.TimeFormat;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

import org.jetbrains.annotations.Nullable;

public final class EconomyCommands {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int MAIN_INVENTORY_SLOTS = 36;
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext buildContext,
                                Commands.CommandSelection selection) {
        dispatcher.register(buildRoot(
                buildContext,
                selection,
                buildAddMoney(),
                buildSetMoney(),
                buildRemoveMoney(),
                buildRemovePlayer()
        ));

        dispatcher.register(withCommandPermission(
                buildBalance().requires(s -> EconomyConfig.get().standaloneCommands), Nodes.COMMAND_BALANCE));
        dispatcher.register(withCommandPermission(
                buildPay().requires(s -> EconomyConfig.get().standaloneCommands), Nodes.COMMAND_PAY));
        dispatcher.register(withCommandPermission(
                SellCommand.register().requires(s -> EconomyConfig.get().standaloneCommands && EconomyConfig.get().sellEnabled),
                Nodes.COMMAND_SELL));
        registerStandalone(dispatcher, buildAuction("ah"), Nodes.COMMAND_AUCTION);
        registerStandalone(dispatcher, buildAuction("auction"), Nodes.COMMAND_AUCTION);
        registerStandalone(dispatcher, buildOffers(), Nodes.COMMAND_OFFERS);
        registerStandalone(dispatcher, buildShop(), Nodes.COMMAND_SHOP);
        dispatcher.register(withCommandPermission(
                buildOrders(buildContext).requires(s -> EconomyConfig.get().standaloneCommands), Nodes.COMMAND_ORDERS));
        dispatcher.register(withCommandPermission(
                buildDeliveries().requires(s -> EconomyConfig.get().standaloneCommands), Nodes.COMMAND_DELIVERIES));
        dispatcher.register(withCommandPermission(
                buildDaily().requires(s -> EconomyConfig.get().standaloneCommands), Nodes.COMMAND_DAILY));
        dispatcher.register(withCommandPermission(
                buildTransactions().requires(s -> EconomyConfig.get().standaloneCommands), Nodes.COMMAND_TRANSACTIONS));
        registerStandalone(dispatcher, buildToll("toll"), Nodes.COMMAND_TOLL);
        registerStandalone(dispatcher, buildTag(), Nodes.COMMAND_TAG);
        dispatcher.register(withCommandPermission(
                buildJob().requires(s -> EconomyConfig.get().standaloneCommands),
                Nodes.COMMAND_TAG));
        dispatcher.register(withCommandPermission(
                buildParty().requires(s -> EconomyConfig.get().standaloneCommands && EconomyConfig.get().factions.enabled),
                Nodes.COMMAND_TAG));
        dispatcher.register(withCommandPermission(
                WorthCommand.register(buildContext).requires(s ->
                        EconomyConfig.get().standaloneCommands && EconomyConfig.get().worthEnabled),
                Nodes.COMMAND_WORTH));

        dispatcher.register(
                buildAddMoney().requires(src ->
                        EconomyPermissions.checkAdmin(src, Nodes.ADMIN_PLAYERS)
                                && EconomyConfig.get().standaloneAdminCommands
                )
        );

        dispatcher.register(
                buildSetMoney().requires(src ->
                        EconomyPermissions.checkAdmin(src, Nodes.ADMIN_PLAYERS)
                                && EconomyConfig.get().standaloneAdminCommands
                )
        );

        dispatcher.register(
                buildRemoveMoney().requires(src ->
                        EconomyPermissions.checkAdmin(src, Nodes.ADMIN_PLAYERS)
                                && EconomyConfig.get().standaloneAdminCommands
                )
        );

        dispatcher.register(
                buildRemovePlayer().requires(src ->
                        EconomyPermissions.checkAdmin(src, Nodes.ADMIN_PLAYERS)
                                && EconomyConfig.get().standaloneAdminCommands
                )
        );

        dispatcher.register(
                buildGossip().requires(src ->
                        EconomyConfig.get().standaloneAdminCommands && EconomyPermissions.hasAnyAdmin(src)
                )
        );

    }

    private static void registerStandalone(CommandDispatcher<CommandSourceStack> dispatcher,
                                           LiteralArgumentBuilder<CommandSourceStack> command, String node) {
        withCommandPermission(command, node);
        command.requires(command.getRequirement().and(src -> EconomyConfig.get().standaloneCommands));
        dispatcher.register(command);
    }

    private static LiteralArgumentBuilder<CommandSourceStack> withCommandPermission(
            LiteralArgumentBuilder<CommandSourceStack> command, String node) {
        command.requires(command.getRequirement().and(src -> EconomyPermissions.checkCommand(src, node)));
        return command;
    }

    private static LiteralArgumentBuilder<CommandSourceStack> buildRoot(
            CommandBuildContext buildContext,
            Commands.CommandSelection selection,
            LiteralArgumentBuilder<CommandSourceStack> addMoney,
            LiteralArgumentBuilder<CommandSourceStack> setMoney,
            LiteralArgumentBuilder<CommandSourceStack> removeMoney,
            LiteralArgumentBuilder<CommandSourceStack> removePlayer
    ) {
        LiteralArgumentBuilder<CommandSourceStack> root = literal("eco");

        root.executes(ctx -> openHub(ctx.getSource()));
        root.then(literal("menu")
                .requires(src -> EconomyPermissions.checkCommand(src, Nodes.COMMAND_MENU))
                .executes(ctx -> openHub(ctx.getSource())));
        root.then(literal("admin").requires(EconomyPermissions::hasAnyAdmin)
                .executes(ctx -> openAdmin(ctx.getSource())));

        root.then(withCommandPermission(buildBalance(), Nodes.COMMAND_BALANCE));
        root.then(withCommandPermission(buildPay(), Nodes.COMMAND_PAY));
        root.then(withCommandPermission(
                SellCommand.register().requires(s -> EconomyConfig.get().sellEnabled), Nodes.COMMAND_SELL));
        root.then(withCommandPermission(buildAuction("ah"), Nodes.COMMAND_AUCTION));
        root.then(withCommandPermission(buildAuction("auction"), Nodes.COMMAND_AUCTION));
        root.then(withCommandPermission(buildOffers(), Nodes.COMMAND_OFFERS));
        root.then(withCommandPermission(buildShop(), Nodes.COMMAND_SHOP));
        root.then(withCommandPermission(buildOrders(buildContext), Nodes.COMMAND_ORDERS));
        root.then(withCommandPermission(buildDeliveries(), Nodes.COMMAND_DELIVERIES));
        root.then(withCommandPermission(buildDaily(), Nodes.COMMAND_DAILY));
        root.then(withCommandPermission(buildTransactions(), Nodes.COMMAND_TRANSACTIONS));
        root.then(withCommandPermission(buildToll("toll"), Nodes.COMMAND_TOLL));
        root.then(withCommandPermission(buildTag(), Nodes.COMMAND_TAG));
        root.then(withCommandPermission(buildJob(), Nodes.COMMAND_TAG));
        root.then(withCommandPermission(
                buildParty().requires(s -> EconomyConfig.get().factions.enabled), Nodes.COMMAND_TAG));
        root.then(withCommandPermission(
                WorthCommand.register(buildContext).requires(s -> EconomyConfig.get().worthEnabled), Nodes.COMMAND_WORTH));
        root.then(buildGossip().requires(EconomyPermissions::hasAnyAdmin));

        root.then(addMoney);
        root.then(setMoney);
        root.then(removeMoney);
        root.then(removePlayer);

        if (selection != Commands.CommandSelection.DEDICATED) {
            root.then(literal("import")
                    .requires(s -> EconomyCraft.canImportSharedFolder())
                    .executes(ctx -> importSharedFolder(ctx.getSource())));
        }

        return root;
    }

    private static LiteralArgumentBuilder<CommandSourceStack> buildToll(String rootName) {
        LiteralArgumentBuilder<CommandSourceStack> root = literal(rootName);
        root.then(literal("create").then(argument("fee", LongArgumentType.longArg(1, EconomyManager.MAX))
                .executes(ctx -> tollCommand(ctx.getSource(), "create", LongArgumentType.getLong(ctx,"fee")))));
        root.then(literal("set").then(argument("fee", LongArgumentType.longArg(1, EconomyManager.MAX))
                .executes(ctx -> tollCommand(ctx.getSource(), "set", LongArgumentType.getLong(ctx,"fee")))));
        root.then(literal("transfer").then(argument("player", GameProfileArgument.gameProfile())
                .executes(ctx -> transferToll(ctx.getSource(), IdentityCompat.getArgAsPlayerRefs(ctx, "player")))));
        root.then(literal("info").executes(ctx -> tollCommand(ctx.getSource(), "info", 0)));
        root.then(literal("remove").executes(ctx -> tollCommand(ctx.getSource(), "remove", 0)));
        return root;
    }

    private static int tollCommand(CommandSourceStack source, String action, long fee) {
        ServerPlayer player = tryGetPlayer(source);
        if (player == null) { source.sendFailure(Component.literal("Only players can manage tolls.")); return 0; }
        var hit = player.pick(5.0, 1.0f, false);
        if (!(hit instanceof net.minecraft.world.phys.BlockHitResult blockHit) || hit.getType() != net.minecraft.world.phys.HitResult.Type.BLOCK) {
            source.sendFailure(Component.literal("Look at a block within five blocks.")); return 0;
        }
        var pos = blockHit.getBlockPos();
        String dimension = player.level().dimension().identifier().toString();
        TollManager tolls = TollManager.of(source.getServer());
        TollManager.Toll toll = tolls.get(dimension,pos);
        switch (action) {
            case "create" -> {
                if (!canModifyTollAt(player, pos)) {
                    source.sendFailure(Component.literal("You don't have permission to modify that block.")); return 0;
                }
                if (toll != null) { source.sendFailure(Component.literal("That block is already registered as a toll.")); return 0; }
                int cap = EconomyConfig.get().maxActiveTollsPerPlayer;
                if (cap > 0 && tolls.count(player.getUUID()) >= cap) { source.sendFailure(Component.literal("You have reached your active toll limit ("+cap+").")); return 0; }
                tolls.put(dimension,pos,player.getUUID(),fee);
                source.sendSuccess(() -> Component.literal("Toll created at "+pos.toShortString()+" for "+EconomyCraft.formatMoney(fee)+" net."), false);
            }
            case "set" -> {
                if (!canModifyTollAt(player, pos)) {
                    source.sendFailure(Component.literal("You don't have permission to modify that block.")); return 0;
                }
                if (toll == null || !toll.owner.equals(player.getUUID().toString())) { source.sendFailure(Component.literal("You do not own a toll at that block.")); return 0; }
                tolls.put(dimension,pos,player.getUUID(),fee);
                source.sendSuccess(() -> Component.literal("Toll fee set to "+EconomyCraft.formatMoney(fee)+" net."), false);
            }
            case "info" -> {
                if (toll == null) { source.sendFailure(Component.literal("There is no toll at that block.")); return 0; }
                source.sendSuccess(() -> Component.literal("Toll fee: "+EconomyCraft.formatMoney(toll.fee)+" net; owner: "+toll.owner), false);
            }
            case "remove" -> {
                if (!tolls.remove(dimension,pos,player.getUUID())) { source.sendFailure(Component.literal("You do not own a toll at that block.")); return 0; }
                source.sendSuccess(() -> Component.literal("Toll removed."), false);
            }
        }
        return 1;
    }

    static boolean canModifyTollAt(ServerPlayer player, net.minecraft.core.BlockPos pos) {
        return player.mayInteract(player.level(), pos)
                && !player.blockActionRestricted(player.level(), pos, player.gameMode.getGameModeForPlayer());
    }

    private static int transferToll(CommandSourceStack source, Collection<IdentityCompat.PlayerRef> targets) {
        ServerPlayer player = tryGetPlayer(source);
        if (player == null) { source.sendFailure(Component.literal("Only players can transfer toll ownership.")); return 0; }
        if (targets.size() != 1) { source.sendFailure(Component.literal("Choose exactly one player.")); return 0; }
        IdentityCompat.PlayerRef target = targets.iterator().next();
        if (target.id().equals(player.getUUID())) { source.sendFailure(Component.literal("You already own this toll.")); return 0; }
        EconomyManager economy = EconomyCraft.getManager(source.getServer());
        if (!knownTollRecipient(source.getServer(), economy, target)) {
            source.sendFailure(Component.literal("That player account is not known to EconomyCraft. Choose an online player or an existing account."));
            return 0;
        }
        var hit = player.pick(5.0, 1.0f, false);
        if (!(hit instanceof net.minecraft.world.phys.BlockHitResult blockHit) || hit.getType() != net.minecraft.world.phys.HitResult.Type.BLOCK) {
            source.sendFailure(Component.literal("Look at a block within five blocks.")); return 0;
        }
        var pos = blockHit.getBlockPos();
        String dimension = player.level().dimension().identifier().toString();
        TollManager tolls = TollManager.of(source.getServer());
        TollManager.Toll toll = tolls.get(dimension, pos);
        if (toll == null || !toll.owner.equals(player.getUUID().toString())) {
            source.sendFailure(Component.literal("You do not own a toll at that block.")); return 0;
        }
        int cap = EconomyConfig.get().maxActiveTollsPerPlayer;
        if (cap > 0 && tolls.count(target.id()) >= cap) {
            source.sendFailure(Component.literal("That player has reached their active toll limit (" + cap + ").")); return 0;
        }
        if (!tolls.transfer(dimension, pos, player.getUUID(), target.id())) {
            source.sendFailure(Component.literal("Toll ownership could not be transferred.")); return 0;
        }
        String targetName = target.name() == null || target.name().isBlank() ? target.id().toString() : target.name();
        source.sendSuccess(() -> Component.literal("Toll ownership transferred to " + targetName + "."), false);
        return 1;
    }

    static boolean knownTollRecipient(MinecraftServer server, EconomyManager economy, IdentityCompat.PlayerRef target) {
        return server.getPlayerList().getPlayer(target.id()) != null
                || economy.getBalances().containsKey(target.id());
    }

    private static int importSharedFolder(CommandSourceStack source) {
        MinecraftServer server = source.getServer();

        if (!EconomyPaths.hasSharedFolder(server)) {
            source.sendFailure(Component.literal("There is nothing left to import.").withStyle(ChatFormatting.RED));
            return 0;
        }

        AsyncFileWriter.flush();

        if (!EconomyPaths.importSharedFolder(server)) {
            source.sendFailure(Component.literal("Import failed. config/economycraft was left in place, so you can try again. Check the log for the reason.")
                    .withStyle(ChatFormatting.RED));
            return 0;
        }

        EconomyCraft.reloadFromDisk(server);
        resyncCommands(server);

        source.sendSuccess(() -> Component.literal("Imported the old settings, prices and economy into this world. The old folder is now config/economycraft_imported.")
                .withStyle(ChatFormatting.GREEN), false);
        return 1;
    }

    public static void resyncCommands(MinecraftServer server) {
        if (server == null) return;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            server.getCommands().sendCommands(player);
        }
    }

    private static int openHub(CommandSourceStack source) {
        ServerPlayer player = tryGetPlayer(source);
        if (player == null) {
            source.sendFailure(Component.literal("Only players can open the menu.").withStyle(ChatFormatting.RED));
            return 0;
        }
        if (!EconomyPermissions.checkCommand(source, Nodes.COMMAND_MENU)) {
            source.sendFailure(Component.literal("You don't have permission for that.").withStyle(ChatFormatting.RED));
            return 0;
        }
        try {
            HubUi.open(player);
            return 1;
        } catch (Exception e) {
            LOGGER.error("[EconomyCraft] Failed to open the menu for {}", player.getDisplayName().getString(), e);
            source.sendFailure(Component.literal("Failed to open the menu. Check server logs."));
            return 0;
        }
    }

    private static int openAdmin(CommandSourceStack source) {
        ServerPlayer player = tryGetPlayer(source);
        if (player == null) {
            source.sendFailure(Component.literal("Only players can open the admin menu.").withStyle(ChatFormatting.RED));
            return 0;
        }
        try {
            AdminUi.open(player, EconomyCraft.getManager(source.getServer()));
            return 1;
        } catch (Exception e) {
            LOGGER.error("[EconomyCraft] Failed to open the admin menu for {}", player.getDisplayName().getString(), e);
            source.sendFailure(Component.literal("Failed to open the admin menu. Check server logs."));
            return 0;
        }
    }

    private static LiteralArgumentBuilder<CommandSourceStack> buildBalance() {
        return literal("bal")
                .executes(ctx -> showBalance(IdentityCompat.of(ctx.getSource().getPlayerOrException()), ctx.getSource()))
                .then(argument("target", StringArgumentType.word())
                        .suggests((ctx, builder) -> suggestPlayers(ctx.getSource(), builder))
                        .executes(ctx -> showBalance(StringArgumentType.getString(ctx, "target"), ctx.getSource())));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> buildPay() {
        String payUsage = "/pay <player> <amount>";
        return literal("pay")
                .executes(ctx -> usage(ctx.getSource(), payUsage))
                .then(argument("player", StringArgumentType.word())
                        .suggests((ctx, builder) -> suggestPlayers(ctx.getSource(), builder))
                        .executes(ctx -> usage(ctx.getSource(), payUsage))
                        .then(argument("amount", StringArgumentType.word())
                                .executes(ctx -> {
                                    Long amount = parseAmount(ctx.getSource(), StringArgumentType.getString(ctx, "amount"), 1, EconomyManager.MAX);
                                    if (amount == null) return 0;
                                    return pay(ctx.getSource().getPlayerOrException(),
                                            StringArgumentType.getString(ctx, "player"),
                                            amount, ctx.getSource());
                                })));
    }

    private static int usage(CommandSourceStack source, String usage) {
        source.sendFailure(Component.literal("Usage: " + usage).withStyle(ChatFormatting.RED));
        return 0;
    }

    private static @Nullable Long parseAmount(CommandSourceStack source, String raw, long min, long max) {
        Long amount = EconomyCraft.parseMoneyShort(raw);
        if (amount == null) {
            source.sendFailure(Component.literal("Invalid amount: " + raw + " (try 1000, 1.5k, 20k, 234M, ...)")
                    .withStyle(ChatFormatting.RED));
            return null;
        }
        if (amount < min || amount > max) {
            source.sendFailure(Component.literal("Amount must be between " + min + " and " + max)
                    .withStyle(ChatFormatting.RED));
            return null;
        }
        return amount;
    }

    private static int showBalance(IdentityCompat.PlayerRef target, CommandSourceStack source) {
        EconomyManager manager = EconomyCraft.getManager(source.getServer());
        Long bal = manager.getBalance(target.id(), false);
        if (bal == null) {
            source.sendFailure(Component.literal("Unknown player").withStyle(ChatFormatting.RED));
            return 0;
        }

        ServerPlayer executor = tryGetPlayer(source);

        Component msg;
        if (executor != null && executor.getUUID().equals(target.id())) {
            msg = Component.literal("Balance: " + EconomyCraft.formatMoney(bal))
                    .withStyle(ChatFormatting.YELLOW);
        } else {
            msg = Component.literal(target.name() + "'s balance: " + EconomyCraft.formatMoney(bal))
                    .withStyle(ChatFormatting.YELLOW);
        }

        reply(source, executor, msg, false);

        return 1;
    }

    private static int showBalance(String targetName, CommandSourceStack source) {
        EconomyManager manager = EconomyCraft.getManager(source.getServer());
        UUID targetId = manager.tryResolveUuidByName(targetName);
        if (targetId == null) {
            source.sendFailure(Component.literal("Unknown player").withStyle(ChatFormatting.RED));
            return 0;
        }
        if (manager.getBalance(targetId, false) == null) {
            source.sendFailure(Component.literal("Unknown player").withStyle(ChatFormatting.RED));
            return 0;
        }

        String resolvedName = manager.getBestName(targetId);
        return showBalance(new IdentityCompat.PlayerRef(targetId,
                resolvedName == null || resolvedName.isBlank() ? targetName : resolvedName), source);
    }

    private static int pay(ServerPlayer from, String target, long amount, CommandSourceStack source) {
        var server = source.getServer();
        EconomyManager manager = EconomyCraft.getManager(server);

        ServerPlayer toOnline = server.getPlayerList().getPlayerByName(target);
        UUID toId = (toOnline != null) ? toOnline.getUUID() : null;

        if (toId == null) {
            try { toId = UUID.fromString(target); } catch (IllegalArgumentException ignored) {}
        }

        if (toId == null) {
            toId = manager.tryResolveUuidByName(target);
        }

        if (toId == null) {
            EconomySounds.failure(from);
            source.sendFailure(Component.literal("Unknown player").withStyle(ChatFormatting.RED));
            return 0;
        }

        if (from.getUUID().equals(toId)) {
            EconomySounds.failure(from);
            source.sendFailure(Component.literal("You cannot pay yourself").withStyle(ChatFormatting.RED));
            return 0;
        }

        if (manager.getBalance(toId, false) == null) {
            EconomySounds.failure(from);
            source.sendFailure(Component.literal("Unknown player").withStyle(ChatFormatting.RED));
            return 0;
        }

        String displayName = toOnline != null ? IdentityCompat.of(toOnline).name() : manager.getBestName(toId);
        if (displayName == null || displayName.isBlank()) {
            EconomySounds.failure(from);
            source.sendFailure(Component.literal("Unknown player").withStyle(ChatFormatting.RED));
            return 0;
        }

        TaxQuote quote = TaxPolicy.resolve(TaxScope.TRANSACTION_PAY, amount, from.getUUID(), manager);
        long debit = quote.total();
        String detail = "Payment to " + displayName;
        var payment = manager.transferMoney(from.getUUID(), toId, debit, amount, EconomySources.PLAYER_PAYMENT, detail);
        if (payment.successful()) {

            ServerPlayer executor = tryGetPlayer(source);
            if (executor != null) EconomySounds.success(executor);

            Component msg = Component.literal("Paid " + EconomyCraft.formatMoney(amount) + " to " + displayName)
                    .withStyle(ChatFormatting.GREEN);

            reply(source, executor, msg, false);

            if (toOnline != null) {
                EconomySounds.moneyReceived(toOnline);
                toOnline.sendSystemMessage(
                        Component.literal(from.getName().getString() + " sent you " + EconomyCraft.formatMoney(amount))
                                .withStyle(ChatFormatting.GREEN)
                );
            }
        } else {
            EconomySounds.failure(from);
            String message = payment.status() == com.reazip.economycraft.api.v1.BalanceMutationStatus.MAX_BALANCE_EXCEEDED
                    ? "Recipient cannot receive that much money"
                    : "Not enough balance";
            source.sendFailure(Component.literal(message).withStyle(ChatFormatting.RED));
        }
        return 1;
    }

    private static LiteralArgumentBuilder<CommandSourceStack> buildAddMoney() {
        return literal("addmoney").requires(src -> EconomyPermissions.checkAdmin(src, Nodes.ADMIN_PLAYERS))
                .then(argument("targets", GameProfileArgument.gameProfile())
                        .then(argument("amount", StringArgumentType.word())
                                .executes(ctx -> {
                                    Long amount = parseAmount(ctx.getSource(), StringArgumentType.getString(ctx, "amount"), 1, EconomyManager.MAX);
                                    if (amount == null) return 0;
                                    return addMoney(
                                            IdentityCompat.getArgAsPlayerRefs(ctx, "targets"),
                                            amount,
                                            ctx.getSource());
                                })));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> buildSetMoney() {
        return literal("setmoney").requires(src -> EconomyPermissions.checkAdmin(src, Nodes.ADMIN_PLAYERS))
                .then(argument("targets", GameProfileArgument.gameProfile())
                        .then(argument("amount", StringArgumentType.word())
                                .executes(ctx -> {
                                    Long amount = parseAmount(ctx.getSource(), StringArgumentType.getString(ctx, "amount"), 0, EconomyManager.MAX);
                                    if (amount == null) return 0;
                                    return setMoney(
                                            IdentityCompat.getArgAsPlayerRefs(ctx, "targets"),
                                            amount,
                                            ctx.getSource());
                                })));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> buildRemoveMoney() {
        return literal("removemoney").requires(src -> EconomyPermissions.checkAdmin(src, Nodes.ADMIN_PLAYERS))
                .then(argument("targets", GameProfileArgument.gameProfile())
                        .executes(ctx -> removeMoney(
                                IdentityCompat.getArgAsPlayerRefs(ctx, "targets"),
                                null,
                                ctx.getSource()))
                        .then(argument("amount", StringArgumentType.word())
                                .executes(ctx -> {
                                    Long amount = parseAmount(ctx.getSource(), StringArgumentType.getString(ctx, "amount"), 1, EconomyManager.MAX);
                                    if (amount == null) return 0;
                                    return removeMoney(
                                            IdentityCompat.getArgAsPlayerRefs(ctx, "targets"),
                                            amount,
                                            ctx.getSource());
                                })));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> buildRemovePlayer() {
        return literal("removeplayer").requires(src -> EconomyPermissions.checkAdmin(src, Nodes.ADMIN_PLAYERS))
                .then(argument("targets", GameProfileArgument.gameProfile())
                        .executes(ctx -> removePlayers(
                                IdentityCompat.getArgAsPlayerRefs(ctx, "targets"),
                                ctx.getSource())));
    }

    private static int addMoney(Collection<IdentityCompat.PlayerRef> profiles, long amount, CommandSourceStack source) {
        if (profiles.isEmpty()) {
            source.sendFailure(Component.literal("No targets matched").withStyle(ChatFormatting.RED));
            return 0;
        }

        EconomyManager manager = EconomyCraft.getManager(source.getServer());
        ServerPlayer executor = tryGetPlayer(source);

        if (profiles.size() == 1) {
            var p = profiles.iterator().next();
            var result = manager.addMoney(p.id(), amount, EconomySources.ADMIN_ADD);
            if (!result.successful()) {
                source.sendFailure(Component.literal("Could not add money: maximum balance exceeded")
                        .withStyle(ChatFormatting.RED));
                return 0;
            }

            Component msg = Component.literal(
                            "Added " + EconomyCraft.formatMoney(amount) + " to " + p.name() + "'s balance.")
                    .withStyle(ChatFormatting.GREEN);

            reply(source, executor, msg, true);

            return 1;
        }

        int count = 0;
        for (var p : profiles) {
            if (manager.addMoney(p.id(), amount, EconomySources.ADMIN_ADD).successful()) count++;
        }

        if (count == 0) {
            source.sendFailure(Component.literal("Could not add money: all target balances would exceed the maximum")
                    .withStyle(ChatFormatting.RED));
            return 0;
        }

        Component msg = Component.literal(
                        "Added " + EconomyCraft.formatMoney(amount) + " to " + count + " player" + (count > 1 ? "s" : ""))
                .withStyle(ChatFormatting.GREEN);

        reply(source, executor, msg, true);

        return count;
    }

    private static int setMoney(Collection<IdentityCompat.PlayerRef> profiles, long amount, CommandSourceStack source) {
        if (profiles.isEmpty()) {
            source.sendFailure(Component.literal("No targets matched").withStyle(ChatFormatting.RED));
            return 0;
        }

        EconomyManager manager = EconomyCraft.getManager(source.getServer());
        ServerPlayer executor = tryGetPlayer(source);

        if (profiles.size() == 1) {
            var p = profiles.iterator().next();
            manager.setMoney(p.id(), amount, EconomySources.ADMIN_SET);

            Component msg = Component.literal(
                            "Set balance of " + p.name() + " to " + EconomyCraft.formatMoney(amount))
                    .withStyle(ChatFormatting.GREEN);

            reply(source, executor, msg, true);

            return 1;
        }

        for (var p : profiles) {
            manager.setMoney(p.id(), amount, EconomySources.ADMIN_SET);
        }

        int count = profiles.size();

        Component msg = Component.literal(
                        "Set balance to " + EconomyCraft.formatMoney(amount) + " for " + count + " player" + (count > 1 ? "s" : ""))
                .withStyle(ChatFormatting.GREEN);

        reply(source, executor, msg, true);

        return count;
    }

    private static int removeMoney(Collection<IdentityCompat.PlayerRef> profiles, Long amount, CommandSourceStack source) {
        if (profiles.isEmpty()) {
            source.sendFailure(Component.literal("No targets matched").withStyle(ChatFormatting.RED));
            return 0;
        }

        EconomyManager manager = EconomyCraft.getManager(source.getServer());
        ServerPlayer executor = tryGetPlayer(source);

        int success = 0;

        if (profiles.size() == 1) {
            var p = profiles.iterator().next();
            UUID id = p.id();

            if (amount == null) {
                if (!manager.getBalances().containsKey(id)) {
                    source.sendFailure(Component.literal(
                                    "Failed to remove all money from " + p.name() + "'s balance. Unknown player.")
                            .withStyle(ChatFormatting.RED));
                    return 1;
                }
                manager.setMoney(id, 0L, EconomySources.ADMIN_REMOVE);
                Component msg = Component.literal(
                                "Removed all money from " + p.name() + "'s balance.")
                        .withStyle(ChatFormatting.GREEN);
                reply(source, executor, msg, true);
                return 1;
            }

            if (!manager.removeMoney(id, amount, EconomySources.ADMIN_REMOVE).successful()) {
                source.sendFailure(Component.literal(
                                "Failed to remove " + EconomyCraft.formatMoney(amount) + " from " + p.name() + "'s balance due to insufficient funds.")
                        .withStyle(ChatFormatting.RED));
                return 1;
            }

            Component msg = Component.literal(
                            "Successfully removed " + EconomyCraft.formatMoney(amount) + " from " + p.name() + "'s balance.")
                    .withStyle(ChatFormatting.GREEN);
            reply(source, executor, msg, true);
            return 1;
        }

        for (var p : profiles) {
            UUID id = p.id();
            if (amount == null) {
                if (!manager.getBalances().containsKey(id)) {
                    source.sendFailure(Component.literal(
                                    "Failed to remove all money from " + p.name() + "'s balance. Unknown player.")
                            .withStyle(ChatFormatting.RED));
                    continue;
                }
                manager.setMoney(id, 0L, EconomySources.ADMIN_REMOVE);
                success++;
            } else {
                if (manager.removeMoney(id, amount, EconomySources.ADMIN_REMOVE).successful()) {
                    success++;
                } else {
                    source.sendFailure(Component.literal(
                                    "Failed to remove " + EconomyCraft.formatMoney(amount) + " from " + p.name() + "'s balance due to insufficient funds.")
                            .withStyle(ChatFormatting.RED));
                }
            }
        }

        if (success > 0) {
            Component msg;
            if (amount == null) {
                msg = Component.literal(
                                "Removed all money from " + success + " player" + (success > 1 ? "s" : "") + ".")
                        .withStyle(ChatFormatting.GREEN);
            } else {
                msg = Component.literal(
                                "Successfully removed " + EconomyCraft.formatMoney(amount) + " from " + success + " player" + (success > 1 ? "s" : "") + ".")
                        .withStyle(ChatFormatting.GREEN);
            }
            reply(source, executor, msg, true);
        }

        return profiles.size();
    }

    private static int removePlayers(Collection<IdentityCompat.PlayerRef> profiles, CommandSourceStack source) {
        if (profiles.isEmpty()) {
            source.sendFailure(Component.literal("No targets matched").withStyle(ChatFormatting.RED));
            return 0;
        }

        EconomyManager manager = EconomyCraft.getManager(source.getServer());
        ServerPlayer executor = tryGetPlayer(source);

        if (profiles.size() == 1) {
            var p = profiles.iterator().next();
            manager.removePlayer(p.id());

            Component msg = Component.literal("Removed " + p.name() + " from economy")
                    .withStyle(ChatFormatting.GREEN);

            reply(source, executor, msg, true);

            return 1;
        }

        for (var p : profiles) {
            manager.removePlayer(p.id());
        }

        int count = profiles.size();

        Component msg = Component.literal(
                        "Removed " + count + " player" + (count > 1 ? "s" : "") + " from economy")
                .withStyle(ChatFormatting.GREEN);

        reply(source, executor, msg, true);

        return count;
    }

    private static LiteralArgumentBuilder<CommandSourceStack> buildAuction(String command) {
        return literal(command)
                .requires(src -> EconomyConfig.get().auctionEnabled)
                .executes(ctx -> openAuction(ctx.getSource().getPlayerOrException(), ctx.getSource()))
                .then(literal("list")
                        .executes(ctx -> usage(ctx.getSource(), "/" + command + " list <price> [<amount>]"))
                        .then(argument("price", LongArgumentType.longArg(1, EconomyManager.MAX))
                                .executes(ctx -> listAuctionItem(ctx.getSource().getPlayerOrException(),
                                         LongArgumentType.getLong(ctx, "price"), -1,
                                         ctx.getSource()))
                                .then(argument("amount", IntegerArgumentType.integer(1))
                                        .executes(ctx -> listAuctionItem(ctx.getSource().getPlayerOrException(),
                                                 LongArgumentType.getLong(ctx, "price"),
                                                 IntegerArgumentType.getInteger(ctx, "amount"),
                                                 ctx.getSource())))))
                .then(literal("search")
                        .executes(ctx -> usage(ctx.getSource(), "/" + command + " search <query>"))
                        .then(argument("query", StringArgumentType.greedyString())
                                .executes(ctx -> searchAuction(ctx.getSource().getPlayerOrException(),
                                         StringArgumentType.getString(ctx, "query"),
                                         ctx.getSource()))));
    }

    private static int openAuction(ServerPlayer player, CommandSourceStack source) {
        if (!EconomyConfig.get().auctionEnabled) {
            source.sendFailure(Component.literal("The auction house is disabled.").withStyle(ChatFormatting.RED));
            return 0;
        }
        try {
            AuctionUi.open(player, EconomyCraft.getManager(source.getServer()).getAuctions());
            return 1;
        } catch (Exception e) {
            LOGGER.error("[EconomyCraft] Failed to open the auction house for {}", player.getDisplayName().getString(), e);
            source.sendFailure(Component.literal("Failed to open auction house. Check server logs."));
            return 0;
        }
    }

    private static int searchAuction(ServerPlayer player, String query, CommandSourceStack source) {
        if (!EconomyConfig.get().auctionEnabled) {
            source.sendFailure(Component.literal("The auction house is disabled.").withStyle(ChatFormatting.RED));
            return 0;
        }
        try {
            AuctionUi.openSearch(player, EconomyCraft.getManager(source.getServer()).getAuctions(), query);
            return 1;
        } catch (Exception e) {
            LOGGER.error("[EconomyCraft] Failed to search the auction house for {}", player.getDisplayName().getString(), e);
            source.sendFailure(Component.literal("Failed to open auction house. Check server logs."));
            return 0;
        }
    }

    private static int listAuctionItem(ServerPlayer player, long price, int amount, CommandSourceStack source) {
        if (!EconomyConfig.get().auctionEnabled) {
            EconomySounds.failure(player);
            source.sendFailure(Component.literal("The auction house is disabled.").withStyle(ChatFormatting.RED));
            return 0;
        }
        ItemStack hand = player.getMainHandItem();
        if (hand.isEmpty()) {
            EconomySounds.failure(player);
            source.sendFailure(Component.literal("Hold the item to list in your hand").withStyle(ChatFormatting.RED));
            return 0;
        }

        int count = amount > 0 ? amount : Math.min(hand.getCount(), hand.getMaxStackSize());
        if (count > hand.getCount()) {
            EconomySounds.failure(player);
            source.sendFailure(Component.literal("You only have " + hand.getCount() + ".").withStyle(ChatFormatting.RED));
            return 0;
        }

        AuctionManager auctions = EconomyCraft.getManager(source.getServer()).getAuctions();
        if (auctions.hasReachedLimit(player.getUUID())) {
            EconomySounds.failure(player);
            source.sendFailure(Component.literal("You have reached your limit of "
                    + auctions.getEffectiveLimit(player.getUUID()) + " active listing(s).").withStyle(ChatFormatting.RED));
            return 0;
        }
        AuctionListing listing = new AuctionListing();
        listing.seller = player.getUUID();
        listing.price = price;
        listing.item = hand.copyWithCount(count);
        listing.createdAt = System.currentTimeMillis();
        listing.expiresAt = ExpirationUtil.expiresAt(listing.createdAt, EconomyConfig.get().auctionExpirationHours);
        hand.shrink(count);
        auctions.addListing(listing);

        TaxQuote quote = TaxPolicy.quoteForSale(TaxScope.TRANSACTION_AUCTION_BUY, price, player.getUUID(),
                EconomyCraft.getManager(source.getServer()));

        Component msg = Component.literal("Listed item for " + EconomyCraft.formatMoney(price) +
                        AuctionUi.buyerTaxSuffix(quote))
                .withStyle(ChatFormatting.GREEN);

        EconomySounds.success(player);
        player.sendSystemMessage(msg);

        return 1;
    }

    private static LiteralArgumentBuilder<CommandSourceStack> buildShop() {
        return literal("shop")
                .requires(src -> EconomyConfig.get().shopEnabled)
                .executes(ctx -> openShop(ctx.getSource().getPlayerOrException(), ctx.getSource(), null))
                .then(argument("category", StringArgumentType.greedyString())
                        .suggests((ctx, builder) -> suggestShopCategories(ctx.getSource(), builder))
                        .executes(ctx -> openShop(
                                ctx.getSource().getPlayerOrException(),
                                ctx.getSource(),
                                StringArgumentType.getString(ctx, "category")
                        )))
                .then(literal("search")
                        .executes(ctx -> usage(ctx.getSource(), "/shop search <query>"))
                        .then(argument("query", StringArgumentType.greedyString())
                                .executes(ctx -> searchShop(ctx.getSource().getPlayerOrException(),
                                        StringArgumentType.getString(ctx, "query"),
                                        ctx.getSource()))));
    }

    private static int openShop(ServerPlayer player, CommandSourceStack source, @Nullable String category) {
        if (!EconomyConfig.get().shopEnabled) {
            source.sendFailure(Component.literal("Shop is disabled.").withStyle(ChatFormatting.RED));
            return 0;
        }
        EconomyManager manager = EconomyCraft.getManager(source.getServer());
        try {
            ShopUi.open(player, manager, category);
            return 1;
        } catch (Exception e) {
            LOGGER.error("[EconomyCraft] Failed to open /shop for {} (category={})",
                    player.getDisplayName().getString(), category, e);
            source.sendFailure(Component.literal("Failed to open shop. Check server logs."));
            return 0;
        }
    }

    private static int searchShop(ServerPlayer player, String query, CommandSourceStack source) {
        if (!EconomyConfig.get().shopEnabled) {
            source.sendFailure(Component.literal("Shop is disabled.").withStyle(ChatFormatting.RED));
            return 0;
        }
        try {
            ShopUi.openSearch(player, EconomyCraft.getManager(source.getServer()), query);
            return 1;
        } catch (Exception e) {
            LOGGER.error("[EconomyCraft] Failed to search /shop for {}", player.getDisplayName().getString(), e);
            source.sendFailure(Component.literal("Failed to open shop. Check server logs."));
            return 0;
        }
    }

    private static LiteralArgumentBuilder<CommandSourceStack> buildOrders(CommandBuildContext buildContext) {
        String requestUsage = "/orders request <item> <amount> <price>";
        return literal("orders")
                .executes(ctx -> openOrders(ctx.getSource().getPlayerOrException(), ctx.getSource()))
                .then(literal("request")
                        .requires(src -> EconomyConfig.get().ordersEnabled)
                        .executes(ctx -> usage(ctx.getSource(), requestUsage))
                        .then(argument("item", ItemArgument.item(buildContext))
                                .executes(ctx -> usage(ctx.getSource(), requestUsage))
                                .then(argument("amount", LongArgumentType.longArg(1, EconomyManager.MAX))
                                        .executes(ctx -> usage(ctx.getSource(), requestUsage))
                                        .then(argument("price", LongArgumentType.longArg(1, EconomyManager.MAX))
                                                .executes(ctx -> requestItem(ctx.getSource().getPlayerOrException(),
                                                        ItemArgument.getItem(ctx, "item"),
                                                        (int) Math.min(LongArgumentType.getLong(ctx, "amount"), EconomyManager.MAX),
                                                        LongArgumentType.getLong(ctx, "price"),
                                                        ctx.getSource()))))))
                .then(literal("search")
                        .requires(src -> EconomyConfig.get().ordersEnabled)
                        .executes(ctx -> usage(ctx.getSource(), "/orders search <query>"))
                        .then(argument("query", StringArgumentType.greedyString())
                                .executes(ctx -> searchOrders(ctx.getSource().getPlayerOrException(),
                                        StringArgumentType.getString(ctx, "query"),
                                        ctx.getSource()))));
    }

    private static int openOrders(ServerPlayer player, CommandSourceStack source) {
        if (!EconomyConfig.get().ordersEnabled) {
            source.sendFailure(Component.literal("Orders are disabled.").withStyle(ChatFormatting.RED));
            return 0;
        }
        try {
            OrdersUi.open(player, EconomyCraft.getManager(source.getServer()));
            return 1;
        } catch (Exception e) {
            LOGGER.error("[EconomyCraft] Failed to open /orders for {}", player.getDisplayName().getString(), e);
            source.sendFailure(Component.literal("Failed to open orders. Check server logs."));
            return 0;
        }
    }

    private static int requestItem(ServerPlayer player, ItemInput input, int amount, long price, CommandSourceStack source) {
        ItemStack item;
        try {
            item = ItemArgumentCompat.createItemStack(input, 1);
        } catch (CommandSyntaxException e) {
            EconomySounds.failure(player);
            source.sendFailure(Component.literal("Invalid item").withStyle(ChatFormatting.RED));
            return 0;
        }
        EconomyManager eco = EconomyCraft.getManager(source.getServer());
        OrderManager orders = eco.getOrders();
        if (orders.hasReachedLimit(player.getUUID())) {
            EconomySounds.failure(player);
            source.sendFailure(Component.literal("You have reached your limit of "
                    + orders.getEffectiveLimit(player.getUUID()) + " active order request(s).").withStyle(ChatFormatting.RED));
            return 0;
        }
        int maxAmount = MAIN_INVENTORY_SLOTS * item.getMaxStackSize();
        if (amount > maxAmount) {
            EconomySounds.failure(player);
            source.sendFailure(Component.literal("Amount exceeds " + MAIN_INVENTORY_SLOTS + " stacks (max " + maxAmount + ")").withStyle(ChatFormatting.RED));
            return 0;
        }

        OrderRequest r = OrderFulfillment.createEscrowedRequest(eco, player.getUUID(), item, amount, price);
        if (r == null) {
            EconomySounds.failure(player);
            source.sendFailure(Component.literal("You can't afford to reserve " + EconomyCraft.formatMoney(price)).withStyle(ChatFormatting.RED));
            return 0;
        }
        long tax = TaxPolicy.tax(TaxScope.TRANSACTION_ORDER, price);

        Component msg = Component.literal("Created request" +
                (tax > 0 ? " (fulfiller receives " + EconomyCraft.formatMoney(price - tax) + ")" : ""))
                .withStyle(ChatFormatting.GREEN);
        EconomySounds.success(player);
        player.sendSystemMessage(msg);

        return 1;
    }

    /**
     * {@code /eco offers} opens the hub; {@code /eco offers ah <id>} and {@code /eco offers order
     * <id>} deep-link into one target's review screen, which is what the "Review it now" click in
     * an offer notification runs. The subcommand names match the ids the messages print
     * ("listing #12", "request #7") so a player reading one can type the other.
     */
    private static LiteralArgumentBuilder<CommandSourceStack> buildOffers() {
        return literal("offers")
                .executes(ctx -> openOffersHub(ctx.getSource(), null, 0))
                .then(literal("ah")
                        .then(argument("id", IntegerArgumentType.integer(1))
                                .executes(ctx -> openOffersHub(ctx.getSource(),
                                        NegotiationStore.Kind.AH,
                                        IntegerArgumentType.getInteger(ctx, "id")))))
                .then(literal("order")
                        .then(argument("id", IntegerArgumentType.integer(1))
                                .executes(ctx -> openOffersHub(ctx.getSource(),
                                        NegotiationStore.Kind.ORDER,
                                        IntegerArgumentType.getInteger(ctx, "id")))));
    }

    private static int openOffersHub(CommandSourceStack source, @Nullable NegotiationStore.Kind kind, int targetId) {
        ServerPlayer player = tryGetPlayer(source);
        if (player == null) {
            source.sendFailure(Component.literal("Only players can review price offers.")
                    .withStyle(ChatFormatting.RED));
            return 0;
        }
        try {
            if (kind == null) {
                OffersHubUi.open(player);
            } else {
                OffersHubUi.openTarget(player, kind, targetId);
            }
            return 1;
        } catch (Exception e) {
            LOGGER.error("[EconomyCraft] Failed to open the offers hub for {}",
                    player.getDisplayName().getString(), e);
            source.sendFailure(Component.literal("Failed to open price offers. Check server logs."));
            return 0;
        }
    }

    private static LiteralArgumentBuilder<CommandSourceStack> buildDeliveries() {
        return literal("deliveries")
                .executes(ctx -> openDeliveries(ctx.getSource().getPlayerOrException(), ctx.getSource()));
    }

    private static int openDeliveries(ServerPlayer player, CommandSourceStack source) {
        OrdersUi.openClaims(player, EconomyCraft.getManager(source.getServer()));
        return 1;
    }

    private static int searchOrders(ServerPlayer player, String query, CommandSourceStack source) {
        if (!EconomyConfig.get().ordersEnabled) {
            source.sendFailure(Component.literal("Orders are disabled.").withStyle(ChatFormatting.RED));
            return 0;
        }
        try {
            OrdersUi.openSearch(player, EconomyCraft.getManager(source.getServer()), query);
            return 1;
        } catch (Exception e) {
            LOGGER.error("[EconomyCraft] Failed to search /orders for {}", player.getDisplayName().getString(), e);
            source.sendFailure(Component.literal("Failed to open orders. Check server logs."));
            return 0;
        }
    }

    private static LiteralArgumentBuilder<CommandSourceStack> buildDaily() {
        return literal("daily")
                .executes(ctx -> daily(ctx.getSource().getPlayerOrException(), ctx.getSource()));
    }

    private static int daily(ServerPlayer player, CommandSourceStack source) {
        EconomyManager manager = EconomyCraft.getManager(source.getServer());
        boolean alreadyClaimed = manager.hasClaimedDailyToday(player.getUUID());
        if (manager.claimDaily(player.getUUID())) {
            EconomySounds.dailyReward(player);
            Component msg = Component.literal("Claimed " + EconomyCraft.formatMoney(EconomyConfig.get().dailyAmount))
                    .withStyle(ChatFormatting.GREEN);
            player.sendSystemMessage(msg);
        } else if (alreadyClaimed) {
            EconomySounds.failure(player);
            source.sendFailure(Component.literal("Already claimed today").withStyle(ChatFormatting.RED));
        } else {
            EconomySounds.failure(player);
            source.sendFailure(Component.literal("Daily reward could not be added to your balance")
                    .withStyle(ChatFormatting.RED));
        }
        return 1;
    }

    private static LiteralArgumentBuilder<CommandSourceStack> buildTransactions() {
        return literal("transactions")
                .executes(ctx -> openTransactions(ctx.getSource().getPlayerOrException(), ctx.getSource()));
    }

    private static int openTransactions(ServerPlayer player, CommandSourceStack source) {
        try {
            TransactionsUi.open(player);
            return 1;
        } catch (Exception e) {
            LOGGER.error("[EconomyCraft] Failed to open /transactions for {}", player.getDisplayName().getString(), e);
            source.sendFailure(Component.literal("Failed to open transactions. Check server logs."));
            return 0;
        }
    }

    private static LiteralArgumentBuilder<CommandSourceStack> buildTag() {
        return literal("tag")
                .executes(ctx -> {
                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                    TagUi.open(player);
                    return 1;
                })
                .then(argument("player", GameProfileArgument.gameProfile())
                        .requires(src -> EconomyPermissions.checkAdmin(src, Nodes.ADMIN_PLAYERS))
                        .executes(ctx -> {
                            ServerPlayer executor = tryGetPlayer(ctx.getSource());
                            EconomyManager eco = EconomyCraft.getManager(ctx.getSource().getServer());
                            TagDisplayService display = eco.getTagDisplay();
                            for (IdentityCompat.PlayerRef ref : IdentityCompat.getArgAsPlayerRefs(ctx, "player")) {
                                List<TagStyle.Tagged> tags = display.tagsOf(ref.id());
                                MutableComponent msg = Component.literal(ref.name() + "'s tags: ")
                                        .withStyle(ChatFormatting.YELLOW);
                                if (tags.isEmpty()) {
                                    msg = msg.append(Component.literal("none").withStyle(ChatFormatting.GRAY));
                                } else {
                                    for (int i = 0; i < tags.size(); i++) {
                                        if (i > 0) msg = msg.append(Component.literal(", ").withStyle(ChatFormatting.GRAY));
                                        msg = msg.append(tags.get(i).full());
                                    }
                                }
                                reply(ctx.getSource(), executor, msg, false);
                            }
                            return 1;
                        }));
    }


    private static LiteralArgumentBuilder<CommandSourceStack> buildJob() {
        LiteralArgumentBuilder<CommandSourceStack> root = literal("job")
                .executes(ctx -> {
                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                    TagUi.openProfession(player);
                    return 1;
                })
                .then(literal("leave")
                        .executes(ctx -> {
                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                            EconomyManager eco = EconomyCraft.getManager(ctx.getSource().getServer());
                            eco.getProfessions().reset(player.getUUID());
                            eco.getTagDisplay().refresh(player);
                            player.sendSystemMessage(Component.literal("Profession cleared").withStyle(ChatFormatting.YELLOW));
                            return 1;
                        }));

        for (ProfessionId p : ProfessionId.values()) {
            root.then(literal(p.name().toLowerCase(Locale.ROOT)).executes(ctx -> {
                ServerPlayer player = ctx.getSource().getPlayerOrException();
                EconomyManager eco = EconomyCraft.getManager(ctx.getSource().getServer());
                long lockout = EconomyConfig.get().professions.selectionLockoutHours;
                if (!eco.getProfessions().canChange(player.getUUID(), lockout) && !PermissionCompat.isAdmin(player)) {
                    long remaining = eco.getProfessions().remainingCooldownMillis(player.getUUID(), lockout);
                    player.sendSystemMessage(Component.literal("You cannot change profession yet. Remaining: "
                            + TimeFormat.formatDuration(remaining)).withStyle(ChatFormatting.RED));
                    return 0;
                }
                eco.getProfessions().select(player.getUUID(), p);
                eco.getTagDisplay().refresh(player);
                player.sendSystemMessage(Component.literal("Profession set to " + p.displayName()).withStyle(ChatFormatting.GREEN));
                return 1;
            }));
        }
        return root;
    }

    /**
     * {@code /eco party} — the menu (D16), a direct choice for players who already know what they want, and
     * {@code leave} to fall back to the default.
     *
     * <p>All three routes go through the same lockout check, because all three <em>are</em> a party change.
     * Leaving is not an escape hatch: spec 67 says a party cannot be changed for 30 hours, and a player who
     * could {@code leave} and immediately rejoin another party would have no lockout at all.
     */
    private static LiteralArgumentBuilder<CommandSourceStack> buildParty() {
        LiteralArgumentBuilder<CommandSourceStack> root = literal("party")
                .executes(ctx -> {
                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                    TagUi.openParty(player);
                    return 1;
                })
                .then(literal("leave").executes(ctx -> {
                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                    EconomyManager eco = EconomyCraft.getManager(ctx.getSource().getServer());
                    if (!partyUnlocked(player, eco)) return 0;
                    eco.getFactions().reset(player.getUUID());
                    eco.getTagDisplay().refresh(player);
                    player.sendSystemMessage(Component.literal("Party cleared — you are back to the default, "
                                    + FactionId.defaultFaction().displayName() + ".")
                            .withStyle(ChatFormatting.YELLOW));
                    player.sendSystemMessage(Component.literal("Choosing again starts a new 30 hour lockout.")
                            .withStyle(ChatFormatting.GRAY));
                    return 1;
                }));

        for (FactionId f : FactionId.values()) {
            root.then(literal(f.key()).executes(ctx -> {
                ServerPlayer player = ctx.getSource().getPlayerOrException();
                EconomyManager eco = EconomyCraft.getManager(ctx.getSource().getServer());
                if (!partyUnlocked(player, eco)) return 0;
                eco.getFactions().select(player.getUUID(), f);
                eco.getTagDisplay().refresh(player);
                player.sendSystemMessage(Component.literal("Party set to " + f.displayName()).withStyle(ChatFormatting.GREEN));
                return 1;
            }));
        }
        return root;
    }

    /**
     * Whether the player may change party right now, telling them how long is left when they may not.
     *
     * <p>An admin is exempt so a moderator can put a player where they need to be; the same exemption already
     * applies to the profession half of the lockout.
     */
    private static boolean partyUnlocked(ServerPlayer player, EconomyManager eco) {
        long lockout = EconomyConfig.get().factions.selectionLockoutHours;
        if (eco.getFactions().canChange(player.getUUID(), lockout) || PermissionCompat.isAdmin(player)) {
            return true;
        }
        long remaining = eco.getFactions().remainingCooldownMillis(player.getUUID(), lockout);
        player.sendSystemMessage(Component.literal("You cannot change party yet. Remaining: "
                        + TimeFormat.formatDuration(remaining))
                .withStyle(ChatFormatting.RED));
        return false;
    }

    @Nullable
    private static ServerPlayer tryGetPlayer(CommandSourceStack source) {
        try {
            return source.getPlayerOrException();
        } catch (Exception e) {
            return null;
        }
    }

    private static void reply(CommandSourceStack source, @Nullable ServerPlayer executor, Component msg, boolean broadcastToOps) {
        if (executor != null) {
            executor.sendSystemMessage(msg);
        } else {
            source.sendSuccess(() -> msg, broadcastToOps);
        }
    }

    private static CompletableFuture<Suggestions> suggestPlayers(CommandSourceStack source, SuggestionsBuilder builder) {
        var server = source.getServer();
        var manager = EconomyCraft.getManager(server);
        Set<String> suggestions = new HashSet<>();

        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            suggestions.add(IdentityCompat.of(p).name());
        }

        for (UUID id : manager.getBalances().keySet()) {
            String name = manager.getBestName(id);
            if (name != null && !name.isBlank()) {
                suggestions.add(name);
            }
        }

        String typed = builder.getRemainingLowerCase();
        suggestions.stream()
                .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(typed))
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .forEach(builder::suggest);
        return builder.buildFuture();
    }

    private static CompletableFuture<Suggestions> suggestShopCategories(CommandSourceStack source, SuggestionsBuilder builder) {
        PriceRegistry prices = EconomyCraft.getManager(source.getServer()).getPrices();
        for (String cat : prices.buyCategories()) {
            builder.suggest(cat);
        }
        return builder.buildFuture();
    }

    private static LiteralArgumentBuilder<CommandSourceStack> buildGossip() {
        LiteralArgumentBuilder<CommandSourceStack> root = literal("gossip");

        root.executes(ctx -> showGossipStatus(ctx.getSource()));
        root.then(literal("status").executes(ctx -> showGossipStatus(ctx.getSource())));
        root.then(literal("refresh").executes(ctx -> refreshGossip(ctx.getSource())));
        root.then(literal("test")
                .executes(ctx -> testGossip(ctx.getSource(), null))
                .then(argument("category", StringArgumentType.word())
                        .suggests((ctx, builder) -> {
                            for (var cat : GossipCategory.values()) {
                                builder.suggest(cat.name().toLowerCase(Locale.ROOT));
                            }
                            return builder.buildFuture();
                        })
                        .executes(ctx -> testGossip(ctx.getSource(), StringArgumentType.getString(ctx, "category")))));

        return root;
    }

    private static int showGossipStatus(CommandSourceStack source) {
        var cfg = EconomyConfig.get();
        var gossipCfg = cfg != null ? cfg.geminiGossip : null;
        if (gossipCfg == null || !gossipCfg.enabled()) {
            reply(source, tryGetPlayer(source), Component.literal("[EconomyCraft-AI] Villager gossip is currently DISABLED in configuration.").withStyle(ChatFormatting.RED), false);
            return 1;
        }

        var pool = EconomyCraft.GOSSIP_POOL.get();
        var worker = EconomyCraft.getGossipWorker();
        boolean circuitOpen = worker != null && worker.getApiClient().isCircuitOpen();

        reply(source, tryGetPlayer(source), Component.literal("=== EconomyCraft AI Villager Gossip ===").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD), false);
        reply(source, tryGetPlayer(source), Component.literal("• Model: ").withStyle(ChatFormatting.GRAY)
                .append(Component.literal(gossipCfg.model()).withStyle(ChatFormatting.WHITE))
                .append(Component.literal(" (OpenAI-compat: " + gossipCfg.isOpenAiCompatible() + ")").withStyle(ChatFormatting.DARK_GRAY)), false);
        reply(source, tryGetPlayer(source), Component.literal("• Base URL: ").withStyle(ChatFormatting.GRAY)
                .append(Component.literal(gossipCfg.baseUrl()).withStyle(ChatFormatting.WHITE)), false);
        reply(source, tryGetPlayer(source), Component.literal("• Refresh Interval: ").withStyle(ChatFormatting.GRAY)
                .append(Component.literal(gossipCfg.refreshIntervalMinutes() + "m").withStyle(ChatFormatting.WHITE))
                .append(Component.literal(" | Cooldown: " + gossipCfg.cooldownMinutes() + "m").withStyle(ChatFormatting.DARK_GRAY)), false);
        reply(source, tryGetPlayer(source), Component.literal("• Circuit Breaker: ").withStyle(ChatFormatting.GRAY)
                .append(circuitOpen ? Component.literal("OPEN (30m pause)").withStyle(ChatFormatting.RED) : Component.literal("CLOSED (operational)").withStyle(ChatFormatting.GREEN)), false);

        int totalRumors = (pool != null) ? pool.totalRumors() : 0;
        int totalCats = (pool != null) ? pool.rumorsByCategory().size() : 0;
        reply(source, tryGetPlayer(source), Component.literal("• Active Rumors: ").withStyle(ChatFormatting.GRAY)
                .append(Component.literal(totalRumors + " rumor(s) across " + totalCats + " category(ies)").withStyle(totalRumors > 0 ? ChatFormatting.GREEN : ChatFormatting.YELLOW)), false);

        if (pool != null && !pool.isEmpty()) {
            StringBuilder sb = new StringBuilder("• Categories: ");
            for (var entry : pool.rumorsByCategory().entrySet()) {
                sb.append(entry.getKey().name().toLowerCase(Locale.ROOT)).append(" (").append(entry.getValue().size()).append(") ");
            }
            reply(source, tryGetPlayer(source), Component.literal(sb.toString().trim()).withStyle(ChatFormatting.DARK_AQUA), false);
        }

        return 1;
    }

    private static int refreshGossip(CommandSourceStack source) {
        var worker = EconomyCraft.getGossipWorker();
        if (worker == null) {
            reply(source, tryGetPlayer(source), Component.literal("[EconomyCraft-AI] Worker not initialized (is gossip enabled in config?)").withStyle(ChatFormatting.RED), false);
            return 0;
        }

        reply(source, tryGetPlayer(source), Component.literal("[EconomyCraft-AI] Requesting fresh gossip digest from AI provider...").withStyle(ChatFormatting.YELLOW), false);
        worker.triggerManualRefresh().whenComplete((success, ex) -> {
            var srv = source.getServer();
            srv.execute(() -> {
                if (ex != null || !Boolean.TRUE.equals(success)) {
                    reply(source, tryGetPlayer(source), Component.literal("[EconomyCraft-AI] Failed to refresh gossip pool. See server console logs.").withStyle(ChatFormatting.RED), false);
                } else {
                    var pool = EconomyCraft.GOSSIP_POOL.get();
                    int count = pool != null ? pool.totalRumors() : 0;
                    reply(source, tryGetPlayer(source), Component.literal("[EconomyCraft-AI] Gossip refreshed successfully! " + count + " rumor(s) currently cached.").withStyle(ChatFormatting.GREEN), false);
                }
            });
        });
        return 1;
    }

    private static int testGossip(CommandSourceStack source, @Nullable String categoryName) {
        var pool = EconomyCraft.GOSSIP_POOL.get();
        if (pool == null || pool.isEmpty()) {
            reply(source, tryGetPlayer(source), Component.literal("[EconomyCraft-AI] Rumor pool is currently empty. Try '/eco gossip refresh' first.").withStyle(ChatFormatting.YELLOW), false);
            return 0;
        }

        GossipCategory cat = GossipCategory.GENERAL;
        if (categoryName != null && !categoryName.isBlank()) {
            try {
                cat = GossipCategory.valueOf(categoryName.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                reply(source, tryGetPlayer(source), Component.literal("[EconomyCraft-AI] Unknown category: " + categoryName + ". Using GENERAL.").withStyle(ChatFormatting.GRAY), false);
            }
        }

        String rumor = pool.getRandomRumor(cat, RandomSource.create());
        if (rumor != null) {
            Component formatted = VillagerGossipListener.formatRumor(null, "[" + cat.name() + "] " + rumor);
            reply(source, tryGetPlayer(source), formatted, false);
            return 1;
        } else {
            reply(source, tryGetPlayer(source), Component.literal("[EconomyCraft-AI] No rumor available for " + cat.name()).withStyle(ChatFormatting.RED), false);
            return 0;
        }
    }
}
