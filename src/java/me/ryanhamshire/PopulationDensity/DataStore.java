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

import com.google.common.collect.ImmutableList;
import com.google.common.io.Files;
import org.apache.commons.lang.Validate;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Sign;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.util.StringUtil;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.Charset;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

public class DataStore implements TabCompleter
{
    //in-memory cache for player home region, because it's needed very frequently
    private HashMap<String, PlayerData> playerNameToPlayerDataMap = new HashMap<String, PlayerData>();

    //path information, for where stuff stored on disk is well...  stored
    private final static String dataLayerFolderPath = "plugins" + File.separator + "PopulationDensityData";
    private final static String playerDataFolderPath = dataLayerFolderPath + File.separator + "PlayerData";
    private final static String regionDataFolderPath = dataLayerFolderPath + File.separator + "RegionData";
    public final static String configFilePath = dataLayerFolderPath + File.separator + "config.yml";
    final static String messagesFilePath = dataLayerFolderPath + File.separator + "messages.yml";

    //in-memory cache for messages
    private String[] messages;

    //currently open region
    private RegionCoordinates openRegionCoordinates;

    //coordinates of the next region which will be opened, if one needs to be opened
    private RegionCoordinates nextRegionCoordinates;

    //region data cache
    private ConcurrentHashMap<String, RegionCoordinates> nameToCoordsMap = new ConcurrentHashMap<String, RegionCoordinates>();
    private ConcurrentHashMap<RegionCoordinates, String> coordsToNameMap = new ConcurrentHashMap<RegionCoordinates, String>();

    //initialization!
    public DataStore(List<String> regionNames)
    {
        //ensure data folders exist
        new File(playerDataFolderPath).mkdirs();
        new File(regionDataFolderPath).mkdirs();

        this.regionNamesList = regionNames.toArray(new String[]{});

        this.loadMessages();

        //get a list of all the files in the region data folder
        //some of them are named after region names, others region coordinates
        File regionDataFolder = new File(regionDataFolderPath);
        File[] files = regionDataFolder.listFiles();

        for (int i = 0; i < files.length; i++)
        {
            if (files[i].isFile())  //avoid any folders
            {
                try
                {
                    //if the filename converts to region coordinates, add that region to the list of defined regions
                    //(this constructor throws an exception if it can't do the conversion)
                    RegionCoordinates regionCoordinates = new RegionCoordinates(files[i].getName());
                    String regionName = Files.readFirstLine(files[i], Charset.forName("UTF-8"));
                    this.nameToCoordsMap.put(regionName.toLowerCase(), regionCoordinates);
                    this.coordsToNameMap.put(regionCoordinates, regionName);
                }

                //catch for files named after region names
                catch (Exception e) { }
            }
        }

        //study region data and initialize both this.openRegionCoordinates and this.nextRegionCoordinates
        this.findNextRegion();

        //if no regions were loaded, create the first one
        if (nameToCoordsMap.keySet().size() == 0)
        {
            PopulationDensity.AddLogEntry("Please be patient while I search for a good new player starting point!");
            PopulationDensity.AddLogEntry("This initial scan could take a while, especially for worlds where players have already been building.");
            this.addRegion();
        }

        PopulationDensity.AddLogEntry("Open region: \"" + this.getRegionName(this.getOpenRegion()) + "\" at " + this.getOpenRegion().toString() + ".");
    }

    //used in the spiraling code below (see findNextRegion())
    private enum Direction
    {
        left, right, up, down
    }

