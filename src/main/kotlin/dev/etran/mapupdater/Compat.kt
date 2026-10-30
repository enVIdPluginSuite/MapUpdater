package dev.etran.mapupdater

import org.bukkit.World
import org.bukkit.map.MapView
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Everything that is newer than the 1.13.2 API we compile against, or that is not in the
 * Bukkit API at all, is reached from here. Every lookup is cached and failure-tolerant.
 */
object Compat {
    private fun method(cls: Class<*>, name: String): Method? =
        try { cls.getMethod(name) } catch (_: Throwable) { null }

    private val isLockedM = method(MapView::class.java, "isLocked")
    private val isVirtualM = method(MapView::class.java, "isVirtual")
    private val minHeightM = method(World::class.java, "getMinHeight")
    private val hasCeilingM = method(World::class.java, "hasCeiling")

    fun isLocked(view: MapView): Boolean = try { isLockedM?.invoke(view) as? Boolean ?: false } catch (_: Throwable) { false }
    fun isVirtual(view: MapView): Boolean = try { isVirtualM?.invoke(view) as? Boolean ?: false } catch (_: Throwable) { false }

    /** Lowest block Y of the world (0 before 1.17, e.g. -64 in the modern overworld). */
    fun minHeight(world: World): Int = try { (minHeightM?.invoke(world) as? Int) ?: 0 } catch (_: Throwable) { 0 }

    /** True for dimensions with a solid roof (the Nether). */
    fun hasCeiling(world: World): Boolean {
        try {
            val r = hasCeilingM?.invoke(world) as? Boolean
            if (r != null) return r
        } catch (_: Throwable) { /* fall through */ }
        return world.environment == World.Environment.NETHER
    }

    /** Map id as an Int whether the server's MapView#getId returns short or int. */
    fun id(view: MapView): Int = (view.id as Number).toInt()
}

/**
 * Direct access to a map's 128x128 colour array.
 *
 * Bukkit has no API to change the stored pixels of a map (renderers only affect what is
 * *sent*, and are lost on restart), so to genuinely update a vanilla map we reach into the
 * server's map data. Server internals are obfuscated / renamed between versions, so nothing
 * is looked up by name: we find the field that holds a `byte[16384]` and the `(int,int) -> void`
 * "mark dirty" method by their *shape*. If anything cannot be found, [supported] stays false,
 * the reason is reported in /mapupdater status, and no map is ever touched.
 */
object MapAccess {
    private const val SIZE = 128 * 128

    @Volatile var checked = false; private set
    @Volatile var supported = false; private set
    @Volatile var failure: String? = null; private set
    @Volatile var hasDirtyMethod = false; private set

    private var dataField: Field? = null      // CraftMapView -> server map data object
    private var colorsField: Field? = null    // server map data -> byte[16384]
    private var dirtyMethod: Method? = null   // server map data .setColorsDirty(int,int)

    /** Probes [view] once. Safe to call repeatedly; later calls are no-ops. */
    @Synchronized
    fun ensureChecked(view: MapView) {
        if (checked) return
        checked = true
        try {
            probe(view)
            supported = true
        } catch (t: Throwable) {
            supported = false
            failure = "${t.javaClass.simpleName}: ${t.message}"
        }
    }

    private fun probe(view: MapView) {
        var cls: Class<*>? = view.javaClass
        while (cls != null && cls != Any::class.java) {
            for (f in cls.declaredFields) {
                if (Modifier.isStatic(f.modifiers) || f.type.isPrimitive || f.type.isArray) continue
                val tn = f.type.name
                if (tn.startsWith("java.") || tn.startsWith("org.bukkit.") && !tn.startsWith("org.bukkit.craftbukkit.")) continue
                val value = try { f.isAccessible = true; f.get(view) } catch (_: Throwable) { null } ?: continue
                val colors = findColorsField(value.javaClass, value) ?: continue
                dataField = f
                colorsField = colors
                dirtyMethod = findDirtyMethod(value.javaClass)
                hasDirtyMethod = dirtyMethod != null
                return
            }
            cls = cls.superclass
        }
        throw IllegalStateException("could not locate the map data inside ${view.javaClass.name}")
    }

    private fun findColorsField(type: Class<*>, instance: Any): Field? {
        var c: Class<*>? = type
        while (c != null && c != Any::class.java) {
            for (f in c.declaredFields) {
                if (Modifier.isStatic(f.modifiers) || f.type != ByteArray::class.java) continue
                try {
                    f.isAccessible = true
                    val arr = f.get(instance) as? ByteArray
                    if (arr != null && arr.size == SIZE) return f
                } catch (_: Throwable) { /* keep looking */ }
            }
            c = c.superclass
        }
        return null
    }

    private fun findDirtyMethod(type: Class<*>): Method? {
        val found = LinkedHashSet<Method>()
        var c: Class<*>? = type
        while (c != null && c != Any::class.java) {
            for (m in c.declaredMethods) {
                if (!Modifier.isStatic(m.modifiers) && m.returnType == Void.TYPE &&
                    m.parameterTypes.size == 2 && m.parameterTypes.all { it == Int::class.javaPrimitiveType }
                ) found.add(m)
            }
            c = c.superclass
        }
        val pick = found.firstOrNull { it.name == "setColorsDirty" } ?: found.singleOrNull() ?: return null
        pick.isAccessible = true
        return pick
    }

    /** The live pixel array of [view], or null if unavailable. Re-read this on every use, never cache it. */
    fun colors(view: MapView): ByteArray? {
        if (!supported) return null
        return try {
            val data = dataField!!.get(view) ?: return null
            colorsField!!.get(data) as? ByteArray
        } catch (_: Throwable) { null }
    }

    /** Tells the server the pixel at ([x],[y]) changed: schedules a save and a client update. */
    fun markDirty(view: MapView, x: Int, y: Int) {
        val m = dirtyMethod ?: return
        try { m.invoke(dataField!!.get(view), x, y) } catch (_: Throwable) { /* non-fatal */ }
    }
}
