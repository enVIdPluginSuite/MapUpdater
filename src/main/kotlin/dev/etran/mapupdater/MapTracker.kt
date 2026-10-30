package dev.etran.mapupdater

import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.ItemFrame
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.MapMeta
import org.bukkit.map.MapView
import java.io.File
import java.lang.ref.WeakReference

/**
 * Remembers which map ids exist on the server (persisted in maps.yml) and discovers new ones.
 * Discovery is passive and cheap: maps are noticed as they are loaded, held, or framed.
 */
class MapTracker(private val plugin: MapUpdaterPlugin) {
    private val file = File(plugin.dataFolder, "maps.yml")
    private val ids = LinkedHashSet<Int>()
    private val views = HashMap<Int, WeakReference<MapView>>()
    private var dirty = false

    val size: Int get() = ids.size

    fun snapshot(): List<Int> = ArrayList(ids)
    fun contains(id: Int) = id in ids

    fun load() {
        ids.clear()
        if (!file.exists()) return
        try {
            YamlConfiguration.loadConfiguration(file).getIntegerList("ids").forEach { if (it >= 0) ids.add(it) }
        } catch (t: Throwable) {
            plugin.logger.warning("Could not read maps.yml: ${t.message}")
        }
    }

    fun saveIfDirty() {
        if (!dirty) return
        try {
            plugin.dataFolder.mkdirs()
            val y = YamlConfiguration()
            y.set("ids", ids.sorted())
            y.save(file)
            dirty = false
        } catch (t: Throwable) {
            plugin.logger.warning("Could not save maps.yml: ${t.message}")
        }
    }

    fun track(view: MapView): Boolean {
        val id = Compat.id(view)
        views[id] = WeakReference(view)
        if (!ids.add(id)) return false
        dirty = true
        if (plugin.settings.debug) plugin.logger.info("Tracking map #$id")
        return true
    }

    fun track(id: Int): Boolean {
        if (!ids.add(id)) return false
        dirty = true
        return true
    }

    fun untrack(id: Int): Boolean {
        views.remove(id)
        val removed = ids.remove(id)
        if (removed) dirty = true
        return removed
    }

    /** Finds the live MapView for [id], or null if that map no longer exists. */
    fun view(id: Int): MapView? {
        try {
            @Suppress("DEPRECATION")
            Bukkit.getMap(id)?.let { views[id] = WeakReference(it); return it }
        } catch (_: Throwable) { /* API shape differs on this version, fall back to what we have seen */ }
        return views[id]?.get()
    }

    // ---- discovery ---------------------------------------------------------------------------------------------

    fun scanItem(item: ItemStack?): Boolean {
        if (item == null || item.type != Material.FILLED_MAP) return false
        return try {
            val meta = item.itemMeta as? MapMeta ?: return false
            if (!meta.hasMapView()) return false
            val view = meta.mapView ?: return false
            track(view)
        } catch (_: Throwable) {
            false
        }
    }

    fun scanInventory(inv: Inventory?): Int {
        if (inv == null) return 0
        var found = 0
        for (item in inv.contents) if (scanItem(item)) found++
        return found
    }

    fun scanPlayer(p: Player): Int {
        var found = scanInventory(p.inventory)
        if (scanItem(p.inventory.itemInMainHand)) found++
        return found
    }

    fun scanFrame(frame: ItemFrame): Boolean = scanItem(frame.item)
}