    //starts at region 0,0 and spirals outward until it finds a region which hasn't been initialized
    //sets private variables for openRegion and nextRegion when it's done
    //this may look like black magic, but seriously, it produces a tight spiral on a grid
    //coding this made me reminisce about seemingly pointless computer science exercises in college
    public int findNextRegion()
    {
        //spiral out from region coordinates 0, 0 until we find coordinates for an uninitialized region
        int x = 0;
        int z = 0;

        //keep count of the regions encountered
        int regionCount = 0;

        //initialization
        Direction direction = Direction.down;   //direction to search
        int sideLength = 1;                    //maximum number of regions to move in this direction before changing directions
        int side = 0;                            //increments each time we change directions.  this tells us when to add length to each side
        this.openRegionCoordinates = new RegionCoordinates(0, 0);
        this.nextRegionCoordinates = new RegionCoordinates(0, 0);

        //while the next region coordinates are taken, walk the spiral
        while (this.getRegionName(this.nextRegionCoordinates) != null)
        {
            //loop for one side of the spiral
            for (int i = 0; i < sideLength && this.getRegionName(this.nextRegionCoordinates) != null; i++)
            {
                regionCount++;

                //converts a direction to a change in X or Z
                if (direction == Direction.down) z++;
                else if (direction == Direction.left) x--;
                else if (direction == Direction.up) z--;
                else x++;

                this.openRegionCoordinates = this.nextRegionCoordinates;
                this.nextRegionCoordinates = new RegionCoordinates(x, z);
            }

            //after finishing a side, change directions
            if (direction == Direction.down) direction = Direction.left;
            else if (direction == Direction.left) direction = Direction.up;
            else if (direction == Direction.up) direction = Direction.right;
            else direction = Direction.down;

            //keep count of the completed sides
            side++;

            //on even-numbered sides starting with side == 2, increase the length of each side
            if (side % 2 == 0) sideLength++;
        }

        //return total number of regions seen
        return regionCount;
    }

    //picks a region at random (sort of)
    public RegionCoordinates getRandomRegion(RegionCoordinates regionToAvoid)
    {
        if (this.coordsToNameMap.keySet().size() < 2) return null;

        //initialize random number generator with a seed based the current time
        Random randomGenerator = new Random();

        ArrayList<RegionCoordinates> possibleDestinations = new ArrayList<RegionCoordinates>();
        for (RegionCoordinates coords : this.coordsToNameMap.keySet())
        {
            if (!coords.equals(regionToAvoid))
            {
                possibleDestinations.add(coords);
            }
        }

        //pick one of those regions at random
        int randomRegion = randomGenerator.nextInt(possibleDestinations.size());
        return possibleDestinations.get(randomRegion);
    }

    public void savePlayerData(OfflinePlayer player, PlayerData data)
    {
        //save that data in memory
        this.playerNameToPlayerDataMap.put(player.getUniqueId().toString(), data);

        BufferedWriter outStream = null;
        try
        {
            //open the player's file
            File playerFile = new File(playerDataFolderPath + File.separator + player.getUniqueId().toString());
            playerFile.createNewFile();
            outStream = new BufferedWriter(new FileWriter(playerFile));

            //first line is home region coordinates
            outStream.write(data.homeRegion.toString());
            outStream.newLine();

            //second line is last disconnection date,
            //note use of the ROOT locale to avoid problems related to regional settings on the server being updated
            DateFormat dateFormat = DateFormat.getDateTimeInstance(DateFormat.FULL, DateFormat.FULL, Locale.ROOT);
            outStream.write(dateFormat.format(data.lastDisconnect));
            outStream.newLine();

            //third line is login priority
            outStream.write(String.valueOf(data.loginPriority));
            outStream.newLine();
        }

        //if any problem, log it
        catch (Exception e)
        {
            PopulationDensity.AddLogEntry("PopulationDensity: Unexpected exception saving data for player \"" + player.getName() + "\": " + e.getMessage());
        }

        try
        {
            //close the file
            if (outStream != null) outStream.close();
        }
        catch (IOException exception) {}
    }

    public PlayerData getPlayerData(OfflinePlayer player)
    {
        //first, check the in-memory cache
        PlayerData data = this.playerNameToPlayerDataMap.get(player.getUniqueId().toString());

        if (data != null) return data;

        //if not there, try to load the player from file using UUID
        loadPlayerDataFromFile(player.getUniqueId().toString(), player.getUniqueId().toString());

        //check again
        data = this.playerNameToPlayerDataMap.get(player.getUniqueId().toString());

        if (data != null) return data;

        //if still not there, try player name
        loadPlayerDataFromFile(player.getName(), player.getUniqueId().toString());

        //check again
        data = this.playerNameToPlayerDataMap.get(player.getUniqueId().toString());

        if (data != null) return data;

        return new PlayerData();
    }

