package com.velmorath.app

import java.net.URI

/** Only consider pages on the intended campus application, never the CAS challenge. */
object LoginDestination {
    fun isCampusPage(target: String, url: String): Boolean {
        val uri = try { URI(url) } catch (_: Exception) { return false }
        if (uri.scheme != "https") return false
        val path = uri.path.orEmpty()
        return when (target) {
            "portal" -> uri.host == "zhlj.whu.edu.cn" &&
                (path == "/pc/index" || path == "/pc/toIndex" || path == "/pc/index.html")
            "jwgl" -> uri.host == "jwgl.whu.edu.cn" && path == "/xtgl/index_initMenu.html"
            else -> false
        }
    }
}
