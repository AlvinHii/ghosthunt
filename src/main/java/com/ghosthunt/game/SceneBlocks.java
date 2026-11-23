package com.ghosthunt.game;

import org.bukkit.Bukkit;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;

import java.util.HashMap;
import java.util.Map;

/**
 * 場景模型 → 方塊狀態（tools/make_scene_blocks.py 產生的，不要手改）。
 * 資源包把紅石線（redstone_wire）的狀態換成我們的模型，用 block display 顯示；每個模型 4 個方向。
 */
public final class SceneBlocks {

    private SceneBlocks() {
    }

    private static final String[] MODELS = {
            "backup_lever",
            "backup_lever_up",
            "barricade",
            "barricade_broken",
            "barricade_mark",
            "breaker_box",
            "breaker_box_fixed",
            "cabinet",
            "cabinet_fallen",
            "card_reader",
            "card_reader_countdown",
            "card_reader_idle",
            "card_reader_open",
            "cctv_camera",
            "cctv_monitor",
            "cctv_monitor_on",
            "container",
            "container_locked",
            "container_locked_pried",
            "container_open",
            "crawl_hole",
            "crawl_hole_big",
            "door_jam",
            "elevator_door",
            "elevator_door_open",
            "elevator_panel",
            "elevator_panel_1",
            "elevator_panel_2",
            "exit_gate",
            "exit_gate_open",
            "golf_cart",
            "golf_cart_high",
            "hatch",
            "hatch_open",
            "jail_cage",
            "locked_door",
            "locked_door_open",
            "note_stand",
            "note_stand_taken",
            "pa_desk",
            "power_panel",
            "power_panel_on",
            "radio",
            "radio_calling",
            "security_terminal",
            "security_terminal_auth",
            "security_terminal_on",
            "sewer_grate",
            "sewer_grate_open",
            "shutter",
            "shutter_open",
            "speaker",
            "steam_valve",
            "vending_machine",
            "vending_machine_off",
            "welcome_bell"
    };
    private static final String[] SIDES = {"none", "side", "up"};
    private static final Map<String, Integer> INDEX = new HashMap<>();

    static {
        for (int i = 0; i < MODELS.length; i++) INDEX.put(MODELS[i], i);
    }

    public static boolean has(String model) {
        return INDEX.containsKey(model);
    }

    /** 模型正面朝 front（東南西北）的方塊狀態；沒有這個模型就回傳 null。 */
    public static BlockData data(String model, BlockFace front) {
        Integer k = INDEX.get(model);
        if (k == null) return null;
        int r = switch (front) {
            case EAST -> 1;
            case SOUTH -> 2;
            case WEST -> 3;
            default -> 0;
        };
        int i = k * 4 + r;
        int power = i / 81, rest = i % 81;
        String s = "minecraft:redstone_wire[east=" + SIDES[rest / 27] + ",north=" + SIDES[rest / 9 % 3]
                + ",power=" + power + ",south=" + SIDES[rest / 3 % 3] + ",west=" + SIDES[rest % 3] + "]";
        return Bukkit.createBlockData(s);
    }
}
