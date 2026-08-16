package eu.odran.archipelago

import android.content.Context
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import org.json.JSONObject
import java.io.File

data class GeneratedArtifact(val name: String, val path: String, val kind: String)

data class WorldCapability(
    val game: String,
    val version: String,
    val source: String,
    val patchExtension: String,
    val resultExtension: String,
    val generation: Boolean,
    val template: Boolean,
    val romPatch: Boolean,
    val liveBridge: Boolean,
)

data class GenerationResult(
    val seed: String,
    val players: List<String>,
    val files: List<GeneratedArtifact>,
    val patches: List<GeneratedArtifact>,
)

/** Serialized access to the embedded Archipelago Python runtime. */
object OfflineGenerator {
    /** Chaquopy uses one interpreter. Generation and live clients share this lock. */
    internal val runtimeLock = Any()
    private val templateAssets = linkedMapOf(
        "Metroid Fusion" to "templates/metroid_fusion.yaml",
        "The Minish Cap" to "templates/the_minish_cap.yaml",
    )

    val builtInGames: List<String>
        get() = templateAssets.keys.toList()
    @Volatile private var catalog: List<WorldCapability> = emptyList()
    @Volatile private var worldFailures: Map<String, String> = emptyMap()

    internal fun python(context: Context): Python {
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(context.applicationContext))
        }
        return Python.getInstance()
    }

    internal fun workDirectory(context: Context): File = ImportedApWorldStore.runtimeRoot(context)

    fun availableGames(context: Context): List<String> = (
        builtInGames + ImportedApWorldStore.list(context).map { it.game }
    ).distinct()

    fun defaultYaml(context: Context, game: String = builtInGames.first()): String = synchronized(runtimeLock) {
        val asset = templateAssets[game]
        if (asset != null) {
            return@synchronized context.assets.open(asset).bufferedReader(Charsets.UTF_8).use { it.readText() }
        }
        python(context).getModule("offline_generator")
            .callAttr("template_for_game", workDirectory(context).absolutePath, game)
            .toString()
    }

    fun patchGame(context: Context, patch: ByteArray): String = synchronized(runtimeLock) {
        python(context).getModule("offline_generator")
            .callAttr("patch_game", patch, workDirectory(context).absolutePath)
            .toString()
    }

    fun refreshCatalog(context: Context): List<WorldCapability> = synchronized(runtimeLock) {
        val result = python(context).getModule("offline_generator")
            .callAttr("world_catalog", workDirectory(context).absolutePath)
            .toString()
        val root = JSONObject(result)
        val failures = root.optJSONObject("failures")
        worldFailures = if (failures == null) {
            emptyMap()
        } else {
            failures.keys().asSequence().associateWith { key -> failures.optString(key) }
        }
        val worlds = root.getJSONArray("worlds")
        catalog = List(worlds.length()) { index ->
            val item = worlds.getJSONObject(index)
            WorldCapability(
                game = item.getString("game"),
                version = item.optString("version", "0.0.0"),
                source = item.optString("source", "unknown"),
                patchExtension = item.optString("patch_extension"),
                resultExtension = item.optString("result_extension"),
                generation = item.optBoolean("generation"),
                template = item.optBoolean("template"),
                romPatch = item.optBoolean("rom_patch"),
                liveBridge = item.optBoolean("live_bridge"),
            )
        }
        catalog
    }

    fun cachedCatalog(): List<WorldCapability> = catalog

    fun cachedWorldFailures(): Map<String, String> = worldFailures

    fun generate(context: Context, yaml: String, seed: String): GenerationResult = synchronized(runtimeLock) {
        val workDirectory = workDirectory(context)
        val result = python(context).getModule("offline_generator")
            .callAttr("generate", yaml, workDirectory.absolutePath, seed)
            .toString()
        val root = JSONObject(result)

        fun parseArtifacts(key: String): List<GeneratedArtifact> {
            val array = root.getJSONArray(key)
            return List(array.length()) { index ->
                val item = array.getJSONObject(index)
                GeneratedArtifact(item.getString("name"), item.getString("path"), item.getString("kind"))
            }
        }

        val players = root.getJSONArray("players")
        GenerationResult(
            seed = root.getString("seed"),
            players = List(players.length()) { players.getString(it) },
            files = parseArtifacts("files"),
            patches = parseArtifacts("patches"),
        )
    }

    fun patchRom(context: Context, patch: ByteArray, baseRom: ByteArray, output: File): File = synchronized(runtimeLock) {
        val workDirectory = workDirectory(context)
        python(context).getModule("offline_generator")
            .callAttr("patch_rom", patch, baseRom, output.absolutePath, workDirectory.absolutePath)
        output
    }
}
