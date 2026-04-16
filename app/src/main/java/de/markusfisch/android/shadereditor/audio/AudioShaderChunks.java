package de.markusfisch.android.shadereditor.audio;

public final class AudioShaderChunks {
	public static final String VERTEX =
			"#version 320 es\n" +
					"precision highp float;\n" +
					"precision highp int;\n" +
					"precision highp uint;\n" +
					"in vec2 position;\n" +
					"void main() {\n" +
					"  gl_Position = vec4(position, 0.0, 1.0);\n" +
					"}\n";

	public static final String PRE =
			"#version 320 es\n" +
					"\n" +
					"precision highp float;\n" +
					"precision highp int;\n" +
					"precision highp uint;\n" +
					"\n" +
					"#define _PI 3.14159265359\n" +
					"\n" +
					"uniform vec2 resolution;\n" +
					"uniform vec2 touch;\n" +
					"uniform float bpm;\n" +
					"uniform float sampleRate;\n" +
					"uniform vec4 timeLength;\n" +
					"uniform float _deltaSample;\n" +
					"uniform float _framesPerRender;\n" +
					"uniform vec4 _timeHead;\n" +
					"\n" +
					"uniform vec4 fader0;\n" +
					"uniform vec4 fader1;\n" +
					"uniform vec4 fader2;\n" +
					"uniform vec4 fader3;\n" +
					"uniform vec4 fader4;\n" +
					"uniform vec4 fader5;\n" +
					"uniform vec4 fader6;\n" +
					"uniform vec4 fader7;\n" +
					"\n" +
					"#define param_knob0 fader0\n" +
					"#define param_knob1 fader1\n" +
					"#define param_knob2 fader2\n" +
					"#define param_knob3 fader3\n" +
					"#define param_knob4 fader4\n" +
					"#define param_knob5 fader5\n" +
					"#define param_knob6 fader6\n" +
					"#define param_knob7 fader7\n" +
					"\n" +
					"out vec4 _fragColor;\n" +
					"\n" +
					"float faderFetch(vec4 fader) {\n" +
					"  float x = floor(gl_FragCoord.x) / _framesPerRender;\n" +
					"  vec4 v = x - vec4(1.0, 0.0, -1.0, -2.0);\n" +
					"  float y = dot(\n" +
					"    vec4(\n" +
					"      v.y * v.z * v.w,\n" +
					"      v.x * v.z * v.w,\n" +
					"      v.x * v.y * v.w,\n" +
					"      v.x * v.y * v.z\n" +
					"    ),\n" +
					"    fader / vec4(6.0, -2.0, 2.0, -6.0)\n" +
					"  );\n" +
					"  return clamp(y, 0.0, 1.0);\n" +
					"}\n" +
					"\n" +
					"float paramFetch(vec4 param) {\n" +
					"  return faderFetch(param);\n" +
					"}\n" +
					"\n" +
					"float _safeSinc(float x) {\n" +
					"  if (abs(x) < 0.00001) {\n" +
					"    return 1.0;\n" +
					"  }\n" +
					"  return sin(x * _PI) / (x * _PI);\n" +
					"}\n" +
					"\n" +
					"vec2 sampleNearest(sampler2D s, vec4 meta, float time) {\n" +
					"  if (meta.w <= 0.0 || time < 0.0 || meta.w < time) {\n" +
					"    return vec2(0.0);\n" +
					"  }\n" +
					"  float x = time / meta.x * meta.z;\n" +
					"  vec2 uv = fract(vec2(\n" +
					"    x,\n" +
					"    floor(x) / meta.y\n" +
					"  )) + 0.5 / meta.xy;\n" +
					"  return texture(s, uv).xy;\n" +
					"}\n" +
					"\n" +
					"vec2 sampleSinc(sampler2D s, vec4 meta, float time) {\n" +
					"  if (meta.w <= 0.0 || time < 0.0 || meta.w < time) {\n" +
					"    return vec2(0.0);\n" +
					"  }\n" +
					"  vec2 sum = vec2(0.0);\n" +
					"  float def = -fract(time * meta.z);\n" +
					"  for (int i = -5; i <= 5; i++) {\n" +
					"    float x = floor(time * meta.z + float(i)) / meta.x;\n" +
					"    float deft = def + float(i);\n" +
					"    vec2 uv = fract(vec2(\n" +
					"      x,\n" +
					"      floor(x) / meta.y\n" +
					"    )) + 0.5 / meta.xy;\n" +
					"    sum += texture(s, uv).xy * min(_safeSinc(deft), 1.0);\n" +
					"  }\n" +
					"  return sum;\n" +
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
