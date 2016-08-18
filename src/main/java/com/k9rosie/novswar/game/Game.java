package com.k9rosie.novswar.game;


import com.k9rosie.novswar.NovsWar;
import com.k9rosie.novswar.event.NovsWarEndGameEvent;
import com.k9rosie.novswar.event.NovsWarJoinGameEvent;
import com.k9rosie.novswar.event.NovsWarJoinTeamEvent;
import com.k9rosie.novswar.event.NovsWarNewGameEvent;
import com.k9rosie.novswar.event.NovsWarPlayerAssistEvent;
import com.k9rosie.novswar.event.NovsWarPlayerDeathEvent;
import com.k9rosie.novswar.event.NovsWarPlayerKillEvent;
import com.k9rosie.novswar.event.NovsWarPlayerRespawnEvent;
import com.k9rosie.novswar.event.NovsWarTeamVictoryEvent;
import com.k9rosie.novswar.gamemode.Gamemode;
import com.k9rosie.novswar.model.NovsPlayer;
import com.k9rosie.novswar.model.NovsTeam;
import com.k9rosie.novswar.model.NovsWorld;
import com.k9rosie.novswar.util.Messages;
import com.k9rosie.novswar.util.SendTitle;

import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Team;

import java.util.*;

public class Game {
    private GameHandler gameHandler;
    private NovsWorld world;
    private Gamemode gamemode;
    private GameState gameState;
    private boolean paused;
    private ArrayList<NovsTeam> enabledTeams;
    private HashMap<NovsPlayer, DeathTimer> deathTimers;
    private NovsWar novsWar;
    private GameTimer gameTimer;
    private GameScoreboard scoreboard;
    private BallotBox ballotBox;
    
    private int rounds;
    private int messageTime;
    private int messageTask;

    public Game(GameHandler gameHandler, NovsWorld world, Gamemode gamemode) {
        this.gameHandler = gameHandler;
        this.world = world;
        this.gamemode = gamemode;
        enabledTeams = new ArrayList<NovsTeam>();
        deathTimers = new HashMap<NovsPlayer, DeathTimer>();
        gameState = GameState.WAITING_FOR_PLAYERS;
        paused = false;
        novsWar = gameHandler.getNovsWarInstance();
        gameTimer = new GameTimer(this);
        scoreboard = new GameScoreboard(this);
        ballotBox = new BallotBox(novsWar);
        rounds = gamemode.getRounds();
    }

    public void initialize() {
        world.saveRegionBlocks();
    	//Create default team
        NovsTeam defaultTeam = novsWar.getNovsTeamCache().getDefaultTeam();
        Team defaultScoreboardTeam = scoreboard.createScoreboardTeam(defaultTeam);
        defaultTeam.setScoreboardTeam(defaultScoreboardTeam);

        //Populate the list 'enabledTeams' with valid NovsTeam objects
        List<String> enabledTeamNames = novsWar.getNovsConfigCache().getConfig("worlds").getStringList("worlds."+world.getBukkitWorld().getName()+".enabled_teams");
        for (String validTeam : enabledTeamNames) {
            for (NovsTeam team : novsWar.getNovsTeamCache().getTeams()) {
                if (validTeam.equalsIgnoreCase(team.getTeamName())) {
                	team.setScoreboardTeam(scoreboard.createScoreboardTeam(team));
                	enabledTeams.add(team);
                }
            }
        }

        for (NovsTeam team : enabledTeams) {
        	team.getNovsScore().setScore(0);	//Resets all team's scores
        }


        for (NovsPlayer player : novsWar.getNovsPlayerCache().getPlayers().values()) {
        	player.setTeam(defaultTeam); // NovsPlayer now has private NovsTeam var
        	player.setSpectating(false); //remove from spectator mode
        	player.getSpectatorObservers().clear(); //clear spectators
            player.getBukkitPlayer().teleport(novsWar.getNovsWorldCache().getLobbyWorld().getTeamSpawnLoc(defaultTeam));
            player.getBukkitPlayer().setGameMode(GameMode.SURVIVAL);
            player.getBukkitPlayer().setHealth(player.getBukkitPlayer().getMaxHealth());
            player.getBukkitPlayer().setFoodLevel(20);
        }

        scoreboard.initialize();
        
        NovsWarNewGameEvent event = new NovsWarNewGameEvent(this);
        Bukkit.getPluginManager().callEvent(event);

        waitForPlayers();
    }

