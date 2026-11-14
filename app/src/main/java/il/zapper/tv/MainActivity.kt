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
import android.content.Context
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import android.content.res.ColorStateList
import android.widget.ProgressBar
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors

/**
 * מסך אחד: ערוץ גדול עם קול, ואפשרות לערוץ קטן ומושתק בפינה.
 *
 * שלט:
 *   OK      - אין פינה: הערוץ הנוכחי עובר לפינה, והמסך הגדול עובר לערוץ הקודם.
 *             יש פינה: החלפה בין הגדול לפינה.
 *   ↑ / ↓   - ערוץ הבא / הקודם במסך הגדול (גם כפתורי CH+/CH-).
 *   ← / →   - הזזת הפינה (כשיש פינה).
 *   ספרות   - מעבר ישיר לערוץ (למשל 1 ואז 2 = ערוץ 12).
 *   חזור    - סגירת הפינה; בלי פינה: לחיצה כפולה ליציאה.
 *   OK ארוך - לוח שידורים של הערוץ (↑↓ דפדוף בתכניות). גם ☰/INFO/GUIDE בשלטים שמעבירים אותם.
 *   ← / →   - בלי פינה: עזרה.
 */
@OptIn(UnstableApi::class)
class MainActivity : Activity() {

    private val ui = Handler(Looper.getMainLooper())
    private val io = Executors.newCachedThreadPool()

    // צבעים
    private val accent = Color.rgb(245, 158, 11)        // כתום
    private val navy = Color.rgb(30, 58, 138)
    private val glass = Color.argb(200, 12, 18, 32)

    private lateinit var root: FrameLayout
    private lateinit var bigView: PlayerView
    private lateinit var smallView: PlayerView
    private lateinit var smallBox: LinearLayout
    private lateinit var bannerBox: LinearLayout
    private lateinit var bannerNum: TextView
    private lateinit var bannerName: TextView
    private lateinit var bannerStatus: TextView
    private lateinit var helpCard: LinearLayout
    private lateinit var bannerNow: TextView
    private lateinit var guideCard: LinearLayout
    private lateinit var guideBadge: TextView
    private lateinit var guideTitle: TextView
    private lateinit var guideClock: TextView
    private lateinit var guideList: LinearLayout
    private lateinit var guideDesc: TextView
    private var guideOpen = false
    private var guideSel = -1          // אינדקס התכנית המסומנת ברשימת הערוץ

    private val tzIL = TimeZone.getTimeZone("Asia/Jerusalem")
    private val hhmm = SimpleDateFormat("HH:mm", Locale.US).apply { timeZone = tzIL }
    private val dayFmt = SimpleDateFormat("EEEE d/M", Locale("iw")).apply { timeZone = tzIL }

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
                if (this === main) showChannel(ch, "לא זמין כרגע · מנסה שוב...", 6000)
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
                        if (this === main) showChannel(ch, it.message ?: "שגיאה", 3000)
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

        refreshEpg()

