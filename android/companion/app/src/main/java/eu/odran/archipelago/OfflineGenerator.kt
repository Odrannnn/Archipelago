package eu.odran.archipelago

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class GeneratedArtifact(val name: String, val path: String, val kind: String)

data class BundledWorld(val game: String, val packageName: String, val platform: String)

data class RomInputRequirement(
    val key: String,
    val description: String,
    val fileName: String,
)

data class RomRequirements(
    val game: String,
    val inputs: List<RomInputRequirement>,
    val streaming: Boolean = false,
    val resultExtension: String = "",
)

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
    val attempts: Int,
    val players: List<String>,
    val files: List<GeneratedArtifact>,
    val patches: List<GeneratedArtifact>,
)

data class FormChoice(val value: String, val label: String)

data class FormOption(
    val key: String,
    val label: String,
    val description: String,
    val kind: String,
    val defaultValue: Any?,
    val choices: List<FormChoice>,
    val specialValues: List<FormChoice>,
    val minimum: Int?,
    val maximum: Int?,
)

data class FormOptionGroup(
    val name: String,
    val startCollapsed: Boolean,
    val options: List<FormOption>,
)

data class GameOptionSchema(val game: String, val groups: List<FormOptionGroup>)

data class PlayerFormData(
    var name: String,
    var game: String,
    val values: JSONObject,
    val extras: JSONObject,
)

/** Serialized access to the embedded Archipelago Python runtime. */
object OfflineGenerator {
    /** Chaquopy uses one interpreter. Generation and live clients share this lock. */
    internal val runtimeLock = Any()
    @Volatile private var bundledWorldCache: List<BundledWorld>? = null
    @Volatile private var catalog: List<WorldCapability> = emptyList()
    @Volatile private var worldFailures: Map<String, String> = emptyMap()

