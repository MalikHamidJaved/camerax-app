package com.example.cameraxsample.extensions

enum class UiState {
    IDLE,       // Not recording, all UI controls are active.
    RECORDING,  // Camera is recording, only display Pause/Resume & Stop button.
    FINALIZED,  // Recording just completes, disable all RECORDING UI controls.
    RECOVERY    // For future use.
}