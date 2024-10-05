package diy.rcache.lru.disk

import android.util.Log
import java.io.File
import java.io.FileReader
import java.io.FileWriter
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.time.Instant
import java.util.UUID
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.io.path.Path
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async

private const val MAGIC = "R2D2"
private const val version = "v1.0"
private const val defaultCacheDataDir = "rcache"
private const val PATH_SEPARATOR = "/"
private const val TAG = "RDiskLruCache"
private const val journalFileName = "rjournal.bin"
private val DEFAULT_CACHE_LOCATION = File(defaultCacheDataDir)
private const val DEFAULT_CACHE_SIZE = 1024L * 1024L * 50L

interface Cacheable {
  fun fetch(key: String): ByteArray?
  fun store(key: String, value: ByteArray)
  fun delete(key: String)

  fun clearMemoryCache()
  fun memCacheSize(): Long

  suspend fun fileCacheSize(): Deferred<Long>
  suspend fun clearAll(): Deferred<Boolean>
  suspend fun flush(): Deferred<Boolean>
}

internal enum class CacheEntryState {
  UPDATED, ACCESSED, SYNCED, DELETED
}

internal class RCacheConfig(sizeOnDisk: Long = DEFAULT_CACHE_SIZE, sizeInMemory: Long = DEFAULT_CACHE_SIZE /4, cacheLocation: File = DEFAULT_CACHE_LOCATION) {
  val maxSizeOnDisk = if(sizeOnDisk <= 0) DEFAULT_CACHE_SIZE else sizeOnDisk
  val maxSizeInMemory = if(sizeInMemory <= 0) DEFAULT_CACHE_SIZE /4 else sizeInMemory.coerceAtMost(sizeOnDisk)
  val journalFileLocation = File(cacheLocation.absolutePath + PATH_SEPARATOR + "jrnl")
  val journalFile = File(journalFileLocation.absolutePath + PATH_SEPARATOR + journalFileName)
  var cacheFolder = File(cacheLocation.absolutePath + PATH_SEPARATOR + defaultCacheDataDir)
}

internal class CacheEntry {

  internal var key: String
  internal var bytes: ByteArray? = null
  internal var cacheEntryState: CacheEntryState = CacheEntryState.UPDATED
  private var _lastAccessed = Instant.now()
  internal var cacheFile: File? = null

  internal var lastAccessed: Instant
    set(value) {
      cacheEntryState = CacheEntryState.ACCESSED
      _lastAccessed = value
    }
    get() {
      return _lastAccessed
    }

  var size: Long = 0
    get() {
      return (bytes?.size ?: 0).toLong()
    }

  constructor(key: String, bytes: ByteArray) {
    this.bytes = bytes
    this.key = key
  }

 private constructor(key: String, cacheFile: File, state: CacheEntryState) : this(key, cacheFile.readBytes()) {
    this.cacheEntryState = state
    this._lastAccessed = Instant.now()
    this.cacheFile = cacheFile
  }

  internal companion object {

    fun createEntryFromDisk(key: String, file: File): CacheEntry {
      return CacheEntry(key = key, file, CacheEntryState.SYNCED)
    }
  }

}

