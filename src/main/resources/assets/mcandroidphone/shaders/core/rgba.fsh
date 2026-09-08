#version 330
#moj_import <minecraft:dynamictransforms.glsl>
uniform sampler2D Sampler0;
uniform sampler2D Sampler1;
in vec4 vertexColor;
in vec2 texCoord0;
out vec4 fragColor;
void main() {
    vec3 rgb=texture(Sampler0,texCoord0).rgb;
    fragColor=vec4(rgb,1.0)*vertexColor*ColorModulator;
}
