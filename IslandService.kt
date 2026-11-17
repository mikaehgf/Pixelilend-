package com.pixelisland

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.WindowManager.LayoutParams as WLP
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.dynamicanimation.animation.FloatPropertyCompat
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce

class IslandService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    private lateinit var msm: MediaSessionManager
    private lateinit var component: ComponentName
    private lateinit var root: FrameLayout
    private lateinit var island: FrameLayout
    private lateinit var content: LinearLayout
    private lateinit var cover: ImageView
    private lateinit var title: TextView
    private lateinit var artist: TextView
    private lateinit var playBtn: ImageView
    private lateinit var winParams: WLP
    private lateinit var wAnim: SpringAnimation
    private lateinit var hAnim: SpringAnimation

    private var attached = false
    private var registered = false
    private var expanded = false
    private var current: MediaController? = null
    private var lastTitle: String? = null
    private var lastCover: Bitmap? = null
    private val collapseTask = Runnable { collapse() }

    private fun dp(v: Number) = v.toFloat() * resources.displayMetrics.density
    private fun px(key: String) = dp(Prefs.get(this, key)).toInt()

    // Пружинные свойства: ширина и высота острова.
    private val widthProp = object : FloatPropertyCompat<View>("w") {
        override fun getValue(v: View) = v.layoutParams.width.toFloat()
        override fun setValue(v: View, value: Float) {
            v.layoutParams.width = value.toInt().coerceAtLeast(1)
            v.requestLayout()
            v.invalidateOutline()
        }
    }
    private val heightProp = object : FloatPropertyCompat<View>("h") {
        override fun getValue(v: View) = v.layoutParams.height.toFloat()
        override fun setValue(v: View, value: Float) {
            v.layoutParams.height = value.toInt().coerceAtLeast(1)
            v.requestLayout()
            v.invalidateOutline()
        }
    }

    private val sessionsListener =
        MediaSessionManager.OnActiveSessionsChangedListener { onSessions(it) }

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            "demo" -> expand()
            "demofly" -> flyIn()
            else -> applyPrefs()
        }
    }

    private val cb = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            current?.let { refresh(it) }
        }
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            current?.let { updatePlay(it) }
        }
        override fun onSessionDestroyed() {
            onSessions(safeSessions())
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startFg()
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        msm = getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager
        component = ComponentName(this, MediaListener::class.java)
        buildIsland()
        Prefs.sp(this).registerOnSharedPreferenceChangeListener(prefListener)
        registerSessions()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (attached) registerSessions()
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (registered) msm.removeOnActiveSessionsChangedListener(sessionsListener)
        if (attached) {
            Prefs.sp(this).unregisterOnSharedPreferenceChangeListener(prefListener)
            current?.unregisterCallback(cb)
            wAnim.cancel()
            hAnim.cancel()
            try { wm.removeView(root) } catch (e: Exception) {}
        }
        super.onDestroy()
    }

    private fun startFg() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("island", "Pixel Island", NotificationManager.IMPORTANCE_MIN)
        )
        val n = Notification.Builder(this, "island")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Pixel Island работает")
            .build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
    }

    private fun buildIsland() {
        island = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: Outline) {
                    val r = minOf(v.height / 2f, dp(Prefs.get(this@IslandService, "r")))
                    o.setRoundRect(0, 0, v.width, v.height, r)
                }
            }
            setOnClickListener {
                if (current != null) { if (expanded) collapse() else expand() }
            }
        }

        content = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14).toInt(), 0, dp(14).toInt(), 0)
            alpha = 0f
            visibility = View.GONE
        }

        cover = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(0xFF2C2C2E.toInt())
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: Outline) {
                    o.setRoundRect(0, 0, v.width, v.height, dp(12))
                }
            }
        }
        content.addView(cover, LinearLayout.LayoutParams(dp(56).toInt(), dp(56).toInt()))

        title = TextView(this).apply {
            text = "Pixel Island"
            setTextColor(Color.WHITE)
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        artist = TextView(this).apply {
            text = "Здесь появится музыка"
            setTextColor(0xFF9A9AA0.toInt())
            textSize = 13f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(title)
            addView(artist)
        }
        content.addView(
            texts,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(12).toInt()
                marginEnd = dp(6).toInt()
            }
        )

        playBtn = icon(android.R.drawable.ic_media_play) { togglePlay() }
        content.addView(playBtn, LinearLayout.LayoutParams(dp(44).toInt(), dp(44).toInt()))
        content.addView(
            icon(android.R.drawable.ic_media_next) {
                current?.transportControls?.skipToNext()
                keepOpen()
            },
            LinearLayout.LayoutParams(dp(44).toInt(), dp(44).toInt())
        )

        island.addView(
            content,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        root = FrameLayout(this)
        root.addView(island, FrameLayout.LayoutParams(px("cw"), px("ch")))

        wAnim = SpringAnimation(island, widthProp, px("cw").toFloat())
        hAnim = SpringAnimation(island, heightProp, px("ch").toFloat())

        winParams = WLP(
            WLP.WRAP_CONTENT, WLP.WRAP_CONTENT, WLP.TYPE_APPLICATION_OVERLAY,
            WLP.FLAG_NOT_FOCUSABLE or WLP.FLAG_NOT_TOUCH_MODAL or
                WLP.FLAG_LAYOUT_NO_LIMITS or WLP.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            x = px("x")
            y = px("y")
            layoutInDisplayCutoutMode = WLP.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        wm.addView(root, winParams)
        attached = true
    }

    private fun icon(res: Int, onClick: () -> Unit) = ImageView(this).apply {
        setImageResource(res)
        setColorFilter(Color.WHITE)
        setPadding(dp(10).toInt(), dp(10).toInt(), dp(10).toInt(), dp(10).toInt())
        setOnClickListener { onClick() }
    }

    private fun togglePlay() {
        val c = current ?: return
        if (c.playbackState?.state == PlaybackState.STATE_PLAYING) {
            c.transportControls.pause()
        } else {
            c.transportControls.play()
        }
        keepOpen()
    }

    private fun keepOpen() {
        handler.removeCallbacks(collapseTask)
        handler.postDelayed(collapseTask, 5000)
    }

    // ---------- Анимации ----------

    private fun spring(stiffness: Float, damping: Float) {
        for (a in arrayOf(wAnim, hAnim)) {
            a.spring.setStiffness(stiffness).setDampingRatio(damping)
        }
    }

    private fun setSize(w: Int, h: Int) {
        island.layoutParams.width = w
        island.layoutParams.height = h
        island.requestLayout()
        island.invalidateOutline()
    }

    private fun expand() {
        keepOpen()
        if (expanded) return
        expanded = true
        spring(380f, 0.66f)
        wAnim.animateToFinalPosition(px("ew").toFloat())
        hAnim.animateToFinalPosition(px("eh").toFloat())
        content.visibility = View.VISIBLE
        content.animate().setStartDelay(110).alpha(1f).setDuration(240).start()
    }

    private fun collapse() {
        handler.removeCallbacks(collapseTask)
        if (!expanded) return
        expanded = false
        content.animate().setStartDelay(0).alpha(0f).setDuration(120)
            .withEndAction { if (!expanded) content.visibility = View.GONE }
            .start()
        spring(420f, 0.78f)
        wAnim.animateToFinalPosition(px("cw").toFloat())
        hAnim.animateToFinalPosition(px("ch").toFloat())
    }

    // Островок слегка раздувается, когда обложка в него влетает.
    private fun pulse() {
        wAnim.cancel()
        hAnim.cancel()
        setSize(px("cw") + dp(24).toInt(), px("ch") + dp(8).toInt())
        spring(500f, 0.35f)
        wAnim.animateToFinalPosition(px("cw").toFloat())
        hAnim.animateToFinalPosition(px("ch").toFloat())
    }

    // Обложка летит из центра экрана по дуге и втягивается в остров.
    private fun flyIn() {
        if (!attached || Prefs.get(this, "fly") == 0) return
        val dm = resources.displayMetrics
        val size = dp(200)

        val iv = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            val b = lastCover
            if (b != null) setImageBitmap(b) else setImageDrawable(ColorDrawable(0xFF3A3A3C.toInt()))
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: Outline) {
                    o.setRoundRect(0, 0, v.width, v.height, dp(28))
                }
            }
        }
        val layer = FrameLayout(this)
        layer.addView(iv, FrameLayout.LayoutParams(size.toInt(), size.toInt()))
        val lp = WLP(
            WLP.MATCH_PARENT, WLP.MATCH_PARENT, WLP.TYPE_APPLICATION_OVERLAY,
            WLP.FLAG_NOT_FOCUSABLE or WLP.FLAG_NOT_TOUCHABLE or
                WLP.FLAG_LAYOUT_NO_LIMITS or WLP.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            layoutInDisplayCutoutMode = WLP.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        wm.addView(layer, lp)

        val sx = dm.widthPixels / 2f
        val sy = dm.heightPixels * 0.55f
        val ex = dm.widthPixels / 2f + dp(Prefs.get(this, "x"))
        val ey = dp(Prefs.get(this, "y")) + dp(Prefs.get(this, "ch")) / 2f
        val cx = ex
        val cy = ey + (sy - ey) * 0.35f
        val endScale = dp(Prefs.get(this, "ch")) * 0.7f / size
        iv.pivotX = size / 2f
        iv.pivotY = size / 2f

        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 650
            interpolator = PathInterpolator(0.45f, 0f, 0.2f, 1f)
            addUpdateListener { a ->
                val t = a.animatedValue as Float
                val u = 1f - t
                val x = u * u * sx + 2f * u * t * cx + t * t * ex
                val y = u * u * sy + 2f * u * t * cy + t * t * ey
                iv.translationX = x - size / 2f
                iv.translationY = y - size / 2f
                val s = 1f + (endScale - 1f) * t
                iv.scaleX = s
                iv.scaleY = s
                iv.alpha = if (t > 0.8f) (1f - t) / 0.2f else 1f
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    try { wm.removeView(layer) } catch (e: Exception) {}
                    pulse()
                }
            })
            start()
        }
    }

    private fun applyPrefs() {
        if (!attached) return
        winParams.x = px("x")
        winParams.y = px("y")
        wm.updateViewLayout(root, winParams)
        wAnim.cancel()
        hAnim.cancel()
        if (expanded) setSize(px("ew"), px("eh")) else setSize(px("cw"), px("ch"))
    }

    // ---------- Музыка ----------

    private fun safeSessions(): List<MediaController> =
        try { msm.getActiveSessions(component) } catch (e: SecurityException) { emptyList() }

    private fun registerSessions() {
        if (!registered) {
            try {
                msm.addOnActiveSessionsChangedListener(sessionsListener, component, handler)
                registered = true
            } catch (e: SecurityException) {
                return
            }
        }
        onSessions(safeSessions())
    }

    private fun onSessions(list: List<MediaController>?) {
        val pick = list?.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: list?.firstOrNull()
        val had = current
        when {
            pick == null -> if (had != null) {
                detach()
                collapse()
                flyIn()
            }
            had == null || had.sessionToken != pick.sessionToken -> attach(pick)
        }
    }

    private fun attach(c: MediaController) {
        try { current?.unregisterCallback(cb) } catch (e: Exception) {}
        current = c
        lastTitle = null
        c.registerCallback(cb, handler)
        refresh(c)
    }

    private fun detach() {
        try { current?.unregisterCallback(cb) } catch (e: Exception) {}
        current = null
    }

    private fun updatePlay(c: MediaController) {
        val playing = c.playbackState?.state == PlaybackState.STATE_PLAYING
        playBtn.setImageResource(
            if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        )
    }

    private fun refresh(c: MediaController) {
        updatePlay(c)
        val m = c.metadata
        val t = m?.getString(MediaMetadata.METADATA_KEY_TITLE)
        if (m == null || t.isNullOrEmpty()) return
        val changed = t != lastTitle
        lastTitle = t
        title.text = t
        artist.text = m.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: m.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST) ?: ""

        if (changed || lastCover == null) {
            if (changed) {
                lastCover = null
                cover.setImageDrawable(null)
            }
            val bmp = m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: m.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: m.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
            if (bmp != null) {
                setCover(bmp)
            } else {
                val uri = m.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
                    ?: m.getString(MediaMetadata.METADATA_KEY_ART_URI)
                    ?: m.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI)
                if (uri != null) loadUri(uri, t)
            }
        }
        if (changed) expand()
    }

    private fun setCover(b: Bitmap) {
        lastCover = b
        cover.setImageBitmap(b)
    }

    // YouTube Music часто отдаёт обложку ссылкой, а не картинкой.
    private fun loadUri(uri: String, forTitle: String) {
        Thread {
            val bmp = try {
                if (uri.startsWith("http")) {
                    java.net.URL(uri).openStream().use { BitmapFactory.decodeStream(it) }
                } else {
                    contentResolver.openInputStream(Uri.parse(uri))?.use { BitmapFactory.decodeStream(it) }
                }
            } catch (e: Exception) {
                null
            }
            if (bmp != null) {
                handler.post { if (title.text.toString() == forTitle) setCover(bmp) }
            }
        }.start()
    }
}
