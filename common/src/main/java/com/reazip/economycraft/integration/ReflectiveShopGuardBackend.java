package com.reazip.economycraft.integration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.UUID;

final class ReflectiveShopGuardBackend implements ClaimBridge.Backend {
    private static final Logger LOGGER = LoggerFactory.getLogger("EconomyCraft-ShopGuard");

    private final Object store;
    private final MethodHandle claimAtHandle;
    private final MethodHandle getOwnerHandle;
    private final MethodHandle mayBuildHandle;
    private final MethodHandle getIdHandle;
    private final MethodHandle getDimHandle;

    ReflectiveShopGuardBackend() throws ReflectiveOperationException {
        Class<?> shopGuardClass = Class.forName("io.github.andrewwwwwwwwwwwwwww.shopguard.ShopGuard");
        Field storeField = shopGuardClass.getField("STORE");
        this.store = storeField.get(null);

        Class<?> storeClass = store.getClass();
        Method claimAtMethod = storeClass.getMethod("claimAt", String.class, int.class, int.class);
        MethodHandles.Lookup lookup = MethodHandles.publicLookup();
        this.claimAtHandle = lookup.unreflect(claimAtMethod);

        Class<?> claimClass = Class.forName("io.github.andrewwwwwwwwwwwwwww.shopguard.claim.Claim");
        Field ownerField = claimClass.getField("owner");
        this.getOwnerHandle = lookup.unreflectGetter(ownerField);

        Field idField = claimClass.getField("id");
        this.getIdHandle = lookup.unreflectGetter(idField);

        Field dimField = claimClass.getField("dimension");
        this.getDimHandle = lookup.unreflectGetter(dimField);

        Method mayBuildMethod = claimClass.getMethod("mayBuild", UUID.class);
        this.mayBuildHandle = lookup.unreflect(mayBuildMethod);
    }

    @Override
    public ClaimBridge.ClaimInfo claimAt(String dim, int x, int z) {
        try {
            Object claim = claimAtHandle.invoke(store, dim, x, z);
            if (claim == null) return null;
            long id = (long) getIdHandle.invoke(claim);
            UUID owner = (UUID) getOwnerHandle.invoke(claim);
            String dimension = (String) getDimHandle.invoke(claim);
            return new ClaimBridge.ClaimInfo(id, owner, dimension);
        } catch (Throwable t) {
            LOGGER.error("[EconomyCraft] Error querying ShopGuard claimAt", t);
            return null;
        }
    }

    @Override
    public boolean isOwnClaim(UUID player, String dim, int x, int z) {
        if (player == null) return false;
        ClaimBridge.ClaimInfo c = claimAt(dim, x, z);
        return c != null && player.equals(c.owner());
    }

    @Override
    public boolean isUnclaimed(String dim, int x, int z) {
        try {
            Object claim = claimAtHandle.invoke(store, dim, x, z);
            return claim == null;
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public boolean mayBuild(UUID player, String dim, int x, int z) {
        if (player == null) return false;
        try {
            Object claim = claimAtHandle.invoke(store, dim, x, z);
            if (claim == null) return true;
            return (boolean) mayBuildHandle.invoke(claim, player);
        } catch (Throwable t) {
            return true;
        }
    }
}
