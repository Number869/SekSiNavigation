package com.number869.seksinavigation

import android.annotation.SuppressLint
import android.os.Build
import android.window.BackEvent
import android.window.OnBackAnimationCallback
import android.window.OnBackInvokedDispatcher
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.annotation.RequiresApi
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.EaseOutExpo
import androidx.compose.animation.core.EaseOutQuart
import androidx.compose.animation.core.animateIntSizeAsState
import androidx.compose.animation.core.animateOffsetAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.roundToInt


// display all of the composables here, as overlays. use ExpandableWrapper
// only to calculate the composables original/collapsed originalBounds and report
// its position on the screen. then display the overlayed one with the
// position and originalBounds from its related ExpandableWrapper until expanded.
// in case its expanded - switch to animated offset and originalBounds.
@Composable
fun OverlayLayout(
	state: OverlayLayoutState,
	thisOfActivity: ComponentActivity,
	nonOverlayContent: @Composable () -> Unit
) {
	var screenSize by remember { mutableStateOf(IntSize.Zero) }
	val density = LocalDensity.current.density

	val firstOverlayKey by remember { derivedStateOf { state.overlayStack.firstOrNull() } }
	val lastOverlayKey by remember { derivedStateOf { state.overlayStack.lastOrNull() } }
	val isOverlaying by remember {
		derivedStateOf {
			firstOverlayKey != null
		}
	}

	val firstOverlayExpansionFraction by remember{
		derivedStateOf {
			state.itemsState[firstOverlayKey]?.sizeAgainstOriginalAnimationProgress?.heightProgress ?: 0f
		}
	}

	handleBackGesture(state, thisOfActivity)

	val nonOverlayScrimColor by animateColorAsState(
		MaterialTheme.colorScheme.scrim.copy(
			if (!isOverlaying)
				0f
			else
				if(state.itemsState[firstOverlayKey]?.isExpanded == true)
					0.3f
				else
					0.3f * (firstOverlayExpansionFraction)
		),
		label = ""
	)

	val processedNonOverlayScale: () -> Float = { 1f - firstOverlayExpansionFraction * 0.1f }

	// TODO make scale fraction add up
	Box(
		Modifier
			.fillMaxSize()
			.onSizeChanged {
				screenSize = IntSize(
					(it.width / density).toInt(),
					(it.height / density).toInt()
				)
			}
	) {
		Box(
			Modifier
				.drawWithContent {
					drawContent()
					drawRect(nonOverlayScrimColor)
				}
				.graphicsLayer {
					scaleX = processedNonOverlayScale()
					scaleY = processedNonOverlayScale()
				}
		) {
			nonOverlayContent()
		}

		// display the overlayed composables with the position and size
		// from its related ExpandableWrapper until expanded
		state.overlayStack.forEach { key ->
			val itemState by remember { derivedStateOf { state.itemsState[key]!! } }

			// needed for the animations to start as soon as the
			// composable is rendered
			LaunchedEffect(Unit) {
				state.itemsState.replace(
					key,
					itemState.copy(
						isExpanded = true,
						isOverlaying = true
					)
				)
			}

			// this one is for the scrim
			val isOverlayAboveOtherOverlays = lastOverlayKey == key

			val lastOverlayExpansionFraction = if (isOverlayAboveOtherOverlays) {
				0f
			} else {
				state.itemsState[lastOverlayKey]?.sizeAgainstOriginalAnimationProgress?.heightProgress ?: 0f
			}


			val isExpanded by remember{ derivedStateOf { itemState.isExpanded } }
			val originalSize by remember{
				derivedStateOf {
					IntSize(
						(itemState.originalBounds.size.width / density).toInt(),
						(itemState.originalBounds.size.height / density).toInt()
					)
				}
			}
			val originalOffset by remember{ derivedStateOf { itemState.originalBounds.topLeft  } }
			var overlayBounds by remember { mutableStateOf(Rect.Zero) }

			val backGestureProgress by remember{
				derivedStateOf {
					EaseOutQuart.transform(
						itemState.backGestureProgress
					)
				}
			}

			val backGestureSwipeEdge by remember { derivedStateOf { itemState.backGestureSwipeEdge } }
			val backGestureOffset by remember { derivedStateOf { itemState.backGestureOffset } }

			val positionAnimationSpec = if (isExpanded)
				tween<Offset>(600, 0, easing = EaseOutExpo)
			else
				spring(0.97f, 500f)

			val alignmentAnimationSpec: AnimationSpec<Float> = if (isExpanded)
				tween(600, 0, easing = EaseOutExpo)
			else
				spring( 0.97f, 500f)

			val sizeAnimationSpec = if (isExpanded)
				tween<IntSize>(600, 0, easing = EaseOutExpo)
			else
				spring(0.97f, 500f)

			// there must be a way to calculate animation duration without
			// hardcoding a number
			val onSwipeScaleChangeExtent = 0.15f
			val onSwipeOffsetXChangeExtent = 0.15f
			val onSwipeOffsetYChangeExtent = 0.1f
			val onSwipeOffsetYPrevalence = backGestureProgress * 1f
			// the higher the number above is - the earlier the gesture will
			// fully depend the vertical swipe offset

			// interpolates from 0 to 1 over the specified duration
			// into "animationProgress"
			var animationProgress by remember { mutableStateOf(0f) }

			var isAnimating by remember { mutableStateOf(true) }

			var useGestureValues by remember { mutableStateOf(false) }

			// by default is original offset and becomes a static target
			// offset once collapse animation starts so that
			// offset deviation can be calculated. this is needed for
			// the whole animation to move with content with close
			// to 0 latency
			var initialTargetOffset by remember { mutableStateOf(originalOffset) }

			val offsetDeviationFromTarget = if (isExpanded)
				Offset.Zero
			else
				Offset(
				originalOffset.x - initialTargetOffset.x,
				originalOffset.y - initialTargetOffset.y
			)

			val offsetExpandedWithSwipeProgress: () -> Offset = {
				Offset(
					if (backGestureSwipeEdge == 0)
					// if swipe is from the left side
						((screenSize.width * onSwipeOffsetXChangeExtent) * backGestureProgress)
					else
					// if swipe is from the right side
						(-(screenSize.width * onSwipeOffsetXChangeExtent) * backGestureProgress),
					((backGestureOffset.y + (-screenSize.height * backGestureProgress * 2)) * onSwipeOffsetYChangeExtent) * onSwipeOffsetYPrevalence
				)
			}

			val animatedSize by animateIntSizeAsState(
				if (isExpanded) {
					if (itemState.expandedSize == DpSize.Unspecified)
						screenSize
					else IntSize(
						itemState.expandedSize.width.value.toInt(),
						itemState.expandedSize.height.value.toInt()
					)
				} else {
					originalSize
				},
				sizeAnimationSpec,
				label = ""
			)

			val animatedOffset by animateOffsetAsState(
				if (isExpanded && !useGestureValues)
					Offset.Zero
				else if (!isExpanded)
					initialTargetOffset
				else
					offsetExpandedWithSwipeProgress(),
				positionAnimationSpec,
				label = ""
			)

			val animatedScrim by animateColorAsState(
				MaterialTheme.colorScheme.scrim.copy(
					if (isOverlayAboveOtherOverlays)
						0f
					else
						if(state.itemsState[lastOverlayKey]?.isExpanded == true)
							0.3f
						else
							0.3f * (lastOverlayExpansionFraction)
				), label = ""
			)


			val animatedAlignment by animateAlignmentAsState(
				if (isExpanded) Alignment.Center else Alignment.TopStart,
				alignmentAnimationSpec
			)

			val processedOffset: () -> IntOffset = {
				if (useGestureValues) IntOffset(
					offsetExpandedWithSwipeProgress().x.roundToInt(),
					offsetExpandedWithSwipeProgress().y.roundToInt()
				) else
					IntOffset(
						(animatedOffset.x + offsetDeviationFromTarget.x).roundToInt(),
						(animatedOffset.y + offsetDeviationFromTarget.y).roundToInt()
					)
			}

			val processedSize: () -> DpSize = {
				DpSize(
					animatedSize.width.dp,
					animatedSize.height.dp
				)
			}

			val processedScale: () -> Float = {
				// scale when another overlay is being displayed
				((1f - lastOverlayExpansionFraction * 0.1f)
				+
				// scale with gestures
				(backGestureProgress * -onSwipeScaleChangeExtent)
				// scale back to normal when gestures are completed
				*
				animationProgress)
			}

			LaunchedEffect(isExpanded) {
				// only report this once
				if (!isExpanded) initialTargetOffset = originalOffset
			}

			LaunchedEffect(backGestureOffset, animatedOffset) {
				useGestureValues = backGestureProgress != 0f && isExpanded
			}

			LaunchedEffect(animatedOffset) {
				// bruh
				// calculate from the overlays actual location on screen
				animationProgress = ((-(overlayBounds.top - itemState.originalBounds.top) / (itemState.originalBounds.top - Rect.Zero.top)) +  -(overlayBounds.left - itemState.originalBounds.left) / (itemState.originalBounds.left - Rect.Zero.left)) / 2
				state.setItemsOffsetAnimationProgress(
					key,
					animationProgress
				)

				if (animationProgress == 0f) {
					// when the items are in place - wait a bit and then
					// decide that the animation is done
					// because it might cross the actual position before
					// the spring animation is done
					delay(50)
					isAnimating = false
				}

				if (!isExpanded && !isAnimating) {
					state.itemsState.replace(key, itemState.copy(isOverlaying = false))
					state.overlayStack.remove(key)
				}

				// TODO fix scale fraction
//				val widthScaleFraction = animatedSize.width / screenSize.width.toFloat()
//				val heightScaleFraction = animatedSize.height / screenSize.height.toFloat()
//
//				state.setScaleFraction(
//					key,
//					ScaleFraction(widthScaleFraction, heightScaleFraction)
//				)
			}

			// i dont remember why i thought this was needed
			LaunchedEffect(animatedSize) {
				val widthForOriginalProgressCalculation = (processedSize().width.value - originalSize.width) / (screenSize.width - originalSize.width)
				val heightForOriginalProgressCalculation = (processedSize().height.value - originalSize.height) / (screenSize.height - originalSize.height)

				state.setItemsSizeAgainstOriginalProgress(
					key,
					SizeAgainstOriginalAnimationProgress(
						max(widthForOriginalProgressCalculation, 0f),
						max(heightForOriginalProgressCalculation, 0f),
						max((widthForOriginalProgressCalculation + heightForOriginalProgressCalculation) / 2, 0f)
					)
				)
			}

			if (itemState.isOverlaying) {
				Box(
					Modifier
						// full screen scrim and then draw content
						.fillMaxSize()
						.drawWithContent {
							drawContent()
							drawRect(animatedScrim)
						}
				) {
					Box(
						Modifier
							.offset { processedOffset() }
							.size(processedSize())
							.align(animatedAlignment)
							.clickable(
								indication = null,
								interactionSource = remember { MutableInteractionSource() }
							) {
								// workaround that fixes elements being clickable
								// under the overlay
							}
							.onGloballyPositioned { overlayBounds = it.boundsInWindow() }
							.graphicsLayer {
								scaleX = processedScale()
								scaleY = processedScale()
							}
					) {
						// display content
						// TODO fix color scheme default colors not being applied
						// on text and icons
						state.getItemsContent(key)()
					}
				}
			}
		}
	}
}

