
#pragma use_binary_literals
uniform vec4 param_knob0,param_knob1,param_knob2,param_knob3,param_knob4,param_knob5,param_knob6,param_knob7;
#define p0 paramFetch(param_knob0)
#define p1 paramFetch(param_knob1)
#define p2 paramFetch(param_knob2)
#define p3 paramFetch(param_knob3)
#define p4 paramFetch(param_knob4)
#define p5 paramFetch(param_knob5)
#define p6 paramFetch(param_knob6)
#define p7 paramFetch(param_knob7)

#define S2T (15.0 / bpm)
#define B2T (60.0 / bpm)
#define ZERO min(0, int(bpm))
#define sat(x) clamp(x, 0., 1.)
#define linearstep(a,b,x) sat(((x)-(a))/((b)-(a)))
#define clip(x) clamp(x, -1., 1.)
#define quantize(i,q) (floor((i)/(q))*(q))
#define tri(x) (1.0 - 4.0 * abs(fract((x) + 0.25) - 0.5))
#define rep(i,n) for(int i=ZERO;i<n;i++)
#define m2f(i) (exp2(((i)-69.)/12.)*440.)
// #define m2f(i) (exp2(((i)-69.)/12.)*453.)
#define cis(t) vec2(cos(t),sin(t))
#define u2s(u) ((u)*2.-1.)
#define s2u(u) ((u)*.5+.5)

#define SWING 0.5
const float PI=acos(-1.);
const float TAU=2.*PI;

uvec3 hashu(uvec3 v)
{
  v = v * 1664525u + 1013904223u;v.x += v.y*v.z;v.y += v.z*v.x;v.z += v.x*v.y;v ^= v >> 16u;v.x += v.y*v.z;v.y += v.z*v.x;v.z += v.x*v.y;
  return v;
}
vec3 hash(vec3 h){
  uvec3 v=floatBitsToUint(h+vec3(.179,-.241,.731));
  v=hashu(v);
  return vec3(v)/vec3(-1u);
}

vec3 cyc(vec3 x,float q)
{
  const mat3 m=mat3(0.5547001962252291,0,-0.8320502943378437,0.22237479499833038,0.9636241116594317,0.14824986333222026,0.8017837257372732,-0.2672612419124244,0.5345224838248488);
  vec4 v;
  rep(i,5){x+=sin(x.yzx);v=q*v+vec4(cross(cos(x),sin(x.zxy)),1);x*=q*m;}
  return v.xyz/v.w;
}
vec3 tknot(vec2 pq,float t)
{
  vec2 th=TAU*t*pq;
  float r=1.+.5*cos(th.x);
  return vec3(r*cis(th.y),sin(th.x));
}
mat2 rot(float t){
  float c = cos(t),s = sin(t);
  return mat2(c, s, -s, c);
}




// 行って戻る
float j_wouf(float x){
  return 1.0 / (1.0 + exp(-50.0 * (x - 0.15))) * 1.0 / (1.0 + exp(-30.0 * (0.8 - x))) / (1.0 + exp(-6.0 * (0.9 - x)));
}
float t2sSwing(float t){
  float st = t / S2T;
  return 2.0 * floor(st / 2.0) + step(SWING, fract(0.5 * st));
}
float s2tSwing(float st){
 return 2.0 * S2T * (floor(st / 2.0) + SWING * mod(st, 2.0));
}
int ec16(int n)
{
  return int[](0x0000,0x8000,0x8080,0x8420,0x8888,
  0x9248,0x9494,0x952a,0xaaaa,
  0xb56a,0xb5b5,0xb6db,0xbbbb,
  0xbdef,0xbfbf,0xfffe,0xffff
  )[clamp(n,0,16)];
}
int rotr16(int x,int n){x&=0xffff;return ((x >> n) | (x << (16 - n))) & 0xffff;}
int rotl16(int x,int n){x&=0xffff;return ((x << n) | (x >> (16 - n))) & 0xffff;}
vec4 seq16(float t, int seq) {
  t = mod(t, 4.0 * B2T);
  int sti = clamp(int(t2sSwing(t)), 0, 15);
  int rotated = ((seq >> (15 - sti)) | (seq << (sti + 1))) & 0xffff;

  float i_prevStepBehind = log2(float(rotated & -rotated));
  float prevStep = float(sti) - i_prevStepBehind;
  float prevTime = s2tSwing(prevStep);
  float i_nextStepForward = 16.0 - floor(log2(float(rotated)));
  float nextStep = float(sti) + i_nextStepForward;
  float nextTime = s2tSwing(nextStep);

  return vec4(
    prevStep,
    t - prevTime,
    nextStep,
    nextTime - t
  );
}

vec2 boxMuller(vec2 xi){
  float r = sqrt(-2.0 * log(xi.x));
  float t = TAU*xi.y;
  return r*cis(t);
}
vec2 cheapnoise(float t) {
  uvec3 s=uvec3(t * 256.0);
  float p=fract(t * 256.0);

  vec3 dice;
  vec2 v = vec2(0.0);

  dice=vec3(hashu(s + 0u)) / float(-1u) - vec3(0.5, 0.5, 0.0);
  v += dice.xy * smoothstep(1.0, 0.0, abs(p + dice.z));
  dice=vec3(hashu(s + 1u)) / float(-1u) - vec3(0.5, 0.5, 1.0);
  v += dice.xy * smoothstep(1.0, 0.0, abs(p + dice.z));
  dice=vec3(hashu(s + 2u)) / float(-1u) - vec3(0.5, 0.5, 2.0);
  v += dice.xy * smoothstep(1.0, 0.0, abs(p + dice.z));

  return 2.0 * v;
}

vec2 mainAudio(vec4 time) {
  vec4 lt=time/B2T;vec2 o=vec2(0);
  vec4 seq;float t;
  {
    int b=ec16(11);int of=0;
    b=rotl16(b,int(lt.w/4.)%4);
    vec4 seq=seq16(time.y,b);
    float t=seq.t;
    // o+=u2s(hash(vec3(t,1,2)).xy)*exp2(-t*60.)*p0;
    // o+=cheapnoise(t)*exp2(-t*60.)*p1;
  }
  return tanh(o);
}