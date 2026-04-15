package de.markusfisch.android.shadereditor.fragment;

import android.graphics.Typeface;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.tabs.TabLayout;

import java.util.Collections;
import java.util.List;

import de.markusfisch.android.shadereditor.R;
import de.markusfisch.android.shadereditor.app.ShaderEditorApp;
import de.markusfisch.android.shadereditor.opengl.ShaderError;
import de.markusfisch.android.shadereditor.preference.Preferences;
import de.markusfisch.android.shadereditor.view.SoftKeyboard;
import de.markusfisch.android.shadereditor.view.UndoRedo;
import de.markusfisch.android.shadereditor.widget.ErrorListModal;
import de.markusfisch.android.shadereditor.widget.ShaderEditor;

public class EditorFragment extends Fragment {
	public static final String TAG = "EditorFragment";
	private static final String TAB_NAME = "tab";
	private static final String VISUAL_SHADER = "visual_shader";
	private static final String AUDIO_SHADER = "audio_shader";

	public enum Tab {
		VISUAL,
		AUDIO
	}

	@FunctionalInterface
	public interface OnTabChangedListener {
		void onTabChanged(@NonNull Tab tab);
	}

	@Nullable
	private View editorContainer;
	@Nullable
	private ShaderEditor shaderEditor;
	@Nullable
	private UndoRedo undoRedo;
	@Nullable
	private TabLayout editorTabs;
	@NonNull
	private String fragmentShader = "";
	@NonNull
	private String audioShader = "";
	@NonNull
	private Tab currentTab = Tab.VISUAL;
	private boolean suppressCallbacks = false;

	@Nullable
	private ShaderEditor.OnEditPausedListener editPausedListener;
	@Nullable
	private ShaderEditor.OnTextModifiedListener textModifiedListener;
	@Nullable
	private ShaderEditor.CodeCompletionListener codeCompletionListener;
	@Nullable
	private OnTabChangedListener tabChangedListener;

	@Override
	public void onCreate(@Nullable Bundle state) {
		super.onCreate(state);
		if (state == null) {
			return;
		}
		fragmentShader = state.getString(VISUAL_SHADER, "");
		audioShader = state.getString(AUDIO_SHADER, "");
		String tabName = state.getString(TAB_NAME, Tab.VISUAL.name());
		currentTab = Tab.valueOf(tabName);
	}

