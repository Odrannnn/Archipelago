package eu.odran.archipelago

import android.content.Context
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import org.json.JSONObject
import java.io.File

data class GeneratedArtifact(val name: String, val path: String, val kind: String)

data class GenerationResult(
    val seed: String,
    val players: List<String>,
    val files: List<GeneratedArtifact>,
    val patches: List<GeneratedArtifact>,
)

/** Serialized access to the embedded Archipelago Python runtime. */
object OfflineGenerator {
    private val lock = Any()
    private val templateAssets = linkedMapOf(
        "Metroid Fusion" to "templates/metroid_fusion.yaml",
        "The Minish Cap" to "templates/the_minish_cap.yaml",
    )

    val supportedGames: List<String>
        get() = templateAssets.keys.toList()

    private fun python(context: Context): Python {
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(context.applicationContext))
        }
        return Python.getInstance()
    }

    fun defaultYaml(context: Context, game: String = supportedGames.first()): String {
        val asset = templateAssets[game] ?: error("Unsupported offline game: $game")
        return context.assets.open(asset).bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    fun patchGame(context: Context, patch: ByteArray): String = synchronized(lock) {
        python(context).getModule("offline_generator").callAttr("patch_game", patch).toString()
    }

    fun generate(context: Context, yaml: String, seed: String): GenerationResult = synchronized(lock) {
        val workDirectory = File(context.filesDir, "offline_generator").apply { mkdirs() }
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

    fun patchRom(context: Context, patch: ByteArray, baseRom: ByteArray, output: File): File = synchronized(lock) {
        val workDirectory = File(context.filesDir, "offline_generator").apply { mkdirs() }
        python(context).getModule("offline_generator")
            .callAttr("patch_rom", patch, baseRom, output.absolutePath, workDirectory.absolutePath)
        output
    }
}