    private void loadPlayerDataFromFile(String source, String dest)
    {
        //load player data into memory
        File playerFile = new File(playerDataFolderPath + File.separator + source);

        BufferedReader inStream = null;
        try
        {
            PlayerData playerData = new PlayerData();
            inStream = new BufferedReader(new FileReader(playerFile.getAbsolutePath()));

            //first line is home region coordinates
            String homeRegionCoordinatesString = inStream.readLine();

            //second line is date of last disconnection
            String lastDisconnectedString = inStream.readLine();

            //third line is login priority
            String rankString = inStream.readLine();

            //convert string representation of home coordinates to a proper object
            RegionCoordinates homeRegionCoordinates = new RegionCoordinates(homeRegionCoordinatesString);
            playerData.homeRegion = homeRegionCoordinates;

            //parse the last disconnect date string
            try
            {
                DateFormat dateFormat = DateFormat.getDateTimeInstance(DateFormat.FULL, DateFormat.FULL, Locale.ROOT);
                Date lastDisconnect = dateFormat.parse(lastDisconnectedString);
                playerData.lastDisconnect = lastDisconnect;
            }
            catch (Exception e)
            {
                playerData.lastDisconnect = Calendar.getInstance().getTime();
            }

            //parse priority string
            if (rankString == null || rankString.isEmpty())
            {
                playerData.loginPriority = 0;
            } else
            {
                try
                {
                    playerData.loginPriority = Integer.parseInt(rankString);
                }
                catch (Exception e)
                {
                    playerData.loginPriority = 0;
                }
            }

            //shove into memory for quick access
            this.playerNameToPlayerDataMap.put(dest, playerData);
        }

        //if the file isn't found, just don't do anything (probably a new-to-server player)
        catch (FileNotFoundException e)
        {
            return;
        }

        //if there's any problem with the file's content, log an error message and skip it
        catch (Exception e)
        {
            PopulationDensity.AddLogEntry("Unable to load data for player \"" + source + "\": " + e.getMessage());
        }

        try
        {
            if (inStream != null) inStream.close();
        }
        catch (IOException exception) {}
    }

    //adds a new region, assigning it a name and updating local variables accordingly
    public RegionCoordinates addRegion()
    {
        //first, find a unique name for the new region
        String newRegionName;

        //select a name from the list of region names
        //strategy: use names from the list in rotation, appending a number when a name is already used
        //(redstone, mountain, valley, redstone1, mountain1, valley1, ...)

        int newRegionNumber = this.coordsToNameMap.keySet().size() - 1;

        //as long as the generated name is already in use, move up one name on the list
        do
        {
            newRegionNumber++;
            int nameBodyIndex = newRegionNumber % this.regionNamesList.length;
            int nameSuffix = newRegionNumber / this.regionNamesList.length;
            newRegionName = this.regionNamesList[nameBodyIndex];
            if (nameSuffix > 0) newRegionName += nameSuffix;

        } while (this.getRegionCoordinates(newRegionName) != null);

        this.privateNameRegion(this.nextRegionCoordinates, newRegionName);

        //find the next region in the spiral (updates this.openRegionCoordinates and this.nextRegionCoordinates)
        this.findNextRegion();

        return this.openRegionCoordinates;
    }

    //names a region, never throws an exception for name content
    private void privateNameRegion(RegionCoordinates coords, String name)
    {
        //delete any existing data for the region at these coordinates
        String oldRegionName = this.getRegionName(coords);
        if (oldRegionName != null)
        {
            File oldRegionCoordinatesFile = new File(regionDataFolderPath + File.separator + coords.toString());
            oldRegionCoordinatesFile.delete();

            File oldRegionNameFile = new File(regionDataFolderPath + File.separator + oldRegionName);
            oldRegionNameFile.delete();
            this.coordsToNameMap.remove(coords);
            this.nameToCoordsMap.remove(oldRegionName.toLowerCase());
        }

        //"create" the region by saving necessary data to disk
        BufferedWriter outStream = null;
        try
        {
            //coordinates file contains the region's name
            File regionCoordinatesFile = new File(regionDataFolderPath + File.separator + coords.toString());
            regionCoordinatesFile.createNewFile();
            outStream = new BufferedWriter(new FileWriter(regionCoordinatesFile));
            outStream.write(name);
            outStream.close();

            //cache in memory
            this.coordsToNameMap.put(coords, name);
            this.nameToCoordsMap.put(name.toLowerCase(), coords);
        }

        //in case of any problem, log the details
        catch (Exception e)
        {
            PopulationDensity.AddLogEntry("Unexpected Exception: " + e.getMessage());
        }

        try
        {
            if (outStream != null) outStream.close();
        }
        catch (IOException exception) {}
    }

