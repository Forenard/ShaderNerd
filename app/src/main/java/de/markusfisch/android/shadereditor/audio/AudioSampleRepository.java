package de.markusfisch.android.shadereditor.audio;

import android.content.Context;
import android.net.Uri;
import android.util.Base64;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.documentfile.provider.DocumentFile;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import de.markusfisch.android.shadereditor.app.ShaderEditorApp;

public final class AudioSampleRepository {
	private static final String CACHE_FILE_NAME = ".shadernerd_audio_cache.json";
	private static final String CACHE_DIRECTORY_NAME = "shadernerd_audio_cache";
	private static final String LEGACY_CACHE_DIRECTORY_NAME = ".shadernerd_audio_cache";
	private static final int CACHE_VERSION = 1;
	private static final int WAVEFORM_BUCKETS = 96;
	private static final float TRIM_SILENCE_THRESHOLD = 0.0015f;

	private static volatile AudioSampleRepository instance;

	@NonNull
	private final Context context;
	@NonNull
	private final Object lock = new Object();
	@NonNull
	private final Map<String, List<AudioSampleBrowserEntry>> cachedDirectories = new HashMap<>();
	@NonNull
	private final Map<String, AudioSampleInfo> samplesByUniformName = new HashMap<>();
	@NonNull
	private final Map<String, AudioSampleInfo> samplesByRelativePath = new HashMap<>();
	@NonNull
	private final Map<String, DecodedAudioSample> decodedSamples = new HashMap<>();
	@NonNull
	private final Map<String, AudioSampleWaveform> waveformByRelativePath = new HashMap<>();
	@NonNull
	private final Map<String, CachedAudioSample> persistedSamples = new HashMap<>();
	@Nullable
	private String cachedFolderUriString;
	private boolean diskCacheLoaded;
	private boolean diskCacheDirty;

	private AudioSampleRepository(@NonNull Context context) {
		this.context = context.getApplicationContext();
	}

	@NonNull
	public static AudioSampleRepository getInstance(@NonNull Context context) {
		AudioSampleRepository repository = instance;
		if (repository == null) {
			synchronized (AudioSampleRepository.class) {
				repository = instance;
				if (repository == null) {
					repository = new AudioSampleRepository(context);
					instance = repository;
				}
			}
		}
		return repository;
	}

	public void setFolderUri(@Nullable Uri uri) {
		ShaderEditorApp.preferences.setAudioSampleFolderUri(
				uri != null ? uri.toString() : null);
		invalidate();
	}

	@Nullable
	public Uri getFolderUri() {
		String uriString = ShaderEditorApp.preferences.getAudioSampleFolderUri();
		return uriString == null || uriString.isEmpty()
				? null
				: Uri.parse(uriString);
	}

	@NonNull
	public String getFolderSummary() {
		DocumentFile directory = getRootDirectory();
		if (directory == null) {
			Uri uri = getFolderUri();
			return uri == null ? "" : uri.toString();
		}
		String name = directory.getName();
		return name == null || name.isEmpty() ? directory.getUri().toString() : name;
	}

	@NonNull
	public String getCurrentPathLabel(@Nullable String relativePath) {
		String normalized = normalizeRelativePath(relativePath);
		if (normalized.isEmpty()) {
			return "Root";
		}
		return normalized;
	}

	public void invalidate() {
		synchronized (lock) {
			cachedDirectories.clear();
			samplesByUniformName.clear();
			samplesByRelativePath.clear();
			decodedSamples.clear();
			waveformByRelativePath.clear();
			persistedSamples.clear();
			cachedFolderUriString = null;
			diskCacheLoaded = false;
			diskCacheDirty = false;
		}
	}

	@NonNull
	public AudioSampleDirectoryListing listDirectory(@Nullable String relativePath) {
		String normalized = normalizeRelativePath(relativePath);

		synchronized (lock) {
			ensureFolderStateLocked();
			ensureDiskCacheLoadedLocked();
			List<AudioSampleBrowserEntry> cached = cachedDirectories.get(normalized);
			if (cached != null) {
				return new AudioSampleDirectoryListing(normalized, cached);
			}
		}

		DocumentFile directory = resolveDocumentFile(normalized);
		if (directory == null || !directory.canRead() || !directory.isDirectory()) {
			return new AudioSampleDirectoryListing(normalized, Collections.emptyList());
		}

		ArrayList<AudioSampleBrowserEntry> directories = new ArrayList<>();
		ArrayList<AudioSampleBrowserEntry> samples = new ArrayList<>();
		for (DocumentFile file : sortFiles(directory.listFiles())) {
			String name = file.getName();
			if (name == null || name.isEmpty() || isCacheEntry(name)) {
				continue;
			}
			String childRelativePath = normalized.isEmpty()
					? name
					: normalized + "/" + name;
			if (file.isDirectory()) {
				directories.add(AudioSampleBrowserEntry.directory(name, childRelativePath));
				continue;
			}
			if (!file.isFile() || !isSupportedAudioFile(file)) {
				continue;
			}
			samples.add(AudioSampleBrowserEntry.sample(createSampleInfo(file, childRelativePath)));
		}

		ArrayList<AudioSampleBrowserEntry> entries = new ArrayList<>(
				directories.size() + samples.size());
		entries.addAll(directories);
		entries.addAll(samples);
		List<AudioSampleBrowserEntry> immutableEntries = List.copyOf(entries);
		synchronized (lock) {
			cachedDirectories.put(normalized, immutableEntries);
		}
		persistCacheIfDirty();
		return new AudioSampleDirectoryListing(normalized, immutableEntries);
	}

