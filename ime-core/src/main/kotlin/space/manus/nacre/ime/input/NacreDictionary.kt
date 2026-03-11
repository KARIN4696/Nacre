package space.manus.nacre.ime.input

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream
import space.manus.nacre.ai.KenLmScorer

/**
 * Nacre Japanese Dictionary with POS-aware Viterbi conversion.
 *
 * Uses Mozc OSS dictionary with POS group IDs and a grouped connection cost matrix
 * for accurate word segmentation approaching Google日本語入力 quality.
 *
 * Dictionary format: reading\tsurface\tleft_group\tright_group\tcost
 * Connection matrix: 14×14 POS group transition costs
 *
 * POS groups:
 *   0=BOS/EOS 1=名詞 2=動詞 3=形容詞 4=助動詞 5=助詞
 *   6=副詞 7=連体詞 8=接続詞 9=感動詞 10=接頭詞 11=記号
 *   12=フィラー 13=その他
 */
class NacreDictionary(private val context: Context) : DictionaryProvider {

    // reading → list of entries with POS info
    private val dict = HashMap<String, MutableList<DictEntry>>(4000000)

    // Sorted readings for prefix search
    private var sortedReadings: Array<String> = emptyArray()

    // POS group connection cost matrix [left_group][right_group]
    private var connectionCost: Array<IntArray> = emptyArray()
    private var numGroups = 14

    // User learning: boost for selected candidates (thread-safe)
    private val userBoost = ConcurrentHashMap<String, Int>(1000)

    // Bigram learning: "prevSurface→reading:surface" → count
    private val bigramBoost = ConcurrentHashMap<String, Int>(500)

    // Trigram learning: "prev2→prev1→reading:surface" → count
    private val trigramBoost = ConcurrentHashMap<String, Int>(300)

    // Recent history: ordered list of recently committed candidates (newest first)
    private val recentHistory = java.util.LinkedList<ConversionCandidate>()
    private val maxHistory = 200

    // English word dictionary (hiragana reading → English words)
    private val englishDict = HashMap<String, MutableList<DictEntry>>(5000)

    // Full English dictionary: lowercase key → list of DictEntry (surface may have mixed case)
    private val englishFullDict = HashMap<String, MutableList<DictEntry>>(25000)
    // Sorted keys for binary-search prefix matching
    private var englishSortedKeys: Array<String> = emptyArray()
    // English word learning: "prevWord→word" → count (bigram boost)
    private val englishBigramBoost = ConcurrentHashMap<String, Int>(200)
    private var lastCommittedEnglish: String = ""

    // N-gram context: last 2 committed surfaces
    private var lastCommittedSurface: String = ""
    private var secondLastCommittedSurface: String = ""

    // Last committed right POS group (for connection cost to next word)
    private var lastRightGroup: Int = 0  // BOS/EOS

    // KenLM 5-gram language model scorer (optional, loaded from ime-ai)
    @Volatile
    var kenLmScorer: KenLmScorer? = null

    private var loaded = false
    private var savePending = false
    private var lastSaveTime = 0L

    /** Total number of dictionary entries loaded (for debug display) */
    var entryCount = 0
        private set

    data class DictEntry(
        val surface: String,
        val cost: Int,
        val leftGroup: Int = 1,   // POS group for left context (default: noun)
        val rightGroup: Int = 1,  // POS group for right context
    )

    fun load() {
        if (loaded) return

        loadConnectionMatrix()
        loadMozcDictionary()
        loadSlangDictionary()
        loadEnglishDict()
        buildRomajiEnglishIndex()
        loadEnglishFullDict()
        loadUserBoost()

        loaded = true
    }