class RDiskLRUCache internal constructor(internal val cacheConfig: RCacheConfig = RCacheConfig()) :
  Cacheable {

  private var lruMap = mutableMapOf<String, CacheEntry>()
  private val readWriteLock = ReentrantReadWriteLock()

  private var backgroundScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

  init {
    if (!cacheConfig.cacheFolder.exists()) {
      cacheConfig.cacheFolder.mkdirs()
    }
    recoverJournalIfNeeded()
    createJournalIfNeeded()
  }

  override fun fetch(key: String): ByteArray? {
    var value: CacheEntry? = null
    try {
      readWriteLock.writeLock().lock()

      if (lruMap[key]?.cacheEntryState == CacheEntryState.DELETED) {
        return null
      }

      value = lruMap[key]

      if (value == null) {
        value = readFileFromDisk(key)
      } else {
        lruMap.remove(key)
      }

      //put it back in the map for in memory lru handling
      if (value != null) {
        lruMap[value.key] = value
        lruMap[value.key]?.lastAccessed = Instant.now()
      }

      //is mem cache size greater than set size?
      //evict the oldest entry from the cache
      var memCacheSize = this.memCacheSize()
      while (memCacheSize > this.cacheConfig.maxSizeInMemory) {
        val entry = lruMap.entries.iterator().next()
        lruMap.remove(entry.key)
        memCacheSize -= entry.value.size
      }

    } finally {
      readWriteLock.writeLock().unlock()
    }
    return value?.bytes
  }

  override fun store(key: String, value: ByteArray) {
    // set the lastAccessed time
    try {
      readWriteLock.writeLock().lock()

      val cacheValue = CacheEntry(key, value)
      lruMap[key] = cacheValue
      lruMap[key]?.let {
        it.lastAccessed = Instant.now()
        it.cacheEntryState = CacheEntryState.UPDATED
      }
    } finally {
      readWriteLock.writeLock().unlock()
    }
  }

  override fun delete(key: String) {
    try {
      readWriteLock.writeLock().lock()
      var entry = lruMap[key]

      if (entry == null) {
        entry = readFileFromDisk(key)
        entry?.let {
          it.cacheEntryState = CacheEntryState.DELETED
          lruMap[entry.key] = entry
        }
      }

      lruMap[key]?.let {
        it.cacheEntryState = CacheEntryState.DELETED
      }
    } finally {
      readWriteLock.writeLock().unlock()
    }
  }

  override fun clearMemoryCache() {
    try {
      readWriteLock.writeLock().lock()
      lruMap.clear()
    } finally {
      readWriteLock.writeLock().unlock()
    }
  }

  override fun memCacheSize(): Long {
    try {
      readWriteLock.writeLock().lock()
      return lruMap.entries.fold(0L) { acc, entry -> acc + entry.value.size}
    } finally {
      readWriteLock.writeLock().unlock()
    }
  }

  override suspend fun fileCacheSize(): Deferred<Long> {
    return backgroundScope.async {
      try {
        readWriteLock.writeLock().lock()
        val files = cacheConfig.cacheFolder.listFiles()
        var cacheSize = 0L
        cacheSize = files?.fold(0L) { acc, file -> acc + file.length() } ?: 0L
        cacheSize
      } finally {
        readWriteLock.writeLock().unlock()
      }
    }
  }

  override suspend fun clearAll(): Deferred<Boolean> {
    return backgroundScope.async { removeAllFromDisk() }
  }

  override suspend fun flush(): Deferred<Boolean> = this.backgroundScope.async {
    val result = flushToDisk()
    val cacheSize = fileCacheSize().await()
    purgeOldestEntries(cacheSize)
    result
  }

  internal fun fileInCache(key: String) : File? {
    return lruMap[key]?.cacheFile
  }


  private fun removeAllFromDisk(): Boolean {
    try {
      readWriteLock.writeLock().lock()
      lruMap.clear()

      cacheConfig.cacheFolder.deleteRecursively()

      if (!cacheConfig.cacheFolder.exists())
        cacheConfig.cacheFolder.mkdirs()

      if (!cacheConfig.journalFileLocation.exists())
        cacheConfig.journalFileLocation.mkdirs()

      if (cacheConfig.journalFile.exists())
        cacheConfig.journalFile.delete()

      createJournalIfNeeded()
    } finally {
      readWriteLock.writeLock().unlock()
    }
    return true
  }

  fun readJournal(): List<String> {
    try {
      this.readWriteLock.writeLock().lock()
      FileReader(cacheConfig.journalFile).use {
        return it.readLines()
      }
    } finally {
      readWriteLock.writeLock().unlock()
    }
  }

  private fun flushToDisk(): Boolean {
    try {
      this.readWriteLock.writeLock().lock()
      val allUpdates =
        lruMap.filter { it.value.cacheEntryState != CacheEntryState.SYNCED }

      allUpdates.forEach {
        when (it.value.cacheEntryState) {
          CacheEntryState.UPDATED -> {
            val uuid = UUID.randomUUID()
            markUpdateInJournal(it.value.key, uuid)
            writeEntryOnDisk(it.value)
            lruMap[it.value.key]?.cacheEntryState = CacheEntryState.SYNCED
            markCommitInJournal(it.value.key, uuid)
          }

          CacheEntryState.DELETED -> {
            val uuid = UUID.randomUUID()
            markDeleteInJournal(it.value.key, uuid)
            removeEntryOnDisk(it.value)
            markCommitInJournal(it.value.key, uuid)
            lruMap.remove(it.value.key)
          }

          CacheEntryState.ACCESSED -> {
            Files.setLastModifiedTime(
              it.value.cacheFile?.absolutePath?.let { it1 -> Path(it1) },
              FileTime.from(it.value.lastAccessed)
            )
            lruMap[it.value.key]?.cacheEntryState = CacheEntryState.SYNCED
          }

          CacheEntryState.SYNCED -> {/* Do Nothing */
          }
        }
      }

      return true
    } finally {
      readWriteLock.writeLock().unlock()
    }
  }

  private fun writeEntryOnDisk(entry: CacheEntry) {
    val cacheFile = File(cacheConfig.cacheFolder.absolutePath + PATH_SEPARATOR + entry.key)
    entry.bytes?.let { cacheFile.writeBytes(it) }
    entry.cacheFile = cacheFile
  }

  private fun removeEntryOnDisk(entry: CacheEntry) {
    val cacheFile = File(cacheConfig.cacheFolder.absolutePath + PATH_SEPARATOR + entry.key)
    if (cacheFile.exists())
      cacheFile.delete()
  }

  private fun readFileFromDisk(key: String): CacheEntry? {
    val cacheFile = File(cacheConfig.cacheFolder.absolutePath + PATH_SEPARATOR + key)
    var entry: CacheEntry? = null

    if (cacheFile.exists()) {
      entry = CacheEntry.createEntryFromDisk(key, cacheFile)
    }
    return entry
  }

  private fun purgeOldestEntries(currentCacheSize: Long) {
    var cacheSize = currentCacheSize
    val files = cacheConfig.cacheFolder.listFiles()?.toList() ?: emptyList()
    val sortedFiles = files.sortedBy { it.lastModified() }.filter { !lruMap.containsKey(it.name) }
    var indx = 0
    while (cacheSize > cacheConfig.maxSizeOnDisk) {
      cacheSize -= sortedFiles[indx].length()
      sortedFiles[indx].delete()
      indx += 1
    }
  }

  private fun markUpdateInJournal(key: String, uuid: UUID) {
    FileWriter(cacheConfig.journalFile, true).use {
      it.append("\nW: ${uuid.toString()} $key ${Instant.now()}")
      it.flush()
    }
  }

  private fun markCommitInJournal(key: String, uuid: UUID) {
    FileWriter(cacheConfig.journalFile, true).use {
      it.append("\nC: ${uuid.toString()}")
      it.flush()
    }
  }

  private fun markDeleteInJournal(key: String, uuid: UUID) {

    FileWriter(cacheConfig.journalFile, true).use {
      it.append("\nD: ${uuid.toString()} $key ${Instant.now()}")
      it.flush()
    }
  }

  private fun recoverJournalIfNeeded() {
    if (!cacheConfig.journalFile.exists())
      return

    val cacheLines = cacheConfig.journalFile.readLines()

    val incompleteEntries = mutableMapOf<String, String>()
    cacheLines.forEach { line ->
      val parts = line.split(" ")
      when (parts[0]) {
        "W" -> {
          val uuid = parts[1]
          val key = parts[2]
          val file = File(key)
          incompleteEntries[uuid] = parts[2]
          // currentSize += file.length()
        }

        "R" -> {
          val uuid = parts[1]
          val key = parts[2]
          incompleteEntries[uuid] = parts[2]
        }

        "C" -> {
          val uuid = parts[1]
          incompleteEntries.remove(uuid)
        }
      }
      incompleteEntries.forEach {
        val file = File(cacheConfig.cacheFolder.absolutePath + PATH_SEPARATOR + it.value)
        if (file.exists())
          file.delete()
      }
      createJournalIfNeeded()
    }

  }

  private fun createJournalIfNeeded() {

    if (!cacheConfig.journalFileLocation.exists()) {
      cacheConfig.journalFileLocation.mkdirs()
    }

    if (cacheConfig.journalFile.exists()) {
      cacheConfig.journalFile.delete()
    }

    try {
      cacheConfig.journalFile.createNewFile()
      FileWriter(cacheConfig.journalFile, true).use {
        it.append(MAGIC)
        it.append(version)
        it.flush()
      }
    } catch (exc: Exception) {
      Log.e(
        TAG,
        "Could not create journal file ${cacheConfig.journalFile.absolutePath} ${exc.message}"
      )
    }
  }

  companion object Builder {
    class RDiskLRUCacheBuilder {
      private var cacheSizeOnDisk: Long = DEFAULT_CACHE_SIZE
      private var cacheSizeInMem: Long = DEFAULT_CACHE_SIZE /4
      private var cacheLocation: File = DEFAULT_CACHE_LOCATION

      fun maxSizeOnDisk(size: Long): RDiskLRUCacheBuilder {
        this.cacheSizeOnDisk = size
        return this
      }

      fun maxSizeInMem(size: Long): RDiskLRUCacheBuilder {
        this.cacheSizeInMem = size
        return this
      }

      fun cacheLocation(location: File): RDiskLRUCacheBuilder {
        this.cacheLocation = location
        return this
      }

      fun build(): RDiskLRUCache {
        return RDiskLRUCache(RCacheConfig(cacheSizeOnDisk, cacheSizeInMem, cacheLocation))
      }
    }
  }
}

fun File.copyInputStreamToFile(inputStream: InputStream)
{
  inputStream.use { input ->
    this.outputStream().use { output ->
      input.copyTo(output, bufferSize = DEFAULT_BUFFER_SIZE)
    }
  }
}