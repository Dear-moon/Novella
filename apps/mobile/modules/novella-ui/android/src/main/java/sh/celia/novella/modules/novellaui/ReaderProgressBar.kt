package sh.celia.novella.modules.novellaui

import android.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import expo.modules.kotlin.records.Field
import expo.modules.kotlin.records.Record
import expo.modules.kotlin.types.OptimizedRecord
import expo.modules.kotlin.views.ComposeProps
import expo.modules.kotlin.views.FunctionalComposableScope
import expo.modules.kotlin.views.OptimizedComposeProps
import expo.modules.ui.composeOrNull

@OptimizedRecord
data class ReaderProgressChangeEvent(
  @Field val value: Double = 0.0
) : Record

@OptimizedComposeProps
data class ReaderProgressBarProps(
  val accentColor: Color? = null,
  val currentPage: Int = 0,
  val direction: String = "ltr",
  val disabled: Boolean = false,
  val progress: Double = 0.0,
  val remainingText: String = "",
  val totalPages: Int = 0
) : ComposeProps

/**
 * Reader progress slider drawn in Compose so the reader does not depend on a
 * JS slider library. Reports raw 0..1 positions; snapping and RTL mapping stay
 * on the JS side, which owns the reader's page semantics.
 */
@Composable
fun FunctionalComposableScope.ReaderProgressBarContent(
  props: ReaderProgressBarProps,
  onProgressChange: (ReaderProgressChangeEvent) -> Unit
) {
  val accent = props.accentColor.composeOrNull ?: MaterialTheme.colorScheme.primary
  val unfilled = MaterialTheme.colorScheme.surfaceContainerHighest
  val knobColor = MaterialTheme.colorScheme.surface
  val pageColor = MaterialTheme.colorScheme.onSurface
  val remainingColor = MaterialTheme.colorScheme.onSurfaceVariant
  val progress = props.progress.coerceIn(0.0, 1.0).toFloat()
  val pageLabel = if (props.totalPages > 0) "${props.currentPage} / ${props.totalPages}" else ""

  BoxWithConstraints(modifier = Modifier.fillMaxWidth().height(40.dp)) {
    val density = LocalDensity.current
    val sidePadding = 12.dp
    val sidePaddingPx = with(density) { sidePadding.toPx() }
    val usablePx = (constraints.maxWidth - sidePaddingPx * 2f).coerceAtLeast(1f)
    val filled = with(density) { (usablePx * progress).toDp() }

    fun report(positionX: Float) {
      if (props.disabled) return
      val value = ((positionX - sidePaddingPx) / usablePx).coerceIn(0f, 1f)
      onProgressChange(ReaderProgressChangeEvent(value.toDouble()))
    }

    Box(
      modifier = Modifier
        .align(Alignment.TopCenter)
        .fillMaxWidth()
        .height(24.dp)
        .offset(y = 10.dp)
        .pointerInput(props.disabled) {
          detectTapGestures { offset -> report(offset.x) }
        }
        .pointerInput(props.disabled) {
          detectDragGestures(
            onDragStart = { offset -> report(offset.x) },
            onDrag = { change, _ -> report(change.position.x) }
          )
        }
    ) {
      Box(
        modifier = Modifier
          .align(Alignment.CenterStart)
          .padding(horizontal = sidePadding)
          .fillMaxWidth()
          .height(3.dp)
          .background(unfilled, RoundedCornerShape(2.dp))
      )
      Box(
        modifier = Modifier
          .align(Alignment.CenterStart)
          .offset(x = sidePadding)
          .width(filled)
          .height(3.dp)
          .background(accent, RoundedCornerShape(2.dp))
      )
      Box(
        modifier = Modifier
          .align(Alignment.CenterStart)
          .offset(x = sidePadding + filled - 9.dp)
          .width(18.dp)
          .height(12.dp)
          .shadow(1.5.dp, RoundedCornerShape(6.dp))
          .background(knobColor, RoundedCornerShape(6.dp))
      )
    }

    Text(
      text = pageLabel,
      color = pageColor,
      fontSize = 10.sp,
      textAlign = TextAlign.Center,
      modifier = Modifier
        .align(Alignment.BottomCenter)
        .fillMaxWidth()
    )
    Text(
      text = props.remainingText,
      color = remainingColor,
      fontSize = 10.sp,
      modifier = Modifier
        .align(Alignment.BottomEnd)
        .padding(end = 16.dp)
    )
  }
}
