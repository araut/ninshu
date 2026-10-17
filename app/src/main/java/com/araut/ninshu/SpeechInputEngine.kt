package com.araut.ninshu

interface SpeechInputEngine {
    fun start(languageTag: String)
    fun stop()
    fun cancel()
    fun shutdown()
}