package com.moonlightpages.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import java.io.File

/** Original generated artwork; tests never use or modify the user's imported books. */
internal fun addIllustratedChapter(folder: File) {
    val image = Bitmap.createBitmap(1440, 2880, Bitmap.Config.ARGB_8888)
    try {
        val canvas = Canvas(image)
        canvas.drawColor(Color.rgb(201, 101, 63))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.rgb(31, 62, 50)
        canvas.drawRect(0f, 0f, 1440f, 750f, paint)
        paint.color = Color.rgb(255, 218, 145)
        canvas.drawCircle(720f, 1120f, 340f, paint)
        paint.color = Color.rgb(255, 243, 216)
        repeat(8) { line -> canvas.drawRect(150f, 1720f + line * 90, 1290f, 1750f + line * 90, paint) }
        File(folder, "plate.jpg").outputStream().use { image.compress(Bitmap.CompressFormat.JPEG, 90, it) }
    } finally { image.recycle() }
    File(folder, "plate.svg").writeText("""
        <svg xmlns="http://www.w3.org/2000/svg" width="1600" height="900" viewBox="0 0 1600 900">
        <rect width="1600" height="900" fill="#193B32"/><circle cx="800" cy="450" r="300" fill="#F2BC63"/>
        </svg>
    """.trimIndent())
    File(folder, "one.xhtml").writeText("""
        <html xmlns="http://www.w3.org/1999/xhtml"><head><title>Illustrated chapter</title></head><body>
        <h1>Illustrated chapter</h1><p>A page of text before the illustrations. The lamp and page controls should stay in place.</p>
        ${(1..6).joinToString("") { index -> """
            <figure style="break-before:column; break-inside:avoid">
            <img src="${if (index % 2 == 0) "plate.svg" else "plate.jpg"}" loading="lazy"
                width="1440" height="2880" alt="Original illustration $index"/>
            <figcaption>Illustration $index</figcaption></figure>
            <p>After illustration $index, the reading controls are still visible above and below the page.</p>
        """ }}
        <p>The final paragraph after all the illustrations.</p></body></html>
    """.trimIndent())
}
