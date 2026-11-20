package com.ghosthunt.game;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.ArrayList;
import java.util.List;

/**
 * 畫面右邊的狀態欄（大家看到的一樣）＋把名牌藏起來。
 * 原版玩家頭上的名字會穿牆顯示，躲起來的人一下就被看到，所以用隊伍設定把名牌關掉。
 */
final class Hud {

    private final Match m;
    private final Scoreboard board;
    private final Objective side;
    private final Team team;

    Hud(Match m) {
        this.m = m;
        this.board = Bukkit.getScoreboardManager().getNewScoreboard();
        this.side = board.registerNewObjective("ghosthunt", Criteria.DUMMY, Component.text("鬼抓人：大樓逃生", NamedTextColor.RED));
        side.setDisplaySlot(DisplaySlot.SIDEBAR);
        this.team = board.registerNewTeam("players");
        team.setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.NEVER);
        team.setOption(Team.Option.COLLISION_RULE, Team.OptionStatus.NEVER);
        team.setCanSeeFriendlyInvisibles(false);
    }

    void show() {
        for (Player p : m.participants()) {
            team.addEntry(p.getName());
            p.setScoreboard(board);
        }
        update();
    }

    void update() {
        for (String e : new ArrayList<>(board.getEntries())) {
            if (!team.hasEntry(e)) board.resetScores(e);
        }
        List<String> lines = new ArrayList<>();
        long secs = m.tick / 20;
        lines.add("時間 " + secs / 60 + ":" + String.format("%02d", secs % 60) + "／" + m.s.timeLimit() / 20 / 60 + ":00");
        lines.add("電源：" + (m.obj.powerOn ? "§a開" : "§c關"));
        for (Objectives.GateState gs : m.obj.gates) {
            String st;
            if (gs.opened) st = "§a開了";
            else if (gs.running()) st = "§e倒數 " + (gs.remaining / 20 + 1) + " 秒";
            else if (gs.unlocked) st = "§6故障，要重新啟動";
            else st = "§7鎖著（要刷卡）";
            lines.add("大門 " + (gs.g.floor() + 1) + "F：" + st);
        }
        int escaped = 0, jailed = 0, out = 0, free = 0;
        for (Match.Survivor sv : m.survivors.values()) {
            switch (sv.status) {
                case ESCAPED -> escaped++;
                case JAILED -> jailed++;
                case ELIMINATED -> out++;
                case FREE -> free++;
            }
        }
        lines.add("§r ");
        lines.add("自由 " + free + "｜監獄 " + jailed);
        lines.add("逃出 " + escaped + "｜淘汰 " + out);
        int score = lines.size();
        for (String l : lines) side.getScore(l).setScore(score--);
    }

    void hide() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getScoreboard() == board) p.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
        }
    }
}
