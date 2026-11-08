package com.example.lumialauncher

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.SoundPool
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.net.wifi.SupplicantState
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextClock
import android.widget.TextView
import android.widget.Toast
import java.text.Collator
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ---- Ajustes rápidos ----
private const val BADGE_MAIL = 1       // el mosaico muestra el nº real de correos sin leer
private const val BADGE_MSG = 2        // el mosaico muestra el nº real de mensajes sin leer
private const val SHOW_BORDER = true   // marco rojo del teléfono

private val RED = Color.parseColor("#B02420")
private val WHITE_TILE = Color.parseColor("#E8E8E8")

/** Icono de wifi: punto + 3 arcos. level: -1 = sin wifi, 0..4 = intensidad. */
private class WifiView(c: Context) : View(c) {
    var level = -1
        set(v) { field = v; invalidate() }
    private val on = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.STROKE
    }
    private val off = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(90, 255, 255, 255); style = Paint.Style.STROKE
    }
    private val dotOn = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val dotOff = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(90, 255, 255, 255) }

    override fun onDraw(canvas: Canvas) {
        val sw = height * 0.11f
        on.strokeWidth = sw; off.strokeWidth = sw
        val cx = width / 2f
        val cy = height - sw
        val active = if (level < 0) 0 else 1 + level / 2
        val radii = floatArrayOf(height * 0.33f, height * 0.60f, height * 0.87f)
        for (i in 0 until 3) {
            val r = radii[i]
            canvas.drawArc(RectF(cx - r, cy - r, cx + r, cy + r), 225f, 90f, false,
                if (i < active) on else off)
        }
        canvas.drawCircle(cx, cy, sw * 0.8f, if (level >= 0) dotOn else dotOff)
    }
}

private class BatteryView(c: Context) : View(c) {
    var level = 100
        set(v) { field = v; invalidate() }
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 3f
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    override fun onDraw(canvas: Canvas) {
        val nub = width * 0.08f
        val body = RectF(2f, 2f, width - nub - 2f, height - 2f)
        canvas.drawRect(body, outline)
        val inner = (body.width() - 10f) * level / 100f
        canvas.drawRect(body.left + 5f, body.top + 5f, body.left + 5f + inner, body.bottom - 5f, fill)
        canvas.drawRect(width - nub, height * 0.3f, width.toFloat(), height * 0.7f, fill)
    }
}

/** Una app de la lista: nombre e icono se leen una sola vez. */
private class AppEntry(val label: String, val pkg: String, val cls: String, val icon: Drawable)

class MainActivity : Activity() {

    private class Tile(
        val label: String,
        val icon: Int,
        val badgeKind: Int = 0,
        val keepColor: Boolean = false,
        val action: () -> Unit
    )

    private lateinit var titleView: TextView
    private lateinit var dayView: TextView
    private lateinit var battery: BatteryView
    private lateinit var wifi: WifiView
    private lateinit var drawer: FrameLayout
    private lateinit var appList: ListView
    private val appAdapter = AppAdapter()
    private val ui = Handler(Looper.getMainLooper())
    private var loadGen = 0
    private lateinit var tiles: List<Tile>
    private val homeViews = mutableListOf<View>()   // título + mosaicos: se ocultan al abrir la lista de apps
    private val boxes = mutableListOf<FrameLayout>()
    private val icons = mutableListOf<ImageView>()
    private val badges = mutableListOf<TextView?>()
    private var selected = 4

    // Sonidos del teléfono: mover entre mosaicos, entrar a una app, salir
    private lateinit var sounds: SoundPool
    private var sMove = 0
    private var sEnter = 0
    private var sExit = 0
    private var leftForApp = false

