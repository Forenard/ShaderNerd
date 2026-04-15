package de.markusfisch.android.shadereditor.activity.managers;

import android.app.Activity;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.ViewCompat;

import de.markusfisch.android.shadereditor.R;
import de.markusfisch.android.shadereditor.audio.AudioShaderPlayer;
import de.markusfisch.android.shadereditor.app.ShaderEditorApp;
import de.markusfisch.android.shadereditor.opengl.ShaderRenderer;

public final class AudioShaderPlayerManager {
	private static final float MIN_BPM = 40f;
	private static final float MAX_BPM = 240f;

	@NonNull
	private final AudioShaderPlayer player = new AudioShaderPlayer();
	@NonNull
	private final View play;
	@NonNull
	private final View pause;
	@NonNull
	private final View bpmContainer;
	@NonNull
	private final EditText bpmInput;
	private boolean applyingBpmText;

	@Nullable
	private String currentSource;

	public AudioShaderPlayerManager(@NonNull Activity activity) {
		play = activity.findViewById(R.id.run_code);
		pause = activity.findViewById(R.id.pause_audio);
		bpmContainer = activity.findViewById(R.id.audio_bpm_container);
		bpmInput = activity.findViewById(R.id.audio_bpm_input);

		ViewCompat.setTooltipText(pause, activity.getText(R.string.audio_pause));
		pause.setOnClickListener(v -> {
			player.pause();
			refreshUi();
		});

		bpmInput.setInputType(InputType.TYPE_CLASS_NUMBER |
				InputType.TYPE_NUMBER_FLAG_DECIMAL);
		bpmInput.setOnEditorActionListener((TextView v, int actionId, KeyEvent event) -> {
			boolean isDone = actionId == EditorInfo.IME_ACTION_DONE ||
					actionId == EditorInfo.IME_ACTION_NEXT;
			boolean isEnter = event != null &&
					event.getKeyCode() == KeyEvent.KEYCODE_ENTER &&
					event.getAction() == KeyEvent.ACTION_DOWN;
			if (!isDone && !isEnter) {
				return false;
			}
			applyBpmInput();
			bpmInput.clearFocus();
			return true;
		});
		bpmInput.setOnFocusChangeListener((v, hasFocus) -> {
			if (!hasFocus) {
				applyBpmInput();
			}
		});

		refreshUi();
	}

	public void setAudioShader(@Nullable String source) {
		currentSource = normalize(source);
		if (currentSource == null) {
			player.pause();
			player.stop();
			player.clearPlaybackClock();
			refreshUi();
			return;
		}
		ensureStarted();
		player.setSource(currentSource);
		refreshUi();
	}

	public void onPause() {
		player.pause();
		player.stop();
		player.clearPlaybackClock();
		refreshUi();
	}

	public void onDestroy() {
		player.stop();
		player.clearPlaybackClock();
	}

	public void playFromStart() {
		if (currentSource == null) {
			return;
		}
		ensureStarted();
		player.setSource(currentSource);
		player.playFromStart();
		refreshUi();
	}

	public boolean hasAudioShader() {
		return currentSource != null;
	}

	@NonNull
	public ShaderRenderer.TimeSource getTimeSource() {
		return player::getPlaybackTimeSeconds;
	}

	public void refreshUi() {
		boolean hasAudio = currentSource != null;
		play.setVisibility(shouldShowPlay(hasAudio) ? View.VISIBLE : View.GONE);
		pause.setVisibility(hasAudio ? View.VISIBLE : View.GONE);
		pause.setEnabled(hasAudio && player.isPlaying());
		bpmContainer.setVisibility(hasAudio ? View.VISIBLE : View.GONE);
		updateBpmText();
	}

	private void ensureStarted() {
		if (currentSource == null) {
			return;
		}
		player.start();
		player.setSource(currentSource);
	}

	private void applyBpmInput() {
		if (applyingBpmText) {
			return;
		}
		String text = bpmInput.getText() != null ? bpmInput.getText().toString().trim() : "";
		if (text.isEmpty()) {
			updateBpmText();
			return;
		}
		try {
			float bpm = Float.parseFloat(text);
			player.setBpm(Math.max(MIN_BPM, Math.min(MAX_BPM, bpm)));
		} catch (NumberFormatException ignored) {
		}
		updateBpmText();
	}

	private void updateBpmText() {
		applyingBpmText = true;
		bpmInput.setText(String.format(java.util.Locale.US, "%.0f", player.getBpm()));
		bpmInput.setSelection(bpmInput.getText().length());
		applyingBpmText = false;
	}

	private boolean shouldShowPlay(boolean hasAudio) {
		return hasAudio || !ShaderEditorApp.preferences.doesRunOnChange();
	}

	@Nullable
	private static String normalize(@Nullable String source) {
		if (source == null) {
			return null;
		}
		String trimmed = source.trim();
		return trimmed.isEmpty() ? null : source;
	}
}
