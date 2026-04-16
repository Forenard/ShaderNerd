vec2 mainAudio(vec4 time) {
	float level = mix(0.55, 1.20, faderFetch(fader0));
	float toneMix = mix(0.10, 0.34, faderFetch(fader1));
	float hatMix = mix(0.03, 0.22, faderFetch(fader2));
	float stereoSpread = mix(0.0, 65.0, faderFetch(fader3));
	float kickBase = mix(48.0, 92.0, faderFetch(fader4));
	float kickDrop = mix(70.0, 190.0, faderFetch(fader5));
	float toneBase = mix(165.0, 520.0, faderFetch(fader6));
	float gate = mix(0.35, 1.0, faderFetch(fader7));
	float kickEnv = exp(-18.0 * time.x);
	float kick = sin(2.0 * _PI * (kickBase + kickDrop * kickEnv) * time.w) * kickEnv;
	float hatPhase = fract(time.w * bpm / 15.0);
	float hatEnv = exp(-50.0 * hatPhase);
	float hat = sin(2.0 * _PI * 4000.0 * time.w) * hatEnv;
	float toneEnv = exp(-6.0 * time.y);
	float leftTone = sin(2.0 * _PI * toneBase * time.w) * toneEnv;
	float rightTone = sin(2.0 * _PI * (toneBase + stereoSpread) * time.w) * toneEnv;
	vec2 out2 = 0.38 * vec2(kick) +
			hatMix * vec2(hat) +
			toneMix * vec2(leftTone, rightTone);
	return clamp(level * gate * out2, -0.9, 0.9);
}
