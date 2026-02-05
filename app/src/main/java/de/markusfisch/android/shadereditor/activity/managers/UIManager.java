package de.markusfisch.android.shadereditor.activity.managers;

import android.view.View;
import android.view.Window;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBarDrawerToggle;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import de.markusfisch.android.shadereditor.R;
import de.markusfisch.android.shadereditor.app.ShaderEditorApp;
import de.markusfisch.android.shadereditor.fragment.EditorFragment;
import de.markusfisch.android.shadereditor.widget.TouchThruDrawerLayout;

public class UIManager {
	private final AppCompatActivity activity;
	private final EditorFragment editorFragment;
	private final ExtraKeysManager extraKeysManager;
	private final ShaderViewManager shaderViewManager;
	private final Toolbar toolbar;
	private final TouchThruDrawerLayout drawerLayout;
	private final View mainLayout;
	private final View mainCoordinator;
	private final View navbar;
	private boolean isFullscreen = false;
	private int savedMainLayoutPaddingBottom = 0;

	public final ActionBarDrawerToggle drawerToggle;

	public UIManager(@NonNull AppCompatActivity activity,
			@NonNull EditorFragment editorFragment,
			@NonNull ExtraKeysManager extraKeysManager,
			@NonNull ShaderViewManager shaderViewManager) {
		this.activity = activity;
		this.editorFragment = editorFragment;
		this.extraKeysManager = extraKeysManager;
		this.shaderViewManager = shaderViewManager;

		toolbar = activity.findViewById(R.id.toolbar);
		activity.setSupportActionBar(toolbar);

		mainLayout = activity.findViewById(R.id.main_layout);
		mainCoordinator = activity.findViewById(R.id.main_coordinator);
		navbar = activity.findViewById(R.id.navbar);
		drawerLayout = activity.findViewById(R.id.drawer_layout);
		drawerToggle = new ActionBarDrawerToggle(
				activity, drawerLayout, toolbar,
				R.string.drawer_open, R.string.drawer_close);
		drawerToggle.setDrawerIndicatorEnabled(true);
		drawerLayout.addDrawerListener(drawerToggle);
	}

	public void setupToolbar(View.OnClickListener menuClickListener,
			View.OnClickListener runClickListener,
			View.OnClickListener toggleCodeClickListener,
			View.OnClickListener showErrorClickListener) {
		View menuBtn = toolbar.findViewById(R.id.menu_btn);
		ViewCompat.setTooltipText(menuBtn, activity.getText(R.string.menu_btn));
		menuBtn.setOnClickListener(menuClickListener);

		View runCode = toolbar.findViewById(R.id.run_code);
		ViewCompat.setTooltipText(runCode, activity.getText(R.string.run_code));
		runCode.setOnClickListener(runClickListener);

		View toggleCode = toolbar.findViewById(R.id.toggle_code);
		ViewCompat.setTooltipText(toggleCode, activity.getText(R.string.toggle_code));
		toggleCode.setOnClickListener(toggleCodeClickListener);

		View fullscreen = toolbar.findViewById(R.id.fullscreen);
		ViewCompat.setTooltipText(fullscreen, activity.getText(R.string.fullscreen));
		fullscreen.setOnClickListener(v -> toggleFullscreen());

		View showErrors = toolbar.findViewById(R.id.show_errors);
		ViewCompat.setTooltipText(showErrors, activity.getText(R.string.show_errors));
		showErrors.setOnClickListener(showErrorClickListener);
	}

	public void updateUiToPreferences() {
		boolean runInBackground =
				ShaderEditorApp.preferences.doesRunInBackground();
		shaderViewManager.setVisibility(runInBackground);
		toolbar.findViewById(R.id.toggle_code).setVisibility(
				runInBackground ? View.VISIBLE : View.GONE);
		toolbar.findViewById(R.id.fullscreen).setVisibility(
				runInBackground ? View.VISIBLE : View.GONE);
		if (!runInBackground && !editorFragment.isCodeVisible()) {
			toggleCodeVisibility();
		}
		if (!runInBackground && isFullscreen) {
			setFullscreen(false);
		}
		toolbar.findViewById(R.id.run_code).setVisibility(
				!ShaderEditorApp.preferences.doesRunOnChange()
						? View.VISIBLE
						: View.GONE);
		extraKeysManager.updateVisibility();
		editorFragment.setShowLineNumbers(
				ShaderEditorApp.preferences.showLineNumbers());
		editorFragment.updateHighlighting();
		activity.invalidateOptionsMenu();
	}

	public void toggleCodeVisibility() {
		boolean isVisible = editorFragment.toggleCode();
		drawerLayout.setTouchThru(isVisible);
		extraKeysManager.setVisible(!isVisible &&
				ShaderEditorApp.preferences.showExtraKeys());
	}

	public void setToolbarTitle(String name) {
		toolbar.setTitle(name);
		toolbar.setSubtitle(null);
	}

	public void closeDrawers() {
		drawerLayout.closeDrawers();
	}

	public void setToolbarSubtitle(String subtitle) {
		toolbar.post(() -> toolbar.setSubtitle(subtitle));
	}

	public boolean isFullscreen() {
		return isFullscreen;
	}

	public void setFullscreen(boolean fullscreen) {
		isFullscreen = fullscreen;
		if (fullscreen) {
			enterFullscreen();
		} else {
			exitFullscreen();
		}
	}

	public void toggleFullscreen() {
		setFullscreen(!isFullscreen);
	}

	private void enterFullscreen() {
		// Save current padding
		savedMainLayoutPaddingBottom = mainLayout.getPaddingBottom();

		// Hide UI elements
		mainLayout.setVisibility(View.GONE);
		if (navbar != null) {
			navbar.setVisibility(View.GONE);
		}
		extraKeysManager.setVisible(false);

		// Hide system bars using WindowInsetsController
		Window window = activity.getWindow();
		WindowInsetsControllerCompat controller =
				WindowCompat.getInsetsController(window, window.getDecorView());
		controller.hide(WindowInsetsCompat.Type.systemBars());
		controller.setSystemBarsBehavior(
				WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
	}

	private void exitFullscreen() {
		// Show UI elements
		mainLayout.setVisibility(View.VISIBLE);
		if (navbar != null && android.os.Build.VERSION.SDK_INT >=
				android.os.Build.VERSION_CODES.VANILLA_ICE_CREAM) {
			navbar.setVisibility(View.VISIBLE);
		}
		extraKeysManager.setVisible(ShaderEditorApp.preferences.showExtraKeys());

		// Restore padding
		mainLayout.setPadding(
				mainLayout.getPaddingLeft(),
				mainLayout.getPaddingTop(),
				mainLayout.getPaddingRight(),
				savedMainLayoutPaddingBottom);

		// Show system bars
		Window window = activity.getWindow();
		WindowInsetsControllerCompat controller =
				WindowCompat.getInsetsController(window, window.getDecorView());
		controller.show(WindowInsetsCompat.Type.systemBars());
	}
}
