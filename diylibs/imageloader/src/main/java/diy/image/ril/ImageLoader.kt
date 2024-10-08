package diy.image.ril

import android.graphics.Bitmap
import android.graphics.Bitmap.CompressFormat
import android.graphics.BitmapFactory
import android.graphics.Bitmap.CompressFormat.JPEG
import android.util.Log
import diy.rcache.lru.disk.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URL
import kotlinx.coroutines.*

private const val DEFAULT_CACHE_SIZE = 1024L * 1024L * 50L
private const val DEFAULT_WIDTH = 1080
private const val DEFAULT_HEIGHT = 720
private const val DEFAULT_QUALITY = 80

interface ImageLoader {
  fun fetchImage(url: URL) : Deferred<Bitmap?>
  fun removeAll() : Deferred<Boolean>
  companion object Builder {

    private var cacheSizeOnDisk: Long = DEFAULT_CACHE_SIZE
    private var cacheSizeInMem: Long = DEFAULT_CACHE_SIZE / 4
    private var cacheLocation: File = File("image_cache")
    private var imageHeight: Int = DEFAULT_HEIGHT
    private var imageWidth: Int = DEFAULT_WIDTH
    private var imageQuality: Int = DEFAULT_QUALITY
    private var imageFormat: Bitmap.CompressFormat = Bitmap.CompressFormat.JPEG

    fun maxSizeOnDisk(size: Long): ImageLoader.Builder {
      this.cacheSizeOnDisk = size
      return this
    }

    fun maxSizeInMem(size: Long): ImageLoader.Builder {
      this.cacheSizeInMem = size
      return this
    }

    fun cacheLocation(location: File): ImageLoader.Builder {
      this.cacheLocation = location
      return this
    }

    fun imageHeight(height: Int): ImageLoader.Builder {
      this.imageHeight = height
      return this
    }

    fun imageWidth(width: Int): ImageLoader.Builder {
      this.imageHeight = width
      return this
    }

    fun imageQuality(quality: Int): ImageLoader.Builder {
      this.imageQuality = quality
      return this
    }

    fun imageFormat(format: CompressFormat): ImageLoader.Builder {
      this.imageFormat = format
      return this
    }


    fun build(): ImageLoader {
      return RImageLoaderImpl(
        RImageLoaderConfig(
          cacheLocation, cacheSizeInMem,
          cacheSizeOnDisk, imageHeight,
          imageWidth, imageQuality, imageFormat
        )
      )
    }
  }

}

internal class RImageLoaderConfig(internal val cacheLocation: File,
                                  internal val memoryCacheSize: Long,
                                  internal val diskCacheSize: Long,
                                  internal val imageHeight: Int,
                                  internal val imageWidth: Int,
                                  internal val imageQuality: Int,
                                  internal val format: Bitmap.CompressFormat = JPEG) {

}

internal class RImageLoaderImpl internal constructor (): ImageLoader {
  private val TAG = "RImageLoader"
  private lateinit var imageConfig: RImageLoaderConfig
  private lateinit var diskLruCache: LRUDiskCache

  private val loaderScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  internal constructor(imageConfig: RImageLoaderConfig) : this() {
    this.imageConfig = imageConfig
    diskLruCache = LRUDiskCache.Builder
      .maxSizeInMem( this.imageConfig.diskCacheSize)
      .maxSizeOnDisk(this.imageConfig.memoryCacheSize)
      .cacheLocation(this.imageConfig.cacheLocation).build()
  }

  override fun removeAll(): Deferred<Boolean>  {
   return diskLruCache.clearAll()
  }

  override fun fetchImage(url: URL): Deferred<Bitmap?> {
    return loaderScope.async {
      val key = url.hashCode().toString(16)
      val bytes = diskLruCache.fetch(key)
      var bitmap: Bitmap? = null

      if (bytes!=null) {
        Log.d(TAG, "Getting image from cache for  $url")
        bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
      } else {
        Log.d(TAG, "Cache Miss for $url")
        bitmap = loadFromURL(url)
        val stream = ByteArrayOutputStream()
        bitmap.compress(imageConfig.format, imageConfig.imageQuality, stream)
        diskLruCache.store(key, stream.toByteArray())
        Log.d(TAG, "Store key in cache for $url")
      }
      bitmap
    }
  }

  private fun loadFromURL(url: URL): Bitmap {
    val connection = url.openConnection()
    connection.connect()
    Log.d(TAG, " Downloading image $url")
    val inputStream = connection.getInputStream()
    return BitmapFactory.decodeStream(inputStream)
  }


}