package de.markusfisch.android.shadereditor.fragment;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputFilter;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.fragment.app.Fragment;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

import de.markusfisch.android.shadereditor.R;
import de.markusfisch.android.shadereditor.activity.AddUniformActivity;
import de.markusfisch.android.shadereditor.opengl.ShaderRenderer;
import de.markusfisch.android.shadereditor.opengl.TextureParameters;
import de.markusfisch.android.shadereditor.view.SoftKeyboard;
import de.markusfisch.android.shadereditor.widget.TextureParametersView;

public abstract class AbstractSamplerPropertiesFragment extends Fragment {
	public static final String TEXTURE_NAME_PATTERN = "[a-zA-Z0-9_]+";
	public static final String SAMPLER_2D = "sampler2D";
	public static final String SAMPLER_CUBE = "samplerCube";
	public static final int MAX_TEXTURE_SIZE = 4096;

	private static final Pattern NAME_PATTERN = Pattern.compile(
			"^" + TEXTURE_NAME_PATTERN + "$");

	private static boolean inProgress = false;

	private TextView sizeCaption;
	// Width/Height mode views
	private View sizeWhContainer;
	private EditText widthView;
	private EditText heightView;
	private CheckBox keepAspectRatioView;
	// SeekBar mode views
	private View sizeSeekBarContainer;
	private SeekBar sizeBarView;
	private TextView sizeView;
	// Common views
	private EditText nameView;
	private CheckBox addUniformView;
	private TextureParametersView textureParameterView;
	private View progressView;
	private String samplerType = SAMPLER_2D;

	// Size state
	private boolean useSeekBarMode = false;
	private int defaultWidth = 256;
	private int defaultHeight = 256;
	private float aspectRatio = 1f;
	private boolean updatingSize = false;

	protected void setSizeCaption(String caption) {
		sizeCaption.setText(caption);
	}

	protected void setMaxValue(int max) {
		if (sizeBarView != null) {
			sizeBarView.setMax(max);
		}
	}

	protected void setSamplerType(String name) {
		samplerType = name;
	}

	/**
	 * Switch to SeekBar mode (for cubemaps that need power-of-2 sizes)
	 */
	protected void useSeekBarMode() {
		useSeekBarMode = true;
		if (sizeWhContainer != null) {
			sizeWhContainer.setVisibility(View.GONE);
		}
		if (sizeSeekBarContainer != null) {
			sizeSeekBarContainer.setVisibility(View.VISIBLE);
		}
	}

	/**
	 * Set default dimensions (for 2D textures, from original image)
	 */
	protected void setDefaultDimensions(int width, int height) {
		defaultWidth = Math.min(width, MAX_TEXTURE_SIZE);
		defaultHeight = Math.min(height, MAX_TEXTURE_SIZE);
		aspectRatio = (float) defaultWidth / defaultHeight;
		if (widthView != null && heightView != null) {
			widthView.setText(String.valueOf(defaultWidth));
			heightView.setText(String.valueOf(defaultHeight));
		}
	}

	protected abstract int saveSampler(
			Context context,
			String name,
			int width,
			int height);

	protected View initView(
			Activity activity,
			LayoutInflater inflater,
			ViewGroup container) {
		View view = inflater.inflate(
				R.layout.fragment_sampler_properties,
				container,
				false);

		sizeCaption = view.findViewById(R.id.size_caption);
		// Width/Height mode
		sizeWhContainer = view.findViewById(R.id.size_wh_container);
		widthView = view.findViewById(R.id.width);
		heightView = view.findViewById(R.id.height);
		keepAspectRatioView = view.findViewById(R.id.keep_aspect_ratio);
		// SeekBar mode
		sizeSeekBarContainer = view.findViewById(R.id.size_seekbar_container);
		sizeBarView = view.findViewById(R.id.size_bar);
		sizeView = view.findViewById(R.id.size);
		// Common
		nameView = view.findViewById(R.id.name);
		addUniformView = view.findViewById(R.id.should_add_uniform);
		textureParameterView = view.findViewById(R.id.texture_parameters);
		progressView = view.findViewById(R.id.progress_view);

		view.findViewById(R.id.save).setOnClickListener(v -> saveSamplerAsync());

		if (activity.getCallingActivity() == null) {
			addUniformView.setVisibility(View.GONE);
			addUniformView.setChecked(false);
			textureParameterView.setVisibility(View.GONE);
		}

		initSizeViews();
		initNameView();

		return view;
	}

