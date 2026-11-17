package com.pixelisland

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat

class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var master: Switch

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(48), dp(20), dp(32))
        }
        col.addView(TextView(this).apply { text = "Pixel Island"; textSize = 28f })
        status = TextView(this).apply { setPadding(0, dp(8), 0, dp(8)) }
        col.addView(status)

        master = Switch(this).apply {
            text = "Остров включён"
            textSize = 18f
            isChecked = Prefs.enabled(this@MainActivity)
        }
        master.setOnCheckedChangeListener { _, on -> onMaster(on) }
        col.addView(master)

        col.addView(button("Разрешить «Поверх других окон»") {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        })
        col.addView(button("Разрешить доступ к уведомлениям") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        })

        col.addView(TextView(this).apply {
            text = "Размер и положение"
            textSize = 20f
            setPadding(0, dp(20), 0, 0)
        })
        slider(col, "Ширина в покое", "cw", 60, 200)
        slider(col, "Высота в покое", "ch", 20, 60)
        slider(col, "Отступ сверху", "y", 0, 40)
        slider(col, "Сдвиг влево и вправо", "x", -60, 60)
        slider(col, "Ширина раскрытого", "ew", 240, 400)
        slider(col, "Высота раскрытого", "eh", 64, 140)
        slider(col, "Скругление", "r", 10, 50)

        val fly = Switch(this).apply {
            text = "Анимация вылета в остров"
            isChecked = Prefs.get(this@MainActivity, "fly") == 1
            setPadding(0, dp(20), 0, dp(8))
        }
        fly.setOnCheckedChangeListener { _, on -> Prefs.set(this, "fly", if (on) 1 else 0) }
        col.addView(fly)

        col.addView(button("Показать раскрытым") {
            Prefs.sp(this).edit().putLong("demo", System.nanoTime()).apply()
        })
        col.addView(button("Проверить вылет в остров") {
            Prefs.sp(this).edit().putLong("demofly", System.nanoTime()).apply()
        })

        setContentView(ScrollView(this).apply { addView(col) })
    }

    override fun onResume() {
        super.onResume()
        val overlay = Settings.canDrawOverlays(this)
        val listener = NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)
        status.text = "Поверх окон: " + (if (overlay) "да" else "нет") +
            "\nДоступ к уведомлениям: " + (if (listener) "да" else "нет")
        if (Prefs.enabled(this) && overlay) {
            startForegroundService(Intent(this, IslandService::class.java))
        }
    }

    private fun onMaster(on: Boolean) {
        if (!on) {
            Prefs.setEnabled(this, false)
            stopService(Intent(this, IslandService::class.java))
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            master.isChecked = false
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
            return
        }
        Prefs.setEnabled(this, true)
        startForegroundService(Intent(this, IslandService::class.java))
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        setOnClickListener { onClick() }
    }

    private fun slider(parent: LinearLayout, label: String, key: String, min: Int, max: Int) {
        val tv = TextView(this).apply { setPadding(0, dp(12), 0, 0) }
        val sb = SeekBar(this)
        val cur = Prefs.get(this, key)
        tv.text = label + ": " + cur + " dp"
        sb.max = max - min
        sb.progress = cur - min
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                val v = p + min
                tv.text = label + ": " + v + " dp"
                Prefs.set(this@MainActivity, key, v)
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        parent.addView(tv)
        parent.addView(sb)
    }
}
