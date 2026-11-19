package com.novelreader.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

data class Chapter(val title: String, val content: String)

class NovelStorage(private val context: Context) {

    companion object {
        const val SAMPLE_BOOK_ID = "__sample_demo__"
        const val SAMPLE_BOOK_TITLE = "使用说明与插图测试（示例）"

        private val CHAPTER_REGEX = Regex(
            """^\s*(?:第\s*[0-9零一二三四五六七八九十百千万两〇○]+\s*[章节回话卷部篇集节幕]|(?:序章|序幕|楔子|引子|尾声|终章|后记|番外|外传|间章|幕间)(?:[\s:：\-—].*)?|(?:Chapter|Episode|Part|Vol\.?|Volume|EP\.?)\s*\d+|Prologue|Epilogue|(?:【|\[)\s*(?:第\s*\d+|EP\.?\s*\d+|番外|序章)[^】\]]*(?:】|\]))\S*.{0,60}$""",
            RegexOption.IGNORE_CASE
        )

        private val SAMPLE_TEXT = """
第一章 手机竖屏阅读与插图测试

欢迎使用竖屏 TXT 小说阅读器安卓版！点击左上角「书架」或右上角「打开」，即可导入手机里的任意 .txt 小说文件（自动识别 UTF-8、GBK/GB18030、Big5、UTF-16 编码，不乱码）。

下方是 Novelpia 内嵌插图标签的实测示例，软件会自动通过原生代理抓取并本地缓存，即使后缀是 .file 也能正常高清渲染：

[图片: https://images.novelpia.com/imagebox/c6/c6f8637cb88d064777eaa0a4c9e6de34_1579171_1782548146_ori.file]

点击上方图片可全屏放大查看；如果网络波动导致加载失败，点击「重新加载」即可重试。

第二章 手机阅读手势与技巧

• 点击屏幕正文空白处：快速显示或隐藏顶部与底部工具栏
• 音量键上下翻页：可在「设置」中开启或关闭音量键翻页
• 5 套护眼主题：纸墨、护眼绿、纯净白、夜灰、纯黑 OLED 省电模式
• 自动记录进度：每次退出或切换章节都会自动保存阅读位置

现在，点击右上角「打开」导入你的 TXT 小说开始阅读吧！
""".trimIndent()
    }

    private val booksDir = File(context.filesDir, "books").apply { mkdirs() }
    private val imageCacheDir = File(context.cacheDir, "image_cache").apply { mkdirs() }
    private val stateFile = File(context.filesDir, "state.json")
    private val chapterCache = ConcurrentHashMap<String, List<Chapter>>()

