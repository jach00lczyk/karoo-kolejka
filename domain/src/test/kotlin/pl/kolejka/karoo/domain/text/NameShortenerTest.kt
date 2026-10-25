package pl.kolejka.karoo.domain.text

import org.junit.Assert.assertEquals
import org.junit.Test

class NameShortenerTest {
    @Test
    fun `short names stay untouched`() {
        assertEquals("Tłuszcz", NameShortener.short("Tłuszcz", 20))
    }

    @Test
    fun `standard abbreviations from spec`() {
        assertEquals("W-wa Gdańska", NameShortener.short("Warszawa Gdańska", 14))
        assertEquals("Mińsk Maz.", NameShortener.short("Mińsk Mazowiecki", 12))
    }

    @Test
    fun `ellipsis when still too long`() {
        val s = NameShortener.short("Grodzisk Mazowiecki Jordanowice", 16)
        assertEquals(16, s.length)
        assertEquals('…', s.last())
    }

    @Test
    fun `tiny variant for small tile`() {
        assertEquals("Mińsk", NameShortener.tiny("Mińsk Mazowiecki"))
        assertEquals("Gdań.", NameShortener.tiny("Warszawa Gdańska"))
        assertEquals("Otwo.", NameShortener.tiny("Otwock"))
    }
}