	@Nullable
	public AudioSampleWaveform getCachedWaveform(@NonNull AudioSampleInfo sample) {
		synchronized (lock) {
			ensureFolderStateLocked();
			AudioSampleWaveform waveform = waveformByRelativePath.get(sample.relativePath());
			if (waveform != null) {
				return waveform;
			}
			ensureDiskCacheLoadedLocked();
			CachedAudioSample cached = persistedSamples.get(sample.relativePath());
			if (cached == null || !cached.matches(sample) || cached.waveformBase64 == null) {
				return null;
			}

			float[] amplitudes = decodeWaveform(cached.waveformBase64);
			if (amplitudes.length == 0) {
				return null;
			}

			waveform = new AudioSampleWaveform(sample.relativePath(),
					cached.durationMs,
					amplitudes);
			waveformByRelativePath.put(sample.relativePath(), waveform);
			return waveform;
		}
	}

	@NonNull
	public AudioSampleWaveform loadWaveform(@NonNull AudioSampleInfo sample) throws IOException {
		AudioSampleWaveform cachedWaveform = getCachedWaveform(sample);
		if (cachedWaveform != null) {
			return cachedWaveform;
		}

		DecodedAudioSample decodedSample;
		synchronized (lock) {
			decodedSample = decodedSamples.get(sample.uniformName());
		}

		AudioSampleWaveform waveform = decodedSample != null
				? buildWaveformFromDecoded(sample.relativePath(), decodedSample, WAVEFORM_BUCKETS)
				: AudioSampleDecoder.decodeWaveform(context, sample, WAVEFORM_BUCKETS);

		synchronized (lock) {
			ensureFolderStateLocked();
			ensureDiskCacheLoadedLocked();
			waveformByRelativePath.put(sample.relativePath(), waveform);

			CachedAudioSample previous = persistedSamples.get(sample.relativePath());
			String uniformName = previous != null && previous.uniformName != null
					? previous.uniformName
					: sample.uniformName();
			persistedSamples.put(sample.relativePath(), new CachedAudioSample(
					sample.relativePath(),
					uniformName,
					sample.mimeType(),
					sample.lastModified(),
					sample.size(),
					waveform.durationMs(),
					encodeWaveform(waveform.amplitudes()),
					previous != null ? previous.sourceRelativePath : null,
					previous != null && previous.trimSilence));
			diskCacheDirty = true;
		}
		persistCacheIfDirty();
		return waveform;
	}

	@Nullable
	public AudioSampleInfo findSample(@NonNull String uniformName) {
		CachedAudioSample matchingCachedSample = null;
		synchronized (lock) {
			ensureFolderStateLocked();
			AudioSampleInfo cached = samplesByUniformName.get(uniformName);
			if (cached != null) {
				return cached;
			}
			AudioSampleInfo generated = resolveGeneratedSampleLocked(uniformName);
			if (generated != null) {
				return generated;
			}
			ensureDiskCacheLoadedLocked();
			for (CachedAudioSample sample : persistedSamples.values()) {
				if (uniformName.equals(sample.uniformName) ||
						uniformName.equals(buildLegacyUniformName(sample.relativePath))) {
					matchingCachedSample = sample;
					break;
				}
			}
		}
		if (matchingCachedSample != null) {
			AudioSampleInfo info = resolveOrRegenerateCachedSample(matchingCachedSample);
			if (info != null) {
				return info;
			}
		}

		AudioSampleInfo found = findSampleRecursively(uniformName);
		if (found != null) {
			persistCacheIfDirty();
		}
		return found;
	}

	@NonNull
	public DecodedAudioSample getDecodedSample(@NonNull String uniformName) throws IOException {
		synchronized (lock) {
			DecodedAudioSample cached = decodedSamples.get(uniformName);
			if (cached != null) {
				return cached;
			}
		}

		AudioSampleInfo sample = findSample(uniformName);
		if (sample == null) {
			throw new IOException("Audio sample not found: " + uniformName);
		}

		DecodedAudioSample decoded = AudioSampleDecoder.decode(context, sample);
		synchronized (lock) {
			decodedSamples.put(uniformName, decoded);
		}
		return decoded;
	}

	@NonNull
	public static String buildInsertSnippet(@NonNull String uniformName) {
		String metaName = uniformName + "_meta";
		String localName = uniformName.replaceFirst("^sample_", "");
		if (localName.isEmpty()) {
			localName = "sampleOut";
		}
		String wrapperName = "s_" + localName;
		return "uniform sampler2D " + uniformName + ";\n" +
				"uniform vec4 " + metaName + ";\n" +
				"#define " + wrapperName + "(t) sampleSinc(" +
				uniformName + ", " + metaName + ", t)\n";
	}