    @Synchronized
    fun loadState(): JSONObject {
        val defaultSettings = JSONObject().apply {
            put("theme", "paper")
            put("fontFamily", "sans-serif")
            put("fontSize", 19)
            put("lineHeight", 1.85)
            put("paraSpacing", 1.0)
            put("paddingX", 18)
            put("textIndent", true)
            put("autoLoadImages", true)
            put("volumeKeys", true)
        }
        if (!stateFile.exists()) {
            return JSONObject().apply {
                put("books", JSONArray())
                put("last_book_id", SAMPLE_BOOK_ID)
                put("settings", defaultSettings)
            }
        }
        return try {
            val obj = JSONObject(stateFile.readText(Charsets.UTF_8))
            if (!obj.has("books")) obj.put("books", JSONArray())
            if (!obj.has("last_book_id")) obj.put("last_book_id", SAMPLE_BOOK_ID)
            val s = obj.optJSONObject("settings") ?: JSONObject()
            val keys = defaultSettings.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                if (!s.has(k)) s.put(k, defaultSettings.get(k))
            }
            obj.put("settings", s)
            obj
        } catch (_: Exception) {
            JSONObject().apply {
                put("books", JSONArray())
                put("last_book_id", SAMPLE_BOOK_ID)
                put("settings", defaultSettings)
            }
        }
    }

    @Synchronized
    fun saveState(state: JSONObject) {
        val tmp = File(context.filesDir, "state.json.tmp")
        tmp.writeText(state.toString(), Charsets.UTF_8)
        tmp.renameTo(stateFile)
    }

    fun updateSettings(settingsJson: String) {
        val newSettings = JSONObject(settingsJson)
        val state = loadState()
        val cur = state.getJSONObject("settings")
        val keys = newSettings.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            cur.put(k, newSettings.get(k))
        }
        saveState(state)
    }

    fun saveProgress(bookId: String, chapter: Int, scrollRatio: Double) {
        val state = loadState()
        state.put("last_book_id", bookId)
        val books = state.getJSONArray("books")
        for (i in 0 until books.length()) {
            val b = books.getJSONObject(i)
            if (b.optString("id") == bookId) {
                b.put("chapter", chapter)
                b.put("scrollRatio", scrollRatio)
                b.put("updatedAt", System.currentTimeMillis() / 1000)
                break
            }
        }
        saveState(state)
    }

    fun importTxt(filename: String, rawBytes: ByteArray): JSONObject {
        val text = detectAndDecode(rawBytes)
        val title = filename.removeSuffix(".txt").removeSuffix(".TXT").ifBlank { "未命名小说" }
        val bookId = md5Hex("$title:${text.length}").take(12)
        File(booksDir, "$bookId.txt").writeText(text, Charsets.UTF_8)

        val chapters = parseChapters(text)
        chapterCache[bookId] = chapters

        val state = loadState()
        val books = state.getJSONArray("books")
        var found = false
        for (i in 0 until books.length()) {
            val b = books.getJSONObject(i)
            if (b.optString("id") == bookId) {
                b.put("title", title)
                b.put("chapters", chapters.size)
                b.put("updatedAt", System.currentTimeMillis() / 1000)
                found = true
                break
            }
        }
        if (!found) {
            val newBooks = JSONArray()
            newBooks.put(JSONObject().apply {
                put("id", bookId)
                put("title", title)
                put("chapters", chapters.size)
                put("chapter", 0)
                put("scrollRatio", 0.0)
                put("updatedAt", System.currentTimeMillis() / 1000)
            })
            for (i in 0 until books.length()) {
                newBooks.put(books.get(i))
            }
            state.put("books", newBooks)
        }
        state.put("last_book_id", bookId)
        saveState(state)

        return JSONObject().apply {
            put("id", bookId)
            put("title", title)
            put("chapters", chapters.size)
        }
    }

    fun deleteBook(bookId: String) {
        chapterCache.remove(bookId)
        File(booksDir, "$bookId.txt").delete()
        val state = loadState()
        val books = state.getJSONArray("books")
        val kept = JSONArray()
        for (i in 0 until books.length()) {
            val b = books.getJSONObject(i)
            if (b.optString("id") != bookId) kept.put(b)
        }
        state.put("books", kept)
        if (state.optString("last_book_id") == bookId) {
            state.put("last_book_id", SAMPLE_BOOK_ID)
        }
        saveState(state)
    }

    fun getBookTocJson(bookId: String): JSONObject {
        val chapters = getChapters(bookId)
        val state = loadState()
        val books = state.getJSONArray("books")
        var title = if (bookId == SAMPLE_BOOK_ID) SAMPLE_BOOK_TITLE else bookId
        var savedChapter = 0
        var scrollRatio = 0.0
        for (i in 0 until books.length()) {
            val b = books.getJSONObject(i)
            if (b.optString("id") == bookId) {
                title = b.optString("title", title)
                savedChapter = b.optInt("chapter", 0)
                scrollRatio = b.optDouble("scrollRatio", 0.0)
                break
            }
        }
        val titlesArr = JSONArray()
        chapters.forEach { titlesArr.put(it.title) }
        return JSONObject().apply {
            put("id", bookId)
            put("title", title)
            put("chapters", titlesArr)
            put("chapter", savedChapter)
            put("scrollRatio", scrollRatio)
        }
    }

    fun getChapterJson(bookId: String, index: Int): JSONObject {
        val chapters = getChapters(bookId)
        val safeIdx = index.coerceIn(0, (chapters.size - 1).coerceAtLeast(0))
        val ch = chapters[safeIdx]
        return JSONObject().apply {
            put("title", ch.title)
            put("content", ch.content)
        }
    }

    fun clearImageCache(): Int {
        var count = 0
        imageCacheDir.listFiles()?.forEach { f ->
            if (f.delete() && f.name.endsWith(".bin")) count++
        }
        return count
    }

    fun fetchAndCacheImage(urlStr: String, forceRefresh: Boolean): Pair<ByteArray, String> {
        val parsed = URL(urlStr)
        val key = sha256Hex(urlStr)
        val binFile = File(imageCacheDir, "$key.bin")
        val mimeFile = File(imageCacheDir, "$key.mime")

        if (!forceRefresh && binFile.exists() && mimeFile.exists()) {
            return Pair(binFile.readBytes(), mimeFile.readText(Charsets.UTF_8).trim())
        }

        val host = parsed.host.lowercase()
        val referer = if (host.contains("novelpia.com")) {
            "https://novelpia.com/"
        } else {
            "${parsed.protocol}://${parsed.host}/"
        }

        val conn = (parsed.openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 20000
            instanceFollowRedirects = true
            setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"
            )
            setRequestProperty("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
            setRequestProperty("Referer", referer)
        }

        val bytes = conn.inputStream.use { it.readBytes() }
        val respMime = conn.contentType ?: ""
        conn.disconnect()

        val mime = sniffImageMime(bytes, respMime)
        val tmp = File(imageCacheDir, "$key.tmp")
        tmp.writeBytes(bytes)
        tmp.renameTo(binFile)
        mimeFile.writeText(mime, Charsets.UTF_8)
        return Pair(bytes, mime)
    }

    private fun getChapters(bookId: String): List<Chapter> {
        chapterCache[bookId]?.let { return it }
        if (bookId == SAMPLE_BOOK_ID) {
            val chs = parseChapters(SAMPLE_TEXT)
            chapterCache[bookId] = chs
            return chs
        }
        val file = File(booksDir, "$bookId.txt")
        if (!file.exists()) {
            return parseChapters(SAMPLE_TEXT)
        }
        val chs = parseChapters(file.readText(Charsets.UTF_8))
        chapterCache[bookId] = chs
        return chs
    }

    private fun parseChapters(raw: String): List<Chapter> {
        val lines = raw.replace("\r\n", "\n").replace("\r", "\n").split("\n")
        val chapters = mutableListOf<Chapter>()
        var curTitle = "正文"
        val curLines = mutableListOf<String>()
        var foundChapter = false

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isNotEmpty() && trimmed.length <= 65 && CHAPTER_REGEX.matches(trimmed)) {
                if (curLines.any { it.isNotBlank() }) {
                    chapters.add(Chapter(curTitle, curLines.joinToString("\n")))
                } else if (foundChapter) {
                    chapters.add(Chapter(curTitle, ""))
                }
                curTitle = trimmed
                curLines.clear()
                foundChapter = true
            } else {
                curLines.add(line)
            }
        }
        if (curLines.any { it.isNotBlank() } || chapters.isEmpty()) {
            chapters.add(Chapter(curTitle, curLines.joinToString("\n")))
        }

        if (chapters.size == 1) {
            val allLines = chapters[0].content.split("\n")
            val nonEmpty = allLines.count { it.isNotBlank() }
            if (nonEmpty > 400) {
                val chunked = mutableListOf<Chapter>()
                val buf = mutableListOf<String>()
                var count = 0
                var part = 1
                for (l in allLines) {
                    buf.add(l)
                    if (l.isNotBlank()) count++
                    if (count >= 300) {
                        chunked.add(Chapter("第 $part 节", buf.joinToString("\n")))
                        buf.clear()
                        count = 0
                        part++
                    }
                }
                if (buf.any { it.isNotBlank() }) {
                    chunked.add(Chapter("第 $part 节", buf.joinToString("\n")))
                }
                return chunked
            }
        }
        return chapters
    }

    private fun detectAndDecode(raw: ByteArray): String {
        if (raw.size >= 3 && raw[0] == 0xEF.toByte() && raw[1] == 0xBB.toByte() && raw[2] == 0xBF.toByte()) {
            return String(raw, 3, raw.size - 3, Charsets.UTF_8)
        }
        if (raw.size >= 2) {
            if ((raw[0] == 0xFF.toByte() && raw[1] == 0xFE.toByte()) ||
                (raw[0] == 0xFE.toByte() && raw[1] == 0xFF.toByte())
            ) {
                return String(raw, Charsets.UTF_16)
            }
        }
        val charsets = listOf("UTF-8", "GB18030", "GBK", "Big5", "Shift_JIS", "EUC-KR")
        for (name in charsets) {
            try {
                val cs = Charset.forName(name)
                val decoder = cs.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                return decoder.decode(ByteBuffer.wrap(raw)).toString()
            } catch (_: Exception) {
                // try next encoding
            }
        }
        return String(raw, Charsets.UTF_8)
    }

    private fun sniffImageMime(data: ByteArray, fallback: String): String {
        if (data.size >= 12) {
            if (data[0] == 0xFF.toByte() && data[1] == 0xD8.toByte() && data[2] == 0xFF.toByte()) {
                return "image/jpeg"
            }
            if (data[0] == 0x89.toByte() && data[1] == 0x50.toByte() && data[2] == 0x4E.toByte() && data[3] == 0x47.toByte()) {
                return "image/png"
            }
            val header6 = String(data, 0, 6, Charsets.US_ASCII)
            if (header6 == "GIF87a" || header6 == "GIF89a") {
                return "image/gif"
            }
            val riff = String(data, 0, 4, Charsets.US_ASCII)
            val webp = String(data, 8, 4, Charsets.US_ASCII)
            if (riff == "RIFF" && webp == "WEBP") {
                return "image/webp"
            }
            if (data[0] == 0x42.toByte() && data[1] == 0x4D.toByte()) {
                return "image/bmp"
            }
            val ftyp = String(data, 4, minOf(12, data.size - 4), Charsets.US_ASCII)
            if (ftyp.contains("ftypavif") || ftyp.contains("ftypavis")) {
                return "image/avif"
            }
        }
        if (fallback.lowercase().startsWith("image/")) {
            return fallback.substringBefore(";").trim()
        }
        return "image/jpeg"
    }

    private fun md5Hex(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun sha256Hex(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
