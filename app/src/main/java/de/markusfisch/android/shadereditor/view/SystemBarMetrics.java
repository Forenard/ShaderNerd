package de.markusfisch.android.shadereditor.view;

import android.content.Context;
import android.graphics.Rect;
import android.os.Build;
import android.util.TypedValue;
import android.view.View;
import android.view.Window;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import de.markusfisch.android.shadereditor.R;
import de.markusfisch.android.shadereditor.app.ShaderEditorApp;

public class SystemBarMetrics {
	public static int getToolBarHeight(@NonNull Context context) {
		TypedValue tv = new TypedValue();
		return context.getTheme().resolveAttribute(
				android.R.attr.actionBarSize,
				tv,
				true) ? TypedValue.complexToDimensionPixelSize(
				tv.data,
				context.getResources().getDisplayMetrics()) : 0;
	}

	public static void initMainLayout(
			@NonNull AppCompatActivity activity,
			@Nullable Rect insets) {
		Window window = activity.getWindow();

		// Enable edge-to-edge display
		WindowCompat.setDecorFitsSystemWindows(window, false);

		// Hide navigation bar by default (swipe to reveal)
		WindowInsetsControllerCompat controller =
				WindowCompat.getInsetsController(window, window.getDecorView());
		controller.hide(WindowInsetsCompat.Type.navigationBars());
		controller.setSystemBarsBehavior(
				WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);

		View navbar = activity.findViewById(R.id.navbar);
		// System bars no longer have a background from SDK35+.
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
			int color = ShaderEditorApp.preferences.getSystemBarColor();
			window.setStatusBarColor(color);
			window.setNavigationBarColor(color);
		}
		// navbar view is no longer needed since navigation bar is hidden
		if (navbar != null) {
			navbar.setVisibility(View.GONE);
		}

		View mainLayout = activity.findViewById(R.id.main_layout);
		if (mainLayout != null) {
			setWindowInsets(mainLayout,
					activity.findViewById(R.id.toolbar),
					navbar,
					insets);
		}
	}

	private static void setWindowInsets(
			final View mainLayout,
			@Nullable final View toolbar,
			@Nullable final View navbar,
			@Nullable final Rect windowInsets) {
		ViewCompat.setOnApplyWindowInsetsListener(mainLayout, (v, insets) -> {
			Insets systemBarsInsets = insets.getInsets(
					WindowInsetsCompat.Type.systemBars() |
							WindowInsetsCompat.Type.ime());
			int left = systemBarsInsets.left;
			int top = systemBarsInsets.top;
			int right = systemBarsInsets.right;
			int bottom = systemBarsInsets.bottom;

			// Only apply top padding for status bar (navigation bar is hidden)
			mainLayout.setPadding(left, 0, right, bottom);
			if (windowInsets != null) {
				windowInsets.set(left, top, right, bottom);
			}

			if (toolbar != null &&
					toolbar.getPaddingTop() == 0 &&
					top > 0) {
				toolbar.setPadding(0, top, 0, 0);
				toolbar.getLayoutParams().height += top;
			}

			return insets;
		});
	}
}