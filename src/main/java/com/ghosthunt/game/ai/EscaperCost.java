package com.ghosthunt.game.ai;

import java.util.function.IntPredicate;

/**
 * 逃生者在尋路圖上的邊成本（純邏輯）：跟鬼用同一張圖，只是成本不同。
 * 蹲伏小洞對逃生者是正常的路（蹲著鑽不減速）；放了障礙物的門口過不去；卡住的門自己拆得開（要時間）；地洞照常走。
 * 粒子濾波和逃生者的距離場用這套。
 */
public final class EscaperCost {

    private EscaperCost() {}

    /**
     * @param doorJammed  DOOR 邊的 ref（NavMap.doors 的編號）是不是卡住了
     * @param barricaded  BARRICADE 邊的 ref（障礙物位置的編號）是不是放了障礙物
     * @param unjamBlocks 拆掉卡門要花的時間換成格數（進、出兩條邊各算一半）
     * @param hatchBlocks 開活板門＋爬梯子換成格數
     */
    public static GridNav.Cost of(IntPredicate doorJammed, IntPredicate barricaded, double unjamBlocks, double hatchBlocks) {
        return e -> switch (e.kind()) {
            case WALK, STAIR, HOLE -> 0;
            case DOOR -> doorJammed.test(e.ref()) ? unjamBlocks / 2 : 0;
            case BARRICADE -> barricaded.test(e.ref()) ? Double.POSITIVE_INFINITY : 0;
            case HATCH -> hatchBlocks;
        };
    }
}
