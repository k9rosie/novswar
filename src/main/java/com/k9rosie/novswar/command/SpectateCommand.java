package com.k9rosie.novswar.command;

import java.util.ArrayList;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import com.k9rosie.novswar.NovsWar;
import com.k9rosie.novswar.game.Game;
import com.k9rosie.novswar.game.GameState;
import com.k9rosie.novswar.model.NovsPlayer;
import com.k9rosie.novswar.model.NovsTeam;

public class SpectateCommand extends NovsCommand{
	private Game game;

    public SpectateCommand(NovsWar novsWar, CommandSender sender, String[] args) {
        super(novsWar, sender, args);
        game = getNovsWar().getGameHandler().getGame();
    }

    public void execute() {
        NovsPlayer player = getNovsWar().getPlayerManager().getPlayers().get((Player) getSender());
        NovsTeam defaultTeam = getNovsWar().getTeamManager().getDefaultTeam();
        
        if(player.isSpectating()) {
        	//Return the player to the lobby
        	player.setSpectating(false); //must occur BEFORE gamemode change
        	player.getBukkitPlayer().teleport(getNovsWar().getWorldManager().getLobbyWorld().getTeamSpawns().get(defaultTeam));
            player.getBukkitPlayer().setGameMode(GameMode.SURVIVAL);
            
        } else {
        	if(player.getTeam().equals(defaultTeam)) {
        		//Begin spectating
            	if(game.getGameState().equals(GameState.DURING_GAME) || game.getGameState().equals(GameState.PRE_GAME)) {
            		ArrayList<NovsPlayer> inGamePlayers = getNovsWar().getPlayerManager().getInGamePlayers();
            		NovsPlayer target = inGamePlayers.get(0);
            		player.setSpectatorTarget(target);
            		target.getSpectatorObservers().add(player);
            		player.getBukkitPlayer().setGameMode(GameMode.SPECTATOR);
            		player.setSpectating(true); //must occur AFTER gamemode change
            		player.getBukkitPlayer().teleport(target.getBukkitPlayer().getLocation());
            		player.getBukkitPlayer().setSpectatorTarget(target.getBukkitPlayer());
            		player.getBukkitPlayer().sendMessage("Spectate next player with LSHIFT. F5 to change view.");
            		player.getBukkitPlayer().sendMessage("Spectating "+target.getBukkitPlayer().getName());
            		Bukkit.broadcastMessage(player.getBukkitPlayer().getName()+" is spectating the round!");

            	} else {
            		player.getBukkitPlayer().sendMessage("You can only spectate during the round");
            	}
        	} else {
        		player.getBukkitPlayer().sendMessage("You can only spectate while in the Lobby");
        	}
        }
    }
}
