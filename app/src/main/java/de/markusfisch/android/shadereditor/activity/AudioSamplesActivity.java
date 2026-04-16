package de.markusfisch.android.shadereditor.activity;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

import androidx.fragment.app.Fragment;

import de.markusfisch.android.shadereditor.fragment.AudioSamplesFragment;

public class AudioSamplesActivity extends AbstractContentActivity {
	public static final String STATEMENT = "statement";

	public static void setSampleResult(Activity activity, String statement) {
		Bundle bundle = new Bundle();
		bundle.putString(STATEMENT, statement);

		Intent data = new Intent();
		data.putExtras(bundle);

		activity.setResult(RESULT_OK, data);
	}

	@Override
	protected Fragment defaultFragment() {
		return new AudioSamplesFragment();
	}
}
