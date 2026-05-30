package com.promptplayer.domain

object TextMatcher {

    private const val SIMILARITY_THRESHOLD = 0.3           // Raised from 0.2 to reduce false positives
    private const val PREFIX_MATCH_THRESHOLD = 0.4         // Minimum decayed score for prefix match
    private const val LOOK_BACK = 3                        // Reduced from 5 — less backward searching
    private const val LOOK_AHEAD = 20
    private const val DECAY_RATE = 8.0                     // Position decay rate (lower = faster decay)
    private const val BACKWARD_PENALTY = 0.4               // Extra penalty multiplier for backward jumps

    fun findBestMatchIndex(recognizedText: String, sentences: List<String>, startIndex: Int = 0): Int {
        val cleanRecognized = cleanText(recognizedText)
        if (cleanRecognized.isEmpty() || sentences.isEmpty()) return startIndex

        // Phase 1: Prefix matching with position-weighted scoring
        // Instead of returning the first match blindly, we score each match by
        // how close it is to the current position and pick the best one.
        val prefixResult = findBestPrefixMatch(cleanRecognized, sentences, startIndex)
        if (prefixResult >= 0) return prefixResult

        // Phase 2: Jaccard similarity with position decay in local search window
        val searchStart = maxOf(0, startIndex - LOOK_BACK)
        val searchEnd = minOf(sentences.size, startIndex + LOOK_AHEAD)

        var bestIndex = startIndex
        var bestScore = 0.0

        for (i in searchStart until searchEnd) {
            val cleanSentence = cleanText(sentences[i])
            if (cleanSentence.isEmpty()) continue

            val similarity = jaccardSimilarity(cleanRecognized, cleanSentence)
            val positionWeight = calculatePositionWeight(i, startIndex)
            val finalScore = similarity * positionWeight

            if (finalScore > bestScore && finalScore > SIMILARITY_THRESHOLD) {
                bestScore = finalScore
                bestIndex = i
            }
        }

        // Phase 3: No full-document scan — staying at current position is safer
        // than jumping to a distant similar sentence. If the user re-reads earlier
        // text, the next partial result will pick it up within the LOOK_BACK window.
        return bestIndex
    }

    /**
     * Find the best prefix match with position-aware scoring.
     *
     * Previously: returned the first forward match immediately, which caused
     * the UI to jump to far-away sentences that share common prefixes (e.g.,
     * "我们要..." matching the first occurrence 80 sentences back).
     *
     * Now: scores each prefix match by its distance from startIndex using
     * exponential decay, and only returns if the best score exceeds a threshold.
     * Backward matches receive an additional penalty.
     */
    private fun findBestPrefixMatch(recognized: String, sentences: List<String>, startIndex: Int): Int {
        var bestIndex = -1
        var bestScore = 0.0

        // Search forward from startIndex
        val searchEnd = minOf(sentences.size, startIndex + LOOK_AHEAD)
        for (i in startIndex until searchEnd) {
            val cleanSentence = cleanText(sentences[i])
            if (cleanSentence.isEmpty()) continue
            if (cleanSentence.startsWith(recognized)) {
                val weight = calculatePositionWeight(i, startIndex)
                if (weight > bestScore) {
                    bestScore = weight
                    bestIndex = i
                }
            }
        }

        // Search backward with extra penalty to discourage re-jumping
        val searchStart = maxOf(0, startIndex - LOOK_BACK)
        for (i in startIndex - 1 downTo searchStart) {
            val cleanSentence = cleanText(sentences[i])
            if (cleanSentence.isEmpty()) continue
            if (cleanSentence.startsWith(recognized)) {
                val weight = calculatePositionWeight(i, startIndex) * BACKWARD_PENALTY
                if (weight > bestScore) {
                    bestScore = weight
                    bestIndex = i
                }
            }
        }

        // Only return if the best score exceeds the threshold
        return if (bestScore >= PREFIX_MATCH_THRESHOLD) bestIndex else -1
    }

    /**
     * Position weight using exponential decay: weight = exp(-distance / DECAY_RATE)
     *
     * Examples with DECAY_RATE = 8.0:
     *   distance 0:  weight = 1.0
     *   distance 1:  weight = 0.882
     *   distance 5:  weight = 0.535
     *   distance 10: weight = 0.287
     *   distance 20: weight = 0.082
     *
     * Combined with BACKWARD_PENALTY = 0.4, a backward match at distance 0
     * scores 0.4 (barely above threshold), and at distance 1 scores 0.353
     * (below threshold — rejected).
     */
    private fun calculatePositionWeight(index: Int, startIndex: Int): Double {
        val distance = kotlin.math.abs(index - startIndex)
        return kotlin.math.exp(-distance / DECAY_RATE)
    }

    /**
     * Check if the recognized text covers the end portion of the given sentence.
     * Used for pre-scrolling — when the user is reading the last words of a
     * sentence, we start scrolling before the match moves to the next sentence.
     *
     * Returns true when:
     * 1. Recognized text is a suffix of the cleaned sentence (user is at the end)
     * 2. Recognized text shares > 65% bigram overlap with the sentence AND
     *    the remaining unmatched portion of the sentence is small (< 40%)
     */
    fun isEndOfSentence(recognizedText: String, sentence: String): Boolean {
        val cleanRec = cleanText(recognizedText)
        val cleanSent = cleanText(sentence)
        // Need enough text to judge
        if (cleanRec.length < 3 || cleanSent.length < 5) return false
        // Don't trigger if recognized text is longer than the sentence (likely next sentence)
        if (cleanRec.length > cleanSent.length * 1.2f) return false

        // Check 1: recognized text is the suffix of the sentence
        if (cleanSent.endsWith(cleanRec)) return true

        // Check 2: recognized text covers > 65% of the sentence via bigram overlap
        val sentBigrams = extractBigrams(cleanSent)
        if (sentBigrams.isEmpty()) return false
        val recBigrams = extractBigrams(cleanRec)
        val overlap = recBigrams.intersect(sentBigrams).size.toFloat() / sentBigrams.size
        if (overlap > 0.65f) return true

        // Check 3: recognized text ends with the last bigram of the sentence
        val lastBigram = cleanSent.takeLast(2)
        if (lastBigram.length == 2 && cleanRec.contains(lastBigram)) {
            // And the recognized text is sufficiently long relative to the sentence
            if (cleanRec.length > cleanSent.length * 0.5f) return true
        }

        return false
    }

    internal fun jaccardSimilarity(s1: String, s2: String): Double {
        val bigrams1 = extractBigrams(s1)
        val bigrams2 = extractBigrams(s2)

        if (bigrams1.isEmpty() && bigrams2.isEmpty()) return 1.0
        if (bigrams1.isEmpty() || bigrams2.isEmpty()) return 0.0

        val intersection = bigrams1.intersect(bigrams2).size
        val union = bigrams1.union(bigrams2).size

        return intersection.toDouble() / union.toDouble()
    }

    internal fun extractBigrams(s: String): Set<String> {
        if (s.length < 2) return setOf(s)
        val bigrams = mutableSetOf<String>()
        for (i in 0 until s.length - 1) {
            bigrams.add(s.substring(i, i + 2))
        }
        return bigrams
    }

    internal fun cleanText(text: String): String {
        return text.lowercase()
            .replace(Regex("[^\\u4e00-\\u9fff\\u3400-\\u4dbf\\uf900-\\ufaffa-z0-9]"), "")
            .trim()
    }
}
