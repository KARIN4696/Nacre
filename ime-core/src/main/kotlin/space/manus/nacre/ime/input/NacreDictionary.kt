package space.manus.nacre.ime.input

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream
import space.manus.nacre.ai.KenLmScorer

/**
 * Nacre Japanese Dictionary with POS-aware Viterbi conversion.
 *
 * Uses Mozc OSS dictionary with full 2670 POS IDs and the original
 * 2670×2670 connection cost matrix for maximum conversion accuracy.
 *
 * Dictionary format: reading\tsurface\tleft_id\tright_id\tcost
 * Connection matrix: binary 2670×2670 int16 (connection.bin)
 *
 * Mozc POS ID ranges:
 *   0=BOS/EOS, 2..11=フィラー, 12..28=副詞, 29..267=助動詞,
 *   268..433=助詞, 434..1840=動詞, 1841..2193=名詞,
 *   2194..2588=形容詞, 2589..2590=感動詞, 2591..2593=接続詞,
 *   2594..2640=接頭詞, 2641..2656=記号, 2657..2669=連体詞
 */
class NacreDictionary(private val context: Context) : DictionaryProvider {

    // reading → list of entries with POS info (initial capacity smaller to avoid OOM on allocation)
    private val dict = HashMap<String, MutableList<DictEntry>>(400000)

    // Sorted readings for prefix search
    private var sortedReadings: Array<String> = emptyArray()

    // Full Mozc connection cost matrix as flat ShortArray [right_id * numIds + left_id]
    private var connectionCostFlat: ShortArray = ShortArray(0)
    private var numIds = 0

    // User learning: boost for selected candidates (thread-safe)
    private val userBoost = ConcurrentHashMap<String, Int>(1000)

    // Bigram learning: "prevSurface→reading:surface" → count
    private val bigramBoost = ConcurrentHashMap<String, Int>(500)

    // Trigram learning: "prev2→prev1→reading:surface" → count
    private val trigramBoost = ConcurrentHashMap<String, Int>(300)

    // Recent history: ordered list of recently committed candidates (newest first)
    private val recentHistory = java.util.LinkedList<ConversionCandidate>()
    private val maxHistory = 200

    // Static bigram data: "prevSurface→nextReading:nextSurface" → boost value
    private val staticBigrams = HashMap<String, Int>(2000)

    // English word dictionary (hiragana reading → English words)
    private val englishDict = HashMap<String, MutableList<DictEntry>>(5000)

    // Full English dictionary: lowercase key → list of DictEntry (surface may have mixed case)
    private val englishFullDict = HashMap<String, MutableList<DictEntry>>(25000)
    // Sorted keys for binary-search prefix matching
    private var englishSortedKeys: Array<String> = emptyArray()
    // English word learning: "prevWord→word" → count (bigram boost)
    private val englishBigramBoost = ConcurrentHashMap<String, Int>(200)
    private var lastCommittedEnglish: String = ""

    // N-gram context: last 4 committed surfaces (for KenLM 5-gram)
    private val committedContext = ArrayDeque<String>(4)

    // Last committed right POS group (for connection cost to next word)
    private var lastRightGroup: Int = 0  // BOS/EOS

    // Convenience accessors for backward compat
    private val lastCommittedSurface: String get() = committedContext.firstOrNull() ?: ""
    private val secondLastCommittedSurface: String get() = committedContext.getOrNull(1) ?: ""

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
        loadSupplementaryDict("dict/emoji_kaomoji.tsv", "emoji/kaomoji")
        loadSupplementaryDict("dict/symbols.tsv", "symbols")

        // Sort all entries and build index ONCE after all dicts loaded (avoids OOM from repeated sorts)
        for (entries in dict.values) {
            entries.sortBy { it.cost }
        }
        sortedReadings = dict.keys.toTypedArray().also { it.sort() }
        Log.i("NacreDictionary", "Index built: ${dict.size} readings, ${sortedReadings.size} sorted")

        loadEnglishDict()
        buildRomajiEnglishIndex()
        loadEnglishFullDict()
        loadStaticBigrams()
        loadUserBoost()

