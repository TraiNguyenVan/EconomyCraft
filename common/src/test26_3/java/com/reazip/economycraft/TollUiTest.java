package com.reazip.economycraft;

import com.mojang.authlib.GameProfile;
import com.reazip.economycraft.api.v1.PaymentResult;
import com.reazip.economycraft.auction.AuctionListing;
import com.reazip.economycraft.auction.AuctionManager;
import com.reazip.economycraft.util.AsyncFileWriter;
import com.reazip.economycraft.util.ClickKind;
import com.reazip.economycraft.util.EconomyPermissions;
import com.reazip.economycraft.util.EconomySounds;
import com.reazip.economycraft.util.MenuUiSupport;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Exercises real menu callbacks and toll persistence with server/player boundaries mocked. */
class TollUiTest {
    private static final BlockPos POS = new BlockPos(1, 64, 1);
    private static final String DIMENSION = "minecraft:overworld";
    private static final UUID ACTOR = UUID.randomUUID();
    private static final UUID OWNER = UUID.randomUUID();
    private static final UUID RECIPIENT = UUID.randomUUID();
    @TempDir Path world;
    private MinecraftServer server;
    private ServerLevel level;
    private ServerPlayer player;
    private PlayerList players;
    private EconomyManager eco;
    private TollManager tolls;
    private EconomyConfig config;
    private AbstractContainerMenu menu;
    private boolean tollAllowed = true;
    private boolean admin;
    private MockedStatic<EconomySounds> sounds;
    private MockedStatic<EconomyCraft> craft;
    private MockedStatic<EconomyConfig> configs;
    private MockedStatic<MenuUiSupport> menus;

