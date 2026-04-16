package de.markusfisch.android.shadereditor.audio;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES30;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import de.markusfisch.android.shadereditor.opengl.ShaderError;

/**
 * Renders a GLSL fragment shader as PCM stereo audio on a dedicated audio thread.
 * Uses an offscreen EGL pbuffer context so it runs independently of the visual
 * GLSurfaceView, and an {@link AudioTrack} in {@code WRITE_BLOCKING} mode for
 * natural back-pressure (no ring buffer needed).
 *
 * Default state is silent: nothing is started until {@link #start()} is called
 * with a non-empty audio shader source.
 */
public final class AudioShaderPlayer {
	private static final String TAG = "AudioShaderPlayer";
	private static final int USER_SHADER_LINE_OFFSET = countLines(AudioShaderChunks.PRE);
	private static final Pattern INFO_LOG_PATTERN = Pattern.compile(
			"^.*(\\d+):(\\d+):\\s+(.*)$");
	private static final Pattern REQUIRED_SAMPLE_PATTERN = Pattern.compile(
			"uniform\\s+sampler2D\\s+(sample_[A-Za-z0-9_]+)\\s*;");

	public static final int SAMPLE_RATE = 48000;
	public static final int BLOCK_SIZE = 128;
	public static final int BLOCKS_PER_RENDER = 16;
	public static final int FRAMES_PER_RENDER = BLOCK_SIZE * BLOCKS_PER_RENDER; // 2048
	public static final int FADER_COUNT = 8;
	private static final int MAX_SAMPLE_TEXTURES = 16;
	private static final float NS_PER_SECOND = 1_000_000_000f;

	@Nullable
	private Thread audioThread;
	private volatile boolean running;
	private volatile boolean playing;
	private volatile boolean rewindRequested;

	@Nullable
	private volatile String pendingSource;
	@Nullable
	private volatile String queuedSource;

	private final Object pauseLock = new Object();
	private final Object faderLock = new Object();
	private final Object inputLock = new Object();

	private final AudioSampleRepository sampleRepository;
	private final BeatClock beatClock = new BeatClock(140f);
	private final FaderState[] faders = new FaderState[FADER_COUNT];
	private final ArrayList<RequiredSampleBinding> requiredSamples = new ArrayList<>();
	private final float[] resolution = new float[]{1f, 1f};
	private final float[] touch = new float[]{0f, 0f};
	private volatile float playbackTimeSeconds = Float.NaN;
	private volatile long playbackStartTime;

	// GL resources (audio thread only)
	private EGLDisplay eglDisplay = EGL14.EGL_NO_DISPLAY;
	private EGLSurface eglSurface = EGL14.EGL_NO_SURFACE;
	private EGLContext eglContext = EGL14.EGL_NO_CONTEXT;
	private int program;
	private int quadBuffer;
	private int framebuffer;
	private int texture;

	private FloatBuffer pixelBuffer;
	private float[] interleaved;
	private boolean audioTrackPlaying;

	@Nullable
	private AudioTrack audioTrack;
	@Nullable
	private volatile OnInfoLogListener onInfoLogListener;
	@Nullable
	private volatile Runnable onCueAppliedListener;

	@FunctionalInterface
	public interface OnInfoLogListener {
		void onInfoLog(@NonNull List<ShaderError> infoLog);
	}

	public AudioShaderPlayer(@NonNull Context context) {
		sampleRepository = AudioSampleRepository.getInstance(context);
		for (int i = 0; i < FADER_COUNT; i++) {
			faders[i] = new FaderState(0f);
		}
	}

	// ----- public API (any thread) ----------------------------------------------------------------

	public synchronized void start() {
		if (audioThread != null) return;
		running = true;
		audioThread = new Thread(this::audioLoop, "AudioShader");
		audioThread.start();
	}

	public synchronized void stop() {
		if (audioThread == null) return;
		running = false;
		playing = false;
		queuedSource = null;
		clearPlaybackClock();
		synchronized (pauseLock) {
			pauseLock.notifyAll();
		}
		try {
			audioThread.join(1000);
		} catch (InterruptedException ignored) {
			Thread.currentThread().interrupt();
		}
		audioThread = null;
	}

	public void playFromStart() {
		playbackTimeSeconds = 0f;
		playbackStartTime = System.nanoTime();
		rewindRequested = true;
		synchronized (pauseLock) {
			playing = true;
			pauseLock.notifyAll();
		}
	}

