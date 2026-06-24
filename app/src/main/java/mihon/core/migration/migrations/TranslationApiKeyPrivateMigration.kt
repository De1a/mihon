package mihon.core.migration.migrations

import mihon.core.migration.Migration
import mihon.core.migration.MigrationContext
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.util.lang.withIOContext

/**
 * Moves the translation API key from its legacy plaintext preference
 * ("translation_api_key") to the private key ("__PRIVATE_translation_api_key"),
 * so the credential is excluded from unencrypted backups.
 *
 * Marked ALWAYS so it also applies on development builds where the app version
 * code does not change. Idempotent: once the legacy value is moved and the old
 * key deleted, subsequent runs are no-ops.
 */
class TranslationApiKeyPrivateMigration : Migration {
    override val version: Float = Migration.ALWAYS

    override suspend fun invoke(migrationContext: MigrationContext): Boolean = withIOContext {
        val preferenceStore = migrationContext.get<PreferenceStore>() ?: return@withIOContext false

        val legacy = preferenceStore.getString("translation_api_key", "")
        val legacyValue = legacy.get()
        if (legacyValue.isEmpty()) return@withIOContext true

        val current = preferenceStore.getString(Preference.privateKey("translation_api_key"), "")
        if (!current.isSet()) {
            current.set(legacyValue)
        }
        legacy.delete()
        return@withIOContext true
    }
}
