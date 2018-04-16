/*
    PopulationDensity Server Plugin for Minecraft
    Copyright (C) 2011 Ryan Hamshire

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package me.ryanhamshire.PopulationDensity;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;

import org.bukkit.Location;
import org.bukkit.entity.Animals;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.*;
import org.bukkit.event.player.PlayerLoginEvent.Result;
import org.bukkit.scheduler.BukkitRunnable;

public class PlayerEventHandler implements Listener {
	private DataStore dataStore;
	private PopulationDensity instance;

	public PlayerEventHandler(DataStore dataStore, PopulationDensity plugin) {
		this.dataStore = dataStore;
		instance = plugin;
	}

	// when a player successfully joins the server...
	@EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
	private void onPlayerJoin(PlayerJoinEvent event) {
		
		Player joiningPlayer = event.getPlayer();
		
		PlayerData playerData = this.dataStore.getPlayerData(joiningPlayer);

		// if the player doesn't have a home region yet (he hasn't logged in
		// since the plugin was installed)
		RegionCoordinates homeRegion = playerData.homeRegion;
		if (homeRegion == null)
		{
		    // if he's never been on the server before
		    if(!joiningPlayer.hasPlayedBefore())
		    {
    			// his home region is the open region
    			RegionCoordinates openRegion = this.dataStore.getOpenRegion();
    			playerData.homeRegion = openRegion;
    			PopulationDensity.AddLogEntry("Assigned new player "
    					+ joiningPlayer.getName() + " to region "
    					+ this.dataStore.getRegionName(openRegion) + " at "
    					+ openRegion.toString() + ".");
    
    			// entirely new players who've not visited the server before will
    			// spawn in their home region by default.
    			// if configured as such, teleport him there after 2 ticks (since we can't teleport in the event tick)
		    }
		    
		    //otherwise if he's played before, guess his home region as best we can
		    else
		    {
		        if(joiningPlayer.getBedSpawnLocation() != null)
		        {
		            playerData.homeRegion = RegionCoordinates.fromLocation(joiningPlayer.getBedSpawnLocation());
		        }
		        else
		        {
		            playerData.homeRegion = RegionCoordinates.fromLocation(joiningPlayer.getLocation());
		        }
		        
		        if(playerData.homeRegion == null)
		        {
		            playerData.homeRegion = instance.dataStore.getOpenRegion();
		        }
		    }
		    
		    this.dataStore.savePlayerData(joiningPlayer, playerData);
		}
	}

	// when a player disconnects...
	@EventHandler(ignoreCancelled = true)
	private void onPlayerQuit(PlayerQuitEvent event)
	{
		this.onPlayerDisconnect(event.getPlayer());
	}

	// when a player disconnects...
	private void onPlayerDisconnect(Player player)
	{
		// clear any cached data for this player in the data store
		this.dataStore.clearCachedPlayerData(player);
	}

	// when a player respawns after death...
	@EventHandler(ignoreCancelled = true)
	private void onPlayerRespawn(PlayerRespawnEvent respawnEvent)
	{
		if (!instance.respawnInHomeRegion)
		{
		    if(PopulationDensity.ManagedWorld == respawnEvent.getRespawnLocation().getWorld())
		    {
		        PopulationDensity.removeMonstersAround(respawnEvent.getRespawnLocation());
		    }
		    return;
		}
		
		Player player = respawnEvent.getPlayer();
		
		// if it's NOT a bed respawn, redirect it to the player's home region
		// post
		// this keeps players near where they live, even when they die (haha)
		if (!respawnEvent.isBedSpawn())
		{
		    PlayerData playerData = this.dataStore.getPlayerData(player);

			// find the center of his home region
			Location homeRegionCenter = PopulationDensity.getRegionCenter(playerData.homeRegion, false);

			// aim for two blocks above the highest block and teleport
			homeRegionCenter.setY(PopulationDensity.ManagedWorld
					.getHighestBlockYAt(homeRegionCenter) + 2);
			respawnEvent.setRespawnLocation(homeRegionCenter);
			
			PopulationDensity.removeMonstersAround(homeRegionCenter);
		}
	}
	
	@EventHandler(ignoreCancelled = true)
    private void onPlayerChat(AsyncPlayerChatEvent event)
    {
        String msg = event.getMessage();

		if(msg.equalsIgnoreCase(instance.dataStore.getMessage(Messages.Lag)))
		{
			final Player player = event.getPlayer();

			event.getRecipients().clear();
			event.getRecipients().add(player);

			new BukkitRunnable()
			{
				public void run()
				{
					instance.reportTPS(player);
				}
			}.runTask(instance);
		}
    }
}