    /**
     * onEndTimer()
     * Controls the next state of the game when the timer ends
     */
    public void onEndTimer() {
    	switch(gameState) {
            case PRE_GAME:
                startGame();
                break;
            case DURING_GAME:
                endGame();
                break;
            case POST_GAME:
                NovsWorld nextMap;
                if(novsWar.getNovsConfigCache().getConfig("core").getBoolean("core.voting.enabled") == true) {
                    nextMap = ballotBox.tallyResults();
                }
                else {
                    nextMap = ballotBox.nextWorld(world);
                }
                if(nextMap == null) {
                    nextMap = world;
                    novsWar.printDebug("There was a problem getting the next NovsWorld. Using previous world.");
                }
                gameHandler.newGame(nextMap);
                break;
            }
    }

    public void waitForPlayers() {
        gameState = GameState.WAITING_FOR_PLAYERS;
        gameTimer.stopTimer();
        scoreboard.setSidebarTitle("Waiting for players");
    }

    public void preGame() {
        gameState = GameState.PRE_GAME;
        world.respawnBattlefields();
        int gameTime = novsWar.getNovsConfigCache().getConfig("core").getInt("core.game.pre_game_timer");
        gameTimer.stopTimer();
        gameTimer.setTime(gameTime);
        gameTimer.startTimer();
    }

    public void startGame() {
        gameState = GameState.DURING_GAME;
        world.openIntermissionGates();
        int gameTime = gamemode.getGameTime();
        gameTimer.stopTimer();
        gameTimer.setTime(gameTime);
        gameTimer.startTimer();
        Bukkit.broadcastMessage("Starting Round");
    }

    public void pauseGame() {
    	paused = true;
        Bukkit.broadcastMessage("Pausing Round");
        world.closeIntermissionGates();
        for(NovsPlayer player : getGamePlayers()) {
        	if(player.isDead()) {
        		respawn(player);
        	} else {
        		player.getBukkitPlayer().teleport(world.getTeamSpawnLoc(player.getTeam()));
        	}
        }
        gameTimer.pauseTimer();
        clockTick(); //Update the scoreboard with paused info
    }

    public void unpauseGame() {
        if (!paused) {
            return;
        }
        gameTimer.startTimer();
        Bukkit.broadcastMessage("Resuming Round");
        world.openIntermissionGates();
    }