        loaded = true
    }

    private fun loadConnectionMatrix() {
        try {
            context.assets.open("dict/connection.bin").use { stream ->
                val dis = java.io.DataInputStream(java.io.BufferedInputStream(stream, 65536))
                // First 4 bytes: uint32 num_ids (little-endian)
                val b = ByteArray(4)
                dis.readFully(b)
                numIds = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).int
                val total = numIds * numIds
                connectionCostFlat = ShortArray(total)
                // Read int16 values in 8KB chunks to avoid 14MB byte[] allocation
                val chunkBytes = ByteArray(8192)
                var idx = 0
                while (idx < total) {
                    val remaining = (total - idx) * 2
                    val toRead = minOf(chunkBytes.size, remaining)
                    dis.readFully(chunkBytes, 0, toRead)
                    val chunk = ByteBuffer.wrap(chunkBytes, 0, toRead).order(ByteOrder.LITTLE_ENDIAN)
                    val count = toRead / 2
                    for (j in 0 until count) {
                        connectionCostFlat[idx++] = chunk.short
                    }
                }
                Log.i("NacreDictionary", "Connection matrix loaded: ${numIds}x${numIds} (${total * 2 / 1024}KB)")
            }
        } catch (e: Exception) {
            Log.e("NacreDictionary", "Failed to load binary connection matrix, trying TSV fallback", e)
            loadConnectionMatrixTsvFallback()
        }
    }

    private fun loadConnectionMatrixTsvFallback() {
        try {
            val rows = mutableListOf<IntArray>()
            var n = 14
            context.assets.open("dict/connection_group.tsv").use { stream ->
                BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
                    reader.forEachLine { line ->
                        if (line.isBlank() || line.startsWith('#')) return@forEachLine
                        val trimmed = line.trim()
                        if (rows.isEmpty() && trimmed.toIntOrNull() != null) {
                            n = trimmed.toInt()
                            return@forEachLine
                        }
                        val values = trimmed.split('\t').map { it.toIntOrNull() ?: 5000 }
                        rows.add(values.toIntArray())
                    }
                }
            }
            numIds = n
            connectionCostFlat = ShortArray(n * n)
            for (r in 0 until minOf(n, rows.size)) {
                for (c in 0 until minOf(n, rows[r].size)) {
                    connectionCostFlat[r * n + c] = rows[r][c].coerceIn(-32768, 32767).toShort()
                }
            }
            Log.i("NacreDictionary", "Connection matrix (TSV fallback): ${n}x${n}")
        } catch (e2: Exception) {
            Log.e("NacreDictionary", "TSV fallback also failed", e2)
            numIds = 0
            connectionCostFlat = ShortArray(0)
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

        // Sorting deferred to load() after all dicts are loaded
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
            // Sorting deferred to load()
        } catch (e: Exception) {
            Log.i("NacreDictionary", "No slang dictionary found (optional)")
        }
    }

    /**
     * Load a supplementary TSV dictionary (emoji, symbols, etc).
     * Format: reading\tsurface\tleft_id\tright_id\tcost
     */
    private fun loadSupplementaryDict(assetPath: String, label: String) {
        try {
            context.assets.open(assetPath).use { stream ->
                BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
                    var count = 0
                    reader.forEachLine { line ->
                        if (line.isBlank() || line.startsWith('#')) return@forEachLine
                        val parts = line.split('\t')
                        if (parts.size >= 5) {
                            val reading = parts[0]
                            val surface = parts[1]
                            val leftGroup = parts[2].toIntOrNull() ?: 2641
                            val rightGroup = parts[3].toIntOrNull() ?: 2641
                            val cost = parts[4].toIntOrNull() ?: 5500
                            val entries = dict.getOrPut(reading) { mutableListOf() }
                            if (entries.none { it.surface == surface }) {
                                entries.add(DictEntry(surface, cost, leftGroup, rightGroup))
                                count++
                            }
                        }
                    }
                    Log.i("NacreDictionary", "$label dict loaded: $count entries")
                }
            }
        } catch (e: Exception) {
            Log.i("NacreDictionary", "No $label dictionary found (optional)")
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

        // 1. Exact match FIRST (single-word candidates — these should rank highest)
        addUnique(exactMatch(kana))

        // 2. Viterbi multi-word conversion (best segmentation)
        addUnique(viterbiConvert(kana))

        // 2.5 Kana variant conversion (を→お/うぉ, ぢ→じ, づ→ず etc.)
        // Google日本語入力方式: ローマ字テーブルは標準のまま、変換段階で読み替え候補を生成
        val kanaVariants = generateKanaVariants(kana)
        for (variant in kanaVariants) {
            val variantExact = exactMatch(variant)
            for (c in variantExact) {
                if (seen.add(c.surface)) results.add(c.copy(cost = c.cost + 300))
            }
            val variantViterbi = viterbiConvert(variant).take(5)
            for (c in variantViterbi) {
                if (seen.add(c.surface)) results.add(c.copy(cost = c.cost + 500))
            }
        }

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

        // 4. 3-way segmentation for medium inputs — skip for long text (Viterbi handles it)
        if (results.size < 15 && kana.length in 6..14) {
            outer@ for (s1 in 2..(kana.length - 4)) {
                val seg1 = kana.substring(0, s1)
                val entries1 = dict[seg1]?.take(3) ?: continue
                for (s2 in (s1 + 2)..(kana.length - 2)) {
                    val seg2 = kana.substring(s1, s2)
                    val seg3 = kana.substring(s2)
                    val entries2 = dict[seg2]?.take(3) ?: continue
                    val entries3 = dict[seg3]?.take(3) ?: continue
                    for (e1 in entries1) {
                        for (e2 in entries2) {
                            for (e3 in entries3) {
                                val combined = e1.surface + e2.surface + e3.surface
                                if (seen.add(combined)) {
                                    val cost = e1.cost + e2.cost + e3.cost +
                                        getConnectionCost(e1.rightGroup, e2.leftGroup) +
                                        getConnectionCost(e2.rightGroup, e3.leftGroup)
                                    results.add(ConversionCandidate(surface = combined, reading = kana, cost = cost))
                                }
                                if (results.size >= 25) break@outer
                            }
                        }
                    }
                }
            }
        }

        // 5. Katakana / Hiragana as-is (boost katakana for loanword-heavy readings)
        val katakana = hiraganaToKatakana(kana)
        if (katakana != kana && seen.add(katakana)) {
            val katakanaCost = estimateKatakanaCost(kana, results)
            results.add(ConversionCandidate(surface = katakana, reading = kana, cost = katakanaCost))
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

        return boosted.sortedBy { it.cost }.take(if (kana.length <= 3) 40 else 30)
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

        // 1. Exact match FIRST (single-word candidates rank highest)
        addUnique(exactMatch(kana))

        // 2. Viterbi multi-word conversion
        addUnique(viterbiConvert(kana).take(8))

        // 2.5 Kana variant conversion (を→お/うぉ, ぢ→じ etc.)
        val kanaVariants = generateKanaVariants(kana)
        for (variant in kanaVariants) {
            val variantResults = exactMatch(variant) + viterbiConvert(variant).take(3)
            for (c in variantResults) {
                if (seen.add(c.surface)) results.add(c.copy(cost = c.cost + 500))
            }
        }

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

        return boosted.sortedBy { it.cost }.take(if (kana.length <= 3) 40 else 25)
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
        committedContext.addFirst(candidate.surface)
        while (committedContext.size > 4) committedContext.removeLast()

        // Update POS context from the selected candidate's dictionary entry
        val entries = dict[candidate.reading]
        val matchEntry = entries?.firstOrNull { it.surface == candidate.surface }
        lastRightGroup = matchEntry?.rightGroup ?: 1

        // Recent history
        recentHistory.removeAll { it.surface == candidate.surface && it.reading == candidate.reading }
        // Store with a low cost so history candidates rank near the top.
        // The user explicitly selected this, so it should strongly influence future predictions.
        val historyCost = minOf(candidate.cost, 2000).coerceAtLeast(300)
        recentHistory.addFirst(candidate.copy(cost = historyCost))
        while (recentHistory.size > maxHistory) recentHistory.removeLast()

        debouncedSave()
    }

    fun updateContext(surface: String) {
        committedContext.addFirst(surface)
        while (committedContext.size > 4) committedContext.removeLast()
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
     * Uses the full Mozc 2670×2670 connection cost matrix.
     */
    private fun getConnectionCost(prevRightId: Int, currLeftId: Int): Int {
        if (connectionCostFlat.isEmpty() || numIds == 0) return DEFAULT_CONNECTION_COST
        val r = prevRightId.coerceIn(0, numIds - 1)
        val l = currLeftId.coerceIn(0, numIds - 1)
        val idx = r * numIds + l
        if (idx >= connectionCostFlat.size) return DEFAULT_CONNECTION_COST
        // Mozc connection costs range roughly -1000..32000; /3 scaling lets the matrix guide more
        return connectionCostFlat[idx].toInt() / 3
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

        // Static bigram boost (from bigrams.tsv — common Japanese collocations)
        val staticBoostVal = if (lastCommittedSurface.isNotEmpty()) {
            staticBigrams["$lastCommittedSurface→$key"] ?: 0
        } else 0

        // First use gets the biggest boost (diminishing returns after)
        // 1st use: 2000, 2nd: 3500, 3rd: 4500, 4th: 5200, 5th+: 5500
        val unigramBoostVal = when {
            unigramCount >= 5 -> 5500
            unigramCount >= 4 -> 5200
            unigramCount >= 3 -> 4500
            unigramCount >= 2 -> 3500
            unigramCount >= 1 -> 2000
            else -> 0
        }
        val bigramBoostVal = minOf(bigramCount, 4) * 2500
        val trigramBoostVal = minOf(trigramCount, 3) * 3500
        val totalBoost = minOf(unigramBoostVal + bigramBoostVal + trigramBoostVal + staticBoostVal, 22000)
        return maxOf(100, baseCost - totalBoost)
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
        // With full Mozc matrix (/3 scaled): natural ~650, unnatural ~4000
        // Neutral point 900 (shifted down to reward good transitions)
        return baseCost + (connCost - 900) / 2
    }

    /**
     * Rescore candidates using KenLM 5-gram language model.
     * Candidates with segments get LM score blended into their cost.
     * Candidates without segments are scored using surface as a single word.
     */
    private fun kenLmRescore(candidates: MutableList<ConversionCandidate>) {
        val scorer = kenLmScorer ?: return
        if (!scorer.isReady() || candidates.isEmpty()) return
        if (candidates.size <= 2) return
        val maxScore = 40.coerceAtMost(candidates.size)

        // Use up to 4 words of context for KenLM 5-gram
        val precedingContext = committedContext.reversed().joinToString(" ")

        val segmentLists = candidates.take(maxScore).map { c ->
            c.segments.ifEmpty { listOf(c.surface) }
        }

        val scores = scorer.scoreBatch(segmentLists, precedingContext)

        for (i in 0 until maxScore) {
            if (i >= scores.size) break
            // scores[i] is log10 prob (negative; higher = better)
            // Use sqrt normalization to mildly favor longer sequences without over-penalizing
            val wordCount = segmentLists[i].size.coerceAtLeast(1)
            val lmBonus = (scores[i] * -KENLM_WEIGHT / kotlin.math.sqrt(wordCount.toFloat())).toInt()
            candidates[i] = candidates[i].copy(cost = candidates[i].cost + lmBonus)
        }
    }

    // --- Kana variant generation (Google日本語入力方式の読み替え) ---

    /**
     * Generate alternative kana readings for conversion.
     * Handles cases where standard romaji mapping produces one kana but
     * the user may intend another pronunciation.
     *
     * Examples:
     * - ちぇをん → ちぇうぉん (を→うぉ for loanwords like チェウォン)
     * - を → お (を as vowel 'o' in compound words)
     * - ぢ → じ, づ → ず (四つ仮名の読み替え)
     * - ゐ → い, ゑ → え (historical kana)
     */
    private fun generateKanaVariants(kana: String): List<String> {
        val variants = mutableSetOf<String>()

        // を → うぉ replacement (loanword pronunciation)
        if ('を' in kana) {
            variants.add(kana.replace("を", "うぉ"))
            // Also try を → お (common in compound words)
            variants.add(kana.replace("を", "お"))
        }

        // ぢ ↔ じ (四つ仮名)
        if ('ぢ' in kana) {
            variants.add(kana.replace('ぢ', 'じ'))
        }
        if ('じ' in kana && kana.length <= 8) {
            variants.add(kana.replace('じ', 'ぢ'))
        }

        // づ ↔ ず
        if ('づ' in kana) {
            variants.add(kana.replace('づ', 'ず'))
        }
        if ('ず' in kana && kana.length <= 8) {
            variants.add(kana.replace('ず', 'づ'))
        }

        // ゐ → い, ゑ → え (historical)
        if ('ゐ' in kana) variants.add(kana.replace('ゐ', 'い'))
        if ('ゑ' in kana) variants.add(kana.replace('ゑ', 'え'))

        // Remove the original kana from variants
        variants.remove(kana)
        return variants.toList().take(4)  // Limit to avoid explosion
    }

    // --- Internal ---

    private fun exactMatch(kana: String): List<ConversionCandidate> {
        val entries = dict[kana] ?: return emptyList()
        // Exact match bonus: single-word results should beat multi-word Viterbi splits
        // Longer exact matches get bigger bonus (e.g. こんにちは should beat 今日+葉)
        val exactBonus = when {
            kana.length >= 8 -> -7000
            kana.length >= 7 -> -6000
            kana.length >= 5 -> -5000
            kana.length >= 4 -> -3500
            kana.length >= 3 -> -2000
            else -> -1000
        }
        return entries
            .map { entry ->
                var cost = applyBoost(kana, entry.surface, entry.cost) + exactBonus
                cost = posContextCost(kana, entry.surface, cost)
                // Suppress function words (助詞・助動詞) appearing as sole candidates for 2+ char input
                if (kana.length >= 2 && isFunctionWord(entry.leftGroup) && entry.surface.length <= 1) {
                    cost += 2000
                }
                ConversionCandidate(
                    surface = entry.surface,
                    reading = kana,
                    cost = cost,
                    segments = listOf(entry.surface),
                )
            }
            .sortedBy { it.cost }
            .take(if (kana.length <= 3) 40 else 20)  // Short readings: show all kanji candidates
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
     * Load static bigram data from bigrams.tsv.
     * Format: prev_surface\tnext_reading\tnext_surface\tboost
     */
    private fun loadStaticBigrams() {
        try {
            context.assets.open("dict/bigrams.tsv").use { stream ->
                BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
                    var count = 0
                    reader.forEachLine { line ->
                        if (line.isBlank() || line.startsWith('#')) return@forEachLine
                        val parts = line.split('\t')
                        if (parts.size >= 4) {
                            val prevSurface = parts[0]
                            val nextReading = parts[1]
                            val nextSurface = parts[2]
                            val boost = parts[3].toIntOrNull() ?: 1000
                            val key = "$prevSurface→$nextReading:$nextSurface"
                            staticBigrams[key] = boost
                            count++
                        }
                    }
                    Log.i("NacreDictionary", "Static bigrams loaded: $count entries")
                }
            }
        } catch (e: Exception) {
            Log.i("NacreDictionary", "No bigrams.tsv found (optional)")
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
        if (limit <= 0 || kana.length < 2) return emptyList()
        val results = mutableListOf<ConversionCandidate>()

        // 1. Exact match — no penalty
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

        // 2. Prefix match — penalty proportional to remaining characters
        // e.g. "あっぷ"(3) matching "あっぷる"(4): penalty = (4-3)/4 * 800 = 200
        // e.g. "あ"(1) matching "あっぷる"(4): penalty = (4-1)/4 * 800 = 600
        if (results.size < limit) {
            for ((key, entries) in englishDict) {
                if (key.startsWith(kana) && key != kana) {
                    val matchRatio = kana.length.toFloat() / key.length
                    val penalty = ((1f - matchRatio) * 800).toInt()
                    for (entry in entries.take(1)) {
                        results.add(ConversionCandidate(
                            surface = entry.surface,
                            reading = key,
                            cost = entry.cost + penalty,
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

        // 2. Common misreading/mistyping patterns
        // Only entries where key != value (self-mappings are skipped by the check below)
        val corrections = mapOf(
            // 定番の誤読
            "ふいんき" to "ふんいき",        // 雰囲気
            "たいく" to "たいいく",          // 体育
            "がいしゅつ" to "きしゅつ",       // 既出
            "ぜいいん" to "ぜんいん",        // 全員
            "いちよう" to "いちおう",        // 一応
            "そうゆう" to "そういう",        // そういう
            "こんにちわ" to "こんにちは",
            "こんばんわ" to "こんばんは",
            "づつ" to "ずつ",              // ～ずつ
            "しゅみれーしょん" to "しみゅれーしょん", // シミュレーション
            "うるおぼえ" to "うろおぼえ",     // うろ覚え
            "ていかく" to "てきかく",        // 的確（誤読）
            "げいいん" to "げんいん",        // 原因（誤読）
            "がいいん" to "げんいん",        // 原因（誤読）
            // 音声認識でよく起きる誤認識
            "わたしわ" to "わたしは",        // 助詞「は」
            "きょうわ" to "きょうは",        // 今日は
            "ぼくわ" to "ぼくは",
            "あなたわ" to "あなたは",
            "それわ" to "それは",
            "これわ" to "これは",
            "なにわ" to "なには",
            "どこえ" to "どこへ",            // 助詞「へ」
            "いえ" to "いへ",
            "こっちえ" to "こっちへ",
            "そっちえ" to "そっちへ",
            "～お" to "～を",               // 助詞「を」
            // 長音の誤入力
            "とうり" to "とおり",            // 通り
            "おうきい" to "おおきい",        // 大きい
            "おうい" to "おおい",            // 多い
            "こうり" to "こおり",            // 氷
            // カタカナ語の誤読
            "てぃーしゃつ" to "てぃーしゃつ",
            "こみにけーしょん" to "こみゅにけーしょん", // コミュニケーション
            "あぼがど" to "あぼかど",        // アボカド
            "ばっく" to "ばっぐ",            // バッグ
            "べっと" to "べっど",            // ベッド
            "でぃすくとっぷ" to "ですくとっぷ", // デスクトップ（逆方向も）
            "ですくとっぷ" to "でぃすくとっぷ",
            // IT用語の誤入力
            "でぃふぉると" to "でふぉると",   // デフォルト
            "でふぉると" to "でふぉると",
            "あるごりずむ" to "あるごりずむ",
            "ぱらめーた" to "ぱらめーたー",   // パラメーター
            "ぷろぱてぃ" to "ぷろぱてぃー",   // プロパティー
            "めっせーじ" to "めっせーじ",
            // 敬語の誤り
            "おっしゃった" to "おっしゃった",
            "いただきます" to "いただきます",
            "さしすせそ" to "さしすせそ",
            "ゆわれた" to "いわれた",         // 言われた
            "ゆった" to "いった",            // 言った
            "ゆってた" to "いってた",         // 言ってた
            "ちがくて" to "ちがって",         // 違って
            "ちがかった" to "ちがった",        // 違った
            "やっぱし" to "やっぱり",         // やっぱり
            "やぱり" to "やっぱり",
            // 濁点・半濁点の誤り
            "せんたく" to "せんたく",         // 洗濯/選択
            "きっぷ" to "きっぷ",
            "しんぱい" to "しんぱい",
            // 音便の誤入力
            "あったかい" to "あたたかい",     // 暖かい
            "つめたい" to "つめたい",
            "はやい" to "はやい",
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
        private const val DEFAULT_CONNECTION_COST = 2000
        // KenLM weight for post-hoc rescoring
        private const val KENLM_WEIGHT = 2500f
        // KenLM weight inside Viterbi (lower than post-hoc to avoid dominating beam search)
        private const val VITERBI_LM_WEIGHT = 1500f

        // Mozc POS ID range checks (from id.def)
        fun isNoun(id: Int) = id in 1841..2193
        fun isVerb(id: Int) = id in 434..1840
        fun isAdjective(id: Int) = id in 2194..2588
        fun isAuxVerb(id: Int) = id in 29..267
        fun isParticle(id: Int) = id in 268..433
        fun isContentWord(id: Int) = id in 434..2588  // 動詞+名詞+形容詞
        fun isFunctionWord(id: Int) = id in 29..433   // 助動詞+助詞
        fun isAdverb(id: Int) = id in 12..28
        fun isConjunction(id: Int) = id in 2591..2593
        fun isInterjection(id: Int) = id in 2589..2590
        fun isSymbol(id: Int) = id in 2641..2656
    }

    private fun lengthBonus(segLen: Int): Int {
        // Strongly prefer longer segments — key for matching Google IME quality.
        // Longer segments = fewer word boundaries = less ambiguity.
        return when {
            segLen >= 10 -> -4000
            segLen >= 8 -> -3200
            segLen >= 7 -> -2600
            segLen >= 6 -> -2000
            segLen >= 5 -> -1500
            segLen >= 4 -> -1000
            segLen >= 3 -> -400
            segLen == 2 -> 0
            else -> 1200  // Single-char segments heavily penalized (particles handled by connection cost)
        }
    }

    private fun viterbiConvert(kana: String): List<ConversionCandidate> {
        val n = kana.length
        if (n == 0) return emptyList()

        // Check if KenLM incremental scoring is available
        val scorer = kenLmScorer
        val lmAvailable = scorer != null && scorer.isReady() && scorer.getStateSize() > 0
        val lmBosState = if (lmAvailable) scorer!!.getBeginState() else null
        // Build preceding context state by feeding committed words through the LM
        val lmInitState: ByteArray? = if (lmAvailable && lmBosState != null && committedContext.isNotEmpty()) {
            var state: ByteArray = lmBosState
            for (word in committedContext.reversed()) {
                val result = scorer!!.scoreWordIncremental(state, word)
                state = result?.second ?: state
            }
            state
        } else {
            lmBosState
        }

        data class Node(
            val cost: Int,
            val backPos: Int,
            val surface: String,
            val reading: String,
            val segCount: Int,
            val rightGroup: Int,
            val prevNode: Node?,
            val lmState: ByteArray?,   // KenLM state for incremental scoring
            val lmScore: Float,        // Cumulative LM log10 prob
        )

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

        val K = if (n <= 6) 20 else if (n <= 10) 18 else 15  // Wide beam for accuracy
        val dp = Array(n + 1) { mutableListOf<Node>() }
        dp[0].add(Node(cost = 0, backPos = -1, surface = "", reading = "", segCount = 0,
            rightGroup = lastRightGroup, prevNode = null, lmState = lmInitState, lmScore = 0f))

        for (endPos in 1..n) {
            val allCandidates = mutableListOf<Node>()
            val maxSegLen = minOf(endPos, if (n <= 10) 12 else 10)

            for (segLen in 1..maxSegLen) {
                val startPos = endPos - segLen
                val segment = kana.substring(startPos, endPos)

                val entries = dict[segment]
                if (entries != null && entries.isNotEmpty()) {
                    for (prevNode in dp[startPos]) {
                        val prevRG = prevNode.rightGroup
                        val isAfterContentWord = isContentWord(prevRG)
                        val isAfterFunctionWord = isFunctionWord(prevRG)

                        // Limit candidates per segment: fewer for long inputs
                        val takeN = if (n > 12) minOf(if (segLen >= 3) 12 else 8, entries.size)
                                    else if (segLen >= 3) 16 else 10
                        for (entry in entries.take(takeN)) {
                            var cost = applyBoost(segment, entry.surface, entry.cost)
                            val connCost = if (startPos == 0 && prevRG == 0) {
                                getConnectionCost(0, entry.leftGroup)
                            } else {
                                getConnectionCost(prevRG, entry.leftGroup)
                            }
                            cost += connCost

                            // Hiragana surface bonus (reduced — full Mozc matrix handles most POS transitions)
                            if (entry.surface == segment) {
                                if (isParticle(entry.leftGroup) && segLen <= 2 && isAfterContentWord) cost -= 1500
                                if (isAuxVerb(entry.leftGroup) && segLen <= 3 && isAfterContentWord) cost -= 1200
                                if (segLen <= 3 && isAfterContentWord && !isFunctionWord(entry.leftGroup)) cost -= 800
                                if (segLen <= 2 && isAfterFunctionWord && isFunctionWord(entry.leftGroup)) cost -= 600
                                if (segLen <= 2 && isAfterFunctionWord && !isFunctionWord(entry.leftGroup)) cost -= 300
                            }

                            val lBonus = lengthBonus(segLen)

                            // KenLM incremental scoring within Viterbi
                            var lmCost = 0
                            var newLmState = prevNode.lmState
                            var newLmScore = prevNode.lmScore
                            if (lmAvailable && prevNode.lmState != null) {
                                val lmResult = scorer!!.scoreWordIncremental(prevNode.lmState!!, entry.surface)
                                if (lmResult != null) {
                                    newLmScore = prevNode.lmScore + lmResult.first
                                    newLmState = lmResult.second
                                    // Convert LM score to cost: negative log prob * weight
                                    // lmResult.first is log10(P(word|context)), typically -0.5 to -4.0
                                    lmCost = (lmResult.first * -VITERBI_LM_WEIGHT).toInt()
                                }
                            }

                            val totalCost = prevNode.cost + cost + lBonus + lmCost

                            allCandidates.add(Node(
                                cost = totalCost,
                                backPos = startPos,
                                surface = entry.surface,
                                reading = segment,
                                segCount = prevNode.segCount + 1,
                                rightGroup = entry.rightGroup,
                                prevNode = prevNode,
                                lmState = newLmState,
                                lmScore = newLmScore,
                            ))
                        }
                    }
                } else if (segLen == 1) {
                    for (prevNode in dp[startPos]) {
                        val connCost = if (startPos == 0) 0 else getConnectionCost(prevNode.rightGroup, 1)
                        val totalCost = prevNode.cost + 15000 + connCost
                        allCandidates.add(Node(
                            cost = totalCost,
                            backPos = startPos,
                            surface = segment,
                            reading = segment,
                            segCount = prevNode.segCount + 1,
                            rightGroup = 1,
                            prevNode = prevNode,
                            lmState = prevNode.lmState,
                            lmScore = prevNode.lmScore,
                        ))
                    }
                }
            }

            // Keep top-K with diversity — use partial sort for performance
            allCandidates.sortBy { it.cost }
            val kept = mutableListOf<Node>()
            val seenHashes = mutableSetOf<Long>()
            for (node in allCandidates) {
                val pathHash = (System.identityHashCode(node.prevNode).toLong() shl 32) xor
                    node.surface.hashCode().toLong()
                if (seenHashes.add(pathHash)) {
                    kept.add(node)
                }
                if (kept.size >= K * 3) break
            }
            dp[endPos] = kept
        }

        val results = mutableListOf<ConversionCandidate>()
        val seen = mutableSetOf<String>()

        for (node in dp[n]) {
            val segments = reconstructSegments(node)
            val combined = segments.joinToString("")
            if (combined.isNotEmpty() && seen.add(combined)) {
                val eosCost = getConnectionCost(node.rightGroup, 0)
                val segPenalty = if (node.segCount >= 5) (node.segCount - 4) * 500 else 0
                val finalCost = node.cost + eosCost / 2 + segPenalty
                results.add(ConversionCandidate(surface = combined, reading = kana, cost = finalCost, segments = segments))
            }
            if (results.size >= 25) break
        }

        val exactMatches = exactMatch(kana)
        if (results.isEmpty()) return exactMatches

        for (em in exactMatches) {
            if (seen.add(em.surface)) results.add(em)
        }

        val katakana = hiraganaToKatakana(kana)
        if (katakana != kana && seen.add(katakana)) {
            val maxCost = results.maxOfOrNull { it.cost } ?: 5000
            results.add(ConversionCandidate(surface = katakana, reading = kana, cost = maxCost + 500))
        }
        if (seen.add(kana)) {
            val maxCost = results.maxOfOrNull { it.cost } ?: 5000
            results.add(ConversionCandidate(surface = kana, reading = kana, cost = maxCost + 200))
        }

        return results.take(25)
    }

    /**
     * Estimate appropriate cost for katakana conversion.
     * If most dictionary entries for this reading have katakana surfaces (= loanword),
     * rank katakana much higher.
     */
    private fun estimateKatakanaCost(kana: String, existingResults: List<ConversionCandidate>): Int {
        val entries = dict[kana]
        if (entries != null && entries.size >= 2) {
            val katakanaCount = entries.count { surface ->
                surface.surface.all { it in '\u30A0'..'\u30FF' || it == 'ー' }
            }
            val ratio = katakanaCount.toFloat() / entries.size
            if (ratio >= 0.5f) {
                // Loanword-heavy reading: katakana should rank near the top
                val minCost = existingResults.minOfOrNull { it.cost } ?: 5000
                return minCost + 200
            }
        }
        // Default: katakana at the bottom
        val maxCost = existingResults.maxOfOrNull { it.cost } ?: 5000
        return maxCost + 500
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
