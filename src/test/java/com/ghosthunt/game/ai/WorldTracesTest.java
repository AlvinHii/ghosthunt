package com.ghosthunt.game.ai;

import com.ghosthunt.game.ai.WorldTraces.Type;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class WorldTracesTest {

    @Test
    void firstSight_isNotATrace() {
        WorldTraces t = new WorldTraces();
        assertNull(t.observe(Type.DOOR, 3, 0, 1, 65, 1, 100));
    }

    @Test
    void changedState_isATrace() {
        WorldTraces t = new WorldTraces();
        t.observe(Type.DOOR, 3, 0, 1, 65, 1, 100);
        WorldTraces.Trace tr = t.observe(Type.DOOR, 3, 1, 1, 65, 1, 400);
        assertNotNull(tr);
        assertEquals(Type.DOOR, tr.type());
        assertEquals(3, tr.id());
        assertEquals(300, tr.gapTicks());
        assertEquals(1.0, tr.usefulness(), 1e-9);
    }

    @Test
    void sameState_isNotATrace() {
        WorldTraces t = new WorldTraces();
        t.observe(Type.BARREL, 1, 0, 1, 65, 1, 100);
        assertNull(t.observe(Type.BARREL, 1, 0, 1, 65, 1, 200));
    }

    @Test
    void ghostCaused_isNotATrace() {
        WorldTraces t = new WorldTraces();
        t.observe(Type.DOOR, 3, 0, 1, 65, 1, 100);
        t.ghostChanged(Type.DOOR, 3, 1, 100);
        assertNull(t.observe(Type.DOOR, 3, 1, 1, 65, 1, 200));
        // 之後別人再改就算
        assertNotNull(t.observe(Type.DOOR, 3, 0, 1, 65, 1, 300));
    }

    @Test
    void ghostCaused_delayedChange_isNotATrace() {
        // 鬼開始打大洞（要 4 秒）：中間看到還是原本的狀態，打完才變；這不是別人留下的痕跡
        WorldTraces t = new WorldTraces();
        t.observe(Type.HOLE, 6, 0, 1, 65, 1, 0);
        t.ghostChanged(Type.HOLE, 6, 1, 0);
        assertNull(t.observe(Type.HOLE, 6, 0, 1, 65, 1, 20));
        assertNull(t.observe(Type.HOLE, 6, 1, 1, 65, 1, 100));
    }

    @Test
    void ghostMarker_expires() {
        // 鬼要撞開卡門但動作被取消：記號 10 秒後失效，之後逃生者造成同樣的狀態還是算痕跡
        WorldTraces t = new WorldTraces();
        t.observe(Type.DOOR, 1, 2, 1, 65, 1, 0);
        t.ghostChanged(Type.DOOR, 1, 1, 0);
        assertNotNull(t.observe(Type.DOOR, 1, 1, 1, 65, 1, 300));
    }

    @Test
    void oldGap_isLessUseful() {
        WorldTraces t = new WorldTraces();
        t.observe(Type.CABINET, 0, 0, 1, 65, 1, 0);
        WorldTraces.Trace tr = t.observe(Type.CABINET, 0, 1, 1, 65, 1, 1300);
        assertEquals(0.25, tr.usefulness(), 1e-9);
    }

    @Test
    void differentTypesSameId_areSeparate() {
        WorldTraces t = new WorldTraces();
        t.observe(Type.DOOR, 0, 0, 1, 65, 1, 0);
        assertNull(t.observe(Type.HOLE, 0, 1, 1, 65, 1, 10)); // 第一次看到這個洞
    }
}
