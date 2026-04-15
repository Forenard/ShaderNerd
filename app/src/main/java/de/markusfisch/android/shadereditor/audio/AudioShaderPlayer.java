package de.markusfisch.android.shadereditor.audio;

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

import androidx.annotation.Nullable;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

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

	public static final int SAMPLE_RATE = 48000;
	public static final int BLOCK_SIZE = 128;
	public static final int BLOCKS_PER_RENDER = 16;
	public static final int FRAMES_PER_RENDER = BLOCK_SIZE * BLOCKS_PER_RENDER; // 2048
	public static final int KNOB_COUNT = 8;
	private static final float NS_PER_SECOND = 1_000_000_000f;

	@Nullable
	private Thread audioThread;
	private volatile boolean running;
	private volatile boolean playing;
	private volatile boolean rewindRequested;

	@Nullable
	private volatile String pendingSource;

	private final Object pauseLock = new Object();

	private final BeatClock beatClock = new BeatClock(140f);
	private final WavenerdParam[] knobs = new WavenerdParam[KNOB_COUNT];
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

	public AudioShaderPlayer() {
		for (int i = 0; i < KNOB_COUNT; i++) {
			knobs[i] = new WavenerdParam(0f);
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

	public void setKnob(int index, float value) {
		if (index < 0 || index >= KNOB_COUNT) return;
		knobs[index].value = value;
	}

	// ----- audio thread ---------------------------------------------------------------------------

	private void audioLoop() {
		try {
			if (!initEgl()) {
				Log.e(TAG, "failed to init EGL");
				return;
			}
			initGl();
			initAudioTrack();

			while (running) {
				synchronized (pauseLock) {
					while (running && !playing) {
						pauseAudioTrackIfNeeded();
						try {
							pauseLock.wait();
						} catch (InterruptedException ignored) {
							Thread.currentThread().interrupt();
						}
					}
				}
				if (!running) break;
				playAudioTrackIfNeeded();

				if (pendingSource != null) {
					recompile(pendingSource);
					pendingSource = null;
				}

				if (rewindRequested) {
					beatClock.rewind();
					if (audioTrack != null) audioTrack.flush();
					rewindRequested = false;
				}

				if (program == 0) {
					// no valid shader → output silence and yield
					java.util.Arrays.fill(interleaved, 0f);
					if (audioTrack != null) {
						audioTrack.write(interleaved, 0, interleaved.length,
								AudioTrack.WRITE_BLOCKING);
					}
					beatClock.advance((double) FRAMES_PER_RENDER / SAMPLE_RATE);
					continue;
				}

				renderBlock();
				readback();
				if (audioTrack != null) {
					audioTrack.write(interleaved, 0, interleaved.length,
							AudioTrack.WRITE_BLOCKING);
				}
				beatClock.advance((double) FRAMES_PER_RENDER / SAMPLE_RATE);
			}
		} catch (Throwable t) {
			Log.e(TAG, "audio loop crashed", t);
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
		}

		pixelBuffer = ByteBuffer.allocateDirect(FRAMES_PER_RENDER * 4 * 4)
				.order(ByteOrder.nativeOrder())
				.asFloatBuffer();
		interleaved = new float[FRAMES_PER_RENDER * 2];
	}

	private void tearDownGl() {
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
		if (userCode == null || userCode.trim().isEmpty()) {
			return;
		}

		int vs = compile(GLES30.GL_VERTEX_SHADER, AudioShaderChunks.VERTEX);
		if (vs == 0) return;
		int fs = compile(GLES30.GL_FRAGMENT_SHADER, AudioShaderChunks.wrap(userCode));
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
			Log.e(TAG, "link failed: " + GLES30.glGetProgramInfoLog(p));
			GLES30.glDeleteProgram(p);
			return;
		}
		program = p;
	}

	private static int compile(int type, String src) {
		int s = GLES30.glCreateShader(type);
		GLES30.glShaderSource(s, src);
		GLES30.glCompileShader(s);
		int[] ok = new int[1];
		GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0);
		if (ok[0] == 0) {
			Log.e(TAG, "compile failed: " + GLES30.glGetShaderInfoLog(s));
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

		beatClock.snapshot(tmpTimeHead, tmpLengths);
		setUniform4f("_timeHead", tmpTimeHead);
		setUniform4f("timeLength", tmpLengths);

		for (int i = 0; i < KNOB_COUNT; i++) {
			WavenerdParam k = knobs[i];
			k.update();
			int loc = GLES30.glGetUniformLocation(program, "param_knob" + i);
			if (loc >= 0) {
				GLES30.glUniform4f(loc, k.y0, k.y1, k.y2, k.y3);
			}
		}

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
	 * Lagrange-history wrapped knob value (port of WavenerdDeckParam).
	 */
	private static final class WavenerdParam {
		volatile float value;
		float y0, y1, y2, y3;

		WavenerdParam(float v) {
			value = v;
			y0 = y1 = y2 = y3 = v;
		}

		void update() {
			y3 = y2;
			y2 = y1;
			y1 = y0;
			y0 = value;
		}
	}
}
