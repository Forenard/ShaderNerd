package de.markusfisch.android.shadereditor.activity.managers;

import android.app.Activity;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.ViewCompat;

import com.google.android.material.button.MaterialButton;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import de.markusfisch.android.shadereditor.R;
import de.markusfisch.android.shadereditor.audio.AudioShaderPlayer;
import de.markusfisch.android.shadereditor.app.ShaderEditorApp;
import de.markusfisch.android.shadereditor.opengl.ShaderError;
import de.markusfisch.android.shadereditor.opengl.ShaderRenderer;
import de.markusfisch.android.shadereditor.widget.VerticalSeekBar;

public final class AudioShaderPlayerManager {
	private static final float MIN_BPM = 40f;
	private static final float MAX_BPM = 240f;
	private static final int FADER_PROGRESS_MAX = 1000;

	@FunctionalInterface
	public interface Listener {
		void onInfoLog(@NonNull List<ShaderError> infoLog);
	}

	@NonNull
	private final AudioShaderPlayer player;
	@NonNull
	private final View play;
	@NonNull
	private final View pause;
	@NonNull
	private final MaterialButton toggleFaders;
	@NonNull
	private final View bpmContainer;
	@NonNull
	private final EditText bpmInput;
	@NonNull
	private final View fadersContainer;
	@NonNull
	private final LinearLayout fadersTrack;
	@Nullable
	private final Listener listener;
	private boolean applyingBpmText;
	private boolean applyingFaderUi;
	private boolean audioTabSelected;
	private boolean audioFadersVisible;
	private boolean keyboardVisible;
	@NonNull
	private final VerticalSeekBar[] faderBars =
			new VerticalSeekBar[AudioShaderPlayer.FADER_COUNT];
	@NonNull
	private final TextView[] faderValueViews =
			new TextView[AudioShaderPlayer.FADER_COUNT];
	@NonNull
	private final ShaderRenderer.PlaybackUniformProvider playbackUniformProvider =
			new ShaderRenderer.PlaybackUniformProvider() {
				@Override
				public float getBpm() {
					return player.getBpm();
				}

				@Override
				public void copyFaders(@NonNull float[] target) {
					player.copyFaderUniforms(target);
				}
			};

	@Nullable
	private String editedSource;
	@Nullable
	private String activeSource;

