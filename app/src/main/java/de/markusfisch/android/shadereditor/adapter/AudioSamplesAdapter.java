package de.markusfisch.android.shadereditor.adapter;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import de.markusfisch.android.shadereditor.R;
import de.markusfisch.android.shadereditor.audio.AudioSampleBrowserEntry;
import de.markusfisch.android.shadereditor.audio.AudioSampleInfo;
import de.markusfisch.android.shadereditor.audio.AudioSampleWaveform;
import de.markusfisch.android.shadereditor.widget.AudioSampleWaveformView;

public class AudioSamplesAdapter extends BaseAdapter {
	private static final int VIEW_TYPE_DIRECTORY = 0;
	private static final int VIEW_TYPE_SAMPLE = 1;

	public interface Listener {
		void onOpenDirectory(@NonNull String relativePath);

		void onPreviewToggle(@NonNull AudioSampleInfo sample);

		void onInsert(@NonNull AudioSampleInfo sample);
	}

	@NonNull
	private final LayoutInflater inflater;
	@NonNull
	private final Listener listener;
	@NonNull
	private List<AudioSampleBrowserEntry> entries = Collections.emptyList();
	@NonNull
	private Map<String, AudioSampleWaveform> waveforms = Collections.emptyMap();
	@NonNull
	private Set<String> loadingWaveforms = Collections.emptySet();
	@Nullable
	private String previewingRelativePath;
	private float previewProgress = -1f;

	public AudioSamplesAdapter(@NonNull Context context, @NonNull Listener listener) {
		inflater = LayoutInflater.from(context);
		this.listener = listener;
	}

	public void setEntries(@NonNull List<AudioSampleBrowserEntry> entries) {
		this.entries = List.copyOf(entries);
		notifyDataSetChanged();
	}

	public void setWaveforms(@NonNull Map<String, AudioSampleWaveform> waveforms) {
		this.waveforms = Map.copyOf(waveforms);
		notifyDataSetChanged();
	}

	public void putWaveform(@NonNull String relativePath,
			@NonNull AudioSampleWaveform waveform) {
		HashMap<String, AudioSampleWaveform> updated = new HashMap<>(waveforms);
		updated.put(relativePath, waveform);
		waveforms = Map.copyOf(updated);
		loadingWaveforms = withoutLoading(relativePath);
		notifyDataSetChanged();
	}

	public void setLoadingWaveforms(@NonNull Set<String> relativePaths) {
		loadingWaveforms = Set.copyOf(relativePaths);
		notifyDataSetChanged();
	}

	public void markWaveformFailed(@NonNull String relativePath) {
		loadingWaveforms = withoutLoading(relativePath);
		notifyDataSetChanged();
	}

	public void setPreviewingRelativePath(@Nullable String previewingRelativePath) {
		this.previewingRelativePath = previewingRelativePath;
		notifyDataSetChanged();
	}

	public void setPreviewProgress(float previewProgress) {
		this.previewProgress = previewProgress;
	}

	@Override
	public int getViewTypeCount() {
		return 2;
	}

	@Override
	public int getItemViewType(int position) {
		return getItem(position).isDirectory()
				? VIEW_TYPE_DIRECTORY
				: VIEW_TYPE_SAMPLE;
	}

	@Override
	public int getCount() {
		return entries.size();
	}

	@Override
	public AudioSampleBrowserEntry getItem(int position) {
		return entries.get(position);
	}

	@Override
	public long getItemId(int position) {
		return position;
	}

	@Override
	public View getView(int position, View convertView, ViewGroup parent) {
		AudioSampleBrowserEntry entry = getItem(position);
		if (entry.isDirectory()) {
			return bindDirectoryView(entry, convertView, parent);
		}
		return bindSampleView(entry, convertView, parent);
	}

	@NonNull
	private View bindDirectoryView(@NonNull AudioSampleBrowserEntry entry,
			@Nullable View convertView,
			@NonNull ViewGroup parent) {
		View view = convertView;
		if (view == null) {
			view = inflater.inflate(R.layout.row_audio_sample_folder, parent, false);
		}

		DirectoryViewHolder holder = getDirectoryViewHolder(view);
		holder.name.setText(entry.name());
		holder.open.setOnClickListener(v -> listener.onOpenDirectory(entry.relativePath()));
		view.setOnClickListener(v -> listener.onOpenDirectory(entry.relativePath()));
		return view;
	}

	@NonNull
	private View bindSampleView(@NonNull AudioSampleBrowserEntry entry,
			@Nullable View convertView,
			@NonNull ViewGroup parent) {
		View view = convertView;
		if (view == null) {
			view = inflater.inflate(R.layout.row_audio_sample, parent, false);
		}

		SampleViewHolder holder = getSampleViewHolder(view);
		AudioSampleInfo sample = entry.sample();
		if (sample == null) {
			return view;
		}

		boolean previewing = sample.relativePath().equals(previewingRelativePath);
		holder.name.setText(sample.displayName());
		holder.uniform.setText(parent.getContext().getString(
				R.string.audio_sample_uniform_format,
				sample.uniformName()));
		holder.preview.setText(previewing
				? R.string.audio_sample_stop_preview
				: R.string.audio_sample_preview);
		holder.preview.setOnClickListener(v -> listener.onPreviewToggle(sample));
		holder.insert.setOnClickListener(v -> listener.onInsert(sample));

		AudioSampleWaveform waveform = waveforms.get(sample.relativePath());
		holder.waveform.setWaveform(
				waveform != null ? waveform.amplitudes() : null,
				loadingWaveforms.contains(sample.relativePath()));
		holder.waveform.setPreviewProgress(previewing ? previewProgress : -1f);
		view.setTag(R.id.audio_sample_waveform, sample.relativePath());
		return view;
	}

	@NonNull
	private Set<String> withoutLoading(@NonNull String relativePath) {
		HashSet<String> updated = new HashSet<>(loadingWaveforms);
		updated.remove(relativePath);
		return Set.copyOf(updated);
	}

	@NonNull
	private static DirectoryViewHolder getDirectoryViewHolder(@NonNull View view) {
		DirectoryViewHolder holder = (DirectoryViewHolder) view.getTag();
		if (holder == null) {
			holder = new DirectoryViewHolder();
			holder.name = view.findViewById(R.id.audio_sample_folder_name);
			holder.open = view.findViewById(R.id.open_audio_sample_folder);
			view.setTag(holder);
		}
		return holder;
	}

	@NonNull
	private static SampleViewHolder getSampleViewHolder(@NonNull View view) {
		SampleViewHolder holder = (SampleViewHolder) view.getTag();
		if (holder == null) {
			holder = new SampleViewHolder();
			holder.name = view.findViewById(R.id.audio_sample_name);
			holder.uniform = view.findViewById(R.id.audio_sample_uniform);
			holder.preview = view.findViewById(R.id.audio_sample_preview);
			holder.insert = view.findViewById(R.id.audio_sample_insert);
			holder.waveform = view.findViewById(R.id.audio_sample_waveform);
			view.setTag(holder);
		}
		return holder;
	}

	private static final class DirectoryViewHolder {
		private TextView name;
		private Button open;
	}

	private static final class SampleViewHolder {
		private TextView name;
		private TextView uniform;
		private Button preview;
		private Button insert;
		private AudioSampleWaveformView waveform;
	}
}
