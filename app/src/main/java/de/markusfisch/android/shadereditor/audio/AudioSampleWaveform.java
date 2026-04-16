package de.markusfisch.android.shadereditor.audio;

import androidx.annotation.NonNull;

public record AudioSampleWaveform(
		@NonNull String relativePath,
		int durationMs,
		@NonNull float[] amplitudes
) {
}