    /**
     * endGame()
     * Controls the team victory message and end-game stats. Respawns dead players.
     */
    public void endGame() {
        NovsWarEndGameEvent event = new NovsWarEndGameEvent(this);
        Bukkit.getServer().getPluginManager().callEvent(event);

        if (!event.isCancelled()) {
            gameState = GameState.POST_GAME;
            
            //Respawns all dead players and tp's alive players to their team spawns
            for(NovsPlayer player : getGamePlayers()) {
            	if(player.isDead()) {
            		respawn(player);
            	} else {
            		player.getBukkitPlayer().teleport(world.getTeamSpawnLoc(player.getTeam()));
            	}
            }
            
            //Determine winning teams and invoke events
            ArrayList<NovsTeam> winners = winningTeams();
            System.out.println(winners.size());
            if (winners.size() == 1) {
                NovsTeam winner = winners.get(0);
                //Display victory message for all players, given single victor
                for(NovsPlayer player : novsWar.getNovsPlayerCache().getPlayers().values()) {
                	SendTitle.sendTitle(player.getBukkitPlayer(), 0, 20*4, 20, " ", winner.getColor()+winner.getTeamName()+" §fwins!");
                }
            } else if (winners.size() > 1) {
                StringBuilder teamList = new StringBuilder();
                for (int i = 0; i < winners.toArray().length; i++) {
                    NovsTeam team = (NovsTeam) winners.toArray()[i];
                    teamList.append(team.getColor()+team.getTeamName());
                    if (i != winners.toArray().length-1) {
                        teamList.append(ChatColor.GRAY+", ");
                    }
                }
              //Display victory message for all players, given multiple victors
                for(NovsPlayer player : novsWar.getNovsPlayerCache().getPlayers().values()) {
                	SendTitle.sendTitle(player.getBukkitPlayer(), 0, 20*4, 20, " ", teamList.toString() + " §fwin!");
                }
            } else { //no winners (all teams scored 0)
            	for(NovsPlayer player : novsWar.getNovsPlayerCache().getPlayers().values()) {
                	SendTitle.sendTitle(player.getBukkitPlayer(), 0, 20*4, 20, " ", "§fDraw");
                }
            }
            for(NovsTeam winner : winners) {
            	NovsWarTeamVictoryEvent invokeEvent = new NovsWarTeamVictoryEvent(winner, this);
                Bukkit.getPluginManager().callEvent(invokeEvent);
            }
            
            //Stats generation
            for (NovsTeam team : winners) {
                for (NovsPlayer player : team.getPlayers()) {
                    player.getStats().incrementWins();
                }
            }

            for (NovsTeam team : enabledTeams) {
                for (NovsPlayer player : team.getPlayers()) {
                    player.getStats().incrementGamesPlayed();
                }
            }

            world.closeIntermissionGates();
            world.respawnBattlefields();
            int gameTime = novsWar.getNovsConfigCache().getConfig("core").getInt("core.game.post_game_timer");
            gameTimer.stopTimer();
            gameTimer.setTime(gameTime);
            gameTimer.startTimer();

        	Bukkit.getScheduler().scheduleSyncDelayedTask(novsWar.getPlugin(), new Runnable() {
                @Override
                public void run() {
                	//Remove victory message
                	for(NovsPlayer player : novsWar.getNovsPlayerCache().getPlayers().values()) {
                		SendTitle.sendTitle(player.getBukkitPlayer(), 0, 0, 0, " ", "");
                	}
                	if(rounds <= 1) {
                		//This was the final round. Prompt voting.
                		if(novsWar.getNovsConfigCache().getConfig("core").getBoolean("core.voting.enabled") == true) {
                            ballotBox.castVotes();
                        }
                	} else {
                		//Start a new round
                    	rounds--;
                    	for (NovsTeam team : enabledTeams) {
                        	team.getNovsScore().setScore(0);	//Resets all team's scores
                        }
                    	rotateTeams();
                    	preGame();
                    } 
                }
            }, 20*4);
        }
    }

    /**
     * Determines the team(s) with the highest score
     * @return ArrayList of NovsTeams with highest score
     */
    public ArrayList<NovsTeam> winningTeams() {
        ArrayList<NovsTeam> winningTeams = new ArrayList<NovsTeam>();
        int topScore = 0;
        NovsTeam topTeam = enabledTeams.get(0); //arbitrarily initialize topTeam as team 0
        //Find the team with the highest score
        for (NovsTeam team : enabledTeams) {
            if (team.getNovsScore().getScore() > topScore) {
            	topScore = team.getNovsScore().getScore();
            	topTeam = team;
            }
        }
        if(topScore != 0) {
        	winningTeams.add(topTeam);
            //Find other teams that are tied with the top team
            for (NovsTeam team : enabledTeams) {
            	if(team.equals(topTeam) == false && team.getNovsScore().getScore() == topScore) {
            		winningTeams.add(team);
            	}
            }
        }
        return winningTeams;
    }

