package de.markusfisch.android.shadereditor.activity;

import android.app.WallpaperManager;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.coordinatorlayout.widget.CoordinatorLayout;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;

import org.jetbrains.annotations.Contract;

import java.util.List;

import de.markusfisch.android.shadereditor.R;
import de.markusfisch.android.shadereditor.activity.managers.ExtraKeysManager;
import de.markusfisch.android.shadereditor.activity.managers.MainMenuManager;
import de.markusfisch.android.shadereditor.activity.managers.NavigationManager;
import de.markusfisch.android.shadereditor.activity.managers.AudioShaderPlayerManager;
import de.markusfisch.android.shadereditor.activity.managers.ShaderListManager;
import de.markusfisch.android.shadereditor.activity.managers.ShaderManager;
import de.markusfisch.android.shadereditor.activity.managers.ShaderViewManager;
import de.markusfisch.android.shadereditor.activity.managers.UIManager;
import de.markusfisch.android.shadereditor.app.ShaderEditorApp;
import de.markusfisch.android.shadereditor.database.DataRecords;
import de.markusfisch.android.shadereditor.database.DataSource;
import de.markusfisch.android.shadereditor.database.Database;
import de.markusfisch.android.shadereditor.fragment.EditorFragment;
import de.markusfisch.android.shadereditor.opengl.ShaderError;
import de.markusfisch.android.shadereditor.service.ShaderWallpaperService;
import de.markusfisch.android.shadereditor.view.SystemBarMetrics;
import de.markusfisch.android.shadereditor.widget.ShaderView;

public class MainActivity extends AppCompatActivity {
	private static final String CODE_VISIBLE = "code_visible";
	private static final String FULLSCREEN_MODE = "fullscreen_mode";
	private static final String CRASH_RECOVERY_COMMENT = """
			// Shader Nerd disabled this shader because it caused a crash while loading.
			// Fix the shader and remove the #if 0 wrapper to re-enable it.
			""";

	private EditorFragment editorFragment;
	private UIManager uiManager;
	private ShaderManager shaderManager;
	private ShaderListManager shaderListManager;
	private ShaderViewManager shaderViewManager;
	private AudioShaderPlayerManager audioShaderPlayerManager;
	private NavigationManager navigationManager;
	private DataSource dataSource;
	private ShaderView previewView;
	private View compileShaderButton;
	private ProgressBar compileProgress;
	private boolean isInitialLoad = false;
	private boolean visualCompileInFlight = false;
	private boolean audioCompileInFlight = false;