	@Override
	public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle state) {
		View view = inflater.inflate(R.layout.fragment_editor, container, false);

		editorTabs = view.findViewById(R.id.editor_tabs);
		editorContainer = view.findViewById(R.id.editor_container);
		shaderEditor = view.findViewById(R.id.editor);
		shaderEditor.setOnEditPausedListener(text -> {
			if (suppressCallbacks) {
				return;
			}
			storeCurrentText(text);
			if (editPausedListener != null) {
				editPausedListener.onEditPaused(text);
			}
		});
		shaderEditor.setOnTextModifiedListener(() -> {
			if (suppressCallbacks) {
				return;
			}
			storeCurrentText();
			if (textModifiedListener != null) {
				textModifiedListener.onTextModified();
			}
		});
		shaderEditor.setOnCompletionsListener((completions, position) -> {
			if (suppressCallbacks) {
				return;
			}
			if (codeCompletionListener != null) {
				codeCompletionListener.onCodeCompletions(completions, position);
			}
		});
		setShowLineNumbers(ShaderEditorApp.preferences.showLineNumbers());
		undoRedo = new UndoRedo(shaderEditor, getHistoryFor(currentTab));
		if (editorTabs != null) {
			editorTabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
				@Override
				public void onTabSelected(TabLayout.Tab tab) {
					Tab newTab = tab.getPosition() == Tab.AUDIO.ordinal()
							? Tab.AUDIO
							: Tab.VISUAL;
					switchTab(newTab);
				}

				@Override
				public void onTabUnselected(TabLayout.Tab tab) {
				}

				@Override
				public void onTabReselected(TabLayout.Tab tab) {
				}
			});
			selectCurrentTab();
		}
		showCurrentTabText(false);

		return view;
	}

	public void setOnTextModifiedListener(@Nullable ShaderEditor.OnTextModifiedListener listener) {
		textModifiedListener = listener;
	}

	public void setOnEditPausedListener(@Nullable ShaderEditor.OnEditPausedListener listener) {
		editPausedListener = listener;
	}

	public void setCodeCompletionListener(@Nullable ShaderEditor.CodeCompletionListener listener) {
		codeCompletionListener = listener;
	}

	public void setOnTabChangedListener(@Nullable OnTabChangedListener listener) {
		tabChangedListener = listener;
	}

	@Override
	public void onDestroyView() {
		super.onDestroyView();
		if (undoRedo != null) {
			undoRedo.detachListener();
		}
		editorTabs = null;
		editorContainer = null;
		shaderEditor = null;
		undoRedo = null;
	}

	@Override
	public void onResume() {
		super.onResume();
		updateToPreferences();
		// Only start listening after EditText restored its content
		// to make sure the initial change is not recorded.
		if (undoRedo != null) {
			undoRedo.listenForChanges();
		}
	}

	@Override
	public void onSaveInstanceState(@NonNull Bundle outState) {
		super.onSaveInstanceState(outState);
		storeCurrentText();
		outState.putString(VISUAL_SHADER, fragmentShader);
		outState.putString(AUDIO_SHADER, audioShader);
		outState.putString(TAB_NAME, currentTab.name());
	}

	public void undo() {
		if (undoRedo != null) {
			undoRedo.undo();
		}
	}

	public boolean canUndo() {
		return undoRedo != null && undoRedo.canUndo();
	}

	public void redo() {
		if (undoRedo != null) {
			undoRedo.redo();
		}
	}

	public boolean canRedo() {
		return undoRedo != null && undoRedo.canRedo();
	}

	public boolean hasErrors() {
		return shaderEditor != null && shaderEditor.hasErrors();
	}

	public void clearError() {
		if (shaderEditor != null) {
			shaderEditor.setErrors(Collections.emptyList());
		}
	}

	public void updateHighlighting() {
		if (shaderEditor != null) {
			shaderEditor.updateHighlighting();
		}
	}

	public void highlightErrors() {
		if (shaderEditor != null) {
			shaderEditor.updateErrorHighlighting();
		}
	}

	public void setErrors(@NonNull List<ShaderError> errors) {
		if (shaderEditor == null) {
			return;
		}
		shaderEditor.setErrors(errors);
		highlightErrors();
	}

	public void showErrors() {
		if (shaderEditor == null) {
			return;
		}
		List<ShaderError> errors = shaderEditor.getErrors();
		new ErrorListModal(errors, this::navigateToLine).show(getParentFragmentManager(),
				ErrorListModal.TAG);
	}

	public void navigateToLine(int lineNumber) {
		if (shaderEditor != null) {
			shaderEditor.navigateToLine(lineNumber);
		}
	}


	public String getText() {
		return getCurrentText();
	}

	@NonNull
	public String getCurrentText() {
		storeCurrentText();
		return currentTab == Tab.AUDIO ? audioShader : fragmentShader;
	}

	@NonNull
	public String getFragmentShaderText() {
		storeCurrentText();
		return fragmentShader;
	}

	@NonNull
	public String getAudioShaderText() {
		storeCurrentText();
		return audioShader;
	}

	public boolean isVisualTabSelected() {
		return currentTab == Tab.VISUAL;
	}

	public void setCurrentTab(@NonNull Tab tab) {
		if (editorTabs == null) {
			currentTab = tab;
			return;
		}
		if (currentTab == tab) {
			selectCurrentTab();
			return;
		}
		TabLayout.Tab selectedTab = editorTabs.getTabAt(tab.ordinal());
		if (selectedTab != null) {
			editorTabs.selectTab(selectedTab);
		} else {
			switchTab(tab);
		}
	}

	public void setShaderTexts(@Nullable String fragmentShader, @Nullable String audioShader) {
		this.fragmentShader = fragmentShader == null ? "" : fragmentShader;
		this.audioShader = audioShader == null ? "" : audioShader;
		ShaderEditorApp.editHistory.clear();
		ShaderEditorApp.audioEditHistory.clear();
		showCurrentTabText(true);
	}

	public void insert(@NonNull CharSequence text) {
		if (shaderEditor != null) {
			shaderEditor.insert(text);
		}
	}

	public void addUniform(String name) {
		if (name == null) {
			return;
		}
		if (currentTab != Tab.VISUAL) {
			setCurrentTab(Tab.VISUAL);
		}
		if (shaderEditor != null) {
			shaderEditor.addUniform(name);
			storeCurrentText();
		}
	}

	public boolean isCodeVisible() {
		return editorContainer == null || editorContainer.getVisibility() == View.VISIBLE;
	}

	public boolean toggleCode() {
		boolean visible = isCodeVisible();
		if (editorContainer != null) {
			editorContainer.setVisibility(visible ? View.GONE : View.VISIBLE);
		}
		if (visible && shaderEditor != null) {
			SoftKeyboard.hide(getActivity(), shaderEditor);
		}
		return visible;
	}

	private void updateToPreferences() {
		if (shaderEditor == null) {
			return;
		}
		Preferences preferences = ShaderEditorApp.preferences;
		shaderEditor.setUpdateDelay(preferences.getUpdateDelay());
		shaderEditor.setTextSize(TypedValue.COMPLEX_UNIT_SP, preferences.getTextSize());
		Typeface font = preferences.getFont();
		shaderEditor.setTypeface(font);
		String features = shaderEditor.getFontFeatureSettings();
		boolean isMono = font == Typeface.MONOSPACE;
		// Don't touch font features for the default MONOSPACE font as
		// this can impact performance.
		if (!isMono || features != null) {
			shaderEditor.setFontFeatureSettings(isMono ? null : preferences.useLigatures() ?
					"normal" : "calt off");
		}
	}

	public void setShowLineNumbers(boolean showLineNumbers) {
		if (shaderEditor != null) {
			shaderEditor.setShowLineNumbers(showLineNumbers);
		}
	}

	private void switchTab(@NonNull Tab tab) {
		if (tab == currentTab) {
			return;
		}
		storeCurrentText();
		currentTab = tab;
		if (undoRedo != null) {
			undoRedo.stopListeningForChanges();
			undoRedo.setEditHistory(getHistoryFor(tab));
		}
		showCurrentTabText(false);
		if (tabChangedListener != null) {
			tabChangedListener.onTabChanged(tab);
		}
	}

	private void selectCurrentTab() {
		if (editorTabs == null) {
			return;
		}
		TabLayout.Tab tab = editorTabs.getTabAt(currentTab.ordinal());
		if (tab != null && !tab.isSelected()) {
			editorTabs.selectTab(tab);
		}
	}

	private void showCurrentTabText(boolean clearHistory) {
		if (shaderEditor == null || undoRedo == null) {
			return;
		}
		clearError();
		undoRedo.stopListeningForChanges();
		if (clearHistory) {
			undoRedo.clearHistory();
		}
		suppressCallbacks = true;
		shaderEditor.setTextHighlighted(currentTab == Tab.AUDIO ? audioShader : fragmentShader);
		suppressCallbacks = false;
		undoRedo.listenForChanges();
	}

	private void storeCurrentText() {
		if (shaderEditor == null) {
			return;
		}
		storeCurrentText(shaderEditor.getCleanText());
	}

	private void storeCurrentText(@NonNull String text) {
		if (currentTab == Tab.AUDIO) {
			audioShader = text;
		} else {
			fragmentShader = text;
		}
	}

	@NonNull
	private UndoRedo.EditHistory getHistoryFor(@NonNull Tab tab) {
		return tab == Tab.AUDIO
				? ShaderEditorApp.audioEditHistory
				: ShaderEditorApp.editHistory;
	}
}
