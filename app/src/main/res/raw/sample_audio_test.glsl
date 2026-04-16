#ifdef GL_ES
precision mediump float;
#endif

uniform vec2 resolution;
uniform float time;
uniform float bpm;
uniform vec4 fader0;
uniform vec4 fader1;
uniform vec4 fader2;
uniform vec4 fader3;
uniform vec4 fader4;
uniform vec4 fader5;
uniform vec4 fader6;
uniform vec4 fader7;

float currentFader(vec4 fader)
{
	return clamp(fader.x, 0.0, 1.0);
}

void main(void)
{
	vec2 uv = (gl_FragCoord.xy * 2.0 - resolution.xy) /
			min(resolution.x, resolution.y);
	float rate = max(bpm, 1.0) / 60.0;
	float beat = fract(time * rate);
	float pulse = exp(-8.0 * beat);
	float radius = length(uv);
	float mixA = currentFader(fader0);
	float mixB = currentFader(fader1);
	float mixC = currentFader(fader2);
	float mixD = currentFader(fader3);
	float mixE = currentFader(fader4);
	float mixF = currentFader(fader5);
	float mixG = currentFader(fader6);
	float mixH = currentFader(fader7);
	float ring = smoothstep(0.34 + (0.04 + 0.10 * mixA) * pulse,
			0.31 + (0.03 + 0.10 * mixA) * pulse,
			radius);
	float spokes = 0.5 + 0.5 * cos(
			(8.0 + 18.0 * mixB) * atan(uv.y, uv.x) - time * (2.0 + 8.0 * mixC));
	float halo = smoothstep(0.95 + 0.20 * mixD,
			0.18 + 0.05 * mixD,
			radius) * exp(-3.0 * beat);
	float stripes = 0.5 + 0.5 * sin(
			uv.y * (8.0 + 30.0 * mixE) - time * (1.5 + 6.0 * mixF));
	float sweep = smoothstep(0.10, 0.00,
			abs(sin(atan(uv.y, uv.x) * 0.5 + time * (0.3 + mixG))));
	vec3 background = mix(
			vec3(0.03, 0.04, 0.08) + vec3(0.06, 0.00, 0.10) * mixH,
			vec3(0.08, 0.15, 0.24) + vec3(0.00, 0.10, 0.08) * mixD,
			0.5 + 0.5 * uv.y + 0.1 * stripes);
	vec3 accent = mix(
			vec3(0.20, 0.62, 0.95),
			vec3(1.00, 0.48, 0.12) + vec3(0.15 * mixG, 0.05 * mixH, 0.0),
			pulse);
	vec3 color = background +
			accent * ring * (0.35 + 0.65 * spokes) +
			accent.zyx * halo * (0.25 + 0.75 * sweep);
	gl_FragColor = vec4(color, 1.0);
}
