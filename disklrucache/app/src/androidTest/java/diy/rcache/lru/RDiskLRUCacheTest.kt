package diy.rcache.lru

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.file.Paths
import kotlin.io.path.getLastModifiedTime
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented test, which will execute on an Android device.
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
@RunWith(AndroidJUnit4::class)
class RDiskLRUCacheTest {

  private val files = mutableListOf<File>()
  private val maxFiles  = 1000
  private val fileSize = 1024
  private val cacheSize  = maxFiles * fileSize * 1L


  @Before
  fun setupFiles() {
    val appContext = InstrumentationRegistry.getInstrumentation().targetContext
     for (i in 1..maxFiles) {
       val file = File(appContext.dataDir.absolutePath + "/" +"file$i.test")
       val bytes: ByteArray = ByteArray(fileSize)
       bytes.fill(i.toByte())
       file.writeBytes(bytes)
       files.add(file)
     }
  }

  @After
  fun deleteFiles() {
    files.forEach {
      it.delete()
    }
  }

  @Test
  fun `Add a couple Entries in Memory`() = runTest {

      val appContext = InstrumentationRegistry.getInstrumentation().targetContext
      assertEquals("diy.rcache.lru", appContext.packageName)
      val cache = RDiskLRUCache(RCacheConfig(sizeOnDisk = cacheSize, cacheLocation = appContext.dataDir))
      runBlocking{
        cache.clearAll().await()
      }
      assertTrue(cache.cacheConfig.cacheFolder.exists())
      assertTrue(cache.cacheConfig.journalFileLocation.exists())
      assertTrue(cache.cacheConfig.journalFile.exists())

      assertTrue(cache.readJournal().size == 1)
      val key1 = "one"
      val file0Bytes = files[0].readBytes()
      val file1Bytes = files[1].readBytes()
      cache.store(key1, file0Bytes)
      val key2 = "two"
      cache.store(key2, file1Bytes)
      val totalMemSizeExpected = file0Bytes.size + file1Bytes.size
      assertTrue(cache.memCacheSize() == (totalMemSizeExpected *1L))
      assertTrue(cache.fileCacheSize().await() == 0L)

  }

  @Test
  fun `Add a couple Entries in Memory and Disk`() = runTest {

    val appContext = InstrumentationRegistry.getInstrumentation().targetContext
    assertEquals("diy.rcache.lru", appContext.packageName)
    val cache = RDiskLRUCache(RCacheConfig(sizeOnDisk = cacheSize, cacheLocation = appContext.dataDir))

    runBlocking{
      cache.clearAll().await()
    }
    assertTrue(cache.cacheConfig.cacheFolder.exists())
    assertTrue(cache.cacheConfig.journalFileLocation.exists())
    assertTrue(cache.cacheConfig.journalFile.exists())
    assertTrue(cache.readJournal().size == 1)
    val file0Bytes = files[0].readBytes()
    val file1Bytes = files[1].readBytes()
    val key1 = "one"
    cache.store(key1, file0Bytes)
    val key2 = "two"
    cache.store(key2, file1Bytes)

    assertTrue( cache.fileCacheSize().await() == 0L)
    assertTrue(cache.flush().await())
    assertTrue(cache.fileCacheSize().await() == (fileSize * 2L))
  }


  @Test
  fun `Test Updated Time on Disk`() = runTest {

    val appContext = InstrumentationRegistry.getInstrumentation().targetContext
    assertEquals("diy.rcache.lru", appContext.packageName)
    val cache = RDiskLRUCache(RCacheConfig(sizeOnDisk = cacheSize, cacheLocation = appContext.dataDir))
    assertTrue(cache.clearAll().await())
    assertTrue(cache.cacheConfig.cacheFolder.exists())
    assertTrue(cache.cacheConfig.journalFileLocation.exists())
    assertTrue(cache.cacheConfig.journalFile.exists())

    val key1 = "one"
    cache.store(key1, files[0].readBytes())
    val key2 = "two"
    cache.store(key2, files[1].readBytes())
    assertTrue(cache.flush().await())

    val getOne = cache.fetch(key1)
    assertNotNull(getOne)
    assertTrue(cache.flush().await())
    val firstGetModTime = Paths.get(cache.fileInCache(key1)?.absolutePath).getLastModifiedTime()
    runBlocking {delay(5000L) }

    val getOnceAgain = cache.fetch(key1)
    assertNotNull(getOnceAgain)
    assertTrue(cache.flush().await())
    val secondGetModTime = Paths.get(cache.fileInCache(key1)?.absolutePath).getLastModifiedTime()

    assertTrue(secondGetModTime > firstGetModTime)
  }

  @Test
  fun `Test Multi threaded access to cache state and its correctness `() = runTest {

    val appContext = InstrumentationRegistry.getInstrumentation().targetContext
    assertEquals("diy.rcache.lru", appContext.packageName)
    val cache = RDiskLRUCache(RCacheConfig(sizeOnDisk = cacheSize, cacheLocation = appContext.dataDir))
    assertTrue(cache.clearAll().await())
    assertTrue(cache.cacheConfig.cacheFolder.exists())
    assertTrue(cache.cacheConfig.journalFileLocation.exists())
    assertTrue(cache.cacheConfig.journalFile.exists())

    val task1 = async(start = CoroutineStart.LAZY, context = Dispatchers.Default) {
        for (i in 1..(maxFiles/2)){
          cache.store("$i", files.get(i - 1).readBytes())
          delay(1)
          cache.flush().await()
        }
      }

    val task2 = async(start = CoroutineStart.LAZY, context = Dispatchers.Main) {
      for (i in (maxFiles/5)..maxFiles) {
        cache.store("$i", files.get(i - 1).readBytes())
        delay(1)
        cache.flush().await()
      }
    }

    val task3 = async(start = CoroutineStart.LAZY, context = Dispatchers.Main) {
      var i = 100
      while ( i < 200 ){

        while (cache.fetch("$i") == null)
          delay(1)

        cache.delete("$i")
        cache.flush().await()
        delay(1)
        i += 1
      }
    }

    joinAll(task1, task2, task3)
    assertTrue(cache.memCacheSize() == (fileSize * 900L))

    assertTrue( cache.fileCacheSize().await() == (fileSize * 900L))
  }


  @Test
  fun `Test Cache Size must not exceed set Size `() = runTest {

    val appContext = InstrumentationRegistry.getInstrumentation().targetContext
    assertEquals("diy.rcache.lru", appContext.packageName)
    val cache = RDiskLRUCache(RCacheConfig(sizeOnDisk = 5L * fileSize, cacheLocation = appContext.dataDir))
    assertTrue(cache.clearAll().await())
    assertTrue(cache.cacheConfig.cacheFolder.exists())
    assertTrue(cache.cacheConfig.journalFileLocation.exists())
    assertTrue(cache.cacheConfig.journalFile.exists())

    //lets add 5 files
    runBlocking{
      for (i in 1..5){
        cache.store("$i", files[i - 1].readBytes())
      }
      cache.flush().await()
    }

    for (i in 1..5) {
      assertNotNull(cache.fetch("$i"))
    }

    assertTrue(cache.flush().await())
    cache.clearMemoryCache()
    cache.store("6", files[5].readBytes())
    cache.store("7", files[6].readBytes())

    assertTrue(cache.flush().await())
    assertNull(cache.fetch("1"))

    assertNull(cache.fetch("2"))
    assertNotNull(cache.fetch("6"))
    assertNotNull(cache.fetch("7"))
  }

}