    public void clockTick() {
        String secondsString = Integer.toString(gameTimer.getSeconds());
        String minutesString = Integer.toString(gameTimer.getMinutes());
        String gameStateString = "";

        switch (gameState) {
        case PRE_GAME :
        	gameStateString = ChatColor.GRAY + "Setting up: ";
        	break;
        case DURING_GAME :
        	gameStateString = "";
        	break;
        case POST_GAME :
        	gameStateString = ChatColor.GRAY + "Post game: ";
        	break;
    	default :
    		gameStateString = "";
    		break;
        }

        if (paused) {
            gameStateString = ChatColor.GRAY + "Game Paused ";
        }

        if (gameTimer.getSeconds() < 10) {
            secondsString = "0" + Integer.toString(gameTimer.getSeconds());
        } else if (gameTimer.getSeconds() <= 0) {
            secondsString = "00";
        }
        if (gameTimer.getMinutes() < 10) {
            minutesString = "0" + Integer.toString(gameTimer.getMinutes());
        } else if (gameTimer.getMinutes() <= 0) {
            minutesString = "00";
        }
        scoreboard.setSidebarTitle(gameStateString + ChatColor.GREEN + minutesString + ":" + secondsString);
    }

    /**
     * Checks player count on teams
     * @return True if there are the minimum required players in-game, else false
     */
    public boolean checkPlayerCount() {
        int numPlayers = 0;
        int required = novsWar.getNovsConfigCache().getConfig("core").getInt("core.game.minimum_players");
        for (NovsTeam team : enabledTeams) {
            numPlayers += team.getPlayers().size();
        }
        if (numPlayers >= required) {
            return true;
        } else {
            return false;
        }
    }

    /**
     * Produces death messages, evaluates stats, assists, schedules death (spectating) and calls events
     * @param victim
     * @param attacker
     * @param isArrowDeath
     */
    public void killPlayer(NovsPlayer victim, NovsPlayer attacker, boolean isArrowDeath) {
        //Generate death message
        String deathMessage;
        novsWar.printDebug("Killing player "+victim.getBukkitPlayer().getName()+"...");
        if(attacker != null) {
        	//There is a valid attacker
        	//Evaluate statistics
        	if (isArrowDeath) {
                deathMessage = Messages.SHOT_MESSAGE.toString();
                attacker.getStats().incrementArrowKills();
                victim.getStats().incrementArrowDeaths();
            } else {
                deathMessage = Messages.KILL_MESSAGE.toString();
                attacker.getStats().incrementKills();
                victim.getStats().incrementDeaths();
            }
            deathMessage = deathMessage.replace("%killed_tcolor%", victim.getTeam().getColor().toString())
                    .replace("%killed%", victim.getBukkitPlayer().getDisplayName())
                    .replace("%killer_tcolor%", attacker.getTeam().getColor().toString())
                    .replace("%killer%", attacker.getBukkitPlayer().getDisplayName());
        } else {
        	//There is no attacker
        	deathMessage = Messages.DEATH_MESSAGE.toString();
            deathMessage = deathMessage.replace("%player_tcolor%", victim.getTeam().getColor().toString())
            		.replace("%player%", victim.getBukkitPlayer().getDisplayName());
        }
        
        //Print death message to all players
        for (NovsPlayer p : novsWar.getNovsPlayerCache().getPlayers().values()) {
            if (p.canSeeDeathMessages()) {
                p.getBukkitPlayer().sendMessage(deathMessage);
            }
        }
        
    	//Evaluate assists
        NovsPlayer assistAttacker = victim.getAssistAttacker(attacker);

        victim.clearAttackers();
        
        //Schedule death spectating
        scheduleDeath(victim, attacker, gamemode.getDeathTime());
        
        //Event calls
        novsWar.printDebug("...Calling events");
        if(attacker != null) { //if there is an attacker, invoke kill event
	        NovsWarPlayerKillEvent invokeEvent = new NovsWarPlayerKillEvent(attacker, victim, attacker.getTeam(), victim.getTeam(), this);
	        Bukkit.getPluginManager().callEvent(invokeEvent);
        } else { //if there isn't an attacker, increment suicides
        	victim.getStats().incrementSuicides();
        	NovsWarPlayerDeathEvent invokeEvent = new NovsWarPlayerDeathEvent(victim, victim.getTeam(), this);
	        Bukkit.getPluginManager().callEvent(invokeEvent);
        }
        if(assistAttacker != null) {
            novsWar.printDebug("...Assist attacker was "+assistAttacker.getBukkitPlayer().getName());
            NovsWarPlayerAssistEvent invokeEvent_1 = new NovsWarPlayerAssistEvent(assistAttacker, victim, assistAttacker.getTeam(), victim.getTeam(), this);
            Bukkit.getPluginManager().callEvent(invokeEvent_1);
        }
        novsWar.printDebug("...Finished killing player");
    }
    
