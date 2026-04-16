package de.markusfisch.android.shadereditor.audio;

import androidx.annotation.NonNull;

public record DecodedAudioSample(
		@NonNull String uniformName,
		int width,
		int height,
		float sampleRate,
		float durationSeconds,
		@NonNull float[] rgbaBuffer
) {
}
