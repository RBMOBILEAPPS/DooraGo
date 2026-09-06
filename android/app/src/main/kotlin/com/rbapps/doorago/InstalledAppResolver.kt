package com.rbapps.doorago

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import java.util.Locale

/**
 * Reusable, confidence-based semantic and fuzzy installed-application resolver for DooraGo.
 *
 * Provides resilient app matching against imperfect speech-to-text (STT) transcriptions
 * (e.g. "YouTube polo" -> YouTube, "Up stock" -> Upstox, "What's app" -> WhatsApp),
 * multi-signal scoring, candidate ranking, margin-based disambiguation, and safe execution.
 */
class InstalledAppResolver(
    private val context: Context
) {
    companion object {
        private const val TAG = "InstalledAppResolver"
        private const val NATIVE_TAG = "DooraGoNative"

        // Cache duration: 60 seconds to avoid querying PackageManager on every keystroke
        private const val CACHE_TTL_MS = 60_000L

        // Confidence thresholds
        const val THRESHOLD_EXACT_WINNER = 0.95
        const val THRESHOLD_HIGH_CONFIDENCE = 0.75
        const val THRESHOLD_MIN_MATCH = 0.65
        const val MARGIN_REQUIRED = 0.08
    }

    data class InstalledAppRecord(
        val packageName: String,
        val appLabel: String,
        val normalizedLabel: String,
        val compactLabel: String,
        val tokens: List<String>,
        val aliases: List<String>,
        val soundexCode: String
    )

    data class AppMatchCandidate(
        val app: InstalledAppRecord,
        val score: Double,
        val matchType: String
    )

    sealed class AppLaunchResolution {
        data class Launch(val app: InstalledAppRecord, val action: () -> String) : AppLaunchResolution()
        data class DisambiguationNeeded(val originalQuery: String, val candidates: List<InstalledAppRecord>, val prompt: String) : AppLaunchResolution()
        data class ErrorOrNotFound(val message: String) : AppLaunchResolution()
    }

    private var cachedApps: List<InstalledAppRecord> = emptyList()
    private var lastCacheTime: Long = 0L

    /**
     * Retrieves all launchable apps with caching.
     */
    @Synchronized
    fun getInstalledLaunchableApps(forceRefresh: Boolean = false): List<InstalledAppRecord> {
        val now = System.currentTimeMillis()
        if (!forceRefresh && cachedApps.isNotEmpty() && (now - lastCacheTime) < CACHE_TTL_MS) {
            return cachedApps
        }

        val pm = context.packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }

        val resolveList: List<ResolveInfo> = try {
            pm.queryIntentActivities(mainIntent, 0)
        } catch (e: Exception) {
            Log.e(TAG, "[APP_INDEX] Error querying PackageManager: ${e.message}", e)
            emptyList()
        }

        val appMap = mutableMapOf<String, InstalledAppRecord>() // packageName -> record
        for (info in resolveList) {
            val label = info.loadLabel(pm).toString().trim()
            val pkg = info.activityInfo.packageName
            if (label.isNotEmpty() && !pkg.isNullOrEmpty()) {
                val record = buildAppRecord(label, pkg)
                appMap[pkg] = record
            }
        }

        cachedApps = appMap.values.toList()
        lastCacheTime = now
        Log.i(NATIVE_TAG, "[APP_INDEX] loaded ${cachedApps.size} launchable apps")
        return cachedApps
    }

    /**
     * Resolves a raw app query to an executable launch action, disambiguation prompt, or not-found notice.
     */
    fun resolveApp(rawQuery: String): AppLaunchResolution {
        val cleanQuery = sanitizeAppNameQuery(rawQuery)
        Log.i(NATIVE_TAG, "[APP_RESOLVE] raw='$rawQuery' query='$cleanQuery'")

        if (cleanQuery.isBlank()) {
            return AppLaunchResolution.ErrorOrNotFound("Please specify which app you want to open.")
        }

        val allApps = getInstalledLaunchableApps()
        if (allApps.isEmpty()) {
            return AppLaunchResolution.ErrorOrNotFound("I couldn't find any launchable apps on this phone.")
        }

        val candidates = matchApps(cleanQuery, allApps)
        if (candidates.isEmpty()) {
            val displayTarget = cleanQuery.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
            return AppLaunchResolution.ErrorOrNotFound("I couldn't find '$displayTarget' on this phone.")
        }

        for (cand in candidates.take(4)) {
            Log.i(NATIVE_TAG, "[APP_MATCH] app='${cand.app.appLabel}' score=${String.format(Locale.ROOT, "%.2f", cand.score)} type=${cand.matchType}")
        }

        val topMatch = candidates[0]
        val isExactWinner = topMatch.score >= THRESHOLD_EXACT_WINNER
        val hasClearMargin = if (candidates.size > 1) {
            val secondMatch = candidates[1]
            (topMatch.score >= THRESHOLD_HIGH_CONFIDENCE && (topMatch.score - secondMatch.score) >= MARGIN_REQUIRED)
        } else {
            topMatch.score >= THRESHOLD_MIN_MATCH
        }

        if (isExactWinner || hasClearMargin) {
            val app = topMatch.app
            val pm = context.packageManager
            val launchIntent = pm.getLaunchIntentForPackage(app.packageName)
            if (launchIntent != null) {
                Log.i(NATIVE_TAG, "[APP_RESOLVE] matched='${app.appLabel}' pkg='${app.packageName}'")
                return AppLaunchResolution.Launch(app) {
                    executeAppLaunch(app, launchIntent)
                }
            } else {
                return AppLaunchResolution.ErrorOrNotFound("I couldn't launch ${app.appLabel}.")
            }
        }

        // Ambiguous candidates detection (e.g. Google Drive vs Google Docs)
        if (candidates.size > 1 && topMatch.score >= THRESHOLD_MIN_MATCH) {
            val closeCandidates = candidates.filter { it.score >= (topMatch.score - MARGIN_REQUIRED) }.take(3)
            val distinctApps = closeCandidates.map { it.app }.distinctBy { it.appLabel }
            if (distinctApps.size > 1) {
                val names = distinctApps.joinToString(" ya ") { it.appLabel }
                Log.i(NATIVE_TAG, "[APP_RESOLVE] disambiguation_needed candidates=$names")
                return AppLaunchResolution.DisambiguationNeeded(
                    originalQuery = cleanQuery,
                    candidates = distinctApps,
                    prompt = "Kaunsa app kholna hai — $names?"
                )
            }
        }

        // Single match with moderate score
        if (topMatch.score >= THRESHOLD_MIN_MATCH) {
            val app = topMatch.app
            val launchIntent = context.packageManager.getLaunchIntentForPackage(app.packageName)
            if (launchIntent != null) {
                return AppLaunchResolution.Launch(app) {
                    executeAppLaunch(app, launchIntent)
                }
            }
        }

        val displayTarget = cleanQuery.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
        return AppLaunchResolution.ErrorOrNotFound("I couldn't find '$displayTarget' on this phone.")
    }

    private fun executeAppLaunch(app: InstalledAppRecord, launchIntent: Intent): String {
        val isUiVisible = MainActivity.isUiVisible
        val assistantRole = DooraVoiceInteractionService.isRoleHeld(context)
        Log.i(NATIVE_TAG, "[APP_LAUNCH] target='${app.appLabel}' package='${app.packageName}' uiVisible=$isUiVisible assistantRole=$assistantRole")

        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

        // Always attempt direct startActivity first
        val directSuccess = try {
            context.startActivity(launchIntent)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Direct launch failed for package ${app.packageName}: ${e.message}")
            false
        }

        if (directSuccess) {
            Log.i(NATIVE_TAG, "[APP_LAUNCH] package=${app.packageName} foreground=$isUiVisible assistantRole=$assistantRole directLaunchAttempt=true notificationFallback=false notificationPosted=false result=SUCCESS")
            return "Opening ${app.appLabel}."
        }

        // Direct launch failed (e.g. restricted by Android BAL policy). Attempt notification fallback.
        val notificationPosted = postBackgroundLaunchNotification(app.packageName, app.appLabel, launchIntent)

        return if (notificationPosted) {
            Log.i(NATIVE_TAG, "[APP_LAUNCH] package=${app.packageName} foreground=$isUiVisible assistantRole=$assistantRole directLaunchAttempt=true notificationFallback=true notificationPosted=true result=FALLBACK")
            "Tap notification to open ${app.appLabel}."
        } else {
            Log.i(NATIVE_TAG, "[APP_LAUNCH] package=${app.packageName} foreground=$isUiVisible assistantRole=$assistantRole directLaunchAttempt=true notificationFallback=true notificationPosted=false result=BLOCKED")
            "${app.appLabel} cannot be opened automatically while DooraGo is in the background. Please allow notifications or set DooraGo as your assistant."
        }
    }

    fun postBackgroundLaunchNotification(packageName: String, appLabel: String, launchIntent: Intent): Boolean {
        return try {
            val notificationManagerCompat = androidx.core.app.NotificationManagerCompat.from(context)
            if (!notificationManagerCompat.areNotificationsEnabled()) {
                Log.w(TAG, "Cannot post launch notification for $appLabel: notifications disabled or permission denied.")
                return false
            }

            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return false
            val channelId = "doora_voice_wake_channel"

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    channelId,
                    "Voice Wake Service",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Shows app launch notifications when DooraGo is in background."
                }
                notificationManager.createNotificationChannel(channel)
            }

            val requestCode = (packageName.hashCode() and 0x7fffffff) % 10000 + 4000
            val pendingIntent = PendingIntent.getActivity(
                context,
                requestCode,
                launchIntent.apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(context, channelId)
                .setContentTitle("Open $appLabel")
                .setContentText("Tap to open $appLabel")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .addAction(
                    android.R.drawable.ic_menu_send,
                    "Open $appLabel",
                    pendingIntent
                )
                .build()

            notificationManager.notify(requestCode, notification)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to post background launch notification for $appLabel: ${e.message}")
            false
        }
    }

    data class AppAnchorMatch(
        val app: InstalledAppRecord,
        val score: Double,
        val matchedSpan: String
    )

    /**
     * Identifies if a transcript contains a high-confidence installed app anchor at the beginning
     * or core of the prompt, with small surrounding command/noise tokens (e.g. "YouTube polo").
     *
     * Resolves app intent through confidence and candidate anchoring rather than brittle hardcoded typo mappings.
     */
    fun findAppAnchor(transcript: String): AppAnchorMatch? {
        val clean = transcript.lowercase(Locale.ROOT).trim()
        if (clean.isBlank()) return null

        val tokens = clean.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (tokens.isEmpty() || tokens.size > 4) return null

        val allApps = getInstalledLaunchableApps()
        if (allApps.isEmpty()) return null

        // Try spans of 1 to 3 words from start of transcript
        for (len in minOf(3, tokens.size) downTo 1) {
            val span = tokens.take(len).joinToString(" ")
            val candidates = matchApps(span, allApps)
            if (candidates.isNotEmpty()) {
                val top = candidates[0]
                if (top.score >= THRESHOLD_HIGH_CONFIDENCE) {
                    val remainingTokens = tokens.drop(len)
                    // If remaining tokens are at most 1 short word (e.g. "polo", "please", "fast", "app")
                    if (remainingTokens.size <= 1) {
                        Log.i(NATIVE_TAG, "[APP_ANCHOR] matchedSpan='$span' app='${top.app.appLabel}' score=${top.score} remaining=$remainingTokens")
                        return AppAnchorMatch(
                            app = top.app,
                            score = top.score,
                            matchedSpan = span
                        )
                    }
                }
            }
        }
        return null
    }

    /**
     * Reusable guard to reject conversational, question, or negated statements
     * (e.g. "Don't open YouTube", "My friend uses YouTube", "I like YouTube", "Why do people use YouTube?").
     */
    fun isNegatedOrConversational(lower: String): Boolean {
        val trimmed = lower.trim()

        val nonCommandPrefixes = listOf(
            "don't open", "dont open", "do not open", "please don't", "please do not",
            "my friend", "i like", "i use", "i was", "why do", "why is", "tell me about",
            "what is", "how to", "who uses", "who is",
            "mera dost", "mujhe pasand", "kya hai", "kyun", "kaise",
            "मेरा दोस्त", "मुझे पसंद", "क्या है", "क्यों", "कैसे", "मत खोलो", "नहीं खोलो"
        )
        if (nonCommandPrefixes.any { trimmed.startsWith(it) || trimmed.contains(" $it") }) {
            return true
        }

        val nonCommandSubstrings = listOf(
            "mat kholo", "nahi kholo", "open mat karo", "chalu mat karo", "don't launch", "dont launch",
            "uses ", "watching ", "like to use", "likes to use", "बारे में बताओ", "पसंद है"
        )
        if (nonCommandSubstrings.any { trimmed.contains(it) }) {
            return true
        }

        return false
    }

    /**
     * Multi-signal semantic scoring algorithm.
     */
    fun matchApps(query: String, allApps: List<InstalledAppRecord>): List<AppMatchCandidate> {
        val cleanQuery = query.lowercase(Locale.ROOT).trim()
        val compactQuery = cleanQuery.replace(Regex("[^a-zA-Z0-9\\u0900-\\u097F]"), "")
        val queryTokens = cleanQuery.split(Regex("[\\s\\-_.]+")).filter { it.isNotBlank() }
        val querySoundex = soundex(compactQuery)

        if (cleanQuery.isBlank() || compactQuery.isBlank()) return emptyList()

        val candidates = mutableListOf<AppMatchCandidate>()

        for (app in allApps) {
            var bestScore = 0.0
            var bestType = "NONE"

            // 1. Exact Label Match
            if (app.normalizedLabel == cleanQuery) {
                bestScore = 1.0
                bestType = "EXACT_LABEL"
            }

            // 2. Compact Equality (e.g. "youtube" == "you tube", "upstox" == "up stock")
            if (bestScore < 0.98 && app.compactLabel == compactQuery) {
                bestScore = 0.98
                bestType = "COMPACT_EXACT"
            }

            // 3. Alias Exact / Compact Match
            if (bestScore < 0.96) {
                for (alias in app.aliases) {
                    val aliasClean = alias.lowercase(Locale.ROOT).trim()
                    val aliasCompact = aliasClean.replace(Regex("[^a-zA-Z0-9\\u0900-\\u097F]"), "")
                    if (aliasClean == cleanQuery || aliasCompact == compactQuery) {
                        bestScore = 0.96
                        bestType = "ALIAS_EXACT"
                        break
                    }
                }
            }

            // 4. Token Overlap Scoring
            if (bestScore < 0.92 && queryTokens.isNotEmpty() && app.tokens.isNotEmpty()) {
                val matchedTokens = queryTokens.count { qTok ->
                    app.tokens.any { aTok -> aTok == qTok || similarityRatio(qTok, aTok) >= 0.85 }
                }
                if (matchedTokens == queryTokens.size) {
                    // Full coverage of all user query words in the app name (e.g. "maps" in "Google Maps" or "google drive" in "Google Drive")
                    val score = if (queryTokens.size == app.tokens.size) 0.94 else 0.90
                    if (score > bestScore) {
                        bestScore = score
                        bestType = "TOKEN_FULL_MATCH"
                    }
                } else if (matchedTokens > 0) {
                    val coverage = matchedTokens.toDouble() / queryTokens.size.toDouble()
                    val score = 0.70 + (coverage * 0.15)
                    if (score > bestScore) {
                        bestScore = score
                        bestType = "TOKEN_PARTIAL_MATCH"
                    }
                }
            }

            // 5. Prefix & Substring Match
            if (bestScore < 0.88 && cleanQuery.length >= 3) {
                if (app.normalizedLabel.startsWith(cleanQuery)) {
                    val score = 0.85
                    if (score > bestScore) {
                        bestScore = score
                        bestType = "PREFIX_MATCH"
                    }
                } else if (app.normalizedLabel.contains(cleanQuery)) {
                    val score = 0.80
                    if (score > bestScore) {
                        bestScore = score
                        bestType = "SUBSTRING_MATCH"
                    }
                }
            }

            // 6. Fuzzy Levenshtein Character Similarity
            if (bestScore < 0.90 && compactQuery.length >= 3) {
                val directSim = similarityRatio(compactQuery, app.compactLabel)
                var maxAliasSim = 0.0
                for (alias in app.aliases) {
                    val aliasCompact = alias.replace(Regex("[^a-zA-Z0-9\\u0900-\\u097F]"), "")
                    val sim = similarityRatio(compactQuery, aliasCompact)
                    if (sim > maxAliasSim) maxAliasSim = sim
                }

                val bestSim = maxOf(directSim, maxAliasSim)
                if (bestSim >= 0.70) {
                    val score = bestSim * 0.90
                    if (score > bestScore) {
                        bestScore = score
                        bestType = "FUZZY_LEVENSHTEIN"
                    }
                }
            }

            // 7. Phonetic Soundex Match (for English/Hinglish acoustic similarity)
            if (bestScore in 0.50..0.85 && querySoundex.isNotEmpty() && app.soundexCode.isNotEmpty()) {
                if (querySoundex == app.soundexCode) {
                    bestScore += 0.08
                    bestType += "+SOUNDEX"
                }
            }

            if (bestScore >= 0.60) {
                candidates.add(AppMatchCandidate(app, bestScore, bestType))
            }
        }

        return candidates.sortedByDescending { it.score }
    }

    private fun buildAppRecord(label: String, packageName: String): InstalledAppRecord {
        val cleanLabel = label.lowercase(Locale.ROOT).trim()
        val compactLabel = cleanLabel.replace(Regex("[^a-zA-Z0-9\\u0900-\\u097F]"), "")
        val tokens = cleanLabel.split(Regex("[\\s\\-_.]+")).filter { it.isNotBlank() }
        val soundexCode = soundex(compactLabel)

        val aliases = mutableListOf<String>()

        // 1. Token segmentations for camelCase or multi-word (e.g. "WhatsApp" -> "whats app", "YouTube" -> "you tube")
        val splitCamel = label.replace(Regex("(?<=[a-z])(?=[A-Z])"), " ").lowercase(Locale.ROOT).trim()
        if (splitCamel != cleanLabel) {
            aliases.add(splitCamel)
        }

        // 2. Non-brand main words for multi-word apps (e.g. "Google Maps" -> "maps", "Microsoft Teams" -> "teams")
        val brandPrefixes = listOf("google ", "microsoft ", "android ", "samsung ", "mi ", "apple ", "meta ")
        for (bp in brandPrefixes) {
            if (cleanLabel.startsWith(bp)) {
                val sub = cleanLabel.substring(bp.length).trim()
                if (sub.isNotBlank()) aliases.add(sub)
            }
        }

        // 3. Conservative known aliases & Hindi transliterations for popular apps
        val knownMap = mapOf(
            "youtube" to listOf("youtube", "you tube", "u tube", "utube", "yt", "यूट्यूब", "यू ट्यूब"),
            "whatsapp" to listOf("whatsapp", "whats app", "what's app", "what s app", "watsapp", "watsap", "wa", "व्हाट्सएप", "व्हाट्स ऐप"),
            "chrome" to listOf("chrome", "crome", "chrom", "google chrome", "क्रोम", "गूगल क्रोम"),
            "upstox" to listOf("upstox", "up stock", "up stocks", "upstox pro", "अपस्टॉक्स"),
            "spotify" to listOf("spotify", "spotifi", "spotfy", "स्पॉटिफाई"),
            "instagram" to listOf("instagram", "insta", "इंस्टाग्राम", "इंस्टा"),
            "facebook" to listOf("facebook", "fb", "फेसबुक"),
            "telegram" to listOf("telegram", "tg", "टेलीग्राम"),
            "netflix" to listOf("netflix", "नेटफ्लिक्स"),
            "calculator" to listOf("calculator", "calc", "calci", "कैलकुलेटर"),
            "camera" to listOf("camera", "कैमरा"),
            "gallery" to listOf("gallery", "photos", "photo", "गैलरी", "फोटो"),
            "maps" to listOf("maps", "map", "google maps", "मैप्स", "गूगल मैप्स"),
            "gmail" to listOf("gmail", "email", "mail", "जीमेल", "ईमेल"),
            "clock" to listOf("clock", "alarm", "alarms", "timer", "घड़ी", "अलार्म")
        )

        for ((key, list) in knownMap) {
            if (compactLabel.contains(key) || packageName.lowercase(Locale.ROOT).contains(key)) {
                aliases.addAll(list)
            }
        }

        return InstalledAppRecord(
            packageName = packageName,
            appLabel = label,
            normalizedLabel = cleanLabel,
            compactLabel = compactLabel,
            tokens = tokens,
            aliases = aliases.distinct(),
            soundexCode = soundexCode
        )
    }

    private fun sanitizeAppNameQuery(raw: String): String {
        var clean = raw.lowercase(Locale.ROOT).trim()

        val prefixes = listOf(
            "open the app ", "open app ", "open the ", "open ",
            "launch the app ", "launch app ", "launch ", "start ",
            "please open ", "can you open ", "go to ",
            "khol do ", "khol de ", "kholo ", "khol ",
            "ऐप खोलो ", "ऐप चालू करो ", "एप खोलो ", "खोलो "
        )
        for (prefix in prefixes) {
            if (clean.startsWith(prefix)) {
                clean = clean.substring(prefix.length).trim()
            }
        }

        val suffixes = listOf(
            " kholo", " khol do", " khol de", " kholna", " kholiye",
            " open karo", " open kar do", " open kardo", " open kar",
            " chalu karo", " chalu kar do", " chalu kar", " chalao", " start karo",
            " launch karo", " shuru karo", " app", " application", " ko kholo", " ko open karo",
            " खोलो", " खोल दो", " खोल दे", " खोलिये", " खोलिए", " चालू करो", " चालू कर दो",
            " चलाओ", " शुरू करो", " ओपन करो", " स्टार्ट करो", " ऐप", " एप", " को खोलो", " को चालू करो"
        )
        for (suffix in suffixes) {
            if (clean.endsWith(suffix)) {
                clean = clean.substring(0, clean.length - suffix.length).trim()
            }
        }

        return clean.trim()
    }

    private fun similarityRatio(s1: String, s2: String): Double {
        if (s1 == s2) return 1.0
        val maxLen = maxOf(s1.length, s2.length)
        if (maxLen == 0) return 1.0
        val dist = levenshteinDistance(s1, s2)
        return 1.0 - (dist.toDouble() / maxLen.toDouble())
    }

    private fun levenshteinDistance(s1: String, s2: String): Int {
        val m = s1.length
        val n = s2.length
        val dp = Array(m + 1) { IntArray(n + 1) }

        for (i in 0..m) dp[i][0] = i
        for (j in 0..n) dp[0][j] = j

        for (i in 1..m) {
            for (j in 1..n) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                dp[i][j] = minOf(
                    dp[i - 1][j] + 1,
                    dp[i][j - 1] + 1,
                    dp[i - 1][j - 1] + cost
                )
            }
        }
        return dp[m][n]
    }

    /**
     * Standard 4-character American Soundex algorithm for phonetic indexing of Latin tokens.
     */
    private fun soundex(s: String): String {
        val upper = s.uppercase(Locale.ROOT).replace(Regex("[^A-Z]"), "")
        if (upper.isEmpty()) return ""

        val first = upper[0]
        val sb = StringBuilder().append(first)

        fun getCode(c: Char): Char {
            return when (c) {
                'B', 'F', 'P', 'V' -> '1'
                'C', 'G', 'J', 'K', 'Q', 'S', 'X', 'Z' -> '2'
                'D', 'T' -> '3'
                'L' -> '4'
                'M', 'N' -> '5'
                'R' -> '6'
                else -> '0'
            }
        }

        var lastCode = getCode(first)
        for (i in 1 until upper.length) {
            val code = getCode(upper[i])
            if (code != '0' && code != lastCode) {
                sb.append(code)
                lastCode = code
                if (sb.length == 4) break
            } else if (code == '0') {
                lastCode = '0'
            }
        }

        while (sb.length < 4) {
            sb.append('0')
        }
        return sb.toString()
    }
}
