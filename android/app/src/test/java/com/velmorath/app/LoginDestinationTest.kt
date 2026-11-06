package com.velmorath.app

import org.junit.Assert.*
import org.junit.Test

class LoginDestinationTest {
    @Test fun onlyIntendedAppLandingPagesCanTriggerValidation() {
        assertTrue(LoginDestination.isCampusPage("portal", "https://zhlj.whu.edu.cn/pc/toIndex"))
        assertTrue(LoginDestination.isCampusPage("jwgl", "https://jwgl.whu.edu.cn/xtgl/index_initMenu.html?x=1"))
        listOf("https://cas.whu.edu.cn/authserver/login", "https://cas.whu.edu.cn/authserver/reAuthCheck",
            "https://zhlj.whu.edu.cn.evil.example/pc/index", "http://zhlj.whu.edu.cn/pc/index",
            "https://zhlj.whu.edu.cn/", "invalid").forEach {
            assertFalse(it, LoginDestination.isCampusPage("portal", it))
        }
        assertFalse(LoginDestination.isCampusPage("jwgl", "https://jwgl.whu.edu.cn/xtgl/login_slogin.html"))
    }
}
