package com.ghosthunt.game.ai;

import com.ghosthunt.game.ai.TargetPicker.Candidate;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class TargetPickerTest {

    private static final UUID A = UUID.randomUUID(), B = UUID.randomUUID();
    private final TargetPicker picker = new TargetPicker(0.6, 20, 6.0, 6.0);

    @Test
    void noTarget_picksNearest() {
        assertEquals(B, picker.pick(null, -1, 100, List.of(new Candidate(A, 10, false, false), new Candidate(B, 7, false, false))));
    }

    @Test
    void sticksToCurrent_unlessOtherMuchCloser() {
        // 正在追 A（10 格），B 在 7 格：不換（7 > 10 × 0.6）
        assertEquals(A, picker.pick(A, 100, 100, List.of(new Candidate(A, 10, false, false), new Candidate(B, 7, false, false))));
        // B 在 5 格：換（5 < 6）
        assertEquals(B, picker.pick(A, 100, 100, List.of(new Candidate(A, 10, false, false), new Candidate(B, 5, false, false))));
    }

    @Test
    void currentJustLost_keepsCurrentForOneSecond() {
        // A 剛看不到（10 tick 前），B 看得到：還不換，繼續追 A
        assertEquals(A, picker.pick(A, 90, 100, List.of(new Candidate(B, 8, false, false))));
        // A 看不到超過 1 秒：換成 B
        assertEquals(B, picker.pick(A, 70, 100, List.of(new Candidate(B, 8, false, false))));
    }

    @Test
    void busySurvivor_getsBonus() {
        // A 9 格但正在授權（看得到的行為）：有效距離 3，比 B 的 5 格近
        assertEquals(A, picker.pick(null, -1, 100, List.of(new Candidate(A, 9, true, false), new Candidate(B, 5, false, false))));
    }

    @Test
    void justRescued_getsPenalty() {
        // A 4 格但剛被救出來：有效距離 10，B 7 格比較近
        assertEquals(B, picker.pick(null, -1, 100, List.of(new Candidate(A, 4, false, true), new Candidate(B, 7, false, false))));
    }

    @Test
    void nobodyVisible_andCurrentLongLost_returnsNull() {
        assertNull(picker.pick(A, 10, 100, List.of()));
    }
}
