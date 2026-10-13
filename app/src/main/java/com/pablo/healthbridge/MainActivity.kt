package com.pablo.healthbridge

import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var status: TextView
    private lateinit var urlField: EditText
    private lateinit var tokenField: EditText
    private lateinit var originField: EditText

    private val permLauncher = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        status.text = if (granted.containsAll(Syncer.CORE)) {
            "Permisos concedidos. Ya puedes sincronizar."
        } else {
            "Faltan permisos (${granted.size}/${Syncer.ALL.size}). Vuelve a pulsar el botón 1."
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun field(hint: String, value: String): EditText {
        val e = EditText(this)
        e.hint = hint
        e.setText(value)
        e.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        e.setSingleLine(true)
        return e
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(20), dp(32), dp(20), dp(20))

        val title = TextView(this)
        title.text = "Health Bridge"
        title.textSize = 24f
        root.addView(title)

        val help = TextView(this)
        help.text = "Lee Health Connect (Samsung Health) y envía los datos a tu servidor."
        help.setPadding(0, dp(8), 0, dp(16))
        root.addView(help)

        urlField = field("URL del servidor", Config.url(this))
        tokenField = field("Token", Config.token(this))
        originField = field("Paquete de origen (vacío = todos)", Config.origin(this))
        root.addView(urlField)
        root.addView(tokenField)
        root.addView(originField)

        val btnPerm = Button(this)
        btnPerm.text = "1. Guardar y pedir permisos"
        btnPerm.setOnClickListener { saveAndRequest() }
        root.addView(btnPerm)

        val btnSync = Button(this)
        btnSync.text = "2. Sincronizar ahora"
        btnSync.setOnClickListener { syncNow() }
        root.addView(btnSync)

        status = TextView(this)
        status.text = Config.status(this)
        status.setPadding(0, dp(16), 0, 0)
        root.addView(status)

        val sv = ScrollView(this)
        sv.addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(sv)
    }

    private fun persist() {
        Config.save(this, urlField.text.toString(), tokenField.text.toString(), originField.text.toString())
    }

    private fun saveAndRequest() {
        persist()
        schedulePeriodic()
        val st = HealthConnectClient.getSdkStatus(this)
        if (st != HealthConnectClient.SDK_AVAILABLE) {
            status.text = "Health Connect no está disponible o necesita instalarse/actualizarse."
            return
        }
        try {
            permLauncher.launch(Syncer.ALL)
        } catch (e: Exception) {
            status.text = "No se pudo pedir permisos: ${e.message}"
        }
    }

    private fun schedulePeriodic() {
        val req = PeriodicWorkRequestBuilder<SyncWorker>(30, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(this)
            .enqueueUniquePeriodicWork("health-sync", ExistingPeriodicWorkPolicy.UPDATE, req)
    }

    private fun syncNow() {
        persist()
        status.text = "Sincronizando..."
        scope.launch {
            val msg = withContext(Dispatchers.IO) {
                try {
                    Syncer.run(this@MainActivity)
                } catch (e: Exception) {
                    "Error: ${e.message}"
                }
            }
            Config.setStatus(this@MainActivity, msg)
            status.text = msg
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