    //names or renames a specified region
    public void nameRegion(RegionCoordinates coords, String name) throws RegionNameException
    {
        //validate name
        String error = PopulationDensity.instance.getRegionNameError(name, false);
        if (error != null)
        {
            throw new RegionNameException(error);
        }

        this.privateNameRegion(coords, name);
    }

    //retrieves the open region's coordinates
    public RegionCoordinates getOpenRegion()
    {
        return this.openRegionCoordinates;
    }

    //goes to disk to get the name of a region, given its coordinates
    public String getRegionName(RegionCoordinates coordinates)
    {
        return this.coordsToNameMap.get(coordinates);
    }

    //similar to above, goes to disk to get the coordinates that go with a region name
    public RegionCoordinates getRegionCoordinates(String regionName)
    {
        return this.nameToCoordsMap.get(regionName.toLowerCase());
    }

    //actually edits the world to create a region post at the center of the specified region
    public void AddRegionPost(RegionCoordinates region) throws ChunkLoadException
    {
        //if region post building is disabled, don't do anything
        if (!PopulationDensity.instance.buildRegionPosts) return;

        //find the center
        Location regionCenter = PopulationDensity.getRegionCenter(region, false);
        int x = regionCenter.getBlockX();
        int z = regionCenter.getBlockZ();
        int y;

        //make sure data is loaded for that area, because we're about to request data about specific blocks there
        PopulationDensity.GuaranteeChunkLoaded(x, z);

        //sink lower until we find something solid
        //also ignore glowstone, in case there's already a post here!
        Material blockType;

        //find the highest block.  could be the surface, a tree, some grass...
        y = PopulationDensity.ManagedWorld.getHighestBlockYAt(x, z) + 1;

        //posts fall through trees, snow, and any existing post looking for the ground
        do
        {
            blockType = PopulationDensity.ManagedWorld.getBlockAt(x, --y, z).getType();
        }
        while (y > 0 && (blockType == Material.AIR ||
                blockType == Material.OAK_LEAVES ||
                blockType == Material.SPRUCE_LEAVES ||
                blockType == Material.BIRCH_LEAVES ||
                blockType == Material.JUNGLE_LEAVES ||
                blockType == Material.ACACIA_LEAVES ||
                blockType == Material.DARK_OAK_LEAVES ||
                blockType == Material.GRASS ||
                blockType == Material.TALL_GRASS ||
                blockType == Material.OAK_LOG ||
                blockType == Material.SPRUCE_LOG ||
                blockType == Material.BIRCH_LOG ||
                blockType == Material.JUNGLE_LOG ||
                blockType == Material.ACACIA_LOG ||
                blockType == Material.DARK_OAK_LOG ||
                blockType == Material.SNOW ||
                blockType == Material.VINE
        ));

        if (Tag.SIGNS.isTagged(blockType))
        {
            y -= 4;
        }
        // Changed check because of the refactor to the postdesign.
        else if (blockType == PopulationDensity.instance.postMaterialMidTop)
        {
            y -= 2;
        } else if (blockType == Material.BEDROCK)
        {
            y += 1;
        }

        //if y value is under sea level, correct it to sea level (no posts should be that difficult to find)
        if (y < PopulationDensity.instance.minimumRegionPostY)
        {
            y = PopulationDensity.instance.minimumRegionPostY;
        }

        //clear signs from the area, this ensures signs don't drop as items
        //when the blocks they're attached to are destroyed in the next step
        for (int x1 = x - 2; x1 <= x + 2; x1++)
        {
            for (int z1 = z - 2; z1 <= z + 2; z1++)
            {
                for (int y1 = y + 1; y1 <= y + 5; y1++)
                {
                    Block block = PopulationDensity.ManagedWorld.getBlockAt(x1, y1, z1);
                    if (Tag.SIGNS.isTagged(block.getType()) || Tag.WALL_SIGNS.isTagged(block.getType()))
                        block.setType(Material.AIR);
                }
            }
        }

        //clear above it - sometimes this shears trees in half (doh!)
        for (int x1 = x - 2; x1 <= x + 2; x1++)
        {
            for (int z1 = z - 2; z1 <= z + 2; z1++)
            {
                for (int y1 = y + 1; y1 < y + 10; y1++)
                {
                    Block block = PopulationDensity.ManagedWorld.getBlockAt(x1, y1, z1);
                    if (block.getType() != Material.AIR) block.setType(Material.AIR);
                }
            }
        }

        //Sometimes we don't clear high enough thanks to new ultra tall trees in jungle biomes
        //Instead of attempting to clear up to nearly 110 * 4 blocks more, we'll just see what getHighestBlockYAt returns
        //If it doesn't return our post's y location, we're setting it and all blocks below to air.
        int highestBlockY = PopulationDensity.ManagedWorld.getHighestBlockYAt(x, z);
        while (highestBlockY > y)
        {
            Block block = PopulationDensity.ManagedWorld.getBlockAt(x, highestBlockY, z);
            if (block.getType() != Material.AIR)
                block.setType(Material.AIR);
            highestBlockY--;
        }

        //build post
        PopulationDensity.ManagedWorld.getBlockAt(x, y + 3, z).setType(PopulationDensity.instance.postMaterialTop);
        PopulationDensity.ManagedWorld.getBlockAt(x, y + 2, z).setType(PopulationDensity.instance.postMaterialMidTop);
        PopulationDensity.ManagedWorld.getBlockAt(x, y + 1, z).setType(PopulationDensity.instance.postMaterialMidBottom);
        PopulationDensity.ManagedWorld.getBlockAt(x, y, z).setType(PopulationDensity.instance.postMaterialBottom);

        //build outer platform
        for (int x1 = x - 2; x1 <= x + 2; x1++)
        {
            for (int z1 = z - 2; z1 <= z + 2; z1++)
            {
                PopulationDensity.ManagedWorld.getBlockAt(x1, y, z1).setType(PopulationDensity.instance.outerPlatformMaterial);
            }
        }

        //build inner platform
        for (int x1 = x - 1; x1 <= x + 1; x1++)
        {
            for (int z1 = z - 1; z1 <= z + 1; z1++)
            {
                PopulationDensity.ManagedWorld.getBlockAt(x1, y, z1).setType(PopulationDensity.instance.innerPlatformMaterial);
            }
        }

        String regionName = this.getRegionName(region);

        //If the top sign configuration is not null
        if (PopulationDensity.instance.topSignContent != null)
        {
            //build a sign on top with region name (or wilderness if no name)
            if (regionName == null) regionName = getMessage(Messages.Wilderness);
            regionName = PopulationDensity.capitalize(regionName);
            setSign(x, y + 4, z, BlockFace.NORTH, PopulationDensity.instance.topSignContent, "%regionName%", regionName);
        }

        //If the side sign configuration is not null
        if (PopulationDensity.instance.sideSignContent != null)
        {
            //add a sign for the region to the north
            regionName = this.getRegionName(new RegionCoordinates(region.x - 1, region.z));
            if (regionName == null) regionName = getMessage(Messages.Wilderness);
            regionName = PopulationDensity.capitalize(regionName);
            setWallSign(x, y + 2, z + 1, BlockFace.SOUTH, PopulationDensity.instance.sideSignContent, "%regionName%", regionName);

            //add a sign for the region to the east
            regionName = this.getRegionName(new RegionCoordinates(region.x, region.z - 1));
            if (regionName == null) regionName = getMessage(Messages.Wilderness);
            regionName = PopulationDensity.capitalize(regionName);
            setWallSign(x - 1, y + 2, z, BlockFace.WEST, PopulationDensity.instance.sideSignContent, "%regionName%", regionName);

            //add a sign for the region to the south
            regionName = this.getRegionName(new RegionCoordinates(region.x + 1, region.z));
            if (regionName == null) regionName = getMessage(Messages.Wilderness);
            regionName = PopulationDensity.capitalize(regionName);
            setWallSign(x, y + 2, z - 1, BlockFace.NORTH, PopulationDensity.instance.sideSignContent, "%regionName%", regionName);

            //add a sign for the region to the west
            regionName = this.getRegionName(new RegionCoordinates(region.x, region.z + 1));
            if (regionName == null) regionName = getMessage(Messages.Wilderness);
            regionName = PopulationDensity.capitalize(regionName);
            setWallSign(x + 1, y + 2, z, BlockFace.EAST, PopulationDensity.instance.sideSignContent, "%regionName%", regionName);
        }

        //if teleportation is enabled, also add a sign facing north and south for teleportation help
        if (PopulationDensity.instance.allowTeleportation)
        {
            if (PopulationDensity.instance.instructionsSignContent != null)
            {
                setWallSign(x - 1, y + 3, z, BlockFace.WEST, PopulationDensity.instance.instructionsSignContent);
                setWallSign(x + 1, y + 3, z, BlockFace.EAST, PopulationDensity.instance.instructionsSignContent);
            }
        }

        //custom signs
        if (PopulationDensity.instance.mainCustomSignContent != null)
        {
            setWallSign(x, y + 3, z - 1, BlockFace.NORTH, PopulationDensity.instance.mainCustomSignContent);
        }

        if (PopulationDensity.instance.northCustomSignContent != null)
        {
            setWallSign(x - 1, y + 1, z, BlockFace.WEST, PopulationDensity.instance.northCustomSignContent);
        }

        if (PopulationDensity.instance.southCustomSignContent != null)
        {
            setWallSign(x + 1, y + 1, z, BlockFace.EAST, PopulationDensity.instance.southCustomSignContent);
        }

        if (PopulationDensity.instance.eastCustomSignContent != null)
        {
            setWallSign(x, y + 1, z - 1, BlockFace.NORTH, PopulationDensity.instance.eastCustomSignContent);
        }

        if (PopulationDensity.instance.westCustomSignContent != null)
        {
            setWallSign(x, y + 1, z + 1, BlockFace.SOUTH, PopulationDensity.instance.westCustomSignContent);
        }
    }