	@NonNull
	public static String buildInsertSnippet(@NonNull AudioSampleInfo sample) {
		return buildInsertSnippet(sample.uniformName());
	}

	@NonNull
	public static String suggestInsertName(@NonNull AudioSampleInfo sample) {
		String displayName = stripExtension(sample.displayName());
		String suggested = sanitizeIdentifier(displayName);
		if (suggested.length() > 24) {
			suggested = suggested.substring(0, 24)
					.replaceAll("_+$", "");
		}
		return suggested.isEmpty() ? "sample" : suggested;
	}

	@NonNull
	public static String normalizeInsertUniformName(@NonNull String rawName) {
		String normalized = sanitizeIdentifier(rawName.replaceFirst("^sample_", ""));
		if (normalized.isEmpty()) {
			normalized = "sample";
		}
		return "sample_" + normalized;
	}

	@NonNull
	public AudioSampleInfo prepareInsertedSample(@NonNull AudioSampleInfo sourceSample,
			@NonNull String requestedName,
			boolean trimSilence) throws IOException {
		String uniformName = normalizeInsertUniformName(requestedName);
		DecodedAudioSample decoded = AudioSampleDecoder.decode(context, sourceSample);
		TrimRange trimRange = trimSilence
				? findTrimRange(decoded)
				: new TrimRange(0, getFrameCount(decoded));

		DocumentFile rootDirectory = getRootDirectory();
		if (rootDirectory == null || !rootDirectory.canWrite()) {
			throw new IOException("Selected audio folder is not writable");
		}
		DocumentFile cacheDirectory = findCacheDirectory(rootDirectory, true);
		if (cacheDirectory == null || !cacheDirectory.canWrite()) {
			throw new IOException("Failed to create audio sample cache directory");
		}

		DocumentFile outputFile = createOrReplaceCachedSampleFile(cacheDirectory, uniformName);
		if (outputFile == null) {
			throw new IOException("Failed to create processed audio sample");
		}

		writeTrimmedWaveFile(outputFile, decoded, trimRange.startFrame, trimRange.endFrameExclusive);
		DocumentFile refreshedFile = findGeneratedSampleFile(cacheDirectory, uniformName);
		if (refreshedFile == null || !refreshedFile.isFile()) {
			throw new IOException("Processed audio sample could not be found after writing");
		}

		String relativePath = getGeneratedSampleRelativePath(cacheDirectory, refreshedFile);
		AudioSampleInfo prepared = createSampleInfo(refreshedFile, relativePath, uniformName);
		synchronized (lock) {
			CachedAudioSample previous = persistedSamples.get(prepared.relativePath());
			persistedSamples.put(prepared.relativePath(), new CachedAudioSample(
					prepared.relativePath(),
					prepared.uniformName(),
					prepared.mimeType(),
					prepared.lastModified(),
					prepared.size(),
					previous != null ? previous.durationMs : 0,
					previous != null ? previous.waveformBase64 : null,
					sourceSample.relativePath(),
					trimSilence));
			decodedSamples.remove(uniformName);
			waveformByRelativePath.remove(relativePath);
			diskCacheDirty = true;
		}
		persistCacheIfDirty();
		return prepared;
	}

	@Nullable
	private AudioSampleInfo findSampleRecursively(@NonNull String uniformName) {
		DocumentFile rootDirectory = getRootDirectory();
		if (rootDirectory == null || !rootDirectory.canRead()) {
			return null;
		}
		return findSampleRecursively(rootDirectory, "", uniformName);
	}

	@Nullable
	private AudioSampleInfo findSampleRecursively(@NonNull DocumentFile directory,
			@NonNull String relativeDirectory,
			@NonNull String uniformName) {
		for (DocumentFile file : sortFiles(directory.listFiles())) {
			if (Thread.currentThread().isInterrupted()) {
				return null;
			}
			String name = file.getName();
			if (name == null || name.isEmpty() || isCacheEntry(name)) {
				continue;
			}
			String relativePath = relativeDirectory.isEmpty()
					? name
					: relativeDirectory + "/" + name;
			if (file.isDirectory()) {
				AudioSampleInfo child = findSampleRecursively(file, relativePath, uniformName);
				if (child != null) {
					return child;
				}
				continue;
			}
			if (!file.isFile() || !isSupportedAudioFile(file)) {
				continue;
			}
			AudioSampleInfo sample = createSampleInfo(file, relativePath);
			if (uniformName.equals(sample.uniformName()) ||
					uniformName.equals(buildLegacyUniformName(relativePath))) {
				return sample;
			}
		}
		return null;
	}

	@Nullable
	private AudioSampleInfo resolveCachedSampleLocked(@NonNull String relativePath) {
		AudioSampleInfo cached = samplesByRelativePath.get(relativePath);
		if (cached != null) {
			return cached;
		}

		DocumentFile file = resolveDocumentFile(relativePath);
		if (file == null || !file.isFile() || !isSupportedAudioFile(file)) {
			persistedSamples.remove(relativePath);
			diskCacheDirty = true;
			return null;
		}
		return createSampleInfo(file, relativePath);
	}

