package io.github.offlineglass.rendering

/** XHS opt-in shader specialization. Geometry, projection arithmetic and tie order stay intact. */
internal object BoundedContourShader {
    private val sources = android.util.LruCache<String, String>(4)

    @Synchronized
    fun specialize(source: String): String? {
        if (!source.contains("uniform float2 shapePoints[64];")) return null
        sources.get(source)?.let { return it }
        val start = source.indexOf("float3 continuousContour(float2 p) {")
        val end = source.indexOf("float radiusAt(", start.coerceAtLeast(0))
        if (start < 0 || end <= start) return null
        val original = source.substring(start, end)
        // Keep a full-search fallback compiled from the ORIGINAL source, not another approximation.
        val fallback = original.replace("continuousContour(float2 p)", "fullContourSearch(float2 p)")
        val specialized = source.substring(0, start) + fallback + function + source.substring(end)
        sources.put(source, specialized)
        return specialized
    }

    private val function: String by lazy {
        buildString {
            append("""
                uniform float4 contourGroupBounds[8];
                uniform float contourSearchEnabled;
                float3 continuousContour(float2 p) {
                    if (contourSearchEnabled < 0.5 ||
                        !(abs(p.x) <= 1000000.0 && abs(p.y) <= 1000000.0))
                        return fullContourSearch(p);
                    float seedBest = 1.0e20;
            """)
            // Fixed indices let the compiler address uniforms directly; do not sort candidate edges.
            repeat(8) { group ->
                append("{ float2 seedDelta = p - shapePoints[${group * 8}]; seedBest = min(seedBest, dot(seedDelta, seedDelta)); }\n")
            }
            append("""
                    float best = 1.0e20;
                    float2 delta = float2(0.0);
                    float2 winningEdge = float2(-1.0, 0.0);
            """)
            repeat(8) { group ->
                append("""
                    {
                        float4 box = contourGroupBounds[$group];
                        float2 outside = max(max(box.xy - p, p - box.zw), float2(0.0));
                        float lowerBound = dot(outside, outside);
                        float candidateLimit = min(best, seedBest);
                        float guard = max(0.0001, candidateLimit * 0.00001);
                        if (lowerBound <= candidateLimit + guard) {
                            for (int i = ${group * 8}; i < ${(group + 1) * 8}; i++) {
                                float2 a = shapePoints[i];
                                float2 b = shapePoints[i == 63 ? 0 : i + 1];
                                float2 edge = b - a;
                                float t = clamp(dot(p - a, edge) / max(dot(edge, edge), 0.0001), 0.0, 1.0);
                                float2 v = p - (a + edge * t);
                                float d2 = dot(v, v);
                                if (d2 < best) {
                                    best = d2;
                                    delta = v;
                                    winningEdge = edge;
                                }
                            }
                        }
                    }
                """)
            }
            append("""
                    // Defensive original-search fallback; finite validated contours must find a candidate.
                    if (best == 1.0e20) return fullContourSearch(p);
                    float2 outward = shapeOrientation * normalize(float2(winningEdge.y, -winningEdge.x));
                    float distance = sqrt(best);
                    float side = dot(delta, outward) >= 0.0 ? 1.0 : -1.0;
                    float2 normal = distance > 0.001 ? delta * side / distance : outward;
                    return float3(distance * side, normal);
                }
            """)
        }
    }
}
