#version 320 es
precision highp float;
precision highp int;
precision highp uint;

out vec4 outColor;

uniform float time;
uniform vec2 resolution;
uniform sampler2D backbuffer;

vec3 hash(vec3 h){uvec3 v=floatBitsToUint(h+vec3(.179,-.241,.731));v = v * 1664525u + 1013904223u;v.x += v.y*v.z;v.y += v.z*v.x;v.z += v.x*v.y;v ^= v >> 16u;v.x += v.y*v.z;v.y += v.z*v.x;v.z += v.x*v.y;return vec3(v)/vec3(-1u);}

void add(ivec2 p,vec3 v){uvec3 q=uvec3(v*2048.);imageAtomicAdd(computeTex[0],p,q.x);imageAtomicAdd(computeTex[1],p,q.y);imageAtomicAdd(computeTex[2],p,q.z);}
vec3 read(ivec2 p){return vec3(imageLoad(computeTexBack[0],p).x,imageLoad(computeTexBack[1],p).x,imageLoad(computeTexBack[2],p).x)/2048.;}

void main()
{
	vec2 F=gl_FragCoord.xy,R=resolution;
	ivec2 U=ivec2(F);
	outColor=vec4(read(U),1);
	if(U.x<100)
	{
		ivec2 i=ivec2(hash(vec3(floor(time),F)).xy*R);
		add(i,hash(vec3(4.2,F)));
	}
}
