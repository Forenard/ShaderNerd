package de.markusfisch.android.shadereditor.fragment;

import android.content.Intent;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import de.markusfisch.android.shadereditor.R;
import de.markusfisch.android.shadereditor.activity.AudioSamplesActivity;
import de.markusfisch.android.shadereditor.adapter.AudioSamplesAdapter;
import de.markusfisch.android.shadereditor.audio.AudioSampleBrowserEntry;
import de.markusfisch.android.shadereditor.audio.AudioSampleDirectoryListing;
import de.markusfisch.android.shadereditor.audio.AudioSampleInfo;
import de.markusfisch.android.shadereditor.audio.AudioSampleRepository;
import de.markusfisch.android.shadereditor.audio.AudioSampleWaveform;
import de.markusfisch.android.shadereditor.widget.AudioSampleWaveformView;

public class AudioSamplesFragment extends Fragment {
	private static final String STATE_CURRENT_DIRECTORY = "currentDirectory";
	private static final long PREVIEW_PROGRESS_INTERVAL_MS = 16L;
	private static final long PREVIEW_COMPLETE_HOLD_MS = 140L;
	private static final int PREVIEW_DISPLAY_LATENCY_MS = 90;

	@NonNull
	private final Handler mainHandler = new Handler(Looper.getMainLooper());
	@NonNull
	private final ExecutorService directoryExecutor = Executors.newSingleThreadExecutor();
	@NonNull
	private ExecutorService waveformExecutor = Executors.newSingleThreadExecutor();
	@NonNull
	private final ExecutorService insertExecutor = Executors.newSingleThreadExecutor();
	@NonNull
	private final Runnable previewProgressUpdater = new Runnable() {
		@Override
		public void run() {
			MediaPlayer player = mediaPlayer;
			if (player == null || previewingRelativePath == null) {
				return;
			}

			try {
				int duration = previewDurationMs > 0
						? previewDurationMs
						: Math.max(0, player.getDuration());
				if (duration > 0) {
					previewDurationMs = duration;
				}
				int reportedPosition = Math.max(0, player.getCurrentPosition());
				if (reportedPosition > 0) {
					previewHasObservedPlayback = true;
				}

				int position = previewHasObservedPlayback
						? Math.max(0, reportedPosition - PREVIEW_DISPLAY_LATENCY_MS)
						: 0;
				float progress = duration > 0
						? Math.min(1f, position / (float) duration)
						: 0f;
				previewProgress = progress;
				if (adapter != null) {
					adapter.setPreviewProgress(progress);
				}
				updateVisiblePreviewProgress(progress);
				if (mediaPlayer != null && previewingRelativePath != null) {
					mainHandler.postDelayed(this, PREVIEW_PROGRESS_INTERVAL_MS);
				}
			} catch (IllegalStateException ignored) {
			}
		}
	};

	@Nullable
	private ActivityResultLauncher<Uri> openDocumentTreeLauncher;
	@Nullable
	private AudioSampleRepository repository;
	@Nullable
	private AudioSamplesAdapter adapter;
	@Nullable
	private ListView listView;
	@Nullable
	private TextView emptyView;
	@Nullable
	private TextView folderSummary;
	@Nullable
	private TextView currentPath;
	@Nullable
	private ProgressBar progressBar;
	@Nullable
	private Button chooseFolder;
	@Nullable
	private Button clearFolder;
	@Nullable
	private Button upButton;
	@Nullable
	private MediaPlayer mediaPlayer;
	@Nullable
	private String previewingRelativePath;
	private float previewProgress = -1f;
	private int previewDurationMs;
	private int previewSessionToken;
	private boolean previewHasObservedPlayback;
	@NonNull
	private String currentDirectory = "";
	private int directoryLoadToken;
	@NonNull
	private final Map<String, AudioSampleWaveform> waveforms = new HashMap<>();
	@NonNull
	private final Set<String> loadingWaveforms = new HashSet<>();

	@Override
	public void onCreate(@Nullable Bundle state) {
		super.onCreate(state);
		repository = AudioSampleRepository.getInstance(requireContext());
		openDocumentTreeLauncher = registerForActivityResult(
				new ActivityResultContracts.OpenDocumentTree(),
				this::handleFolderPicked);
		if (state != null) {
			currentDirectory = state.getString(STATE_CURRENT_DIRECTORY, "");
		}
	}

