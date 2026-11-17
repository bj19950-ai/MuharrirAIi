package com.example.export.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.export.docx.WordGenerator
import com.example.export.model.WordExportDataGateway
import com.example.export.model.WordExportMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class WordExportUiState(
    val isModeDialogVisible: Boolean = false,
    val isExporting: Boolean = false,
    val lastExportedFile: File? = null,
    val statusMessage: String? = null,
    val errorMessage: String? = null,
)

sealed interface WordExportEvent {
    data class ShareDocxFile(val file: File, val documentTitle: String) : WordExportEvent
}

class WordExportViewModel(
    private val dataGateway: WordExportDataGateway,
    private val wordGenerator: WordGenerator = WordGenerator(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : ViewModel() {

    private val _uiState = MutableStateFlow(WordExportUiState())
    val uiState: StateFlow<WordExportUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<WordExportEvent>()
    val events: SharedFlow<WordExportEvent> = _events.asSharedFlow()

    fun openModeChooser() {
        _uiState.update { it.copy(isModeDialogVisible = true, errorMessage = null, statusMessage = null) }
    }

    fun dismissModeChooser() {
        _uiState.update { it.copy(isModeDialogVisible = false) }
    }

    fun dismissError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    fun dismissStatusMessage() {
        _uiState.update { it.copy(statusMessage = null) }
    }

    fun reportShareError(message: String) {
        _uiState.update { it.copy(errorMessage = message) }
    }

    fun exportDocument(
        context: Context,
        documentId: String,
        mode: WordExportMode,
    ) {
        val appContext = context.applicationContext
        _uiState.update {
            it.copy(
                isModeDialogVisible = false,
                isExporting = true,
                errorMessage = null,
                statusMessage = null,
            )
        }

        viewModelScope.launch {
            try {
                val document = withContext(ioDispatcher) { dataGateway.getDocument(documentId) }
                if (document == null) {
                    _uiState.update {
                        it.copy(isExporting = false, errorMessage = "تعذّر العثور على المستند.")
                    }
                    return@launch
                }
                if (document.reviewApprovedAt == null) {
                    _uiState.update {
                        it.copy(
                            isExporting = false,
                            errorMessage = "يجب تأكيد واعتماد المراجعة أولاً قبل التصدير إلى Word.",
                        )
                    }
                    return@launch
                }

                val now = clock()
                val generatedFile = withContext(ioDispatcher) {
                    val pages = dataGateway.getPagesInOrder(documentId)
                    val items = dataGateway.getReviewItemsInOrder(documentId)
                    val highlightUnresolved = dataGateway.shouldHighlightUnresolved()
                    val outFile = wordGenerator.buildOutputFile(appContext, document.title, now)
                    val written = wordGenerator.generate(
                        document = document,
                        pages = pages,
                        reviewItems = items,
                        mode = mode,
                        outputFile = outFile,
                        highlightUnresolved = highlightUnresolved,
                    )
                    dataGateway.recordExportedDocument(
                        documentId = documentId,
                        docxFilePath = written.absolutePath,
                        mode = mode,
                        exportedAtMillis = now,
                    )
                    written
                }

                _uiState.update {
                    it.copy(
                        isExporting = false,
                        lastExportedFile = generatedFile,
                        statusMessage = "تم إنشاء ملف Word (${generatedFile.name}) وحفظه بنجاح.",
                        errorMessage = null,
                    )
                }
                _events.emit(WordExportEvent.ShareDocxFile(generatedFile, document.title))
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Exception) {
                _uiState.update {
                    it.copy(
                        isExporting = false,
                        errorMessage = "تعذّر إنشاء ملف Word. حاول مرة أخرى.",
                    )
                }
            }
        }
    }

    fun shareExistingDocx(filePath: String, documentTitle: String) {
        val file = File(filePath)
        if (!file.exists() || !file.isFile) {
            _uiState.update {
                it.copy(errorMessage = "ملف Word السابق غير موجود على الجهاز؛ يمكنك إعادة إنشائه.")
            }
            return
        }
        viewModelScope.launch {
            _events.emit(WordExportEvent.ShareDocxFile(file, documentTitle))
        }
    }
}

object WordShareLauncher {
    private const val DOCX_MIME_TYPE =
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document"

    fun shareDocx(context: Context, file: File, documentTitle: String): Result<Unit> = runCatching {
        val authority = "${context.packageName}.fileprovider"
        val contentUri: Uri = FileProvider.getUriForFile(context, authority, file)

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = DOCX_MIME_TYPE
            putExtra(Intent.EXTRA_STREAM, contentUri)
            putExtra(Intent.EXTRA_SUBJECT, documentTitle)
            clipData = ClipData.newRawUri(documentTitle, contentUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        val chooser = Intent.createChooser(shareIntent, "مشاركة أو فتح ملف Word")
        if (context !is android.app.Activity) {
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }
}
