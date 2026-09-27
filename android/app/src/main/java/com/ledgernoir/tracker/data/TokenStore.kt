package com.ledgernoir.tracker.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "ledger_prefs")

class TokenStore(private val context: Context) {

    companion object {
        private val KEY_BASE_URL = stringPreferencesKey("base_url")
        private val KEY_TOKEN    = stringPreferencesKey("bearer_token")
        private val KEY_DEVICE_KEY = stringPreferencesKey("device_key")
        private val KEY_LAST_SMS_IMPORT = longPreferencesKey("last_sms_import_millis")
        private val KEY_SHOWN_NOTIFS = stringSetPreferencesKey("shown_notification_ids")
        private val KEY_READ_NOTIFS = stringSetPreferencesKey("read_notification_ids")
        const val DEFAULT_BASE_URL = "http://10.0.2.2:8000"
    }

    val baseUrl: Flow<String> = context.dataStore.data
        .map { prefs -> prefs[KEY_BASE_URL] ?: DEFAULT_BASE_URL }

    val token: Flow<String> = context.dataStore.data
        .map { prefs -> prefs[KEY_TOKEN] ?: "" }

    /** Paired-device secret sent as X-Device-Key so the server will mint a session. */
    val deviceKey: Flow<String> = context.dataStore.data
        .map { prefs -> prefs[KEY_DEVICE_KEY] ?: "" }

    /** Timestamp (epoch millis) of the newest SMS imported so far. 0 = never. */
    val lastSmsImportMillis: Flow<Long> = context.dataStore.data
        .map { prefs -> prefs[KEY_LAST_SMS_IMPORT] ?: 0L }

    /** Allows https anywhere; allows http only to loopback / private (LAN) hosts. */
    fun isAllowedBaseUrl(url: String): Boolean {
        val u = url.trim().lowercase()
        if (u.startsWith("https://")) return true
        if (!u.startsWith("http://")) return false
        val host = u.removePrefix("http://").substringBefore('/').substringBefore(':')
        return host == "10.0.2.2" || host == "localhost" || host == "127.0.0.1" ||
            host.startsWith("192.168.") || host.startsWith("10.") ||
            isTailscaleCgnat(host) ||  // Tailscale CGNAT range 100.64.0.0/10
            (host.endsWith(".ts.net") && host.count { it == '.' } >= 2) ||  // Tailscale MagicDNS
            Regex("^172\\.(1[6-9]|2[0-9]|3[01])\\.").containsMatchIn(host)
    }

    /** True only for IPs in Tailscale's CGNAT range 100.64.0.0/10 (100.64–100.127.x.x). */
    private fun isTailscaleCgnat(host: String): Boolean {
        val parts = host.split('.').mapNotNull { it.toIntOrNull() }
        if (parts.size != 4 || parts.any { it !in 0..255 }) return false
        return parts[0] == 100 && parts[1] in 64..127
    }

    suspend fun setBaseUrl(url: String) {
        require(isAllowedBaseUrl(url)) { "Refusing cleartext http:// to a non-private host; use https://" }
        context.dataStore.edit { it[KEY_BASE_URL] = url.trimEnd('/') }
    }

    suspend fun setToken(token: String) {
        context.dataStore.edit { it[KEY_TOKEN] = token }
    }

    suspend fun setDeviceKey(key: String) {
        context.dataStore.edit { it[KEY_DEVICE_KEY] = key }
    }

    suspend fun clearToken() {
        context.dataStore.edit { it.remove(KEY_TOKEN) }
    }

    suspend fun setLastSmsImportMillis(millis: Long) {
        context.dataStore.edit { it[KEY_LAST_SMS_IMPORT] = millis }
    }

    /** IDs of notifications already posted to the system tray (dedupe set). */
    suspend fun shownNotificationIds(): Set<String> =
        context.dataStore.data.first()[KEY_SHOWN_NOTIFS] ?: emptySet()

    /** Record newly-posted notification ids, capping the set so it can't grow
     *  unbounded (keeps the most recent 200). */
    suspend fun addShownNotificationIds(ids: Set<String>) {
        context.dataStore.edit { prefs ->
            val merged = (prefs[KEY_SHOWN_NOTIFS] ?: emptySet()) + ids
            prefs[KEY_SHOWN_NOTIFS] = merged.toList().takeLast(200).toSet()
        }
    }

    /** IDs the user has marked read in the in-app notification center (reactive,
     *  so the bell badge updates the moment something is read). */
    val readNotificationIds: Flow<Set<String>> = context.dataStore.data
        .map { prefs -> prefs[KEY_READ_NOTIFS] ?: emptySet() }

    suspend fun markNotificationsRead(ids: Set<String>) {
        context.dataStore.edit { prefs ->
            val merged = (prefs[KEY_READ_NOTIFS] ?: emptySet()) + ids
            prefs[KEY_READ_NOTIFS] = merged.toList().takeLast(200).toSet()
        }
    }
}
