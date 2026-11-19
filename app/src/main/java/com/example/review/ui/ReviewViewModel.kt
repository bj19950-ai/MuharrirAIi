package com.example.review.ui

import android.graphics.Bitmap
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.review.model.BoundingBox
import com.example.review.model.DiscrepancyCategory
import com.example.review.model.DocumentReviewGateway
import com.example.review.model.PageBitmapSource
import com.example.review.model.ReviewItemModel
import com.example.review.model.ReviewQueueGateway
import com.example.review.model.ReviewStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

class ReviewViewModel(
    private val documentId: String,
    private val reviewQueueGateway: ReviewQueueGateway,
    private val documentReviewGateway: DocumentReviewGateway,
    private val pageBitmapSource: PageBitmapSource,
    private val savedStateHandle: SavedStateHandle = SavedStateHandle(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val defaultDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val clock: () -> Long = { System.currentTimeMillis() },
    initialAutoAcceptHighConfidence: Boolean = false,
    private val onAutoAcceptHighConfidenceChanged: (Boolean) -> Unit = {},
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        ReviewUiState(
            documentId = documentId,
            autoAcceptHighConfidence = initialAutoAcceptHighConfidence,
        ),
    )
    val uiState: StateFlow<ReviewUiState> = _uiState.asStateFlow()

    private val pageBitmapCache = mutableMapOf<Int, Bitmap>()
    private var lastLoadedPageNumber: Int? = null
    private var lastCroppedKey: Pair<Long, Int>? = null
    private var bitmapJob: Job? = null

    init {
        if (documentId.isNotBlank()) {
            observeReviewData()
        } else {
            _uiState.update { it.copy(isLoading = false) }
        }
    }

    private fun observeReviewData() {
        viewModelScope.launch {
            combine(
                reviewQueueGateway.getReviewQueue(documentId),
                reviewQueueGateway.countByStatus(documentId),
                documentReviewGateway.observeDocument(documentId),
            ) { items, stats, docInfo ->
                Triple(items, stats, docInfo)
            }
                .catch {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = "تعذّر تحميل قائمة المراجعة. حاول مرة أخرى.",
                        )
                    }
                }
                .collect { (items, stats, docInfo) ->
                    val currentState = _uiState.value
                    val filtered = applyFilter(
                        items = items,
                        filter = currentState.selectedFilter,
                        includeAgreement = currentState.includeAgreementInAll,
                        ignoreMinorDifferences = currentState.ignoreMinorDifferences,
                    )

                    val preferredId = currentState.currentItem?.id
                        ?: savedStateHandle.get<Long>(KEY_CURRENT_ITEM_ID)
                        ?: ReviewPositionStore.getLastItemId(documentId)

                    val resolvedIndex = resolveTargetIndex(filtered, preferredId, currentState.currentIndex)
                    val targetItem = filtered.getOrNull(resolvedIndex)
                    val itemChanged = targetItem?.id != currentState.currentItem?.id

                    if (targetItem != null) {
                        savedStateHandle[KEY_CURRENT_ITEM_ID] = targetItem.id
                        ReviewPositionStore.setLastItemId(documentId, targetItem.id)
                    }

                    val nextEditedText = when {
                        targetItem == null -> ""
                        itemChanged -> ReviewArabicFormatter.initialApprovedText(targetItem)
                        currentState.isManualEditEnabled -> currentState.editedText
                        else -> ReviewArabicFormatter.initialApprovedText(targetItem)
                    }

                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            documentTitle = docInfo?.title.orEmpty(),
                            reviewApprovedAt = docInfo?.reviewApprovedAt,
                            allItems = items,
                            filteredItems = filtered,
                            currentItem = targetItem,
                            currentIndex = resolvedIndex,
                            stats = stats,
                            editedText = nextEditedText,
                            isManualEditEnabled = if (itemChanged) false else it.isManualEditEnabled,
                        )
                    }

                    if (targetItem != null) {
                        ensureBitmapAndCrop(targetItem)
                    } else {
                        lastLoadedPageNumber = null
                        lastCroppedKey = null
                        _uiState.update {
                            it.copy(
                                pageBitmap = null,
                                enlargedCropBitmap = null,
                                isBitmapLoading = false,
                                bitmapLoadError = false,
                            )
                        }
                    }
                }
        }
    }

    private fun applyFilter(
        items: List<ReviewItemModel>,
        filter: ReviewFilter,
        includeAgreement: Boolean,
        ignoreMinorDifferences: Boolean,
    ): List<ReviewItemModel> {
        return when (filter) {
            ReviewFilter.ALL -> items.filter { item ->
                val matchesAgreement = includeAgreement || item.category != DiscrepancyCategory.A
                val isMinorDifference = ignoreMinorDifferences && isMinorDifferenceItem(item)
                matchesAgreement && !isMinorDifference
            }
            ReviewFilter.DISCREPANCY_ONLY -> items.filter { item ->
                item.category == DiscrepancyCategory.B &&
                    !(ignoreMinorDifferences && isMinorDifferenceItem(item))
            }
            ReviewFilter.UNRESOLVED_ONLY -> items.filter { it.reviewStatus == ReviewStatus.UNRESOLVED }
            ReviewFilter.PENDING_ONLY -> items.filter { item ->
                (item.reviewStatus == ReviewStatus.PENDING || item.isStale) &&
                    !(ignoreMinorDifferences && isMinorDifferenceItem(item))
            }
            ReviewFilter.IGNORED_ONLY -> items.filter { it.reviewStatus == ReviewStatus.IGNORED }
        }
    }

    private fun isMinorDifferenceItem(item: ReviewItemModel): Boolean =
        item.category == DiscrepancyCategory.B &&
            !item.suggestedText.isNullOrBlank() &&
            normalizeMinorArabic(item.ocrText) == normalizeMinorArabic(item.suggestedText)

    private fun normalizeMinorArabic(text: String): String {
        return text
            .replace(Regex("[أإآ]"), "ا")
            .replace('ة', 'ه')
            .replace('ى', 'ي')
            .replace(Regex("\\s+"), "")
            .trim()
    }

    private fun resolveTargetIndex(
        filtered: List<ReviewItemModel>,
        preferredItemId: Long?,
        fallbackIndex: Int,
    ): Int {
        if (filtered.isEmpty()) return 0
        if (preferredItemId != null) {
            val found = filtered.indexOfFirst { it.id == preferredItemId }
            if (found >= 0) return found
        }
        return fallbackIndex.coerceIn(0, filtered.lastIndex)
    }

    private fun ensureBitmapAndCrop(item: ReviewItemModel) {
        val cachedBitmap = pageBitmapCache[item.pageNumber]
        if (cachedBitmap != null) {
            if (lastLoadedPageNumber != item.pageNumber) {
                lastLoadedPageNumber = item.pageNumber
                _uiState.update {
                    it.copy(
                        pageBitmap = cachedBitmap,
                        isBitmapLoading = false,
                        bitmapLoadError = false,
                    )
                }
            }
            updateCropFromBitmap(item, cachedBitmap)
            return
        }

        bitmapJob?.cancel()
        bitmapJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isBitmapLoading = true,
                    bitmapLoadError = false,
                    enlargedCropBitmap = null,
                )
            }
            val loaded = withContext(ioDispatcher) {
                runCatching { pageBitmapSource.loadPageBitmap(documentId, item.pageNumber) }
                    .getOrNull()
            }
            if (loaded != null) {
                pageBitmapCache[item.pageNumber] = loaded
                lastLoadedPageNumber = item.pageNumber
                val crop = computeEnlargedCrop(loaded, item.bbox)
                lastCroppedKey = item.id to item.pageNumber
                _uiState.update {
                    it.copy(
                        pageBitmap = loaded,
                        enlargedCropBitmap = crop,
                        isBitmapLoading = false,
                        bitmapLoadError = false,
                    )
                }
            } else {
                _uiState.update {
                    it.copy(
                        pageBitmap = null,
                        enlargedCropBitmap = null,
                        isBitmapLoading = false,
                        bitmapLoadError = true,
                    )
                }
            }
        }
    }

    private fun updateCropFromBitmap(item: ReviewItemModel, bitmap: Bitmap) {
        val cropKey = item.id to item.pageNumber
        if (lastCroppedKey == cropKey && _uiState.value.enlargedCropBitmap != null) return
        viewModelScope.launch {
            val crop = computeEnlargedCrop(bitmap, item.bbox)
            lastCroppedKey = cropKey
            _uiState.update { it.copy(enlargedCropBitmap = crop) }
        }
    }

    private suspend fun computeEnlargedCrop(
        source: Bitmap,
        bbox: BoundingBox?,
    ): Bitmap? = withContext(defaultDispatcher) {
        if (bbox == null || source.width <= 0 || source.height <= 0) return@withContext null
        val boxWidth = (bbox.right - bbox.left).coerceAtLeast(1f)
        val boxHeight = (bbox.bottom - bbox.top).coerceAtLeast(1f)
        if (bbox.right <= 0f || bbox.bottom <= 0f) return@withContext null

        // Expand around bbox by 30% margin on each side using the exact same source bitmap.
        val marginX = boxWidth * 0.30f
        val marginY = boxHeight * 0.30f

        val cropLeft = (bbox.left - marginX).toInt().coerceIn(0, source.width - 1)
        val cropTop = (bbox.top - marginY).toInt().coerceIn(0, source.height - 1)
        val cropRight = (bbox.right + marginX).roundToInt().coerceIn(cropLeft + 1, source.width)
        val cropBottom = (bbox.bottom + marginY).roundToInt().coerceIn(cropTop + 1, source.height)

        val cropWidth = (cropRight - cropLeft).coerceAtLeast(1)
        val cropHeight = (cropBottom - cropTop).coerceAtLeast(1)

        Bitmap.createBitmap(source, cropLeft, cropTop, cropWidth, cropHeight)
    }

    fun selectFilter(filter: ReviewFilter) {
        val currentState = _uiState.value
        val filtered = applyFilter(
            items = currentState.allItems,
            filter = filter,
            includeAgreement = currentState.includeAgreementInAll,
            ignoreMinorDifferences = currentState.ignoreMinorDifferences,
        )
        val nextIndex = resolveTargetIndex(filtered, currentState.currentItem?.id, 0)
        selectItemAt(
            filtered = filtered,
            index = nextIndex,
            filter = filter,
            includeAgreement = currentState.includeAgreementInAll,
            ignoreMinorDifferences = currentState.ignoreMinorDifferences,
        )
    }

    fun toggleIncludeAgreement() {
        val currentState = _uiState.value
        val nextInclude = !currentState.includeAgreementInAll
        val filtered = applyFilter(
            items = currentState.allItems,
            filter = currentState.selectedFilter,
            includeAgreement = nextInclude,
            ignoreMinorDifferences = currentState.ignoreMinorDifferences,
        )
        val nextIndex = resolveTargetIndex(filtered, currentState.currentItem?.id, 0)
        selectItemAt(
            filtered = filtered,
            index = nextIndex,
            filter = currentState.selectedFilter,
            includeAgreement = nextInclude,
            ignoreMinorDifferences = currentState.ignoreMinorDifferences,
        )
    }

    fun toggleIgnoreMinorDifferences() {
        val currentState = _uiState.value
        val nextIgnoreMinor = !currentState.ignoreMinorDifferences
        val filtered = applyFilter(
            items = currentState.allItems,
            filter = currentState.selectedFilter,
            includeAgreement = currentState.includeAgreementInAll,
            ignoreMinorDifferences = nextIgnoreMinor,
        )
        val nextIndex = resolveTargetIndex(filtered, currentState.currentItem?.id, 0)
        selectItemAt(
            filtered = filtered,
            index = nextIndex,
            filter = currentState.selectedFilter,
            includeAgreement = currentState.includeAgreementInAll,
            ignoreMinorDifferences = nextIgnoreMinor,
        )
    }

    fun goToPrevious() {
        val currentState = _uiState.value
        if (!currentState.canGoPrevious) return
        selectItemAt(
            filtered = currentState.filteredItems,
            index = currentState.currentIndex - 1,
            filter = currentState.selectedFilter,
            includeAgreement = currentState.includeAgreementInAll,
            ignoreMinorDifferences = currentState.ignoreMinorDifferences,
        )
    }

    fun goToNext() {
        val currentState = _uiState.value
        if (!currentState.canGoNext) return
        selectItemAt(
            filtered = currentState.filteredItems,
            index = currentState.currentIndex + 1,
            filter = currentState.selectedFilter,
            includeAgreement = currentState.includeAgreementInAll,
            ignoreMinorDifferences = currentState.ignoreMinorDifferences,
        )
    }

    fun onPageJumpInputChanged(value: String) {
        val digitsOnly = value.filter { it.isDigit() }
        _uiState.update { it.copy(pageJumpInput = digitsOnly, pageJumpError = null) }
    }

    fun jumpToPage() {
        val state = _uiState.value
        val targetPage = state.pageJumpInput.toIntOrNull()
        if (targetPage == null || targetPage <= 0) {
            _uiState.update { it.copy(pageJumpError = "أدخل رقم صفحة صحيحًا.") }
            return
        }
        val idxInCurrentFilter = state.filteredItems.indexOfFirst { it.pageNumber == targetPage }
        if (idxInCurrentFilter >= 0) {
            selectItemAt(
                filtered = state.filteredItems,
                index = idxInCurrentFilter,
                filter = state.selectedFilter,
                includeAgreement = state.includeAgreementInAll,
                ignoreMinorDifferences = state.ignoreMinorDifferences,
            )
            _uiState.update { it.copy(pageJumpInput = "", pageJumpError = null) }
            return
        }

        val existsInAll = state.allItems.any { it.pageNumber == targetPage }
        val error = if (existsInAll) {
            "لا توجد مواضع في الصفحة $targetPage ضمن الفلتر الحالي."
        } else {
            "الصفحة $targetPage لا تحتوي على مواضع مراجعة."
        }
        _uiState.update { it.copy(pageJumpError = error) }
    }

    private fun selectItemAt(
        filtered: List<ReviewItemModel>,
        index: Int,
        filter: ReviewFilter,
        includeAgreement: Boolean,
        ignoreMinorDifferences: Boolean,
    ) {
        val clamped = if (filtered.isEmpty()) 0 else index.coerceIn(0, filtered.lastIndex)
        val item = filtered.getOrNull(clamped)
        if (item != null) {
            savedStateHandle[KEY_CURRENT_ITEM_ID] = item.id
            ReviewPositionStore.setLastItemId(documentId, item.id)
        }
        _uiState.update {
            it.copy(
                selectedFilter = filter,
                includeAgreementInAll = includeAgreement,
                ignoreMinorDifferences = ignoreMinorDifferences,
                filteredItems = filtered,
                currentItem = item,
                currentIndex = clamped,
                editedText = item?.let(ReviewArabicFormatter::initialApprovedText).orEmpty(),
                isManualEditEnabled = false,
                errorMessage = null,
                pageJumpError = null,
            )
        }
        if (item != null) {
            ensureBitmapAndCrop(item)
        } else {
            _uiState.update { it.copy(pageBitmap = null, enlargedCropBitmap = null) }
        }
    }

    /**
     * Action 1 — "قبول اقتراح Gemini"
     */
    fun approveGeminiSuggestion() {
        val current = _uiState.value.currentItem ?: return
        val suggested = current.suggestedText ?: return
        persistDecisionAndAdvance(
            targetItem = current,
            newStatus = ReviewStatus.APPROVED_GEMINI,
            newUserText = suggested,
            successMessage = "تم قبول اقتراح Gemini وحفظه.",
        )
    }

    /**
     * Action 2 (Step 1) — "تعديل يدوي" enables the text field for editing.
     */
    fun enableManualEdit() {
        val current = _uiState.value.currentItem ?: return
        _uiState.update {
            it.copy(
                isManualEditEnabled = true,
                editedText = it.editedText.ifEmpty { ReviewArabicFormatter.initialApprovedText(current) },
                errorMessage = null,
                savedConfirmationVisible = false,
            )
        }
    }

    fun cancelManualEdit() {
        val current = _uiState.value.currentItem
        _uiState.update {
            it.copy(
                isManualEditEnabled = false,
                editedText = current?.let(ReviewArabicFormatter::initialApprovedText).orEmpty(),
            )
        }
    }

    fun onEditedTextChanged(newText: String) {
        _uiState.update { it.copy(editedText = newText) }
    }

    /**
     * Action 2 (Step 2) — Save edited text immediately to Room and move to next pending.
     */
    fun saveManualEdit() {
        val state = _uiState.value
        val current = state.currentItem ?: return
        persistDecisionAndAdvance(
            targetItem = current,
            newStatus = ReviewStatus.MANUALLY_EDITED,
            newUserText = state.editedText,
            successMessage = "تم حفظ التعديل اليدوي.",
        )
    }

    /**
     * Action 3 — "ترك دون حسم"
     */
    fun markUnresolved() {
        val current = _uiState.value.currentItem ?: return
        persistDecisionAndAdvance(
            targetItem = current,
            newStatus = ReviewStatus.UNRESOLVED,
            newUserText = current.userText,
            successMessage = "تم وضع الموضع كغير محسوم.",
        )
    }

    /**
     * Action 4 — "استبعاد (حذف من المستند)"
     */
    fun ignoreItem() {
        val current = _uiState.value.currentItem ?: return
        persistDecisionAndAdvance(
            targetItem = current,
            newStatus = ReviewStatus.IGNORED,
            newUserText = null,
            successMessage = "تم استبعاد الموضع وحذفه من المستند النهائي.",
        )
    }

    private fun persistDecisionAndAdvance(
        targetItem: ReviewItemModel,
        newStatus: ReviewStatus,
        newUserText: String?,
        successMessage: String,
    ) {
        val snapshot = _uiState.value
        val now = clock()

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isSaving = true,
                    savedConfirmationVisible = false,
                    confirmationMessage = null,
                    errorMessage = null,
                )
            }

            try {
                reviewQueueGateway.saveDecision(
                    itemId = targetItem.id,
                    newStatus = newStatus,
                    userText = newUserText,
                    resolvedAt = now,
                )

                val nextPending = reviewQueueGateway.getNextPending(documentId, targetItem.id)

                val updatedAll = snapshot.allItems.map { item ->
                    if (item.id == targetItem.id) {
                        item.copy(
                            reviewStatus = newStatus,
                            userText = newUserText,
                            resolvedAt = now,
                            isStale = false,
                            staleNoteAr = null,
                        )
                    } else {
                        item
                    }
                }
                val updatedFiltered = applyFilter(
                    items = updatedAll,
                    filter = snapshot.selectedFilter,
                    includeAgreement = snapshot.includeAgreementInAll,
                    ignoreMinorDifferences = snapshot.ignoreMinorDifferences,
                )

                val nextTargetItem = when {
                    nextPending != null -> updatedFiltered.firstOrNull { it.id == nextPending.id }
                        ?: updatedFiltered.firstOrNull {
                            it.id != targetItem.id && (it.reviewStatus == ReviewStatus.PENDING || it.isStale)
                        }
                    else -> null
                }

                val targetIndex = when {
                    nextTargetItem != null -> updatedFiltered.indexOfFirst { it.id == nextTargetItem.id }
                    updatedFiltered.isEmpty() -> 0
                    else -> updatedFiltered.indexOfFirst { it.id == targetItem.id }
                        .takeIf { it >= 0 }
                        ?: snapshot.currentIndex.coerceIn(0, updatedFiltered.lastIndex)
                }

                val resolvedCurrent = updatedFiltered.getOrNull(targetIndex)
                if (resolvedCurrent != null) {
                    savedStateHandle[KEY_CURRENT_ITEM_ID] = resolvedCurrent.id
                    ReviewPositionStore.setLastItemId(documentId, resolvedCurrent.id)
                }

                _uiState.update {
                    it.copy(
                        isSaving = false,
                        allItems = updatedAll,
                        filteredItems = updatedFiltered,
                        currentItem = resolvedCurrent,
                        currentIndex = targetIndex,
                        editedText = resolvedCurrent?.let(ReviewArabicFormatter::initialApprovedText).orEmpty(),
                        isManualEditEnabled = false,
                        savedConfirmationVisible = true,
                        confirmationMessage = successMessage,
                        errorMessage = null,
                    )
                }

                if (resolvedCurrent != null) {
                    ensureBitmapAndCrop(resolvedCurrent)
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Exception) {
                _uiState.value = snapshot.copy(
                    isSaving = false,
                    savedConfirmationVisible = false,
                    confirmationMessage = null,
                    errorMessage = SAVE_ERROR_MESSAGE,
                )
            }
        }
    }

    fun requestReopenItem(reason: ReopenReason) {
        val current = _uiState.value.currentItem ?: return
        _uiState.update {
            it.copy(
                reopenDialog = ReopenConfirmationRequest(itemId = current.id, reason = reason),
                errorMessage = null,
            )
        }
    }

    fun dismissReopenDialog() {
        _uiState.update { it.copy(reopenDialog = null) }
    }

    fun confirmReopenItem() {
        val request = _uiState.value.reopenDialog ?: return
        val snapshot = _uiState.value

        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, reopenDialog = null, errorMessage = null) }
            try {
                reviewQueueGateway.reopenItemAsPending(request.itemId)

                val updatedAll = snapshot.allItems.map { item ->
                    if (item.id == request.itemId) {
                        item.copy(
                            reviewStatus = ReviewStatus.PENDING,
                            isStale = false,
                            staleNoteAr = null,
                        )
                    } else {
                        item
                    }
                }
                val updatedFiltered = applyFilter(
                    items = updatedAll,
                    filter = snapshot.selectedFilter,
                    includeAgreement = snapshot.includeAgreementInAll,
                    ignoreMinorDifferences = snapshot.ignoreMinorDifferences,
                )
                val targetIndex = resolveTargetIndex(updatedFiltered, request.itemId, snapshot.currentIndex)
                val current = updatedFiltered.getOrNull(targetIndex)

                _uiState.update {
                    it.copy(
                        isSaving = false,
                        allItems = updatedAll,
                        filteredItems = updatedFiltered,
                        currentItem = current,
                        currentIndex = targetIndex,
                        editedText = current?.let(ReviewArabicFormatter::initialApprovedText).orEmpty(),
                        isManualEditEnabled = false,
                        savedConfirmationVisible = true,
                        confirmationMessage = "تمت إعادة فتح الموضع للمراجعة.",
                    )
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Exception) {
                _uiState.value = snapshot.copy(
                    isSaving = false,
                    reopenDialog = null,
                    savedConfirmationVisible = false,
                    errorMessage = SAVE_ERROR_MESSAGE,
                )
            }
        }
    }

    fun onApproveReviewClicked() {
        val remaining = _uiState.value.stats.remaining
        if (remaining == 0) {
            approveReviewInternal()
        } else {
            _uiState.update { it.copy(showPartialApprovalDialog = true) }
        }
    }

    fun dismissPartialApprovalDialog() {
        _uiState.update { it.copy(showPartialApprovalDialog = false) }
    }

    fun confirmPartialApproval() {
        _uiState.update { it.copy(showPartialApprovalDialog = false) }
        approveReviewInternal()
    }

    private fun approveReviewInternal() {
        val now = clock()
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, errorMessage = null) }
            try {
                documentReviewGateway.setReviewApprovedAt(documentId, now)
                _uiState.update {
                    it.copy(
                        isSaving = false,
                        reviewApprovedAt = now,
                        approvalSucceeded = true,
                        savedConfirmationVisible = true,
                        confirmationMessage = "تم اعتماد المراجعة بنجاح.",
                    )
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Exception) {
                _uiState.update {
                    it.copy(
                        isSaving = false,
                        savedConfirmationVisible = false,
                        errorMessage = SAVE_ERROR_MESSAGE,
                    )
                }
            }
        }
    }

    fun requestBulkApproveGemini() {
        if (_uiState.value.pendingGeminiApproveCount <= 0) return
        _uiState.update { it.copy(showBulkApproveDialog = true, errorMessage = null) }
    }

    fun dismissBulkApproveDialog() {
        _uiState.update { it.copy(showBulkApproveDialog = false) }
    }

    fun confirmBulkApproveGemini() {
        val snapshot = _uiState.value
        val targets = snapshot.allItems.filter {
            it.category != DiscrepancyCategory.A &&
                it.reviewStatus == ReviewStatus.PENDING &&
                !it.suggestedText.isNullOrBlank()
        }
        if (targets.isEmpty()) {
            _uiState.update { it.copy(showBulkApproveDialog = false) }
            return
        }
        approveItemsBatch(
            targetItems = targets,
            successMessage = "تم قبول ${targets.size} اقتراحاً من Gemini.",
            dismissBulkDialog = true,
        )
    }

    fun syncAutoAcceptHighConfidence(enabled: Boolean) {
        if (_uiState.value.autoAcceptHighConfidence != enabled) {
            _uiState.update { it.copy(autoAcceptHighConfidence = enabled) }
        }
    }

    fun toggleAutoAcceptHighConfidence(enabled: Boolean) {
        onAutoAcceptHighConfidenceChanged(enabled)
        _uiState.update { it.copy(autoAcceptHighConfidence = enabled, errorMessage = null) }
        if (!enabled) return

        val highConfidenceTargets = _uiState.value.allItems.filter { item ->
            item.category == DiscrepancyCategory.B &&
                item.reviewStatus == ReviewStatus.PENDING &&
                !item.suggestedText.isNullOrBlank() &&
                item.ocrText.trim() != item.suggestedText.trim() &&
                (item.geminiConfidence ?: 0f) > HIGH_CONFIDENCE_THRESHOLD
        }
        if (highConfidenceTargets.isNotEmpty()) {
            approveItemsBatch(
                targetItems = highConfidenceTargets,
                successMessage = "تم تفعيل القبول التلقائي واعتماد ${highConfidenceTargets.size} اقتراحاً عالي الثقة.",
                dismissBulkDialog = false,
            )
        }
    }

    private fun approveItemsBatch(
        targetItems: List<ReviewItemModel>,
        successMessage: String,
        dismissBulkDialog: Boolean,
    ) {
        val snapshot = _uiState.value
        val targetIds = targetItems.map { it.id }
        val targetIdSet = targetIds.toSet()
        val now = clock()

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isSaving = true,
                    showBulkApproveDialog = if (dismissBulkDialog) false else it.showBulkApproveDialog,
                    savedConfirmationVisible = false,
                    confirmationMessage = null,
                    errorMessage = null,
                )
            }
            try {
                reviewQueueGateway.approveAllPendingGemini(
                    documentId = documentId,
                    itemIds = targetIds,
                    resolvedAt = now,
                )

                val updatedAll = snapshot.allItems.map { item ->
                    if (item.id in targetIdSet) {
                        item.copy(
                            reviewStatus = ReviewStatus.APPROVED_GEMINI,
                            userText = item.suggestedText,
                            resolvedAt = now,
                            isStale = false,
                            staleNoteAr = null,
                        )
                    } else {
                        item
                    }
                }
                val updatedFiltered = applyFilter(
                    items = updatedAll,
                    filter = snapshot.selectedFilter,
                    includeAgreement = snapshot.includeAgreementInAll,
                    ignoreMinorDifferences = snapshot.ignoreMinorDifferences,
                )
                val targetIndex = resolveTargetIndex(updatedFiltered, snapshot.currentItem?.id, snapshot.currentIndex)
                val resolvedCurrent = updatedFiltered.getOrNull(targetIndex)
                if (resolvedCurrent != null) {
                    savedStateHandle[KEY_CURRENT_ITEM_ID] = resolvedCurrent.id
                    ReviewPositionStore.setLastItemId(documentId, resolvedCurrent.id)
                }

                _uiState.update {
                    it.copy(
                        isSaving = false,
                        showBulkApproveDialog = false,
                        allItems = updatedAll,
                        filteredItems = updatedFiltered,
                        currentItem = resolvedCurrent,
                        currentIndex = targetIndex,
                        editedText = resolvedCurrent?.let(ReviewArabicFormatter::initialApprovedText).orEmpty(),
                        isManualEditEnabled = false,
                        savedConfirmationVisible = true,
                        confirmationMessage = successMessage,
                        errorMessage = null,
                    )
                }

                if (resolvedCurrent != null) {
                    ensureBitmapAndCrop(resolvedCurrent)
                } else {
                    lastLoadedPageNumber = null
                    lastCroppedKey = null
                    _uiState.update {
                        it.copy(
                            pageBitmap = null,
                            enlargedCropBitmap = null,
                            isBitmapLoading = false,
                            bitmapLoadError = false,
                        )
                    }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Exception) {
                _uiState.value = snapshot.copy(
                    isSaving = false,
                    showBulkApproveDialog = false,
                    savedConfirmationVisible = false,
                    confirmationMessage = null,
                    errorMessage = SAVE_ERROR_MESSAGE,
                )
            }
        }
    }

    fun dismissFeedbackBanner() {
        _uiState.update {
            it.copy(
                errorMessage = null,
                savedConfirmationVisible = false,
                confirmationMessage = null,
            )
        }
    }

    companion object {
        private const val KEY_CURRENT_ITEM_ID = "review_current_item_id"
        private const val HIGH_CONFIDENCE_THRESHOLD = 0.90f
        const val SAVE_ERROR_MESSAGE = "تعذّر حفظ قرارك. لم يتم تسجيل التغيير. حاول مرة أخرى."
    }
}
