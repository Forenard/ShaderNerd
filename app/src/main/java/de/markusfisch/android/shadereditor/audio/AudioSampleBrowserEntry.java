package de.markusfisch.android.shadereditor.audio;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public record AudioSampleBrowserEntry(
		@NonNull Kind kind,
		@NonNull String name,
		@NonNull String relativePath,
		@Nullable AudioSampleInfo sample
) {
	public enum Kind {
		DIRECTORY,
		SAMPLE
	}

	@NonNull
	public static AudioSampleBrowserEntry directory(@NonNull String name,
			@NonNull String relativePath) {
		return new AudioSampleBrowserEntry(Kind.DIRECTORY, name, relativePath, null);
	}

	@NonNull
	public static AudioSampleBrowserEntry sample(@NonNull AudioSampleInfo sample) {
		return new AudioSampleBrowserEntry(Kind.SAMPLE,
				sample.displayName(),
				sample.relativePath(),
				sample);
	}

	public boolean isDirectory() {
		return kind == Kind.DIRECTORY;
	}

	public boolean isSample() {
		return kind == Kind.SAMPLE && sample != null;
	}
}
