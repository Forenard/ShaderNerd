package de.markusfisch.android.shadereditor.audio;

import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public record AudioSampleInfo(
		@NonNull String displayName,
		@NonNull String relativePath,
		@NonNull String relativeDirectory,
		@NonNull String uniformName,
		long lastModified,
		long size,
		@NonNull Uri uri,
		@Nullable String mimeType
) {
}