    @BeforeAll static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(VanillaRegistries.createWorldLookup())
                .forEach(components -> components.apply());
    }

    @BeforeEach void setup() throws ReflectiveOperationException {
        server = mock(MinecraftServer.class);
        when(server.getWorldPath(LevelResource.ROOT)).thenReturn(world);
        players = mock(PlayerList.class);
        when(server.getPlayerList()).thenReturn(players);
        level = mock(ServerLevel.class);
        when(level.getServer()).thenReturn(server);
        when(level.dimension()).thenReturn(Level.OVERWORLD);
        player = mock(ServerPlayer.class);
        when(player.level()).thenReturn(level);
        when(player.getUUID()).thenReturn(ACTOR);
        when(player.getName()).thenReturn(Component.literal("Actor"));
        when(player.getGameProfile()).thenReturn(new GameProfile(ACTOR, "Actor"));
        when(player.pick(5.0, 1.0f, false)).thenReturn(hit(POS));
        when(player.mayInteract(level, POS)).thenReturn(true);
        var gameModeField = ServerPlayer.class.getField("gameMode");
        gameModeField.setAccessible(true);
        gameModeField.set(player, mock(ServerPlayerGameMode.class));
        when(player.gameMode.getGameModeForPlayer()).thenReturn(GameType.SURVIVAL);
        when(players.getPlayers()).thenReturn(List.of(player));
        when(players.getPlayer(ACTOR)).thenReturn(player);
        eco = mock(EconomyManager.class, RETURNS_DEEP_STUBS);
        when(eco.getBalances()).thenReturn(Map.of(RECIPIENT, 0L));
        when(eco.getBestName(RECIPIENT)).thenReturn("Recipient");
        when(eco.getBestName(OWNER)).thenReturn("Owner");
        config = new EconomyConfig();
        config.shopEnabled = config.sellEnabled = config.worthEnabled = config.auctionEnabled = config.ordersEnabled = false;
        configs = mockStatic(EconomyConfig.class);
        configs.when(EconomyConfig::get).thenReturn(config);
        craft = mockStatic(EconomyCraft.class, CALLS_REAL_METHODS);
        craft.when(() -> EconomyCraft.getManager(server)).thenReturn(eco);
        sounds = mockStatic(EconomySounds.class);
        Inventory inv = mock(Inventory.class);
        when(inv.getItem(anyInt())).thenReturn(ItemStack.EMPTY);
        menus = mockStatic(MenuUiSupport.class, CALLS_REAL_METHODS);
        menus.when(() -> MenuUiSupport.openMenu(eq(player), anyString(), any())).thenAnswer(call -> {
            BiFunction<Integer, Inventory, AbstractContainerMenu> factory = call.getArgument(2);
            menu = factory.apply(1, inv);
            return null;
        });
        EconomyPermissions.setBackend((source, node, fallback) -> node.equals(EconomyPermissions.Nodes.COMMAND_TOLL)
                ? tollAllowed : node.startsWith("economycraft.admin") ? admin : true);
        tolls = TollManager.of(server);
    }

    @AfterEach void cleanup() {
        EconomyPermissions.setBackend(null);
        if (menus != null) menus.close();
        if (sounds != null) sounds.close();
        if (craft != null) craft.close();
        if (configs != null) configs.close();
    }

    private static BlockHitResult hit(BlockPos pos) {
        return new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false);
    }

    private void click(int slot) throws Exception {
        var method = menu.getClass().getDeclaredMethod("onClick", int.class, int.class, ClickKind.class, Player.class);
        method.setAccessible(true);
        method.invoke(menu, slot, 0, ClickKind.PICKUP, player);
    }

    private String label(int slot) { return menu.getSlot(slot).getItem().getHoverName().getString(); }
    private TollManager.Toll toll() { return tolls.get(DIMENSION, POS); }
    private void ownToll() { tolls.put(DIMENSION, POS, ACTOR, 10); TollUi.open(player); }
    private void otherToll() { tolls.put(DIMENSION, POS, OWNER, 10); TollUi.open(player); }
    private void pickRecipient() throws Exception {
        // The real picker includes a known offline account as well as online players.
        for (int i = 0; i < menu.slots.size(); i++) {
            if (label(i).equals("Recipient")) { click(i); return; }
        }
        fail("Recipient missing from picker");
    }
    private void messageContains(String text) {
        verify(player, atLeastOnce()).sendSystemMessage(argThat(message -> message.getString().contains(text)));
    }
    private TollManager reload() {
        MinecraftServer restarted = mock(MinecraftServer.class);
        when(restarted.getWorldPath(LevelResource.ROOT)).thenReturn(world);
        return TollManager.of(restarted);
    }

    @Test void hubVisibilityAndClickRecheck() throws Exception {
        HubUi.open(player);
        assertEquals("Tolls", label(34));
        tollAllowed = false;
        click(34);
        messageContains("permission");
        HubUi.open(player);
        assertNotEquals("Tolls", label(34));
        menu = null;
        TollUi.open(player);
        assertNull(menu);
    }

    @Test void noTargetOrBeyondReachDoesNotOpen() {
        when(player.pick(5.0, 1.0f, false)).thenReturn(BlockHitResult.miss(new Vec3(0, 64, 6), Direction.UP, new BlockPos(0, 64, 6)));
        TollUi.open(player);
        assertNull(menu);
        messageContains("within five blocks");
        verify(player).pick(5.0, 1.0f, false);
    }

    @Test void createAndFeeEditPersistAcrossReload() throws Exception {
        TollUi.open(player);
        click(13);
        click(26);
        assertEquals(ACTOR.toString(), toll().owner);
        assertEquals(1, reload().get(DIMENSION, POS).fee);
        click(12);
        click(8); // +1000 in the existing money editor
        click(26);
        assertEquals(1001, toll().fee);
        assertEquals(1001, reload().get(DIMENSION, POS).fee);
    }

    @Test void cancelCreateEditTransferAndRemoveDoesNotMutate() throws Exception {
        TollUi.open(player);
        click(13); click(18);
        assertNull(toll());
        ownToll();
        click(12); click(8); click(18);
        assertEquals(10, toll().fee);
        click(14); pickRecipient(); click(MenuUiSupport.ROW_CANCEL);
        assertEquals(ACTOR.toString(), toll().owner);
        click(14); // picker Back
        click(menu.slots.size() - 36 - 9);
        assertEquals(ACTOR.toString(), toll().owner);
        click(16); click(MenuUiSupport.ROW_CANCEL);
        assertNotNull(toll());
    }

    @Test void creationRechecksModificationGameModeAndLimit() throws Exception {
        TollUi.open(player);
        when(player.mayInteract(level, POS)).thenReturn(false);
        click(13);
        assertEquals("Create", label(13));
        assertNull(toll());
        when(player.mayInteract(level, POS)).thenReturn(true);
        click(13);
        when(player.blockActionRestricted(level, POS, GameType.SURVIVAL)).thenReturn(true);
        click(26);
        assertNull(toll());
        when(player.blockActionRestricted(level, POS, GameType.SURVIVAL)).thenReturn(false);
        TollUi.open(player); click(13);
        config.maxActiveTollsPerPlayer = 1;
        tolls.put(DIMENSION, POS.above(), ACTOR, 5);
        click(26);
        assertNull(toll());
        messageContains("active toll limit");
    }

    @Test void creationCannotOverwriteTollRegisteredWhileEditing() throws Exception {
        TollUi.open(player); click(13);
        tolls.put(DIMENSION, POS, OWNER, 20);
        click(26);
        assertEquals(OWNER.toString(), toll().owner);
        assertEquals(20, toll().fee);
    }

    @Test void confirmationRechecksPermissionTargetAndOwnership() throws Exception {
        ownToll(); click(12);
        tollAllowed = false;
        click(26);
        assertEquals(10, toll().fee);
        tollAllowed = true;
        TollUi.open(player); click(12);
        when(player.pick(5.0, 1.0f, false)).thenReturn(hit(POS.above()));
        click(26);
        assertEquals(10, toll().fee);
        when(player.pick(5.0, 1.0f, false)).thenReturn(hit(POS));
        TollUi.open(player); click(12);
        tolls.transfer(DIMENSION, POS, ACTOR, OWNER);
        click(26);
        assertEquals(OWNER.toString(), toll().owner);
        assertEquals(10, toll().fee);
    }

    @Test void feeEditHasNoModificationOverride() throws Exception {
        admin = true;
        ownToll(); click(12);
        when(player.mayInteract(level, POS)).thenReturn(false);
        click(26);
        assertEquals(10, toll().fee);
    }

    @Test void nonOwnerOnlyGetsInfoEvenForHiddenSlotClicks() throws Exception {
        otherToll();
        assertEquals("Info", label(10));
        assertNotEquals("Change fee", label(12));
        assertNotEquals("Transfer", label(14));
        assertNotEquals("Remove", label(16));
        click(12); click(13); click(14); click(16); click(10);
        assertEquals(OWNER.toString(), toll().owner);
        messageContains("Owner");
    }

    @Test void ownerTransferAndRemovalPersistAndEnforceRecipientLimit() throws Exception {
        ownToll(); click(14); pickRecipient();
        config.maxActiveTollsPerPlayer = 1;
        tolls.put(DIMENSION, POS.above(), RECIPIENT, 5);
        click(MenuUiSupport.ROW_CONFIRM);
        assertEquals(ACTOR.toString(), toll().owner);
        config.maxActiveTollsPerPlayer = 0;
        TollUi.open(player); click(14); pickRecipient(); click(MenuUiSupport.ROW_CONFIRM);
        assertEquals(RECIPIENT.toString(), reload().get(DIMENSION, POS).owner);
        ownToll(); click(16); click(MenuUiSupport.ROW_CONFIRM);
        assertNull(reload().get(DIMENSION, POS));
    }

    @Test void transferPickerCanSelectAnOfflineKnownAccountWithoutCreatingAnother() throws Exception {
        ownToll();
        click(14);
        pickRecipient();
        assertTrue(label(MenuUiSupport.ROW_CONFIRM).contains("Confirm"));
        verify(eco, never()).getBalance(any(UUID.class), eq(true));
        click(MenuUiSupport.ROW_CONFIRM);
        assertEquals(RECIPIENT.toString(), toll().owner);
        verify(eco, never()).getBalance(any(UUID.class), eq(true));
    }

    @Test void commandTransferRejectsSyntheticOfflineIdentityAndChecksKnownNameAgainstUuid() {
        var known = new com.reazip.economycraft.util.IdentityCompat.PlayerRef(RECIPIENT, "Recipient");
        when(eco.tryResolveUuidByName("Recipient")).thenReturn(RECIPIENT);
        assertTrue(EconomyCommands.knownTollRecipient(server, eco, known));

        var mismatched = new com.reazip.economycraft.util.IdentityCompat.PlayerRef(RECIPIENT, "OtherName");
        when(eco.tryResolveUuidByName("OtherName")).thenReturn(null);
        assertFalse(EconomyCommands.knownTollRecipient(server, eco, mismatched));

        UUID synthetic = UUID.nameUUIDFromBytes("OfflinePlayer:CapCapServer".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var invented = new com.reazip.economycraft.util.IdentityCompat.PlayerRef(synthetic, "CapCapServer");
        assertFalse(EconomyCommands.knownTollRecipient(server, eco, invented));
    }

    @Test void adminTransferIsConfirmedAndNotifiesFormerOwner() throws Exception {
        admin = true;
        ServerPlayer former = mock(ServerPlayer.class);
        when(players.getPlayer(OWNER)).thenReturn(former);
        otherToll();
        assertEquals("Admin transfer", label(14));
        assertEquals("Admin remove", label(16));
        assertNotEquals("Change fee", label(12));
        click(14); pickRecipient();
        assertEquals(OWNER.toString(), toll().owner);
        click(MenuUiSupport.ROW_CONFIRM);
        assertEquals(RECIPIENT.toString(), reload().get(DIMENSION, POS).owner);
        verify(former).sendSystemMessage(argThat(m -> m.getString().contains("Admin Actor transferred")
                && m.getString().contains("Recipient")));
    }

    @Test void adminRemovalRechecksPermissionsAndOwnerBeforeNotifying() throws Exception {
        admin = true;
        ServerPlayer former = mock(ServerPlayer.class);
        when(players.getPlayer(OWNER)).thenReturn(former);
        otherToll(); click(16);
        admin = false;
        click(MenuUiSupport.ROW_CONFIRM);
        assertNotNull(toll());
        verifyNoInteractions(former);
        admin = true;
        TollUi.open(player); click(16);
        tolls.transfer(DIMENSION, POS, OWNER, RECIPIENT);
        click(MenuUiSupport.ROW_CONFIRM);
        assertEquals(RECIPIENT.toString(), toll().owner);
        verifyNoInteractions(former);
        otherToll(); click(16); click(MenuUiSupport.ROW_CONFIRM);
        assertNull(reload().get(DIMENSION, POS));
        verify(former).sendSystemMessage(argThat(m -> m.getString().contains("Admin Actor removed")));
    }

    @Test void adminTransferRechecksAdminTollPermissionAndRecipientLimit() throws Exception {
        admin = true;
        otherToll(); click(14); pickRecipient();
        admin = false;
        click(MenuUiSupport.ROW_CONFIRM);
        assertEquals(OWNER.toString(), toll().owner);
        admin = true;
        TollUi.open(player); click(14); pickRecipient();
        tollAllowed = false;
        click(MenuUiSupport.ROW_CONFIRM);
        assertEquals(OWNER.toString(), toll().owner);
        tollAllowed = true;
        TollUi.open(player); click(14); pickRecipient();
        config.maxActiveTollsPerPlayer = 1;
        tolls.put(DIMENSION, POS.above(), RECIPIENT, 1);
        click(MenuUiSupport.ROW_CONFIRM);
        assertEquals(OWNER.toString(), toll().owner);
    }

    @Test void adminCanTransferToSelfButNotToCurrentOwner() throws Exception {
        admin = true;
        when(eco.getBalances()).thenReturn(Map.of(OWNER, 0L));
        otherToll(); click(14);
        assertEquals("Actor", label(0));
        assertEquals("Owner", label(1));
        click(1);
        messageContains("already owns");
        assertEquals(OWNER.toString(), toll().owner);
        click(0); click(MenuUiSupport.ROW_CONFIRM);
        assertEquals(ACTOR.toString(), toll().owner);
    }

    @Test void menuCreatedTollStillChargesRightClicksAndHonorsCooldown() throws Exception {
        TollUi.open(player); click(13); click(26);
        ServerPlayer visitor = mock(ServerPlayer.class);
        when(visitor.getUUID()).thenReturn(RECIPIENT);
        PaymentResult payment = mock(PaymentResult.class);
        when(payment.successful()).thenReturn(true);
        when(eco.transferMoney(eq(RECIPIENT), eq(ACTOR), eq(1L), eq(1L), eq(EconomySources.TOLL_PAYMENT), anyString()))
                .thenReturn(payment);
        assertEquals(TollManager.InteractionResult.GRANTED, tolls.interact(server, player, DIMENSION, POS));
        assertEquals(TollManager.InteractionResult.GRANTED, tolls.interact(server, visitor, DIMENSION, POS));
        assertEquals(TollManager.InteractionResult.DENIED, tolls.interact(server, visitor, DIMENSION, POS));
        verify(eco, times(1)).transferMoney(eq(RECIPIENT), eq(ACTOR), eq(1L), eq(1L), eq(EconomySources.TOLL_PAYMENT), anyString());
        click(16); click(MenuUiSupport.ROW_CONFIRM);
        assertEquals(TollManager.InteractionResult.NOT_TOLL, tolls.interact(server, visitor, DIMENSION, POS));
    }

    @Test void menuCreatedTollStillControlsPressurePlateAndRemovalRestoresVanilla() throws Exception {
        TollUi.open(player); click(13); click(26);
        ServerPlayer visitor = mock(ServerPlayer.class);
        when(visitor.getUUID()).thenReturn(RECIPIENT);
        when(level.getEntitiesOfClass(eq(ServerPlayer.class), any(AABB.class), any())).thenReturn(List.of(visitor));
        // Default mocked payment fails: no redstone signal for a visitor who cannot pay.
        assertEquals(0, tolls.pressurePlateSignal(level, POS, 15));
        when(level.getEntitiesOfClass(eq(ServerPlayer.class), any(AABB.class), any())).thenReturn(List.of());
        assertEquals(0, tolls.pressurePlateSignal(level, POS, 0));
        PaymentResult payment = mock(PaymentResult.class);
        when(payment.successful()).thenReturn(true);
        when(eco.transferMoney(eq(RECIPIENT), eq(ACTOR), eq(1L), eq(1L), eq(EconomySources.TOLL_PAYMENT), anyString()))
                .thenReturn(payment);
        when(level.getEntitiesOfClass(eq(ServerPlayer.class), any(AABB.class), any())).thenReturn(List.of(visitor));
        assertEquals(15, tolls.pressurePlateSignal(level, POS, 15));
        assertEquals(15, tolls.pressurePlateSignal(level, POS, 15));
        verify(eco, times(2)).transferMoney(eq(RECIPIENT), eq(ACTOR), eq(1L), eq(1L), eq(EconomySources.TOLL_PAYMENT), anyString());
        click(16); click(MenuUiSupport.ROW_CONFIRM);
        assertEquals(15, tolls.pressurePlateSignal(level, POS, 15));
    }

    @Test void tollOverlayAppearsOnTargetAndDoesNotClearOtherActionbarMessages() {
        tolls.put(DIMENSION, POS, OWNER, 75);
        when(server.getTickCount()).thenReturn(10);
        TollHud.tick(server);
        verify(player).sendSystemMessage(argThat(message -> message.getString().contains("Toll: $75 net")), eq(true));

        when(player.pick(5.0, 1.0f, false)).thenReturn(BlockHitResult.miss(Vec3.ZERO, Direction.UP, POS));
        when(server.getTickCount()).thenReturn(20);
        TollHud.tick(server);
        verify(player, times(1)).sendSystemMessage(any(Component.class), eq(true));
    }

    @Test void offlineAuctionSellerGetsPendingSaleNotification() {
        when(players.getPlayer(OWNER)).thenReturn(null);
        NotificationManager notifications = new NotificationManager(server);
        when(eco.getNotifications()).thenReturn(notifications);
        AuctionManager auctions = new AuctionManager(server, mock(DeliveryManager.class));
        AuctionListing listing = new AuctionListing();
        listing.seller = OWNER;
        listing.item = new ItemStack(Items.DIAMOND, 3);
        listing.price = 125;

        auctions.notifySellerSale(listing, player);
        AsyncFileWriter.flush();

        Path file = world.resolve("economycraft/data/notifications.json");
        assertTrue(java.nio.file.Files.exists(file));
        assertTrue(read(file).contains("Sold 3x Diamond to Actor for $125"));

        ServerPlayer seller = mock(ServerPlayer.class);
        when(seller.getUUID()).thenReturn(OWNER);
        notifications.sendPending(seller);
        verify(seller).sendSystemMessage(argThat(message -> message.getString().contains("Sold 3x Diamond")));
        AsyncFileWriter.flush();
    }

    private static String read(Path file) {
        try { return java.nio.file.Files.readString(file); }
        catch (java.io.IOException e) { throw new AssertionError(e); }
    }
}
