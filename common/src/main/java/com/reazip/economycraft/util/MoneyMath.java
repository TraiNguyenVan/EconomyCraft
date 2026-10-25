package com.reazip.economycraft.util;

public final class MoneyMath {
    private MoneyMath() {}

    public static long scale(long amount, long numerator, long denominator) {
        return Math.round((double) amount * numerator / denominator);
    }

    public static long saturatedAdd(long a, long b) {
        try {
            return Math.addExact(a, b);
        } catch (ArithmeticException ex) {
            return a < 0 ? Long.MIN_VALUE : Long.MAX_VALUE;
        }
    }
}
