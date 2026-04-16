package de.markusfisch.android.shadereditor.audio;

/**
 * Tracks beat / bar / 16-bar / total time for the audio shader.
 * Phase is preserved across BPM changes (port of wavenerd-deck BeatManager).
 */
public final class BeatClock {
	private static final double MAX_TOTAL = 1.0e16;

	private float bpm;
	private double sixteenBar;
	private double total;

	public BeatClock(float initialBpm) {
		this.bpm = initialBpm;
		this.sixteenBar = 0.0;
		this.total = 0.0;
	}

	public synchronized void advance(double deltaSeconds) {
		double range = sixteenBarSeconds();
		sixteenBar = (sixteenBar + deltaSeconds) % range;
		if (sixteenBar < 0) sixteenBar += range;
		total = (total + deltaSeconds) % MAX_TOTAL;
	}

	public synchronized boolean advanceAndCheckBarBoundary(double deltaSeconds) {
		float previousBar = bar();
		advance(deltaSeconds);
		return bar() < previousBar;
	}

	public synchronized void rewind() {
		sixteenBar = 0.0;
		total = 0.0;
	}

	public synchronized void nudge(double seconds) {
		advance(seconds);
	}

	public synchronized void setBpm(float newBpm) {
		if (newBpm <= 0f || newBpm == bpm) return;
		// Preserve phase: keep the same fractional position within the
		// 16-bar period when the bar length changes.
		sixteenBar = sixteenBar * bpm / newBpm;
		bpm = newBpm;
	}

	public synchronized float bpm() {
		return bpm;
	}

	public synchronized float beatSeconds() {
		return 60f / bpm;
	}

	public synchronized float barSeconds() {
		return 4f * 60f / bpm;
	}

	public synchronized float sixteenBarSeconds() {
		return 16f * 4f * 60f / bpm;
	}

	public synchronized float beat() {
		float bs = beatSeconds();
		return (float) (sixteenBar - Math.floor(sixteenBar / bs) * bs);
	}

	public synchronized float bar() {
		float bs = barSeconds();
		return (float) (sixteenBar - Math.floor(sixteenBar / bs) * bs);
	}

	public synchronized float sixteenBar() {
		return (float) sixteenBar;
	}

	public synchronized float total() {
		return (float) total;
	}

	/**
	 * Snapshot of the time uniforms at the start of a render block.
	 * timeHead = (beat, bar, 16bar, total)
	 * lengths  = (beatSec, barSec, 16barSec, MAX_TOTAL)
	 */
	public synchronized void snapshot(float[] timeHead, float[] lengths) {
		float bs = beatSeconds();
		float br = barSeconds();
		float sb = sixteenBarSeconds();
		timeHead[0] = (float) (sixteenBar - Math.floor(sixteenBar / bs) * bs);
		timeHead[1] = (float) (sixteenBar - Math.floor(sixteenBar / br) * br);
		timeHead[2] = (float) sixteenBar;
		timeHead[3] = (float) total;
		lengths[0] = bs;
		lengths[1] = br;
		lengths[2] = sb;
		lengths[3] = (float) MAX_TOTAL;
	}
}
