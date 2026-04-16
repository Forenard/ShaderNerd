package de.markusfisch.android.shadereditor.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatSeekBar;
import androidx.core.content.ContextCompat;

import de.markusfisch.android.shadereditor.R;

public class VerticalSeekBar extends AppCompatSeekBar {
	@NonNull
	private final Paint inactiveTrackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	@NonNull
	private final Paint activeTrackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	@NonNull
	private final Paint thumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	@NonNull
	private final Paint thumbStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	@NonNull
	private final RectF trackRect = new RectF();

	public VerticalSeekBar(@NonNull Context context) {
		super(context);
		init(context);
	}

	public VerticalSeekBar(@NonNull Context context,
			@Nullable AttributeSet attrs) {
		super(context, attrs);
		init(context);
	}

	public VerticalSeekBar(@NonNull Context context,
			@Nullable AttributeSet attrs,
			int defStyleAttr) {
		super(context, attrs, defStyleAttr);
		init(context);
	}

	private void init(@NonNull Context context) {
		setSplitTrack(false);
		inactiveTrackPaint.setStyle(Paint.Style.FILL);
		inactiveTrackPaint.setColor(0x66ffffff);
		activeTrackPaint.setStyle(Paint.Style.FILL);
		activeTrackPaint.setColor(ContextCompat.getColor(context, R.color.accent));
		thumbPaint.setStyle(Paint.Style.FILL);
		thumbPaint.setColor(ContextCompat.getColor(context, R.color.accent));
		thumbStrokePaint.setStyle(Paint.Style.STROKE);
		thumbStrokePaint.setStrokeWidth(dpToPx(2f));
		thumbStrokePaint.setColor(0xaa000000);
	}

	@Override
	protected synchronized void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
		setMeasuredDimension(
				View.getDefaultSize(getSuggestedMinimumWidth(), widthMeasureSpec),
				View.getDefaultSize(getSuggestedMinimumHeight(), heightMeasureSpec));
	}

	@Override
	protected void onSizeChanged(int w, int h, int oldw, int oldh) {
		super.onSizeChanged(w, h, oldw, oldh);
	}

	@Override
	protected synchronized void onDraw(@NonNull Canvas canvas) {
		float width = getWidth();
		float height = getHeight();
		float thumbRadius = Math.min(width * 0.34f, dpToPx(10f));
		float trackWidth = Math.max(dpToPx(6f), width * 0.18f);
		float top = getPaddingTop() + thumbRadius;
		float bottom = height - getPaddingBottom() - thumbRadius;
		float available = Math.max(1f, bottom - top);
		float progressFraction = getMax() > 0
				? getProgress() / (float) getMax()
				: 0f;
		float thumbCenterY = bottom - available * progressFraction;
		float alpha = isEnabled() ? 1f : 0.38f;

		trackRect.set((width - trackWidth) / 2f, top,
				(width + trackWidth) / 2f, bottom);
		inactiveTrackPaint.setAlpha(Math.round(0x66 * alpha));
		activeTrackPaint.setAlpha(Math.round(0xff * alpha));
		thumbPaint.setAlpha(Math.round(0xff * alpha));
		thumbStrokePaint.setAlpha(Math.round(0xaa * alpha));

		canvas.drawRoundRect(trackRect, trackWidth / 2f, trackWidth / 2f,
				inactiveTrackPaint);

		trackRect.top = thumbCenterY;
		canvas.drawRoundRect(trackRect, trackWidth / 2f, trackWidth / 2f,
				activeTrackPaint);

		float thumbCenterX = width / 2f;
		canvas.drawCircle(thumbCenterX, thumbCenterY, thumbRadius, thumbPaint);
		canvas.drawCircle(thumbCenterX, thumbCenterY, thumbRadius, thumbStrokePaint);
	}

	@Override
	public boolean onTouchEvent(@NonNull MotionEvent event) {
		if (!isEnabled()) {
			return false;
		}

		switch (event.getAction()) {
			case MotionEvent.ACTION_DOWN:
				getParent().requestDisallowInterceptTouchEvent(true);
				setPressed(true);
				updateProgress(event.getY());
				return true;
			case MotionEvent.ACTION_MOVE:
				updateProgress(event.getY());
				return true;
			case MotionEvent.ACTION_UP:
				updateProgress(event.getY());
				getParent().requestDisallowInterceptTouchEvent(false);
				setPressed(false);
				performClick();
				return true;
			case MotionEvent.ACTION_CANCEL:
				getParent().requestDisallowInterceptTouchEvent(false);
				setPressed(false);
				return true;
			default:
				return super.onTouchEvent(event);
		}
	}

	@Override
	public synchronized void setProgress(int progress) {
		super.setProgress(progress);
		invalidate();
	}

	@Override
	public boolean performClick() {
		return super.performClick();
	}

	private void updateProgress(float touchY) {
		int progress = getMax() - Math.round(getMax() * touchY / Math.max(1f, getHeight()));
		setProgress(Math.max(0, Math.min(getMax(), progress)));
	}

	private float dpToPx(float value) {
		return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,
				value, getResources().getDisplayMetrics());
	}
}