	@Nullable
	private AudioSampleInfo resolveOrRegenerateCachedSample(
			@NonNull CachedAudioSample cachedSample) {
		synchronized (lock) {
			ensureFolderStateLocked();
			AudioSampleInfo cached = samplesByRelativePath.get(cachedSample.relativePath);
			if (cached != null) {
				return cached;
			}
		}

		DocumentFile file = resolveDocumentFile(cachedSample.relativePath);
		if (file != null && file.isFile() && isSupportedAudioFile(file)) {
			return createSampleInfo(file, cachedSample.relativePath, cachedSample.uniformName);
		}

		if (cachedSample.sourceRelativePath == null || cachedSample.sourceRelativePath.isEmpty()) {
			synchronized (lock) {
				persistedSamples.remove(cachedSample.relativePath);
				diskCacheDirty = true;
			}
			persistCacheIfDirty();
			return null;
		}

		DocumentFile sourceFile = resolveDocumentFile(cachedSample.sourceRelativePath);
		if (sourceFile == null || !sourceFile.isFile() || !isSupportedAudioFile(sourceFile)) {
			synchronized (lock) {
				persistedSamples.remove(cachedSample.relativePath);
				diskCacheDirty = true;
			}
			persistCacheIfDirty();
			return null;
		}

		AudioSampleInfo sourceSample = createSampleInfo(sourceFile, cachedSample.sourceRelativePath);
		AudioSampleInfo regenerated = null;
		try {
			regenerated = prepareInsertedSample(sourceSample,
					cachedSample.uniformName != null
							? cachedSample.uniformName
							: suggestInsertName(sourceSample),
					cachedSample.trimSilence);
		} catch (IOException ignored) {
		}

		if (regenerated != null &&
				!cachedSample.relativePath.equals(regenerated.relativePath())) {
			synchronized (lock) {
				persistedSamples.remove(cachedSample.relativePath);
				diskCacheDirty = true;
			}
			persistCacheIfDirty();
		}
		return regenerated;
	}

	@NonNull
	private AudioSampleInfo createSampleInfo(@NonNull DocumentFile file,
			@NonNull String relativePath) {
		return createSampleInfo(file, relativePath, null);
	}

	@NonNull
	private AudioSampleInfo createSampleInfo(@NonNull DocumentFile file,
			@NonNull String relativePath,
			@Nullable String preferredUniformName) {
		String displayName = file.getName();
		if (displayName == null || displayName.isEmpty()) {
			displayName = relativePath;
		}
		String relativeDirectory = getParentPath(relativePath);

		CachedAudioSample cachedSample;
		synchronized (lock) {
			ensureFolderStateLocked();
			ensureDiskCacheLoadedLocked();
			cachedSample = persistedSamples.get(relativePath);
		}

		String uniformName = preferredUniformName != null && !preferredUniformName.isEmpty()
				? preferredUniformName
				: cachedSample != null && cachedSample.uniformName != null &&
						!cachedSample.uniformName.isEmpty()
				? cachedSample.uniformName
				: buildCanonicalUniformName(relativePath);
		AudioSampleInfo sample = new AudioSampleInfo(
				displayName,
				relativePath,
				relativeDirectory,
				uniformName,
				file.lastModified(),
				file.length(),
				file.getUri(),
				file.getType());

		synchronized (lock) {
			cacheSampleInfoLocked(sample);
			boolean metadataChanged = cachedSample == null ||
					cachedSample.lastModified != sample.lastModified() ||
					cachedSample.size != sample.size();
			CachedAudioSample updatedCache = new CachedAudioSample(
					relativePath,
					uniformName,
					sample.mimeType(),
					sample.lastModified(),
					sample.size(),
					!metadataChanged && cachedSample != null ? cachedSample.durationMs : 0,
					!metadataChanged && cachedSample != null
							? cachedSample.waveformBase64
							: null,
					cachedSample != null ? cachedSample.sourceRelativePath : null,
					cachedSample != null && cachedSample.trimSilence);
			CachedAudioSample previous = persistedSamples.put(relativePath, updatedCache);
			if (!updatedCache.equals(previous)) {
				diskCacheDirty = true;
			}
		}

		return sample;
	}

	private void cacheSampleInfoLocked(@NonNull AudioSampleInfo sample) {
		samplesByRelativePath.put(sample.relativePath(), sample);
		samplesByUniformName.put(sample.uniformName(), sample);
	}

	@Nullable
	private AudioSampleInfo resolveGeneratedSampleLocked(@NonNull String uniformName) {
		DocumentFile rootDirectory = getRootDirectory();
		if (rootDirectory == null) {
			return null;
		}
		DocumentFile cacheDirectory = findCacheDirectory(rootDirectory, false);
		if (cacheDirectory == null || !cacheDirectory.isDirectory()) {
			return null;
		}
		DocumentFile file = findGeneratedSampleFile(cacheDirectory, uniformName);
		if (file == null || !file.isFile() || !isSupportedAudioFile(file)) {
			return null;
		}
		return createSampleInfo(file,
				getGeneratedSampleRelativePath(cacheDirectory, file),
				uniformName);
	}

