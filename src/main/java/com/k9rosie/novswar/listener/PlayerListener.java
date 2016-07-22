package com.k9rosie.novswar.listener;


import com.k9rosie.novswar.NovsWar;
import com.k9rosie.novswar.NovsWarPlugin;
import com.k9rosie.novswar.event.NovsWarPlayerKillEvent;
import com.k9rosie.novswar.game.Game;
import com.k9rosie.novswar.manager.PlayerManager;
import com.k9rosie.novswar.model.NovsPlayer;
import com.k9rosie.novswar.model.NovsRegion;
import com.k9rosie.novswar.model.NovsTeam;
import com.k9rosie.novswar.model.NovsWorld;
import com.k9rosie.novswar.util.Messages;
import com.k9rosie.novswar.util.RegionType;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.HashSet;

public class PlayerListener implements Listener {

    private NovsWarPlugin plugin;
    private NovsWar novswar;
    private Game game;
    private PlayerManager playerManager;

    public PlayerListener(NovsWarPlugin plugin) {
        this.plugin = plugin;
        novswar = plugin.getNovswarInstance();
        game = novswar.getGameHandler().getGame();
        playerManager = novswar.getPlayerManager();
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player bukkitPlayer = event.getPlayer();
        NovsPlayer player = playerManager.createNovsPlayer(bukkitPlayer); //handles assignment to default team
        NovsTeam defaultTeam = novswar.getTeamManager().getDefaultTeam();

        novswar.getDatabase().fetchPlayerData(player);
        //game.getNeutralTeamData().getPlayers().add(player);
        //game.getNeutralTeamData().getScoreboardTeam().addEntry(player.getBukkitPlayer().getDisplayName());
        novswar.getTeamManager().getDefaultTeam().getScoreboardTeam().addEntry(player.getBukkitPlayer().getDisplayName());
        bukkitPlayer.setScoreboard(game.getScoreboard().getBukkitScoreboard());
        bukkitPlayer.teleport(novswar.getWorldManager().getLobbyWorld().getTeamSpawns().get(defaultTeam));

        player.getStats().incrementConnects();
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onAsyncPlayerChat(AsyncPlayerChatEvent event) {
        Player bukkitPlayer = event.getPlayer();
        NovsPlayer player = playerManager.getNovsPlayer(bukkitPlayer);
        //NovsTeam team = game.getPlayerTeam(player);
        NovsTeam team = player.getTeam();

        event.setFormat(team.getColor() + bukkitPlayer.getDisplayName() + ChatColor.WHITE + ": " + event.getMessage());
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player bukkitPlayer = event.getPlayer();
        NovsPlayer player = playerManager.getNovsPlayer(bukkitPlayer);
        //NovsTeam team = game.getPlayerTeam(player);

        novswar.getDatabase().flushPlayerData(player);
        /*if (player.getTeam().equals(novswar.getTeamManager().getDefaultTeam())) {
            game.getNeutralTeamData().getPlayers().remove(player);
        } else {
            game.getTeamData().get(team).getPlayers().remove(player);
        }*/
        playerManager.getPlayers().remove(player);
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerDamageByPlayer(EntityDamageByEntityEvent event) {
        Player victimBukkitPlayer;
        Player attackerBukkitPlayer = null;
        boolean arrowDeath = false;

        if (event.getEntity() instanceof Player) {
        	victimBukkitPlayer = (Player) event.getEntity();

            if (event.getDamager() instanceof Arrow) {
                Arrow arrow = (Arrow) event.getDamager();

                if (arrow.getShooter() instanceof Player) {
                	attackerBukkitPlayer = (Player) arrow.getShooter();
                    arrowDeath = true;
                }
            } else if (event.getDamager() instanceof Player) {
            	attackerBukkitPlayer = (Player) event.getDamager();
            } else { // if neither player nor arrow
                return;
            }

            NovsPlayer victim = playerManager.getNovsPlayer(victimBukkitPlayer);
            NovsPlayer attacker = playerManager.getNovsPlayer(attackerBukkitPlayer);
            NovsTeam victimTeam = victim.getTeam();
            NovsTeam attackerTeam = attacker.getTeam();

            if (attackerTeam.equals(victimTeam)) {
                if (!attackerTeam.getFriendlyFire()) {
                    event.setCancelled(true);
                    return;
                }
            }

            if (!victim.getTeam().canBeDamaged()) {
                event.setCancelled(true);
                return;
            }

            double damage = event.getFinalDamage();
            victim.getStats().incrementDamageTaken(damage);
            attacker.getStats().incrementDamageGiven(damage);

            // if damage is fatal
            if (victimBukkitPlayer.getHealth() - damage <= 0) {
                event.setCancelled(true);

                NovsWarPlayerKillEvent invokeEvent = new NovsWarPlayerKillEvent(attacker, victim, attackerTeam, victimTeam, game);
                Bukkit.getPluginManager().callEvent(invokeEvent);

                String deathMessage;
                if (arrowDeath) {
                    deathMessage = Messages.SHOT_MESSAGE.toString();
                } else {
                    deathMessage = Messages.KILL_MESSAGE.toString();
                }
                deathMessage = deathMessage.replace("%killed_tcolor%", victimTeam.getColor().toString())
                        .replace("%killed%", victimBukkitPlayer.getDisplayName())
                        .replace("%killer_tcolor%", attackerTeam.getColor().toString())
                        .replace("%killer%", attackerBukkitPlayer.getDisplayName());
                
                System.out.println("Death Message: "+deathMessage);
                for (NovsPlayer p : playerManager.getPlayers()) {
                    //if (p.canSeeDeathMessages()) {
                	if (true) { //THIS IS TO DEBUG WHETHER PLAYERS HAVE deathMessages = false
                        p.getBukkitPlayer().sendMessage(deathMessage);
                    }
                }

                if (arrowDeath) {
                	attacker.getStats().incrementArrowKills();
                    victim.getStats().incrementArrowDeaths();
                } else {
                	attacker.getStats().incrementKills();
                    victim.getStats().incrementDeaths();
                }

                game.scheduleDeath(victim, game.getGamemode().getDeathTime());
            }
        }
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player) {
            Player bukkitPlayer = (Player) event.getEntity();
            NovsPlayer player = playerManager.getNovsPlayer(bukkitPlayer);

            if (!player.getTeam().canBeDamaged()) {
                event.setCancelled(true);
                return;
            }

            double damage = event.getFinalDamage();
            player.getStats().incrementDamageTaken(damage);

            // if damage is fatal
            if (bukkitPlayer.getHealth() - damage <= 0) {
                event.setCancelled(true);

                String deathMessage = Messages.DEATH_MESSAGE.toString();
                deathMessage = deathMessage.replace("%player_tcolor%", player.getTeam().getColor().toString())
                .replace("%player%", bukkitPlayer.getDisplayName());

                for (NovsPlayer p : playerManager.getPlayers()) {
                    if (p.canSeeDeathMessages()) {
                        p.getBukkitPlayer().sendMessage(deathMessage);
                    }
                }

                player.getStats().incrementSuicides();

                game.scheduleDeath(player, game.getGamemode().getDeathTime());
            }
        }
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerInteract(PlayerInteractEvent event) {
        Player bukkitPlayer = event.getPlayer();
        NovsPlayer player = playerManager.getNovsPlayer(bukkitPlayer);

        if (player.isSettingRegion()) {
            if (event.getClickedBlock() == null) {
                bukkitPlayer.sendMessage("You need to click a block");
                event.setCancelled(true);
                return;
            }
            Location location = event.getClickedBlock().getLocation();

            if (novswar.getWorldManager().getWorld(bukkitPlayer.getWorld()) == null) {
                bukkitPlayer.sendMessage("The world you're in isn't enabled in NovsWar.");
                event.setCancelled(true);
                return;
            }

            if (player.getCornerOneBuffer() == null) {
                player.setCornerOneBuffer(location);
                bukkitPlayer.sendMessage("Setting corner two...");
            } else if (player.getCornerOneBuffer() != null) {
                NovsWorld world = novswar.getWorldManager().getWorld(bukkitPlayer.getWorld());
                NovsRegion region = new NovsRegion(world,
                        player.getCornerOneBuffer(), location, player.getRegionTypeBuffer());

                world.getRegions().put(player.getRegionNameBuffer(), region);

                bukkitPlayer.sendMessage("Region set");
                player.setCornerOneBuffer(null);
                player.setRegionTypeBuffer(null);
                player.setRegionNameBuffer(null);
                player.setSettingRegion(false);
            }

            event.setCancelled(true);
        }

    }
    
    @EventHandler
	public void onInventoryClick(InventoryClickEvent event) {
		Inventory voter = Game.getBallotBox().getBallots();
		Player player = (Player) event.getWhoClicked();
		int slot = event.getSlot();
		ItemStack clicked = event.getCurrentItem();
		Inventory inventory = event.getInventory();
		//check to make sure click occurs inside voting Inventory screen
		if(inventory.getName().equals(voter.getName())) {
			//check that the click was on a BEDROCK voting item
			if(clicked.getType().equals(Material.BEDROCK)){
				Game.getBallotBox().recordResult(slot);
				player.closeInventory();
				player.sendMessage("You voted for "+clicked.getItemMeta().getDisplayName());
				NovsPlayer nplayer = novswar.getPlayerManager().getNovsPlayer(player);
				nplayer.setVoted(true);
			}
			event.setCancelled(true);
		}
	}
}
