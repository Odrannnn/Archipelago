package eu.odran.archipelago

import android.content.Context
import org.json.JSONObject

data class GeneratorDraft(
    val playersJson: String,
    val selectedPlayerIndex: Int,
    val seed: String,
    val advancedYaml: String,
    val advancedYamlDirty: Boolean,
)

/** Keeps the unfinished generator form available across recreation, navigation, and process restarts. */
object GeneratorDraftStore {
    private const val PREFERENCES = "generator_draft"
    private const val KEY_DRAFT = "draft"
    private const val VERSION = 1

    fun load(context: Context): GeneratorDraft? = runCatching {
        val encoded = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getString(KEY_DRAFT, null)
            ?: return null
        val root = JSONObject(encoded)
        require(root.optInt("version") == VERSION) { "Unsupported generator draft version" }
        GeneratorDraft(
            playersJson = root.getString("players_json"),
            selectedPlayerIndex = root.optInt("selected_player"),
            seed = root.optString("seed"),
            advancedYaml = root.optString("advanced_yaml"),
            advancedYamlDirty = root.optBoolean("advanced_yaml_dirty"),
        )
    }.getOrNull()

    fun save(context: Context, draft: GeneratorDraft) {
        val encoded = JSONObject().apply {
            put("version", VERSION)
            put("players_json", draft.playersJson)
            put("selected_player", draft.selectedPlayerIndex)
            put("seed", draft.seed)
            put("advanced_yaml", draft.advancedYaml)
            put("advanced_yaml_dirty", draft.advancedYamlDirty)
        }.toString()
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_DRAFT, encoded)
            .apply()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_DRAFT)
            .apply()
    }
}
