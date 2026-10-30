package dev.etran.mapupdater

import org.bukkit.Bukkit
import org.bukkit.entity.Entity
import org.bukkit.entity.ItemFrame
import org.bukkit.event.Event
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.server.MapInitializeEvent
import org.bukkit.event.world.ChunkLoadEvent
import org.bukkit.plugin.EventExecutor

/** Passive map discovery. Nothing here does real work: it only notes map ids. */
class MapListener(private val plugin: MapUpdaterPlugin) : Listener {
    private val tracker get() = plugin.tracker
    private val settings get() = plugin.settings

    // Entities are loaded separately from chunks since 1.17; EntitiesLoadEvent is the right hook there.
    private val entitiesLoadEventClass: Class<*>? =
        try { Class.forName("org.bukkit.event.world.EntitiesLoadEvent") } catch (_: Throwable) { null }

    fun register() {
        Bukkit.getPluginManager().registerEvents(this, plugin)
        val cls = entitiesLoadEventClass ?: return
        @Suppress("UNCHECKED_CAST")
        Bukkit.getPluginManager().registerEvent(
            cls as Class<out Event>, this, EventPriority.MONITOR,
            EventExecutor { _, event ->
                if (!settings.scanItemFrames) return@EventExecutor
                try {
                    @Suppress("UNCHECKED_CAST")
                    val entities = event.javaClass.getMethod("getEntities").invoke(event) as List<Entity>
                    for (e in entities) if (e is ItemFrame) tracker.scanFrame(e)
                } catch (_: Throwable) { /* ignore */ }
            },
            plugin, true
        )
    }

    /** A map was loaded into memory: the most reliable signal that it exists and is in use. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onMapInit(e: MapInitializeEvent) {
        try { tracker.track(e.map) } catch (_: Throwable) { /* ignore */ }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onJoin(e: PlayerJoinEvent) {
        if (!settings.scanPlayerInventories) return
        val p = e.player
        Bukkit.getScheduler().runTask(plugin, Runnable { if (p.isOnline) tracker.scanPlayer(p) })
    }

    /** Opening and closing a chest is how maps stored in containers get noticed. */
    @EventHandler(priority = EventPriority.MONITOR)
    fun onClose(e: InventoryCloseEvent) {
        if (!settings.scanPlayerInventories) return
        tracker.scanInventory(e.inventory)
    }

    /** Putting a map into an item frame. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onInteractEntity(e: PlayerInteractEntityEvent) {
        if (!settings.scanItemFrames) return
        val frame = e.rightClicked as? ItemFrame ?: return
        Bukkit.getScheduler().runTask(plugin, Runnable { tracker.scanFrame(frame) })
    }

    /** Pre-1.17 servers have entities available as soon as the chunk loads. */
    @EventHandler(priority = EventPriority.MONITOR)
    fun onChunkLoad(e: ChunkLoadEvent) {
        if (entitiesLoadEventClass != null || !settings.scanItemFrames) return
        for (entity in e.chunk.entities) if (entity is ItemFrame) tracker.scanFrame(entity)
    }
}
