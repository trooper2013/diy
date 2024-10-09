package diy.image.ril

import android.graphics.Bitmap
import java.net.URL

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
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
class RImageLoaderTest {

  private val files = mutableListOf<File>()
  private val maxFiles = 1000
  private val fileSize = 1024
  private val imageWidth = 760
  private val imageHeight = 1080
  private val cacheSize = maxFiles * fileSize * 1L
  private var listOfURLS = mutableListOf<URL>()
  @Before
  fun setupFiles() {
    for (i in 1000..1050) {
      listOfURLS.add(URL("https://picsum.photos/$imageHeight/$imageWidth?image=#${i}"))
    }
  }

  @After
  fun deleteFiles() {

  }

  @Test
  fun `Add a couple Images in Memory`() = runTest {
    val appContext = InstrumentationRegistry.getInstrumentation().targetContext
    val imageLoader = ImageLoader.cacheLocation(appContext.cacheDir)
                    .imageFormat(Bitmap.CompressFormat.PNG)
      .maxSizeInMem(1024L * 1024L * 20L)
      .maxSizeOnDisk(1024L * 1024L * 50L)
      .imageHeight(imageHeight)
      .imageWidth(imageWidth)
      .imageQuality(80).build()

    runBlocking {

      var deferredList = mutableListOf<Deferred<Bitmap?>>()
      val max = 1015
      val start = 1000
      for (i in start..max) {
        deferredList.add(imageLoader.fetchImage(url = listOfURLS.get(i - 1000)))
      }

      var images = deferredList.awaitAll()
      assertTrue(images.size == (max - start + 1))

      deferredList = mutableListOf<Deferred<Bitmap?>>()
      for (i in start..max) {
        deferredList.add(imageLoader.fetchImage(url = listOfURLS.get(i - 1000)))
      }
      images = deferredList.awaitAll()
      assertTrue(images.size == (max - start + 1))
    }



  }
}