    private void scheduleDeath(NovsPlayer player, NovsPlayer spectatorTarget, int seconds) {
        Player bukkitPlayer = player.getBukkitPlayer();
        player.setDeath(true);
        novsWar.printDebug("...Scheduling death, setting max food & health");
        bukkitPlayer.setHealth(player.getBukkitPlayer().getMaxHealth());
        bukkitPlayer.setFoodLevel(20);
        for(PotionEffect effect : bukkitPlayer.getActivePotionEffects()) {
        	novsWar.printDebug("...Removing potion effect "+effect.getType().toString());
        	bukkitPlayer.removePotionEffect(effect.getType());
        }
        novsWar.printDebug("...Generating effects");
        bukkitPlayer.getWorld().playEffect(bukkitPlayer.getLocation(), Effect.SMOKE, 30, 2);
        bukkitPlayer.getWorld().playSound(player.getBukkitPlayer().getLocation(), Sound.ENTITY_WITCH_DEATH, 5, 0.5f);
        
        novsWar.printDebug("..."+bukkitPlayer.getName()+" died and has observers: ");
        //Set each observer for this player to a new target
        for(NovsPlayer observer : player.getSpectatorObservers()) {
        	novsWar.printDebug("    "+observer.getBukkitPlayer().getName());
        	NovsPlayer newTarget = observer.nextSpectatorTarget(this); //sets the observer's target to another player
        	if(newTarget != null) {
        		newTarget.getSpectatorObservers().add(observer);
        	}
        }
        //Clear this player's observer list
        player.getSpectatorObservers().clear();
        novsWar.printDebug("...Setting spectator mode");
        bukkitPlayer.setGameMode(GameMode.SPECTATOR);
        
        //If there is an attacker, set spectator target.
        if (spectatorTarget != null) {
        	player.setSpectatorTarget(spectatorTarget);
        	spectatorTarget.getSpectatorObservers().add(player);
        } else {
        	//Check if there are available spectator targets
        	NovsPlayer noAttackerTarget = player.nextSpectatorTarget(this);
        	if(noAttackerTarget != null) {
        		noAttackerTarget.getSpectatorObservers().add(player);
        	}
        }
        
        novsWar.printDebug("...Starting death timer");
        DeathTimer timer = new DeathTimer(this, seconds, player);
        timer.startTimer();
        deathTimers.put(player, timer);
    }

    public void deathTick(NovsPlayer player) {
        DeathTimer timer = deathTimers.get(player);
        SendTitle.sendTitle(player.getBukkitPlayer(), 0, 2000, 0, " ", "Respawn in " + Integer.toString(timer.getSeconds()) + "...");
    }

