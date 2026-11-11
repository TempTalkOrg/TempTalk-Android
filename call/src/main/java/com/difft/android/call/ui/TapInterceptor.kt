package com.difft.android.call.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput










fun Modifier.tapInterceptor(
    enabled: Boolean = true,
    onTap: () -> Unit,
): Modifier = if (!enabled) this else this.then(
    Modifier.pointerInput(Unit) {
        val longPressTimeout = viewConfiguration.longPressTimeoutMillis
        val slop = viewConfiguration.touchSlop
        awaitPointerEventScope {
            while (true) {
                
                val downEvent = awaitPointerEvent(PointerEventPass.Initial)
                val down = downEvent.changes.firstOrNull { it.pressed } ?: continue
                val downPos = down.position
                val downTime = down.uptimeMillis
                var moved = false
                var multiTouch = downEvent.changes.count { it.pressed } > 1
                var primaryUp: PointerInputChange? = null
                
                
                do {
                    val e = awaitPointerEvent(PointerEventPass.Initial)
                    
                    if (e.changes.count { it.pressed } > 1) {
                        multiTouch = true
                    }
                    e.changes.forEach { change ->
                        
                        if (!moved && change.id == down.id &&
                            (change.position - downPos).getDistance() > slop
                        ) {
                            moved = true
                        }
                        
                        if (!change.pressed && change.id == down.id && primaryUp == null) {
                            primaryUp = change
                        }
                    }
                } while (e.changes.any { it.pressed })
                val up = primaryUp
                val isTap = !multiTouch && !moved && up != null &&
                    (up.uptimeMillis - downTime) < longPressTimeout
                if (isTap) {
                    
                    up.consume()
                    onTap()
                }
            }
        }
    }
)