	@Override
	public void onSaveInstanceState(@NonNull Bundle outState) {
		super.onSaveInstanceState(outState);
		outState.putString(STATE_CURRENT_DIRECTORY, currentDirectory);
	}

	@Override
	public View onCreateView(@NonNull LayoutInflater inflater,
			ViewGroup container,
			Bundle state) {
		View view = inflater.inflate(R.layout.fragment_audio_samples, container, false);

		listView = view.findViewById(R.id.audio_samples);
		emptyView = view.findViewById(R.id.audio_samples_empty);
		folderSummary = view.findViewById(R.id.audio_sample_folder_summary);
		currentPath = view.findViewById(R.id.audio_sample_current_path);
		progressBar = view.findViewById(R.id.audio_samples_progress);
		chooseFolder = view.findViewById(R.id.choose_audio_sample_folder);
		clearFolder = view.findViewById(R.id.clear_audio_sample_folder);
		upButton = view.findViewById(R.id.audio_sample_up);

		adapter = new AudioSamplesAdapter(requireContext(), new AudioSamplesAdapter.Listener() {
			@Override
			public void onOpenDirectory(@NonNull String relativePath) {
				openDirectory(relativePath);
			}

			@Override
			public void onPreviewToggle(@NonNull AudioSampleInfo sample) {
				togglePreview(sample);
			}

			@Override
			public void onInsert(@NonNull AudioSampleInfo sample) {
				insertSample(sample);
			}
		});
		listView.setAdapter(adapter);

		chooseFolder.setOnClickListener(v -> launchFolderPicker());
		clearFolder.setOnClickListener(v -> clearFolder());
		upButton.setOnClickListener(v -> navigateUp());

		updateFolderUi();
		loadCurrentDirectoryAsync();

		return view;
	}

	@Override
	public void onPause() {
		super.onPause();
		stopPreview();
	}

	@Override
	public void onDestroyView() {
		super.onDestroyView();
		stopPreview();
		listView = null;
		emptyView = null;
		folderSummary = null;
		currentPath = null;
		progressBar = null;
		chooseFolder = null;
		clearFolder = null;
		upButton = null;
		adapter = null;
	}

	@Override
	public void onDestroy() {
		super.onDestroy();
		directoryExecutor.shutdownNow();
		waveformExecutor.shutdownNow();
		insertExecutor.shutdownNow();
	}

	private void launchFolderPicker() {
		if (openDocumentTreeLauncher == null || repository == null) {
			return;
		}
		openDocumentTreeLauncher.launch(repository.getFolderUri());
	}

	private void clearFolder() {
		if (repository == null) {
			return;
		}
		stopPreview();
		currentDirectory = "";
		restartWaveformExecutor();
		waveforms.clear();
		loadingWaveforms.clear();
		repository.setFolderUri(null);
		if (adapter != null) {
			adapter.setEntries(Collections.emptyList());
			adapter.setWaveforms(Collections.emptyMap());
			adapter.setLoadingWaveforms(Collections.emptySet());
		}
		updateFolderUi();
		loadCurrentDirectoryAsync();
	}

