package il.zapper.tv

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import java.util.concurrent.Executors

/**
 * מסך אחד: ערוץ גדול עם קול, ואפשרות לערוץ קטן ומושתק בפינה.
 *
 * שלט:
 *   OK      - אין פינה: הערוץ הנוכחי עובר לפינה, והמסך הגדול עובר לערוץ הקודם.
 *             יש פינה: החלפה בין הגדול לקטן (מיידית, בלי טעינה מחדש).
 *   ↑ / ↓   - ערוץ הבא / הקודם במסך הגדול (גם כפתורי CH+/CH-).
 *   ← / →   - הזזת הפינה (כשיש פינה).
 *   ספרות   - מעבר ישיר לערוץ (למשל 1 ואז 2 = ערוץ 12).
 *   חזור    - סגירת הפינה; בלי פינה: לחיצה כפולה ליציאה.
 *   תפריט   - עזרה.
 */
@OptIn(UnstableApi::class)
class MainActivity : Activity() {

    private val ui = Handler(Looper.getMainLooper())
    private val io = Executors.newCachedThreadPool()

    private lateinit var bigView: PlayerView
    private lateinit var smallView: PlayerView
    private lateinit var smallBox: LinearLayout
    private lateinit var smallLabel: TextView
    private lateinit var banner: TextView
    private lateinit var hint: TextView

    private var channels: List<Channel> = emptyList()
    private lateinit var main: Slot
    private lateinit var pip: Slot
    private var pipOn = false
    private var corner = 0            // 0 עליון-ימין, 1 תחתון-ימין, 2 תחתון-שמאל, 3 עליון-שמאל
    private var prevChannel: Channel? = null
    private var digits = ""
    private var lastBack = 0L
    private var started = false

    private val prefs by lazy { getSharedPreferences("zapper", MODE_PRIVATE) }