	private void ensureFolderStateLocked() {
		String folderUriString = ShaderEditorApp.preferences.getAudioSampleFolderUri();
		if (Objects.equals(cachedFolderUriString, folderUriString)) {
			return;
		}

		cachedFolderUriString = folderUriString;
		cachedDirectories.clear();
		samplesByUniformName.clear();
		samplesByRelativePath.clear();
		decodedSamples.clear();
		waveformByRelativePath.clear();
		persistedSamples.clear();
		diskCacheLoaded = false;
		diskCacheDirty = false;
	}

	private void ensureDiskCacheLoadedLocked() {
		if (diskCacheLoaded) {
			return;
		}
		diskCacheLoaded = true;

		DocumentFile rootDirectory = getRootDirectory();
		if (rootDirectory == null || !rootDirectory.canRead()) {
			return;
		}

		DocumentFile cacheFile = findCacheFile(rootDirectory, false);
		if (cacheFile == null || !cacheFile.isFile()) {
			return;
		}

		try (InputStream input = context.getContentResolver()
				.openInputStream(cacheFile.getUri())) {
			if (input == null) {
				return;
			}
			JSONObject root = new JSONObject(readUtf8(input));
			if (root.optInt("version", 0) != CACHE_VERSION) {
				return;
			}

			JSONArray samples = root.optJSONArray("samples");
			if (samples == null) {
				return;
			}
			for (int i = 0; i < samples.length(); ++i) {
				JSONObject object = samples.optJSONObject(i);
				if (object == null) {
					continue;
				}
				CachedAudioSample sample = CachedAudioSample.fromJson(object);
				if (sample != null) {
					persistedSamples.put(sample.relativePath, sample);
				}
			}
		} catch (IOException | JSONException ignored) {
			persistedSamples.clear();
		}
	}

	private void persistCacheIfDirty() {
		Uri folderUri;
		ArrayList<CachedAudioSample> snapshot;

		synchronized (lock) {
			ensureFolderStateLocked();
			if (!diskCacheDirty || cachedFolderUriString == null || cachedFolderUriString.isEmpty()) {
				return;
			}
			folderUri = Uri.parse(cachedFolderUriString);
			snapshot = new ArrayList<>(persistedSamples.values());
			snapshot.sort(Comparator.comparing(sample -> sample.relativePath));
			diskCacheDirty = false;
		}

		try {
			writeCacheFile(folderUri, snapshot);
		} catch (IOException | JSONException e) {
			synchronized (lock) {
				diskCacheDirty = true;
			}
		}
	}

	private void writeCacheFile(@NonNull Uri folderUri,
			@NonNull List<CachedAudioSample> samples) throws IOException, JSONException {
		DocumentFile rootDirectory = DocumentFile.fromTreeUri(context, folderUri);
		if (rootDirectory == null || !rootDirectory.canWrite()) {
			return;
		}

		DocumentFile cacheFile = findCacheFile(rootDirectory, true);
		if (cacheFile == null) {
			return;
		}

		JSONObject root = new JSONObject();
		root.put("version", CACHE_VERSION);

		JSONArray items = new JSONArray();
		for (CachedAudioSample sample : samples) {
			items.put(sample.toJson());
		}
		root.put("samples", items);

		try (OutputStream output = context.getContentResolver()
				.openOutputStream(cacheFile.getUri(), "wt")) {
			if (output == null) {
				throw new IOException("Failed to open audio cache for writing");
			}
			try (OutputStreamWriter writer = new OutputStreamWriter(output,
					StandardCharsets.UTF_8)) {
				writer.write(root.toString());
			}
		}
	}

	@Nullable
	private DocumentFile getRootDirectory() {
		Uri uri = getFolderUri();
		if (uri == null) {
			return null;
		}
		return DocumentFile.fromTreeUri(context, uri);
	}

	@Nullable
	private DocumentFile resolveDocumentFile(@NonNull String relativePath) {
		DocumentFile current = getRootDirectory();
		if (current == null || !current.canRead()) {
			return null;
		}
		if (relativePath.isEmpty()) {
			return current;
		}

		String[] parts = relativePath.split("/");
		for (String part : parts) {
			if (part.isEmpty()) {
				continue;
			}
			current = findChildByName(current, part);
			if (current == null) {
				return null;
			}
		}
		return current;
	}

	@Nullable
	private static DocumentFile findCacheDirectory(@NonNull DocumentFile rootDirectory,
			boolean createIfMissing) {
		DocumentFile visibleDirectory = null;
		DocumentFile legacyDirectory = null;
		for (DocumentFile file : rootDirectory.listFiles()) {
			String name = file.getName();
			if (name == null || !file.isDirectory()) {
				continue;
			}
			if (CACHE_DIRECTORY_NAME.equals(name)) {
				visibleDirectory = file;
			} else if (LEGACY_CACHE_DIRECTORY_NAME.equals(name)) {
				legacyDirectory = file;
			}
		}

		if (visibleDirectory != null) {
			return visibleDirectory;
		}
		if (!createIfMissing) {
			return legacyDirectory;
		}
		if (!createIfMissing || !rootDirectory.canWrite()) {
			return null;
		}
		return rootDirectory.createDirectory(CACHE_DIRECTORY_NAME);
	}

