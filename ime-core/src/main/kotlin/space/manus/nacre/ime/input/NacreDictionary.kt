package space.manus.nacre.ime.input

import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Nacre Japanese Dictionary.
 *
 * Loads a TSV dictionary from assets (reading\tsurface\tcost per line).
 * Provides:
 * - Exact match conversion (kana → kanji candidates)
 * - Prefix-match prediction (partial reading → candidates)
 * - Viterbi-based segmentation for multi-word conversion
 * - User learning (boost selected candidates)
 *
 * Dictionary format (assets/dict/nacre_dict.tsv):
 *   ひらがな読み\t表層形\tコスト
 *   かんじ\t漢字\t5000
 *   かんじ\t感じ\t5500
 *   ...
 */
class NacreDictionary(private val context: Context) : DictionaryProvider {

    // reading → list of (surface, cost)
    private val dict = HashMap<String, MutableList<DictEntry>>(50000)

    // Sorted readings for prefix search
    private var sortedReadings: Array<String> = emptyArray()

    // User learning: boost for selected candidates
    private val userBoost = HashMap<String, Int>(1000) // "reading:surface" → boost

    private var loaded = false

    data class DictEntry(
        val surface: String,
        val cost: Int,
    )

    fun load() {
        if (loaded) return

        try {
            context.assets.open("dict/nacre_dict.tsv").use { stream ->
                BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
                    reader.forEachLine { line ->
                        if (line.isBlank() || line.startsWith('#')) return@forEachLine
                        val parts = line.split('\t')
                        if (parts.size >= 3) {
                            val reading = parts[0]
                            val surface = parts[1]
                            val cost = parts[2].toIntOrNull() ?: 10000
                            dict.getOrPut(reading) { mutableListOf() }
                                .add(DictEntry(surface, cost))
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // Dictionary file not found — fall back to no conversion
        }

        // Sort entries by cost within each reading
        for (entries in dict.values) {
            entries.sortBy { it.cost }
        }

        sortedReadings = dict.keys.toTypedArray().also { it.sort() }
        loaded = true

        // Load user learning data
        loadUserBoost()
    }

    // --- DictionaryProvider implementation ---

    override fun convert(kana: String): List<ConversionCandidate> {
        if (!loaded || kana.isEmpty()) return emptyList()

        // Try Viterbi segmentation first
        val viterbiResult = viterbiConvert(kana)
        if (viterbiResult.isNotEmpty()) return viterbiResult

        // Fallback: exact match
        return exactMatch(kana)
    }

    override fun predict(kana: String): List<ConversionCandidate> {
        if (!loaded || kana.isEmpty()) return emptyList()

        val results = mutableListOf<ConversionCandidate>()

        // Exact match first
        results.addAll(exactMatch(kana))

        // Prefix match
        val prefixResults = prefixMatch(kana, limit = 20 - results.size)
        results.addAll(prefixResults)

        return results.distinctBy { it.surface }.take(20)
    }

    override fun recordSelection(candidate: ConversionCandidate) {
        val key = "${candidate.reading}:${candidate.surface}"
        userBoost[key] = (userBoost[key] ?: 0) + 1
        saveUserBoost()
    }

    // --- Internal ---

    private fun exactMatch(kana: String): List<ConversionCandidate> {
        val entries = dict[kana] ?: return emptyList()
        return entries
            .map { entry ->
                val boost = userBoost["$kana:${entry.surface}"] ?: 0
                ConversionCandidate(
                    surface = entry.surface,
                    reading = kana,
                    cost = entry.cost - boost * 500,
                )
            }
            .sortedBy { it.cost }
            .take(20)
    }

    private fun prefixMatch(kana: String, limit: Int): List<ConversionCandidate> {
        if (limit <= 0) return emptyList()

        val results = mutableListOf<ConversionCandidate>()

        // Binary search for first reading that starts with kana
        var idx = sortedReadings.binarySearch(kana).let {
            if (it >= 0) it else -(it + 1)
        }

        while (idx < sortedReadings.size && sortedReadings[idx].startsWith(kana)) {
            val reading = sortedReadings[idx]
            if (reading != kana) { // Skip exact matches (already added)
                val entries = dict[reading] ?: continue
                for (entry in entries.take(3)) {
                    results.add(
                        ConversionCandidate(
                            surface = entry.surface,
                            reading = reading,
                            cost = entry.cost,
                        ),
                    )
                    if (results.size >= limit) return results
                }
            }
            idx++
        }

        return results
    }

    // --- Viterbi segmentation ---

    /**
     * Viterbi-based segmentation: find the lowest-cost path through the kana string.
     *
     * For each position i in the string, find all dictionary entries that end at i,
     * then pick the path with minimum total cost.
     */
    private fun viterbiConvert(kana: String): List<ConversionCandidate> {
        val n = kana.length
        if (n == 0) return emptyList()

        // dp[i] = (minCost, backPointer, bestEntry) for position i
        data class Node(
            val cost: Int,
            val backPos: Int,
            val surface: String,
            val reading: String,
        )

        val dp = arrayOfNulls<Node>(n + 1)
        dp[0] = Node(cost = 0, backPos = -1, surface = "", reading = "")

        for (endPos in 1..n) {
            // Try all possible start positions for a segment ending at endPos
            val maxSegLen = minOf(endPos, 12) // Max segment length (practical limit)
            for (segLen in 1..maxSegLen) {
                val startPos = endPos - segLen
                val prevNode = dp[startPos] ?: continue
                val segment = kana.substring(startPos, endPos)

                val entries = dict[segment]
                if (entries != null && entries.isNotEmpty()) {
                    // Use the best entry for this segment
                    val bestEntry = entries.first()
                    val boost = userBoost["$segment:${bestEntry.surface}"] ?: 0
                    val segCost = bestEntry.cost - boost * 500
                    val totalCost = prevNode.cost + segCost

                    val current = dp[endPos]
                    if (current == null || totalCost < current.cost) {
                        dp[endPos] = Node(
                            cost = totalCost,
                            backPos = startPos,
                            surface = bestEntry.surface,
                            reading = segment,
                        )
                    }
                } else if (segLen == 1) {
                    // Single character with no dictionary entry — pass through as-is
                    val totalCost = prevNode.cost + 20000
                    val current = dp[endPos]
                    if (current == null || totalCost < current.cost) {
                        dp[endPos] = Node(
                            cost = totalCost,
                            backPos = startPos,
                            surface = segment,
                            reading = segment,
                        )
                    }
                }
            }
        }

        // Reconstruct path
        val finalNode = dp[n] ?: return emptyList()

        // If the path is just a single segment, return candidates for it instead
        if (finalNode.backPos == 0) {
            return exactMatch(kana)
        }

        val segments = mutableListOf<String>()
        var pos = n
        while (pos > 0) {
            val node = dp[pos] ?: break
            if (node.surface.isNotEmpty()) {
                segments.add(0, node.surface)
            }
            pos = node.backPos
        }

        if (segments.isEmpty()) return emptyList()

        val combined = segments.joinToString("")
        val result = mutableListOf(
            ConversionCandidate(surface = combined, reading = kana, cost = finalNode.cost),
        )

        // Also add individual exact matches as alternatives
        result.addAll(exactMatch(kana).filter { it.surface != combined })

        return result.take(20)
    }

    // --- User learning persistence ---

    private fun loadUserBoost() {
        try {
            val prefs = context.getSharedPreferences("nacre_user_dict", Context.MODE_PRIVATE)
            val data = prefs.getString("boost", null) ?: return
            for (line in data.split('\n')) {
                val parts = line.split('\t')
                if (parts.size == 2) {
                    userBoost[parts[0]] = parts[1].toIntOrNull() ?: 0
                }
            }
        } catch (_: Exception) { }
    }

    private fun saveUserBoost() {
        try {
            val prefs = context.getSharedPreferences("nacre_user_dict", Context.MODE_PRIVATE)
            val data = userBoost.entries
                .sortedByDescending { it.value }
                .take(5000)
                .joinToString("\n") { "${it.key}\t${it.value}" }
            prefs.edit().putString("boost", data).apply()
        } catch (_: Exception) { }
    }
}