@SuppressLint("CoroutineCreationDuringComposition")
@Composable
fun handleBackGesture(state: OverlayLayoutState, thisOfActivity: ComponentActivity) {
	val firstOverlayKey by remember { derivedStateOf { state.overlayStack.firstOrNull() } }
	val lastOverlayKey by remember { derivedStateOf { state.overlayStack.lastOrNull() } }
	val isOverlaying by remember { derivedStateOf { firstOverlayKey != null } }

	val scope = rememberCoroutineScope()

	if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU || Build.VERSION.CODENAME == "UpsideDownCake") {
		rememberCoroutineScope().launch {
			val onBackPressedCallback = @RequiresApi(34) object: OnBackAnimationCallback {
				override fun onBackInvoked() = state.closeLastOverlay()

				override fun onBackProgressed(backEvent: BackEvent) {
					super.onBackProgressed(backEvent)

					// does running it in a coroutine even help performance
					scope.launch {
						val itemState = state.itemsState[lastOverlayKey]

						if (itemState != null) {
							lastOverlayKey?.let {
								state.updateGestureValues(it, backEvent)
							}
						}
					}
				}
			}

			// why doesnt his work TODO
			if (isOverlaying)  {
				if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
					thisOfActivity.onBackInvokedDispatcher.registerOnBackInvokedCallback(
						OnBackInvokedDispatcher.PRIORITY_OVERLAY,
						onBackPressedCallback
					)
				}
			} else {
				if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
					thisOfActivity.onBackInvokedDispatcher.unregisterOnBackInvokedCallback(onBackPressedCallback)
				}
			}
		}
	} else {
		BackHandler(isOverlaying) { state.closeLastOverlay() }
	}
}