	@Nullable
	private static DocumentFile createOrReplaceCachedSampleFile(
			@NonNull DocumentFile cacheDirectory,
			@NonNull String uniformName) {
		DocumentFile existing = findGeneratedSampleFile(cacheDirectory, uniformName);
		if (existing != null) {
			existing.delete();
		}
		return cacheDirectory.createFile("audio/wav", uniformName);
	}

	@Nullable
	private static DocumentFile findGeneratedSampleFile(@NonNull DocumentFile cacheDirectory,
			@NonNull String uniformName) {
		for (DocumentFile file : cacheDirectory.listFiles()) {
			String name = file.getName();
			if (name == null || !file.isFile()) {
				continue;
			}
			if (uniformName.equals(stripExtension(name))) {
				return file;
			}
		}
		return null;
	}

	@NonNull
	private static String getGeneratedSampleFilename(@NonNull DocumentFile file) {
		String name = file.getName();
		return name == null || name.isEmpty()
				? "generated.wav"
				: name;
	}

	@NonNull
	private static String getGeneratedSampleRelativePath(@NonNull DocumentFile directory,
			@NonNull DocumentFile file) {
		String directoryName = directory.getName();
		if (directoryName == null || directoryName.isEmpty()) {
			directoryName = CACHE_DIRECTORY_NAME;
		}
		return directoryName + "/" + getGeneratedSampleFilename(file);
	}

	@Nullable
	private static DocumentFile findChildByName(@NonNull DocumentFile directory,
			@NonNull String name) {
		for (DocumentFile file : directory.listFiles()) {
			if (name.equals(file.getName())) {
				return file;
			}
		}
		return null;
	}

	@Nullable
	private static DocumentFile findCacheFile(@NonNull DocumentFile directory,
			boolean createIfMissing) {
		for (DocumentFile file : directory.listFiles()) {
			if (CACHE_FILE_NAME.equals(file.getName())) {
				return file;
			}
		}
		if (!createIfMissing || !directory.canWrite()) {
			return null;
		}
		return directory.createFile("application/json", CACHE_FILE_NAME);
	}

	@NonNull
	private static List<DocumentFile> sortFiles(@NonNull DocumentFile[] files) {
		ArrayList<DocumentFile> sortedFiles = new ArrayList<>(files.length);
		Collections.addAll(sortedFiles, files);
		sortedFiles.sort((left, right) -> {
			boolean leftDirectory = left.isDirectory();
			boolean rightDirectory = right.isDirectory();
			if (leftDirectory != rightDirectory) {
				return leftDirectory ? -1 : 1;
			}
			return getSortName(left).compareTo(getSortName(right));
		});
		return sortedFiles;
	}

	private static boolean isSupportedAudioFile(@NonNull DocumentFile file) {
		String mimeType = file.getType();
		if (mimeType != null && mimeType.startsWith("audio/")) {
			return true;
		}
		String name = file.getName();
		if (name == null) {
			return false;
		}
		String lower = name.toLowerCase(Locale.US);
		return lower.endsWith(".wav") ||
				lower.endsWith(".mp3") ||
				lower.endsWith(".m4a") ||
				lower.endsWith(".aac") ||
				lower.endsWith(".ogg") ||
				lower.endsWith(".flac");
	}

	@NonNull
	private static String buildCanonicalUniformName(@NonNull String relativePath) {
		String normalized = stripExtension(relativePath).toLowerCase(Locale.US);
		String baseName = sanitizeIdentifier(normalized.replace('/', '_'));
		if (baseName.length() > 40) {
			baseName = baseName.substring(0, 40);
		}
		String hash = Integer.toHexString(normalized.hashCode());
		if (hash.length() > 8) {
			hash = hash.substring(hash.length() - 8);
		}
		return "sample_" + baseName + "_" + hash;
	}

	@NonNull
	private static String buildLegacyUniformName(@NonNull String relativePath) {
		return "sample_" + sanitizeIdentifier(
				stripExtension(relativePath).toLowerCase(Locale.US)
						.replace('/', '_'));
	}

	@NonNull
	private static String sanitizeIdentifier(@NonNull String rawName) {
		String sanitized = rawName.toLowerCase(Locale.US)
				.replaceAll("[^a-z0-9_]+", "_")
				.replaceAll("_+", "_")
				.replaceAll("^_+", "")
				.replaceAll("_+$", "");
		if (sanitized.isEmpty()) {
			return "file";
		}
		if (Character.isDigit(sanitized.charAt(0))) {
			return "file_" + sanitized;
		}
		return sanitized;
	}

	@NonNull
	private static String stripExtension(@NonNull String name) {
		int dot = name.lastIndexOf('.');
		return dot > 0 ? name.substring(0, dot) : name;
	}

