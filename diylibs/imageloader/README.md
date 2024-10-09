## ImageLoader - Efficient Image Loading for Android

This is a README file for the `ImageLoader` library, written in Kotlin for Android applications. 

**What it does:**

-   Fetches images from URLs asynchronously using coroutines.
-   Provides built-in disk caching for efficient image loading and reduced network traffic.
-   Offers customization options for cache size, location, image dimensions, and compression format.

**Benefits:**

-   Improves app performance by caching downloaded images.
-   Reduces network usage and data consumption.
-   Simplifies image loading logic in your application.

**Installation:**

(Assuming you're using Gradle)

Add the following dependency to your `build.gradle` file:

```
dependencies {
  implementation 'your.package.path:image-loader:<version>'
}

```

**Usage:**

1.  **Create an ImageLoader instance:**

```Kotlin
val imageLoader = ImageLoader.Builder()
  .maxSizeOnDisk(10 * 1024 * 1024) // Set max cache size on disk to 10 MB
  .imageWidth(500) // Set desired image width
  .imageQuality(75) // Set image compression quality (0-100)
  .build()

```

Use code [with caution.](/faq#coding)

2.  **Fetch an image:**

```Kotlin
val url = URL("https://example.com/image.jpg")
val deferredBitmap: Deferred<Bitmap?> = imageLoader.fetchImage(url)

deferredBitmap.await().let { bitmap ->
  if (bitmap != null) {
    // Use the loaded bitmap
    imageView.setImageBitmap(bitmap)
  } else {
    // Handle image loading failure
  }
}

```

Use code [with caution.](/faq#coding)

**Cache Management:**

-   **Flush cache:**

Kotlin

```
imageLoader.flush().await()

```


-   **Remove all cached images:**

Kotlin

```
imageLoader.removeAll().await()

```

**Customization Options:**

-   **`maxSizeOnDisk(size: Long)`:** Set the maximum size of the disk cache in bytes.
-   **`maxSizeInMem(size: Long)`:** Set the maximum size of the in-memory cache in bytes. (Currently not implemented)
-   **`cacheLocation(location: File)`:** Specify the location for the disk cache.
-   **`imageHeight(height: Int)`:** Set the desired image height for downloaded images.
-   **`imageWidth(width: Int)`:** Set the desired image width for downloaded images.
-   **`imageQuality(quality: Int)`:** Set the compression quality for downloaded images (0-100).
-   **`imageFormat(format: CompressFormat)`:** Choose the compression format for cached images (default: JPEG).

**Note:**

-   This is a basic implementation and may require further development based on your specific needs.

**Feel free to contribute or raise issues on the project repository!**