	@Override
	protected void onCreate(@Nullable Bundle state) {
		super.onCreate(state);
		setContentView(R.layout.activity_main);
		SystemBarMetrics.initMainLayout(this, null);

		dataSource = Database.getInstance(this).getDataSource();
		recoverFromCrashIfNeeded();

		editorFragment = state == null ? new EditorFragment() : (EditorFragment)
				getSupportFragmentManager().findFragmentByTag(EditorFragment.TAG);

		if (state == null) {
			getSupportFragmentManager().beginTransaction()
					.replace(R.id.content_frame, editorFragment, EditorFragment.TAG)
					.commit();
		}

		navigationManager = new NavigationManager(this);
		previewView = findViewById(R.id.preview);
		shaderViewManager = new ShaderViewManager(this,
				previewView,
				findViewById(R.id.quality),
				createShaderViewListener());
		audioShaderPlayerManager = new AudioShaderPlayerManager(this,
				infoLog -> handleShaderInfoLog(EditorFragment.Tab.AUDIO, infoLog));
		audioShaderPlayerManager.setAudioTabSelected(!editorFragment.isVisualTabSelected());
		shaderViewManager.setTimeSource(audioShaderPlayerManager.getTimeSource());
		shaderViewManager.setPlaybackUniformProvider(
				audioShaderPlayerManager.getPlaybackUniformProvider());
		View mainCoordinator = findViewById(R.id.main_coordinator);
		ViewCompat.setOnApplyWindowInsetsListener(mainCoordinator, (view, insets) -> {
			audioShaderPlayerManager.setKeyboardVisible(
					insets.isVisible(WindowInsetsCompat.Type.ime()));
			return insets;
		});
		ViewCompat.requestApplyInsets(mainCoordinator);
		ExtraKeysManager extraKeysManager = new ExtraKeysManager(this,
				findViewById(android.R.id.content),
				editorFragment::insert);
		uiManager = new UIManager(this,
				editorFragment,
				extraKeysManager,
				shaderViewManager);
		shaderListManager = new ShaderListManager(this,
				findViewById(R.id.shaders),
				dataSource,
				createShaderListListener());
		shaderManager = new ShaderManager(this,
				editorFragment,
				shaderViewManager,
				audioShaderPlayerManager,
				shaderListManager,
				uiManager,
				dataSource,
				createShaderViewListener());
		if (previewView != null) {
			previewView.setOnTouchListener((v, event) -> {
				if (event != null) {
					audioShaderPlayerManager.updateTouch(event,
							v.getWidth(),
							v.getHeight(),
							shaderManager.getQuality());
				}
				return false;
			});
			previewView.addOnLayoutChangeListener((v,
					left, top, right, bottom,
					oldLeft, oldTop, oldRight, oldBottom) -> syncAudioPreviewMetrics());
			previewView.post(this::syncAudioPreviewMetrics);
		}

		MainMenuManager mainMenuManager = new MainMenuManager(
				this,
				createEditorActions(),
				createShaderActions(extraKeysManager),
				createNavigationActions());

		uiManager.setupToolbar(mainMenuManager::show,
				v -> this.runShader(),
				v -> uiManager.toggleCodeVisibility(),
				v -> editorFragment.showErrors());
		View compileShader = findViewById(R.id.compile_shader);
		View resetTime = findViewById(R.id.reset_time);
		compileShaderButton = compileShader;
		compileProgress = findViewById(R.id.compile_progress);
		ViewCompat.setTooltipText(compileShader, getText(R.string.compile_shader));
		ViewCompat.setTooltipText(resetTime, getText(R.string.reset_time));
		compileShader.setOnClickListener(v -> compileCurrentShader());
		resetTime.setOnClickListener(v -> resetShaderTime());
		updateCompileUi();

		// Handle back button to exit fullscreen mode
		getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
			@Override
			public void handleOnBackPressed() {
				if (uiManager.isFullscreen()) {
					uiManager.setFullscreen(false);
				} else {
					setEnabled(false);
					getOnBackPressedDispatcher().onBackPressed();
				}
			}
		});

		shaderManager.handleSendText(getIntent());

		if (state == null) {
			Intent intent = getIntent();
			String action = intent != null ? intent.getAction() : null;
			if (!Intent.ACTION_SEND.equals(action) &&
					!Intent.ACTION_VIEW.equals(action)) {
				isInitialLoad = true;
			}
		}

		editorFragment.setOnEditPausedListener(text -> {
			if (editorFragment.isVisualTabSelected() &&
					ShaderEditorApp.preferences.doesRunOnChange()) {
				if (editorFragment.hasErrors()) {
					editorFragment.clearError();
					editorFragment.highlightErrors();
					updateErrorIndicator();
				}
				setCompiling(EditorFragment.Tab.VISUAL, true);
				shaderViewManager.setFragmentShader(editorFragment.getFragmentShaderText());
			} else {
				audioShaderPlayerManager.setEditedAudioShader(
						editorFragment.getAudioShaderText());
				updatePlaybackUiMode();
			}
		});
		editorFragment.setOnTextModifiedListener(() -> {
			shaderManager.setModified(true);
			if (!editorFragment.isVisualTabSelected()) {
				updatePlaybackUiMode();
			}
		});
		editorFragment.setCodeCompletionListener(extraKeysManager::setCompletions);
		editorFragment.setOnTabChangedListener(tab -> {
			audioShaderPlayerManager.setAudioTabSelected(tab == EditorFragment.Tab.AUDIO);
			updateErrorIndicator();
			updatePlaybackUiMode();
		});
		updateErrorIndicator();
	}

	@Override
	protected void onRestoreInstanceState(@NonNull Bundle state) {
		super.onRestoreInstanceState(state);
		shaderManager.restoreState(state);
		if (!state.getBoolean(CODE_VISIBLE, true)) {
			uiManager.toggleCodeVisibility();
		}
		if (state.getBoolean(FULLSCREEN_MODE, false)) {
			uiManager.setFullscreen(true);
		}
	}

	@Override
	protected void onSaveInstanceState(@NonNull Bundle state) {
		shaderManager.saveState(state);
		state.putBoolean(CODE_VISIBLE, editorFragment.isCodeVisible());
		state.putBoolean(FULLSCREEN_MODE, uiManager.isFullscreen());
		super.onSaveInstanceState(state);
	}

	@Override
	protected void onResume() {
		super.onResume();
		updateErrorIndicator();
		updatePlaybackUiMode();
		updateCompileUi();
		syncAudioPreviewMetrics();
		audioShaderPlayerManager.refreshUi();
		shaderListManager.loadShadersAsync();
		shaderViewManager.onResume();
	}

	@Override
	protected void onPause() {
		ShaderEditorApp.preferences.setPendingCrashShaderId(0);
		if (ShaderEditorApp.preferences.autoSave()) {
			shaderManager.saveShader();
		}
		audioShaderPlayerManager.onPause();
		super.onPause();
		shaderViewManager.onPause();
	}

	@Override
	protected void onDestroy() {
		if (shaderListManager != null) {
			shaderListManager.destroy();
		}
		if (audioShaderPlayerManager != null) {
			audioShaderPlayerManager.onDestroy();
		}
		super.onDestroy();
	}

	@Override
	protected void onNewIntent(Intent intent) {
		super.onNewIntent(intent);
		shaderManager.handleSendText(intent);
	}

	@Override
	protected void onPostCreate(Bundle state) {
		super.onPostCreate(state);
		if (uiManager.drawerToggle != null) {
			uiManager.drawerToggle.syncState();
		}
	}

	@NonNull
	@Contract(" -> new")
	private ShaderListManager.Listener createShaderListListener() {
		return new ShaderListManager.Listener() {
			@Override
			public void onShaderSelected(long id) {
				isInitialLoad = false;
				if (ShaderEditorApp.preferences.autoSave()) {
					shaderManager.saveShader();
				}
				uiManager.closeDrawers();
				shaderManager.selectShader(id);
			}

			@Override
			public void onShaderRenamed(long id, @NonNull String name) {
				if (id == shaderManager.getSelectedShaderId()) {
					uiManager.setToolbarTitle(name);
				}
				shaderListManager.loadShadersAsync();
			}

			@Override
			public void onAllShadersDeleted() {
				isInitialLoad = false;
				shaderManager.selectShader(0);
			}

			@Override
			public void onShadersLoaded(@NonNull List<DataRecords.ShaderInfo> shaders) {
				if (isInitialLoad && !shaders.isEmpty()) {
					// Try to load the last opened shader.
					// NOTE: This assumes a method like `getLastOpenedShader()` exists
					// in your SharedPreferences helper class.
					long lastOpenedId = ShaderEditorApp.preferences.getLastOpenedShader();
					long idToLoad = 0;

					// Check if the last opened shader still exists in the list.
					if (lastOpenedId > 0) {
						for (DataRecords.ShaderInfo shader : shaders) {
							if (shader.id() == lastOpenedId) {
								idToLoad = lastOpenedId;
								break;
							}
						}
					}

					// If no last-opened shader was found (or it was deleted),
					// fall back to the first shader in the list.
					if (idToLoad == 0) {
						idToLoad = shaders.get(0).id();
					}

					shaderManager.selectShader(idToLoad);
				}
				isInitialLoad = false;
			}
		};
	}

	@NonNull
	@Contract(" -> new")
	private ShaderViewManager.Listener createShaderViewListener() {
		return new ShaderViewManager.Listener() {
			@Override
			public void onFramesPerSecond(int fps) {
				if (fps > 0) {
					ShaderEditorApp.preferences.setPendingCrashShaderId(0);
					uiManager.setToolbarSubtitle(fps + " fps");
				}
			}

			@Override
			public void onInfoLog(@NonNull List<ShaderError> infoLog) {
				runOnUiThread(() -> handleShaderInfoLog(EditorFragment.Tab.VISUAL, infoLog));
			}

			@Override
			public void onQualityChanged(float quality) {
				shaderManager.setQuality(quality);
				syncAudioPreviewMetrics();
			}
		};
	}

	@NonNull
	@Contract(" -> new")
	private MainMenuManager.EditorActions createEditorActions() {
		return new MainMenuManager.EditorActions() {
			@Override
			public void onUndo() {
				editorFragment.undo();
			}

			@Override
			public void onRedo() {
				editorFragment.redo();
			}

			@Override
			public boolean canUndo() {
				return editorFragment != null && editorFragment.canUndo();
			}

			@Override
			public boolean canRedo() {
				return editorFragment != null && editorFragment.canRedo();
			}
		};
	}

	@NonNull
	@Contract("_ -> new")
	private MainMenuManager.ShaderActions createShaderActions(ExtraKeysManager extraKeysManager) {
		return new MainMenuManager.ShaderActions() {
			@Override
			public void onAddShader() {
				long defaultId = ShaderEditorApp.preferences.getDefaultNewShader();
				if (defaultId > 0 && dataSource.shader.getShader(defaultId) != null) {
					duplicateShader(defaultId);
				} else {
					long newId = dataSource.shader.insertNewShader();
					shaderManager.selectShader(newId);
					shaderListManager.loadShadersAsync();
				}
			}

			@Override
			public void onSaveShader() {
				shaderManager.saveShader(true);
			}

			@Override
			public void onDuplicateShader() {
				if (editorFragment == null || shaderManager.getSelectedShaderId() < 1) {
					return;
				}
				if (shaderManager.isModified()) {
					shaderManager.saveShader();
				}
				duplicateShader(shaderManager.getSelectedShaderId());
			}

			@Override
			public void onDeleteShader() {
				if (shaderManager.getSelectedShaderId() < 1) {
					return;
				}
				new MaterialAlertDialogBuilder(MainActivity.this)
						.setMessage(R.string.sure_remove_shader)
						.setPositiveButton(android.R.string.ok, (dialog, which) -> {
							shaderManager.deleteShader(shaderManager.getSelectedShaderId());
							shaderManager.selectShader(dataSource.shader.getFirstShaderId());
							shaderListManager.loadShadersAsync();
						})
						.setNegativeButton(android.R.string.cancel, null)
						.show();
			}

			@Override
			public void onShareShader() {
				navigationManager.shareShader(editorFragment.getFragmentShaderText());
			}

			@Override
			public void onUpdateWallpaper() {
				updateWallpaper();
			}

			@Override
			public void onToggleExtraKeys() {
				extraKeysManager.setVisible(ShaderEditorApp.preferences.toggleShowExtraKeys());
			}

			@Override
			public long getSelectedShaderId() {
				return shaderManager.getSelectedShaderId();
			}
		};
	}

	@NonNull
	@Contract(" -> new")
	private MainMenuManager.NavigationActions createNavigationActions() {
		return new MainMenuManager.NavigationActions() {
			@Override
			public void onAddUniform() {
				navigationManager.goToAddUniform(shaderManager.addUniformLauncher);
			}

			@Override
			public void onLoadSample() {
				navigationManager.goToLoadSample(shaderManager.loadSampleLauncher);
			}

			@Override
			public void onBrowseAudioSamples() {
				navigationManager.goToAudioSamples(shaderManager.browseAudioSamplesLauncher);
			}

			@Override
			public void onShowSettings() {
				navigationManager.goToPreferences();
			}

			@Override
			public void onShowFaq() {
				navigationManager.goToFaq();
			}
		};
	}

	private void duplicateShader(long id) {
		// The ShaderManager is now responsible for getting the shader details.
		// We just need to pass the ID.
		long newId = shaderManager.duplicateShader(id);
		if (newId > 0) {
			shaderManager.selectShader(newId);
			shaderListManager.loadShadersAsync();
		}
	}

	private void updateWallpaper() {
		if (shaderManager.getSelectedShaderId() < 1) {
			return;
		}
		if (shaderManager.isModified()) {
			shaderManager.saveShader();
		}

		ShaderEditorApp.preferences.setWallpaperShader(0); // Force change
		ShaderEditorApp.preferences.setWallpaperShader(shaderManager.getSelectedShaderId());

		int messageId = R.string.wallpaper_set;
		if (!WallpaperManager.getInstance(this).isWallpaperSupported()) {
			messageId = R.string.cannot_set_wallpaper;
		} else if (!ShaderWallpaperService.isRunning()) {
			Intent intent = new Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
					.putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
							new ComponentName(this, ShaderWallpaperService.class));
			try {
				startActivity(intent);
				return;
			} catch (Exception e) {
				messageId = R.string.pick_live_wallpaper_manually;
			}
		}
		Toast.makeText(this, messageId, Toast.LENGTH_SHORT).show();
	}

	private void runShader() {
		String src = editorFragment.getFragmentShaderText();
		audioShaderPlayerManager.setEditedAudioShader(editorFragment.getAudioShaderText());
		updatePlaybackUiMode();
		editorFragment.clearError();
		updateErrorIndicator();
		if (ShaderEditorApp.preferences.doesSaveOnRun()) {
			PreviewActivity.renderStatus.reset();
			shaderManager.saveShader();
		}
		if (audioShaderPlayerManager.hasAudioShader()) {
			setCompiling(EditorFragment.Tab.AUDIO, true);
			if (!audioShaderPlayerManager.playFromStart()) {
				setCompiling(EditorFragment.Tab.AUDIO, false);
			}
		}
		if (ShaderEditorApp.preferences.doesRunInBackground() ||
				audioShaderPlayerManager.hasAudioShader()) {
			setCompiling(EditorFragment.Tab.VISUAL, true);
			shaderViewManager.setFragmentShader(src);
		} else {
			navigationManager.showPreview(src, shaderManager.getQuality(),
					shaderManager.previewShaderLauncher);
		}
	}

	private void updatePlaybackUiMode() {
		boolean hasAudioShader = audioShaderPlayerManager.hasAudioShader();
		uiManager.updateUiToPreferences(
				hasAudioShader,
				audioShaderPlayerManager.shouldShowPlaybackUi());
	}

	private void compileCurrentShader() {
		if (editorFragment.isVisualTabSelected()) {
			editorFragment.clearError();
			updateErrorIndicator();
			setCompiling(EditorFragment.Tab.VISUAL, true);
			shaderViewManager.setFragmentShader(editorFragment.getFragmentShaderText());
			return;
		}
		setCompiling(EditorFragment.Tab.AUDIO, true);
		if (!audioShaderPlayerManager.compileEditedShader()) {
			setCompiling(EditorFragment.Tab.AUDIO, false);
		}
	}

	private void resetShaderTime() {
		shaderViewManager.resetAnimationTime();
		audioShaderPlayerManager.resetTime();
	}

	private void syncAudioPreviewMetrics() {
		if (previewView == null || shaderManager == null || audioShaderPlayerManager == null) {
			return;
		}
		audioShaderPlayerManager.setPreviewSurface(
				previewView.getWidth(),
				previewView.getHeight(),
				shaderManager.getQuality());
	}

	private void handleShaderInfoLog(@NonNull EditorFragment.Tab tab,
			@NonNull List<ShaderError> infoLog) {
		setCompiling(tab, false);
		if (tab == EditorFragment.Tab.AUDIO) {
			editorFragment.setAudioErrors(infoLog);
		} else {
			editorFragment.setVisualErrors(infoLog);
		}
		updateErrorIndicator();
		if (!infoLog.isEmpty()) {
			showError(infoLog.get(0).toString(), tab);
		}
	}

	private void setCompiling(@NonNull EditorFragment.Tab tab, boolean compiling) {
		if (tab == EditorFragment.Tab.AUDIO) {
			audioCompileInFlight = compiling;
		} else {
			visualCompileInFlight = compiling;
		}
		updateCompileUi();
	}

	private void updateCompileUi() {
		boolean compiling = visualCompileInFlight || audioCompileInFlight;
		if (compileShaderButton != null) {
			compileShaderButton.setEnabled(!compiling);
			compileShaderButton.setAlpha(compiling ? .35f : 1f);
		}
		if (compileProgress != null) {
			compileProgress.setVisibility(compiling ? View.VISIBLE : View.GONE);
		}
	}

	private void updateErrorIndicator() {
		View showErrors = findViewById(R.id.show_errors);
		showErrors.setVisibility(editorFragment.hasErrors()
				? View.VISIBLE
				: View.GONE);
	}

	private void showError(@NonNull String error, @NonNull EditorFragment.Tab tab) {
		View mainCoordinator = findViewById(R.id.main_coordinator);
		Snackbar snackbar = Snackbar.make(mainCoordinator,
						error,
						Snackbar.LENGTH_LONG)
				.setAction(R.string.details, v -> {
					editorFragment.setCurrentTab(tab);
					editorFragment.showErrors();
				});
		moveSnackBarOverActionBar(snackbar.getView());
		snackbar.show();
	}

	private void moveSnackBarOverActionBar(@Nullable View snackbarView) {
		if (snackbarView == null) {
			return;
		}

		ViewGroup.LayoutParams layoutParams = snackbarView.getLayoutParams();
		if (!(layoutParams instanceof CoordinatorLayout.LayoutParams params)) {
			return;
		}

		params.gravity = Gravity.TOP;

		View mainCoordinator = findViewById(R.id.main_coordinator);
		View toolbar = findViewById(R.id.toolbar);
		int topInset = 0;
		WindowInsetsCompat rootInsets = ViewCompat.getRootWindowInsets(mainCoordinator);
		if (rootInsets != null) {
			topInset = rootInsets.getInsets(WindowInsetsCompat.Type.systemBars()).top;
		}
		if (topInset == 0 && toolbar != null) {
			topInset = toolbar.getPaddingTop();
		}
		params.topMargin = topInset;
		snackbarView.setLayoutParams(params);
	}

	private void recoverFromCrashIfNeeded() {
		var preferences = ShaderEditorApp.preferences;
		long shaderId = preferences.getPendingCrashShaderId();
		if (shaderId <= 0) {
			return;
		}
		var shader = dataSource.shader.getShader(shaderId);
		if (shader == null) {
			preferences.setPendingCrashShaderId(0);
			return;
		}
		String recoveredSource = buildCrashRecoverySource(shader.fragmentShader());
		dataSource.shader.updateShader(shaderId, recoveredSource, null, shader.quality());
		preferences.setPendingCrashShaderId(0);
		Toast.makeText(this, R.string.shader_disabled_after_crash,
				Toast.LENGTH_LONG).show();
	}

	@NonNull
	private static String buildCrashRecoverySource(@Nullable String source) {
		if (source != null && source.startsWith(CRASH_RECOVERY_COMMENT)) {
			return source;
		}
		StringBuilder builder = new StringBuilder();
		builder.append(CRASH_RECOVERY_COMMENT);
		builder.append("#if 0\n");
		if (source != null && !source.isEmpty()) {
			builder.append(source);
			if (!source.endsWith("\n")) {
				builder.append('\n');
			}
		}
		builder.append("#endif\n");
		return builder.toString();
	}
}
