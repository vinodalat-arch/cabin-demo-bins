package com.incabin

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

/**
 * Persistent store for registered face embeddings.
 * Singleton — shared between InCabinService and FaceRegistrationActivity
 * so that newly registered faces are immediately available for recognition.
 * Saves as flat JSON file (faces.json) on internal storage.
 * All public methods are synchronized for thread safety.
 */
class FaceStore private constructor(context: Context) {

    companion object {
        private const val TAG = "FaceStore"
        private const val FILENAME = "faces.json"
        private val gson = Gson()

        @Volatile
        private var instance: FaceStore? = null

        /** Get the singleton FaceStore instance. */
        fun getInstance(context: Context): FaceStore {
            return instance ?: synchronized(this) {
                instance ?: FaceStore(context.applicationContext).also { instance = it }
            }
        }

        /**
         * Compute cosine similarity between two embeddings.
         * Works correctly with both normalized and unnormalized inputs.
         */
        fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
            if (a.size != b.size || a.isEmpty()) return 0f
            var dot = 0f
            var normA = 0f
            var normB = 0f
            for (i in a.indices) {
                dot += a[i] * b[i]
                normA += a[i] * a[i]
                normB += b[i] * b[i]
            }
            val denom = kotlin.math.sqrt(normA) * kotlin.math.sqrt(normB)
            return if (denom > 1e-8f) dot / denom else 0f
        }
    }

    data class RegisteredFace(val name: String, val embedding: FloatArray)

    private val file: File = File(context.filesDir, FILENAME)
    private val faces = mutableListOf<RegisteredFace>()

    init {
        loadFromDisk()
    }

    /** Register a face. Replaces existing entry with the same name. */
    @Synchronized
    fun register(name: String, embedding: FloatArray) {
        faces.removeAll { it.name == name }
        faces.add(RegisteredFace(name, embedding))
        saveToDisk()
        Log.i(TAG, "Registered face: $name (total: ${faces.size})")
    }

    /** Delete a face by name. Returns true if found and removed. */
    @Synchronized
    fun delete(name: String): Boolean {
        val removed = faces.removeAll { it.name == name }
        if (removed) {
            saveToDisk()
            Log.i(TAG, "Deleted face: $name (total: ${faces.size})")
        }
        return removed
    }

    /** Rename a registered face. Removes any existing entry with newName to prevent duplicates. Returns true if oldName was found. */
    @Synchronized
    fun rename(oldName: String, newName: String): Boolean {
        val index = faces.indexOfFirst { it.name == oldName }
        if (index < 0) return false
        faces.removeAll { it.name == newName }
        val old = faces[index]
        faces[index] = RegisteredFace(newName, old.embedding)
        saveToDisk()
        Log.i(TAG, "Renamed face: $oldName -> $newName (total: ${faces.size})")
        return true
    }

    /** Get all registered face names. */
    @Synchronized
    fun getNames(): List<String> = faces.map { it.name }

    /** Get all registered faces. */
    @Synchronized
    fun getAll(): List<RegisteredFace> = faces.toList()

    /** Number of registered faces. */
    @Synchronized
    fun count(): Int = faces.size

    /**
     * Find the best matching face above the given threshold.
     * @return Pair(name, similarity) or null if no match above threshold
     */
    @Synchronized
    fun findBestMatch(embedding: FloatArray, threshold: Float): Pair<String, Float>? {
        var bestName: String? = null
        var bestSim = -1f

        for (face in faces) {
            val sim = cosineSimilarity(embedding, face.embedding)
            if (sim > bestSim) {
                bestSim = sim
                bestName = face.name
            }
        }

        return if (bestName != null && bestSim >= threshold) {
            Pair(bestName, bestSim)
        } else {
            null
        }
    }

    private fun loadFromDisk() {
        try {
            if (!file.exists()) return
            val json = file.readText()
            val type = object : TypeToken<List<SerializedFace>>() {}.type
            val loaded: List<SerializedFace> = gson.fromJson(json, type) ?: return
            faces.clear()
            for (sf in loaded) {
                faces.add(RegisteredFace(sf.name, sf.embedding))
            }
            Log.i(TAG, "Loaded ${faces.size} faces from disk")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load faces from disk", e)
        }
    }

    private fun saveToDisk() {
        try {
            val serialized = faces.map { SerializedFace(it.name, it.embedding) }
            file.writeText(gson.toJson(serialized))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save faces to disk", e)
        }
    }

    /** JSON-friendly version of RegisteredFace (FloatArray serializes fine with Gson). */
    private data class SerializedFace(val name: String, val embedding: FloatArray)
}