	public void pause() {
		if (!Float.isNaN(playbackTimeSeconds)) {
			playbackTimeSeconds = getPlaybackTimeSeconds();
		}
		playing = false;
	}

	public void resetTime() {
		playbackTimeSeconds = 0f;
		playbackStartTime = System.nanoTime();
		rewindRequested = true;
		if (!playing) {
			beatClock.rewind();
		}
		synchronized (pauseLock) {
			pauseLock.notifyAll();
		}
	}

	public boolean isPlaying() {
		return playing;
	}

	public float getPlaybackTimeSeconds() {
		float base = playbackTimeSeconds;
		if (Float.isNaN(base)) {
			return Float.NaN;
		}
		if (!playing) {
			return base;
		}
		return base + (System.nanoTime() - playbackStartTime) / NS_PER_SECOND;
	}

	public void setSource(@Nullable String source) {
		pendingSource = source == null ? "" : source;
		queuedSource = null;
		synchronized (pauseLock) {
			pauseLock.notifyAll();
		}
	}

	public void queueSource(@Nullable String source) {
		queuedSource = source == null ? "" : source;
		synchronized (pauseLock) {
			pauseLock.notifyAll();
		}
	}

	public void setBpm(float bpm) {
		beatClock.setBpm(bpm);
	}

	public float getBpm() {
		return beatClock.bpm();
	}

	public BeatClock getBeatClock() {
		return beatClock;
	}

	public void clearPlaybackClock() {
		playbackTimeSeconds = Float.NaN;
		playbackStartTime = 0L;
	}

	public void setFader(int index, float value) {
		if (index < 0 || index >= FADER_COUNT) return;
		synchronized (faderLock) {
			faders[index].value = value;
			if (!playing) {
				faders[index].snapToValue();
			}
		}
	}

	public void syncFadersToValues() {
		synchronized (faderLock) {
			for (FaderState fader : faders) {
				fader.snapToValue();
			}
		}
	}

	public void setResolution(float width, float height) {
		synchronized (inputLock) {
			resolution[0] = Math.max(1f, width);
			resolution[1] = Math.max(1f, height);
		}
	}

	public void setTouch(float x, float y) {
		synchronized (inputLock) {
			touch[0] = x;
			touch[1] = y;
		}
	}

	public void copyFaderUniforms(@NonNull float[] target) {
		synchronized (faderLock) {
			for (int i = 0; i < FADER_COUNT; ++i) {
				int offset = i * 4;
				FaderState fader = faders[i];
				target[offset] = fader.y0;
				target[offset + 1] = fader.y1;
				target[offset + 2] = fader.y2;
				target[offset + 3] = fader.y3;
			}
		}
	}

	public synchronized boolean isStarted() {
		return audioThread != null;
	}

	public void clearQueuedSource() {
		queuedSource = null;
	}

	public void setOnInfoLogListener(@Nullable OnInfoLogListener listener) {
		onInfoLogListener = listener;
	}

	public void setOnCueAppliedListener(@Nullable Runnable listener) {
		onCueAppliedListener = listener;
	}

	// ----- audio thread ---------------------------------------------------------------------------