    private fun loadConnectionMatrix() {
        try {
            var rowIdx = 0
            context.assets.open("dict/connection_group.tsv").use { stream ->
                BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
                    reader.forEachLine { line ->
                        if (line.isBlank() || line.startsWith('#')) return@forEachLine
                        val trimmed = line.trim()
                        if (connectionCost.isEmpty()) {
                            numGroups = trimmed.toIntOrNull() ?: 14
                            connectionCost = Array(numGroups) { IntArray(numGroups) }
                            return@forEachLine
                        }
                        if (rowIdx < numGroups) {
                            val values = trimmed.split('\t')
                            for (j in values.indices) {
                                if (j < numGroups) {
                                    connectionCost[rowIdx][j] = values[j].toIntOrNull() ?: 5000
                                }
                            }
                            rowIdx++
                        }
                    }
                }
            }
            Log.i("NacreDictionary", "Connection matrix loaded: ${numGroups}x${numGroups}")
        } catch (e: Exception) {
            Log.e("NacreDictionary", "Failed to load connection matrix, using defaults", e)
            numGroups = 14
            connectionCost = Array(numGroups) { IntArray(numGroups) { 5000 } }
        }
    }

    private fun loadMozcDictionary() {
        try {
            // Try gzip binary first, fall back to plain TSV
            val stream = try {
                GZIPInputStream(context.assets.open("dict/mozc_dict.bin"))
            } catch (_: Exception) {
                context.assets.open("dict/mozc_dict.tsv")
            }

            stream.use { rawStream ->
                BufferedReader(InputStreamReader(rawStream, Charsets.UTF_8), 65536).use { reader ->
                    var count = 0
                    reader.forEachLine { line ->
                        if (line.isBlank() || line.startsWith('#')) return@forEachLine
                        val parts = line.split('\t')
                        if (parts.size >= 5) {
                            val reading = parts[0]
                            val surface = parts[1]
                            val leftGroup = parts[2].toIntOrNull() ?: 1
                            val rightGroup = parts[3].toIntOrNull() ?: 1
                            val cost = parts[4].toIntOrNull() ?: 10000
                            dict.getOrPut(reading) { mutableListOf() }
                                .add(DictEntry(surface, cost, leftGroup, rightGroup))
                            count++
                        } else if (parts.size >= 3) {
                            val reading = parts[0]
                            val surface = parts[1]
                            val cost = parts[2].toIntOrNull() ?: 10000
                            if (cost <= 15000) {
                                dict.getOrPut(reading) { mutableListOf() }
                                    .add(DictEntry(surface, cost))
                            }
                            count++
                        }
                    }
                    entryCount = count
                    Log.i("NacreDictionary", "Dictionary loaded: $count entries, ${dict.size} unique readings")
                }
            }
        } catch (e: Exception) {
            Log.e("NacreDictionary", "Failed to load dictionary", e)
        }

        // Sort entries by cost within each reading
        for (entries in dict.values) {
            entries.sortBy { it.cost }
        }

        sortedReadings = dict.keys.toTypedArray().also { it.sort() }
    }

    private fun loadSlangDictionary() {
        try {
            context.assets.open("dict/slang_words.tsv").use { stream ->
                BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
                    var count = 0
                    reader.forEachLine { line ->
                        if (line.isBlank() || line.startsWith('#')) return@forEachLine
                        val parts = line.split('\t')
                        if (parts.size >= 5) {
                            val reading = parts[0]
                            val surface = parts[1]
                            val leftGroup = parts[2].toIntOrNull() ?: 1
                            val rightGroup = parts[3].toIntOrNull() ?: 1
                            val cost = parts[4].toIntOrNull() ?: 5000
                            val entries = dict.getOrPut(reading) { mutableListOf() }
                            // Only add if not already present (Mozc takes priority for same surface)
                            if (entries.none { it.surface == surface }) {
                                entries.add(DictEntry(surface, cost, leftGroup, rightGroup))
                                count++
                            }
                        }
                    }
                    Log.i("NacreDictionary", "Slang dict loaded: $count new entries")
                }
            }
            // Re-sort affected entries
            for (entries in dict.values) {
                entries.sortBy { it.cost }
            }
            // Rebuild sorted readings
            sortedReadings = dict.keys.toTypedArray().also { it.sort() }
        } catch (e: Exception) {
            Log.i("NacreDictionary", "No slang dictionary found (optional)")
        }
    }

    // --- DictionaryProvider implementation ---

    override fun convert(kana: String): List<ConversionCandidate> {
        if (!loaded || kana.isEmpty()) return emptyList()

        val results = mutableListOf<ConversionCandidate>()
        val seen = mutableSetOf<String>()

        fun addUnique(candidates: List<ConversionCandidate>) {
            for (c in candidates) {
                if (seen.add(c.surface)) results.add(c)
            }
        }

        // 1. Viterbi multi-word conversion (best segmentation)
        addUnique(viterbiConvert(kana))

        // 2. Exact match (single-word candidates)
        addUnique(exactMatch(kana))

        // 3. Partial segmentation for diversity (with POS connection cost)
        if (results.size < 12 && kana.length >= 3) {
            for (splitAt in (kana.length - 1) downTo 2) {
                val head = kana.substring(0, splitAt)
                val tail = kana.substring(splitAt)
                val headEntries = dict[head]?.take(3) ?: continue
                val tailEntries = dict[tail]?.take(3)
                if (tailEntries != null) {
                    for (h in headEntries) {
                        for (t in tailEntries) {
                            val combined = h.surface + t.surface
                            if (seen.add(combined)) {
                                results.add(ConversionCandidate(
                                    surface = combined, reading = kana,
                                    cost = h.cost + t.cost + getConnectionCost(h.rightGroup, t.leftGroup),
                                ))
                            }
                        }
                        // Hiragana tail
                        if (tail.length <= 4 && h.surface != head) {
                            val combo = h.surface + tail
                            if (seen.add(combo)) {
                                results.add(ConversionCandidate(surface = combo, reading = kana, cost = h.cost + 3500))
                            }
                        }
                    }
                }
                if (results.size >= 15) break
            }
        }

        // 4. 3-way segmentation for longer inputs (e.g. きょうはいいてんきですね → 今日は+いい+天気ですね)
        if (results.size < 15 && kana.length >= 6) {
            outer@ for (s1 in 2..(kana.length - 4)) {
                val seg1 = kana.substring(0, s1)
                val e1 = dict[seg1]?.firstOrNull() ?: continue
                for (s2 in (s1 + 2)..(kana.length - 2)) {
                    val seg2 = kana.substring(s1, s2)
                    val seg3 = kana.substring(s2)
                    val e2 = dict[seg2]?.firstOrNull() ?: continue
                    val e3 = dict[seg3]?.firstOrNull() ?: continue
                    val combined = e1.surface + e2.surface + e3.surface
                    if (seen.add(combined)) {
                        val cost = e1.cost + e2.cost + e3.cost +
                            getConnectionCost(e1.rightGroup, e2.leftGroup) +
                            getConnectionCost(e2.rightGroup, e3.leftGroup)
                        results.add(ConversionCandidate(surface = combined, reading = kana, cost = cost))
                    }
                    if (results.size >= 18) break@outer
                }
            }
        }

        // 5. Katakana / Hiragana as-is
        val katakana = hiraganaToKatakana(kana)
        if (katakana != kana && seen.add(katakana)) {
            val maxCost = results.maxOfOrNull { it.cost } ?: 5000
            results.add(ConversionCandidate(surface = katakana, reading = kana, cost = maxCost + 500))
        }
        if (seen.add(kana)) {
            val maxCost = results.maxOfOrNull { it.cost } ?: 5000
            results.add(ConversionCandidate(surface = kana, reading = kana, cost = maxCost + 200))
        }

        // Sort: apply user boost + POS context + KenLM rescoring
        val boosted = results.map { c ->
            var cost = applyBoost(c.reading, c.surface, c.cost)
            cost = posContextCost(c.reading, c.surface, cost)
            c.copy(cost = cost)
        }.toMutableList()

        kenLmRescore(boosted)

        return boosted.sortedBy { it.cost }.take(20)
    }

    override fun predict(kana: String, romaji: String): List<ConversionCandidate> {
        if (!loaded || kana.isEmpty()) return emptyList()

        val results = mutableListOf<ConversionCandidate>()
        val seen = mutableSetOf<String>()

        fun addUnique(candidates: List<ConversionCandidate>) {
            for (c in candidates) {
                if (seen.add(c.surface)) results.add(c)
            }
        }

        // 0. Recent history matches (with prefix-length penalty)
        val historyMatches = recentHistory.filter {
            it.reading == kana || it.reading.startsWith(kana)
        }.map { h ->
            if (h.reading == kana) {
                h // Exact match — no penalty
            } else {
                // Prefix match: penalize by unmatched length to prevent stale history domination
                val unmatched = h.reading.length - kana.length
                h.copy(cost = h.cost + unmatched * 1500)
            }
        }.take(3)
        addUnique(historyMatches)

        // 1. Viterbi multi-word conversion
        addUnique(viterbiConvert(kana).take(8))

        // 2. Exact match
        addUnique(exactMatch(kana))

        // 3. Prefix match
        if (results.size < 15) {
            addUnique(prefixMatch(kana, limit = 15 - results.size))
        }

        // 4. Partial segmentation with hiragana tail preference
        if (results.size < 10 && kana.length >= 3) {
            for (splitAt in 2 until kana.length) {
                val head = kana.substring(0, splitAt)
                val tail = kana.substring(splitAt)
                val headEntries = dict[head]?.take(3) ?: continue
                val tailEntries = dict[tail]?.take(3)
                if (tailEntries != null) {
                    for (h in headEntries) {
                        for (t in tailEntries) {
                            val combined = h.surface + t.surface
                            if (seen.add(combined)) {
                                results.add(ConversionCandidate(
                                    surface = combined,
                                    reading = kana,
                                    cost = h.cost + t.cost + getConnectionCost(h.rightGroup, t.leftGroup),
                                ))
                            }
                            if (results.size >= 25) break
                        }
                        // Also add hiragana-tail variant: e.g. "スクショ" + "した"
                        if (tail.length <= 3 && h.surface != head) {
                            val hiraganaCombo = h.surface + tail
                            if (seen.add(hiraganaCombo)) {
                                results.add(ConversionCandidate(
                                    surface = hiraganaCombo,
                                    reading = kana,
                                    cost = h.cost + 4000,
                                ))
                            }
                        }
                        if (results.size >= 25) break
                    }
                }
                if (results.size >= 25) break
            }
        }

        // 5. English word candidates (hiragana reading match)
        if (results.size < 20) {
            addUnique(englishMatch(kana, limit = 5))
        }

        // 6. Romaji-based English candidates (e.g. "goo" → "Google")
        if (romaji.isNotEmpty() && romaji.length >= 2) {
            addUnique(romajiEnglishMatch(romaji, limit = 5))
        }

        // 7. Typo correction: swap adjacent kana, common misreadings
        if (results.size < 15 && kana.length >= 3) {
            addUnique(typoCorrection(kana, limit = 5))
        }

        // 8. Always ensure katakana and hiragana as-is candidates exist
        if (kana.length >= 2) {
            val katakana = hiraganaToKatakana(kana)
            if (katakana != kana && seen.add(katakana)) {
                results.add(ConversionCandidate(surface = katakana, reading = kana, cost = 5800))
            }
            if (seen.add(kana)) {
                results.add(ConversionCandidate(surface = kana, reading = kana, cost = 5500))
            }
        }

        // Final sort: apply user boost + POS context + KenLM to all candidates
        val boosted = results.map { c ->
            var cost = applyBoost(c.reading, c.surface, c.cost)
            cost = posContextCost(c.reading, c.surface, cost)
            c.copy(cost = cost)
        }.toMutableList()

        kenLmRescore(boosted)

        return boosted.sortedBy { it.cost }.take(20)
    }

    override fun recordSelection(candidate: ConversionCandidate) {
        val key = "${candidate.reading}:${candidate.surface}"
        userBoost[key] = (userBoost[key] ?: 0) + 1

        // Bigram
        if (lastCommittedSurface.isNotEmpty()) {
            val bigramKey = "$lastCommittedSurface→$key"
            bigramBoost[bigramKey] = (bigramBoost[bigramKey] ?: 0) + 1
        }
        // Trigram
        if (secondLastCommittedSurface.isNotEmpty() && lastCommittedSurface.isNotEmpty()) {
            val trigramKey = "$secondLastCommittedSurface→$lastCommittedSurface→$key"
            trigramBoost[trigramKey] = (trigramBoost[trigramKey] ?: 0) + 1
        }
        secondLastCommittedSurface = lastCommittedSurface
        lastCommittedSurface = candidate.surface

        // Update POS context from the selected candidate's dictionary entry
        val entries = dict[candidate.reading]
        val matchEntry = entries?.firstOrNull { it.surface == candidate.surface }
        lastRightGroup = matchEntry?.rightGroup ?: 1

        // Recent history
        recentHistory.removeAll { it.surface == candidate.surface && it.reading == candidate.reading }
        // Store with a low but non-zero cost so applyBoost context bonuses can still improve ranking
        val historyCost = minOf(candidate.cost, 3000).coerceAtLeast(500)
        recentHistory.addFirst(candidate.copy(cost = historyCost))
        while (recentHistory.size > maxHistory) recentHistory.removeLast()

        debouncedSave()
    }

    fun updateContext(surface: String) {
        secondLastCommittedSurface = lastCommittedSurface
        lastCommittedSurface = surface
        lastRightGroup = 0 // BOS/EOS for raw text
    }

    /**
     * Predict next word based on committed context (bigram/trigram history).
     * Returns candidates that frequently follow the last committed word(s).
     */
    fun predictNextWord(limit: Int = 8): List<ConversionCandidate> {
        if (lastCommittedSurface.isEmpty()) return emptyList()

        val results = mutableListOf<ConversionCandidate>()
        val seen = mutableSetOf<String>()

        // 1. Trigram matches (strongest signal): prev2→prev1→X
        if (secondLastCommittedSurface.isNotEmpty()) {
            val triPrefix = "$secondLastCommittedSurface→$lastCommittedSurface→"
            for ((key, count) in trigramBoost) {
                if (key.startsWith(triPrefix) && count > 0) {
                    val target = key.removePrefix(triPrefix)
                    val parts = target.split(':', limit = 2)
                    if (parts.size == 2) {
                        val surface = parts[1]
                        if (seen.add(surface)) {
                            results.add(ConversionCandidate(
                                surface = surface,
                                reading = parts[0],
                                cost = maxOf(0, 3000 - count * 1000),
                            ))
                        }
                    }
                }
            }
        }

        // 2. Bigram matches: prev1→X
        val biPrefix = "$lastCommittedSurface→"
        for ((key, count) in bigramBoost) {
            if (key.startsWith(biPrefix) && count > 0) {
                val target = key.removePrefix(biPrefix)
                val parts = target.split(':', limit = 2)
                if (parts.size == 2) {
                    val surface = parts[1]
                    if (seen.add(surface)) {
                        results.add(ConversionCandidate(
                            surface = surface,
                            reading = parts[0],
                            cost = maxOf(0, 4000 - count * 800),
                        ))
                    }
                }
            }
        }

        // 3. Recent history (fallback)
        if (results.size < limit) {
            for (h in recentHistory) {
                if (seen.add(h.surface) && h.surface != lastCommittedSurface) {
                    results.add(h.copy(cost = 5000))
                    if (results.size >= limit) break
                }
            }
        }

        return results.sortedBy { it.cost }.take(limit)
    }

    private fun debouncedSave() {
        val now = System.currentTimeMillis()
        if (now - lastSaveTime > 5000) {
            lastSaveTime = now
            saveUserBoost()
        } else {
            savePending = true
        }
    }

    fun flushPendingSave() {
        if (savePending) {
            saveUserBoost()
            savePending = false
        }
    }

    // --- Connection cost ---

    /**
     * Get the POS-based connection cost between two words.
     * Uses the grouped connection cost matrix from Mozc data.
     */
    private fun getConnectionCost(prevRightGroup: Int, currLeftGroup: Int): Int {
        if (connectionCost.isEmpty()) return DEFAULT_CONNECTION_COST
        val pg = prevRightGroup.coerceIn(0, numGroups - 1)
        val cg = currLeftGroup.coerceIn(0, numGroups - 1)
        val raw = connectionCost[pg][cg]
        // Scale: Mozc costs are ~3000-11000.
        // Use /4 for finer control: POS transitions guide but dictionary cost dominates.
        // With expanded dict (cost<10500), /4 prevents connection cost from overwhelming.
        return raw / 4
    }

    // --- Boost calculation ---

    private fun applyBoost(reading: String, surface: String, baseCost: Int): Int {
        val key = "$reading:$surface"
        val unigramCount = userBoost[key] ?: 0
        val bigramCount = if (lastCommittedSurface.isNotEmpty()) {
            bigramBoost["$lastCommittedSurface→$key"] ?: 0
        } else 0
        val trigramCount = if (secondLastCommittedSurface.isNotEmpty() && lastCommittedSurface.isNotEmpty()) {
            trigramBoost["$secondLastCommittedSurface→$lastCommittedSurface→$key"] ?: 0
        } else 0

        val unigramBoostVal = minOf(unigramCount, 5) * 2000   // cap at 5 to prevent over-learning
        val bigramBoostVal = minOf(bigramCount, 4) * 3000      // cap at 4
        val trigramBoostVal = minOf(trigramCount, 3) * 4000    // cap at 3
        // Floor at 100 instead of 0 to preserve relative ordering among boosted candidates
        return maxOf(100, baseCost - unigramBoostVal - bigramBoostVal - trigramBoostVal)
    }

    /**
     * POS-aware cost adjustment for a candidate given the current committed context.
     * Rewards natural POS transitions, penalizes unnatural ones.
     */
    private fun posContextCost(reading: String, surface: String, baseCost: Int): Int {
        if (lastRightGroup == 0) return baseCost
        val entries = dict[reading]
        val entry = entries?.firstOrNull { it.surface == surface } ?: return baseCost
        val connCost = getConnectionCost(lastRightGroup, entry.leftGroup)
        // Neutral point ~1875 (average connection cost / 4)
        // Strong natural transition (e.g. 名詞→助詞): connCost ≈ 500 → bonus -687
        // Unnatural (e.g. 助詞→助詞): connCost ≈ 2750 → penalty +437
        return baseCost + (connCost - 1875) / 2
    }

    /**
     * Rescore candidates using KenLM 5-gram language model.
     * Candidates with segments get LM score blended into their cost.
     * Candidates without segments are scored using surface as a single word.
     */
    private fun kenLmRescore(candidates: MutableList<ConversionCandidate>) {
        val scorer = kenLmScorer ?: return
        if (!scorer.isReady() || candidates.isEmpty()) return
        // Skip LM scoring for short inputs (not enough context) or tiny candidate lists
        if (candidates.size <= 2) return
        // Only score top candidates for performance
        val maxScore = 20.coerceAtMost(candidates.size)

        val precedingContext = if (lastCommittedSurface.isNotEmpty()) {
            val prev2 = if (secondLastCommittedSurface.isNotEmpty()) "$secondLastCommittedSurface " else ""
            "$prev2$lastCommittedSurface"
        } else ""

        // Build segment lists for top candidates only
        val segmentLists = candidates.take(maxScore).map { c ->
            c.segments.ifEmpty { listOf(c.surface) }
        }

        val scores = scorer.scoreBatch(segmentLists, precedingContext)

        // Blend LM scores with existing costs
        for (i in 0 until maxScore) {
            if (i >= scores.size) break
            // scores[i] is log10 prob (negative; higher = better)
            // Convert to cost adjustment: more likely sentences get lower cost
            val lmBonus = (scores[i] * -KENLM_WEIGHT).toInt()
            // Normalize by word count to avoid penalizing longer candidates
            val wordCount = segmentLists[i].size.coerceAtLeast(1)
            val normalizedBonus = lmBonus / wordCount
            candidates[i] = candidates[i].copy(cost = candidates[i].cost + normalizedBonus)
        }
    }

    // --- Internal ---

    private fun exactMatch(kana: String): List<ConversionCandidate> {
        val entries = dict[kana] ?: return emptyList()
        return entries
            .map { entry ->
                var cost = applyBoost(kana, entry.surface, entry.cost)
                cost = posContextCost(kana, entry.surface, cost)
                // Suppress function words (助詞・助動詞) appearing as sole candidates for 2+ char input
                if (kana.length >= 2 && entry.leftGroup in 4..5 && entry.surface.length <= 1) {
                    cost += 2000
                }
                ConversionCandidate(
                    surface = entry.surface,
                    reading = kana,
                    cost = cost,
                )
            }
            .sortedBy { it.cost }
            .take(20)
    }

    private fun prefixMatch(kana: String, limit: Int): List<ConversionCandidate> {
        if (limit <= 0) return emptyList()

        val results = mutableListOf<ConversionCandidate>()

        var idx = sortedReadings.binarySearch(kana).let {
            if (it >= 0) it else -(it + 1)
        }

        while (idx < sortedReadings.size && sortedReadings[idx].startsWith(kana)) {
            val reading = sortedReadings[idx]
            if (reading != kana) {
                val entries = dict[reading]
                if (entries == null) { idx++; continue }
                for (entry in entries.take(3)) {
                    var cost = applyBoost(reading, entry.surface, entry.cost)
                    cost = posContextCost(reading, entry.surface, cost)
                    // Penalty for how much longer the reading is than input
                    cost += (reading.length - kana.length) * 300
                    results.add(
                        ConversionCandidate(
                            surface = entry.surface,
                            reading = reading,
                            cost = cost,
                        ),
                    )
                    if (results.size >= limit) {
                        return results.sortedBy { it.cost }
                    }
                }
            }
            idx++
        }

        return results.sortedBy { it.cost }
    }

    // --- English word matching ---

    private fun loadEnglishDict() {
        try {
            context.assets.open("dict/english_words.tsv").use { stream ->
                BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
                    reader.forEachLine { line ->
                        if (line.isBlank() || line.startsWith('#')) return@forEachLine
                        val parts = line.split('\t')
                        if (parts.size >= 2) {
                            val romaji = parts[0]
                            val english = parts[1]
                            val cost = if (parts.size >= 3) parts[2].toIntOrNull() ?: 8000 else 8000
                            englishDict.getOrPut(romaji) { mutableListOf() }
                                .add(DictEntry(english, cost))
                        }
                    }
                }
            }
            Log.i("NacreDictionary", "English dict loaded: ${englishDict.size} entries")
        } catch (e: Exception) {
            Log.i("NacreDictionary", "No English dictionary found (optional)")
        }
    }

    private fun loadEnglishFullDict() {
        try {
            context.assets.open("dict/english_full.tsv").use { stream ->
                BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
                    reader.forEachLine { line ->
                        if (line.isBlank() || line.startsWith('#')) return@forEachLine
                        val parts = line.split('\t')
                        if (parts.size >= 3) {
                            val key = parts[0]       // lowercase key
                            val surface = parts[1]    // display form (may have mixed case)
                            val cost = parts[2].toIntOrNull() ?: 8000
                            englishFullDict.getOrPut(key) { mutableListOf() }
                                .add(DictEntry(surface, cost))
                        }
                    }
                }
            }
            englishSortedKeys = englishFullDict.keys.toTypedArray().also { it.sort() }
            Log.i("NacreDictionary", "English full dict loaded: ${englishFullDict.size} keys, ${englishSortedKeys.size} sorted")
        } catch (e: Exception) {
            Log.i("NacreDictionary", "No english_full.tsv found (optional)")
        }
    }

    /**
     * Predict English words from prefix input.
     * Returns autocomplete candidates sorted by cost (frequency).
     */
    override fun predictEnglish(prefix: String, limit: Int): List<ConversionCandidate> {
        if (prefix.length < 1) return emptyList()
        val prefixLower = prefix.lowercase()
        val results = mutableListOf<ConversionCandidate>()

        // 1. Exact match
        val exact = englishFullDict[prefixLower]
        if (exact != null) {
            for (entry in exact) {
                results.add(ConversionCandidate(
                    surface = entry.surface,
                    reading = prefix,
                    cost = entry.cost - 2000,  // Strong bonus for exact match
                ))
            }
        }

        // 2. Prefix match via binary search on sorted keys
        val startIdx = englishSortedKeys.binarySearchInsertionPoint(prefixLower)
        var count = 0
        for (i in startIdx until englishSortedKeys.size) {
            val key = englishSortedKeys[i]
            if (!key.startsWith(prefixLower)) break
            if (key == prefixLower) continue  // Already handled as exact
            val entries = englishFullDict[key] ?: continue
            for (entry in entries.take(2)) {
                val lengthPenalty = (key.length - prefixLower.length) * 100
                results.add(ConversionCandidate(
                    surface = entry.surface,
                    reading = prefix,
                    cost = entry.cost + lengthPenalty,
                ))
            }
            count++
            if (count >= limit * 2) break
        }

        // 3. Spell correction (edit distance 1) for inputs >= 3 chars
        if (results.size < 5 && prefixLower.length >= 3) {
            val corrections = spellCorrect(prefixLower, limit = 5)
            for (c in corrections) {
                if (results.none { it.surface.equals(c.surface, ignoreCase = true) }) {
                    results.add(c)
                }
            }
        }

        // 4. Apply English bigram boost
        if (lastCommittedEnglish.isNotEmpty()) {
            for (i in results.indices) {
                val bigramKey = "${lastCommittedEnglish.lowercase()}→${results[i].surface.lowercase()}"
                val boost = englishBigramBoost[bigramKey] ?: 0
                if (boost > 0) {
                    results[i] = results[i].copy(cost = results[i].cost - minOf(boost * 800, 3000))
                }
            }
        }

        return results.sortedBy { it.cost }.take(limit)
    }

    /**
     * Record English word selection for bigram learning.
     */
    override fun recordEnglishSelection(word: String) {
        if (lastCommittedEnglish.isNotEmpty()) {
            val key = "${lastCommittedEnglish.lowercase()}→${word.lowercase()}"
            val count = englishBigramBoost.merge(key, 1) { old, _ -> minOf(old + 1, 5) } ?: 1
        }
        lastCommittedEnglish = word
    }

    /**
     * Spell correction via edit distance 1 (deletion, substitution, insertion, transposition).
     */
    private fun spellCorrect(input: String, limit: Int): List<ConversionCandidate> {
        val results = mutableListOf<ConversionCandidate>()
        val seen = mutableSetOf<String>()

        // Deletions: remove one char
        for (i in input.indices) {
            val candidate = input.removeRange(i, i + 1)
            if (candidate.length >= 2 && seen.add(candidate)) {
                val entries = englishFullDict[candidate]
                if (entries != null) {
                    for (e in entries.take(1)) {
                        results.add(ConversionCandidate(e.surface, input, e.cost + 3000))
                    }
                }
            }
        }

        // Substitutions: replace one char
        for (i in input.indices) {
            for (c in 'a'..'z') {
                if (c == input[i]) continue
                val candidate = input.replaceRange(i, i + 1, c.toString())
                if (seen.add(candidate)) {
                    val entries = englishFullDict[candidate]
                    if (entries != null) {
                        for (e in entries.take(1)) {
                            results.add(ConversionCandidate(e.surface, input, e.cost + 3000))
                        }
                    }
                }
            }
            if (results.size >= limit) break
        }

        // Transpositions: swap adjacent chars
        for (i in 0 until input.length - 1) {
            val candidate = buildString {
                append(input, 0, i)
                append(input[i + 1])
                append(input[i])
                if (i + 2 < input.length) append(input, i + 2, input.length)
            }
            if (seen.add(candidate)) {
                val entries = englishFullDict[candidate]
                if (entries != null) {
                    for (e in entries.take(1)) {
                        results.add(ConversionCandidate(e.surface, input, e.cost + 2500))
                    }
                }
            }
        }

        return results.sortedBy { it.cost }.take(limit)
    }

    /** Binary search for insertion point in sorted array. */
    private fun Array<String>.binarySearchInsertionPoint(prefix: String): Int {
        var lo = 0
        var hi = size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (this[mid] < prefix) lo = mid + 1 else hi = mid
        }
        return lo
    }

    private fun englishMatch(kana: String, limit: Int): List<ConversionCandidate> {
        if (limit <= 0) return emptyList()
        val results = mutableListOf<ConversionCandidate>()

        val exact = englishDict[kana]
        if (exact != null) {
            for (entry in exact.take(limit)) {
                results.add(ConversionCandidate(
                    surface = entry.surface,
                    reading = kana,
                    cost = entry.cost,
                ))
            }
        }

        if (results.size < limit) {
            for ((key, entries) in englishDict) {
                if (key.startsWith(kana) && key != kana) {
                    for (entry in entries.take(1)) {
                        results.add(ConversionCandidate(
                            surface = entry.surface,
                            reading = key,
                            cost = entry.cost + 1000,
                        ))
                        if (results.size >= limit) return results.sortedBy { it.cost }
                    }
                }
            }
        }

        return results.sortedBy { it.cost }
    }

    // Romaji→English reverse index: maps lowercase English word prefix to entries
    // Built from englishDict: e.g. "google" → DictEntry("Google", 4000)
    private val romajiEnglishIndex = HashMap<String, MutableList<DictEntry>>(500)

    private fun buildRomajiEnglishIndex() {
        for ((_, entries) in englishDict) {
            for (entry in entries) {
                val key = entry.surface.lowercase()
                romajiEnglishIndex.getOrPut(key) { mutableListOf() }.add(entry)
            }
        }
        Log.i("NacreDictionary", "Romaji English index: ${romajiEnglishIndex.size} entries")
    }

    /**
     * Match raw romaji input against English words.
     * e.g. "goo" matches "Google", "good"; "lin" matches "LINE", "Linux"
     */
    private fun romajiEnglishMatch(romaji: String, limit: Int): List<ConversionCandidate> {
        if (limit <= 0 || romaji.length < 2) return emptyList()
        val romajiLower = romaji.lowercase()
        val results = mutableListOf<ConversionCandidate>()

        for ((key, entries) in romajiEnglishIndex) {
            if (key.startsWith(romajiLower)) {
                for (entry in entries.take(1)) {
                    results.add(ConversionCandidate(
                        surface = entry.surface,
                        reading = romaji,
                        cost = entry.cost + if (key == romajiLower) 0 else 500,
                    ))
                    if (results.size >= limit) return results.sortedBy { it.cost }
                }
            }
        }

        return results.sortedBy { it.cost }
    }

    // --- Typo correction ---

    /**
     * Generate typo-corrected candidates by:
     * 1. Swapping adjacent kana characters (transposition)
     * 2. Common misreading patterns (ふいんき→ふんいき etc.)
     */
    private fun typoCorrection(kana: String, limit: Int): List<ConversionCandidate> {
        if (limit <= 0 || kana.length < 3) return emptyList()
        val results = mutableListOf<ConversionCandidate>()
        val seen = mutableSetOf<String>()

        // 1. Adjacent character transposition
        for (i in 0 until kana.length - 1) {
            val swapped = buildString {
                append(kana, 0, i)
                append(kana[i + 1])
                append(kana[i])
                append(kana, i + 2, kana.length)
            }
            if (swapped != kana && seen.add(swapped)) {
                val entries = dict[swapped]
                if (entries != null) {
                    for (entry in entries.take(2)) {
                        results.add(ConversionCandidate(
                            surface = entry.surface,
                            reading = swapped,
                            cost = entry.cost + 2000, // Penalty for typo
                        ))
                        if (results.size >= limit) return results.sortedBy { it.cost }
                    }
                }
                // Also try Viterbi on the swapped reading
                val viterbi = viterbiConvert(swapped)
                for (v in viterbi.take(1)) {
                    if (seen.add(v.surface)) {
                        results.add(v.copy(cost = v.cost + 2000))
                        if (results.size >= limit) return results.sortedBy { it.cost }
                    }
                }
            }
        }

        // 2. Common misreading patterns
        val corrections = mapOf(
            "ふいんき" to "ふんいき",  // 雰囲気
            "たいく" to "たいいく",    // 体育
            "がいしゅつ" to "きしゅつ", // 既出
            "ぜいいん" to "ぜんいん",  // 全員
            "いちよう" to "いちおう",  // 一応
            "そうゆう" to "そういう",  // そういう
            "こんにちわ" to "こんにちは",
            "こんばんわ" to "こんばんは",
            "づつ" to "ずつ",          // 少しずつ
            "ちぢむ" to "ちぢむ",      // 縮む (correct reading)
            "おもむろに" to "おもむろに", // 徐に
            "しゅみれーしょん" to "しみゅれーしょん", // シミュレーション
            "あたらしい" to "あたらしい",
            "うるおぼえ" to "うろおぼえ", // うろ覚え
            "さいさき" to "さいさき",    // 幸先
            "やくばらい" to "やくばらい", // 厄払い
            "かんぺき" to "かんぺき",    // 完璧
            "ていきゅう" to "ていきゅう", // 定休
            "しんちょく" to "しんちょく", // 進捗
            "ぜったい" to "ぜったい",    // 絶対
            "けいさい" to "けいさい",    // 掲載
            "ひつじゅひん" to "ひつじゅひん", // 必需品
            "ずかい" to "ずかい",        // 図解
            "いぜん" to "いぜん",        // 以前
            "えんかつ" to "えんかつ",    // 円滑
        )
        val corrected = corrections[kana]
        if (corrected != null && corrected != kana) {
            val entries = dict[corrected]
            if (entries != null) {
                for (entry in entries.take(3)) {
                    if (seen.add(entry.surface)) {
                        results.add(ConversionCandidate(
                            surface = entry.surface,
                            reading = corrected,
                            cost = entry.cost + 500, // Small penalty — likely what user meant
                        ))
                    }
                }
            }
            val viterbi = viterbiConvert(corrected)
            for (v in viterbi.take(2)) {
                if (seen.add(v.surface)) {
                    results.add(v.copy(cost = v.cost + 500))
                }
            }
        }

        // 3. Single character deletion (one extra char typed)
        if (kana.length >= 4) {
            for (i in kana.indices) {
                val deleted = kana.removeRange(i, i + 1)
                if (seen.add(deleted)) {
                    val entries = dict[deleted]
                    if (entries != null) {
                        for (entry in entries.take(1)) {
                            results.add(ConversionCandidate(
                                surface = entry.surface,
                                reading = deleted,
                                cost = entry.cost + 3000,
                            ))
                            if (results.size >= limit) return results.sortedBy { it.cost }
                        }
                    }
                }
            }
        }

        return results.sortedBy { it.cost }.take(limit)
    }

    // --- Viterbi segmentation with POS connection costs ---

    companion object {
        private const val DEFAULT_CONNECTION_COST = 1500
        // KenLM weight: how much 1 log10 point affects cost (2000 ≈ bigram boost equivalent)
        private const val KENLM_WEIGHT = 2000f
    }

    private fun lengthBonus(segLen: Int): Int {
        return when {
            segLen >= 8 -> -1800  // 長い複合語・固有名詞を強く優遇
            segLen >= 7 -> -1500
            segLen >= 6 -> -1200
            segLen >= 5 -> -800
            segLen >= 4 -> -500
            segLen >= 3 -> -200
            segLen == 2 -> 0
            else -> 500  // 1文字セグメントをより強くペナルティ
        }
    }

    private fun viterbiConvert(kana: String): List<ConversionCandidate> {
        val n = kana.length
        if (n == 0) return emptyList()

        data class Node(
            val cost: Int,
            val backPos: Int,
            val surface: String,
            val reading: String,
            val segCount: Int,
            val rightGroup: Int,
            // Backpointer to previous node for path reconstruction (avoids GC pressure from list copies)
            val prevNode: Node?,
        )

        // Reconstruct segments list from backpointer chain
        fun reconstructSegments(node: Node): List<String> {
            val segments = mutableListOf<String>()
            var cur: Node? = node
            while (cur != null && cur.surface.isNotEmpty()) {
                segments.add(cur.surface)
                cur = cur.prevNode
            }
            segments.reverse()
            return segments
        }

        // Reconstruct full surface string from backpointer chain
        fun reconstructPath(node: Node): String = reconstructSegments(node).joinToString("")

        // N-best Viterbi: keep top-K paths at each position
        val K = 8
        val dp = Array(n + 1) { mutableListOf<Node>() }
        // Use lastRightGroup from previous committed word for better BOS context
        dp[0].add(Node(cost = 0, backPos = -1, surface = "", reading = "", segCount = 0, rightGroup = lastRightGroup, prevNode = null))

        for (endPos in 1..n) {
            val allCandidates = mutableListOf<Node>()
            val maxSegLen = minOf(endPos, 16)

            for (segLen in 1..maxSegLen) {
                val startPos = endPos - segLen
                val segment = kana.substring(startPos, endPos)

                val entries = dict[segment]
                if (entries != null && entries.isNotEmpty()) {
                    for (prevNode in dp[startPos]) {
                        val prevRG = prevNode.rightGroup
                        val isAfterContentWord = prevRG in 1..3
                        val isAfterFunctionWord = prevRG in 4..5

                        for (entry in entries.take(8)) {
                            var cost = applyBoost(segment, entry.surface, entry.cost)
                            val connCost = if (startPos == 0 && prevRG == 0) {
                                // BOS: strongly favor 名詞(1), 副詞(6), 接続詞(8), 感動詞(9)
                                // Penalize starting with 助詞(5), 助動詞(4)
                                getConnectionCost(0, entry.leftGroup) + when (entry.leftGroup) {
                                    1, 6, 8, 9 -> -500  // Natural sentence starters
                                    4, 5 -> 1000          // Unnatural to start with particle
                                    else -> 0
                                }
                            } else {
                                getConnectionCost(prevRG, entry.leftGroup)
                            }
                            cost += connCost

                            // Hiragana surface bonus: prefer leaving function words as kana
                            if (entry.surface == segment) {
                                // 助詞 after 名詞/動詞/形容詞 is extremely natural (e.g. 食べ+た, 学校+の)
                                if (entry.leftGroup == 5 && segLen <= 2 && isAfterContentWord) cost -= 4000
                                // 助動詞 after content word (e.g. 行き+ます)
                                if (entry.leftGroup == 4 && segLen <= 3 && isAfterContentWord) cost -= 3500
                                // Generic hiragana after content word
                                if (segLen <= 3 && isAfterContentWord && entry.leftGroup !in 4..5) cost -= 2500
                                // Function word after function word (e.g. で+は, に+も)
                                if (segLen <= 2 && isAfterFunctionWord && entry.leftGroup in 4..5) cost -= 2000
                                // General short hiragana after function word
                                if (segLen <= 2 && isAfterFunctionWord && entry.leftGroup !in 4..5) cost -= 1000
                            }

                            val lBonus = lengthBonus(segLen)
                            val totalCost = prevNode.cost + cost + lBonus

                            allCandidates.add(Node(
                                cost = totalCost,
                                backPos = startPos,
                                surface = entry.surface,
                                reading = segment,
                                segCount = prevNode.segCount + 1,
                                rightGroup = entry.rightGroup,
                                prevNode = prevNode,
                            ))
                        }
                    }
                } else if (segLen == 1) {
                    for (prevNode in dp[startPos]) {
                        val connCost = if (startPos == 0) 0 else getConnectionCost(prevNode.rightGroup, 13)
                        val totalCost = prevNode.cost + 15000 + connCost
                        allCandidates.add(Node(
                            cost = totalCost,
                            backPos = startPos,
                            surface = segment,
                            reading = segment,
                            segCount = prevNode.segCount + 1,
                            rightGroup = 13,
                            prevNode = prevNode,
                        ))
                    }
                }
            }

            // Keep top-K, ensuring path diversity
            val sorted = allCandidates.sortedBy { it.cost }
            val kept = mutableListOf<Node>()
            val seenPaths = mutableSetOf<String>()
            for (node in sorted) {
                val pathKey = reconstructPath(node)
                if (seenPaths.add(pathKey)) {
                    kept.add(node)
                }
                if (kept.size >= K * 2) break
            }
            dp[endPos] = kept.toMutableList()
        }

        // Collect results from all N-best paths at dp[n], applying EOS cost
        val results = mutableListOf<ConversionCandidate>()
        val seen = mutableSetOf<String>()

        for (node in dp[n]) {
            val segments = reconstructSegments(node)
            val combined = segments.joinToString("")
            if (combined.isNotEmpty() && seen.add(combined)) {
                // EOS transition cost: penalize unnatural sentence-ending POS
                val eosCost = getConnectionCost(node.rightGroup, 0)
                // Segment count penalty: discourage over-segmentation
                val segPenalty = if (node.segCount >= 5) (node.segCount - 4) * 500 else 0
                val finalCost = node.cost + eosCost / 2 + segPenalty
                results.add(ConversionCandidate(surface = combined, reading = kana, cost = finalCost, segments = segments))
            }
            if (results.size >= 10) break
        }

        // Also add exact matches as alternatives
        val exactMatches = exactMatch(kana)

        if (results.isEmpty()) return exactMatches

        for (em in exactMatches) {
            if (seen.add(em.surface)) results.add(em)
        }

        // Always include katakana and hiragana as-is
        val katakana = hiraganaToKatakana(kana)
        if (katakana != kana && seen.add(katakana)) {
            val maxCost = results.maxOfOrNull { it.cost } ?: 5000
            results.add(ConversionCandidate(surface = katakana, reading = kana, cost = maxCost + 500))
        }
        if (seen.add(kana)) {
            val maxCost = results.maxOfOrNull { it.cost } ?: 5000
            results.add(ConversionCandidate(surface = kana, reading = kana, cost = maxCost + 200))
        }

        return results.take(20)
    }

    /** Convert hiragana to katakana */
    private fun hiraganaToKatakana(hiragana: String): String {
        val sb = StringBuilder(hiragana.length)
        for (ch in hiragana) {
            sb.append(if (ch in '\u3041'..'\u3096') (ch + 0x60) else ch)
        }
        return sb.toString()
    }

    // --- User learning persistence ---

    private fun loadUserBoost() {
        try {
            val prefs = context.getSharedPreferences("nacre_user_dict", Context.MODE_PRIVATE)

            val data = prefs.getString("boost", null)
            if (data != null) {
                for (line in data.split('\n')) {
                    val parts = line.split('\t')
                    if (parts.size == 2) {
                        userBoost[parts[0]] = parts[1].toIntOrNull() ?: 0
                    }
                }
            }

            val bigramData = prefs.getString("bigram", null)
            if (bigramData != null) {
                for (line in bigramData.split('\n')) {
                    val parts = line.split('\t')
                    if (parts.size == 2) {
                        bigramBoost[parts[0]] = parts[1].toIntOrNull() ?: 0
                    }
                }
            }

            val trigramData = prefs.getString("trigram", null)
            if (trigramData != null) {
                for (line in trigramData.split('\n')) {
                    val parts = line.split('\t')
                    if (parts.size == 2) {
                        trigramBoost[parts[0]] = parts[1].toIntOrNull() ?: 0
                    }
                }
            }

            val historyData = prefs.getString("history", null)
            if (historyData != null) {
                for (line in historyData.split('\n')) {
                    if (line.isBlank()) continue
                    val parts = line.split('\t')
                    if (parts.size >= 2) {
                        recentHistory.add(ConversionCandidate(
                            surface = parts[0],
                            reading = parts[1],
                            cost = 0,
                        ))
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("NacreDictionary", "Failed to load user boost", e)
        }
    }

    private fun saveUserBoost() {
        try {
            val prefs = context.getSharedPreferences("nacre_user_dict", Context.MODE_PRIVATE)

            val data = userBoost.entries
                .sortedByDescending { it.value }
                .take(5000)
                .joinToString("\n") { "${it.key}\t${it.value}" }

            val bigramData = bigramBoost.entries
                .sortedByDescending { it.value }
                .take(3000)
                .joinToString("\n") { "${it.key}\t${it.value}" }

            val trigramData = trigramBoost.entries
                .sortedByDescending { it.value }
                .take(2000)
                .joinToString("\n") { "${it.key}\t${it.value}" }

            val historyData = recentHistory
                .take(maxHistory)
                .joinToString("\n") { "${it.surface}\t${it.reading}" }

            prefs.edit()
                .putString("boost", data)
                .putString("bigram", bigramData)
                .putString("trigram", trigramData)
                .putString("history", historyData)
                .apply()
        } catch (e: Exception) {
            Log.w("NacreDictionary", "Failed to save user boost", e)
        }
    }
}
