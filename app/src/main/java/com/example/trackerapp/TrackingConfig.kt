package com.example.trackerapp

import kotlinx.coroutines.flow.MutableStateFlow

object TrackingConfig {
    val noiseThreshold = MutableStateFlow(0.5f) // Expanded range
    val velocityDecay = MutableStateFlow(0.90f)
    val distanceScale = MutableStateFlow(1.00f)
    val turnStabilizer = MutableStateFlow(true) // Turn ZUPT toggle
    val cardinalSnapping = MutableStateFlow(true) // Snap to 90 degree increments
}
