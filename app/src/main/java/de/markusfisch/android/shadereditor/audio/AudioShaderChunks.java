package de.markusfisch.android.shadereditor.audio;

public final class AudioShaderChunks {
	public static final String VERTEX =
			"#version 300 es\n" +
					"precision highp float;\n" +
					"in vec2 position;\n" +
					"void main() {\n" +
					"  gl_Position = vec4(position, 0.0, 1.0);\n" +
					"}\n";

	public static final String PRE =
			"#version 300 es\n" +
					"\n" +
					"precision highp float;\n" +
					"\n" +
					"#define _PI 3.14159265359\n" +
					"\n" +
					"uniform float bpm;\n" +
					"uniform float sampleRate;\n" +
					"uniform vec4 timeLength;\n" +
					"uniform float _deltaSample;\n" +
					"uniform float _framesPerRender;\n" +
					"uniform vec4 _timeHead;\n" +
					"\n" +
					"uniform vec4 param_knob0;\n" +
					"uniform vec4 param_knob1;\n" +
					"uniform vec4 param_knob2;\n" +
					"uniform vec4 param_knob3;\n" +
					"uniform vec4 param_knob4;\n" +
					"uniform vec4 param_knob5;\n" +
					"uniform vec4 param_knob6;\n" +
					"uniform vec4 param_knob7;\n" +
					"\n" +
					"out vec4 _fragColor;\n" +
					"\n" +
					"float paramFetch(vec4 param) {\n" +
					"  float x = floor(gl_FragCoord.x) / _framesPerRender;\n" +
					"  vec4 v = x - vec4(1.0, 0.0, -1.0, -2.0);\n" +
					"  float y = dot(\n" +
					"    vec4(\n" +
					"      v.y * v.z * v.w,\n" +
					"      v.x * v.z * v.w,\n" +
					"      v.x * v.y * v.w,\n" +
					"      v.x * v.y * v.z\n" +
					"    ),\n" +
					"    param / vec4(6.0, -2.0, 2.0, -6.0)\n" +
					"  );\n" +
					"  return clamp(y, 0.0, 1.0);\n" +
					"}\n";

	public static final String POST =
			"\n" +
					"void main() {\n" +
					"  vec2 out2 = mainAudio(mod(_timeHead + floor(gl_FragCoord.x) * _deltaSample, timeLength));\n" +
					"  if (any(isnan(out2)) || any(isinf(out2))) {\n" +
					"    out2 = vec2(0.0);\n" +
					"  }\n" +
					"  _fragColor = vec4(out2.x, out2.y, 0.0, 1.0);\n" +
					"}\n";

	public static String wrap(String userCode) {
		return PRE + userCode + POST;
	}

	private AudioShaderChunks() {
	}
}
