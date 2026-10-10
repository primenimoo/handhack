package com.primenimoo.handhack.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

val HandHackShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

/** Corner radius used by cards and result surfaces. */
val CardRadius = 22.dp

/** Corner radius of the floating capsule navigation bar. */
val NavCapsuleRadius = 30.dp