    private void setSign(int x, int y, int z, BlockFace blockFace, String[] lines, String... replacements)
    {
        Block block = PopulationDensity.ManagedWorld.getBlockAt(x, y, z);
        block.setType(Material.OAK_SIGN);

        org.bukkit.block.data.type.Sign wall = (org.bukkit.block.data.type.Sign)block.getBlockData();
        wall.setRotation(blockFace);
        block.setBlockData(wall);

        Sign s = (Sign)block.getState();
        for (int i = 0; i < 4; i++)
        {
            String line = lines[i];
            for (int r = 0; r < replacements.length; r++, r++)
            {
                String key = replacements[r];
                String value = replacements[r + 1];
                line = line.replace(key, value);
            }
            s.setLine(i, color(line));
        }
        s.update();
    }

    private void setWallSign(int x, int y, int z, BlockFace blockFace, String[] lines, String... replacements)
    {
        Block block = PopulationDensity.ManagedWorld.getBlockAt(x, y, z);
        block.setType(Material.OAK_WALL_SIGN);

        org.bukkit.block.data.type.WallSign wall = (org.bukkit.block.data.type.WallSign)block.getBlockData();
        wall.setFacing(blockFace);
        block.setBlockData(wall);

        Sign s = (Sign)block.getState();
        for (int i = 0; i < 4; i++)
        {
            String line = lines[i];
            for (int r = 0; r < replacements.length; r++, r++)
            {
                String key = replacements[r];
                String value = replacements[r + 1];
                line = line.replace(key, value);
            }
            s.setLine(i, color(line));
        }
        s.update();
    }

