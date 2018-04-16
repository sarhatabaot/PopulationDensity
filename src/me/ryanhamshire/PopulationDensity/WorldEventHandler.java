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

import java.util.List;

import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Animals;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Horse;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.SkeletonHorse;
import org.bukkit.entity.Tameable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.metadata.FixedMetadataValue;

public class WorldEventHandler implements Listener
{
	//when a chunk loads, generate a region post in that chunk if necessary
	@EventHandler(ignoreCancelled = true)
	public void onChunkLoad(ChunkLoadEvent chunkLoadEvent)
	{		
		Chunk chunk = chunkLoadEvent.getChunk();
		
		//nothing more to do in worlds other than the managed world
		if(chunk.getWorld() != PopulationDensity.ManagedWorld) return;
		
		//find the boundaries of the chunk
		Location lesserCorner = chunk.getBlock(0, 0, 0).getLocation();
		Location greaterCorner = chunk.getBlock(15, 0, 15).getLocation();
		
		//find the center of this chunk's region
		RegionCoordinates region = RegionCoordinates.fromLocation(lesserCorner);		
		Location regionCenter = PopulationDensity.getRegionCenter(region, false);
		
		//if the chunk contains the region center
		if(	regionCenter.getBlockX() >= lesserCorner.getBlockX() && regionCenter.getBlockX() <= greaterCorner.getBlockX() &&
			regionCenter.getBlockZ() >= lesserCorner.getBlockZ() && regionCenter.getBlockZ() <= greaterCorner.getBlockZ())
		{
			//create a task to build the post after 10 seconds
			try
			{
			    PopulationDensity.instance.dataStore.AddRegionPost(region);
			}
			catch(ChunkLoadException e)  //this should never happen, because the chunk is loaded (why else would onChunkLoad() be invoked?)
                    //Then why are you eating it? - RoboMWM
            {
                PopulationDensity.instance.getLogger().info("Was unable to build a post. Do not fret as we'll try again when the chunk is loaded again - but, if you wish to report this, please include the ''entire'' log, not just this error.");
                e.printStackTrace();
            }
		}
	}
}