	private void audioLoop() {
		try {
			if (!initEgl()) {
				Log.e(TAG, "failed to init EGL");
				submitInfoLog(List.of(
						ShaderError.createGeneral("Failed to initialize audio renderer")));
				return;
			}
			initGl();
			initAudioTrack();

			while (running) {
				String source = pendingSource;
					if (source != null) {
						pendingSource = null;
						try {
							recompile(source);
						} catch (Throwable t) {
							handleAudioRuntimeFailure("Failed to update audio shader", t);
						}
					}

				synchronized (pauseLock) {
					while (running && !playing && pendingSource == null) {
						pauseAudioTrackIfNeeded();
						try {
							pauseLock.wait();
						} catch (InterruptedException ignored) {
							Thread.currentThread().interrupt();
						}
					}
				}
				if (!running) break;
				if (!playing) {
					continue;
				}
				playAudioTrackIfNeeded();

				if (rewindRequested) {
					beatClock.rewind();
					if (audioTrack != null) audioTrack.flush();
					rewindRequested = false;
				}

				if (program == 0) {
					// no valid shader → output silence and yield
						java.util.Arrays.fill(interleaved, 0f);
						try {
							if (audioTrack != null) {
								audioTrack.write(interleaved, 0, interleaved.length,
										AudioTrack.WRITE_BLOCKING);
							}
							if (beatClock.advanceAndCheckBarBoundary(
									(double) FRAMES_PER_RENDER / SAMPLE_RATE)) {
								applyQueuedSourceIfNeeded();
							}
						} catch (Throwable t) {
							handleAudioRuntimeFailure("Failed to render silent audio block", t);
						}
						continue;
					}

					try {
						renderBlock();
						readback();
						if (audioTrack != null) {
							audioTrack.write(interleaved, 0, interleaved.length,
									AudioTrack.WRITE_BLOCKING);
						}
						if (beatClock.advanceAndCheckBarBoundary(
								(double) FRAMES_PER_RENDER / SAMPLE_RATE)) {
							applyQueuedSourceIfNeeded();
						}
					} catch (Throwable t) {
						handleAudioRuntimeFailure("Failed to render audio block", t);
					}
			}
		} catch (Throwable t) {
			Log.e(TAG, "audio loop crashed", t);
			submitInfoLog(List.of(ShaderError.createGeneral(
					t.getMessage() != null
							? t.getMessage()
							: "Audio engine crashed")));
		} finally {
			tearDownAudioTrack();
			tearDownGl();
			tearDownEgl();
		}
	}

	private void initAudioTrack() {
		int minBuf = AudioTrack.getMinBufferSize(
				SAMPLE_RATE,
				AudioFormat.CHANNEL_OUT_STEREO,
				AudioFormat.ENCODING_PCM_FLOAT);
		int desired = FRAMES_PER_RENDER * 2 * 4 * 2; // 2 channels, 4 bytes/float, 2 blocks
		int bufBytes = Math.max(minBuf, desired);

		audioTrack = new AudioTrack.Builder()
				.setAudioAttributes(new AudioAttributes.Builder()
						.setUsage(AudioAttributes.USAGE_MEDIA)
						.setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
						.build())
				.setAudioFormat(new AudioFormat.Builder()
						.setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
						.setSampleRate(SAMPLE_RATE)
						.setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
				.build())
				.setBufferSizeInBytes(bufBytes)
				.setTransferMode(AudioTrack.MODE_STREAM)
				.build();
		audioTrackPlaying = false;
	}

	private void tearDownAudioTrack() {
		if (audioTrack != null) {
			try {
				audioTrack.pause();
				audioTrack.flush();
				audioTrack.stop();
			} catch (IllegalStateException ignored) {
			}
			audioTrack.release();
			audioTrack = null;
		}
		audioTrackPlaying = false;
	}

	private void playAudioTrackIfNeeded() {
		if (audioTrack == null || audioTrackPlaying) {
			return;
		}
		audioTrack.play();
		audioTrackPlaying = true;
	}

	private void pauseAudioTrackIfNeeded() {
		if (audioTrack == null || !audioTrackPlaying) {
			return;
		}
		audioTrack.pause();
		audioTrack.flush();
		audioTrackPlaying = false;
	}

	private boolean initEgl() {
		eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
		if (eglDisplay == EGL14.EGL_NO_DISPLAY) return false;

		int[] version = new int[2];
		if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) return false;

		int[] cfgAttr = {
				EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
				EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
				EGL14.EGL_RED_SIZE, 8,
				EGL14.EGL_GREEN_SIZE, 8,
				EGL14.EGL_BLUE_SIZE, 8,
				EGL14.EGL_NONE
		};
		EGLConfig[] configs = new EGLConfig[1];
		int[] num = new int[1];
		if (!EGL14.eglChooseConfig(eglDisplay, cfgAttr, 0, configs, 0, 1, num, 0)
				|| num[0] == 0) {
			return false;
		}

		int[] ctxAttr = {
				EGL14.EGL_CONTEXT_CLIENT_VERSION, 3,
				EGL14.EGL_NONE
		};
		eglContext = EGL14.eglCreateContext(eglDisplay, configs[0],
				EGL14.EGL_NO_CONTEXT, ctxAttr, 0);
		if (eglContext == EGL14.EGL_NO_CONTEXT) return false;