    internal fun python(context: Context): Python {
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(context.applicationContext))
        }
        return Python.getInstance()
    }

    internal fun workDirectory(context: Context): File = ImportedApWorldStore.runtimeRoot(context)

    fun bundledWorlds(context: Context): List<BundledWorld> = bundledWorldCache ?: synchronized(this) {
        bundledWorldCache ?: context.assets.open("bundled_worlds.json").bufferedReader().use { reader ->
            val entries = JSONArray(reader.readText())
            List(entries.length()) { index ->
                val entry = entries.getJSONObject(index)
                BundledWorld(
                    entry.getString("game"),
                    entry.getString("package"),
                    entry.optString("platform", "Other"),
                )
            }.also { bundledWorldCache = it }
        }
    }

    fun isBundledGame(context: Context, game: String): Boolean =
        bundledWorlds(context).any { it.game == game }

    fun availableGames(context: Context): List<String> =
        (bundledWorlds(context).map { it.game } + ImportedApWorldStore.list(context).map { it.game }).sorted()

    fun defaultYaml(context: Context, game: String): String = synchronized(runtimeLock) {
        require(game in availableGames(context)) { "$game is not available; import its APWorld first" }
        python(context).getModule("offline_generator")
            .callAttr("template_for_game", workDirectory(context).absolutePath, game)
            .toString()
    }

    fun optionSchema(context: Context, game: String): GameOptionSchema = synchronized(runtimeLock) {
        require(game in availableGames(context)) { "$game is not available; import its APWorld first" }
        val root = JSONObject(
            python(context).getModule("offline_generator")
                .callAttr("option_schema_for_game", workDirectory(context).absolutePath, game)
                .toString(),
        )
        val groupsJson = root.getJSONArray("groups")
        GameOptionSchema(
            game = root.getString("game"),
            groups = List(groupsJson.length()) { groupIndex ->
                val group = groupsJson.getJSONObject(groupIndex)
                val options = group.getJSONArray("options")
                FormOptionGroup(
                    name = group.getString("name"),
                    startCollapsed = group.optBoolean("start_collapsed"),
                    options = List(options.length()) { optionIndex ->
                        val option = options.getJSONObject(optionIndex)
                        FormOption(
                            key = option.getString("key"),
                            label = option.getString("label"),
                            description = option.optString("description"),
                            kind = option.getString("kind"),
                            defaultValue = option.opt("default").takeUnless { it == JSONObject.NULL },
                            choices = parseChoices(option.optJSONArray("choices")),
                            specialValues = parseChoices(option.optJSONArray("special_values")),
                            minimum = (option.opt("minimum") as? Number)?.toInt(),
                            maximum = (option.opt("maximum") as? Number)?.toInt(),
                        )
                    },
                )
            },
        )
    }

    fun playerFormsFromYaml(context: Context, yaml: String): List<PlayerFormData> = synchronized(runtimeLock) {
        val root = JSONObject(
            python(context).getModule("offline_generator")
                .callAttr("player_forms_from_yaml", workDirectory(context).absolutePath, yaml)
                .toString(),
        )
        decodePlayerForms(root)
    }

    fun decodePlayerForms(playersJson: String): List<PlayerFormData> =
        decodePlayerForms(JSONObject(playersJson))

    private fun decodePlayerForms(root: JSONObject): List<PlayerFormData> {
        val players = root.getJSONArray("players")
        return List(players.length()) { index ->
            val player = players.getJSONObject(index)
            PlayerFormData(
                name = player.getString("name"),
                game = player.getString("game"),
                values = player.getJSONObject("values"),
                extras = player.optJSONObject("extras") ?: JSONObject(),
            )
        }
    }

    fun encodePlayerForms(players: List<PlayerFormData>): String = JSONObject().apply {
        put("players", JSONArray().apply {
            players.forEach { player ->
                put(JSONObject().apply {
                    put("name", player.name)
                    put("game", player.game)
                    put("values", JSONObject(player.values.toString()))
                    put("extras", JSONObject(player.extras.toString()))
                })
            }
        })
    }.toString()

    fun yamlFromPlayerForms(context: Context, playersJson: String): String = synchronized(runtimeLock) {
        python(context).getModule("offline_generator")
            .callAttr("yaml_from_player_forms", playersJson)
            .toString()
    }

    fun patchGame(context: Context, patch: ByteArray): String = synchronized(runtimeLock) {
        python(context).getModule("offline_generator")
            .callAttr("patch_game", patch, workDirectory(context).absolutePath)
            .toString()
    }

    fun patchResultExtension(context: Context, patch: ByteArray): String = synchronized(runtimeLock) {
        python(context).getModule("offline_generator")
            .callAttr("patch_result_extension", patch, workDirectory(context).absolutePath)
            .toString()
    }

    fun romRequirements(context: Context, patch: ByteArray): RomRequirements = synchronized(runtimeLock) {
        val root = JSONObject(
            python(context).getModule("offline_generator")
                .callAttr("rom_requirements", patch, workDirectory(context).absolutePath)
                .toString(),
        )
        val inputs = root.getJSONArray("inputs")
        RomRequirements(
            game = root.getString("game"),
            inputs = List(inputs.length()) { index ->
                val input = inputs.getJSONObject(index)
                RomInputRequirement(
                    key = input.getString("key"),
                    description = input.getString("description"),
                    fileName = input.optString("file_name"),
                )
            },
            streaming = root.optBoolean("streaming", false),
            resultExtension = root.optString("result_extension"),
        )
    }

    fun validateRomInput(
        context: Context,
        patch: ByteArray,
        inputKey: String,
        rom: ByteArray,
    ) = synchronized(runtimeLock) {
        python(context).getModule("offline_generator").callAttr(
            "validate_rom_input",
            patch,
            inputKey,
            rom,
            workDirectory(context).absolutePath,
        )
        Unit
    }

    fun validateRomInputDocument(
        context: Context,
        patch: ByteArray,
        inputKey: String,
        uri: Uri,
    ) = synchronized(runtimeLock) {
        val descriptor = context.contentResolver.openFileDescriptor(uri, "r")
            ?: error("Could not open the selected ROM document")
        descriptor.use {
            python(context).getModule("offline_generator").callAttr(
                "validate_rom_input_fd",
                patch,
                inputKey,
                descriptor.fd,
                workDirectory(context).absolutePath,
            )
        }
        Unit
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
            attempts = root.optInt("attempts", 1).coerceAtLeast(1),
            players = List(players.length()) { players.getString(it) },
            files = parseArtifacts("files"),
            patches = parseArtifacts("patches"),
        )
    }

    fun patchRom(
        context: Context,
        patch: ByteArray,
        romInputs: Map<String, ByteArray>,
        output: File,
    ): File = synchronized(runtimeLock) {
        val workDirectory = workDirectory(context)
        val inputDirectory = File(workDirectory, "rom-inputs").apply {
            check(isDirectory || mkdirs()) { "Could not create temporary ROM input storage" }
        }
        val stagedInputs = romInputs.mapValues { (key, bytes) ->
            File.createTempFile("input-", ".rom", inputDirectory).apply { writeBytes(bytes) }
        }
        try {
            val paths = JSONObject()
            stagedInputs.forEach { (key, file) -> paths.put(key, file.absolutePath) }
            python(context).getModule("offline_generator")
                .callAttr("patch_rom", patch, paths.toString(), output.absolutePath, workDirectory.absolutePath)
            output
        } finally {
            stagedInputs.values.forEach { it.delete() }
        }
    }

    fun patchRomDocuments(
        context: Context,
        patch: ByteArray,
        romInputs: Map<String, Uri>,
        output: Uri,
    ) = synchronized(runtimeLock) {
        val openedInputs = mutableListOf<ParcelFileDescriptor>()
        var openedOutput: ParcelFileDescriptor? = null
        try {
            val paths = JSONObject()
            romInputs.forEach { (key, uri) ->
                val descriptor = context.contentResolver.openFileDescriptor(uri, "r")
                    ?: error("Could not open ROM input $key")
                openedInputs += descriptor
                paths.put(key, descriptor.fd)
            }
            openedOutput = context.contentResolver.openFileDescriptor(output, "rwt")
                ?: error("Could not open the selected ISO destination")
            python(context).getModule("offline_generator").callAttr(
                "patch_rom",
                patch,
                paths.toString(),
                openedOutput.fd,
                workDirectory(context).absolutePath,
            )
        } finally {
            openedOutput?.close()
            openedInputs.forEach { it.close() }
        }
    }

    private fun parseChoices(array: JSONArray?): List<FormChoice> = if (array == null) {
        emptyList()
    } else {
        List(array.length()) { index ->
            val choice = array.getJSONObject(index)
            FormChoice(choice.getString("value"), choice.getString("label"))
        }
    }
}