        val last = prefs.getInt("last", 12)
        main.play(channels.firstOrNull { it.number == last } ?: channels.first())
        prevChannel = channels.firstOrNull { it.number == prefs.getInt("prev", 11) }
        showChannel(main.channel!!, null, 4000)
        showHelp(10000)
        started = true
    }

    /** לוח שידורים ברקע (מהעותק השמור, ומהרשת אם עברו 6 שעות). */
    private fun refreshEpg() {
        val keys = channels.mapNotNull { it.epg }.toSet()
        io.execute {
            Epg.refresh(this, keys)
            ui.post { if (guideOpen) renderGuide() }
        }
    }

    /** אחרי רשימה חדשה: הערוצים שמתנגנים מקבלים את המקורות המעודכנים (לניסיון הבא). */
    private fun remapChannels(list: List<Channel>) {
        for (s in listOf(main, pip)) {
            val n = s.channel?.number ?: continue
            list.firstOrNull { it.number == n }?.let { s.channel = it }
        }
        prevChannel = prevChannel?.let { p -> list.firstOrNull { it.number == p.number } }
    }

    /**
     * מעבר נגן בין המסך הגדול לפינה = החלפת המשטח שהמפענח מצייר עליו.
     * ב-Mi Box (Amlogic) ההחלפה "המהירה" (setOutputSurface) השאירה את הווידאו במקום הישן
     * (הגדול שחור, התמונה בפינה). לכן: בכל החלפת משטח המפענח נפתח מחדש. עולה רגע קצר, אבל אמין.
     */
    private class Renderers(ctx: Context) : DefaultRenderersFactory(ctx) {
        override fun buildVideoRenderers(
            context: Context,
            extensionRendererMode: Int,
            mediaCodecSelector: MediaCodecSelector,
            enableDecoderFallback: Boolean,
            eventHandler: Handler,
            eventListener: VideoRendererEventListener,
            allowedVideoJoiningTimeMs: Long,
            out: ArrayList<Renderer>,
        ) {
            out.add(object : MediaCodecVideoRenderer(
                context, mediaCodecSelector, allowedVideoJoiningTimeMs,
                enableDecoderFallback, eventHandler, eventListener, 50,
            ) {
                override fun codecNeedsSetOutputSurfaceWorkaround(name: String) = true
            })
        }
    }

    private fun newPlayer(): ExoPlayer {
        val p = ExoPlayer.Builder(this, Renderers(this)).build()
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

    private fun rounded(color: Int, radiusDp: Int, strokeColor: Int? = null) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
        if (strokeColor != null) setStroke(dp(2), strokeColor)
    }

    private fun text(sp: Float, color: Int = Color.WHITE, bold: Boolean = false) = TextView(this).apply {
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        if (bold) setTypeface(Typeface.DEFAULT_BOLD)
        setShadowLayer(4f, 0f, 1f, Color.argb(160, 0, 0, 0))
    }

    /** "מקש" מעוצב לעזרה: כתום עם טקסט כהה. */
    private fun keyChip(label: String) = text(17f, Color.rgb(20, 20, 20), bold = true).apply {
        this.text = label
        setShadowLayer(0f, 0f, 0f, 0)
        gravity = Gravity.CENTER
        minWidth = dp(64)
        setPadding(dp(12), dp(4), dp(12), dp(4))
        background = rounded(accent, 8)
    }

    private fun buildUi() {
        root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }

        bigView = PlayerView(this).apply {
            useController = false
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            isFocusable = false
        }
        root.addView(bigView, FrameLayout.LayoutParams(-1, -1))

        // ---- הפינה: וידאו קטן + שורת שם, במסגרת כתומה ----
        val w = resources.displayMetrics.widthPixels * 30 / 100
        val h = w * 9 / 16
        smallView = layoutInflater.inflate(R.layout.small_player, null) as PlayerView
        // בלי מסגרת ובלי כיתוב: רק הווידאו, שיפריע כמה שפחות
        smallBox = LinearLayout(this).apply {
            addView(smallView, LinearLayout.LayoutParams(w, h))
            visibility = View.GONE
        }
        root.addView(smallBox, FrameLayout.LayoutParams(-2, -2))

        // ---- באנר ערוץ: מספר בתג כתום + שם + שורת מצב ----
        bannerNum = text(34f, Color.rgb(20, 20, 20), bold = true).apply {
            setShadowLayer(0f, 0f, 0f, 0)
            gravity = Gravity.CENTER
            minWidth = dp(72)
            setPadding(dp(14), dp(2), dp(14), dp(2))
            background = rounded(accent, 12)
        }
        bannerName = text(28f, bold = true)
        bannerStatus = text(17f, Color.rgb(253, 211, 77)).apply { visibility = View.GONE }
        bannerNow = text(17f, Color.argb(225, 255, 255, 255)).apply { visibility = View.GONE }
        val nameCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(4), 0)
            addView(bannerName)
            addView(bannerNow)
            addView(bannerStatus)
        }
        bannerBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(22), dp(12))
            background = rounded(glass, 16)
            addView(bannerNum)
            addView(nameCol)
            visibility = View.GONE
            alpha = 0f
        }
        root.addView(bannerBox, FrameLayout.LayoutParams(-2, -2))

        // ---- לוח שידורים ----
        guideCard = buildGuide()
        root.addView(guideCard)

        // ---- כרטיס עזרה ----
        helpCard = buildHelpCard()
        root.addView(helpCard, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            setMargins(dp(40), 0, dp(40), dp(36))
        })

        setContentView(root)
    }

    private fun buildHelpCard(): LinearLayout {
        val rows = listOf(
            "OK" to "פרסומות? הערוץ עובר לפינה, ואתם לערוץ הקודם",
            "OK" to "נגמרו? החלפה: הערוץ שבפינה חוזר לגדול",
            "▲ ▼" to "החלפת ערוץ",
            "◀ ▶" to "הזזת הפינה",
            "1 2" to "מעבר ישיר לערוץ",
            "OK ארוך" to "לוח שידורים: מה משודר עכשיו, לפני ואחרי",
            "חזור" to "סגירת הפינה",
        )
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28), dp(18), dp(28), dp(16))
            background = rounded(glass, 18)
            visibility = View.GONE
            alpha = 0f
        }
        card.addView(text(22f, bold = true).apply {
            this.text = "זפזפן · איך משתמשים"
            setPadding(0, 0, 0, dp(10))
        })
        // שתי עמודות של שורות
        val cols = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        rows.chunked(4).forEach { chunk ->
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 0, 0, 0)
            }
            chunk.forEach { (key, desc) ->
                col.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(5), 0, dp(5))
                    addView(keyChip(key))
                    addView(text(17f).apply { this.text = desc; setPadding(dp(12), 0, 0, 0) })
                })
            }
            cols.addView(col, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(36) })
        }
        card.addView(cols)
        card.addView(text(14f, Color.argb(180, 255, 255, 255)).apply {
            this.text = "◀ ▶ (כשאין פינה) מציגים את העזרה שוב"
            setPadding(0, dp(10), 0, 0)
        })
        return card
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
        // הבאנר למעלה, בצד שאין בו פינה
        val bannerLeft = corner == 0 || corner == 1
        bannerBox.layoutParams = FrameLayout.LayoutParams(-2, -2, Gravity.TOP or (if (bannerLeft) Gravity.LEFT else Gravity.RIGHT)).apply {
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

    private fun fadeIn(v: View) {
        v.animate().cancel()
        if (v.visibility != View.VISIBLE) { v.alpha = 0f; v.visibility = View.VISIBLE }
        v.animate().alpha(1f).setDuration(180).start()
    }

    private fun fadeOut(v: View) {
        v.animate().cancel()
        v.animate().alpha(0f).setDuration(300).withEndAction { v.visibility = View.GONE }.start()
    }

    private val hideBanner = Runnable { fadeOut(bannerBox) }
    private val hideHelp = Runnable { fadeOut(helpCard) }

    /** באנר ערוץ: מספר, שם, ושורת מצב אופציונלית (שגיאה / הודעה). */
    private fun showChannel(ch: Channel, status: String?, ms: Long = 3000) {
        bannerNum.visibility = View.VISIBLE
        bannerNum.text = ch.number.toString()
        bannerName.text = ch.name
        val p = Epg.now(ch.epg)
        bannerNow.text = p?.let { "עכשיו: ${it.name}  ·  עד ${hhmm.format(Date(it.end * 1000))}" } ?: ""
        bannerNow.visibility = if (p == null) View.GONE else View.VISIBLE
        setStatus(status)
        popBanner(ms)
    }

    /** באנר בלי ערוץ: הודעה קצרה (הקלדת מספר, יציאה). */
    private fun showMessage(big: String, status: String? = null, ms: Long = 2000) {
        bannerNum.visibility = View.GONE
        bannerName.text = big
        bannerNow.visibility = View.GONE
        setStatus(status)
        popBanner(ms)
    }

    private fun setStatus(status: String?) {
        bannerStatus.text = status ?: ""
        bannerStatus.visibility = if (status.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    private fun popBanner(ms: Long) {
        fadeIn(bannerBox)
        ui.removeCallbacks(hideBanner)
        ui.postDelayed(hideBanner, ms)
    }

    private fun showHelp(ms: Long) {
        fadeIn(helpCard)
        ui.removeCallbacks(hideHelp)
        ui.postDelayed(hideHelp, ms)
    }

    // ---------- לוח שידורים ----------

    private fun buildGuide(): LinearLayout {
        guideBadge = text(22f, Color.rgb(20, 20, 20), bold = true).apply {
            setShadowLayer(0f, 0f, 0f, 0)
            gravity = Gravity.CENTER
            minWidth = dp(52)
            setPadding(dp(10), dp(1), dp(10), dp(1))
            background = rounded(accent, 9)
        }
        guideTitle = text(22f, bold = true).apply { setPadding(dp(12), 0, dp(12), 0) }
        guideClock = text(15f, Color.argb(190, 255, 255, 255))
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(guideBadge)
            addView(guideTitle, LinearLayout.LayoutParams(0, -2, 1f))
            addView(guideClock)
        }
        guideList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, dp(8))
        }
        guideDesc = text(16f, Color.argb(230, 255, 255, 255)).apply {
            maxLines = 6
            setLineSpacing(0f, 1.15f)
            setPadding(dp(4), dp(10), dp(4), dp(4))
        }
        val divider = View(this).apply { setBackgroundColor(Color.argb(60, 255, 255, 255)) }
        val footer = text(13f, Color.argb(160, 255, 255, 255)).apply {
            this.text = "▲▼ דפדוף  ·  חזור או OK לסגירה  ·  לוח שידורים: פישנזון"
            setPadding(0, dp(10), 0, 0)
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(18), dp(22), dp(14))
            background = rounded(glass, 18)
            addView(header)
            addView(guideList)
            addView(divider, LinearLayout.LayoutParams(-1, dp(1)))
            addView(guideDesc)
            addView(footer)
            visibility = View.GONE
            alpha = 0f
        }
    }

    private fun placeGuide() {
        // בצד שאין בו פינה
        val left = !pipOn || corner == 0 || corner == 1
        guideCard.layoutParams = FrameLayout.LayoutParams(
            resources.displayMetrics.widthPixels * 46 / 100, -2,
            Gravity.CENTER_VERTICAL or (if (left) Gravity.LEFT else Gravity.RIGHT),
        ).apply { setMargins(dp(40), dp(28), dp(40), dp(28)) }
    }

    private val hideGuide = Runnable { closeGuide() }

    private fun openGuide() {
        guideOpen = true
        guideSel = -1
        ui.removeCallbacks(hideHelp); fadeOut(helpCard)
        ui.removeCallbacks(hideBanner); fadeOut(bannerBox)
        placeGuide()
        renderGuide()
        fadeIn(guideCard)
        refreshEpg()
        touchGuide()
    }

    private fun closeGuide() {
        guideOpen = false
        ui.removeCallbacks(hideGuide)
        fadeOut(guideCard)
    }

    /** הלוח נסגר לבד אחרי 30 שניות בלי לחיצה */
    private fun touchGuide() {
        ui.removeCallbacks(hideGuide)
        ui.postDelayed(hideGuide, 30000)
    }

    private fun moveGuide(dir: Int) {
        val n = Epg.programs(main.channel?.epg).size
        if (n == 0) return
        guideSel = (guideSel + dir).coerceIn(0, n - 1)
        renderGuide()
        touchGuide()
    }

    private fun renderGuide() {
        val ch = main.channel ?: return
        guideBadge.text = ch.number.toString()
        guideTitle.text = ch.name
        val nowMs = System.currentTimeMillis()
        val now = nowMs / 1000
        guideClock.text = "${dayFmt.format(Date(nowMs))}  ·  ${hhmm.format(Date(nowMs))}"
        guideList.removeAllViews()

        val progs = Epg.programs(ch.epg)
        if (progs.isEmpty()) {
            guideList.addView(text(17f, Color.argb(200, 255, 255, 255)).apply {
                this.text = if (ch.epg == null) "אין לוח שידורים לערוץ הזה" else "לוח השידורים נטען..."
            })
            guideDesc.text = ""
            return
        }
        val nowIdx = progs.indexOfFirst { it.isNow(now) }.let { if (it >= 0) it else progs.indexOfFirst { p -> p.start > now }.coerceAtLeast(0) }
        if (guideSel !in progs.indices) guideSel = nowIdx
        // חלון: תכנית אחת לפני המסומנת ו-5 אחריה
        val from = (guideSel - 1).coerceAtLeast(0)
        val to = (from + 6).coerceAtMost(progs.size)
        var lastDay = ""
        for (i in from until to) {
            val p = progs[i]
            val day = dayFmt.format(Date(p.start * 1000))
            if (day != dayFmt.format(Date(nowMs)) && day != lastDay) {
                guideList.addView(text(14f, accent, bold = true).apply { this.text = day; setPadding(dp(4), dp(6), 0, dp(2)) })
            }
            lastDay = day
            guideList.addView(programRow(p, now, i == guideSel))
        }
        val sel = progs[guideSel]
        val range = "${dayFmt.format(Date(sel.start * 1000))}  ${hhmm.format(Date(sel.start * 1000))}-${hhmm.format(Date(sel.end * 1000))}"
        guideDesc.text = if (sel.desc.isEmpty()) range else "$range\n${sel.desc}"
    }

    private fun programRow(p: Prog, now: Long, selected: Boolean): View {
        val live = p.isNow(now)
        val past = p.end <= now
        val time = text(17f, if (live) accent else Color.WHITE, bold = true).apply {
            this.text = hhmm.format(Date(p.start * 1000))
            minWidth = dp(64)
        }
        val name = text(18f, if (live) accent else Color.WHITE, bold = live).apply {
            this.text = p.name
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        val line = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(time)
            addView(name, LinearLayout.LayoutParams(0, -2, 1f))
            if (live) addView(text(13f, Color.rgb(20, 20, 20), bold = true).apply {
                this.text = "עכשיו"
                setShadowLayer(0f, 0f, 0f, 0)
                setPadding(dp(8), dp(1), dp(8), dp(1))
                background = rounded(accent, 6)
            })
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(7), dp(10), dp(7))
            if (selected) background = rounded(Color.argb(55, 255, 255, 255), 10)
            if (past) alpha = 0.5f
            addView(line)
            if (live) {
                val bar = ProgressBar(this@MainActivity, null, android.R.attr.progressBarStyleHorizontal).apply {
                    max = 1000
                    progress = ((now - p.start) * 1000 / (p.end - p.start)).toInt()
                    progressTintList = ColorStateList.valueOf(accent)
                    progressBackgroundTintList = ColorStateList.valueOf(Color.argb(70, 255, 255, 255))
                }
                addView(bar, LinearLayout.LayoutParams(-1, dp(4)).apply { topMargin = dp(6) })
            }
        }
    }

    // ---------- פעולות ----------

    private fun savePrefs() {
        prefs.edit()
            .putInt("last", main.channel?.number ?: 12)
            .putInt("prev", prevChannel?.number ?: 11)
            .apply()
    }

    private fun setMain(ch: Channel) {
        val cur = main.channel
        if (cur != null && cur.number != ch.number) prevChannel = cur
        main.play(ch)
        showChannel(ch, if (pipOn) pipStatus() else null)
        savePrefs()
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
        fadeIn(smallBox)
        main.play(target)
        prevChannel = cur
        showChannel(target, pipStatus())
        savePrefs()
    }

    private fun pipStatus() = "${pip.channel?.name ?: ""} בפינה · OK החלפה · חזור סוגר"

    /** OK עם פינה: החלפה בין הגדול לפינה. הפינה נשארת עד לחיצה על חזור. */
    private fun swapPip() {
        swapSlots()
        prevChannel = pip.channel
        main.channel?.let { showChannel(it, pipStatus()) }
        savePrefs()
    }

    private fun swapSlots() {
        val t = main; main = pip; pip = t
        attachViews()
        applyRoles()
    }

    private fun closePip() {
        pipOn = false
        smallView.player = null      // קודם לנתק את הפינה מהנגן, ורק אז לעצור ולהסתיר
        pip.stop()
        pip.channel = null
        smallBox.animate().cancel()
        smallBox.visibility = View.GONE
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
        if (ch != null) setMain(ch)
        else showMessage("אין ערוץ $n", "ערוצים: " + channels.joinToString(" · ") { it.number.toString() }, 3000)
    }

    private fun typeDigit(d: Int) {
        digits += d
        showMessage(digits + "_", null, 2000)
        ui.removeCallbacks(commitDigits)
        if (digits.length >= 2) ui.post(commitDigits) else ui.postDelayed(commitDigits, 1500)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val guideKey = keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_INFO ||
            keyCode == KeyEvent.KEYCODE_GUIDE
        if (guideOpen) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> { moveGuide(-1); return true }
                KeyEvent.KEYCODE_DPAD_DOWN -> { moveGuide(+1); return true }
                KeyEvent.KEYCODE_BACK -> { closeGuide(); return true }
                // OK סוגר, אבל לא החזרות של הלחיצה הארוכה שפתחה את הלוח
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { if (event.repeatCount == 0) closeGuide(); return true }
                KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                    zap(if (keyCode == KeyEvent.KEYCODE_CHANNEL_UP) +1 else -1)
                    guideSel = -1; renderGuide(); touchGuide(); return true
                }
                else -> if (guideKey) { closeGuide(); return true } else closeGuide()
            }
        }
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> zap(+1)
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> zap(-1)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                // לחיצה קצרה = פינה (בעזיבת הכפתור), לחיצה ארוכה = לוח שידורים
                if (event.repeatCount == 0) event.startTracking()
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (pipOn) moveCorner(+1) else showHelp(8000)
            KeyEvent.KEYCODE_DPAD_LEFT -> if (pipOn) moveCorner(-1) else showHelp(8000)
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_GUIDE -> openGuide()
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> typeDigit(keyCode - KeyEvent.KEYCODE_0)
            KeyEvent.KEYCODE_BACK -> {
                if (helpCard.visibility == View.VISIBLE) { ui.removeCallbacks(hideHelp); fadeOut(helpCard) }
                else if (pipOn) closePip()
                else {
                    val now = System.currentTimeMillis()
                    if (now - lastBack < 2000) finish()
                    else { lastBack = now; showMessage("ליציאה: עוד לחיצה על חזור", null, 2000) }
                }
            }
            else -> return super.onKeyDown(keyCode, event)
        }
        return true
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean {
        if (isOkKey(keyCode)) { openGuide(); return true }
        return super.onKeyLongPress(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (isOkKey(keyCode)) {
            // אחרי לחיצה ארוכה האירוע מסומן כמבוטל, ואז לא עושים כלום
            if (event.isTracking && !event.isCanceled && !guideOpen) { if (pipOn) swapPip() else openPip() }
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun isOkKey(k: Int) = k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER ||
        k == KeyEvent.KEYCODE_NUMPAD_ENTER || k == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE

    // ---------- מחזור חיים ----------

    override fun onStart() {
        super.onStart()
        if (!started) return
        refreshEpg()
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