	@NonNull
	private static String normalizeRelativePath(@Nullable String relativePath) {
		if (relativePath == null || relativePath.isEmpty()) {
			return "";
		}
		String normalized = relativePath.replace('\\', '/');
		while (normalized.startsWith("/")) {
			normalized = normalized.substring(1);
		}
		while (normalized.endsWith("/")) {
			normalized = normalized.substring(0, normalized.length() - 1);
		}
		return normalized;
	}

	@NonNull
	private static String getParentPath(@NonNull String relativePath) {
		int slash = relativePath.lastIndexOf('/');
		return slash > 0 ? relativePath.substring(0, slash) : "";
	}

	@NonNull
	private static String getSortName(@NonNull DocumentFile file) {
		String name = file.getName();
		return name == null ? "" : name.toLowerCase(Locale.US);
	}

	private static boolean isCacheEntry(@NonNull String name) {
		return CACHE_FILE_NAME.equals(name) ||
				CACHE_DIRECTORY_NAME.equals(name) ||
				LEGACY_CACHE_DIRECTORY_NAME.equals(name);
	}

	@NonNull
	private static String readUtf8(@NonNull InputStream input) throws IOException {
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		byte[] buffer = new byte[4096];
		int read;
		while ((read = input.read(buffer)) != -1) {
			output.write(buffer, 0, read);
		}
		return output.toString(StandardCharsets.UTF_8);
	}

	@NonNull
	private static String encodeWaveform(@NonNull float[] amplitudes) {
		byte[] bytes = new byte[amplitudes.length];
		for (int i = 0; i < amplitudes.length; ++i) {
			float amplitude = Math.max(0f, Math.min(1f, amplitudes[i]));
			bytes[i] = (byte) Math.round(amplitude * 255f);
		}
		return Base64.encodeToString(bytes, Base64.NO_WRAP);
	}

	@NonNull
	private static float[] decodeWaveform(@NonNull String waveformBase64) {
		try {
			byte[] bytes = Base64.decode(waveformBase64, Base64.DEFAULT);
			float[] amplitudes = new float[bytes.length];
			for (int i = 0; i < bytes.length; ++i) {
				amplitudes[i] = (bytes[i] & 0xff) / 255f;
			}
			return amplitudes;
		} catch (IllegalArgumentException ignored) {
			return new float[0];
		}
	}

	@NonNull
	private static AudioSampleWaveform buildWaveformFromDecoded(@NonNull String relativePath,
			@NonNull DecodedAudioSample sample,
			int bucketCount) {
		int frameCount = Math.max(1,
				Math.min(sample.rgbaBuffer().length / 4,
						Math.round(sample.durationSeconds() * sample.sampleRate())));
		float[] amplitudes = new float[bucketCount];
		for (int frame = 0; frame < frameCount; ++frame) {
			int offset = frame * 4;
			float left = Math.abs(sample.rgbaBuffer()[offset]);
			float right = Math.abs(sample.rgbaBuffer()[offset + 1]);
			int bucket = Math.min(bucketCount - 1, frame * bucketCount / frameCount);
			amplitudes[bucket] = Math.max(amplitudes[bucket], Math.max(left, right));
		}
		normalizeWaveform(amplitudes);
		return new AudioSampleWaveform(relativePath,
				Math.max(1, Math.round(sample.durationSeconds() * 1000f)),
				amplitudes);
	}

	private static void normalizeWaveform(@NonNull float[] amplitudes) {
		float max = 0f;
		for (float amplitude : amplitudes) {
			max = Math.max(max, amplitude);
		}
		if (max <= 0f) {
			return;
		}
		for (int i = 0; i < amplitudes.length; ++i) {
			amplitudes[i] /= max;
		}
	}

	@NonNull
	private static TrimRange findTrimRange(@NonNull DecodedAudioSample sample) {
		int frameCount = getFrameCount(sample);
		int start = 0;
		while (start < frameCount && getFramePeak(sample, start) <= TRIM_SILENCE_THRESHOLD) {
			++start;
		}
		if (start >= frameCount) {
			return new TrimRange(0, 1);
		}

		int end = frameCount - 1;
		while (end > start && getFramePeak(sample, end) <= TRIM_SILENCE_THRESHOLD) {
			--end;
		}
		return new TrimRange(start, end + 1);
	}

	private static int getFrameCount(@NonNull DecodedAudioSample sample) {
		return Math.max(1,
				Math.min(sample.rgbaBuffer().length / 4,
						Math.round(sample.durationSeconds() * sample.sampleRate())));
	}

	private static float getFramePeak(@NonNull DecodedAudioSample sample, int frame) {
		int offset = frame * 4;
		float[] rgba = sample.rgbaBuffer();
		return Math.max(Math.abs(rgba[offset]), Math.abs(rgba[offset + 1]));
	}

