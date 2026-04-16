package de.markusfisch.android.shadereditor.audio;

import androidx.annotation.NonNull;

import java.util.List;

public record AudioSampleDirectoryListing(
		@NonNull String relativePath,
		@NonNull List<AudioSampleBrowserEntry> entries
) {
	public boolean isRoot() {
		return relativePath.isEmpty();
	}
}
