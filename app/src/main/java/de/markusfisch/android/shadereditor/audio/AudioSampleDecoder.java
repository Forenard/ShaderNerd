package de.markusfisch.android.shadereditor.audio;

import android.content.Context;
import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;

final class AudioSampleDecoder {
	private static final int SAMPLE_TEXTURE_WIDTH = 2048;
	private static final int MAX_SAMPLE_FRAMES = SAMPLE_TEXTURE_WIDTH * 1024;

	private AudioSampleDecoder() {
	}

	@NonNull
	static DecodedAudioSample decode(@NonNull Context context,
			@NonNull AudioSampleInfo sample) throws IOException {
		MediaExtractor extractor = new MediaExtractor();
		MediaCodec codec = null;

		try {
			extractor.setDataSource(context, sample.uri(), null);

			int trackIndex = findAudioTrackIndex(extractor);
			if (trackIndex < 0) {
				throw new IOException("No audio track found in " + sample.displayName());
			}

			extractor.selectTrack(trackIndex);
			MediaFormat inputFormat = extractor.getTrackFormat(trackIndex);
			String mimeType = inputFormat.getString(MediaFormat.KEY_MIME);
			if (mimeType == null) {
				throw new IOException("Unsupported audio format for " + sample.displayName());
			}

			codec = MediaCodec.createDecoderByType(mimeType);
			codec.configure(inputFormat, null, null, 0);
			codec.start();

			FloatArrayBuilder left = new FloatArrayBuilder();
			FloatArrayBuilder right = new FloatArrayBuilder();
			MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
			boolean inputDone = false;
			boolean outputDone = false;
			int channelCount = Math.max(1,
					inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT));
			int sampleRate = inputFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)
					? inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
					: AudioShaderPlayer.SAMPLE_RATE;
			int pcmEncoding = AudioFormat.ENCODING_PCM_16BIT;

