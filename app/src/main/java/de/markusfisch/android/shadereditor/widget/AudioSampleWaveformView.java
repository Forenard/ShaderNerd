package de.markusfisch.android.shadereditor.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public class AudioSampleWaveformView extends View {
	private static final float[] EMPTY = new float[0];

	@NonNull
	private final Paint waveformPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	@NonNull
	private final Paint activeWaveformPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	@NonNull
	private final Paint playheadPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	@NonNull
	private final Paint loadingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

	@NonNull
	private float[] amplitudes = EMPTY;
	private boolean loading;
	private float previewProgress = -1f;

	public AudioSampleWaveformView(Context context) {
		super(context);
		init();
	}

	public AudioSampleWaveformView(Context context, @Nullable AttributeSet attrs) {
		super(context, attrs);
		init();
	}

	public AudioSampleWaveformView(Context context,
			@Nullable AttributeSet attrs,
			int defStyleAttr) {
		super(context, attrs, defStyleAttr);
		init();
	}

	public void setWaveform(@Nullable float[] amplitudes, boolean loading) {
		this.amplitudes = amplitudes != null ? amplitudes : EMPTY;
		this.loading = loading;
		invalidate();
	}

	public void setPreviewProgress(float previewProgress) {
		this.previewProgress = previewProgress;
		invalidate();
	}

	private void init() {
		waveformPaint.setColor(0x38FFFFFF);
		waveformPaint.setStrokeWidth(2f);

		activeWaveformPaint.setColor(0x70FFFFFF);
		activeWaveformPaint.setStrokeWidth(2f);

		playheadPaint.setColor(0xB0FFFFFF);
		playheadPaint.setStrokeWidth(3f);

		loadingPaint.setColor(0x18FFFFFF);
		loadingPaint.setStrokeWidth(2f);
	}

	@Override
	protected void onDraw(@NonNull Canvas canvas) {
		super.onDraw(canvas);

		float width = getWidth();
		float height = getHeight();
		float centerY = height * .5f;
		float maxAmplitudeHeight = height * .42f;

		if (amplitudes.length == 0) {
			if (loading) {
				float step = Math.max(8f, width / 24f);
				for (float x = 0f; x < width; x += step) {
					canvas.drawLine(x, centerY - 4f, x, centerY + 4f, loadingPaint);
				}
			}
			if (previewProgress >= 0f) {
				float playheadX = previewProgress * width;
				canvas.drawLine(playheadX, 0f, playheadX, height, playheadPaint);
			}
			return;
		}

		float step = width / amplitudes.length;
		float playedUntil = previewProgress >= 0f ? previewProgress * width : -1f;
		for (int i = 0; i < amplitudes.length; ++i) {
			float x = (i + .5f) * step;
			float amplitude = Math.max(0f, Math.min(1f, amplitudes[i]));
			float lineHeight = Math.max(2f, amplitude * maxAmplitudeHeight);
			Paint paint = playedUntil >= 0f && x <= playedUntil
					? activeWaveformPaint
					: waveformPaint;
			canvas.drawLine(x, centerY - lineHeight, x, centerY + lineHeight, paint);
		}

		if (previewProgress >= 0f) {
			float playheadX = previewProgress * width;
			canvas.drawLine(playheadX, 0f, playheadX, height, playheadPaint);
		}
	}
}