	private void initSizeViews() {
		// Initialize SeekBar mode
		if (sizeBarView != null) {
			setSizeView(sizeBarView.getProgress());
			sizeBarView.setOnSeekBarChangeListener(
					new SeekBar.OnSeekBarChangeListener() {
						@Override
						public void onProgressChanged(
								SeekBar seekBar,
								int progressValue,
								boolean fromUser) {
							setSizeView(progressValue);
						}

						@Override
						public void onStartTrackingTouch(SeekBar seekBar) {
						}

						@Override
						public void onStopTrackingTouch(SeekBar seekBar) {
						}
					});
		}

		// Initialize Width/Height mode
		if (widthView != null && heightView != null) {
			widthView.setText(String.valueOf(defaultWidth));
			heightView.setText(String.valueOf(defaultHeight));

			widthView.addTextChangedListener(new TextWatcher() {
				@Override
				public void beforeTextChanged(CharSequence s, int start, int count, int after) {
				}

				@Override
				public void onTextChanged(CharSequence s, int start, int before, int count) {
				}

				@Override
				public void afterTextChanged(Editable s) {
					if (updatingSize || !keepAspectRatioView.isChecked()) {
						return;
					}
					try {
						int width = Integer.parseInt(s.toString());
						int newHeight = Math.round(width / aspectRatio);
						newHeight = Math.max(1, Math.min(newHeight, MAX_TEXTURE_SIZE));
						updatingSize = true;
						heightView.setText(String.valueOf(newHeight));
						updatingSize = false;
					} catch (NumberFormatException ignored) {
					}
				}
			});

			heightView.addTextChangedListener(new TextWatcher() {
				@Override
				public void beforeTextChanged(CharSequence s, int start, int count, int after) {
				}

				@Override
				public void onTextChanged(CharSequence s, int start, int before, int count) {
				}

				@Override
				public void afterTextChanged(Editable s) {
					if (updatingSize || !keepAspectRatioView.isChecked()) {
						return;
					}
					try {
						int height = Integer.parseInt(s.toString());
						int newWidth = Math.round(height * aspectRatio);
						newWidth = Math.max(1, Math.min(newWidth, MAX_TEXTURE_SIZE));
						updatingSize = true;
						widthView.setText(String.valueOf(newWidth));
						updatingSize = false;
					} catch (NumberFormatException ignored) {
					}
				}
			});
		}
	}

	private void setSizeView(int power) {
		int size = getPower(power);
		if (sizeView != null) {
			sizeView.setText(String.format(
					Locale.US,
					"%d x %d",
					size,
					size));
		}
	}

	private void initNameView() {
		nameView.setFilters(new InputFilter[]{
				(source, start, end, dest, dstart, dend) -> NAME_PATTERN
						.matcher(source)
						.find() ? null : ""});
	}

	private void saveSamplerAsync() {
		final Context context = getActivity();

		if (context == null || inProgress) {
			return;
		}

		final String name = nameView.getText().toString();
		final TextureParameters tp = new TextureParameters();
		textureParameterView.setParameters(tp);
		final String params = tp.toString();

		if (name.trim().isEmpty()) {
			Toast.makeText(
					context,
					R.string.missing_name,
					Toast.LENGTH_SHORT).show();
			return;
		} else if (!name.matches(TEXTURE_NAME_PATTERN) ||
				name.equals(ShaderRenderer.UNIFORM_BACKBUFFER)) {
			Toast.makeText(
					context,
					R.string.invalid_texture_name,
					Toast.LENGTH_SHORT).show();
			return;
		}

		SoftKeyboard.hide(context, nameView);

		final int width;
		final int height;

		if (useSeekBarMode) {
			int size = getPower(sizeBarView.getProgress());
			width = size;
			height = size;
		} else {
			try {
				width = Math.max(1, Math.min(
						Integer.parseInt(widthView.getText().toString()),
						MAX_TEXTURE_SIZE));
				height = Math.max(1, Math.min(
						Integer.parseInt(heightView.getText().toString()),
						MAX_TEXTURE_SIZE));
			} catch (NumberFormatException e) {
				Toast.makeText(
						context,
						R.string.invalid_size,
						Toast.LENGTH_SHORT).show();
				return;
			}
		}

		inProgress = true;
		progressView.setVisibility(View.VISIBLE);

		Handler handler = new Handler(Looper.getMainLooper());
		try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
			executor.execute(() -> {
				int messageId = saveSampler(context, name, width, height);
				handler.post(() -> {
					inProgress = false;
					progressView.setVisibility(View.GONE);

					Activity activity = getActivity();
					if (activity == null) {
						return;
					}

					if (messageId > 0) {
						Toast.makeText(
								activity,
								messageId,
								Toast.LENGTH_SHORT).show();
						return;
					}

					if (addUniformView.isChecked()) {
						AddUniformActivity.setAddUniformResult(
								activity,
								"uniform " + samplerType + " " + name + ";" +
										params);
					}

					activity.finish();
				});
			});
		}
	}

	private static int getPower(int power) {
		return 1 << (power + 1);
	}
}
