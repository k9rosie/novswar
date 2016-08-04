package com.k9rosie.novswar.command;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import com.k9rosie.novswar.NovsWar;
import com.k9rosie.novswar.model.NovsPlayer;

public class HelpCommand extends NovsCommand {

    public HelpCommand(NovsWar novsWar, CommandSender sender, String[] args) {
        super(novsWar, sender, args);
    }

    public void execute() {
        NovsPlayer player = getNovsWar().getPlayerManager().getPlayers().get((Player) getSender());
        String message = "";
        for(CommandType cmd : CommandType.values()) {
        	message = "/nw "+cmd.toString().toLowerCase()+" "+cmd.arguments()+": "+cmd.description();
        	player.getBukkitPlayer().sendMessage(message);
        }
    }
}