    /** נגן אחד והערוץ שלו. שני נגנים מתחלפים בין המסך הגדול לפינה. */
    inner class Slot(val player: ExoPlayer) {
        var channel: Channel? = null
        var sourceIdx = 0
        var token = 0
        var failedRounds = 0

        fun play(ch: Channel) {
            channel = ch
            sourceIdx = 0
            failedRounds = 0
            tryCurrent()
        }

        fun tryCurrent() {
            val ch = channel ?: return
            if (sourceIdx >= ch.sources.size) {
                // כל המקורות נכשלו: הודעה, וניסיון חוזר עוד מעט
                failedRounds++
                if (this === main) showBanner("${ch.label}: לא זמין כרגע, מנסה שוב...", 6000)
                sourceIdx = 0
                val t = ++token
                ui.postDelayed({ if (token == t && channel == ch) tryCurrent() }, if (failedRounds < 3) 5000L else 30000L)
                return
            }
            val src = ch.sources[sourceIdx]
            val t = ++token
            io.execute {
                val r = runCatching { Resolver.resolve(src) }
                ui.post {
                    if (token != t || isFinishing) return@post
                    r.onSuccess { start(it) }.onFailure {
                        if (this === main) showBanner("${ch.label}: ${it.message ?: "שגיאה"}", 3000)
                        sourceIdx++
                        tryCurrent()
                    }
                }
            }
        }

        private fun start(r: Resolver.Resolved) {
            val ds = DefaultHttpDataSource.Factory()
                .setUserAgent(Http.UA)
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(8000)
                .setReadTimeoutMs(8000)
                .setDefaultRequestProperties(r.headers)
            val ms = HlsMediaSource.Factory(ds).createMediaSource(MediaItem.fromUri(r.url))
            player.setMediaSource(ms)
            player.prepare()
            player.playWhenReady = true
        }

        fun onError(e: PlaybackException) {
            if (e.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                player.seekToDefaultPosition()
                player.prepare()
                return
            }
            sourceIdx++
            tryCurrent()
        }

        fun stop() {
            token++
            player.stop()
            player.clearMediaItems()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        buildUi()

        main = Slot(newPlayer())
        pip = Slot(newPlayer())
        attachViews()
        applyRoles()

        channels = Lineup.loadLocal(this)
        corner = prefs.getInt("corner", 0)
        placeSmallBox()

        // רשימה עדכנית מהריפו ברקע (לשינויי כתובות בלי גרסה חדשה)
        io.execute {
            val fresh = Lineup.fetchRemote(this)
            if (fresh != null) ui.post { channels = fresh.also { remapChannels(it) } }
        }

        val last = prefs.getInt("last", 12)
        main.play(channels.firstOrNull { it.number == last } ?: channels.first())
        prevChannel = channels.firstOrNull { it.number == prefs.getInt("prev", 11) }
        showBanner(main.channel!!.label)
        showHint(8000)
        started = true
    }

    /** אחרי רשימה חדשה: הערוצים שמתנגנים מקבלים את המקורות המעודכנים (לניסיון הבא). */
    private fun remapChannels(list: List<Channel>) {
        for (s in listOf(main, pip)) {
            val n = s.channel?.number ?: continue
            list.firstOrNull { it.number == n }?.let { s.channel = it }
        }
        prevChannel = prevChannel?.let { p -> list.firstOrNull { it.number == p.number } }
    }

    private fun newPlayer(): ExoPlayer {
        val p = ExoPlayer.Builder(this).build()
        p.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                val slot = if (main.player === p) main else pip
                slot.onError(error)
            }
        })
        return p
    }

    // ---------- תצוגה ----------

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun buildUi() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        bigView = PlayerView(this).apply {
            useController = false
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            isFocusable = false
        }
        root.addView(bigView, FrameLayout.LayoutParams(-1, -1))

        // הפינה: וידאו קטן + תווית, עם מסגרת
        val w = resources.displayMetrics.widthPixels * 30 / 100
        val h = w * 9 / 16
        smallView = PlayerView(this).apply {
            useController = false
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            isFocusable = false
            setShutterBackgroundColor(Color.BLACK)
        }
        // הווידאו הקטן מעל הגדול
        (smallView.videoSurfaceView as? SurfaceView)?.setZOrderMediaOverlay(true)
        smallLabel = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding(dp(8), dp(2), dp(8), dp(4))
            gravity = Gravity.CENTER
        }
        smallBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(3), dp(3), dp(3), 0)
            background = GradientDrawable().apply {
                setColor(Color.argb(230, 30, 58, 138))
                cornerRadius = dp(6).toFloat()
            }
            addView(smallView, LinearLayout.LayoutParams(w, h))
            addView(smallLabel, LinearLayout.LayoutParams(w, -2))
            visibility = View.GONE
        }
        root.addView(smallBox, FrameLayout.LayoutParams(-2, -2))

        banner = overlayText(28f, Typeface.BOLD)
        root.addView(banner, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply {
            setMargins(dp(40), dp(32), dp(40), 0)
        })

        hint = overlayText(17f, Typeface.NORMAL).apply {
            text = "OK: ערוץ לפינה / החלפה   ·   ↑↓: ערוץ   ·   ←→: הזזת הפינה   ·   ספרות: ערוץ ישיר   ·   חזור: סגירת הפינה"
        }
        root.addView(hint, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            setMargins(dp(40), 0, dp(40), dp(28))
        })

        setContentView(root)
    }

    private fun overlayText(sp: Float, style: Int) = TextView(this).apply {
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTypeface(typeface, style)
        setPadding(dp(16), dp(8), dp(16), dp(8))
        background = GradientDrawable().apply {
            setColor(Color.argb(170, 0, 0, 0))
            cornerRadius = dp(8).toFloat()
        }
        visibility = View.GONE
    }

    private fun placeSmallBox() {
        val g = when (corner) {
            0 -> Gravity.TOP or Gravity.RIGHT
            1 -> Gravity.BOTTOM or Gravity.RIGHT
            2 -> Gravity.BOTTOM or Gravity.LEFT
            else -> Gravity.TOP or Gravity.LEFT
        }
        smallBox.layoutParams = FrameLayout.LayoutParams(-2, -2, g).apply {
            setMargins(dp(36), dp(28), dp(36), dp(28))
        }
        // הבאנר בצד השני מהפינה, שלא יסתירו זה את זה
        val bannerRight = corner == 3 || corner == 2
        banner.layoutParams = FrameLayout.LayoutParams(-2, -2, Gravity.TOP or (if (bannerRight) Gravity.RIGHT else Gravity.LEFT)).apply {
            setMargins(dp(40), dp(32), dp(40), 0)
        }
    }

    private fun attachViews() {
        bigView.player = null
        smallView.player = null
        bigView.player = main.player
        smallView.player = pip.player
    }

    /** הגדול: קול ואיכות מלאה. הקטן: מושתק ובאיכות נמוכה (חוסך רוחב פס ומפענח). */
    private fun applyRoles() {
        main.player.volume = 1f
        main.player.trackSelectionParameters = main.player.trackSelectionParameters.buildUpon()
            .clearVideoSizeConstraints()
            .setMaxVideoBitrate(Int.MAX_VALUE)
            .build()
        pip.player.volume = 0f
        pip.player.trackSelectionParameters = pip.player.trackSelectionParameters.buildUpon()
            .setMaxVideoSize(854, 480)
            .setMaxVideoBitrate(1_200_000)
            .build()
    }

    private fun showBanner(text: String, ms: Long = 3000) {
        banner.text = text
        banner.visibility = View.VISIBLE
        ui.removeCallbacks(hideBanner)
        ui.postDelayed(hideBanner, ms)
    }

    private val hideBanner = Runnable { banner.visibility = View.GONE }
    private val hideHint = Runnable { hint.visibility = View.GONE }

    private fun showHint(ms: Long) {
        hint.visibility = View.VISIBLE
        ui.removeCallbacks(hideHint)
        ui.postDelayed(hideHint, ms)
    }

    private fun updateSmallLabel() {
        smallLabel.text = pip.channel?.let { "${it.label}  ·  OK לחזרה" } ?: ""
    }

    // ---------- פעולות ----------

    private fun setMain(ch: Channel) {
        val cur = main.channel
        if (cur != null && cur.number != ch.number) prevChannel = cur
        main.play(ch)
        showBanner(ch.label)
        prefs.edit().putInt("last", ch.number).putInt("prev", prevChannel?.number ?: 11).apply()
    }

    private fun zap(dir: Int) {
        if (channels.isEmpty()) return
        val i = channels.indexOfFirst { it.number == main.channel?.number }
        val next = channels[((if (i < 0) 0 else i) + dir + channels.size) % channels.size]
        setMain(next)
    }

    /** OK בלי פינה: הערוץ הנוכחי (שכבר מתנגן) עובר לפינה, והגדול עובר לערוץ הקודם. */
    private fun openPip() {
        val cur = main.channel ?: return
        val target = prevChannel?.takeIf { it.number != cur.number }
            ?: channels.firstOrNull { it.number != cur.number } ?: return
        swapSlots()
        pipOn = true
        smallBox.visibility = View.VISIBLE
        updateSmallLabel()
        main.play(target)
        prevChannel = cur
        showBanner(target.label)
        prefs.edit().putInt("last", target.number).putInt("prev", cur.number).apply()
    }

    /** OK עם פינה: החלפה מיידית. שני הנגנים ממשיכים לרוץ, רק מתחלפים מסכים. */
    private fun swapPip() {
        swapSlots()
        updateSmallLabel()
        main.channel?.let {
            showBanner(it.label)
            prevChannel = pip.channel
            prefs.edit().putInt("last", it.number).putInt("prev", pip.channel?.number ?: 11).apply()
        }
    }

    private fun swapSlots() {
        val t = main; main = pip; pip = t
        attachViews()
        applyRoles()
    }

    private fun closePip() {
        pipOn = false
        smallBox.visibility = View.GONE
        pip.stop()
        pip.channel = null
    }

    private fun moveCorner(dir: Int) {
        corner = (corner + dir + 4) % 4
        prefs.edit().putInt("corner", corner).apply()
        placeSmallBox()
    }

    private val commitDigits = Runnable {
        val n = digits.toIntOrNull()
        digits = ""
        val ch = channels.firstOrNull { it.number == n }
        if (ch != null) setMain(ch) else showBanner("אין ערוץ $n", 2000)
    }

    private fun typeDigit(d: Int) {
        digits += d
        showBanner(digits + "_", 2000)
        ui.removeCallbacks(commitDigits)
        if (digits.length >= 2) ui.post(commitDigits) else ui.postDelayed(commitDigits, 1500)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> zap(+1)
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> zap(-1)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                if (event.repeatCount == 0) { if (pipOn) swapPip() else openPip() }
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (pipOn) moveCorner(+1) else showHint(5000)
            KeyEvent.KEYCODE_DPAD_LEFT -> if (pipOn) moveCorner(-1) else showHint(5000)
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_GUIDE -> showHint(8000)
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> typeDigit(keyCode - KeyEvent.KEYCODE_0)
            KeyEvent.KEYCODE_BACK -> {
                if (pipOn) closePip()
                else {
                    val now = System.currentTimeMillis()
                    if (now - lastBack < 2000) finish()
                    else { lastBack = now; showBanner("לחיצה נוספת על חזור ליציאה", 2000) }
                }
            }
            else -> return super.onKeyDown(keyCode, event)
        }
        return true
    }

    // ---------- מחזור חיים ----------

    override fun onStart() {
        super.onStart()
        if (!started) return
        // חזרה לאפליקציה: טעינה מחדש של מה שהיה (בשידור חי - לנקודה החיה)
        if (main.channel != null && main.player.playbackState == Player.STATE_IDLE) main.play(main.channel!!)
        if (pipOn && pip.channel != null && pip.player.playbackState == Player.STATE_IDLE) pip.play(pip.channel!!)
    }

    override fun onStop() {
        super.onStop()
        main.token++; pip.token++
        main.player.stop()
        pip.player.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        ui.removeCallbacksAndMessages(null)
        bigView.player = null
        smallView.player = null
        main.player.release()
        pip.player.release()
        io.shutdownNow()
    }
}
