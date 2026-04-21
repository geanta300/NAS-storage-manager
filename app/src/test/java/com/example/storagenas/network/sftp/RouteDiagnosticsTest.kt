package com.example.storagenas.network.sftp

import com.example.storagenas.domain.model.ZeroTierConnectionMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteDiagnosticsTest {
    @Test
    fun `classifyHostType marks rfc1918 as private`() {
        assertEquals(HostTypeLabel.PRIVATE, classifyHostType("10.0.0.5"))
        assertEquals(HostTypeLabel.PRIVATE, classifyHostType("172.16.1.50"))
        assertEquals(HostTypeLabel.PRIVATE, classifyHostType("192.168.1.10"))
        assertEquals(HostTypeLabel.PRIVATE, classifyHostType("localhost"))
    }

    @Test
    fun `classifyHostType marks public ip and domain as public_or_domain`() {
        assertEquals(HostTypeLabel.PUBLIC_OR_DOMAIN, classifyHostType("8.8.8.8"))
        assertEquals(HostTypeLabel.PUBLIC_OR_DOMAIN, classifyHostType("nas.example.com"))
    }

    @Test
    fun `shouldFlagExpectedUnreachablePrivateIpOnCellular true only for private plus cellular`() {
        assertTrue(
            shouldFlagExpectedUnreachablePrivateIpOnCellular(
                hostType = HostTypeLabel.PRIVATE,
                networkType = NetworkTypeLabel.CELLULAR,
            ),
        )
        assertFalse(
            shouldFlagExpectedUnreachablePrivateIpOnCellular(
                hostType = HostTypeLabel.PRIVATE,
                networkType = NetworkTypeLabel.WIFI,
            ),
        )
        assertFalse(
            shouldFlagExpectedUnreachablePrivateIpOnCellular(
                hostType = HostTypeLabel.PUBLIC_OR_DOMAIN,
                networkType = NetworkTypeLabel.CELLULAR,
            ),
        )
    }

    @Test
    fun `decideTcpFallback blocks fallback when lan ineligible`() {
        val decision = decideTcpFallback(
            TcpFallbackDecisionInput(
                isLanEligible = false,
                hostLikelyOnCurrentLan = false,
                directTcpProbeSucceeded = false,
                userAllowsFallback = true,
                quarantineActive = true,
                hasRecentDisruptiveEvent = true,
                hasRecentZeroTierTimeout = true,
                hasRecentZeroTierRouteUnavailable = true,
                avoidZeroTierDataPlane = true,
            ),
        )

        assertFalse(decision.allowTcpFallback)
        assertFalse(decision.forceTcpFallback)
        assertEquals("lanIneligible", decision.reason)
    }

    @Test
    fun `decideTcpFallback blocks when direct path is not verified`() {
        val decision = decideTcpFallback(
            TcpFallbackDecisionInput(
                isLanEligible = true,
                hostLikelyOnCurrentLan = false,
                directTcpProbeSucceeded = false,
                userAllowsFallback = true,
                quarantineActive = false,
                hasRecentDisruptiveEvent = false,
                hasRecentZeroTierTimeout = false,
                hasRecentZeroTierRouteUnavailable = false,
                avoidZeroTierDataPlane = false,
            ),
        )

        assertFalse(decision.allowTcpFallback)
        assertFalse(decision.forceTcpFallback)
        assertEquals("directPathUnverified", decision.reason)
    }

    @Test
    fun `decideTcpFallback allows user fallback when direct path verified`() {
        val decision = decideTcpFallback(
            TcpFallbackDecisionInput(
                isLanEligible = true,
                hostLikelyOnCurrentLan = true,
                directTcpProbeSucceeded = false,
                userAllowsFallback = true,
                quarantineActive = false,
                hasRecentDisruptiveEvent = false,
                hasRecentZeroTierTimeout = false,
                hasRecentZeroTierRouteUnavailable = false,
                avoidZeroTierDataPlane = false,
            ),
        )

        assertTrue(decision.allowTcpFallback)
        assertFalse(decision.forceTcpFallback)
        assertEquals("userEnabled", decision.reason)
    }

    @Test
    fun `decideTcpFallback forces fallback for safety signals when direct path verified by probe`() {
        val decision = decideTcpFallback(
            TcpFallbackDecisionInput(
                isLanEligible = true,
                hostLikelyOnCurrentLan = false,
                directTcpProbeSucceeded = true,
                userAllowsFallback = false,
                quarantineActive = true,
                hasRecentDisruptiveEvent = false,
                hasRecentZeroTierTimeout = false,
                hasRecentZeroTierRouteUnavailable = false,
                avoidZeroTierDataPlane = false,
            ),
        )

        assertTrue(decision.allowTcpFallback)
        assertTrue(decision.forceTcpFallback)
        assertEquals("localQuarantine", decision.reason)
    }

    @Test
    fun `decideSocketRouteOrder prefers system first in system route mode`() {
        val order = decideSocketRouteOrder(
            SocketRouteOrderInput(
                connectionMode = ZeroTierConnectionMode.SYSTEM_ROUTE_FIRST,
                forceTcpFallback = false,
            ),
        )

        assertEquals(
            listOf(PreferredSocketRoute.SYSTEM_SOCKET, PreferredSocketRoute.EMBEDDED_ZEROTIER),
            order,
        )
    }

    @Test
    fun `decideSocketRouteOrder keeps embedded only in embedded mode`() {
        val order = decideSocketRouteOrder(
            SocketRouteOrderInput(
                connectionMode = ZeroTierConnectionMode.EMBEDDED_ONLY,
                forceTcpFallback = true,
            ),
        )

        assertEquals(listOf(PreferredSocketRoute.EMBEDDED_ZEROTIER), order)
    }

    @Test
    fun `decideSocketRouteOrder flips to system first when auto fallback and tcp fallback forced`() {
        val order = decideSocketRouteOrder(
            SocketRouteOrderInput(
                connectionMode = ZeroTierConnectionMode.AUTO_FALLBACK,
                forceTcpFallback = true,
            ),
        )

        assertEquals(
            listOf(PreferredSocketRoute.SYSTEM_SOCKET, PreferredSocketRoute.EMBEDDED_ZEROTIER),
            order,
        )
    }
}
