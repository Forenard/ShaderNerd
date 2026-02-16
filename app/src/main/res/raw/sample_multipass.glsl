#version 300 es
// Multi-pass rendering example
// - Buffer 0: draws animated circles and accumulates with previous frame
// - Buffer 1: horizontal blur of Buffer 0
// - main(): vertical blur of Buffer 1 (final output)
//
// Define void main<N>() to create buffer passes.
// Access buffer output via uniform sampler2D buffer<N>.
// Passes execute in numeric order: main0 -> main1 -> ... -> main()

precision highp float;

uniform vec2 resolution;
uniform float time;
uniform int frame;

// Buffer textures (automatically available when main<N>() is defined)
uniform sampler2D buffer0;
uniform sampler2D buffer1;

out vec4 fragColor;

// Buffer 0: Draw animated circles and blend with previous frame
void main0() {
    vec2 uv = gl_FragCoord.xy / resolution;

    // Get previous frame (self-reference for feedback effect)
    vec4 prev = texture(buffer0, uv);

    // Draw some animated circles
    vec3 col = vec3(0.0);
    for (int i = 0; i < 5; i++) {
        float fi = float(i);
        float t = time * (0.3 + fi * 0.1);
        vec2 center = vec2(
            0.5 + 0.3 * sin(t + fi),
            0.5 + 0.3 * cos(t * 1.3 + fi * 2.0)
        );
        float dist = length(uv - center);
        float radius = 0.05 + 0.02 * sin(time * 2.0 + fi);

        // Color based on circle index
        vec3 circleCol = vec3(
            0.5 + 0.5 * sin(fi * 1.0),
            0.5 + 0.5 * sin(fi * 1.5 + 2.0),
            0.5 + 0.5 * sin(fi * 2.0 + 4.0)
        );

        col += circleCol * smoothstep(radius, radius - 0.01, dist);
    }

    // Blend with previous frame (feedback/trail effect)
    fragColor = vec4(mix(prev.rgb * 0.95, col, 0.3), 1.0);
}

// Buffer 1: Horizontal blur
void main1() {
    vec2 uv = gl_FragCoord.xy / resolution;
    vec2 texel = 1.0 / resolution;

    vec3 col = vec3(0.0);
    float total = 0.0;

    // 9-tap horizontal blur
    for (int i = -4; i <= 4; i++) {
        float weight = 1.0 - abs(float(i)) / 5.0;
        col += texture(buffer0, uv + vec2(float(i) * texel.x * 2.0, 0.0)).rgb * weight;
        total += weight;
    }

    fragColor = vec4(col / total, 1.0);
}

// Main (Image) pass: Vertical blur + final output
void main() {
    vec2 uv = gl_FragCoord.xy / resolution;
    vec2 texel = 1.0 / resolution;

    vec3 col = vec3(0.0);
    float total = 0.0;

    // 9-tap vertical blur
    for (int i = -4; i <= 4; i++) {
        float weight = 1.0 - abs(float(i)) / 5.0;
        col += texture(buffer1, uv + vec2(0.0, float(i) * texel.y * 2.0)).rgb * weight;
        total += weight;
    }

    // Add slight vignette
    float vignette = 1.0 - 0.3 * length(uv - 0.5);

    fragColor = vec4(col / total * vignette, 1.0);
}