    private String color(String string)
    {
        return ChatColor.translateAlternateColorCodes('&', string);
    }

    public void clearCachedPlayerData(Player player)
    {
        this.playerNameToPlayerDataMap.remove(player.getName());
    }

    private void loadMessages()
    {
        Messages[] messageIDs = Messages.values();
        this.messages = new String[Messages.values().length];

        HashMap<String, CustomizableMessage> defaults = new HashMap<String, CustomizableMessage>();

        //initialize defaults
        this.addDefault(defaults, Messages.NoManagedWorld, "The PopulationDensity plugin has not been properly configured.  Please update your config.yml to specify a world to manage.", null);
        this.addDefault(defaults, Messages.NoBreakPost, "You can't break blocks this close to the region post.", null);
        this.addDefault(defaults, Messages.NoBreakSpawn, "You can't break blocks this close to a player spawn point.", null);
        this.addDefault(defaults, Messages.NoBuildPost, "You can't place blocks this close to the region post.", null);
        this.addDefault(defaults, Messages.NoBuildSpawn, "You can't place blocks this close to a player spawn point.", null);
        this.addDefault(defaults, Messages.HelpMessage1, "Region post help and commands: {0} ", "0: Help URL");
        this.addDefault(defaults, Messages.BuildingAwayFromHome, "You're building outside of your home region.  If you'd like to make this region your new home to help you return here later, use /MoveIn.", null);
        this.addDefault(defaults, Messages.NoTeleportThisWorld, "You can't teleport from this world.", null);
        this.addDefault(defaults, Messages.OnlyHomeCityHere, "You're limited to /HomeRegion and /CityRegion here.", null);
        this.addDefault(defaults, Messages.NoTeleportHere, "Sorry, you can't teleport from here.", null);
        this.addDefault(defaults, Messages.NotCloseToPost, "You're not close enough to a region post to teleport.", null);
        this.addDefault(defaults, Messages.InvitationNeeded, "{0} lives in the wilderness.  He or she will have to /invite you.", "0: target player");
        this.addDefault(defaults, Messages.VisitConfirmation, "Teleported to {0}'s home region.", "0: target player");
        this.addDefault(defaults, Messages.DestinationNotFound, "There's no region or online player named \"{0}\".  Use /ListRegions to list possible destinations.", "0: specified destination");
        this.addDefault(defaults, Messages.NeedNewestRegionPermission, "You don't have permission to use that command.", null);
        this.addDefault(defaults, Messages.NewestRegionConfirmation, "Teleported to the current new player area.", null);
        this.addDefault(defaults, Messages.NotInRegion, "You're not in a region!", null);
        this.addDefault(defaults, Messages.UnnamedRegion, "You're in the wilderness!  This region doesn't have a name.", null);
        this.addDefault(defaults, Messages.WhichRegion, "You're in the {0} region.", null);
        this.addDefault(defaults, Messages.RegionNamesNoSpaces, "Region names may not include spaces.", null);
        this.addDefault(defaults, Messages.RegionNameLength, "Region names must be at most {0} letters long.", "0: maximum length specified in config.yml");
        this.addDefault(defaults, Messages.RegionNamesOnlyLettersAndNumbers, "Region names may not include symbols or punctuation.", null);
        this.addDefault(defaults, Messages.RegionNameConflict, "There's already a region by that name.", null);
        this.addDefault(defaults, Messages.NoMoreRegions, "Sorry, you're in the only region.  Over time, more regions will open.", null);
        this.addDefault(defaults, Messages.InviteAlreadySent, "{0} may now use /visit {1} to teleport to your home post.", "0: invitee's name, 1: inviter's name");
        this.addDefault(defaults, Messages.InviteConfirmation, "{0} may now use /visit {1} to teleport to your home post.", "0: invitee's name, 1: inviter's name");
        this.addDefault(defaults, Messages.InviteNotification, "{0} has invited you to visit!", "0: inviter's name");
        this.addDefault(defaults, Messages.InviteInstruction, "Use /visit {0} to teleport there.", "0: inviter's name");
        this.addDefault(defaults, Messages.PlayerNotFound, "There's no player named \"{0}\" online right now.", "0: specified name");
        this.addDefault(defaults, Messages.SetHomeConfirmation, "Home set to the nearest region post!", null);
        this.addDefault(defaults, Messages.SetHomeInstruction1, "Use /Home from any region post to teleport to your home post.", null);
        this.addDefault(defaults, Messages.SetHomeInstruction2, "Use /Invite to invite other players to teleport to your home post.", null);
        this.addDefault(defaults, Messages.AddRegionConfirmation, "Opened a new region and started a resource scan.  See console or server logs for details.", null);
        this.addDefault(defaults, Messages.ScanStartConfirmation, "Started scan.  Check console or server logs for results.", null);
        this.addDefault(defaults, Messages.LoginPriorityCheck, "{0}'s login priority: {1}.", "0: player name, 1: current priority");
        this.addDefault(defaults, Messages.LoginPriorityUpdate, "Set {0}'s priority to {1}.", "0: target player, 1: new priority");
        this.addDefault(defaults, Messages.ThinningConfirmation, "Thinning running.  Check logs for detailed results.", null);
        this.addDefault(defaults, Messages.PerformanceScore, "Current server performance score is {0}%.", "0: performance score");
        this.addDefault(defaults, Messages.PerformanceScore_Lag, "  The server is actively working to reduce lag - please be patient while automatic lag reduction takes effect.", null);
        this.addDefault(defaults, Messages.PerformanceScore_NoLag, "The server is running at normal speed.  If you're experiencing lag, check your graphics settings and internet connection.  ", null);
        this.addDefault(defaults, Messages.PlayerMoved, "Player moved.", null);
        this.addDefault(defaults, Messages.Lag, "lag", null);
        this.addDefault(defaults, Messages.RegionAlreadyNamed, "This region already has a name.  To REname, use /RenameRegion.", null);
        this.addDefault(defaults, Messages.HopperLimitReached, "To prevent server lag, hoppers are limited to {0} per chunk.", "0: maximum hoppers per chunk");
        this.addDefault(defaults, Messages.OutsideWorldBorder, "The region you are attempting to teleport to is outside the world border.", null);
        this.addDefault(defaults, Messages.Wilderness, "Wilderness", null);

        //load the config file
        FileConfiguration config = YamlConfiguration.loadConfiguration(new File(messagesFilePath));

        //for each message ID
        for (int i = 0; i < messageIDs.length; i++)
        {
            //get default for this message
            Messages messageID = messageIDs[i];
            CustomizableMessage messageData = defaults.get(messageID.name());

            //if default is missing, log an error and use some fake data for now so that the plugin can run
            if (messageData == null)
            {
                PopulationDensity.AddLogEntry("Missing message for " + messageID.name() + ".  Please contact the developer.");
                messageData = new CustomizableMessage(messageID, "Missing message!  ID: " + messageID.name() + ".  Please contact a server admin.", null);
            }

            //read the message from the file, use default if necessary
            this.messages[messageID.ordinal()] = config.getString("Messages." + messageID.name() + ".Text", messageData.text);
            config.set("Messages." + messageID.name() + ".Text", this.messages[messageID.ordinal()]);

            if (messageData.notes != null)
            {
                messageData.notes = config.getString("Messages." + messageID.name() + ".Notes", messageData.notes);
                config.set("Messages." + messageID.name() + ".Notes", messageData.notes);
            }
        }

        //save any changes
        try
        {
            config.save(DataStore.messagesFilePath);
        }
        catch (IOException exception)
        {
            PopulationDensity.AddLogEntry("Unable to write to the configuration file at \"" + DataStore.messagesFilePath + "\"");
        }

        defaults.clear();
        System.gc();
    }