    public void respawn(NovsPlayer player) {
        SendTitle.sendTitle(player.getBukkitPlayer(), 0, 0, 0, " ", "");
        DeathTimer timer = deathTimers.get(player);
        timer.stopTimer();
        deathTimers.remove(player);
        player.getBukkitPlayer().getScoreboard().getObjective(DisplaySlot.SIDEBAR).setDisplayName(scoreboard.getSidebarTitle());

        if (player.isDead()) {
            NovsTeam team = player.getTeam();
            player.setDeath(false);
            player.getBukkitPlayer().teleport(world.getTeamSpawnLoc(team));
            player.getBukkitPlayer().setGameMode(GameMode.SURVIVAL);
            //Invoke Event
            NovsWarPlayerRespawnEvent invokeEvent = new NovsWarPlayerRespawnEvent(player, this);
	        Bukkit.getPluginManager().callEvent(invokeEvent);
        }
    }

    public void joinGame(NovsPlayer player) {
        
        boolean canJoinInProgress = novsWar.getNovsConfigCache().getConfig("core").getBoolean("core.game.join_in_progress");

        if (!canJoinInProgress && gameState.equals(GameState.DURING_GAME)) {
            player.getBukkitPlayer().sendMessage(Messages.CANNOT_JOIN_GAME.toString());
            return;
        }
        assignPlayerTeam(player);
        //Invoke event
        NovsWarJoinGameEvent event = new NovsWarJoinGameEvent(this, player);
        Bukkit.getServer().getPluginManager().callEvent(event);
        
        if (checkPlayerCount()) {
        	switch (gameState) {
        	case WAITING_FOR_PLAYERS :
        		preGame();
        		break;
    		default :
    			break;
        	}
        } else {
        	int minimum = novsWar.getNovsConfigCache().getConfig("core").getInt("core.game.minimum_players");
        	String message = Messages.NOT_ENOUGH_PLAYERS.toString().replace("%minimum%", Integer.toString(minimum));
            Bukkit.broadcastMessage(message);
        }

        if (paused) {
            unpauseGame();
        }
    }
    
    public void assignPlayerTeam(NovsPlayer player) {
    	// novsloadout has its own way of sorting players, only run this code if it isnt enabled
        if (!Bukkit.getPluginManager().isPluginEnabled("NovsLoadout")) {
        	//Determine which team has fewer players
        	NovsTeam smallestTeam = enabledTeams.get(0);
        	int smallest = smallestTeam.getPlayers().size();
            for (NovsTeam team : enabledTeams) {
            	if(team.getPlayers().size() <= smallest) {
            		smallest = team.getPlayers().size();
            		smallestTeam = team;
            	}
            }
            forcePlayerTeam(player, smallestTeam);
            
        } else {
        	
        	//TODO Call NovsLoadout's sorting algorithm
        	
        }
    }
    
    /**
     * Sets a players team, health, hunger and teleports them to their team's spawn
     * @param player
     * @param team
     */
    public void forcePlayerTeam(NovsPlayer player, NovsTeam team) {
    	NovsWarJoinTeamEvent event = new NovsWarJoinTeamEvent(this, player, team);
        Bukkit.getServer().getPluginManager().callEvent(event);
    	
        if(event.isCancelled()==false) {
        	
        	if(enabledTeams.contains(team)) {
        		player.setTeam(team);
        		//novsWar.printDebug("Assigning team "+team.getTeamName()+" location "+world.getTeamSpawns().get(team).toString());
                player.getBukkitPlayer().teleport(world.getTeamSpawnLoc(team));
                player.getBukkitPlayer().setHealth(player.getBukkitPlayer().getMaxHealth());
                player.getBukkitPlayer().setFoodLevel(20);
                String message = Messages.JOIN_TEAM.toString().replace("%team_color%", team.getColor().toString()).replace("%team%", team.getTeamName());
                player.getBukkitPlayer().sendMessage(message);
        	} else {
        		String message = Messages.CANNOT_JOIN_TEAM.toString().replace("%team_color%", team.getColor().toString()).replace("%team%", team.getTeamName());
        		player.getBukkitPlayer().sendMessage(message);
        	}
        }
    }
    
