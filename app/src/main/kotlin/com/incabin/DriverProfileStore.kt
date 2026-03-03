package com.incabin

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

/**
 * Per-driver preference persistence store.
 * Mirrors FaceStore pattern: singleton, Gson JSON flat file, @Synchronized.
 *
 * Stores preferred temperature and ambient light color per recognized driver.
 * Profiles are loaded when the driver is identified by face recognition.
 */
data class DriverProfile(
    val name: String,
    val preferredTempC: Float = 22.0f,
    val ambientColorHex: String = "#5B8DEF"
)

class DriverProfileStore private constructor(context: Context) {

    companion object {
        private const val TAG = "DriverProfileStore"
        private const val FILENAME = "driver_profiles.json"
        private val gson = Gson()

        @Volatile
        private var instance: DriverProfileStore? = null

        fun getInstance(context: Context): DriverProfileStore {
            return instance ?: synchronized(this) {
                instance ?: DriverProfileStore(context.applicationContext).also { instance = it }
            }
        }
    }

    private val file: File = File(context.filesDir, FILENAME)
    private val profiles = mutableListOf<DriverProfile>()

    init {
        loadFromDisk()
    }

    /** Save a driver profile. Replaces existing entry with the same name. */
    @Synchronized
    fun save(profile: DriverProfile) {
        profiles.removeAll { it.name == profile.name }
        profiles.add(profile)
        saveToDisk()
        Log.i(TAG, "Saved profile: ${profile.name} (total: ${profiles.size})")
    }

    /** Get a driver profile by name. Returns null if not found. */
    @Synchronized
    fun get(name: String): DriverProfile? {
        return profiles.firstOrNull { it.name == name }
    }

    /** Delete a driver profile by name. Returns true if found and removed. */
    @Synchronized
    fun delete(name: String): Boolean {
        val removed = profiles.removeAll { it.name == name }
        if (removed) {
            saveToDisk()
            Log.i(TAG, "Deleted profile: $name (total: ${profiles.size})")
        }
        return removed
    }

    /** Get all stored profiles. */
    @Synchronized
    fun getAll(): List<DriverProfile> = profiles.toList()

    /** Number of stored profiles. */
    @Synchronized
    fun count(): Int = profiles.size

    private fun loadFromDisk() {
        try {
            if (!file.exists()) return
            val json = file.readText()
            val type = object : TypeToken<List<DriverProfile>>() {}.type
            val loaded: List<DriverProfile> = gson.fromJson(json, type) ?: return
            profiles.clear()
            profiles.addAll(loaded)
            Log.i(TAG, "Loaded ${profiles.size} profiles from disk")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load profiles from disk", e)
        }
    }

    private fun saveToDisk() {
        try {
            file.writeText(gson.toJson(profiles))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save profiles to disk", e)
        }
    }
}
