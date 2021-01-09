/*
 *       _____  _       _    _____                                _
 *      |  __ \| |     | |  / ____|                              | |
 *      | |__) | | ___ | |_| (___   __ _ _   _  __ _ _ __ ___  __| |
 *      |  ___/| |/ _ \| __|\___ \ / _` | | | |/ _` | '__/ _ \/ _` |
 *      | |    | | (_) | |_ ____) | (_| | |_| | (_| | | |  __/ (_| |
 *      |_|    |_|\___/ \__|_____/ \__, |\__,_|\__,_|_|  \___|\__,_|
 *                                    | |
 *                                    |_|
 *            PlotSquared plot management system for Minecraft
 *                  Copyright (C) 2021 IntellectualSites
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.plotsquared.bukkit.listener;

import com.google.inject.Inject;
import com.plotsquared.core.configuration.Settings;
import com.plotsquared.core.location.Location;
import com.plotsquared.core.plot.Plot;
import com.plotsquared.core.plot.world.PlotAreaManager;
import com.plotsquared.core.util.ReflectionUtils.RefClass;
import com.plotsquared.core.util.ReflectionUtils.RefField;
import com.plotsquared.core.util.ReflectionUtils.RefMethod;
import com.plotsquared.core.util.task.PlotSquaredTask;
import com.plotsquared.core.util.task.TaskManager;
import com.plotsquared.core.util.task.TaskTime;
import io.papermc.lib.PaperLib;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Objects;

import static com.plotsquared.core.util.ReflectionUtils.getRefClass;

@SuppressWarnings("unused")
public class ChunkListener implements Listener {

    private static final Logger logger = LoggerFactory.getLogger("P2/" + ChunkListener.class.getSimpleName());

    private final PlotAreaManager plotAreaManager;

    private RefMethod methodGetHandleChunk;
    private RefField mustSave;
    private Chunk lastChunk;
    private boolean ignoreUnload = false;

    @Inject
    public ChunkListener(final @NonNull PlotAreaManager plotAreaManager) {
        this.plotAreaManager = plotAreaManager;
        if (Settings.Chunk_Processor.AUTO_TRIM) {
            try {
                RefClass classChunk = getRefClass("{nms}.Chunk");
                RefClass classCraftChunk = getRefClass("{cb}.CraftChunk");
                this.mustSave = classChunk.getField("mustSave");
                this.methodGetHandleChunk = classCraftChunk.getMethod("getHandle");
            } catch (Throwable ignored) {
                Settings.Chunk_Processor.AUTO_TRIM = false;
            }
        }
        if (!Settings.Chunk_Processor.AUTO_TRIM) {
            return;
        }
        for (World world : Bukkit.getWorlds()) {
            world.setAutoSave(false);
        }
        TaskManager.runTaskRepeat(() -> {
            try {
                HashSet<Chunk> toUnload = new HashSet<>();
                for (World world : Bukkit.getWorlds()) {
                    String worldName = world.getName();
                    if (!this.plotAreaManager.hasPlotArea(worldName)) {
                        continue;
                    }
                    Object w = world.getClass().getDeclaredMethod("getHandle").invoke(world);
                    Object chunkMap = w.getClass().getDeclaredMethod("getPlayerChunkMap").invoke(w);
                    Method methodIsChunkInUse =
                            chunkMap.getClass().getDeclaredMethod("isChunkInUse", int.class, int.class);
                    Chunk[] chunks = world.getLoadedChunks();
                    for (Chunk chunk : chunks) {
                        if ((boolean) methodIsChunkInUse
                                .invoke(chunkMap, chunk.getX(), chunk.getZ())) {
                            continue;
                        }
                        int x = chunk.getX();
                        int z = chunk.getZ();
                        if (!shouldSave(worldName, x, z)) {
                            unloadChunk(worldName, chunk, false);
                            continue;
                        }
                        toUnload.add(chunk);
                    }
                }
                if (toUnload.isEmpty()) {
                    return;
                }
                long start = System.currentTimeMillis();
                for (Chunk chunk : toUnload) {
                    if (System.currentTimeMillis() - start > 5) {
                        return;
                    }
                    chunk.unload(true);
                }
            } catch (Throwable e) {
                e.printStackTrace();
            }
        }, TaskTime.ticks(1L));
    }

    public boolean unloadChunk(String world, Chunk chunk, boolean safe) {
        if (safe && shouldSave(world, chunk.getX(), chunk.getZ())) {
            return false;
        }
        Object c = this.methodGetHandleChunk.of(chunk).call();
        RefField.RefExecutor field = this.mustSave.of(c);
        if ((Boolean) field.get()) {
            field.set(false);
            if (chunk.isLoaded()) {
                ignoreUnload = true;
                chunk.unload(false);
                ignoreUnload = false;
            }
        }
        return true;
    }

    public boolean shouldSave(String world, int chunkX, int chunkZ) {
        int x = chunkX << 4;
        int z = chunkZ << 4;
        int x2 = x + 15;
        int z2 = z + 15;
        Plot plot = Location.at(world, x, 1, z).getOwnedPlotAbs();
        if (plot != null && plot.hasOwner()) {
            return true;
        }
        plot = Location.at(world, x2, 1, z2).getOwnedPlotAbs();
        if (plot != null && plot.hasOwner()) {
            return true;
        }
        plot = Location.at(world, x2, 1, z).getOwnedPlotAbs();
        if (plot != null && plot.hasOwner()) {
            return true;
        }
        plot = Location.at(world, x, 1, z2).getOwnedPlotAbs();
        if (plot != null && plot.hasOwner()) {
            return true;
        }
        plot = Location.at(world, x + 7, 1, z + 7).getOwnedPlotAbs();
        return plot != null && plot.hasOwner();
    }

    @EventHandler
    public void onChunkUnload(ChunkUnloadEvent event) {
        if (ignoreUnload) {
            return;
        }
        Chunk chunk = event.getChunk();
        if (Settings.Chunk_Processor.AUTO_TRIM) {
            String world = chunk.getWorld().getName();
            if (this.plotAreaManager.hasPlotArea(world)) {
                if (unloadChunk(world, chunk, true)) {
                    return;
                }
            }
        }
        if (processChunk(event.getChunk(), true)) {
            chunk.setForceLoaded(true);
        }
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        processChunk(event.getChunk(), false);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onItemSpawn(ItemSpawnEvent event) {
        Item entity = event.getEntity();
        PaperLib.getChunkAtAsync(event.getLocation()).thenAccept(chunk -> {
            if (chunk == this.lastChunk) {
                event.getEntity().remove();
                event.setCancelled(true);
                return;
            }
            if (!this.plotAreaManager.hasPlotArea(chunk.getWorld().getName())) {
                return;
            }
            Entity[] entities = chunk.getEntities();
            if (entities.length > Settings.Chunk_Processor.MAX_ENTITIES) {
                event.getEntity().remove();
                event.setCancelled(true);
                this.lastChunk = chunk;
            } else {
                this.lastChunk = null;
            }
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockPhysics(BlockPhysicsEvent event) {
        if (Settings.Chunk_Processor.DISABLE_PHYSICS) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onEntitySpawn(CreatureSpawnEvent event) {
        LivingEntity entity = event.getEntity();
        PaperLib.getChunkAtAsync(event.getLocation()).thenAccept(chunk -> {
            if (chunk == this.lastChunk) {
                event.getEntity().remove();
                event.setCancelled(true);
                return;
            }
            if (!this.plotAreaManager.hasPlotArea(chunk.getWorld().getName())) {
                return;
            }
            Entity[] entities = chunk.getEntities();
            if (entities.length > Settings.Chunk_Processor.MAX_ENTITIES) {
                event.getEntity().remove();
                event.setCancelled(true);
                this.lastChunk = chunk;
            } else {
                this.lastChunk = null;
            }
        });
    }

    private void cleanChunk(final Chunk chunk) {
        TaskManager.index.incrementAndGet();
        final Integer currentIndex = TaskManager.index.get();
        PlotSquaredTask task = TaskManager.runTaskRepeat(() -> {
            if (!chunk.isLoaded()) {
                Objects.requireNonNull(TaskManager.removeTask(currentIndex)).cancel();
                chunk.unload(true);
                return;
            }
            BlockState[] tiles = chunk.getTileEntities();
            if (tiles.length == 0) {
                Objects.requireNonNull(TaskManager.removeTask(currentIndex)).cancel();
                chunk.unload(true);
                return;
            }
            long start = System.currentTimeMillis();
            int i = 0;
            while (System.currentTimeMillis() - start < 250) {
                if (i >= tiles.length - Settings.Chunk_Processor.MAX_TILES) {
                    Objects.requireNonNull(TaskManager.removeTask(currentIndex)).cancel();
                    chunk.unload(true);
                    return;
                }
                tiles[i].getBlock().setType(Material.AIR, false);
                i++;
            }
        }, TaskTime.ticks(5L));
        TaskManager.addTask(task, currentIndex);
    }

    public boolean processChunk(Chunk chunk, boolean unload) {
        if (!this.plotAreaManager.hasPlotArea(chunk.getWorld().getName())) {
            return false;
        }
        Entity[] entities = chunk.getEntities();
        BlockState[] tiles = chunk.getTileEntities();
        if (entities.length > Settings.Chunk_Processor.MAX_ENTITIES) {
            int toRemove = entities.length - Settings.Chunk_Processor.MAX_ENTITIES;
            int index = 0;
            while (toRemove > 0 && index < entities.length) {
                final Entity entity = entities[index++];
                if (!(entity instanceof Player)) {
                    entity.remove();
                    toRemove--;
                }
            }
        }
        if (tiles.length > Settings.Chunk_Processor.MAX_TILES) {
            if (unload) {
                cleanChunk(chunk);
                return true;
            }

            for (int i = 0; i < (tiles.length - Settings.Chunk_Processor.MAX_TILES); i++) {
                tiles[i].getBlock().setType(Material.AIR, false);
            }
        }
        return false;
    }

}
