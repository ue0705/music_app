package com.cellocoach.ui.screens

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cellocoach.ui.PracticeViewModel
import com.cellocoach.ui.TestTags
import kotlin.math.roundToInt

/**
 * Home screen — score picker (bundled + imported), import (file / URL), practice
 * tempo and metronome controls, calibration status, and the entry button.
 */
@Composable
fun HomeScreen(vm: PracticeViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var showUrlDialog by remember { mutableStateOf(false) }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) {
            val name = displayName(context, uri)
            val bytes = runCatching {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            }.getOrNull()
            if (bytes != null) vm.importScore(name, bytes) else vm.clearImportStatus()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            text = "選一首曲子開始練習",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )

        // ---- Import row ----
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { filePicker.launch(arrayOf("*/*")) },
                modifier = Modifier.testTag(TestTags.HOME_IMPORT_FILE),
            ) { Text("從檔案匯入") }
            OutlinedButton(
                onClick = { showUrlDialog = true },
                modifier = Modifier.testTag(TestTags.HOME_IMPORT_URL),
            ) { Text("從網址匯入") }
        }
        vm.importStatus?.let {
            Text(it, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag(TestTags.HOME_IMPORT_STATUS))
        }

        Text("選擇曲目", style = MaterialTheme.typography.titleMedium)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .testTag(TestTags.HOME_SCORE_PICKER),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            vm.availableScores.forEach { name ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(TestTags.HOME_SCORE_OPTION_PREFIX + name)
                        .clickable { vm.selectScore(name) },
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = name == vm.selectedScore,
                            onClick = { vm.selectScore(name) },
                        )
                        Text(name, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        }

        // ---- Practice tempo (metronome-style ♩ = N) ----
        Text("練習速度", style = MaterialTheme.typography.titleSmall)
        Row(
            modifier = Modifier.fillMaxWidth().testTag(TestTags.HOME_TEMPO),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(
                onClick = { vm.adjustPracticeBpm(-1) },
                enabled = vm.practiceBpm > PracticeViewModel.MIN_PRACTICE_BPM,
                modifier = Modifier.testTag(TestTags.HOME_BPM_MINUS),
            ) { Text("−") }
            Text(
                text = "♩ = ${vm.practiceBpm.roundToInt()}",
                modifier = Modifier.testTag(TestTags.HOME_BPM_VALUE),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            OutlinedButton(
                onClick = { vm.adjustPracticeBpm(+1) },
                enabled = vm.practiceBpm < PracticeViewModel.MAX_PRACTICE_BPM,
                modifier = Modifier.testTag(TestTags.HOME_BPM_PLUS),
            ) { Text("+") }
        }
        Slider(
            value = vm.practiceBpm.toFloat(),
            onValueChange = { vm.changePracticeBpm(it.toDouble()) },
            valueRange = PracticeViewModel.MIN_PRACTICE_BPM.toFloat()..PracticeViewModel.MAX_PRACTICE_BPM.toFloat(),
            modifier = Modifier.fillMaxWidth().testTag(TestTags.HOME_BPM_SLIDER),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "樂譜原速 ♩ = ${vm.bpm.roundToInt()}",
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(
                onClick = { vm.resetPracticeBpm() },
                enabled = vm.practiceBpm.roundToInt() != vm.bpm.roundToInt(),
                modifier = Modifier.testTag(TestTags.HOME_BPM_RESET),
            ) { Text("回到原速") }
        }

        // ---- Wait-until-correct drill (works at any tempo) ----
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(
                checked = vm.waitMode,
                onCheckedChange = { vm.changeWaitMode(it) },
                modifier = Modifier.testTag(TestTags.HOME_WAIT_MODE),
            )
            Text(
                "等正確：拉對目前這顆音才會前進，適合逐句慢練",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = 8.dp),
            )
        }

        // ---- Metronome toggle ----
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(
                checked = vm.metronomeOn,
                onCheckedChange = { vm.toggleMetronome() },
                modifier = Modifier.testTag(TestTags.HOME_METRONOME),
            )
            Text(
                "節拍器（建議戴耳機，喇叭外放可能干擾收音）",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = 8.dp),
            )
        }

        // Saved-calibration badge.
        val status = if (vm.hasSavedTuning) {
            "已校正${vm.savedTuningAt?.let { "（$it）" } ?: ""}"
        } else {
            "尚未校正（將先進行四弦校正）"
        }
        Text(
            text = status,
            modifier = Modifier.testTag(TestTags.HOME_TUNING_STATUS),
            style = MaterialTheme.typography.bodyMedium,
        )

        Button(
            onClick = { vm.onStartPressed() },
            modifier = Modifier.fillMaxWidth().testTag(TestTags.HOME_START),
        ) { Text("開始練習") }

        OutlinedButton(
            onClick = { vm.beginCalibration() },
            modifier = Modifier.fillMaxWidth().testTag(TestTags.HOME_CALIBRATE),
        ) { Text("重新校正") }
    }

    if (showUrlDialog) {
        UrlImportDialog(
            onConfirm = { url -> showUrlDialog = false; vm.importFromUrl(url) },
            onDismiss = { showUrlDialog = false },
        )
    }
}

@Composable
private fun UrlImportDialog(onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var url by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("從網址匯入樂譜") },
        text = {
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                singleLine = true,
                label = { Text("MusicXML / .mxl 的網址") },
                modifier = Modifier.fillMaxWidth().testTag(TestTags.URL_INPUT),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(url) },
                modifier = Modifier.testTag(TestTags.URL_CONFIRM),
            ) { Text("下載") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** Best-effort human filename for a content Uri. */
private fun displayName(context: Context, uri: Uri): String {
    var name = uri.lastPathSegment ?: "imported.musicxml"
    runCatching {
        context.contentResolver.query(
            uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null,
        )?.use { c ->
            if (c.moveToFirst()) {
                val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (i >= 0) c.getString(i)?.let { name = it }
            }
        }
    }
    return name
}
