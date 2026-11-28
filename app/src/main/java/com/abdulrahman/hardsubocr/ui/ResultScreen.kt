@file:OptIn(ExperimentalMaterial3Api::class)

package com.abdulrahman.hardsubocr.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.abdulrahman.hardsubocr.core.Timestamps

@Composable
fun ResultScreen(vm: MainViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val index = vm.editIndex ?: return
    val output = vm.outputs().getOrNull(index)
    var editing by remember { mutableIntStateOf(-1) }
    var draft by remember { mutableStateOf("") }
    var confirmLeave by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val saveAs = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/x-subrip")) {
        vm.saveOutputAs(index, it)
    }

    LaunchedEffect(vm.message) {
        val m = vm.message
        if (m != null) {
            snackbar.showSnackbar(m); vm.message = null
        }
    }

    fun save() {
        if (!vm.saveOutput(index)) saveAs.launch("${output?.name ?: "subtitles"}.srt")
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("${output?.name ?: ""}\u2066.srt\u2069", fontWeight = FontWeight.Bold, maxLines = 1)
                        Text(
                            "${vm.editEntries.size} مقطع" + if (vm.editDirty) " • تعديلات غير محفوظة" else "",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { if (vm.editDirty) confirmLeave = true else onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "رجوع")
                    }
                },
                actions = {
                    val uri = output?.uri
                    if (uri != null) IconButton(onClick = { shareSrt(context, uri) }) { Icon(Icons.Filled.Share, "مشاركة") }
                    IconButton(onClick = { save() }) { Icon(Icons.Filled.Done, "حفظ") }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            itemsIndexed(vm.editEntries) { i, e ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(16.dp))
                        .clickable { editing = i; draft = e.text }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "${i + 1}   ${Timestamps.srt(e.startMs)} → ${Timestamps.srt(e.endMs)}",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(e.text, style = MaterialTheme.typography.bodyLarge)
                    }
                    Spacer(Modifier.width(6.dp))
                    IconButton(onClick = { vm.deleteEntry(i) }) {
                        Icon(Icons.Filled.Delete, "حذف", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }

    if (editing >= 0) {
        AlertDialog(
            onDismissRequest = { editing = -1 },
            title = { Text("تعديل المقطع ${editing + 1}") },
            text = {
                OutlinedTextField(
                    value = draft, onValueChange = { draft = it },
                    modifier = Modifier.fillMaxWidth(), minLines = 2
                )
            },
            confirmButton = {
                TextButton(onClick = { vm.updateEntry(editing, draft); editing = -1 }) { Text("تم") }
            },
            dismissButton = { TextButton(onClick = { editing = -1 }) { Text("إلغاء") } }
        )
    }

    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text("تعديلات غير محفوظة") },
            text = { Text("هل تريد حفظ التعديلات قبل الخروج؟") },
            confirmButton = {
                TextButton(onClick = { confirmLeave = false; save(); onBack() }) { Text("حفظ") }
            },
            dismissButton = {
                TextButton(onClick = { confirmLeave = false; onBack() }) { Text("تجاهل") }
            }
        )
    }
}
