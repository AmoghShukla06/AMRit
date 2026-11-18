package org.amrit.app

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.amrit.app.ui.MainViewModel
import org.amrit.app.ui.ShareCard
import org.amrit.core.Lang
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** 3.6: a result shared to WhatsApp arrives as a picture card in the user's language, with the text as caption. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class ShareCardTest {
    private val app get() = ApplicationProvider.getApplicationContext<AmritApp>()
    private val shots = File(System.getProperty("amrit.shots") ?: "build/screenshots").apply { mkdirs() }

    private fun vm(lang: Lang): MainViewModel {
        runBlocking { app.graph.resolver() }
        app.graph.settings.onboarded = true
        return MainViewModel(app).also { it.setLanguage(lang) }
    }

    @Test fun cardIsAPhoneSizedPicture() {
        val v = vm(Lang.HI)
        val p = v.g.ref.productsByName("Azithral 500 Tablet").first()
        val bmp = ShareCard.render(app, v.g.rules.evaluate(p, p.items), v.g.ref, Lang.HI, emptyList(), "08/2027")
        assertEquals(1080, bmp.width)
        assertTrue("height ${bmp.height}", bmp.height in 900..3200)
        File(shots, "22_share_card_hi.png").outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun shareSendsTheImageWithTextCaption() {
        // FileProvider matches roots with '/', so under Robolectric on Windows (paths with '\') it can't map the cache
        // file; on Android paths always use '/'. Checked on the phone instead when run on Windows.
        org.junit.Assume.assumeTrue(File.separatorChar == '/')
        val v = vm(Lang.EN)
        val p = v.g.ref.productsByName("Dolo 650 Tablet").first()
        val chooser = v.shareIntent(v.g.rules.evaluate(p, p.items), emptyList(), null)
        val send = chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)!!
        assertEquals("image/png", send.type)
        val uri = send.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        assertNotNull(uri); assertEquals("content", uri!!.scheme)
        assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertTrue(send.getStringExtra(Intent.EXTRA_TEXT)!!.contains("Dolo 650"))
        val png = app.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it) }
        assertEquals(1080, png.width)
    }
}
