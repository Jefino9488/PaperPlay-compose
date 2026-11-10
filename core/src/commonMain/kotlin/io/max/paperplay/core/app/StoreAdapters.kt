package io.max.paperplay.core.app

import io.max.paperplay.core.backup.SecretKeys

private typealias ByteStore = io.max.paperplay.core.backup.SecretStore

/**
 * Both the backup restore and [AppServices] read the same secrets. The restore writes bytes under the
 * backup's key names; these adapters give the services string access under their own key names.
 */
private fun storeKey(key: String) = when (key) {
    Keys.BOT_TOKEN -> SecretKeys.BOT_TOKEN
    Keys.API_ID -> SecretKeys.API_ID
    Keys.API_HASH -> SecretKeys.API_HASH
    else -> key
}

class BackedSecrets(private val store: ByteStore) : SecretStore {
    override fun get(key: String): String? = store.get(storeKey(key))?.decodeToString()
    override fun put(key: String, value: String) = store.put(storeKey(key), value.encodeToByteArray())
    override fun remove(key: String) = store.remove(storeKey(key))
}

/** The API id and hash live beside the bot token, so a restored backup finds them. */
class BackedSettings(private val store: ByteStore) : Settings {
    override fun get(key: String): String? = store.get(storeKey(key))?.decodeToString()
    override fun put(key: String, value: String) = store.put(storeKey(key), value.encodeToByteArray())
}
