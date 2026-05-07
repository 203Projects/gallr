package com.gallr.app

interface ShareHandler {
    fun shareApp()
}

expect fun createShareHandler(): ShareHandler
