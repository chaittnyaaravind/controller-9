package dev.ninecontroller.android.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import java.time.temporal.ChronoUnit

/** A host this phone has paired with. `pub` is the host's static key (base64), pinned at pairing. */
@Serializable
data class PairedHost(
    val id: String,
    val name: String,
    val pub: String,
    val addr: String,
    val port: Int,
    val pairedAt: String,
    val lastUsed: String? = null,
)

class HostStore(context: Context) {
    private val file = File(context.filesDir, "hosts.json")
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val _hosts = MutableStateFlow(load())
    val hosts: StateFlow<List<PairedHost>> = _hosts

    private fun load(): List<PairedHost> =
        runCatching { json.decodeFromString(ListSerializer(PairedHost.serializer()), file.readText()) }.getOrDefault(emptyList())

    @Synchronized private fun save(list: List<PairedHost>) {
        val tmp = File(file.path + ".tmp")
        tmp.writeText(json.encodeToString(ListSerializer(PairedHost.serializer()), list))
        tmp.renameTo(file)
        _hosts.value = list
    }

    fun upsert(h: PairedHost) = save(_hosts.value.filterNot { it.id == h.id } + h)
    fun touch(id: String, addr: String, port: Int) = _hosts.value.find { it.id == id }?.let {
        upsert(it.copy(addr = addr, port = port, lastUsed = now()))
    }
    fun remove(id: String) = save(_hosts.value.filterNot { it.id == id })
    fun clear() = save(emptyList())
    fun byId(id: String) = _hosts.value.find { it.id == id }

    companion object { fun now(): String = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString() }
}
