package com.gallr.shared.util

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ValidatorsTest {

    // ── Email validation ────────────────────────────────────────────────

    @Test
    fun validEmail() {
        assertTrue(Validators.isValidEmail("user@example.com"))
    }

    @Test
    fun validEmailWithSubdomain() {
        assertTrue(Validators.isValidEmail("user@mail.example.co.kr"))
    }

    @Test
    fun validEmailWithPlus() {
        assertTrue(Validators.isValidEmail("user+tag@example.com"))
    }

    @Test
    fun validEmailWithDots() {
        assertTrue(Validators.isValidEmail("first.last@example.com"))
    }

    @Test
    fun emptyEmailIsInvalid() {
        assertFalse(Validators.isValidEmail(""))
    }

    @Test
    fun blankEmailIsInvalid() {
        assertFalse(Validators.isValidEmail("   "))
    }

    @Test
    fun emailWithoutAtIsInvalid() {
        assertFalse(Validators.isValidEmail("userexample.com"))
    }

    @Test
    fun emailWithoutDomainIsInvalid() {
        assertFalse(Validators.isValidEmail("user@"))
    }

    @Test
    fun emailWithoutTldIsInvalid() {
        assertFalse(Validators.isValidEmail("user@example"))
    }

    @Test
    fun emailWithSpacesIsInvalid() {
        assertFalse(Validators.isValidEmail("user @example.com"))
    }

    // ── Password validation ─────────────────────────────────────────────

    @Test
    fun validPassword8Chars() {
        assertTrue(Validators.isValidPassword("12345678"))
    }

    @Test
    fun validPasswordLong() {
        assertTrue(Validators.isValidPassword("a very long password with spaces"))
    }

    @Test
    fun passwordExactly8CharsIsValid() {
        assertTrue(Validators.isValidPassword("abcdefgh"))
    }

    @Test
    fun password7CharsIsInvalid() {
        assertFalse(Validators.isValidPassword("abcdefg"))
    }

    @Test
    fun emptyPasswordIsInvalid() {
        assertFalse(Validators.isValidPassword(""))
    }

    @Test
    fun password1CharIsInvalid() {
        assertFalse(Validators.isValidPassword("a"))
    }
}
