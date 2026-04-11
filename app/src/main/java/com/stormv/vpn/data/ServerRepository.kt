package com.stormv.vpn.data

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.stormv.vpn.model.ServerConfig
import com.stormv.vpn.util.AppLogger
import com.stormv.vpn.util.EncryptionHelper
import com.tencent.mmkv.MMKV

object ServerRepository {

    private const val KEY_SERVERS_ENC = "servers_enc"   // зашифрованные
    private const val KEY_SERVERS_LEGACY = "servers"     // старые незашифрованные (миграция)

    private val kv by lazy { MMKV.defaultMMKV() }
    private val gson = Gson()

    fun loadAll(): List<ServerConfig> = runCatching {
        // Миграция: если есть незашифрованные — шифруем и удаляем
        val legacy = kv.decodeString(KEY_SERVERS_LEGACY)
        if (!legacy.isNullOrEmpty()) {
            AppLogger.i("Repo", "Мигрируем незашифрованные серверы...")
            val type = object : TypeToken<List<ServerConfig>>() {}.type
            val list: List<ServerConfig> = gson.fromJson(legacy, type) ?: emptyList()
            saveAll(list)
            kv.remove(KEY_SERVERS_LEGACY)
            AppLogger.i("Repo", "Миграция завершена, незашифрованные данные удалены")
            return list
        }

        val cipher = kv.decodeString(KEY_SERVERS_ENC) ?: return emptyList()
        val json = EncryptionHelper.decrypt(cipher)
        val type = object : TypeToken<List<ServerConfig>>() {}.type
        gson.fromJson<List<ServerConfig>>(json, type) ?: emptyList()
    }.getOrElse { e ->
        AppLogger.e("Repo", "Ошибка загрузки: ${e.message}")
        emptyList()
    }

    fun saveAll(servers: List<ServerConfig>) = runCatching {
        val json = gson.toJson(servers)
        val cipher = EncryptionHelper.encrypt(json)
        kv.encode(KEY_SERVERS_ENC, cipher)
        AppLogger.d("Repo", "Сохранено ${servers.size} серверов (AES-256-GCM)")
    }.onFailure { e ->
        AppLogger.e("Repo", "Ошибка сохранения: ${e.message}")
    }

    fun add(server: ServerConfig) {
        saveAll(loadAll() + server)
    }

    fun remove(id: String) {
        saveAll(loadAll().filter { it.id != id })
    }
}
