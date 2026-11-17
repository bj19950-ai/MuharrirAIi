package com.example.review.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.review.model.BoundingBox
import kotlin.math.min

@Composable
fun ZoomablePageViewer(
    pageBitmap: Bitmap?,
    bbox: BoundingBox?,
    itemId: Long?,
    isLoading: Boolean,
    isError: Boolean,
    modifier: Modifier = Modifier,
) {
    var scale by remember(itemId) { mutableFloatStateOf(1f) }
    var offset by remember(itemId) { mutableStateOf(Offset.Zero) }

    LaunchedEffect(itemId) {
        scale = 1f
        offset = Offset.Zero
    }

    val highlightStrokeColor = MaterialTheme.colorScheme.error
    val highlightFillColor = highlightStrokeColor.copy(alpha = 0.16f)

    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .testTag("page_image_viewer"),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        tonalElevation = 1.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            val containerWidthPx = with(LocalDensity.current) { maxWidth.toPx() }
            val containerHeightPx = with(LocalDensity.current) { maxHeight.toPx() }

            when {
                isLoading -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CircularProgressIndicator()
                        Text(
                            text = "جاري تحميل صورة الصفحة...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                isError || pageBitmap == null -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(24.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.BrokenImage,
                            contentDescription = "تعذّر تحميل صورة الصفحة",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(40.dp),
                        )
                        Text(
                            text = "تعذّر تحميل صورة الصفحة من التخزين",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                else -> {
                    val imageBitmap = remember(pageBitmap) { pageBitmap.asImageBitmap() }
                    val bitmapWidth = pageBitmap.width.toFloat().coerceAtLeast(1f)
                    val bitmapHeight = pageBitmap.height.toFloat().coerceAtLeast(1f)

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(itemId, containerWidthPx, containerHeightPx) {
                                detectTransformGestures { _, pan, zoom, _ ->
                                    val newScale = (scale * zoom).coerceIn(1f, 6f)
                                    val maxOffsetX = (containerWidthPx * (newScale - 1f)) / 2f
                                    val maxOffsetY = (containerHeightPx * (newScale - 1f)) / 2f
                                    val newOffset = if (newScale <= 1f) {
                                        Offset.Zero
                                    } else {
                                        Offset(
                                            x = (offset.x + pan.x).coerceIn(-maxOffsetX, maxOffsetX),
                                            y = (offset.y + pan.y).coerceIn(-maxOffsetY, maxOffsetY),
                                        )
                                    }
                                    scale = newScale
                                    offset = newOffset
                                }
                            }
                            .pointerInput(itemId) {
                                detectTapGestures(
                                    onDoubleTap = {
                                        if (scale > 1.1f) {
                                            scale = 1f
                                            offset = Offset.Zero
                                        } else {
                                            scale = 2.5f
                                        }
                                    },
                                )
                            },
                    ) {
                        Image(
                            bitmap = imageBitmap,
                            contentDescription = "صورة الصفحة الأصلية",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    transformOrigin = TransformOrigin.Center
                                    scaleX = scale
                                    scaleY = scale
                                    translationX = offset.x
                                    translationY = offset.y
                                },
                        )

                        if (bbox != null && bbox.right > bbox.left && bbox.bottom > bbox.top) {
                            Canvas(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .testTag("bbox_overlay_canvas")
                                    .semantics {
                                        contentDescription = "موضع النص المحدد على الصفحة"
                                    },
                            ) {
                                val fitScale = min(
                                    size.width / bitmapWidth,
                                    size.height / bitmapHeight,
                                )
                                val renderedW = bitmapWidth * fitScale
                                val renderedH = bitmapHeight * fitScale
                                val imageOriginX = (size.width - renderedW) / 2f
                                val imageOriginY = (size.height - renderedH) / 2f

                                val pivotX = size.width / 2f
                                val pivotY = size.height / 2f

                                fun mapX(pixelX: Float): Float {
                                    val baseX = imageOriginX + pixelX * fitScale
                                    return (baseX - pivotX) * scale + pivotX + offset.x
                                }

                                fun mapY(pixelY: Float): Float {
                                    val baseY = imageOriginY + pixelY * fitScale
                                    return (baseY - pivotY) * scale + pivotY + offset.y
                                }

                                val rectLeft = mapX(bbox.left)
                                val rectTop = mapY(bbox.top)
                                val rectRight = mapX(bbox.right)
                                val rectBottom = mapY(bbox.bottom)

                                val rectWidth = (rectRight - rectLeft).coerceAtLeast(1f)
                                val rectHeight = (rectBottom - rectTop).coerceAtLeast(1f)

                                drawRect(
                                    color = highlightFillColor,
                                    topLeft = Offset(rectLeft, rectTop),
                                    size = Size(rectWidth, rectHeight),
                                )
                                drawRect(
                                    color = highlightStrokeColor,
                                    topLeft = Offset(rectLeft, rectTop),
                                    size = Size(rectWidth, rectHeight),
                                    style = Stroke(width = 2.dp.toPx()),
                                )
                            }
                        }

                        Row(
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(10.dp)
                                .background(
                                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
                                    shape = RoundedCornerShape(12.dp),
                                )
                                .padding(horizontal = 4.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            FilledTonalIconButton(
                                onClick = { scale = (scale + 0.5f).coerceAtMost(6f) },
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("zoom_in_button"),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Add,
                                    contentDescription = "تكبير الصفحة",
                                )
                            }
                            Text(
                                text = "${(scale * 100).toInt()}%",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 6.dp),
                            )
                            FilledTonalIconButton(
                                onClick = {
                                    val nextScale = (scale - 0.5f).coerceAtLeast(1f)
                                    scale = nextScale
                                    if (nextScale <= 1f) offset = Offset.Zero
                                },
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("zoom_out_button"),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Remove,
                                    contentDescription = "تصغير الصفحة",
                                )
                            }
                            FilledTonalIconButton(
                                onClick = {
                                    scale = 1f
                                    offset = Offset.Zero
                                },
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("zoom_reset_button"),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = "إعادة ضبط التكبير",
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun EnlargedCropCard(
    croppedBitmap: Bitmap?,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("enlarged_crop_card"),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "معاينة مكبّرة للموضع (3×)",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )

            if (croppedBitmap == null) {
                Text(
                    text = "المعاينة المكبّرة غير متوفرة لهذا الموضع.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                val density = LocalDensity.current
                val scaledWidthDp = with(density) { (croppedBitmap.width * 3f).toDp() }
                val scaledHeightDp = with(density) { (croppedBitmap.height * 3f).toDp() }
                val imageBitmap = remember(croppedBitmap) { croppedBitmap.asImageBitmap() }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 64.dp, max = 160.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                        .horizontalScroll(rememberScrollState())
                        .verticalScroll(rememberScrollState()),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        bitmap = imageBitmap,
                        contentDescription = "لقطة مكبرة بنسبة ثلاثة أضعاف حول موضع المراجعة",
                        contentScale = ContentScale.FillBounds,
                        modifier = Modifier.requiredSize(width = scaledWidthDp, height = scaledHeightDp),
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
            }
        }
    }
}