			while (!outputDone) {
				if (!inputDone) {
					int inputIndex = codec.dequeueInputBuffer(10_000);
					if (inputIndex >= 0) {
						ByteBuffer inputBuffer = codec.getInputBuffer(inputIndex);
						if (inputBuffer == null) {
							throw new IOException("Failed to decode " + sample.displayName());
						}
						int size = extractor.readSampleData(inputBuffer, 0);
						if (size < 0) {
							codec.queueInputBuffer(inputIndex, 0, 0, 0L,
									MediaCodec.BUFFER_FLAG_END_OF_STREAM);
							inputDone = true;
						} else {
							codec.queueInputBuffer(inputIndex, 0, size,
									extractor.getSampleTime(), 0);
							extractor.advance();
						}
					}
				}

				int outputIndex = codec.dequeueOutputBuffer(info, 10_000);
				if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
					continue;
				}
				if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
					MediaFormat outputFormat = codec.getOutputFormat();
					channelCount = Math.max(1,
							outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT));
					if (outputFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
						sampleRate = outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE);
					}
					if (outputFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
						pcmEncoding = outputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING);
					}
					continue;
				}
				if (outputIndex < 0) {
					continue;
				}

				ByteBuffer outputBuffer = codec.getOutputBuffer(outputIndex);
				if (outputBuffer != null && info.size > 0) {
					outputBuffer.position(info.offset);
					outputBuffer.limit(info.offset + info.size);
					appendDecodedPcm(outputBuffer.slice(),
							channelCount,
							pcmEncoding,
							left,
							right,
							sample.displayName());
				}
				codec.releaseOutputBuffer(outputIndex, false);
				if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
					outputDone = true;
				}
			}

			int frameCount = left.size();
			if (frameCount < 1) {
				throw new IOException("Audio sample is empty: " + sample.displayName());
			}

			int height = (frameCount + SAMPLE_TEXTURE_WIDTH - 1) / SAMPLE_TEXTURE_WIDTH;
			float[] rgbaBuffer = new float[SAMPLE_TEXTURE_WIDTH * height * 4];
			float[] leftData = left.toArray();
			float[] rightData = right.toArray();

			for (int i = 0; i < frameCount; ++i) {
				int offset = i * 4;
				rgbaBuffer[offset] = leftData[i];
				rgbaBuffer[offset + 1] = rightData[i];
			}

			return new DecodedAudioSample(sample.uniformName(),
					SAMPLE_TEXTURE_WIDTH,
					height,
					sampleRate,
					frameCount / (float) sampleRate,
					rgbaBuffer);
		} catch (RuntimeException e) {
			throw new IOException("Failed to decode " + sample.displayName(), e);
		} finally {
			extractor.release();
			if (codec != null) {
				try {
					codec.stop();
				} catch (IllegalStateException ignored) {
				}
				codec.release();
			}
		}
	}

	@NonNull
	static AudioSampleWaveform decodeWaveform(@NonNull Context context,
			@NonNull AudioSampleInfo sample,
			int bucketCount) throws IOException {
		MediaExtractor extractor = new MediaExtractor();
		MediaCodec codec = null;

		try {
			extractor.setDataSource(context, sample.uri(), null);

			int trackIndex = findAudioTrackIndex(extractor);
			if (trackIndex < 0) {
				throw new IOException("No audio track found in " + sample.displayName());
			}

			extractor.selectTrack(trackIndex);
			MediaFormat inputFormat = extractor.getTrackFormat(trackIndex);
			String mimeType = inputFormat.getString(MediaFormat.KEY_MIME);
			if (mimeType == null) {
				throw new IOException("Unsupported audio format for " + sample.displayName());
			}

			long durationUs = inputFormat.containsKey(MediaFormat.KEY_DURATION)
					? inputFormat.getLong(MediaFormat.KEY_DURATION)
					: 0L;
			int durationMs = durationUs > 0L
					? Math.max(1, (int) Math.round(durationUs / 1000d))
					: 0;

			codec = MediaCodec.createDecoderByType(mimeType);
			codec.configure(inputFormat, null, null, 0);
			codec.start();

			MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
			boolean inputDone = false;
			boolean outputDone = false;
			int channelCount = Math.max(1,
					inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT));
			int sampleRate = inputFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)
					? inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
					: AudioShaderPlayer.SAMPLE_RATE;
			int pcmEncoding = AudioFormat.ENCODING_PCM_16BIT;
			WaveformAccumulator accumulator = new WaveformAccumulator(
					sample.relativePath(),
					bucketCount,
					durationMs,
					durationUs > 0L
							? Math.max(1L,
									Math.round(durationUs * sampleRate / 1_000_000d))
							: -1L);

			while (!outputDone) {
				if (Thread.currentThread().isInterrupted()) {
					throw new IOException("Waveform decode interrupted");
				}

				if (!inputDone) {
					int inputIndex = codec.dequeueInputBuffer(10_000);
					if (inputIndex >= 0) {
						ByteBuffer inputBuffer = codec.getInputBuffer(inputIndex);
						if (inputBuffer == null) {
							throw new IOException("Failed to decode " + sample.displayName());
						}
						int size = extractor.readSampleData(inputBuffer, 0);
						if (size < 0) {
							codec.queueInputBuffer(inputIndex, 0, 0, 0L,
									MediaCodec.BUFFER_FLAG_END_OF_STREAM);
							inputDone = true;
						} else {
							codec.queueInputBuffer(inputIndex, 0, size,
									extractor.getSampleTime(), 0);
							extractor.advance();
						}
					}
				}

				int outputIndex = codec.dequeueOutputBuffer(info, 10_000);
				if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
					continue;
				}
				if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
					MediaFormat outputFormat = codec.getOutputFormat();
					channelCount = Math.max(1,
							outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT));
					if (outputFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
						sampleRate = outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE);
						if (durationUs > 0L) {
							accumulator.setTotalFramesEstimate(Math.max(1L,
									Math.round(durationUs * sampleRate / 1_000_000d)));
						}
					}
					if (outputFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
						pcmEncoding = outputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING);
					}
					continue;
				}
				if (outputIndex < 0) {
					continue;
				}

				ByteBuffer outputBuffer = codec.getOutputBuffer(outputIndex);
				if (outputBuffer != null && info.size > 0) {
					outputBuffer.position(info.offset);
					outputBuffer.limit(info.offset + info.size);
					appendWaveformPcm(outputBuffer.slice(),
							channelCount,
							pcmEncoding,
							accumulator,
							sample.displayName());
				}
				codec.releaseOutputBuffer(outputIndex, false);
				if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
					outputDone = true;
				}
			}

			return accumulator.build();
		} catch (RuntimeException e) {
			throw new IOException("Failed to decode waveform for " + sample.displayName(), e);
		} finally {
			extractor.release();
			if (codec != null) {
				try {
					codec.stop();
				} catch (IllegalStateException ignored) {
				}
				codec.release();
			}
		}
	}

	private static void appendDecodedPcm(@NonNull ByteBuffer buffer,
			int channelCount,
			int pcmEncoding,
			@NonNull FloatArrayBuilder left,
			@NonNull FloatArrayBuilder right,
			@NonNull String displayName) throws IOException {
		if (pcmEncoding == AudioFormat.ENCODING_PCM_FLOAT) {
			FloatBuffer floatBuffer = buffer.order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();
			appendFloatFrames(floatBuffer, channelCount, left, right, displayName);
			return;
		}
		if (pcmEncoding == AudioFormat.ENCODING_PCM_16BIT ||
				pcmEncoding == AudioFormat.ENCODING_INVALID) {
			ShortBuffer shortBuffer = buffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();
			appendShortFrames(shortBuffer, channelCount, left, right, displayName);
			return;
		}
		throw new IOException("Unsupported PCM encoding for " + displayName);
	}

	private static void appendWaveformPcm(@NonNull ByteBuffer buffer,
			int channelCount,
			int pcmEncoding,
			@NonNull WaveformAccumulator accumulator,
			@NonNull String displayName) throws IOException {
		if (pcmEncoding == AudioFormat.ENCODING_PCM_FLOAT) {
			FloatBuffer floatBuffer = buffer.order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();
			appendWaveformFloatFrames(floatBuffer, channelCount, accumulator, displayName);
			return;
		}
		if (pcmEncoding == AudioFormat.ENCODING_PCM_16BIT ||
				pcmEncoding == AudioFormat.ENCODING_INVALID) {
			ShortBuffer shortBuffer = buffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();
			appendWaveformShortFrames(shortBuffer, channelCount, accumulator, displayName);
			return;
		}
		throw new IOException("Unsupported PCM encoding for " + displayName);
	}

	private static void appendShortFrames(@NonNull ShortBuffer buffer,
			int channelCount,
			@NonNull FloatArrayBuilder left,
			@NonNull FloatArrayBuilder right,
			@NonNull String displayName) throws IOException {
		int sampleCount = buffer.remaining();
		int frameCount = sampleCount / channelCount;
		ensureCapacity(left.size() + frameCount, displayName);
		for (int frame = 0; frame < frameCount; ++frame) {
			float leftValue = 0f;
			float rightValue = 0f;
			for (int channel = 0; channel < channelCount; ++channel) {
				float value = buffer.get() / 32768f;
				if (channel == 0) {
					leftValue = value;
					rightValue = value;
				} else if (channel == 1) {
					rightValue = value;
				}
			}
			left.append(leftValue);
			right.append(rightValue);
		}
	}

	private static void appendFloatFrames(@NonNull FloatBuffer buffer,
			int channelCount,
			@NonNull FloatArrayBuilder left,
			@NonNull FloatArrayBuilder right,
			@NonNull String displayName) throws IOException {
		int sampleCount = buffer.remaining();
		int frameCount = sampleCount / channelCount;
		ensureCapacity(left.size() + frameCount, displayName);
		for (int frame = 0; frame < frameCount; ++frame) {
			float leftValue = 0f;
			float rightValue = 0f;
			for (int channel = 0; channel < channelCount; ++channel) {
				float value = buffer.get();
				if (channel == 0) {
					leftValue = value;
					rightValue = value;
				} else if (channel == 1) {
					rightValue = value;
				}
			}
			left.append(leftValue);
			right.append(rightValue);
		}
	}

	private static void appendWaveformShortFrames(@NonNull ShortBuffer buffer,
			int channelCount,
			@NonNull WaveformAccumulator accumulator,
			@NonNull String displayName) throws IOException {
		int sampleCount = buffer.remaining();
		int frameCount = sampleCount / channelCount;
		ensureWaveformCapacity(accumulator.frameCount() + frameCount, displayName);
		for (int frame = 0; frame < frameCount; ++frame) {
			float leftValue = 0f;
			float rightValue = 0f;
			for (int channel = 0; channel < channelCount; ++channel) {
				float value = buffer.get() / 32768f;
				if (channel == 0) {
					leftValue = value;
					rightValue = value;
				} else if (channel == 1) {
					rightValue = value;
				}
			}
			accumulator.append(leftValue, rightValue);
		}
	}

	private static void appendWaveformFloatFrames(@NonNull FloatBuffer buffer,
			int channelCount,
			@NonNull WaveformAccumulator accumulator,
			@NonNull String displayName) throws IOException {
		int sampleCount = buffer.remaining();
		int frameCount = sampleCount / channelCount;
		ensureWaveformCapacity(accumulator.frameCount() + frameCount, displayName);
		for (int frame = 0; frame < frameCount; ++frame) {
			float leftValue = 0f;
			float rightValue = 0f;
			for (int channel = 0; channel < channelCount; ++channel) {
				float value = buffer.get();
				if (channel == 0) {
					leftValue = value;
					rightValue = value;
				} else if (channel == 1) {
					rightValue = value;
				}
			}
			accumulator.append(leftValue, rightValue);
		}
	}

	private static void ensureCapacity(int frameCount,
			@NonNull String displayName) throws IOException {
		if (frameCount > MAX_SAMPLE_FRAMES) {
			throw new IOException("Audio sample is too long to upload: " + displayName);
		}
	}

	private static void ensureWaveformCapacity(long frameCount,
			@NonNull String displayName) throws IOException {
		if (frameCount > MAX_SAMPLE_FRAMES * 8) {
			throw new IOException("Audio sample is too long to analyze: " + displayName);
		}
	}

	private static int findAudioTrackIndex(@NonNull MediaExtractor extractor) {
		for (int i = 0; i < extractor.getTrackCount(); ++i) {
			MediaFormat format = extractor.getTrackFormat(i);
			String mimeType = format.getString(MediaFormat.KEY_MIME);
			if (mimeType != null && mimeType.startsWith("audio/")) {
				return i;
			}
		}
		return -1;
	}

	private static final class FloatArrayBuilder {
		private float[] values = new float[4096];
		private int size;

		int size() {
			return size;
		}

		void append(float value) {
			if (size >= values.length) {
				float[] resized = new float[values.length * 2];
				System.arraycopy(values, 0, resized, 0, size);
				values = resized;
			}
			values[size++] = value;
		}

		@NonNull
		float[] toArray() {
			float[] out = new float[size];
			System.arraycopy(values, 0, out, 0, size);
			return out;
		}
	}

	private static final class WaveformAccumulator {
		@NonNull
		private final String relativePath;
		@NonNull
		private final float[] buckets;
		private final int durationMs;
		@Nullable
		private FloatArrayBuilder unknownFrames;
		private long totalFramesEstimate;
		private long frameCount;

		private WaveformAccumulator(@NonNull String relativePath,
				int bucketCount,
				int durationMs,
				long totalFramesEstimate) {
			this.relativePath = relativePath;
			buckets = new float[bucketCount];
			this.durationMs = durationMs;
			this.totalFramesEstimate = totalFramesEstimate;
			if (totalFramesEstimate <= 0L) {
				unknownFrames = new FloatArrayBuilder();
			}
		}

		private void setTotalFramesEstimate(long totalFramesEstimate) {
			if (this.totalFramesEstimate > 0L || totalFramesEstimate <= 0L) {
				return;
			}
			this.totalFramesEstimate = totalFramesEstimate;
		}

		private long frameCount() {
			return frameCount;
		}

		private void append(float left, float right) {
			float amplitude = Math.max(Math.abs(left), Math.abs(right));
			if (totalFramesEstimate > 0L) {
				int bucket = (int) Math.min(buckets.length - 1,
						frameCount * buckets.length / totalFramesEstimate);
				buckets[bucket] = Math.max(buckets[bucket], amplitude);
			} else if (unknownFrames != null) {
				unknownFrames.append(amplitude);
			}
			++frameCount;
		}

		@NonNull
		private AudioSampleWaveform build() {
			if (unknownFrames != null) {
				float[] frames = unknownFrames.toArray();
				if (frames.length > 0) {
					for (int i = 0; i < frames.length; ++i) {
						int bucket = Math.min(buckets.length - 1,
								i * buckets.length / frames.length);
						buckets[bucket] = Math.max(buckets[bucket], frames[i]);
					}
				}
			}

			float max = 0f;
			for (float amplitude : buckets) {
				max = Math.max(max, amplitude);
			}
			if (max > 0f) {
				for (int i = 0; i < buckets.length; ++i) {
					buckets[i] /= max;
				}
			}

			return new AudioSampleWaveform(relativePath,
					durationMs > 0 ? durationMs : Math.max(1,
							(int) Math.round(frameCount / (float) AudioShaderPlayer.SAMPLE_RATE * 1000f)),
					buckets);
		}
	}
}