		int[] surfAttr = {
				EGL14.EGL_WIDTH, 1,
				EGL14.EGL_HEIGHT, 1,
				EGL14.EGL_NONE
		};
		eglSurface = EGL14.eglCreatePbufferSurface(eglDisplay, configs[0], surfAttr, 0);
		if (eglSurface == EGL14.EGL_NO_SURFACE) return false;

		return EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext);
	}

	private void initGl() {
		// quad VBO
		int[] buf = new int[1];
		GLES30.glGenBuffers(1, buf, 0);
		quadBuffer = buf[0];
		GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, quadBuffer);
		float[] quad = {-1, -1, 1, -1, -1, 1, 1, 1};
		ByteBuffer bb = ByteBuffer.allocateDirect(quad.length * 4)
				.order(ByteOrder.nativeOrder());
		bb.asFloatBuffer().put(quad).position(0);
		GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, quad.length * 4, bb, GLES30.GL_STATIC_DRAW);
		GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0);

		// FBO + RGBA32F texture (FRAMES_PER_RENDER × 1)
		int[] tex = new int[1];
		GLES30.glGenTextures(1, tex, 0);
		texture = tex[0];
		GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture);
		GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA32F,
				FRAMES_PER_RENDER, 1, 0, GLES30.GL_RGBA, GLES30.GL_FLOAT, null);
		GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER,
				GLES30.GL_NEAREST);
		GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER,
				GLES30.GL_NEAREST);
		GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S,
				GLES30.GL_CLAMP_TO_EDGE);
		GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T,
				GLES30.GL_CLAMP_TO_EDGE);
		GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0);

		int[] fbo = new int[1];
		GLES30.glGenFramebuffers(1, fbo, 0);
		framebuffer = fbo[0];
		GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer);
		GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
				GLES30.GL_TEXTURE_2D, texture, 0);
		int status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER);
		GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
		if (status != GLES30.GL_FRAMEBUFFER_COMPLETE) {
			Log.e(TAG, "framebuffer incomplete: 0x" + Integer.toHexString(status));
			submitInfoLog(List.of(ShaderError.createGeneral(
					"Audio framebuffer incomplete: 0x" + Integer.toHexString(status))));
		}

		pixelBuffer = ByteBuffer.allocateDirect(FRAMES_PER_RENDER * 4 * 4)
				.order(ByteOrder.nativeOrder())
				.asFloatBuffer();
		interleaved = new float[FRAMES_PER_RENDER * 2];
	}

	private void tearDownGl() {
		deleteSampleTextures();
		if (program != 0) {
			GLES30.glDeleteProgram(program);
			program = 0;
		}
		if (quadBuffer != 0) {
			GLES30.glDeleteBuffers(1, new int[]{quadBuffer}, 0);
			quadBuffer = 0;
		}
		if (framebuffer != 0) {
			GLES30.glDeleteFramebuffers(1, new int[]{framebuffer}, 0);
			framebuffer = 0;
		}
		if (texture != 0) {
			GLES30.glDeleteTextures(1, new int[]{texture}, 0);
			texture = 0;
		}
	}

	private void tearDownEgl() {
		if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
			EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE,
					EGL14.EGL_NO_CONTEXT);
			if (eglSurface != EGL14.EGL_NO_SURFACE) {
				EGL14.eglDestroySurface(eglDisplay, eglSurface);
				eglSurface = EGL14.EGL_NO_SURFACE;
			}
			if (eglContext != EGL14.EGL_NO_CONTEXT) {
				EGL14.eglDestroyContext(eglDisplay, eglContext);
				eglContext = EGL14.EGL_NO_CONTEXT;
			}
			EGL14.eglTerminate(eglDisplay);
			eglDisplay = EGL14.EGL_NO_DISPLAY;
		}
	}

	private void recompile(String userCode) {
		if (program != 0) {
			GLES30.glDeleteProgram(program);
			program = 0;
		}
		deleteSampleTextures();
		requiredSamples.clear();
		if (userCode == null || userCode.trim().isEmpty()) {
			submitInfoLog(Collections.emptyList());
			return;
		}

		int vs = compile(GLES30.GL_VERTEX_SHADER, AudioShaderChunks.VERTEX, false);
		if (vs == 0) {
			return;
		}
		int fs = compile(GLES30.GL_FRAGMENT_SHADER, AudioShaderChunks.wrap(userCode), true);
		if (fs == 0) {
			GLES30.glDeleteShader(vs);
			return;
		}

		int p = GLES30.glCreateProgram();
		GLES30.glAttachShader(p, vs);
		GLES30.glAttachShader(p, fs);
		GLES30.glLinkProgram(p);
		GLES30.glDeleteShader(vs);
		GLES30.glDeleteShader(fs);

		int[] linked = new int[1];
		GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, linked, 0);
		if (linked[0] != GLES30.GL_TRUE) {
			String infoLog = GLES30.glGetProgramInfoLog(p);
			Log.e(TAG, "link failed: " + infoLog);
			submitInfoLog(parseGeneralInfoLog(infoLog,
					"Failed to link audio shader"));
			GLES30.glDeleteProgram(p);
			return;
		}

		List<RequiredSampleBinding> nextRequiredSamples;
		try {
			nextRequiredSamples = createRequiredSampleBindings(userCode);
		} catch (IOException e) {
			GLES30.glDeleteProgram(p);
			submitInfoLog(List.of(ShaderError.createGeneral(
					e.getMessage() != null
							? e.getMessage()
							: "Failed to load audio sample")));
			return;
		}
		program = p;
		requiredSamples.addAll(nextRequiredSamples);
		submitInfoLog(Collections.emptyList());
	}

	private int compile(int type, String src, boolean userShader) {
		int s = GLES30.glCreateShader(type);
		GLES30.glShaderSource(s, src);
		GLES30.glCompileShader(s);
		int[] ok = new int[1];
		GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0);
		if (ok[0] == 0) {
			String infoLog = GLES30.glGetShaderInfoLog(s);
			Log.e(TAG, "compile failed: " + infoLog);
			submitInfoLog(userShader
					? parseUserShaderInfoLog(infoLog)
					: parseGeneralInfoLog(infoLog,
							"Failed to compile internal audio shader"));
			GLES30.glDeleteShader(s);
			return 0;
		}
		return s;
	}

	private final float[] tmpTimeHead = new float[4];
	private final float[] tmpLengths = new float[4];

	private void renderBlock() {
		GLES30.glUseProgram(program);

		// uniforms
		setUniform1f("bpm", beatClock.bpm());
		setUniform1f("sampleRate", SAMPLE_RATE);
		setUniform1f("_deltaSample", 1f / SAMPLE_RATE);
		setUniform1f("_framesPerRender", FRAMES_PER_RENDER);
		synchronized (inputLock) {
			setUniform2f("resolution", resolution);
			setUniform2f("touch", touch);
		}

		beatClock.snapshot(tmpTimeHead, tmpLengths);
		setUniform4f("_timeHead", tmpTimeHead);
		setUniform4f("timeLength", tmpLengths);

		synchronized (faderLock) {
			for (int i = 0; i < FADER_COUNT; i++) {
				FaderState fader = faders[i];
				fader.update();
				int loc = GLES30.glGetUniformLocation(program, "fader" + i);
				if (loc >= 0) {
					GLES30.glUniform4f(loc,
							fader.y0,
							fader.y1,
							fader.y2,
							fader.y3);
				}
			}
		}
		int sampleTextureCount = bindSampleTextures();

		int posLoc = GLES30.glGetAttribLocation(program, "position");
		GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, quadBuffer);
		GLES30.glEnableVertexAttribArray(posLoc);
		GLES30.glVertexAttribPointer(posLoc, 2, GLES30.GL_FLOAT, false, 0, 0);

		GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer);
		GLES30.glViewport(0, 0, FRAMES_PER_RENDER, 1);
		GLES30.glClearColor(0f, 0f, 0f, 1f);
		GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT);
		GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4);

		GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
		unbindSampleTextures(sampleTextureCount);
		GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0);
		GLES30.glUseProgram(0);
	}

	private void setUniform1f(String name, float v) {
		int loc = GLES30.glGetUniformLocation(program, name);
		if (loc >= 0) GLES30.glUniform1f(loc, v);
	}

	private void setUniform4f(String name, float[] v) {
		int loc = GLES30.glGetUniformLocation(program, name);
		if (loc >= 0) GLES30.glUniform4f(loc, v[0], v[1], v[2], v[3]);
	}

	private void setUniform2f(String name, float[] v) {
		int loc = GLES30.glGetUniformLocation(program, name);
		if (loc >= 0) GLES30.glUniform2f(loc, v[0], v[1]);
	}

	private int bindSampleTextures() {
		int textureUnit = 0;
		for (RequiredSampleBinding sample : requiredSamples) {
			int metaLoc = GLES30.glGetUniformLocation(program,
					sample.uniformName + "_meta");
			if (metaLoc >= 0) {
				GLES30.glUniform4f(metaLoc,
						sample.width,
						sample.height,
						sample.sampleRate,
						sample.durationSeconds);
			}

			int samplerLoc = GLES30.glGetUniformLocation(program, sample.uniformName);
			if (samplerLoc < 0 || textureUnit >= MAX_SAMPLE_TEXTURES) {
				continue;
			}

			GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + textureUnit);
			GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, sample.textureId);
			GLES30.glUniform1i(samplerLoc, textureUnit);
			++textureUnit;
		}
		GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
		return textureUnit;
	}

	private void unbindSampleTextures(int textureCount) {
		for (int i = 0; i < textureCount; ++i) {
			GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + i);
			GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0);
		}
		GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
	}

	private void readback() {
		GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer);
		pixelBuffer.position(0);
		GLES30.glReadPixels(0, 0, FRAMES_PER_RENDER, 1, GLES30.GL_RGBA, GLES30.GL_FLOAT,
				pixelBuffer);
		GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);

		pixelBuffer.position(0);
		for (int i = 0; i < FRAMES_PER_RENDER; i++) {
			int base = i * 4;
			interleaved[i * 2] = pixelBuffer.get(base);     // R = left
			interleaved[i * 2 + 1] = pixelBuffer.get(base + 1); // G = right
		}
	}

	/**
	 * Lagrange-history wrapped fader value (port of WavenerdDeckParam).
	 */
	private static final class FaderState {
		volatile float value;
		float y0, y1, y2, y3;

		FaderState(float v) {
			value = v;
			y0 = y1 = y2 = y3 = v;
		}

		void update() {
			y3 = y2;
			y2 = y1;
			y1 = y0;
			y0 = value;
		}

		void snapToValue() {
			y0 = value;
			y1 = value;
			y2 = value;
			y3 = value;
		}
	}

	@NonNull
	private List<RequiredSampleBinding> createRequiredSampleBindings(
			@NonNull String userCode) throws IOException {
		List<String> requiredNames = extractRequiredSampleNames(userCode);
		if (requiredNames.isEmpty()) {
			return Collections.emptyList();
		}
		if (sampleRepository.getFolderUri() == null) {
			throw new IOException("Select an audio sample folder before using sample_* uniforms");
		}
		if (requiredNames.size() > MAX_SAMPLE_TEXTURES) {
			throw new IOException("Too many audio samples in one shader");
		}

		ArrayList<RequiredSampleBinding> bindings = new ArrayList<>(requiredNames.size());
		for (String uniformName : requiredNames) {
			AudioSampleInfo sample = sampleRepository.findSample(uniformName);
			if (sample == null) {
				throw new IOException("Audio sample not found in selected folder: " +
						uniformName);
			}
			bindings.add(uploadSampleTexture(sampleRepository.getDecodedSample(uniformName)));
		}
		return bindings;
	}

	@NonNull
	private RequiredSampleBinding uploadSampleTexture(
			@NonNull DecodedAudioSample sample) {
		int[] textureIds = new int[1];
		GLES30.glGenTextures(1, textureIds, 0);
		GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureIds[0]);

		FloatBuffer buffer = ByteBuffer.allocateDirect(sample.rgbaBuffer().length * 4)
				.order(ByteOrder.nativeOrder())
				.asFloatBuffer();
		buffer.put(sample.rgbaBuffer());
		buffer.position(0);

		GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA32F,
				sample.width(), sample.height(), 0,
				GLES30.GL_RGBA, GLES30.GL_FLOAT, buffer);
		GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,
				GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST);
		GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,
				GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST);
		GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,
				GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE);
		GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,
				GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE);
		GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0);

		return new RequiredSampleBinding(sample.uniformName(),
				textureIds[0],
				sample.width(),
				sample.height(),
				sample.sampleRate(),
				sample.durationSeconds());
	}

	private void deleteSampleTextures() {
		deleteSampleTextures(requiredSamples);
		requiredSamples.clear();
	}

	private void deleteSampleTextures(@NonNull List<RequiredSampleBinding> samples) {
		for (RequiredSampleBinding sample : samples) {
			GLES30.glDeleteTextures(1, new int[]{sample.textureId}, 0);
		}
	}

	@NonNull
	private static List<String> extractRequiredSampleNames(@NonNull String userCode) {
		ArrayList<String> names = new ArrayList<>();
		Matcher matcher = REQUIRED_SAMPLE_PATTERN.matcher(userCode);
		while (matcher.find()) {
			String name = matcher.group(1);
			if (name != null && !names.contains(name)) {
				names.add(name);
			}
		}
		return names;
	}

	private static final class RequiredSampleBinding {
		@NonNull
		private final String uniformName;
		private final int textureId;
		private final float width;
		private final float height;
		private final float sampleRate;
		private final float durationSeconds;

		private RequiredSampleBinding(@NonNull String uniformName,
				int textureId,
				float width,
				float height,
				float sampleRate,
				float durationSeconds) {
			this.uniformName = uniformName;
			this.textureId = textureId;
			this.width = width;
			this.height = height;
			this.sampleRate = sampleRate;
			this.durationSeconds = durationSeconds;
		}
	}

	private void submitInfoLog(@NonNull List<ShaderError> infoLog) {
		OnInfoLogListener listener = onInfoLogListener;
		if (listener != null) {
			listener.onInfoLog(infoLog);
		}
	}

	private void handleAudioRuntimeFailure(@NonNull String fallbackMessage,
			@NonNull Throwable t) {
		Log.e(TAG, fallbackMessage, t);
		playing = false;
		clearPlaybackClock();
		pauseAudioTrackIfNeeded();
		submitInfoLog(List.of(ShaderError.createGeneral(
				t.getMessage() != null && !t.getMessage().isBlank()
						? t.getMessage()
						: fallbackMessage)));
	}

	private void applyQueuedSourceIfNeeded() {
		String source = queuedSource;
		if (source == null) {
			return;
		}
		queuedSource = null;
		recompile(source);
		Runnable listener = onCueAppliedListener;
		if (listener != null) {
			listener.run();
		}
	}

	@NonNull
	private static List<ShaderError> parseGeneralInfoLog(@Nullable String infoLog,
			@NonNull String fallbackMessage) {
		if (infoLog == null) {
			return List.of(ShaderError.createGeneral(fallbackMessage));
		}
		String trimmed = infoLog.trim();
		if (trimmed.isEmpty()) {
			return List.of(ShaderError.createGeneral(fallbackMessage));
		}
		return parseInfoLog(trimmed, 0);
	}

	@NonNull
	private static List<ShaderError> parseUserShaderInfoLog(@Nullable String infoLog) {
		if (infoLog == null || infoLog.trim().isEmpty()) {
			return List.of(ShaderError.createGeneral("Failed to compile audio shader"));
		}
		return parseInfoLog(infoLog.trim(), USER_SHADER_LINE_OFFSET);
	}

	@NonNull
	private static List<ShaderError> parseInfoLog(@NonNull String infoLog, int lineOffset) {
		String[] messages = infoLog.split("\n");
		List<ShaderError> errors = new ArrayList<>(messages.length);
		for (String message : messages) {
			if (message.isBlank()) {
				continue;
			}
			Matcher matcher = INFO_LOG_PATTERN.matcher(message);
			if (!matcher.matches()) {
				errors.add(ShaderError.createGeneral(message));
				continue;
			}
			String errorLineString = matcher.group(2);
			String errorMessage = matcher.group(3);
			if (errorLineString == null || errorMessage == null) {
				errors.add(ShaderError.createGeneral(message));
				continue;
			}
			int line = Integer.parseInt(errorLineString) - lineOffset;
			if (line > 0) {
				errors.add(ShaderError.createWithLine(line, errorMessage));
			} else {
				errors.add(ShaderError.createGeneral(errorMessage));
			}
		}
		return errors.isEmpty()
				? List.of(ShaderError.createGeneral(infoLog))
				: errors;
	}

	private static int countLines(@NonNull String text) {
		int lines = 0;
		for (int i = 0; i < text.length(); i++) {
			if (text.charAt(i) == '\n') {
				++lines;
			}
		}
		return lines;
	}
}
