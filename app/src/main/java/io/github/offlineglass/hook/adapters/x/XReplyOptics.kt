package io.github.offlineglass.hook.adapters.x

import io.github.offlineglass.rendering.ContinuousControlOptics

/** Refine the sampled contour into a smooth curve before computing refraction. */
internal object XReplyOptics {
    val source: String = ContinuousControlOptics.source.let { original ->
        val start=original.indexOf("float3 continuousContour(float2 p) {")
        val end=original.indexOf("float radiusAt(",start)
        require(start>=0 && end>start)
        original.substring(0,start)+CURVE+original.substring(end)
    }.replace("1.0 - sqrt(1.0 - x * x)", "x * x * (0.5 + 0.5 * x * x)")

    private const val CURVE="""
        float3 continuousContour(float2 p) {
            if (p.x >= contourFlatBounds.x && p.x <= contourFlatBounds.y &&
                min(p.y, size.y-p.y) <= min(p.x, size.x-p.x)) {
                float y=p.y-size.y*0.5;
                return float3(abs(y)-size.y*0.5,0.0,y<0.0?-1.0:1.0);
            }
            float best=1.0e20;
            float2 aBest=float2(0.0), bBest=float2(1.0,0.0);
            float2 na=float2(0.0,1.0), nb=na;
            float2 previous=aBest, following=bBest, nPrevious=na, nFollowing=nb;
            float u=0.0;
            // Coarse lookup only. Polygon distance must never drive the lens:
            // each chord otherwise leaves a wedge in the refracted image.
            for (int i=0;i<64;i++) {
                float2 a=shapePoints[i], b=shapePoints[i==63?0:i+1], edge=b-a;
                float t=clamp(dot(p-a,edge)/max(dot(edge,edge),0.0001),0.0,1.0);
                float2 v=p-a-edge*t;
                float d2=dot(v,v);
                if (d2<best) {
                    best=d2; aBest=a; bBest=b;
                    na=shapeNormals[i]; nb=shapeNormals[i==63?0:i+1]; u=t;
                    previous=shapePoints[i==0?63:i-1];
                    following=shapePoints[i>=62?i-62:i+2];
                    nPrevious=shapeNormals[i==0?63:i-1];
                    nFollowing=shapeNormals[i>=62?i-62:i+2];
                }
            }
            // Cubic Hermite endpoints share position and tangent. Refine the
            // closest point on that curve rather than on the coarse chord.
            float curveBest=1.0e20;
            float3 result=float3(0.0,0.0,1.0);
            // The nearest chord and nearest curve can belong to adjacent
            // spans. Check both neighbors to avoid seams at span boundaries.
            for (int neighbor=-1;neighbor<=1;neighbor++) {
            float2 a=neighbor<0?previous:(neighbor>0?bBest:aBest);
            float2 b=neighbor<0?aBest:(neighbor>0?following:bBest);
            float2 nA=neighbor<0?nPrevious:(neighbor>0?nb:na);
            float2 nB=neighbor<0?na:(neighbor>0?nFollowing:nb);
            float2 chord=b-a;
            u=clamp(dot(p-a,chord)/max(dot(chord,chord),0.0001),0.0,1.0);
            float tangentScale=length(chord)*2.0/
                (1.0+sqrt(max(0.0,(1.0+dot(nA,nB))*0.5)));
            float2 ta=shapeOrientation*float2(-nA.y,nA.x)*tangentScale;
            float2 tb=shapeOrientation*float2(-nB.y,nB.x)*tangentScale;
            float2 c=3.0*chord-2.0*ta-tb;
            float2 d=-2.0*chord+ta+tb;
            for (int step=0;step<5;step++) {
                float2 q=a+u*(ta+u*(c+u*d));
                float2 dq=ta+u*(2.0*c+3.0*u*d);
                float2 ddq=2.0*c+6.0*u*d;
                float denominator=max(dot(dq,dq)+dot(q-p,ddq),0.001);
                u=clamp(u-dot(q-p,dq)/denominator,0.0,1.0);
            }
            float2 q=a+u*(ta+u*(c+u*d));
            float2 tangent=normalize(ta+u*(2.0*c+3.0*u*d));
            float2 normal=shapeOrientation*float2(tangent.y,-tangent.x);
            float2 delta=p-q;
            float distance=length(delta);
            if (distance<curveBest) {
                curveBest=distance;
                result=float3(dot(delta,normal)>=0.0?distance:-distance,normal);
            }
            }
            return result;
        }
    """
}
