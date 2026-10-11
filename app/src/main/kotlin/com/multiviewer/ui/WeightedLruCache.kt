package com.multiviewer.ui

/** Caps retained pixel storage as well as entry count; Compose owns the published bitmaps. */
internal class WeightedLruCache<K, V>(
    private val maxBytes: Long,
    private val maxEntries: Int,
    private val weight: (V) -> Long,
) {
    private data class Entry<V>(val value: V, val bytes: Long)
    private val entries = LinkedHashMap<K, Entry<V>>(16, 0.75f, true)
    private var bytes = 0L
    private var protectedKeys = emptySet<K>()

    init { require(maxBytes > 0 && maxEntries > 0) }

    fun putAll(values: Map<K, V>) {
        for ((key, value) in values) {
            val size = weight(value).coerceAtLeast(0)
            if (size > maxBytes) continue
            entries.remove(key)?.let { bytes -= it.bytes }
            entries[key] = Entry(value, size)
            bytes += size
            while (bytes > maxBytes || entries.size > maxEntries) {
                val victim = entries.entries.firstOrNull { it.key !in protectedKeys } ?: entries.entries.first()
                bytes -= victim.value.bytes
                entries.remove(victim.key)
            }
        }
    }

    fun touch(keys: Iterable<K>) { keys.forEach { entries[it] } }
    /** Updated on viewport changes; delayed batches must not restore their old viewport. */
    fun protect(keys: Iterable<K>) {
        protectedKeys = keys.toSet()
        touch(protectedKeys)
    }
    fun snapshot(): Map<K, V> = entries.mapValues { it.value.value }
    fun clear() { entries.clear(); bytes = 0; protectedKeys = emptySet() }
}