    private fun play(id: Int) { if (id != 0) sounds.play(id, 1f, 1f, 1, 0, 1f) }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { refreshStatus() }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // ---------- Lista de apps (carga en segundo plano, filas recicladas) ----------
    private inner class AppAdapter : BaseAdapter() {
        var data: List<AppEntry> = emptyList()
            set(v) { field = v; notifyDataSetChanged() }

        override fun getCount() = data.size
        override fun getItem(position: Int) = data[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val tv = (convertView as? TextView) ?: TextView(this@MainActivity).apply {
                setTextColor(Color.WHITE); textSize = 20f
                compoundDrawablePadding = dp(16)
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(10), 0, dp(10))
            }
            val e = data[position]
            tv.text = e.label
            tv.setCompoundDrawables(e.icon, null, null, null)
            return tv
        }
    }

    /** Lee las apps instaladas en un hilo aparte, las ordena A-Z (sin tildes ni mayúsculas) y actualiza la lista. */
    private fun loadApps() {
        val gen = ++loadGen
        val pm = packageManager
        val iconSize = dp(40)
        Thread {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val collator = Collator.getInstance(Locale("es")).apply { strength = Collator.PRIMARY }
            val list = pm.queryIntentActivities(intent, 0).map { ri ->
                val icon = ri.loadIcon(pm).apply { setBounds(0, 0, iconSize, iconSize) }
                AppEntry(ri.loadLabel(pm).toString().trim(), ri.activityInfo.packageName, ri.activityInfo.name, icon)
            }.sortedWith { a, b -> collator.compare(a.label, b.label) }
            ui.post { if (gen == loadGen) appAdapter.data = list }
        }.start()
    }

    private val reloadApps = Runnable { loadApps() }

    // Instalar / desinstalar / actualizar una app -> se refresca la lista (con una pequeña espera por si son varias)
    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            ui.removeCallbacks(reloadApps)
            ui.postDelayed(reloadApps, 800)
        }
    }

    // ---------- Números reales de notificaciones ----------
    private fun listenerEnabled(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false
        return enabled.contains("$packageName/")
    }

    private fun refreshBadges() {
        tiles.forEachIndexed { i, t ->
            val b = badges[i] ?: return@forEachIndexed
            val n = when (t.badgeKind) {
                BADGE_MAIL -> NotificationCounter.mail
                BADGE_MSG -> NotificationCounter.msgs
                else -> 0
            }
            b.text = n.toString()
            b.visibility = if (n > 0) View.VISIBLE else View.GONE
        }
    }

    // ---------- Lanzar apps ----------
    private fun launch(intent: Intent) {
        try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            leftForApp = true
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "No hay ninguna app disponible", Toast.LENGTH_SHORT).show()
        }
    }

    private fun category(c: String) =
        launch(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, c))

    private fun launchPackage(pkg: String): Boolean {
        val i = packageManager.getLaunchIntentForPackage(pkg) ?: return false
        launch(i)
        return true
    }

    private fun openUrl(u: String) = launch(Intent(Intent.ACTION_VIEW, Uri.parse(u)))

    private fun openCamera() {
        try {
            startActivity(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            launch(Intent(MediaStore.ACTION_IMAGE_CAPTURE))
        }
    }

    private fun openWhatsApp() {
        if (!launchPackage("com.whatsapp") && !launchPackage("com.whatsapp.w4b")) {
            Toast.makeText(this, "WhatsApp no está instalado", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)

        sounds = SoundPool.Builder()
            .setMaxStreams(3)
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            .build()
        sMove = sounds.load(this, R.raw.snd_mover, 1)
        sEnter = sounds.load(this, R.raw.snd_entrar, 1)
        sExit = sounds.load(this, R.raw.snd_separar, 1)

        // Cada mosaico: nombre, icono, número, acción. Cambia las acciones a tu gusto.
        tiles = listOf(
            Tile("Correo electrónico", R.drawable.ic_mail, BADGE_MAIL) {
                if (!launchPackage("com.google.android.gm")) category(Intent.CATEGORY_APP_EMAIL)
            },
            Tile("Mensajes", R.drawable.ic_chat, BADGE_MSG) { category(Intent.CATEGORY_APP_MESSAGING) },
            Tile("Lista de actividades", R.drawable.ic_tasks) { openDrawer() },
            Tile("Nube", R.drawable.ic_cloud) {
                if (!launchPackage("com.google.android.apps.docs")) openDrawer()
            },
            Tile("Contactos", R.drawable.ic_person) { openWhatsApp() },
            Tile("Ajustes", R.drawable.ic_wrench) { launch(Intent(Settings.ACTION_SETTINGS)) },
            Tile("Snapmatic", R.drawable.ic_snapmatic, keepColor = true) { openCamera() },
            Tile("Internet", R.drawable.ic_globe) {
                // Abre la app de Google; si no está instalada, abre google.com en el navegador
                if (!launchPackage("com.google.android.googlequicksearchbox")) openUrl("https://www.google.com")
            }
        )

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val main = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(0))
        }

        // Barra de estado: wifi · día · (hueco) · batería · hora
        val status = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, dp(4), 0)
        }
        wifi = WifiView(this)
        status.addView(wifi, LinearLayout.LayoutParams(dp(22), dp(14)))
        dayView = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 13f
            setPadding(dp(14), 0, 0, 0)
        }
        status.addView(dayView)
        status.addView(View(this), LinearLayout.LayoutParams(0, 0, 1f))
        battery = BatteryView(this)
        status.addView(battery, LinearLayout.LayoutParams(dp(26), dp(12)))
        status.addView(TextClock(this).apply {
            format24Hour = "HH:mm"; format12Hour = "HH:mm"
            setTextColor(Color.WHITE); textSize = 13f
            setPadding(dp(8), 0, 0, 0)
        })
        main.addView(status)

        // Título
        titleView = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 30f
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            setPadding(dp(4), dp(8), 0, dp(14))
        }
        main.addView(titleView)
        homeViews.add(titleView)

        // Mosaicos 3 columnas
        val size = (resources.displayMetrics.widthPixels - dp(28)) / 3 - dp(8)
        var row: LinearLayout? = null
        tiles.forEachIndexed { i, t ->
            if (i % 3 == 0) {
                val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                row = r
                main.addView(r)
                homeViews.add(r)
            }
            val box = FrameLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(size, size).apply {
                    setMargins(dp(4), dp(4), dp(4), dp(4))
                }
                setOnTouchListener { _, ev ->
                    if (ev.action == MotionEvent.ACTION_DOWN && selected != i) { select(i); play(sMove) }
                    false
                }
                setOnClickListener { select(i); play(sEnter); t.action() }
                if (t.badgeKind > 0) setOnLongClickListener {
                    // Mantener apretado: si falta el permiso, abre la pantalla para darlo
                    if (listenerEnabled()) false
                    else { launch(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)); true }
                }
            }
            val icon = ImageView(this).apply {
                setImageResource(t.icon)
                val k = if (t.keepColor) 72 else 55
                layoutParams = FrameLayout.LayoutParams(size * k / 100, size * k / 100, Gravity.CENTER)
            }
            box.addView(icon)
            var badge: TextView? = null
            if (t.badgeKind > 0) {
                badge = TextView(this).apply {
                    visibility = View.GONE; textSize = 15f
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                        Gravity.TOP or Gravity.END
                    ).apply { setMargins(0, dp(2), dp(8), 0) }
                }
                box.addView(badge)
            }
            boxes.add(box); icons.add(icon); badges.add(badge)
            row!!.addView(box)
        }

        val spacer = View(this)
        main.addView(spacer, LinearLayout.LayoutParams(0, 0, 1f))
        homeViews.add(spacer)

        // Línea separadora + barra inferior (+ verde y flecha roja)
        main.addView(View(this).apply { setBackgroundColor(Color.parseColor("#6B6865")) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            setPadding(0, 0, dp(28), 0)
        }
        bottom.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_add)
            setOnClickListener { play(sEnter); openDrawer() }
        }, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(30) })
        bottom.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_back)
            setOnClickListener {
                play(sExit)
                if (drawer.visibility == View.VISIBLE) closeDrawer() else select(4)
            }
        }, LinearLayout.LayoutParams(dp(44), dp(44)))
        main.addView(bottom, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(84)))
        root.addView(main)

        // Lista de todas las apps
        appList = ListView(this).apply {
            adapter = appAdapter
            divider = null; dividerHeight = 0
            overScrollMode = View.OVER_SCROLL_NEVER
            cacheColorHint = Color.TRANSPARENT
            selector = ColorDrawable(Color.argb(40, 255, 255, 255))
            setOnItemClickListener { _, _, pos, _ ->
                val e = appAdapter.data[pos]
                launch(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                    .setClassName(e.pkg, e.cls))
                closeDrawer()
            }
        }
        val drawerContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(8))
            addView(TextView(this@MainActivity).apply {
                text = "Aplicaciones"; setTextColor(Color.WHITE); textSize = 30f
                typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                setPadding(0, 0, 0, dp(12))
            })
            addView(appList, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        drawer = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK); visibility = View.GONE
            addView(drawerContent)
        }
        // La lista va dentro de la columna principal, entre la barra de estado y la barra de abajo
        main.addView(drawer, main.indexOfChild(spacer) + 1,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        if (SHOW_BORDER) root.addView(View(this).apply {
            background = GradientDrawable().apply {
                setStroke(dp(3), Color.parseColor("#8F1015")); setColor(Color.TRANSPARENT)
            }
        }, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        setContentView(root)
        select(selected)

        // Lista de apps lista de antemano + aviso para refrescarla cuando cambien las apps
        loadApps()
        registerReceiver(packageReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        })

        // Aviso una sola vez para activar los números de notificaciones
        val prefs = getSharedPreferences("lumia", MODE_PRIVATE)
        if (!listenerEnabled() && !prefs.getBoolean("badge_hint", false)) {
            Toast.makeText(this,
                "Mantené apretado Correo o Mensajes para activar los números de notificaciones",
                Toast.LENGTH_LONG).show()
            prefs.edit().putBoolean("badge_hint", true).apply()
        }
    }

    // ---------- Estado real: día, batería, wifi ----------
    override fun onStart() {
        super.onStart()
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_DATE_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(WifiManager.RSSI_CHANGED_ACTION)
            addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
            @Suppress("DEPRECATION")
            addAction(ConnectivityManager.CONNECTIVITY_ACTION)
        }
        registerReceiver(statusReceiver, f)
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.registerNetworkCallback(
                NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(),
                wifiCallback)
        } catch (e: Exception) { /* si falla, queda el refresco por broadcasts */ }
        refreshStatus()
        NotificationCounter.onChange = { refreshBadges() }
        refreshBadges()
    }

    override fun onStop() {
        NotificationCounter.onChange = null
        try {
            (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager)
                .unregisterNetworkCallback(wifiCallback)
        } catch (e: Exception) { }
        unregisterReceiver(statusReceiver)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        if (leftForApp) { leftForApp = false; play(sExit) }
    }

    override fun onDestroy() {
        loadGen++
        ui.removeCallbacksAndMessages(null)
        unregisterReceiver(packageReceiver)
        sounds.release()
        super.onDestroy()
    }

    // En Android 10+ el sistema oculta varios datos del wifi (red, SSID) si la app no tiene permiso de
    // ubicación, y por eso el ícono antes quedaba siempre apagado. Ahora se detecta el wifi por la
    // lista de redes activas, que no necesita permisos extra.
    @Suppress("DEPRECATION")
    private fun wifiLevel(): Int {
        return try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            if (!wm.isWifiEnabled) return -1
            val info = wm.connectionInfo
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val onWifi = cm.allNetworks.any {
                cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            }
            val connected = onWifi || info?.supplicantState == SupplicantState.COMPLETED
            if (!connected) return -1
            val rssi = info?.rssi ?: -127
            when {
                rssi <= -127 -> 2      // conectado pero Android no informa la señal: se muestra a la mitad
                rssi >= -55 -> 4
                rssi >= -65 -> 3
                rssi >= -75 -> 2
                rssi >= -85 -> 1
                else -> 0
            }
        } catch (e: Exception) {
            -1
        }
    }

    // Avisa al instante cuando el wifi se conecta, se corta o cambia la señal
    private val wifiCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { ui.post { refreshStatus() } }
        override fun onLost(network: Network) { ui.post { refreshStatus() } }
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            ui.post { refreshStatus() }
        }
    }

    private fun refreshStatus() {
        dayView.text = SimpleDateFormat("EEE", Locale("es")).format(Date())
            .replace(".", "").replaceFirstChar { it.uppercase() }
        val b = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (b != null) {
            val level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, 100)
            val scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            battery.level = level * 100 / scale
        }
        wifi.level = wifiLevel()
    }

    // ---------- Selección y lista de apps ----------
    private fun select(i: Int) {
        selected = i
        titleView.text = tiles[i].label
        boxes.forEachIndexed { idx, b ->
            val on = idx == i
            b.setBackgroundColor(if (on) WHITE_TILE else RED)
            if (tiles[idx].keepColor) icons[idx].clearColorFilter()
            else icons[idx].setColorFilter(if (on) RED else Color.WHITE)
            badges[idx]?.setTextColor(if (on) RED else Color.WHITE)
        }
    }

    private fun openDrawer() {
        homeViews.forEach { it.visibility = View.GONE }
        drawer.visibility = View.VISIBLE
    }

    private fun closeDrawer() {
        drawer.visibility = View.GONE
        homeViews.forEach { it.visibility = View.VISIBLE }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (drawer.visibility == View.VISIBLE) { play(sExit); closeDrawer() }
        // En un launcher, "atrás" en la pantalla de inicio no hace nada.
    }
}
