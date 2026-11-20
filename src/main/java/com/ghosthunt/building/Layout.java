package com.ghosthunt.building;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 大樓的座標計算。每層是 n×n 個房間格，走廊是「井」字形，兩個方向各兩條：
 * n = 3：每兩個房間格之間都有走廊 → 外牆 | 房間 0 | 走廊 | 房間 1 | 走廊 | 房間 2 | 外牆（中間那格四面都接走廊）
 * n = 4：第 0、1 個和第 2、3 個房間格之間有走廊 → 外牆 | 房 0 | 走廊 | 房 1 | 房 2 | 走廊 | 房 3 | 外牆
 */
public final class Layout {

    public final int n;
    public final int room;
    public final int corridor;
    public final int floors;
    public final int floorHeight;
    public final int ox, oy, oz;
    /** 整層邊長（含兩側外牆）。 */
    public final int size;

    private final int[] cellStart;
    private final List<Integer> corridorStarts;
    /** 不規則大樓：每層的起點（相對座標），檢查走不走得到、傳送都從這裡開始；九宮格大樓是 null。 */
    private int[] start;

    /** 不規則大樓：n = 0（沒有九宮格），size 是外框的邊長。 */
    public static Layout irregular(int size, int floors, int floorHeight, int ox, int oy, int oz, int startX, int startZ) {
        Layout L = new Layout(0, 1, 1, floors, floorHeight, ox, oy, oz, size);
        L.start = new int[]{startX, startZ};
        return L;
    }

    private Layout(int n, int room, int corridor, int floors, int floorHeight, int ox, int oy, int oz, int size) {
        this.n = n;
        this.room = room;
        this.corridor = corridor;
        this.floors = floors;
        this.floorHeight = floorHeight;
        this.ox = ox;
        this.oy = oy;
        this.oz = oz;
        this.cellStart = new int[0];
        this.corridorStarts = List.of();
        this.size = size;
    }

    public boolean isIrregular() {
        return start != null;
    }

    public Layout(int n, int room, int corridor, int floors, int floorHeight, int ox, int oy, int oz) {
        this.n = n;
        this.room = room;
        this.corridor = corridor;
        this.floors = floors;
        this.floorHeight = floorHeight;
        this.ox = ox;
        this.oy = oy;
        this.oz = oz;
        this.cellStart = new int[n];
        List<Integer> cs = new ArrayList<>();
        int pos = 1; // 0 是外牆
        for (int i = 0; i < n; i++) {
            cellStart[i] = pos;
            pos += room;
            if (corridorAfter(i)) {
                cs.add(pos);
                pos += corridor;
            }
        }
        this.corridorStarts = Collections.unmodifiableList(cs);
        this.size = pos + 1; // 最後一格是外牆
    }

    /** 第 i 個房間格的 +方向（東／南）是否接走廊。 */
    public boolean corridorAfter(int i) {
        if (n == 3) return i < n - 1;
        return i % 2 == 0 && i < n - 1;
    }

    /** 第 i 個房間格的 -方向（西／北）是否接走廊。 */
    public boolean corridorBefore(int i) {
        if (n == 3) return i > 0;
        return i % 2 == 1;
    }

    /** 房間格在這一軸上的相對起點（含牆）。 */
    public int cellStart(int i) {
        return cellStart[i];
    }

    public List<Integer> corridorStarts() {
        return corridorStarts;
    }

    /** 第 f 層樓板的 y（走路的高度是 floorY + 1）。 */
    public int floorY(int f) {
        return oy + f * floorHeight;
    }

    public int topY() {
        return floorY(floors);
    }

    /** 房間格內的相對座標 (lx, lz) 轉成世界座標。 */
    public Pos cell(int cx, int cz, int lx, int y, int lz) {
        return new Pos(ox + cellStart[cx] + lx, y, oz + cellStart[cz] + lz);
    }

    public Pos rel(int x, int y, int z) {
        return new Pos(ox + x, y, oz + z);
    }

    /** 房間格中心（走路高度）。 */
    public Pos cellCenter(int f, int cx, int cz) {
        return cell(cx, cz, room / 2, floorY(f) + 1, room / 2);
    }

    /** 第一個走廊交叉口中心（走路高度）。 */
    public Pos crossing(int f) {
        if (start != null) return rel(start[0], floorY(f) + 1, start[1]);
        int c = corridorStarts.get(0) + corridor / 2;
        return rel(c, floorY(f) + 1, c);
    }
}
