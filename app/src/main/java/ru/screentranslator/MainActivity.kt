package ru.screentranslator

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme { SettingsScreen() }
        }
    }
}

@Composable
fun SettingsScreen() {
    val ctx = LocalContext.current
    val settings = remember { AppSettings(ctx) }
    val running by ScreenTranslatorService.running.collectAsState()

    var provider by remember { mutableStateOf(settings.provider) }
    var gcAuth by remember { mutableStateOf(settings.gigachatAuthKey) }
    var gcScope by remember { mutableStateOf(settings.gigachatScope) }
    var cfAccount by remember { mutableStateOf(settings.cloudflareAccountId) }
    var cfToken by remember { mutableStateOf(settings.cloudflareToken) }
    var model by remember { mutableStateOf(settings.model(provider)) }
    var targetLang by remember { mutableStateOf(settings.targetLang) }
    var ocr by remember { mutableStateOf(settings.ocrScript) }
    var status by remember { mutableStateOf("") }
    var canOverlay by remember { mutableStateOf(Settings.canDrawOverlays(ctx)) }

    fun save() {
        settings.provider = provider
        settings.setModel(provider, model.trim())
        settings.gigachatAuthKey = gcAuth.trim()
        settings.gigachatScope = gcScope.trim()
        settings.cloudflareAccountId = cfAccount.trim()
        settings.cloudflareToken = cfToken.trim()
        settings.targetLang = targetLang.trim()
        settings.ocrScript = ocr
        status = "Сохранено"
    }

    val overlayLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        canOverlay = Settings.canDrawOverlays(ctx)
    }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val projectionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            ContextCompat.startForegroundService(
                ctx,
                Intent(ctx, ScreenTranslatorService::class.java)
                    .setAction(ScreenTranslatorService.ACTION_START)
                    .putExtra(ScreenTranslatorService.EXTRA_CODE, result.resultCode)
                    .putExtra(ScreenTranslatorService.EXTRA_DATA, result.data),
            )
        }
    }

    fun startTranslation() {
        save()
        if (Build.VERSION.SDK_INT >= 33) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        projectionLauncher.launch(
            ctx.getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent()
        )
    }

    Scaffold { padding ->
        Column(
            Modifier
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Провайдер перевода", style = MaterialTheme.typography.titleMedium)
            Provider.entries.forEach { p ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = provider == p,
                        onClick = {
                            settings.setModel(provider, model.trim())
                            provider = p
                            model = settings.model(p)
                        },
                    )
                    Text(p.label)
                }
            }

            if (provider == Provider.GIGACHAT) {
                OutlinedTextField(
                    gcAuth, { gcAuth = it },
                    label = { Text("Authorization key (Base64)") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    gcScope, { gcScope = it },
                    label = { Text("Scope") },
                    supportingText = { Text("GIGACHAT_API_PERS или GIGACHAT_API_CORP") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                OutlinedTextField(
                    cfAccount, { cfAccount = it },
                    label = { Text("Account ID") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    cfToken, { cfToken = it },
                    label = { Text("API token") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Text("Модель", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                model, { model = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            provider.models.forEach { m ->
                FilterChip(selected = model == m, onClick = { model = m }, label = { Text(m) })
            }

            OutlinedTextField(
                targetLang, { targetLang = it },
                label = { Text("Язык перевода (например: Russian)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Text("Язык текста на экране (OCR)", style = MaterialTheme.typography.titleMedium)
            OcrScript.entries.forEach { o ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = ocr == o, onClick = { ocr = o })
                    Text(o.label)
                }
            }

            Button(onClick = { save() }, modifier = Modifier.fillMaxWidth()) { Text("Сохранить") }

            if (!canOverlay) {
                Button(
                    onClick = {
                        overlayLauncher.launch(
                            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}"))
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Разрешить показ поверх других окон") }
            }

            Button(
                onClick = { startTranslation() },
                enabled = canOverlay && !running,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Запустить перевод") }

            if (running) {
                OutlinedButton(
                    onClick = {
                        ctx.startService(
                            Intent(ctx, ScreenTranslatorService::class.java)
                                .setAction(ScreenTranslatorService.ACTION_SELECT)
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Выбрать область") }
                OutlinedButton(
                    onClick = {
                        ctx.startService(
                            Intent(ctx, ScreenTranslatorService::class.java)
                                .setAction(ScreenTranslatorService.ACTION_STOP)
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Остановить") }
            }

            if (status.isNotEmpty()) Text(status)
        }
    }
}
