package com.ghosthunt.building;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 蓋之前在記憶體裡檢查大樓走不走得通。
 * 每層從第一個走廊交叉口出發，用「站得住」的格子（腳下是實心、頭頂兩格是空的）做廣度搜尋，
 * 不能跳，所以只走同一高度的相鄰格；樓梯另外檢查底部和頂端都走得到。
 */
final class Validator {

    private static final int[][] DIRS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    static List<String> validate(Building b, BlockMap map, BuildingSettings s, RoomBuilder rooms) {
        Layout L = b.layout;
        List<String> errors = new ArrayList<>();
        List<Set<Pos>> reached = new ArrayList<>();
        List<Set<Pos>> reachedGhostOpen = new ArrayList<>();
        Set<Pos> ghostDoors = new HashSet<>(b.ghostDoors);

        for (int f = 0; f < L.floors; f++) {
            reached.add(flood(L, map, f, Set.of()));
            reachedGhostOpen.add(flood(L, map, f, ghostDoors));
        }

        for (int f = 0; f < L.floors; f++) {
            Set<Pos> r = reached.get(f);
            String fl = (f + 1) + "F";
            if (r.isEmpty()) {
                errors.add(fl + "：走廊交叉口站不住");
                continue;
            }
            // 每個房間格都要走得到（鬼房要等門打開）
            int toilets = 0;
            for (int cx = 0; cx < L.n; cx++) {
                for (int cz = 0; cz < L.n; cz++) {
                    RoomType t = b.type(f, cx, cz);
                    if (t.deadEnd()) toilets++;
                    Set<Pos> set = t == RoomType.GHOST_ROOM ? reachedGhostOpen.get(f) : r;
                    if (!cellReached(L, set, f, cx, cz)) errors.add(fl + " " + t.label + " (" + cx + "," + cz + ") 走不到");
                    // 一般房間裡不能有圍起來、走不進去的小空間（監獄籠子裡面除外）
                    if (t != RoomType.STAIRWELL && t != RoomType.GHOST_ROOM) {
                        int y = L.floorY(f) + 1;
                        int lo = L.room / 2 - 2, hi = L.room / 2 + 1; // 籠子裡面（平面圖的 J 圍起來的 4×4）
                        for (int lx = 1; lx <= L.room - 2; lx++) {
                            for (int lz = 1; lz <= L.room - 2; lz++) {
                                if (t == RoomType.JAIL && lx >= lo && lx <= hi && lz >= lo && lz <= hi) continue;
                                Pos p = L.cell(cx, cz, lx, y, lz);
                                if (standable(map, p, Set.of()) && !set.contains(p)) {
                                    errors.add(fl + " " + t.label + " (" + cx + "," + cz + ") 裡面有走不進去的角落：" + p);
                                    lx = L.room; // 跳出
                                    break;
                                }
                            }
                        }
                    }
                }
            }
            if (toilets > s.maxDeadEndsPerFloor()) errors.add(fl + " 死路房間太多：" + toilets);
            // 樓梯：底部平台和上一層的抵達點都要走得到
            if (f < L.floors - 1) {
                for (Building.Cell st : b.stairwells) {
                    if (st.floor() != f) continue;
                    Pos[] ends = rooms.stairEnds(f, st.cx(), st.cz());
                    b.stairLinks.add(ends);
                    if (!r.contains(ends[0])) errors.add(fl + " 樓梯 (" + st.cx() + "," + st.cz() + ") 底部走不到");
                    if (!reached.get(f + 1).contains(ends[1])) errors.add((f + 2) + "F 樓梯 (" + st.cx() + "," + st.cz() + ") 頂端出不去");
                    for (Pos p : rooms.stairHeadroom(f, st.cx(), st.cz())) {
                        if (!map.passable(p)) {
                            errors.add(fl + " 樓梯 (" + st.cx() + "," + st.cz() + ") 會撞頭：" + p);
                            break;
                        }
                    }
                }
            }
            // 監獄籠子旁邊要站得到人
            Pos cage = b.jailCages.get(f);
            if (cage == null) errors.add(fl + " 沒有監獄");
        }

        // 容器：旁邊至少有一格走得到
        Set<Pos> all = new HashSet<>();
        for (Set<Pos> r : reached) all.addAll(r);
        int usable = 0;
        for (Pos c : b.containers) {
            Pos stand = new Pos(c.x(), c.y(), c.z());
            boolean ok = false;
            for (int[] d : DIRS) if (all.contains(stand.add(d[0], 0, d[1]))) ok = true;
            if (ok) usable++;
        }
        if (usable < s.minContainers())
            errors.add("走得到的容器只有 " + usable + " 個，至少要 " + s.minContainers() + " 個（門禁卡＋上鎖箱＋道具）");

        for (Pos p : b.powerCandidates) {
            if (all.contains(p)) continue;
            int fl = (p.y() - L.floorY(0) - 1) / L.floorHeight;
            StringBuilder nb = new StringBuilder();
            for (int[] d : DIRS) nb.append(map.type(p.add(d[0], 0, d[1]))).append(' ');
            errors.add("電源開關位置 " + p + "（" + typeAt(b, fl, p) + "）走不到；腳下 " + map.type(p.add(0, -1, 0))
                    + "，腳 " + map.type(p) + "，頭 " + map.type(p.add(0, 1, 0)) + "，四周 " + nb);
        }
        if (b.powerCandidates.isEmpty()) errors.add("沒有可以放電源開關的房間");

        for (Building.Gate g : b.gates) {
            boolean readerOk = false;
            for (int[] d : DIRS) if (all.contains(g.reader().add(d[0], 0, d[1]))) readerOk = true;
            if (!readerOk) errors.add((g.floor() + 1) + "F 大門讀卡機走不到");
        }
        if (b.ghostRoom == null) errors.add("沒有鬼房");

        // 出生點候選：可以站的格子，扣掉樓梯間、監獄、鬼房
        if (errors.isEmpty()) {
            for (int f = 0; f < L.floors; f++) {
                Set<Pos> spawns = new HashSet<>();
                for (Pos p : reached.get(f)) {
                    RoomType t = typeAt(b, f, p);
                    if (t == RoomType.STAIRWELL || t == RoomType.JAIL || t == RoomType.GHOST_ROOM) continue;
                    spawns.add(p);
                }
                b.spawnTiles.put(f, spawns);
                b.walkTiles.put(f, reached.get(f));
            }
        }
        return errors;
    }

