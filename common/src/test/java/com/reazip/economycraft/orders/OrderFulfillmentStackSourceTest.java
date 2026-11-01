package com.reazip.economycraft.orders;

import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OrderFulfillmentStackSourceTest {

    @Test
    void consumesAndRestoresSelectedQuantityAcrossStacks() {
        ItemStack first = stack(4);
        ItemStack second = stack(6);
        OrderFulfillment.ItemSource source = OrderFulfillment.stackSource(List.of(first, second));

        assertEquals(8, source.take(8));
        assertEquals(0, first.getCount());
        assertEquals(2, second.getCount());

        assertEquals(0, source.restore(8));
        assertEquals(4, first.getCount());
        assertEquals(6, second.getCount());
    }

    @Test
    void restoresPartialTakeWhenSelectionContainsTooFewItems() {
        ItemStack first = stack(4);
        ItemStack second = stack(3);
        OrderFulfillment.ItemSource source = OrderFulfillment.stackSource(List.of(first, second));

        assertEquals(7, source.take(10));
        assertEquals(0, source.restore(7));
        assertEquals(4, first.getCount());
        assertEquals(3, second.getCount());
    }

    private static ItemStack stack(int initialCount) {
        ItemStack stack = mock(ItemStack.class);
        AtomicInteger count = new AtomicInteger(initialCount);
        when(stack.isEmpty()).thenAnswer(ignored -> count.get() <= 0);
        when(stack.getCount()).thenAnswer(ignored -> count.get());
        doAnswer(invocation -> {
            count.updateAndGet(current -> Math.max(0, current - (int) invocation.getArgument(0)));
            return null;
        }).when(stack).shrink(anyInt());
        doAnswer(invocation -> {
            count.set((int) invocation.getArgument(0));
            return null;
        }).when(stack).setCount(anyInt());
        return stack;
    }
}
