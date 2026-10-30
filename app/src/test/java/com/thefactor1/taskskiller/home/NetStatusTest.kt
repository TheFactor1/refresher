package com.thefactor1.taskskiller.home

import com.thefactor1.taskskiller.home.NetStatus.Link
import com.thefactor1.taskskiller.home.NetStatus.Net
import com.thefactor1.taskskiller.status.SystemStats.Level
import org.junit.Assert.assertEquals
import org.junit.Test

class NetStatusTest {

    @Test fun offlineIsRed() {
        assertEquals(Level.BAD, NetStatus.level(Net(Link.NONE, null, false, false)))
        assertEquals("connected but no internet", Level.BAD, NetStatus.level(Net(Link.WIFI, 4, false, true)))
    }

    @Test fun noVpnOrWeakWifiIsAmber() {
        assertEquals(Level.WARN, NetStatus.level(Net(Link.ETHERNET, null, true, false)))
        assertEquals(Level.WARN, NetStatus.level(Net(Link.WIFI, 1, true, true)))
    }

    @Test fun onlineWithVpnIsFine() {
        assertEquals(Level.OK, NetStatus.level(Net(Link.WIFI, 3, true, true)))
        assertEquals(Level.OK, NetStatus.level(Net(Link.ETHERNET, null, true, true)))
    }

    @Test fun barsBecomeWords() {
        assertEquals(NetStatus.Strength.STRONG, NetStatus.strength(4))
        assertEquals(NetStatus.Strength.FAIR, NetStatus.strength(2))
        assertEquals(NetStatus.Strength.WEAK, NetStatus.strength(1))
    }
}