	public AudioShaderPlayerManager(@NonNull Activity activity,
			@Nullable Listener listener) {
		this.listener = listener;
		player = new AudioShaderPlayer(activity.getApplicationContext());
		play = activity.findViewById(R.id.run_code);
		pause = activity.findViewById(R.id.pause_audio);
		toggleFaders = activity.findViewById(R.id.toggle_audio_faders);
		bpmContainer = activity.findViewById(R.id.audio_bpm_container);
		bpmInput = activity.findViewById(R.id.audio_bpm_input);
		fadersContainer = activity.findViewById(R.id.audio_faders_container);
		fadersTrack = activity.findViewById(R.id.audio_faders_track);
		audioFadersVisible = ShaderEditorApp.preferences.areAudioFadersVisible();
		player.setOnInfoLogListener(infoLog -> {
			if (listener != null) {
				activity.runOnUiThread(() -> listener.onInfoLog(infoLog));
			}
		});

		ViewCompat.setTooltipText(pause, activity.getText(R.string.audio_pause));
		pause.setOnClickListener(v -> {
			player.pause();
			refreshUi();
		});
		toggleFaders.setOnClickListener(v -> {
			audioFadersVisible = !audioFadersVisible;
			ShaderEditorApp.preferences.setAudioFadersVisible(audioFadersVisible);
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

		initFaders(activity);
		refreshUi();
	}

	public void setAudioShader(@Nullable String source) {
		editedSource = normalize(source);
		activeSource = editedSource;
		player.clearQueuedSource();
		if (activeSource == null) {
			stopPlayerAndClearState();
			refreshUi();
			return;
		}
		player.syncFadersToValues();
		activateSource(activeSource, true);
		refreshUi();
	}

	public void setEditedAudioShader(@Nullable String source) {
		editedSource = normalize(source);
		if (editedSource == null) {
			player.clearQueuedSource();
		} else if (audioTabSelected) {
			ensureStarted();
		}
		if (editedSource == null && activeSource == null) {
			stopPlayerAndClearState();
		}
		refreshUi();
	}

	public boolean compileEditedShader() {
		if (editedSource == null) {
			activeSource = null;
			player.clearQueuedSource();
			stopPlayerAndClearState();
			refreshUi();
			return false;
		}
		player.syncFadersToValues();
		player.clearQueuedSource();
		boolean compileScheduled = activateSource(editedSource, true);
		refreshUi();
		return compileScheduled;
	}

	public void onPause() {
		player.clearQueuedSource();
		player.pause();
		player.stop();
		player.clearPlaybackClock();
		refreshUi();
	}

	public void onDestroy() {
		player.clearQueuedSource();
		player.stop();
		player.clearPlaybackClock();
	}

	public boolean playFromStart() {
		if (editedSource == null) {
			return false;
		}
		player.syncFadersToValues();
		player.clearQueuedSource();
		boolean compileScheduled = activateSource(editedSource, false);
		player.playFromStart();
		refreshUi();
		return compileScheduled;
	}

	public void resetTime() {
		player.syncFadersToValues();
		player.resetTime();
		refreshUi();
	}

	public boolean hasAudioShader() {
		return editedSource != null || activeSource != null;
	}

	public boolean hasActiveAudioShader() {
		return activeSource != null;
	}

	public void setAudioTabSelected(boolean audioTabSelected) {
		this.audioTabSelected = audioTabSelected;
		if (audioTabSelected && editedSource != null) {
			ensureStarted();
		}
		refreshUi();
	}

	public void setKeyboardVisible(boolean keyboardVisible) {
		if (this.keyboardVisible == keyboardVisible) {
			return;
		}
		this.keyboardVisible = keyboardVisible;
		refreshUi();
	}

	public void setPreviewSurface(int width, int height, float quality) {
		float safeQuality = Math.max(0f, quality);
		float scaledWidth = Math.round(Math.max(0, width) * safeQuality);
		float scaledHeight = Math.round(Math.max(0, height) * safeQuality);
		player.setResolution(scaledWidth, scaledHeight);
	}

	public void updateTouch(@NonNull MotionEvent event,
			int width,
			int height,
			float quality) {
		float safeQuality = Math.max(0f, quality);
		float scaledWidth = Math.round(Math.max(0, width) * safeQuality);
		float scaledHeight = Math.round(Math.max(0, height) * safeQuality);
		player.setResolution(scaledWidth, scaledHeight);
		player.setTouch(
				event.getX() * safeQuality,
				Math.max(1f, scaledHeight) - event.getY() * safeQuality);
	}

	public boolean shouldShowPlaybackUi() {
		return audioTabSelected || hasAudioShader();
	}

	@NonNull
	public ShaderRenderer.TimeSource getTimeSource() {
		return player::getPlaybackTimeSeconds;
	}

	@NonNull
	public ShaderRenderer.PlaybackUniformProvider getPlaybackUniformProvider() {
		return playbackUniformProvider;
	}

	public void refreshUi() {
		boolean showPlaybackUi = shouldShowPlaybackUi();
		play.setVisibility(shouldShowPlay(showPlaybackUi) ? View.VISIBLE : View.GONE);
		pause.setVisibility(showPlaybackUi ? View.VISIBLE : View.GONE);
		pause.setEnabled(hasActiveAudioShader() && player.isPlaying());
		bpmContainer.setVisibility(showPlaybackUi ? View.VISIBLE : View.GONE);
		toggleFaders.setVisibility(showPlaybackUi ? View.VISIBLE : View.GONE);
		fadersContainer.setVisibility(shouldShowFaders(showPlaybackUi)
				? View.VISIBLE
				: View.GONE);
		setFadersEnabled(shouldShowFaders(showPlaybackUi));
		updateFaderToggleButton();
		updateBpmText();
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
		bpmInput.setText(String.format(Locale.US, "%.0f", player.getBpm()));
		bpmInput.setSelection(bpmInput.getText().length());
		applyingBpmText = false;
	}

	private void initFaders(@NonNull Activity activity) {
		LayoutInflater inflater = LayoutInflater.from(activity);
		for (int i = 0; i < AudioShaderPlayer.FADER_COUNT; ++i) {
			View faderView = inflater.inflate(R.layout.audio_fader_view, fadersTrack, false);
			VerticalSeekBar seekBar = faderView.findViewById(R.id.audio_fader_seekbar);
			TextView valueView = faderView.findViewById(R.id.audio_fader_value);
			TextView labelView = faderView.findViewById(R.id.audio_fader_label);
			float value = clampFader(ShaderEditorApp.preferences.getAudioFaderValue(i));
			int index = i;

			labelView.setText("F" + i);
			faderBars[i] = seekBar;
			faderValueViews[i] = valueView;
			player.setFader(i, value);
			updateFaderUi(i, value);

			seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
				@Override
				public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
					if (applyingFaderUi) {
						return;
					}
					float faderValue = progress / (float) FADER_PROGRESS_MAX;
					player.setFader(index, faderValue);
					ShaderEditorApp.preferences.setAudioFaderValue(index, faderValue);
					updateFaderUi(index, faderValue);
				}

				@Override
				public void onStartTrackingTouch(SeekBar seekBar) {
				}

				@Override
				public void onStopTrackingTouch(SeekBar seekBar) {
				}
			});

			fadersTrack.addView(faderView);
		}
	}

	private void updateFaderUi(int index, float value) {
		if (index < 0 || index >= faderBars.length) {
			return;
		}
		VerticalSeekBar seekBar = faderBars[index];
		TextView valueView = faderValueViews[index];
		if (seekBar == null || valueView == null) {
			return;
		}
		applyingFaderUi = true;
		int progress = Math.round(clampFader(value) * FADER_PROGRESS_MAX);
		if (seekBar.getProgress() != progress) {
			seekBar.setProgress(progress);
		}
		valueView.setText(String.format(Locale.US, "%.2f", clampFader(value)));
		applyingFaderUi = false;
	}

	private void setFadersEnabled(boolean enabled) {
		for (VerticalSeekBar faderBar : faderBars) {
			if (faderBar != null) {
				faderBar.setEnabled(enabled);
			}
		}
	}

	private void updateFaderToggleButton() {
		toggleFaders.setIconResource(audioFadersVisible
				? R.drawable.ic_bottom_panel_close
				: R.drawable.ic_bottom_panel_open);
		ViewCompat.setTooltipText(toggleFaders,
				toggleFaders.getContext().getText(audioFadersVisible
						? R.string.audio_hide_faders
						: R.string.audio_show_faders));
	}

	private boolean shouldShowPlay(boolean showPlaybackUi) {
		return showPlaybackUi || !ShaderEditorApp.preferences.doesRunOnChange();
	}

	private boolean shouldShowFaders(boolean showPlaybackUi) {
		return showPlaybackUi && audioFadersVisible && !keyboardVisible;
	}

	private boolean activateSource(@NonNull String source, boolean forceCompile) {
		boolean justStarted = ensureStarted();
		boolean sourceChanged = !Objects.equals(activeSource, source);
		boolean compileScheduled = justStarted || forceCompile || sourceChanged;
		activeSource = source;
		if (compileScheduled) {
			player.setSource(source);
		}
		return compileScheduled;
	}

	private boolean ensureStarted() {
		if (player.isStarted()) {
			return false;
		}
		player.start();
		return true;
	}

	private void stopPlayerAndClearState() {
		player.clearQueuedSource();
		player.pause();
		player.stop();
		player.clearPlaybackClock();
		if (listener != null) {
			listener.onInfoLog(Collections.emptyList());
		}
	}

	private static float clampFader(float value) {
		return Math.max(0f, Math.min(1f, value));
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
