package com.ghosthunt.game;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;

import java.util.function.Supplier;

/** 對局進行中的事件處理。沒有對局時什麼都不做。 */
public final class GameListener implements Listener {

    private final Supplier<Match> match;

    public GameListener(Supplier<Match> match) {
        this.match = match;
    }

    private Match running() {
        Match m = match.get();
        return m == null || m.ended ? null : m;
    }

    /** 鬼打到逃生者就算抓到；所有參加者都不會受傷、不會被擊退。 */
    @EventHandler(priority = EventPriority.LOW)
    public void onHit(EntityDamageByEntityEvent e) {
        Match m = running();
        if (m == null || !(e.getEntity() instanceof Player victim) || !m.isParticipant(victim)) return;
        e.setCancelled(true);
        if (e.getDamager() instanceof Player attacker && m.isGhost(attacker) && m.isFreeSurvivor(victim)) {
            m.onGhostHit(victim);
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onDamage(EntityDamageEvent e) {
        Match m = running();
        if (m != null && e.getEntity() instanceof Player p && m.isParticipant(p)) e.setCancelled(true);
    }

    @EventHandler
    public void onHunger(FoodLevelChangeEvent e) {
        Match m = running();
        if (m != null && e.getEntity() instanceof Player p && m.isParticipant(p)) e.setCancelled(true);
    }

    /** 逃生者可以按 Q 把道具丟給隊友；門禁卡、鬼的物品、其他東西不能丟。 */
    @EventHandler
    public void onDrop(PlayerDropItemEvent e) {
        Match m = running();
        if (m == null || !m.isParticipant(e.getPlayer())) return;
        String id = Items.id(e.getItemDrop().getItemStack());
        boolean loot = id != null && Items.LOOT.contains(id);
        if (!loot || !m.isFreeSurvivor(e.getPlayer())) {
            e.setCancelled(true);
            if (id != null && id.startsWith(Items.KEYCARD))
                e.getPlayer().sendActionBar(net.kyori.adventure.text.Component.text("門禁卡不能丟"));
        }
    }

    /** 撿道具：鬼不能撿；逃生者帶滿了就撿不起來。 */
    @EventHandler
    public void onPickup(org.bukkit.event.entity.EntityPickupItemEvent e) {
        Match m = running();
        if (m == null || !(e.getEntity() instanceof Player p) || !m.isParticipant(p)) return;
        String id = Items.id(e.getItem().getItemStack());
        if (m.isGhost(p) || !m.isFreeSurvivor(p) || id == null
                || (Items.LOOT.contains(id) && Items.count(p, id) >= m.o.maxOf(id))) {
            e.setCancelled(true);
        }
    }

    /** 煙霧彈（雪球）落地。 */
    @EventHandler
    public void onProjectileHit(org.bukkit.event.entity.ProjectileHitEvent e) {
        Match m = running();
        if (m == null || !(e.getEntity() instanceof org.bukkit.entity.Snowball ball)) return;
        if (!Items.SMOKE.equals(Items.id(ball.getItem()))) return;
        m.items.onSmokeHit(ball.getLocation());
    }

    /** 保險：參加者不會打開容器的介面（翻箱子由插件處理）。 */
    @EventHandler
    public void onOpen(org.bukkit.event.inventory.InventoryOpenEvent e) {
        Match m = running();
        if (m != null && e.getPlayer() instanceof Player p && m.isParticipant(p)
                && e.getInventory().getHolder() instanceof org.bukkit.block.Barrel) e.setCancelled(true);
    }

    /** F 鍵（切換副手）＝緊急衝刺。 */
    @EventHandler
    public void onSwap(PlayerSwapHandItemsEvent e) {
        Match m = running();
        if (m == null || !m.isParticipant(e.getPlayer())) return;
        e.setCancelled(true);
        m.onSwapHand(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onInteract(PlayerInteractEvent e) {
        Match m = running();
        if (m == null || e.getHand() != EquipmentSlot.HAND) return;
        Player p = e.getPlayer();
        if (!m.isParticipant(p)) return;
        Action a = e.getAction();
        if (m.isGhost(p) && (a == Action.LEFT_CLICK_AIR || a == Action.LEFT_CLICK_BLOCK)) {
            m.onGhostSwing(); // 揮空也會疲勞
            return;
        }
        if (a != Action.RIGHT_CLICK_AIR && a != Action.RIGHT_CLICK_BLOCK) return;
        String item = Items.id(p.getInventory().getItemInMainHand());
        org.bukkit.block.Block clicked = e.getClickedBlock();

        // 場景互動優先：木桶、總電源拉桿、讀卡機
        if (a == Action.RIGHT_CLICK_BLOCK && clicked != null) {
            if (m.obj.isContainer(clicked)) {
                e.setCancelled(true);
                if (!m.isGhost(p)) m.obj.onContainer(p, clicked);
                return;
            }
            if (m.obj.isLever(clicked)) {
                e.setCancelled(true);
                m.obj.onLever(p);
                return;
            }
            Objectives.GateState gs = m.obj.gateAt(clicked);
            if (gs != null) {
                e.setCancelled(true);
                m.obj.onReader(p, gs);
                return;
            }
            // 裝飾方塊（高爐、煙燻爐、投擲器、織布機……）不給開介面，免得以為那些也能翻；門照常可以開關。
            // 只擋方塊本身，手上的道具還是可以用（例如對著牆丟煙霧彈）。
            if (!org.bukkit.Tag.DOORS.isTagged(clicked.getType())) e.setUseInteractedBlock(org.bukkit.event.Event.Result.DENY);
        }

        if (m.isGhost(p)) {
            if (Items.LIGHTS_OUT.equals(item) && m.nightmare != null) {
                e.setCancelled(true);
                m.nightmare.use(p);
                return;
            }
            if (Items.MOVE_CARD.equals(item)) {
                e.setCancelled(true);
                m.obj.onGhostMoveCard(p);
                return;
            }
            if (a == Action.RIGHT_CLICK_BLOCK && (m.tryWidenHole(p, clicked) || m.tryBreakBarricade(p, clicked))) e.setCancelled(true);
            return;
        }
        if (Items.BARRICADE.equals(item) && a == Action.RIGHT_CLICK_BLOCK) {
            if (m.tryPlaceBarricade(p, clicked)) e.setCancelled(true);
            return;
        }
        if (item != null && m.items.use(p, item)) e.setCancelled(true);
    }

    /** 鬼鑽不過還沒打大的蹲伏破洞。 */
    @EventHandler(ignoreCancelled = true)
    public void onMove(org.bukkit.event.player.PlayerMoveEvent e) {
        Match m = running();
        if (m == null || !e.hasChangedBlock()) return;
        if (m.blocksGhost(e.getPlayer(), e.getTo())) e.setCancelled(true);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Match m = running();
        if (m != null && m.isParticipant(e.getPlayer())) m.onQuit(e.getPlayer());
    }
}
