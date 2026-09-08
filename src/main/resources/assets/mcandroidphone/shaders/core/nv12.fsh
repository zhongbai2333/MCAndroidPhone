#version 330
#moj_import <minecraft:dynamictransforms.glsl>
uniform sampler2D Sampler0;
uniform sampler2D Sampler1;
in vec4 vertexColor;
in vec2 texCoord0;
out vec4 fragColor;
void main() {
    float y=1.16438356*(texture(Sampler0,texCoord0).r-16.0/255.0);
    vec2 uv=texture(Sampler1,texCoord0).rg-vec2(128.0/255.0);
    vec3 rgb=clamp(vec3(y+1.79274107*uv.y,y-.21324861*uv.x-.53290933*uv.y,y+2.11240179*uv.x),0.0,1.0);
    fragColor=vec4(rgb,1.0)*vertexColor*ColorModulator;
}