    private void addDefault(HashMap<String, CustomizableMessage> defaults, Messages id, String text, String notes)
    {
        CustomizableMessage message = new CustomizableMessage(id, text, notes);
        defaults.put(id.name(), message);
    }

    synchronized public String getMessage(Messages messageID, String... args)
    {
        String message = messages[messageID.ordinal()];

        for (int i = 0; i < args.length; i++)
        {
            String param = args[i];
            message = message.replace("{" + i + "}", param);
        }

        return message;
    }

    //list of region names to use
    private String[] regionNamesList;

    String getRegionNames()
    {
        StringBuilder builder = new StringBuilder();
        for (String regionName : this.nameToCoordsMap.keySet())
        {
            builder.append(PopulationDensity.capitalize(regionName)).append(", ");
        }

        return builder.toString();
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) throws IllegalArgumentException
    {
        Validate.notNull(sender, "Sender cannot be null");
        Validate.notNull(args, "Arguments cannot be null");
        Validate.notNull(alias, "Alias cannot be null");
        if (args.length == 0)
        {
            return ImmutableList.of();
        }

        StringBuilder builder = new StringBuilder();
        for (String arg : args)
        {
            builder.append(arg + " ");
        }

        String arg = builder.toString().trim();
        ArrayList<String> matches = new ArrayList<String>();
        for (String name : this.coordsToNameMap.values())
        {
            if (StringUtil.startsWithIgnoreCase(name, arg))
            {
                matches.add(name);
            }
        }

        Player senderPlayer = sender instanceof Player ? (Player)sender : null;
        for (Player player : sender.getServer().getOnlinePlayers())
        {
            if (senderPlayer == null || senderPlayer.canSee(player))
            {
                if (StringUtil.startsWithIgnoreCase(player.getName(), arg))
                {
                    matches.add(player.getName());
                }
            }
        }

        Collections.sort(matches, String.CASE_INSENSITIVE_ORDER);
        return matches;
    }
}
