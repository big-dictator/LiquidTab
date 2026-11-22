package io.github.offlineglass.rendering

import android.graphics.RuntimeShader

/** No pixel cache, quantization, cadence change or shader-source change. */
private object UniformStates {
    // Values contain no shader reference: dead shader instances remain collectible.
    private val states=java.util.WeakHashMap<RuntimeShader,ExactUniformValues>()
    @Synchronized fun get(shader: RuntimeShader)=states.getOrPut(shader){ExactUniformValues()}

}

internal fun RuntimeShader.setFloatUniformExact(name: String,a: Float) {
    val s=UniformStates.get(this);val x=a.toRawBits()
    synchronized(s){if(!s.matches(name,1,x,0,0,0)){setFloatUniform(name,a);s.commit(name,1,x,0,0,0)}}
}
internal fun RuntimeShader.setFloatUniformExact(name: String,a: Float,b: Float) {
    val s=UniformStates.get(this);val x=a.toRawBits();val y=b.toRawBits()
    synchronized(s){if(!s.matches(name,2,x,y,0,0)){setFloatUniform(name,a,b);s.commit(name,2,x,y,0,0)}}
}
internal fun RuntimeShader.setFloatUniformExact(name: String,a: Float,b: Float,c: Float) {
    val s=UniformStates.get(this);val x=a.toRawBits();val y=b.toRawBits();val z=c.toRawBits()
    synchronized(s){if(!s.matches(name,3,x,y,z,0)){setFloatUniform(name,a,b,c);s.commit(name,3,x,y,z,0)}}
}
internal fun RuntimeShader.setFloatUniformExact(name: String,a: Float,b: Float,c: Float,d: Float) {
    val s=UniformStates.get(this);val x=a.toRawBits();val y=b.toRawBits();val z=c.toRawBits();val w=d.toRawBits()
    synchronized(s){if(!s.matches(name,4,x,y,z,w)){setFloatUniform(name,a,b,c,d);s.commit(name,4,x,y,z,w)}}
}
internal fun RuntimeShader.setFloatUniformExact(name: String,values: FloatArray) {
    val s=UniformStates.get(this)
    synchronized(s){if(!s.matches(name,values)){setFloatUniform(name,values);s.commit(name,values)}}
}
@JvmName("setFloatUniformExactVarargs")
internal fun RuntimeShader.setFloatUniformExact(name: String,vararg values: Float) = setFloatUniformExact(name,values)

// Color setters can address float4 uniforms too. Preserve their exact native
// semantics and invalidate a prior float record rather than assuming disjoint names.
internal fun RuntimeShader.setColorUniformTracked(name: String,value: Int) {
    val s=UniformStates.get(this);synchronized(s){setColorUniform(name,value);s.invalidate(name)}
}
internal fun RuntimeShader.setColorUniformTracked(name: String,value: Long) {
    val s=UniformStates.get(this);synchronized(s){setColorUniform(name,value);s.invalidate(name)}
}
internal fun RuntimeShader.setColorUniformTracked(name: String,value: android.graphics.Color) {
    val s=UniformStates.get(this);synchronized(s){setColorUniform(name,value);s.invalidate(name)}
}