	private void writeTrimmedWaveFile(@NonNull DocumentFile file,
			@NonNull DecodedAudioSample sample,
			int startFrame,
			int endFrameExclusive) throws IOException {
		int frameCount = Math.max(1, endFrameExclusive - startFrame);
		int dataSize = frameCount * 2 * 2;
		try (OutputStream output = context.getContentResolver().openOutputStream(file.getUri(), "w")) {
			if (output == null) {
				throw new IOException("Failed to open processed sample for writing");
			}

			writeAscii(output, "RIFF");
			writeIntLE(output, 36 + dataSize);
			writeAscii(output, "WAVE");
			writeAscii(output, "fmt ");
			writeIntLE(output, 16);
			writeShortLE(output, (short) 1);
			writeShortLE(output, (short) 2);
			writeIntLE(output, Math.round(sample.sampleRate()));
			writeIntLE(output, Math.round(sample.sampleRate()) * 2 * 2);
			writeShortLE(output, (short) (2 * 2));
			writeShortLE(output, (short) 16);
			writeAscii(output, "data");
			writeIntLE(output, dataSize);

			float[] rgba = sample.rgbaBuffer();
			for (int frame = startFrame; frame < endFrameExclusive; ++frame) {
				int offset = frame * 4;
				writeShortLE(output, floatToPcm16(rgba[offset]));
				writeShortLE(output, floatToPcm16(rgba[offset + 1]));
			}
		}
	}

	private static short floatToPcm16(float value) {
		float clamped = Math.max(-1f, Math.min(1f, value));
		return (short) Math.round(clamped * 32767f);
	}

	private static void writeAscii(@NonNull OutputStream output,
			@NonNull String text) throws IOException {
		output.write(text.getBytes(StandardCharsets.US_ASCII));
	}

	private static void writeIntLE(@NonNull OutputStream output, int value) throws IOException {
		output.write(value & 0xff);
		output.write((value >>> 8) & 0xff);
		output.write((value >>> 16) & 0xff);
		output.write((value >>> 24) & 0xff);
	}

	private static void writeShortLE(@NonNull OutputStream output, short value)
			throws IOException {
		output.write(value & 0xff);
		output.write((value >>> 8) & 0xff);
	}

	private static final class TrimRange {
		private final int startFrame;
		private final int endFrameExclusive;

		private TrimRange(int startFrame, int endFrameExclusive) {
			this.startFrame = startFrame;
			this.endFrameExclusive = endFrameExclusive;
		}
	}

	private static final class CachedAudioSample {
		@NonNull
		private final String relativePath;
		@Nullable
		private final String uniformName;
		@Nullable
		private final String mimeType;
		private final long lastModified;
		private final long size;
		private final int durationMs;
		@Nullable
		private final String waveformBase64;
		@Nullable
		private final String sourceRelativePath;
		private final boolean trimSilence;

		private CachedAudioSample(@NonNull String relativePath,
				@Nullable String uniformName,
				@Nullable String mimeType,
				long lastModified,
				long size,
				int durationMs,
				@Nullable String waveformBase64,
				@Nullable String sourceRelativePath,
				boolean trimSilence) {
			this.relativePath = relativePath;
			this.uniformName = uniformName;
			this.mimeType = mimeType;
			this.lastModified = lastModified;
			this.size = size;
			this.durationMs = durationMs;
			this.waveformBase64 = waveformBase64;
			this.sourceRelativePath = sourceRelativePath;
			this.trimSilence = trimSilence;
		}

		private boolean matches(@NonNull AudioSampleInfo sample) {
			return sample.lastModified() == lastModified &&
					sample.size() == size;
		}

		@Nullable
		private static CachedAudioSample fromJson(@NonNull JSONObject object) {
			String relativePath = object.optString("path");
			if (relativePath == null || relativePath.isEmpty()) {
				return null;
			}
			return new CachedAudioSample(
					relativePath,
					object.optString("uniform", null),
					object.optString("mime", null),
					object.optLong("lastModified", 0L),
					object.optLong("size", 0L),
					object.optInt("durationMs", 0),
					object.optString("waveform", null),
					object.optString("sourcePath", null),
					object.optBoolean("trimSilence", false));
		}

		@NonNull
		private JSONObject toJson() throws JSONException {
			JSONObject object = new JSONObject();
			object.put("path", relativePath);
			object.put("uniform", uniformName);
			object.put("mime", mimeType);
			object.put("lastModified", lastModified);
			object.put("size", size);
			object.put("durationMs", durationMs);
			object.put("waveform", waveformBase64);
			object.put("sourcePath", sourceRelativePath);
			object.put("trimSilence", trimSilence);
			return object;
		}

		@Override
		public boolean equals(Object other) {
			if (this == other) {
				return true;
			}
			if (!(other instanceof CachedAudioSample sample)) {
				return false;
			}
			return lastModified == sample.lastModified &&
					size == sample.size &&
					durationMs == sample.durationMs &&
					Objects.equals(relativePath, sample.relativePath) &&
					Objects.equals(uniformName, sample.uniformName) &&
					Objects.equals(mimeType, sample.mimeType) &&
					Objects.equals(waveformBase64, sample.waveformBase64) &&
					Objects.equals(sourceRelativePath, sample.sourceRelativePath) &&
					trimSilence == sample.trimSilence;
		}

		@Override
		public int hashCode() {
			return Objects.hash(relativePath, uniformName, mimeType,
					lastModified, size, durationMs, waveformBase64,
					sourceRelativePath, trimSilence);
		}
	}
}
