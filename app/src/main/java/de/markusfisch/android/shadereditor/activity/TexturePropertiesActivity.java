package de.markusfisch.android.shadereditor.activity;

import android.content.Context;
import android.content.Intent;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Bundle;

import androidx.annotation.NonNull;

import de.markusfisch.android.shadereditor.R;
import de.markusfisch.android.shadereditor.fragment.Sampler2dPropertiesFragment;
import de.markusfisch.android.shadereditor.view.SystemBarMetrics;

public class TexturePropertiesActivity extends AbstractSubsequentActivity {
	private static final String IMAGE_URI = "image_uri";

	@NonNull
	public static Intent getIntentForImage(Context context, Uri imageUri) {
		Intent intent = new Intent(context, TexturePropertiesActivity.class);
		intent.putExtra(IMAGE_URI, imageUri);
		return intent;
	}

	@Override
	protected void onCreate(Bundle state) {
		super.onCreate(state);
		setContentView(R.layout.activity_texture_properties);

		SystemBarMetrics.initMainLayout(this, null);
		AbstractSubsequentActivity.initToolbar(this);

		if (state == null) {
			Uri imageUri = getIntent().getParcelableExtra(IMAGE_URI);
			if (imageUri == null) {
				finish();
				return;
			}
			// Use full image (no crop): rect covers entire image
			RectF fullRect = new RectF(0f, 0f, 1f, 1f);
			addFragment(
					getSupportFragmentManager(),
					Sampler2dPropertiesFragment.newInstance(imageUri, fullRect, 0f));
		}
	}
}
