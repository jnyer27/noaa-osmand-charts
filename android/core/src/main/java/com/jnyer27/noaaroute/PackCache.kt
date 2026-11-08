package com.jnyer27.noaaroute

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Route packs on disk and the published catalog they come from: fetch the catalog
 * (the last copy stays usable offline), download packs checked against the catalog's
 * SHA-256, update installed packs and swap replaced ones. Plain JVM, so the app
 * (through PackStore) and the command-line planner share it. Blocking calls.
 *
 * [base] is the release download URL ending in "/" (BuildConfig.PACK_BASE in the app).
 */
class PackCache(val dir: File, private val catalogFile: File, private val base: String) {
    init { dir.mkdirs() }

    companion object {
        fun httpGet(url: String): HttpURLConnection {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 20000
            c.readTimeout = 60000
            c.instanceFollowRedirects = true
            if (c.responseCode != 200) throw Exception("HTTP ${c.responseCode} for $url")
            return c
        }

        /**
         * Download [url] to [dest], checked against [sha256] before it replaces [dest]
         * (anything still reading the old file keeps it until reopened). [progress] gets
         * (bytes so far, [size]) every 500 KB.
         */
        fun fetchChecked(url: String, dest: File, sha256: String, size: Long, progress: (Long, Long) -> Unit = { _, _ -> }) {
            // A name of our own: the app and the background job may both be at it
            val part = File(dest.parentFile, "${dest.name}.${System.nanoTime()}.part")
            try {
                val md = MessageDigest.getInstance("SHA-256")
                httpGet(url).inputStream.use { inp ->
                    part.outputStream().use { out ->
                        val buf = ByteArray(1 shl 16)
                        var got = 0L
                        var last = 0L
                        while (true) {
                            val r = inp.read(buf)
                            if (r < 0) break
                            out.write(buf, 0, r)
                            md.update(buf, 0, r)
                            got += r
                            if (got - last > 500_000) { last = got; progress(got, size) }
                        }
                    }
                }
                val sha = md.digest().joinToString("") { "%02x".format(it) }
                if (sha != sha256) throw Exception("download check failed, try again")
                if (!part.renameTo(dest)) throw Exception("could not save ${dest.name}")
            } finally {
                part.delete()
            }
        }
    }

    /** The published catalog; the last copy fetched stays usable offline. */
    fun fetchCatalog(): List<AreaInfo> {
        val text = httpGet(base + "catalog.json").inputStream.bufferedReader().readText()
        val list = Regions.parseCatalog(text)
        catalogFile.parentFile?.mkdirs()
        catalogFile.writeText(text)
        return list
    }

    fun cachedCatalog(): List<AreaInfo> = try { Regions.parseCatalog(catalogFile.readText()) } catch (_: Exception) { emptyList() }

    /** Installed pack ids and build times, read from the files' headers. */
    fun installed(): Map<String, String> {
        val out = HashMap<String, String>()
        for (f in dir.listFiles { f -> f.name.endsWith(".bin") }.orEmpty()) {
            try { Pack(f).use { out[it.areaId] = it.created } } catch (_: Exception) { }
        }
        return out
    }

    /** Download a pack (checked against the catalog's SHA-256) and put it in place. */
    fun download(a: AreaInfo, progress: (Long, Long) -> Unit = { _, _ -> }) {
        try {
            fetchChecked(base + a.file, File(dir, "${a.id}.bin"), a.sha256, a.size, progress)
        } catch (e: Exception) {
            if (e.message?.startsWith("could not save") == true) throw Exception("could not save ${a.file}")
            throw e
        }
    }

    fun delete(id: String) { File(dir, "$id.bin").delete() }

    /** Delete replaced packs whose replacements are all installed. True if anything went. */
    fun dropReplaced(catalog: List<AreaInfo>): Boolean {
        var gone = false
        for ((old, missing) in Regions.replacements(catalog, installed().keys)) {
            if (missing.isEmpty()) { delete(old); gone = true }
        }
        return gone
    }

    /**
     * Newer builds of installed packs, then the replacements of a replaced pack,
     * then drop the replaced pack. Returns the number of packs downloaded.
     */
    fun updateAll(): Int {
        val catalog = fetchCatalog()
        val have = installed()
        var n = 0
        for (a in catalog) {
            val built = have[a.id] ?: continue
            if (a.created > built && !a.deprecated) { download(a); n++ }
        }
        for ((_, missing) in Regions.replacements(catalog, installed().keys)) for (a in missing) { download(a); n++ }
        dropReplaced(catalog)
        return n
    }

    /** Changes when packs are added, updated or deleted (the background job may do it). */
    fun signature() = dir.listFiles { f -> f.name.endsWith(".bin") }.orEmpty()
        .sortedBy { it.name }.joinToString { "${it.name}:${it.lastModified()}:${it.length()}" }
}
