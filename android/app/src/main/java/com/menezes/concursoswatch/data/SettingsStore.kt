package com.menezes.concursoswatch.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import com.menezes.concursoswatch.model.UserSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "concursos_watch_settings")

class SettingsStore(private val context: Context) {
    private object Keys {
        val FEDERAL = booleanPreferencesKey("notify_federal")
        val SC = booleanPreferencesKey("notify_sc")
        val SUL = booleanPreferencesKey("notify_sul")
        val BAIXADA = booleanPreferencesKey("notify_baixada")
        val OPEN = booleanPreferencesKey("notify_only_open")
        val RELEVANT = booleanPreferencesKey("notify_only_relevant")
    }

    val flow: Flow<UserSettings> = context.dataStore.data.map { p ->
        UserSettings(
            notifyFederal = p[Keys.FEDERAL] ?: true,
            notifySantaCatarina = p[Keys.SC] ?: true,
            notifySul = p[Keys.SUL] ?: true,
            notifyBaixada = p[Keys.BAIXADA] ?: true,
            notifyOnlyOpen = p[Keys.OPEN] ?: true,
            notifyOnlyRelevant = p[Keys.RELEVANT] ?: true,
        )
    }

    suspend fun save(value: UserSettings) {
        context.dataStore.edit { p ->
            p[Keys.FEDERAL] = value.notifyFederal
            p[Keys.SC] = value.notifySantaCatarina
            p[Keys.SUL] = value.notifySul
            p[Keys.BAIXADA] = value.notifyBaixada
            p[Keys.OPEN] = value.notifyOnlyOpen
            p[Keys.RELEVANT] = value.notifyOnlyRelevant
        }
    }
}