	private void handleFolderPicked(@Nullable Uri uri) {
		if (uri == null || repository == null || getContext() == null) {
			return;
		}
		try {
			requireContext().getContentResolver().takePersistableUriPermission(uri,
					Intent.FLAG_GRANT_READ_URI_PERMISSION |
							Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
		} catch (SecurityException ignored) {
		}
		stopPreview();
		currentDirectory = "";
		restartWaveformExecutor();
		waveforms.clear();
		loadingWaveforms.clear();
		repository.setFolderUri(uri);
		updateFolderUi();
		loadCurrentDirectoryAsync();
	}

	private void openDirectory(@NonNull String relativePath) {
		stopPreview();
		currentDirectory = relativePath;
		loadCurrentDirectoryAsync();
	}

	private void navigateUp() {
		if (currentDirectory.isEmpty()) {
			return;
		}
		stopPreview();
		int slash = currentDirectory.lastIndexOf('/');
		currentDirectory = slash > 0 ? currentDirectory.substring(0, slash) : "";
		loadCurrentDirectoryAsync();
	}

	private void loadCurrentDirectoryAsync() {
		AudioSampleRepository sampleRepository = repository;
		if (sampleRepository == null) {
			return;
		}

		final int token = ++directoryLoadToken;
		final String requestedDirectory = currentDirectory;
		setLoading(true);
		directoryExecutor.execute(() -> {
			AudioSampleDirectoryListing listing = sampleRepository.listDirectory(requestedDirectory);
			HashMap<String, AudioSampleWaveform> cachedWaveforms = new HashMap<>();
			for (AudioSampleBrowserEntry entry : listing.entries()) {
				if (!entry.isSample() || entry.sample() == null) {
					continue;
				}
				AudioSampleWaveform waveform = sampleRepository.getCachedWaveform(entry.sample());
				if (waveform != null) {
					cachedWaveforms.put(entry.sample().relativePath(), waveform);
				}
			}
			mainHandler.post(() -> applyDirectory(token, listing, cachedWaveforms));
		});
	}

	private void applyDirectory(int token,
			@NonNull AudioSampleDirectoryListing listing,
			@NonNull Map<String, AudioSampleWaveform> cachedWaveforms) {
		if (!isAdded() || adapter == null || token != directoryLoadToken) {
			return;
		}

		currentDirectory = listing.relativePath();
		waveforms.clear();
		waveforms.putAll(cachedWaveforms);
		loadingWaveforms.clear();

		adapter.setEntries(listing.entries());
		adapter.setWaveforms(cachedWaveforms);
		adapter.setLoadingWaveforms(Collections.emptySet());

		updateFolderUi();
		setLoading(false);

		boolean hasFolder = repository != null && repository.getFolderUri() != null;
		boolean hasEntries = !listing.entries().isEmpty();
		if (emptyView != null) {
			emptyView.setText(hasFolder
					? R.string.audio_sample_no_files
					: R.string.audio_sample_no_folder);
			emptyView.setVisibility(hasEntries ? View.GONE : View.VISIBLE);
		}
		if (listView != null) {
			listView.setVisibility(hasEntries ? View.VISIBLE : View.GONE);
		}

		queueWaveformLoads(token, listing);
		updateVisiblePreviewProgress(previewingRelativePath != null ? previewProgress : -1f);
	}

	private void queueWaveformLoads(int token,
			@NonNull AudioSampleDirectoryListing listing) {
		AudioSampleRepository sampleRepository = repository;
		AudioSamplesAdapter samplesAdapter = adapter;
		if (sampleRepository == null || samplesAdapter == null) {
			return;
		}

		restartWaveformExecutor();
		loadingWaveforms.clear();

		for (AudioSampleBrowserEntry entry : listing.entries()) {
			AudioSampleInfo sample = entry.sample();
			if (!entry.isSample() || sample == null || waveforms.containsKey(sample.relativePath())) {
				continue;
			}
			loadingWaveforms.add(sample.relativePath());
		}

		samplesAdapter.setLoadingWaveforms(loadingWaveforms);

		for (AudioSampleBrowserEntry entry : listing.entries()) {
			AudioSampleInfo sample = entry.sample();
			if (!entry.isSample() || sample == null || waveforms.containsKey(sample.relativePath())) {
				continue;
			}

			waveformExecutor.execute(() -> {
				AudioSampleWaveform waveform = null;
				try {
					waveform = sampleRepository.loadWaveform(sample);
				} catch (IOException ignored) {
				}
				AudioSampleWaveform result = waveform;
				mainHandler.post(() -> applyWaveform(token, listing.relativePath(), sample, result));
			});
		}
	}

	private void applyWaveform(int token,
			@NonNull String directory,
			@NonNull AudioSampleInfo sample,
			@Nullable AudioSampleWaveform waveform) {
		if (!isAdded() || adapter == null || token != directoryLoadToken ||
				!directory.equals(currentDirectory)) {
			return;
		}

		loadingWaveforms.remove(sample.relativePath());
		if (waveform != null) {
			waveforms.put(sample.relativePath(), waveform);
			adapter.putWaveform(sample.relativePath(), waveform);
		} else {
			adapter.markWaveformFailed(sample.relativePath());
		}
		if (!loadingWaveforms.isEmpty()) {
			adapter.setLoadingWaveforms(loadingWaveforms);
		}
		updateVisiblePreviewProgress(previewingRelativePath != null ? previewProgress : -1f);
	}

	private void updateFolderUi() {
		if (repository == null) {
			return;
		}

		boolean hasFolder = repository.getFolderUri() != null;
		if (folderSummary != null) {
			folderSummary.setText(hasFolder
					? repository.getFolderSummary()
					: getString(R.string.audio_sample_no_folder));
		}
		if (currentPath != null) {
			currentPath.setText(hasFolder
					? currentDirectory.isEmpty()
							? getString(R.string.audio_sample_root)
							: currentDirectory
					: getString(R.string.audio_sample_root));
		}
		if (chooseFolder != null) {
			chooseFolder.setText(hasFolder
					? R.string.audio_sample_change_folder
					: R.string.audio_sample_choose_folder);
		}
		if (clearFolder != null) {
			clearFolder.setVisibility(hasFolder ? View.VISIBLE : View.GONE);
		}
		if (upButton != null) {
			upButton.setVisibility(hasFolder && !currentDirectory.isEmpty()
					? View.VISIBLE
					: View.GONE);
		}
	}

	private void setLoading(boolean loading) {
		if (progressBar != null) {
			progressBar.setVisibility(loading ? View.VISIBLE : View.GONE);
		}
		if (!loading) {
			return;
		}

		boolean hasEntries = adapter != null && adapter.getCount() > 0;
		if (!hasEntries) {
			if (emptyView != null) {
				emptyView.setText(R.string.audio_sample_loading);
				emptyView.setVisibility(View.VISIBLE);
			}
			if (listView != null) {
				listView.setVisibility(View.GONE);
			}
		}
	}

	private void togglePreview(@NonNull AudioSampleInfo sample) {
		if (sample.relativePath().equals(previewingRelativePath)) {
			stopPreview();
			return;
		}
		startPreview(sample);
	}

	private void startPreview(@NonNull AudioSampleInfo sample) {
		stopPreview();
		if (getContext() == null) {
			return;
		}

		final int previewToken = ++previewSessionToken;
		AudioSampleWaveform waveform = waveforms.get(sample.relativePath());
		previewDurationMs = waveform != null ? waveform.durationMs() : 0;
		previewHasObservedPlayback = false;
		MediaPlayer player = new MediaPlayer();
		player.setAudioAttributes(new AudioAttributes.Builder()
				.setUsage(AudioAttributes.USAGE_MEDIA)
				.setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
				.build());
		player.setOnPreparedListener(mp -> {
			if (previewToken != previewSessionToken ||
					mediaPlayer != mp ||
					!sample.relativePath().equals(previewingRelativePath)) {
				releasePreviewPlayer(mp);
				return;
			}
			int duration = Math.max(0, mp.getDuration());
			if (duration > 0) {
				previewDurationMs = duration;
			}
			mp.start();
			if (adapter != null) {
				adapter.setPreviewProgress(0f);
			}
			updateVisiblePreviewProgress(0f);
			mainHandler.removeCallbacks(previewProgressUpdater);
			mainHandler.post(previewProgressUpdater);
		});
		player.setOnCompletionListener(mp -> {
			if (previewToken != previewSessionToken || mediaPlayer != mp) {
				releasePreviewPlayer(mp);
				return;
			}
			previewProgress = 1f;
			if (adapter != null) {
				adapter.setPreviewProgress(1f);
			}
			updateVisiblePreviewProgress(1f);
			mainHandler.removeCallbacks(previewProgressUpdater);
			mainHandler.postDelayed(() -> {
				if (previewToken == previewSessionToken && mediaPlayer == mp) {
					stopPreview();
				}
			}, PREVIEW_COMPLETE_HOLD_MS);
		});
		player.setOnErrorListener((mp, what, extra) -> {
			if (previewToken != previewSessionToken || mediaPlayer != mp) {
				releasePreviewPlayer(mp);
				return true;
			}
			showPreviewFailed();
			stopPreview();
			return true;
		});

		try {
			player.setDataSource(requireContext(), sample.uri());
			player.prepareAsync();
			mediaPlayer = player;
			previewingRelativePath = sample.relativePath();
			previewProgress = 0f;
			if (adapter != null) {
				adapter.setPreviewProgress(0f);
				adapter.setPreviewingRelativePath(previewingRelativePath);
			}
			updateVisiblePreviewProgress(0f);
		} catch (IOException | IllegalArgumentException | SecurityException e) {
			player.release();
			showPreviewFailed();
			stopPreview();
		}
	}

	private void stopPreview() {
		++previewSessionToken;
		mainHandler.removeCallbacks(previewProgressUpdater);

		MediaPlayer player = mediaPlayer;
		mediaPlayer = null;
		releasePreviewPlayer(player);

		previewingRelativePath = null;
		previewProgress = -1f;
		previewDurationMs = 0;
		previewHasObservedPlayback = false;
		if (adapter != null) {
			adapter.setPreviewProgress(-1f);
			adapter.setPreviewingRelativePath(null);
		}
		updateVisiblePreviewProgress(-1f);
	}

	private void updateVisiblePreviewProgress(float progress) {
		ListView samplesList = listView;
		if (samplesList == null) {
			return;
		}
		for (int i = 0; i < samplesList.getChildCount(); ++i) {
			View child = samplesList.getChildAt(i);
			AudioSampleWaveformView waveformView = child.findViewById(R.id.audio_sample_waveform);
			if (waveformView == null) {
				continue;
			}
			Object tag = child.getTag(R.id.audio_sample_waveform);
			if (previewingRelativePath != null && previewingRelativePath.equals(tag)) {
				waveformView.setPreviewProgress(progress);
			} else {
				waveformView.setPreviewProgress(-1f);
			}
		}
	}

	private void showPreviewFailed() {
		if (getContext() != null) {
			Toast.makeText(getContext(), R.string.audio_sample_preview_failed,
					Toast.LENGTH_SHORT).show();
		}
	}

	private void insertSample(@NonNull AudioSampleInfo sample) {
		stopPreview();
		if (getActivity() == null || getContext() == null) {
			return;
		}

		View view = LayoutInflater.from(requireContext())
				.inflate(R.layout.dialog_insert_audio_sample, null);
		EditText nameView = view.findViewById(R.id.audio_sample_insert_name);
		CheckBox trimSilenceView = view.findViewById(R.id.audio_sample_trim_silence);
		nameView.setText(AudioSampleRepository.suggestInsertName(sample));
		nameView.setSelection(nameView.getText().length());

		new MaterialAlertDialogBuilder(requireContext())
				.setTitle(R.string.audio_sample_insert_title)
				.setView(view)
				.setNegativeButton(android.R.string.cancel, null)
				.setPositiveButton(android.R.string.ok, (dialog, which) -> prepareInsertAsync(
						sample,
						nameView.getText().toString(),
						trimSilenceView.isChecked()))
				.show();
	}

	private void restartWaveformExecutor() {
		waveformExecutor.shutdownNow();
		waveformExecutor = Executors.newSingleThreadExecutor();
	}

	private void prepareInsertAsync(@NonNull AudioSampleInfo sample,
			@NonNull String requestedName,
			boolean trimSilence) {
		AudioSampleRepository sampleRepository = repository;
		if (sampleRepository == null || getContext() == null) {
			return;
		}

		String effectiveName = requestedName.trim().isEmpty()
				? AudioSampleRepository.suggestInsertName(sample)
				: requestedName.trim();
		Toast.makeText(requireContext(), R.string.audio_sample_preparing,
				Toast.LENGTH_SHORT).show();

		insertExecutor.execute(() -> {
			AudioSampleInfo prepared = null;
			IOException failure = null;
			try {
				prepared = sampleRepository.prepareInsertedSample(
						sample,
						effectiveName,
						trimSilence);
			} catch (IOException e) {
				failure = e;
			}

			AudioSampleInfo preparedSample = prepared;
			IOException insertFailure = failure;
			mainHandler.post(() -> finishPreparedInsert(preparedSample, insertFailure));
		});
	}

	private void finishPreparedInsert(@Nullable AudioSampleInfo preparedSample,
			@Nullable IOException failure) {
		if (!isAdded() || getActivity() == null || getContext() == null) {
			return;
		}
		if (failure != null || preparedSample == null) {
			String message = failure != null && failure.getMessage() != null
					? failure.getMessage()
					: getString(R.string.audio_sample_insert_failed);
			Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show();
			return;
		}

		AudioSamplesActivity.setSampleResult(getActivity(),
				AudioSampleRepository.buildInsertSnippet(preparedSample));
		getActivity().finish();
	}

	private void releasePreviewPlayer(@Nullable MediaPlayer player) {
		if (player == null) {
			return;
		}
		try {
			player.setOnPreparedListener(null);
			player.setOnCompletionListener(null);
			player.setOnErrorListener(null);
		} catch (IllegalStateException ignored) {
		}
		try {
			player.release();
		} catch (IllegalStateException ignored) {
		}
	}
}
