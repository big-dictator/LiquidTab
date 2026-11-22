package io.github.offlineglass.hook.adapters.taobao

import java.security.MessageDigest

/** Changes only the pinned resource's style-bootstrap preference and scoped CSS. */
internal object TaobaoFlashStyles {
    const val HASH = "ea7db7703e4a9d1c8747acdf7ed785788349fc7a619dd7bd42ad956a38700c83"
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    fun decorate(bytes: ByteArray, reserveVw: Float): ByteArray? {
        if (!reserveVw.isFinite() || reserveVw !in 0f..60f || hash(bytes) != HASH) return null
        val source = TaobaoFlashSource12620.read()
        if (hash(source) != TaobaoFlashSource12620.HASH) return null
        val text = source.toString(Charsets.UTF_8)
        val bootstrap = "l=x.versionCode>=160&&x.getLocalResourceURL"
        val index = text.lastIndexOf("]);\n//# sourceMappingURL=home.js.map")
        if (index < 0 || text.indexOf(bootstrap) != text.lastIndexOf(bootstrap)) return null
        val reserve = String.format(java.util.Locale.US, "%.2fvw", reserveVw)
        val css = ".urgeBarContainer-d5dd3a,.strengthUrgeNewUserCouponContainer-_557c8,.bottomSpace-_7a06c,.container-_23c99{display:none!important;pointer-events:none!important}" +
            ".floatBtnCarts-_56921{bottom:$reserve!important}" +
            ".fluttering-_07d10{bottom:calc(1.4rem + $reserve)!important}" +
            ".flutteringCloseBtn-_311e2{bottom:calc(2.52rem + $reserve)!important}"
        // Original bootstrap retains every stylesheet and at-rule. Add our final
        // page-local override and use its original text stylesheet branch.
        val closeMarker = "}=A;(0,s.useEffect)((()=>{window.__redpacketFloatbarVisible=P"
        if (text.indexOf(closeMarker) < 0 || text.indexOf(closeMarker) != text.lastIndexOf(closeMarker)) return null
        val decorated = text.substring(0,index) + "[\"$css\",0,'liquidtab-flash-controls',]," + text.substring(index)
        return decorated.replace(bootstrap, "l=false")
            .replace(closeMarker, "}=A;(0,s.useLayoutEffect)((()=>{if(P)ne({stopPropagation(){},preventDefault(){}})}),[P,ne]);(0,s.useEffect)((()=>{window.__redpacketFloatbarVisible=P")
            .toByteArray(Charsets.UTF_8)
    }
}
