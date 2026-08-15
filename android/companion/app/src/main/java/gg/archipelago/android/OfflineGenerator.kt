package gg.archipelago.android

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

    private fun python(context: Context): Python {
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(context.applicationContext))
        }
        return Python.getInstance()
    }

    fun defaultYaml(context: Context): String = synchronized(lock) {
        python(context).getModule("offline_generator").callAttr("default_yaml").toString()
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
        python(context).getModule("offline_generator")
            .callAttr("patch_rom", patch, baseRom, output.absolutePath)
        output
    }
}
