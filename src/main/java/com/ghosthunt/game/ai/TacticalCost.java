package com.ghosthunt.game.ai;

import java.util.function.IntPredicate;

/**
 * 戰術尋路（第三版第 4 項，純邏輯）：走進「對方看得到的格子」的邊多算 penalty 格，
 * 找路時自然會從背後、視線外摸過去。原本不能走的邊（無窮大）維持不能走。
 */
public final class TacticalCost {

    private TacticalCost() {}

    public static GridNav.Cost wrap(GridNav.Cost base, IntPredicate visibleToTarget, double penalty) {
        return e -> {
            double x = base.extra(e);
            return Double.isInfinite(x) ? x : x + (visibleToTarget.test(e.to()) ? penalty : 0);
        };
    }
}
