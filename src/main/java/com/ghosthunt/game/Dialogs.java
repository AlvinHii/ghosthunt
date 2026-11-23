package com.ghosthunt.game;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * Paper 的原生對話框（Dialog API，2026-11-23）：下水道輸入密碼、監視器切換、遊戲結束的結算畫面。
 * 對話框開著時遊戲照跑（不暫停），所以遊戲中只在玩家自己按的時候才跳。
 */
public final class Dialogs {

    private Dialogs() {
    }

    /** 給 game 套件外面用（/gh selftest）。 */
    public static String check(Plugin plugin) {
        return selfCheck(plugin);
    }

    /** 按鈕按下去回到插件（主執行緒）；view 是對話框裡輸入框的內容。 */
    static ActionButton button(Plugin plugin, Component label, Component tooltip, int width, BiConsumer<Player, DialogResponseView> onClick) {
        ActionButton.Builder b = ActionButton.builder(label).width(width).action(DialogAction.customClick((view, audience) -> {
            if (audience instanceof Player p) Bukkit.getScheduler().runTask(plugin, () -> onClick.accept(p, view));
        }, ClickCallback.Options.builder().uses(1).lifetime(Duration.ofMinutes(10)).build()));
        if (tooltip != null) b.tooltip(tooltip);
        return b.build();
    }

    /** 按鈕執行一個指令（例如 /gh lobby）。 */
    static ActionButton command(Component label, Component tooltip, int width, String command) {
        ActionButton.Builder b = ActionButton.builder(label).width(width).action(DialogAction.staticAction(ClickEvent.runCommand(command)));
        if (tooltip != null) b.tooltip(tooltip);
        return b.build();
    }

    /** 關掉對話框的按鈕（什麼都不做）。 */
    static ActionButton close(Component label) {
        return ActionButton.builder(label).width(120).build();
    }

    /**
     * 多個按鈕的對話框。
     * @param body   文字（每個元素一段）
     * @param inputs 輸入框（可以是空的）
     * @param exit   最下面的離開按鈕（Esc 也是它）
     */
    static void show(Player p, Component title, List<Component> body, List<? extends DialogInput> inputs,
                     List<ActionButton> buttons, ActionButton exit, int columns) {
        p.showDialog(build(title, body, inputs, buttons, exit, columns));
    }

    /** /gh selftest：三種對話框都建一次（不送出），確認伺服器接受。 */
    static String selfCheck(Plugin plugin) {
        build(Component.text("下水道鐵柵"), List.of(Component.text("測試")),
                List.of(DialogInput.text("code", Component.text("密碼")).maxLength(3).width(120).build()),
                List.of(button(plugin, Component.text("輸入"), null, 120, (p, v) -> { })), close(Component.text("取消")), 1);
        build(Component.text("監視器"), List.of(Component.text("測試")), List.of(),
                List.of(button(plugin, Component.text("大廳"), Component.text("切過去"), 110, (p, v) -> { })), close(Component.text("取消")), 3);
        build(Component.text("遊戲結束"), List.of(Component.text("測試")), List.of(),
                List.of(command(Component.text("回大廳"), null, 150, "/gh lobby")), close(Component.text("關閉")), 1);
        return "對話框（下水道、監視器、結算）建立成功";
    }

    static Dialog build(Component title, List<Component> body, List<? extends DialogInput> inputs,
                        List<ActionButton> buttons, ActionButton exit, int columns) {
        List<DialogBody> bodies = new ArrayList<>();
        for (Component c : body) bodies.add(DialogBody.plainMessage(c, 300));
        Dialog d = Dialog.create(f -> f.empty()
                .base(DialogBase.builder(title).canCloseWithEscape(true).pause(false)
                        .afterAction(DialogBase.DialogAfterAction.CLOSE)
                        .body(bodies).inputs(inputs).build())
                .type(DialogType.multiAction(buttons).exitAction(exit).columns(Math.max(1, columns)).build()));
        return d;
    }
}