    public void nextGame(NovsWorld world) {
    	if(gameTimer.getTaskID() != 0) { //if there is a running timer
			System.out.println("Stopped timer");
			gameTimer.stopTimer();
		}
		gameHandler.newGame(world);
    }

    public GameScoreboard getScoreboard() {
        return scoreboard;
    }

    public GameHandler getGameHandler() {
        return gameHandler;
    }

    public BallotBox getBallotBox() {
    	return ballotBox;
    }
    
    public ArrayList<NovsTeam> getTeams() {
    	return enabledTeams;
    }

    public Gamemode getGamemode() {
        return gamemode;
    }
    
    public GameState getGameState() {
    	return gameState;
    }
    
    public NovsWorld getWorld() {
    	return world;
    }

    /**
     * Sends all in-game players to their spawns and balances the teams
     */
    public void balanceTeams() {
        pauseGame();
        messageTime = 5;
        messageTask = Bukkit.getScheduler().scheduleSyncRepeatingTask(novsWar.getPlugin(), new Runnable() {
            public void run() {
                ArrayList<NovsPlayer> autobalancePlayers = new ArrayList<NovsPlayer>();
                for(NovsPlayer player : getGamePlayers()) {
                    SendTitle.sendTitle(player.getBukkitPlayer(), 0, 2000, 0, " ", "Team Auto-Balance in "+messageTime+"...");
                    autobalancePlayers.add(player);
                }
                messageTime--;
                if(messageTime <= 0) {
                    Bukkit.getScheduler().cancelTask(messageTask);
                    //Set every player's team to default
                    for(NovsPlayer player : autobalancePlayers) {
                        SendTitle.sendTitle(player.getBukkitPlayer(), 0, 0, 0, " ", "");
                        player.setTeam(novsWar.getNovsTeamCache().getDefaultTeam());
                    }
                    //re-do the team sorting algorithm
                    for(NovsPlayer player : autobalancePlayers) {
                        assignPlayerTeam(player);
                    }
                    unpauseGame();
                }
            }
        }, 0, 20);
    }

    /**
     * Assigns all in-game players to the next team index in the NovsTeam array list
     */
    public void rotateTeams() {
        HashMap<NovsTeam, NovsTeam> rotationMap = new HashMap<NovsTeam, NovsTeam>(); //key = source, value = target
        int targetIndex = 0;
        //Generate map for team switching
        for(int sourceIndex = 0; sourceIndex < novsWar.getNovsTeamCache().getTeams().size(); sourceIndex++) {
            targetIndex = sourceIndex + 1;
            if(targetIndex >= novsWar.getNovsTeamCache().getTeams().size()) {
                targetIndex = 0;
            }
            rotationMap.put(novsWar.getNovsTeamCache().getTeams().get(sourceIndex), novsWar.getNovsTeamCache().getTeams().get(targetIndex));
        }
        //Switch teams for each player in-game
        for(NovsPlayer player : getGamePlayers()) {
            NovsTeam newTeam = rotationMap.get(player.getTeam());
            player.setTeam(newTeam);
            player.getBukkitPlayer().teleport(world.getTeamSpawnLoc(newTeam));
            player.getBukkitPlayer().setHealth(player.getBukkitPlayer().getMaxHealth());
            player.getBukkitPlayer().setFoodLevel(20);
        }
    }

    public ArrayList<NovsPlayer> getGamePlayers() {
        ArrayList<NovsPlayer> inGamePlayers = new ArrayList<NovsPlayer>();
        for(NovsPlayer aPlayer : novsWar.getNovsPlayerCache().getPlayers().values()) {
            if(aPlayer.getTeam().equals(novsWar.getNovsTeamCache().getDefaultTeam())==false) {
                inGamePlayers.add(aPlayer);
            }
        }
        return inGamePlayers;
    }

    public boolean isPaused() {
        return paused;
    }
}