    private static Set<Pos> flood(Layout L, BlockMap map, int f, Set<Pos> extraPassable) {
        Set<Pos> seen = new HashSet<>();
        Pos start = L.crossing(f);
        if (!standable(map, start, extraPassable)) return seen;
        ArrayDeque<Pos> q = new ArrayDeque<>();
        q.add(start);
        seen.add(start);
        while (!q.isEmpty()) {
            Pos p = q.poll();
            for (int[] d : DIRS) {
                Pos n = p.add(d[0], 0, d[1]);
                if (n.x() <= L.ox || n.z() <= L.oz || n.x() >= L.ox + L.size - 1 || n.z() >= L.oz + L.size - 1) continue;
                if (seen.contains(n) || !standable(map, n, extraPassable)) continue;
                seen.add(n);
                q.add(n);
            }
        }
        return seen;
    }

    /** p 是腳的位置：腳下實心、腳和頭兩格可以通過。 */
    private static boolean standable(BlockMap map, Pos p, Set<Pos> extra) {
        return map.solid(p.add(0, -1, 0))
                && (map.passable(p) || extra.contains(p))
                && (map.passable(p.add(0, 1, 0)) || extra.contains(p.add(0, 1, 0)));
    }

    private static boolean cellReached(Layout L, Set<Pos> set, int f, int cx, int cz) {
        int y = L.floorY(f) + 1;
        for (int lx = 1; lx <= L.room - 2; lx++)
            for (int lz = 1; lz <= L.room - 2; lz++)
                if (set.contains(L.cell(cx, cz, lx, y, lz))) return true;
        return false;
    }

    private static RoomType typeAt(Building b, int f, Pos p) {
        Layout L = b.layout;
        int rx = p.x() - L.ox, rz = p.z() - L.oz;
        for (int cx = 0; cx < L.n; cx++) {
            int sx = L.cellStart(cx);
            if (rx < sx || rx >= sx + L.room) continue;
            for (int cz = 0; cz < L.n; cz++) {
                int sz = L.cellStart(cz);
                if (rz >= sz && rz < sz + L.room) return b.type(f, cx, cz);
            }
        }
        return null; // 走廊
    }
}
